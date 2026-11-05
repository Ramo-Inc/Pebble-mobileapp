#!/usr/bin/env python3
"""pebble-ring: the user's voice contact point in a Catchball room.

Receives ring transcriptions relayed by the reply server, sends them to the secretary session over
Catchball, and turns every message the secretary sends back to pebble-ring into a reply: the full
text plus a spoken summary synthesized by the local TTS. Settings are read from .env next to this
file. Standard library only.

  Run:  python3 pebble_ring.py serve
"""
import argparse
import base64
import hmac
import json
import os
import queue
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import uuid
import urllib.request
from collections import OrderedDict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ENV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
ACKS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "acks.json")
DEFAULT_JOIN_KEY = os.path.expanduser("~/Library/Application Support/session-mail/tokens/join.key")
POLL_SECONDS = 2
REQUEST_ID_MEMORY = 500
SPOKEN_PLAIN_CHARS = 80
SUMMARY_MAX_TOKENS = 4000
ACK_MIN_CONFIDENCE = 0.5
CLARIFY_MIN_CONFIDENCE = 0.8
CLARIFY_KIND = "unclear"
ACK_INSTRUCTIONS = "latest は、ユーザーがリング越しに秘書へ話しかけた最新の発話。recent（直前の会話）も踏まえると、latest はどの種類の話か"
HISTORY_TURNS = 4
HISTORY_CHARS = 200

ROLE = (
    "ユーザー（人間）との連絡口。私からの本文はユーザーが声で話した内容。"
    "ユーザーへの返事・報告・確認は pebble-ring 宛てに送る（iPhone に全文表示し、要約を読み上げる）。結論を先に書く。"
)

INTRO = """pebble-ring です。このルームでの、ユーザー（人間）との連絡口です。

- 私から届くメッセージは、ユーザーがリングに向かって話した内容の文字起こしです。言い間違いや誤変換があることがあります。
- ユーザーへの返事・報告・確認・質問は、channel_send で to: "pebble-ring" に送ってください。ユーザーの依頼に答えるときは、reply_to にその依頼の message_id を付けてください。
- 送った本文は、ユーザーの iPhone にそのまま表示され、要約した音声で読み上げられます。最初の1〜2文に結論を書いてください。
- 仕事を担当に振り分けたら受け付けたことを、終わったら結果を、判断が必要なときはその内容を、pebble-ring に知らせてください。
- 全員宛て（to: "all"）のメッセージはユーザーには届きません。ユーザーに伝えたいことは必ず pebble-ring 宛てに送ってください。"""

SUMMARY_PROMPT = (
    "<返事> の中は、AI アシスタントからユーザーへの返事です。音声で聞いてすぐわかるように、日本語の話し言葉で要点だけに要約してください。"
    "長さは内容に合わせますが、元の返事よりずっと短くし、どんな場合も150字を超えません。時刻や了解のように一言で済む答えは一言で言います。"
    "お店や方法など候補がいくつもあるときは、それぞれの名前と一番の特徴を短く並べます。"
    "報告や調べた結果は、結論と大事な数字を言います。"
    "細かい説明・経緯・あいさつ・締めの言葉は入れません。"
    "「行く前に確認してください」「情報が古い可能性があります」のような確認の呼びかけや注意書きは、元の返事にあっても必ず省きます。"
    "[確認] のような印・括弧・記号・URL は使わないでください。英字の名前はカタカナにせず元の綴りのまま、数字もそのまま書きます。"
    "返事に答えたり内容を付け足したりせず、読み上げる文だけを出力してください。"
)


def load_env(path):
    """Reads KEY=VALUE lines into os.environ without overriding variables that are already set."""
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip("'\""))


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code


def http_request(method, url, body=None, headers=None, timeout=10, raw=False):
    """JSON request helper. Returns parsed JSON (or raw bytes); raises ApiError on HTTP errors."""
    data = None
    headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
    except urllib.error.HTTPError as e:
        with e:
            text = e.read().decode("utf-8", "replace")
        try:
            err = json.loads(text).get("error", {})
        except (ValueError, AttributeError):
            err = {}
        raise ApiError(e.code, err.get("code", "HTTP_ERROR"), err.get("message", text[:200]))
    if raw:
        return payload
    return json.loads(payload) if payload else {}


class Catchball:
    """A Catchball participant driven over the broker's HTTP API."""

    def __init__(self, base_url, join_key, room, agent_id, display_name, request=http_request):
        self.base_url = base_url.rstrip("/")
        self.join_key = join_key
        self.room = room
        self.agent_id = agent_id
        self.display_name = display_name
        self.request = request
        self.token = None
        self.generation = None
        self.heartbeat_interval = 5

    def _call(self, method, path, body=None, token=None):
        return self.request(method, self.base_url + path, body, {"Authorization": f"Bearer {token or self.token}"})

    def join(self):
        rooms = self._call("GET", "/api/join/rooms", token=self.join_key)
        if self.room not in {r.get("room") for r in rooms.get("rooms", [])}:
            self._call("POST", "/api/join/rooms", {"room": self.room}, token=self.join_key)
        joined = self._call("POST", "/api/join/self", {
            "provider": "claude", "room": self.room, "agent_id": self.agent_id,
            "display_name": self.display_name, "role": ROLE,
        }, token=self.join_key)
        self.token = joined["token"]
        connected = self._call("POST", "/api/agent/connect", {
            "provider": "claude", "bridge_version": "pebble-ring", "pid": os.getpid(),
        })
        self.generation = connected["generation"]
        self.heartbeat_interval = connected.get("heartbeat_interval_ms", 5000) / 1000

    def heartbeat(self, notify_results=()):
        r = self._call("POST", "/api/agent/heartbeat", {"generation": self.generation, "notify_results": list(notify_results)})
        return r.get("notify")

    def send(self, to, body, client_id, reply_to=None):
        payload = {"to": to, "body": body, "client_message_id": client_id}
        if reply_to:
            payload["reply_to"] = reply_to
        return self._call("POST", "/api/agent/send", payload)["message_id"]

    def receive(self, limit=10):
        return self._call("POST", "/api/agent/receive", {"limit": limit})

    def members(self):
        return self._call("GET", "/api/agent/members").get("members", [])


class Summarizer:
    """Turns a reply into a short spoken summary via Anthropic or any OpenAI-compatible chat API."""

    def __init__(self, provider, api_key, model, base_url="", request=http_request):
        self.provider = provider
        self.api_key = api_key
        self.model = model
        self.base_url = base_url.rstrip("/")
        self.request = request

    def spoken(self, text):
        text = text.strip()
        if self.api_key:
            try:
                summary = self._summarize(text).strip()
                if summary:
                    return summary
            except (ApiError, urllib.error.URLError, TimeoutError, KeyError, IndexError, ValueError) as e:
                print(f"[summary] failed, reading the original instead: {e}", flush=True)
        if len(text) <= SPOKEN_PLAIN_CHARS:
            return text
        return text[:SPOKEN_PLAIN_CHARS] + "。続きは画面で確認してください。"

    def _summarize(self, text):
        if self.provider == "anthropic":
            r = self.request("POST", (self.base_url or "https://api.anthropic.com") + "/v1/messages", {
                "model": self.model, "max_tokens": SUMMARY_MAX_TOKENS, "system": SUMMARY_PROMPT,
                "output_config": {"effort": "medium"},
                "messages": [{"role": "user", "content": f"<返事>{text}</返事>"}],
            }, {"x-api-key": self.api_key, "anthropic-version": "2023-06-01"}, 30)
            return "".join(b.get("text", "") for b in r["content"] if b.get("type") == "text")
        r = self.request("POST", (self.base_url or "https://api.openai.com/v1") + "/chat/completions", {
            "model": self.model, "max_tokens": SUMMARY_MAX_TOKENS,
            "messages": [{"role": "system", "content": SUMMARY_PROMPT}, {"role": "user", "content": f"<返事>{text}</返事>"}],
        }, {"Authorization": f"Bearer {self.api_key}"}, 30)
        return r["choices"][0]["message"]["content"]


class Speech:
    """OpenAI-compatible TTS (the local AivisSpeech adapter) to M4A via macOS afconvert."""

    def __init__(self, base_url, voice="", request=http_request):
        self.base_url = base_url.rstrip("/")
        self.voice = voice
        self.request = request

    def synthesize(self, text):
        body = {"input": text, "response_format": "wav"}
        if self.voice:
            body["voice"] = self.voice
        try:
            wav = self.request("POST", self.base_url + "/v1/audio/speech", body, timeout=60, raw=True)
            with tempfile.TemporaryDirectory() as tmp:
                src, dst = os.path.join(tmp, "reply.wav"), os.path.join(tmp, "reply.m4a")
                with open(src, "wb") as f:
                    f.write(wav)
                subprocess.run(["afconvert", "-f", "m4af", "-d", "aac", src, dst], check=True, capture_output=True)
                with open(dst, "rb") as f:
                    return f.read()
        except (ApiError, urllib.error.URLError, TimeoutError, OSError, subprocess.CalledProcessError) as e:
            print(f"[tts] failed, sending text only: {e}", flush=True)
            return None


class Jev:
    """TypeSafe System One (Jev): answers a choice question about a text."""

    URL = "https://api.typesafe.ai/v1/systemone"

    def __init__(self, api_key, model="jev-latest", request=http_request):
        self.api_key = api_key
        self.model = model
        self.request = request

    def choose(self, text, instructions, options):
        r = self.request("POST", self.URL, {
            "state": text,
            "model": self.model,
            "questions": {"kind": {"type": "choice", "instructions": instructions, "criteria": options}},
        }, {"Authorization": f"Bearer {self.api_key}"}, 3)
        answer = r["answers"]["kind"]
        return answer["choice"], answer.get("confidence", 0)


class Acknowledger:
    """Picks a short acknowledgement that fits what the user said, with its audio synthesized ahead of time."""

    def __init__(self, kinds, classifier, speech):
        self.kinds = {k["id"]: k for k in kinds}
        self.classifier = classifier
        self.speech = speech
        self.audio = {}

    def prepare(self):
        for kind in self.kinds.values():
            self.audio_for(kind["phrase"])

    def classify(self, text, history=()):
        """Returns (kind id, confidence); ("default", 0.0) when no classifier answers."""
        if self.classifier:
            try:
                kind, confidence = self.classifier.choose(
                    {"recent": list(history), "latest": text}, ACK_INSTRUCTIONS,
                    {k["id"]: k["when"] for k in self.kinds.values()})
                if kind in self.kinds:
                    return kind, confidence
            except (ApiError, urllib.error.URLError, TimeoutError, KeyError, TypeError, ValueError) as e:
                print(f"[ack] classification failed: {e}", flush=True)
        return "default", 0.0

    def needs_clarification(self, kind):
        kind_id, confidence = kind
        return kind_id == CLARIFY_KIND and confidence >= CLARIFY_MIN_CONFIDENCE

    def clarification(self):
        return self.kinds[CLARIFY_KIND]["phrase"]

    def acknowledgement(self, kind):
        kind_id, confidence = kind
        if kind_id == CLARIFY_KIND or confidence < ACK_MIN_CONFIDENCE:
            kind_id = "default"
        return self.kinds[kind_id]["phrase"]

    def audio_for(self, phrase):
        if phrase not in self.audio:
            audio = self.speech.synthesize(phrase)
            if audio:
                self.audio[phrase] = audio
        return self.audio.get(phrase)


def run_in_thread(task):
    threading.Thread(target=task, daemon=True).start()


class ReplyServer:
    def __init__(self, base_url, token, request=http_request):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.request = request

    def post(self, text, request_id=None, audio=None):
        self.request("POST", self.base_url + "/replies", {
            "text": text,
            "request_id": request_id,
            "audio_base64": base64.b64encode(audio).decode("ascii") if audio else None,
        }, {"Authorization": f"Bearer {self.token}"})


class PebbleRing:
    """Moves transcriptions to the secretary and the secretary's messages back to the user."""

    def __init__(self, catchball, secretary_id, summarizer, speech, replies, acknowledger=None, run_async=run_in_thread):
        self.catchball = catchball
        self.secretary_id = secretary_id
        self.summarizer = summarizer
        self.speech = speech
        self.replies = replies
        self.acknowledger = acknowledger
        self.run_async = run_async
        self.inbox = queue.Queue()
        self.history = deque(maxlen=HISTORY_TURNS)
        self.request_ids = OrderedDict()
        self.introduced = False

    def on_stt(self, payload):
        # A stable id makes a resend after a lost response idempotent on the broker.
        self.inbox.put({**payload, "client_id": uuid.uuid4().hex})

    def request_intro(self):
        self.introduced = False

    def tick(self):
        self._introduce()
        self._forward_transcriptions()
        for message in self.catchball.receive().get("messages", []):
            if message.get("kind") == "direct":
                self._deliver(message)

    def _introduce(self):
        if self.introduced:
            return
        secretary = next((m for m in self.catchball.members() if m.get("id") == self.secretary_id), None)
        if secretary and secretary.get("membership") == "joined":
            self.catchball.send(self.secretary_id, INTRO, uuid.uuid4().hex)
            self.introduced = True
            print(f"[intro] sent to {self.secretary_id}", flush=True)

    def _forward_transcriptions(self):
        while True:
            try:
                payload = self.inbox.get_nowait()
            except queue.Empty:
                return
            if self.acknowledger and "kind" not in payload:
                payload["kind"] = self.acknowledger.classify(payload["text"], self.history)
            kind = payload.get("kind")
            if kind and self.acknowledger.needs_clarification(kind):
                print(f"[stt {payload.get('request_id') or '-'}] unclear, asked again: {payload['text']}", flush=True)
                self._record("user", payload["text"])
                self.run_async(lambda rid=payload.get("request_id"): self._say(self.acknowledger.clarification(), rid))
                continue
            try:
                message_id = self.catchball.send(self.secretary_id, f"[ユーザーの声] {payload['text']}", payload["client_id"])
            except ApiError as e:
                if e.status >= 500 or e.status == 401 or e.code == "STALE_GENERATION":
                    self.inbox.put(payload)
                    raise
                if e.code in ("UNKNOWN_RECIPIENT", "RECIPIENT_NOT_JOINED"):
                    reason = f"秘書（{self.secretary_id}）がルームにいないため、届けられませんでした。"
                else:
                    reason = f"秘書に届けられませんでした（{e.code}）。"
                print(f"[stt {payload.get('request_id') or '-'}] dropped: {e}", flush=True)
                self._tell_user(reason, payload.get("request_id"))
                continue
            self._remember(message_id, payload.get("request_id"))
            self._record("user", payload["text"])
            print(f"[stt {payload.get('request_id') or '-'}] sent to {self.secretary_id}", flush=True)
            if kind:
                self.run_async(lambda: self._say(self.acknowledger.acknowledgement(kind)))

    def _remember(self, message_id, request_id):
        self.request_ids[message_id] = request_id
        while len(self.request_ids) > REQUEST_ID_MEMORY:
            self.request_ids.popitem(last=False)

    def _record(self, speaker, text):
        self.history.append({speaker: text[:HISTORY_CHARS]})

    def _deliver(self, message):
        self._record("secretary", message["body"])
        self._tell_user(message["body"], self.request_ids.get(message.get("reply_to")))
        print(f"[reply from {message['from']['id']}] {message['body'][:60]}", flush=True)

    def _say(self, phrase, request_id=None):
        """Sends a stock phrase whose audio the acknowledger keeps ready."""
        audio = self.acknowledger.audio_for(phrase)
        if not audio:
            return
        try:
            self.replies.post(phrase, request_id, audio)
            print(f"[ack] {phrase}", flush=True)
        except (ApiError, urllib.error.URLError, TimeoutError) as e:
            print(f"[ack] failed: {e}", flush=True)

    def _tell_user(self, text, request_id=None):
        spoken = self.summarizer.spoken(text)
        print(f"[spoken] {spoken}", flush=True)
        self.replies.post(text, request_id, self.speech.synthesize(spoken))

    def run(self, stop):
        """Join, then heartbeat and poll until stop is set; rejoins after the broker or token is lost."""
        joined = False
        last_heartbeat = 0
        notify_results = []
        while not stop.is_set():
            try:
                if not joined:
                    self.catchball.join()
                    joined = True
                    self.request_intro()
                    print(f"[catchball] joined {self.catchball.room} as {self.catchball.agent_id}", flush=True)
                if time.monotonic() - last_heartbeat >= self.catchball.heartbeat_interval:
                    notify = self.catchball.heartbeat(notify_results)
                    notify_results = [{"notify_id": notify["notify_id"], "result": "written"}] if notify else []
                    last_heartbeat = time.monotonic()
                self.tick()
            except ApiError as e:
                print(f"[catchball] {e}", flush=True)
                if e.status == 401 or e.code == "STALE_GENERATION":
                    joined = False
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                print(f"[catchball] unreachable: {e}", flush=True)
                joined = False
            stop.wait(POLL_SECONDS)


def make_handler(ring, token):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, status, body):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            if token and not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {token}"):
                return self._send(401, {"error": "unauthorized"})
            if self.path == "/intro":
                ring.request_intro()
                return self._send(200, {"status": "intro queued"})
            if self.path != "/stt":
                return self._send(404, {"error": "not found"})
            try:
                payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "invalid json"})
            if not str(payload.get("text", "")).strip():
                return self._send(400, {"error": "text required"})
            ring.on_stt(payload)
            self._send(200, {"status": "queued"})

        def log_message(self, fmt, *args):
            pass

    return Handler


def cmd_serve(args):
    env = os.environ.get
    with open(os.path.expanduser(env("CATCHBALL_JOIN_KEY_FILE", DEFAULT_JOIN_KEY)), encoding="utf-8") as f:
        join_key = f.read().strip()
    speech = Speech(env("TTS_URL", "http://127.0.0.1:10102"), env("TTS_VOICE", ""))
    with open(ACKS_PATH, encoding="utf-8") as f:
        kinds = json.load(f)
    typesafe_key = env("PEBBLE_JEV_API_KEY", "")
    acknowledger = Acknowledger(kinds, Jev(typesafe_key) if typesafe_key else None, speech)
    threading.Thread(target=acknowledger.prepare, daemon=True).start()
    ring = PebbleRing(
        Catchball(env("CATCHBALL_URL", "http://127.0.0.1:47821"), join_key, env("CATCHBALL_ROOM", "voice"),
                  env("PEBBLE_RING_ID", "pebble-ring"), env("PEBBLE_RING_NAME", "Pebble リング")),
        env("SECRETARY_ID", "main"),
        Summarizer(env("SUMMARY_PROVIDER", "anthropic"), env("SUMMARY_API_KEY", ""),
                   env("SUMMARY_MODEL", "claude-haiku-5-5"), env("SUMMARY_BASE_URL", "")),
        speech,
        ReplyServer(env("REPLY_SERVER_URL", "http://127.0.0.1:8765"), env("REPLY_TOKEN", "")),
        acknowledger,
    )
    stop = threading.Event()
    threading.Thread(target=ring.run, args=(stop,), daemon=True).start()
    host, port = env("PEBBLE_RING_HOST", "127.0.0.1"), int(env("PEBBLE_RING_PORT", "8766"))
    server = ThreadingHTTPServer((host, port), make_handler(ring, env("PEBBLE_RING_TOKEN", "")))
    print(f"pebble-ring on http://{host}:{port} (room {ring.catchball.room}, secretary {ring.secretary_id})", flush=True)
    try:
        server.serve_forever()
    finally:
        stop.set()


def main():
    load_env(ENV_PATH)
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("serve").set_defaults(func=cmd_serve)
    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
