"""Run: python3 -m unittest discover tools/reply-server"""
import base64
import io
import json
import os
import socket
import tempfile
import threading
import unittest
import wave

from gemini_live import (
    ConversationHistory,
    GeminiLive,
    LiveError,
    LiveResult,
    WebSocket,
    mask_bytes,
    open_socket,
    pcm_to_wav,
)
from reply_server import ReplyStore, VoiceResponder, parse_form


def server_frame(opcode, payload, fin=True):
    """An unmasked frame, as a server sends it."""
    n = len(payload)
    first = (0x80 if fin else 0) | opcode
    if n < 126:
        header = bytes([first, n])
    elif n < 65536:
        header = bytes([first, 126]) + n.to_bytes(2, "big")
    else:
        header = bytes([first, 127]) + n.to_bytes(8, "big")
    return header + payload


def read_client_frame(sock):
    def exact(n):
        data = b""
        while len(data) < n:
            data += sock.recv(n - len(data))
        return data

    b0, b1 = exact(2)
    assert b1 & 0x80, "client frames must be masked"
    length = b1 & 0x7F
    if length == 126:
        length = int.from_bytes(exact(2), "big")
    elif length == 127:
        length = int.from_bytes(exact(8), "big")
    mask = exact(4)
    return b0 & 0x0F, mask_bytes(exact(length), mask)


class WebSocketTest(unittest.TestCase):
    def setUp(self):
        self.client_sock, self.server_sock = socket.socketpair()
        self.ws = WebSocket(self.client_sock)

    def tearDown(self):
        self.client_sock.close()
        self.server_sock.close()

    def test_sends_masked_text_of_every_length_class(self):
        for size in (5, 300, 70000):
            text = "あ" * size
            sender = threading.Thread(target=self.ws.send_json, args=({"t": text},))
            sender.start()
            opcode, payload = read_client_frame(self.server_sock)
            sender.join()
            self.assertEqual(0x1, opcode)
            self.assertEqual({"t": text}, json.loads(payload))

    def test_reassembles_fragments_and_answers_pings(self):
        self.server_sock.sendall(
            server_frame(0x2, b'{"a":', fin=False) + server_frame(0x9, b"hi") + server_frame(0x0, b"1}")
        )
        self.assertEqual({"a": 1}, self.ws.recv_json())
        self.assertEqual((0xA, b"hi"), read_client_frame(self.server_sock))

    def test_close_frame_raises_with_reason(self):
        self.server_sock.sendall(server_frame(0x8, (1008).to_bytes(2, "big") + b"bad model"))
        with self.assertRaisesRegex(LiveError, "1008.*bad model"):
            self.ws.recv()


class OpenSocketTest(unittest.TestCase):
    def test_tunnels_through_the_environment_proxy(self):
        proxy = socket.socket()
        proxy.bind(("127.0.0.1", 0))
        proxy.listen(1)
        requests = []

        def serve():
            conn, _ = proxy.accept()
            data = b""
            while b"\r\n\r\n" not in data:
                data += conn.recv(4096)
            requests.append(data.decode())
            conn.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
            conn.sendall(b"tunnel-ok")
            conn.close()

        threading.Thread(target=serve, daemon=True).start()
        saved = {k: os.environ.pop(k, None) for k in ("https_proxy", "HTTPS_PROXY", "no_proxy", "NO_PROXY")}
        os.environ["https_proxy"] = f"http://127.0.0.1:{proxy.getsockname()[1]}"
        try:
            sock = open_socket("live.example.com", 443, "https", timeout=5)
            self.assertEqual(b"tunnel-ok", sock.recv(100))
            sock.close()
        finally:
            os.environ.pop("https_proxy", None)
            for k, v in saved.items():
                if v is not None:
                    os.environ[k] = v
            proxy.close()
        self.assertTrue(requests[0].startswith("CONNECT live.example.com:443 HTTP/1.1"))


class FakeLiveSocket:
    def __init__(self, messages):
        self.messages = [json.dumps(m) for m in messages]
        self.sent = []
        self.closed = False

    def send_json(self, obj):
        self.sent.append(obj)

    def recv_json(self):
        return json.loads(self.messages.pop(0))

    def close(self):
        self.closed = True


class GeminiLiveTest(unittest.TestCase):
    def live(self, messages):
        self.socket = FakeLiveSocket(messages)
        self.urls = []

        def connect(url, timeout):
            self.urls.append(url)
            return self.socket

        return GeminiLive("KEY", "gemini-live-test", system_prompt="SYS", voice="Kore", connect=connect)

    def test_sends_the_recording_between_activity_markers_and_collects_the_reply(self):
        pcm = b"\x01\x00" * 20000  # 1.25 s, so two audio chunks
        live = self.live([
            {"setupComplete": {}},
            {"serverContent": {"inputTranscription": {"text": "今日の"}}},
            {"serverContent": {"inputTranscription": {"text": "天気は？"}}},
            {"serverContent": {"modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": base64.b64encode(b"AB").decode()}}]},
                               "outputTranscription": {"text": "晴れ"}}},
            {"serverContent": {"modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": base64.b64encode(b"CD").decode()}}]},
                               "outputTranscription": {"text": "です。"}}},
            {"serverContent": {"turnComplete": True}},
        ])

        result = live.respond(pcm, [{"user": "こんにちは", "assistant": "どうも"}])

        self.assertEqual(LiveResult("今日の天気は？", "晴れです。", b"ABCD", 24000), result)
        self.assertIn("key=KEY", self.urls[0])
        setup = self.socket.sent[0]["setup"]
        self.assertEqual("models/gemini-live-test", setup["model"])
        self.assertEqual(["AUDIO"], setup["generationConfig"]["responseModalities"])
        self.assertEqual("Kore", setup["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"])
        self.assertTrue(setup["realtimeInputConfig"]["automaticActivityDetection"]["disabled"])
        prompt = setup["systemInstruction"]["parts"][0]["text"]
        self.assertTrue(prompt.startswith("SYS"))
        self.assertIn("ユーザー: こんにちは", prompt)
        self.assertIn("あなた: どうも", prompt)
        inputs = [m["realtimeInput"] for m in self.socket.sent[1:]]
        self.assertEqual({"activityStart": {}}, inputs[0])
        self.assertEqual({"activityEnd": {}}, inputs[-1])
        sent_audio = b"".join(base64.b64decode(i["audio"]["data"]) for i in inputs[1:-1])
        self.assertEqual(pcm, sent_audio)
        self.assertEqual(2, len(inputs) - 2)
        self.assertTrue(self.socket.closed)

    def test_error_message_raises_and_closes(self):
        live = self.live([{"error": {"message": "quota"}}])
        with self.assertRaisesRegex(LiveError, "quota"):
            live.respond(b"\x00\x00")
        self.assertTrue(self.socket.closed)


class ConversationHistoryTest(unittest.TestCase):
    def test_keeps_the_latest_turns_across_reloads(self):
        path = os.path.join(tempfile.mkdtemp(), "conversation.jsonl")
        history = ConversationHistory(path, max_turns=2)
        for i in range(3):
            history.append(f"q{i}", f"a{i}")
        self.assertEqual([{"user": "q1", "assistant": "a1"}, {"user": "q2", "assistant": "a2"}], history.turns())
        self.assertEqual(history.turns(), ConversationHistory(path, max_turns=2).turns())
        history.clear()
        self.assertEqual([], ConversationHistory(path).turns())


class FakeLive:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error
        self.calls = []

    def respond(self, pcm, history):
        self.calls.append((pcm, history))
        if self.error:
            raise self.error
        return self.result


class VoiceResponderTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.mkdtemp()
        self.store = ReplyStore(os.path.join(tmp, "replies.jsonl"))
        self.history = ConversationHistory(os.path.join(tmp, "conversation.jsonl"))
        self.history.append("前の質問", "前の答え")

    def responder(self, live):
        return VoiceResponder(self.store, live, self.history, decode=lambda audio: b"PCM:" + audio)

    def test_reply_is_stored_as_wav_and_added_to_history(self):
        live = FakeLive(LiveResult("今の質問", "今の答え", b"\x00\x01" * 10, 24000))
        reply = self.responder(live).respond({"audio": b"m4a", "request_id": "d1"})

        self.assertEqual((b"PCM:m4a", [{"user": "前の質問", "assistant": "前の答え"}]), live.calls[0])
        self.assertEqual(("今の答え", "d1"), (reply["text"], reply["request_id"]))
        with wave.open(io.BytesIO(base64.b64decode(reply["audio_base64"]))) as w:
            self.assertEqual((24000, 1, 2, 10), (w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()))
        self.assertEqual({"user": "今の質問", "assistant": "今の答え"}, self.history.turns()[-1])

    def test_failure_leaves_a_text_reply_and_no_history(self):
        reply_count = len(self.store.after(None))
        self.responder(FakeLive(error=LiveError("quota"))).respond({"audio": b"m4a", "request_id": "d1"})

        replies = self.store.after(None)
        self.assertEqual(reply_count + 1, len(replies))
        self.assertIn("quota", replies[-1]["text"])
        self.assertIsNone(replies[-1]["audio_base64"])
        self.assertEqual(1, len(self.history.turns()))

    def test_same_delivery_is_queued_once(self):
        responder = self.responder(FakeLive())
        self.assertTrue(responder.enqueue({"audio": b"a", "request_id": "d1"}))
        self.assertFalse(responder.enqueue({"audio": b"a", "request_id": "d1"}))


class ParseFormTest(unittest.TestCase):
    def test_returns_audio_bytes(self):
        from test_reply_server import BOUNDARY, webhook_body

        fields, files = parse_form(f"multipart/form-data; boundary={BOUNDARY}", webhook_body())
        self.assertEqual(b"\x00\x01\xff", files["audio"])
        self.assertEqual("明日の会議を確認して", fields["transcription"])


class PcmToWavTest(unittest.TestCase):
    def test_round_trips(self):
        with wave.open(io.BytesIO(pcm_to_wav(b"\x01\x02\x03\x04", 16000))) as w:
            self.assertEqual(b"\x01\x02\x03\x04", w.readframes(2))


if __name__ == "__main__":
    unittest.main()
