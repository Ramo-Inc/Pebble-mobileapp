"""Run: python3 -m unittest discover tools/skill/pebble-ring"""
import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from pebble_ring import INTRO, SPOKEN_PLAIN_CHARS, ApiError, PebbleRing, Summarizer, make_handler


class FakeCatchball:
    def __init__(self, members=(), send_error=None):
        self.member_list = list(members)
        self.send_error = send_error
        self.sent = []
        self.inbound = []
        self.client_ids = []

    def members(self):
        return self.member_list

    def send(self, to, body, client_id, reply_to=None):
        self.client_ids.append(client_id)
        if self.send_error and body != INTRO:
            raise self.send_error
        self.sent.append((to, body))
        return f"m{len(self.sent)}"

    def receive(self, limit=10):
        messages, self.inbound = self.inbound, []
        return {"messages": messages}


class FakeSpeech:
    def __init__(self):
        self.spoken = []

    def synthesize(self, text):
        self.spoken.append(text)
        return b"m4a"


class FakeReplies:
    def __init__(self):
        self.posted = []

    def post(self, text, request_id=None, audio=None):
        self.posted.append((text, request_id, audio))


SECRETARY = {"id": "main", "membership": "joined"}


def message(body, reply_to=None, kind="direct"):
    return {"message_id": "x", "from": {"id": "main"}, "kind": kind, "reply_to": reply_to, "body": body}


class PebbleRingTest(unittest.TestCase):
    def ring(self, catchball):
        self.speech, self.replies = FakeSpeech(), FakeReplies()
        return PebbleRing(catchball, "main", Summarizer("anthropic", "", "m"), self.speech, self.replies)

    def test_introduces_itself_once_when_the_secretary_is_in_the_room(self):
        cb = FakeCatchball(members=[SECRETARY])
        ring = self.ring(cb)
        ring.tick()
        ring.tick()
        self.assertEqual([("main", INTRO)], cb.sent)

    def test_waits_to_introduce_until_the_secretary_joins(self):
        cb = FakeCatchball()
        ring = self.ring(cb)
        ring.tick()
        self.assertEqual([], cb.sent)
        cb.member_list = [SECRETARY]
        ring.tick()
        self.assertEqual(INTRO, cb.sent[0][1])

    def test_forwards_transcription_and_links_the_reply_to_its_recording(self):
        cb = FakeCatchball(members=[SECRETARY])
        ring = self.ring(cb)
        ring.on_stt({"text": "牛乳を買うリマインダー", "request_id": "rec-1"})
        ring.tick()
        self.assertEqual(("main", "[ユーザーの声] 牛乳を買うリマインダー"), cb.sent[1])

        cb.inbound = [message("了解しました。", reply_to="m2")]
        ring.tick()
        self.assertEqual([("了解しました。", "rec-1", b"m4a")], self.replies.posted)
        self.assertEqual(["了解しました。"], self.speech.spoken)

    def test_long_reply_is_shown_in_full_but_spoken_shortened(self):
        ring = self.ring(FakeCatchball(members=[SECRETARY]))
        long_text = "あ" * (SPOKEN_PLAIN_CHARS + 50)
        ring.catchball.inbound = [message(long_text)]
        ring.tick()
        self.assertEqual(long_text, self.replies.posted[0][0])
        self.assertLess(len(self.speech.spoken[0]), len(long_text))

    def test_broadcasts_are_not_relayed_to_the_user(self):
        ring = self.ring(FakeCatchball(members=[SECRETARY]))
        ring.catchball.inbound = [message("全員へ共有", kind="broadcast")]
        ring.tick()
        self.assertEqual([], self.replies.posted)

    def test_tells_the_user_when_the_secretary_is_missing(self):
        cb = FakeCatchball(send_error=ApiError(404, "UNKNOWN_RECIPIENT", "no"))
        ring = self.ring(cb)
        ring.on_stt({"text": "hello", "request_id": "rec-1"})
        ring.tick()
        self.assertEqual("rec-1", self.replies.posted[0][1])
        self.assertIn("main", self.replies.posted[0][0])

    def test_transient_send_failure_is_retried_with_the_same_client_id(self):
        cb = FakeCatchball(members=[SECRETARY], send_error=ApiError(503, "UNAVAILABLE", "busy"))
        ring = self.ring(cb)
        ring.on_stt({"text": "hello"})
        with self.assertRaises(ApiError):
            ring.tick()
        cb.send_error = None
        ring.tick()
        self.assertEqual("[ユーザーの声] hello", cb.sent[-1][1])
        self.assertEqual(cb.client_ids[-2], cb.client_ids[-1])

    def test_rejected_transcription_is_dropped_and_reported(self):
        cb = FakeCatchball(members=[SECRETARY], send_error=ApiError(400, "INVALID_ARGUMENT", "bad"))
        ring = self.ring(cb)
        ring.on_stt({"text": "hello"})
        ring.tick()
        self.assertEqual(0, ring.inbox.qsize())
        self.assertIn("INVALID_ARGUMENT", self.replies.posted[0][0])


class SummarizerTest(unittest.TestCase):
    long_text = "長い返事です。" * 30

    def capture(self, response):
        calls = []

        def request(method, url, body=None, headers=None, timeout=10, raw=False):
            calls.append((url, body, headers))
            return response

        return calls, request

    def test_short_text_is_spoken_as_is_without_calling_the_api(self):
        calls, request = self.capture({})
        self.assertEqual("短い", Summarizer("anthropic", "key", "m", request=request).spoken("短い"))
        self.assertEqual([], calls)

    def test_anthropic_messages_api(self):
        calls, request = self.capture({"content": [{"type": "text", "text": "要約です。"}]})
        s = Summarizer("anthropic", "key", "claude-haiku-5-5", request=request)
        self.assertEqual("要約です。", s.spoken(self.long_text))
        url, body, headers = calls[0]
        self.assertEqual("https://api.anthropic.com/v1/messages", url)
        self.assertEqual(("claude-haiku-5-5", "key"), (body["model"], headers["x-api-key"]))

    def test_openai_compatible_api_with_custom_base_url(self):
        calls, request = self.capture({"choices": [{"message": {"content": "要約です。"}}]})
        s = Summarizer("openai", "key", "deepseek-chat", "https://api.deepseek.com", request=request)
        self.assertEqual("要約です。", s.spoken(self.long_text))
        url, body, headers = calls[0]
        self.assertEqual("https://api.deepseek.com/chat/completions", url)
        self.assertEqual("Bearer key", headers["Authorization"])

    def test_falls_back_to_the_beginning_when_the_api_fails(self):
        def failing(*args, **kwargs):
            raise ApiError(500, "ERR", "down")

        spoken = Summarizer("openai", "key", "m", request=failing).spoken(self.long_text)
        self.assertTrue(spoken.startswith(self.long_text[:SPOKEN_PLAIN_CHARS]))
        self.assertIn("続きは画面", spoken)


class HandlerTest(unittest.TestCase):
    def setUp(self):
        self.ring = PebbleRing(FakeCatchball(), "main", Summarizer("anthropic", "", "m"), FakeSpeech(), FakeReplies())
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.ring, "tok"))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def post(self, path, body, token="tok"):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.server.server_port}{path}", data=json.dumps(body).encode(), method="POST",
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        )
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status
        except urllib.error.HTTPError as e:
            with e:
                return e.code

    def test_accepts_relayed_transcription(self):
        self.assertEqual(200, self.post("/stt", {"text": "hello", "request_id": "r"}))
        self.assertEqual("hello", self.ring.inbox.get_nowait()["text"])

    def test_rejects_wrong_token_and_empty_text(self):
        self.assertEqual(401, self.post("/stt", {"text": "hello"}, token="bad"))
        self.assertEqual(400, self.post("/stt", {"text": " "}))

    def test_intro_endpoint_resends_the_introduction(self):
        self.ring.introduced = True
        self.assertEqual(200, self.post("/intro", {}))
        self.assertFalse(self.ring.introduced)


if __name__ == "__main__":
    unittest.main()
