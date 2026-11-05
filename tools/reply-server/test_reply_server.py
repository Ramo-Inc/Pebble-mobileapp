"""Run: python3 -m unittest discover tools/reply-server"""
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from reply_server import Relay, RelayError, ReplyStore, VoiceResponder, load_env, make_handler, parse_multipart

BOUNDARY = "b0undary"


def webhook_body(transcription="明日の会議を確認して", recorded_at="1793786333807", with_audio=True):
    crlf = "\r\n"
    parts = []
    if with_audio:
        parts.append(
            f"--{BOUNDARY}{crlf}Content-Disposition: form-data; name=\"audio\"; filename=\"1.m4a\"{crlf}"
            f"Content-Type: audio/mp4{crlf}{crlf}".encode() + b"\x00\x01\xff" + crlf.encode()
        )
    if transcription is not None:
        parts.append(f"--{BOUNDARY}{crlf}Content-Disposition: form-data; name=\"transcription\"{crlf}{crlf}{transcription}{crlf}".encode())
    parts.append(f"--{BOUNDARY}{crlf}Content-Disposition: form-data; name=\"recordedAt\"{crlf}{crlf}{recorded_at}{crlf}".encode())
    parts.append(f"--{BOUNDARY}{crlf}Content-Disposition: form-data; name=\"client\"{crlf}{crlf}ring{crlf}--{BOUNDARY}--{crlf}".encode())
    return b"".join(parts)


class ParseMultipartTest(unittest.TestCase):
    def test_reads_text_fields_and_skips_audio(self):
        fields = parse_multipart(f"multipart/form-data; boundary={BOUNDARY}", webhook_body())
        self.assertEqual(
            {"transcription": "明日の会議を確認して", "recordedAt": "1793786333807", "client": "ring"}, fields
        )


class LoadEnvTest(unittest.TestCase):
    def test_does_not_override_existing_variables(self):
        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as f:
            f.write("# comment\nRS_TEST_A=from-file\nRS_TEST_B='quoted'\n")
        os.environ["RS_TEST_A"] = "from-shell"
        try:
            load_env(f.name)
            self.assertEqual("from-shell", os.environ["RS_TEST_A"])
            self.assertEqual("quoted", os.environ["RS_TEST_B"])
        finally:
            os.environ.pop("RS_TEST_A", None)
            os.environ.pop("RS_TEST_B", None)
            os.unlink(f.name)


class RelayTest(unittest.TestCase):
    def relay(self, outcomes):
        calls = []

        def send(url, token, payload):
            calls.append(payload)
            outcome = outcomes[len(calls) - 1]
            if outcome is not None:
                raise outcome

        return Relay("http://agent", "tok", send=send, sleep=lambda _: None), calls

    def test_same_delivery_is_queued_once(self):
        relay, _ = self.relay([])
        self.assertTrue(relay.enqueue({"request_id": "d1"}))
        self.assertFalse(relay.enqueue({"request_id": "d1"}))
        self.assertEqual(1, relay.queue.qsize())

    def test_transient_failure_is_retried_three_times_then_dropped(self):
        relay, calls = self.relay([RelayError("down", retryable=True)] * 4)
        self.assertFalse(relay.deliver({"request_id": "d1"}))
        self.assertEqual(4, len(calls))

    def test_recovers_on_a_retry(self):
        relay, calls = self.relay([RelayError("down", retryable=True), None])
        self.assertTrue(relay.deliver({"request_id": "d1"}))
        self.assertEqual(2, len(calls))

    def test_client_error_is_not_retried(self):
        relay, calls = self.relay([RelayError("HTTP 401", retryable=False)])
        self.assertFalse(relay.deliver({"request_id": "d1"}))
        self.assertEqual(1, len(calls))


class WebhookEndpointTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.relayed = []
        self.relay = Relay("http://agent", "tok", send=lambda url, token, payload: self.relayed.append(payload))
        handler = make_handler(ReplyStore(os.path.join(self.tmp, "replies.jsonl")), "secret", self.relay)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def post(self, body, content_type=f"multipart/form-data; boundary={BOUNDARY}", token="secret", **headers):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.server.server_port}/webhook",
            data=body,
            method="POST",
            headers={"Content-Type": content_type, "Authorization": f"Bearer {token}", **headers},
        )
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, json.load(resp)
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.load(e)

    def test_queues_transcription_for_relay(self):
        status, body = self.post(
            webhook_body(), **{"X-Index-Delivery": "d1", "X-Index-Trigger": "single-click-hold"}
        )
        self.assertEqual((200, {"status": "queued"}), (status, body))
        self.relay.deliver(self.relay.queue.get_nowait())
        self.assertEqual(
            [{"text": "明日の会議を確認して", "request_id": "d1", "recorded_at": 1793786333807, "trigger": "single-click-hold"}],
            self.relayed,
        )

    def test_resent_delivery_is_not_queued_again(self):
        self.post(webhook_body(), **{"X-Index-Delivery": "d1"})
        self.assertEqual((200, {"status": "duplicate"}), self.post(webhook_body(), **{"X-Index-Delivery": "d1"}))

    def test_ignores_test_events_recordings_without_text_and_button_events(self):
        self.assertEqual("ignored test event", self.post(webhook_body(), **{"X-Index-Test": "true"})[1]["status"])
        self.assertEqual("no transcription", self.post(webhook_body(transcription=None))[1]["status"])
        self.assertEqual("no transcription", self.post(b'{"gesture":"click"}', content_type="application/json")[1]["status"])
        self.assertEqual(0, self.relay.queue.qsize())

    def test_requires_the_reply_token(self):
        self.assertEqual(401, self.post(webhook_body(), token="wrong")[0])


class WebhookVoiceRoutingTest(WebhookEndpointTest):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.relayed = []
        self.relay = Relay("http://agent", "tok", send=lambda url, token, payload: self.relayed.append(payload))
        store = ReplyStore(os.path.join(self.tmp, "replies.jsonl"))
        self.voice = VoiceResponder(store, live=None, history=None)
        handler = make_handler(store, "secret", self.relay, self.voice)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def test_recording_goes_to_voice_not_relay(self):
        status, body = self.post(webhook_body(transcription=None), **{"X-Index-Delivery": "d1"})
        self.assertEqual((200, {"status": "voice queued"}), (status, body))
        queued = self.voice.queue.get_nowait()
        self.assertEqual((b"\x00\x01\xff", "d1"), (queued["audio"], queued["request_id"]))
        self.assertEqual(0, self.relay.queue.qsize())

    def test_recording_with_transcription_goes_to_voice_only(self):
        self.assertEqual("voice queued", self.post(webhook_body(), **{"X-Index-Delivery": "d1"})[1]["status"])
        self.assertEqual("明日の会議を確認して", self.voice.queue.get_nowait()["text"])
        self.assertEqual(0, self.relay.queue.qsize())

    def test_transcription_only_still_goes_to_relay(self):
        self.assertEqual("queued", self.post(webhook_body(with_audio=False), **{"X-Index-Delivery": "d1"})[1]["status"])
        self.assertEqual(0, self.voice.queue.qsize())

    def test_resent_recording_is_not_queued_again(self):
        self.post(webhook_body(), **{"X-Index-Delivery": "d1"})
        self.assertEqual("duplicate", self.post(webhook_body(), **{"X-Index-Delivery": "d1"})[1]["status"])

    # Inherited tests that assume recordings reach the relay do not apply here.
    test_queues_transcription_for_relay = None
    test_resent_delivery_is_not_queued_again = None
    test_ignores_test_events_recordings_without_text_and_button_events = None


if __name__ == "__main__":
    unittest.main()
