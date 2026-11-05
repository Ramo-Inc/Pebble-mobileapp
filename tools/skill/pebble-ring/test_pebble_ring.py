"""Run: python3 -m unittest discover tools/skill/pebble-ring"""
import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from pebble_ring import ACKS_PATH, INTRO, SPOKEN_PLAIN_CHARS, Acknowledger, ApiError, Jev, PebbleRing, Summarizer, make_handler


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

    def ring_with_ack(self, classifier):
        self.speech, self.replies = FakeSpeech(), FakeReplies()
        self.catchball = FakeCatchball(members=[SECRETARY])
        return PebbleRing(self.catchball, "main", Summarizer("anthropic", "", "m"), self.speech, self.replies,
                          acknowledger=Acknowledger(KINDS, classifier, self.speech), run_async=lambda task: task())

    def test_acknowledges_a_forwarded_transcription_right_away(self):
        ring = self.ring_with_ack(FakeClassifier())
        ring.on_stt({"text": "近くのフレンチを教えて", "request_id": "rec-1"})
        ring.tick()
        self.assertEqual("[ユーザーの声] 近くのフレンチを教えて", self.catchball.sent[-1][1])
        self.assertEqual([("いいところを探してみますね。", None, b"m4a")], self.replies.posted)

    def test_asks_again_instead_of_forwarding_unclear_speech(self):
        ring = self.ring_with_ack(FakeClassifier(choice="unclear", confidence=0.9))
        ring.on_stt({"text": "フィリピンダバオに", "request_id": "rec-1"})
        ring.tick()
        self.assertEqual([("main", INTRO)], self.catchball.sent)
        self.assertEqual([("それって何ですか？", "rec-1", b"m4a")], self.replies.posted)

    def test_passes_the_last_two_exchanges_to_the_classifier(self):
        classifier = FakeClassifier()
        ring = self.ring_with_ack(classifier)
        for i in range(3):
            ring.on_stt({"text": f"質問{i}"})
            ring.tick()
            self.catchball.inbound = [message(f"返事{i}")]
            ring.tick()
        ring.on_stt({"text": "最後"})
        ring.tick()
        self.assertEqual(
            [{"user": "質問1"}, {"secretary": "返事1"}, {"user": "質問2"}, {"secretary": "返事2"}],
            classifier.asked[-1][0]["recent"])

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


KINDS = [
    {"id": "place", "when": "お店を探している", "phrase": "いいところを探してみますね。"},
    {"id": "unclear", "when": "意味が取れない", "phrase": "それって何ですか？"},
    {"id": "default", "when": "どれにも当てはまらない", "phrase": "はい、少々お待ちください。"},
]


class FakeClassifier:
    def __init__(self, choice="place", confidence=0.9, error=None):
        self.result = (choice, confidence)
        self.error = error
        self.asked = []

    def choose(self, state, instructions, options):
        self.asked.append((state, options))
        if self.error:
            raise self.error
        return self.result


class AcknowledgerTest(unittest.TestCase):
    def test_classifies_the_latest_utterance_with_the_recent_conversation(self):
        classifier = FakeClassifier()
        ack = Acknowledger(KINDS, classifier, FakeSpeech())
        self.assertEqual(("place", 0.9), ack.classify("そこ予約して", [{"user": "フレンチを教えて"}]))
        state, options = classifier.asked[0]
        self.assertEqual({"recent": [{"user": "フレンチを教えて"}], "latest": "そこ予約して"}, state)
        self.assertEqual("お店を探している", options["place"])

    def test_classification_failures_count_as_default(self):
        ack = Acknowledger(KINDS, FakeClassifier(error=ApiError(500, "E", "down")), FakeSpeech())
        self.assertEqual(("default", 0.0), ack.classify("x"))
        self.assertEqual(("default", 0.0), Acknowledger(KINDS, FakeClassifier(choice="nope"), FakeSpeech()).classify("x"))
        self.assertEqual(("default", 0.0), Acknowledger(KINDS, None, FakeSpeech()).classify("x"))

    def test_acknowledgement_falls_back_to_default_when_unsure_or_unclear(self):
        ack = Acknowledger(KINDS, None, FakeSpeech())
        self.assertEqual("いいところを探してみますね。", ack.acknowledgement(("place", 0.9)))
        self.assertEqual("はい、少々お待ちください。", ack.acknowledgement(("place", 0.2)))
        self.assertEqual("はい、少々お待ちください。", ack.acknowledgement(("unclear", 0.6)))

    def test_only_confident_unclear_speech_needs_clarification(self):
        ack = Acknowledger(KINDS, None, FakeSpeech())
        self.assertTrue(ack.needs_clarification(("unclear", 0.9)))
        self.assertFalse(ack.needs_clarification(("unclear", 0.6)))
        self.assertFalse(ack.needs_clarification(("place", 1.0)))

    def test_synthesizes_each_phrase_once(self):
        speech = FakeSpeech()
        ack = Acknowledger(KINDS, None, speech)
        ack.prepare()
        self.assertEqual(b"m4a", ack.audio_for("はい、少々お待ちください。"))
        self.assertEqual([k["phrase"] for k in KINDS], speech.spoken)

    def test_shipped_kinds_are_valid(self):
        with open(ACKS_PATH, encoding="utf-8") as f:
            kinds = json.load(f)
        ids = [k["id"] for k in kinds]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue({"default", "unclear"} <= set(ids))
        self.assertTrue(all(k["when"] and k["phrase"] for k in kinds))


class JevTest(unittest.TestCase):
    def test_asks_a_choice_question(self):
        calls = []

        def request(method, url, body=None, headers=None, timeout=10, raw=False):
            calls.append((url, body, headers))
            return {"answers": {"kind": {"type": "choice", "choice": "place", "confidence": 0.8}}}

        result = Jev("key", request=request).choose("フレンチを教えて", "どの種類か", {"place": "お店"})
        self.assertEqual(("place", 0.8), result)
        url, body, headers = calls[0]
        self.assertEqual("https://api.typesafe.ai/v1/systemone", url)
        self.assertEqual("Bearer key", headers["Authorization"])
        self.assertEqual({"type": "choice", "instructions": "どの種類か", "criteria": {"place": "お店"}}, body["questions"]["kind"])
        self.assertEqual("フレンチを教えて", body["state"])


class SummarizerTest(unittest.TestCase):
    long_text = "長い返事です。" * 30

    def capture(self, response):
        calls = []

        def request(method, url, body=None, headers=None, timeout=10, raw=False):
            calls.append((url, body, headers))
            return response

        return calls, request

    def test_short_text_is_also_rewritten_for_speech(self):
        calls, request = self.capture({"content": [{"type": "text", "text": "今は午前4時48分です。"}]})
        s = Summarizer("anthropic", "key", "m", request=request)
        self.assertEqual("今は午前4時48分です。", s.spoken("[確認] 今は 午前4時48分（PST）です。"))
        self.assertEqual(1, len(calls))

    def test_short_text_is_spoken_as_is_without_an_api_key(self):
        calls, request = self.capture({})
        self.assertEqual("短い", Summarizer("anthropic", "", "m", request=request).spoken("短い"))
        self.assertEqual([], calls)

    def test_short_text_is_spoken_as_is_when_the_api_fails(self):
        def failing(*args, **kwargs):
            raise ApiError(500, "ERR", "down")

        self.assertEqual("短い", Summarizer("anthropic", "key", "m", request=failing).spoken("短い"))

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
