#!/usr/bin/env python3
"""Minimal reply server for the Pebble app's "Reply audio" feature.

The agent pushes replies (text + optional audio); the iPhone polls GET /replies and auto-plays them.
Optionally receives the app's Index webhook at POST /webhook. A recording (audio part) is answered
right away by Gemini Live when GEMINI_API_KEY is set; a transcription alone is relayed as JSON to
RELAY_URL. Settings are read from .env next to this file. Standard library only.

  Run the server:   python3 reply_server.py serve [--token SECRET] [--port 8765]
  Push a reply:     python3 reply_server.py push --token SECRET --text "了解" [--audio reply.m4a | --say]
                                              [--request-id <X-Index-Delivery>]
  Forget the voice conversation:  python3 reply_server.py clear-history
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
import urllib.request
from collections import OrderedDict
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from gemini_live import (
    DEFAULT_LIVE_MODEL,
    DEFAULT_SYSTEM_PROMPT,
    ConversationHistory,
    GeminiLive,
    decode_to_pcm16k,
    pcm_to_wav,
)

MAX_REPLIES = 200
MAX_AUDIO_BYTES = 5 * 1024 * 1024
MAX_BODY_BYTES = MAX_AUDIO_BYTES * 2
ENV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
RELAY_RETRY_DELAYS = (5, 15, 30)
RELAY_TIMEOUT = 10
RELAY_DEDUP_SIZE = 1000
HERE = os.path.dirname(os.path.abspath(__file__))
HISTORY_PATH = os.path.join(HERE, "conversation.jsonl")


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


def parse_form(content_type, body):
    """Returns ({name: text}, {name: bytes}) for the text fields and file parts of a multipart body."""
    message = BytesParser(policy=HTTP).parsebytes(
        f"Content-Type: {content_type}\r\n\r\n".encode("latin-1") + body
    )
    fields, files = {}, {}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if not name:
            continue
        if part.get_filename() is None:
            fields[name] = part.get_payload(decode=True).decode("utf-8")
        else:
            files[name] = part.get_payload(decode=True)
    return fields, files


def parse_multipart(content_type, body):
    """Returns the text fields of a multipart/form-data body as {name: value}."""
    return parse_form(content_type, body)[0]


class RelayError(Exception):
    def __init__(self, message, retryable):
        super().__init__(message)
        self.retryable = retryable


def post_json(url, token, payload):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), headers=headers, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=RELAY_TIMEOUT):
            pass
    except urllib.error.HTTPError as e:
        raise RelayError(f"HTTP {e.code}", retryable=e.code in (408, 429) or e.code >= 500)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        raise RelayError(str(getattr(e, "reason", e)), retryable=True)


class Relay:
    """Forwards transcriptions to the relay URL one at a time, in arrival order, in memory only."""

    def __init__(self, url, token, send=post_json, sleep=time.sleep, retry_delays=RELAY_RETRY_DELAYS):
        self.url = url
        self.token = token
        self.send = send
        self.sleep = sleep
        self.retry_delays = retry_delays
        self.queue = queue.Queue()
        self.seen = OrderedDict()
        self.lock = threading.Lock()

    def start(self):
        threading.Thread(target=self._run, daemon=True).start()

    def enqueue(self, payload):
        """Returns False when this delivery was already accepted (the app re-sent it)."""
        delivery = payload.get("request_id")
        with self.lock:
            if delivery:
                if delivery in self.seen:
                    return False
                self.seen[delivery] = True
                if len(self.seen) > RELAY_DEDUP_SIZE:
                    self.seen.popitem(last=False)
        self.queue.put(payload)
        return True

    def _run(self):
        while True:
            self.deliver(self.queue.get())

    def deliver(self, payload):
        label = payload.get("request_id") or "-"
        for attempt in range(len(self.retry_delays) + 1):
            try:
                self.send(self.url, self.token, payload)
                print(f"[relay {label}] delivered", flush=True)
                return True
            except RelayError as e:
                if not e.retryable or attempt == len(self.retry_delays):
                    print(f"[relay {label}] dropped after {attempt + 1} attempt(s): {e}", flush=True)
                    return False
                print(f"[relay {label}] attempt {attempt + 1} failed: {e}", flush=True)
                self.sleep(self.retry_delays[attempt])


class VoiceResponder:
    """Answers recordings with Gemini Live one at a time, so each reply sees the previous exchange."""

    def __init__(self, store, live, history, decode=decode_to_pcm16k, streams=None):
        self.store = store
        self.streams = streams or AudioStreams()
        self.live = live
        self.history = history
        self.decode = decode
        self.queue = queue.Queue()
        self.seen = OrderedDict()
        self.lock = threading.Lock()

    def start(self):
        threading.Thread(target=self._run, daemon=True).start()

    def enqueue(self, payload):
        """Returns False when this delivery was already accepted (the app re-sent it)."""
        delivery = payload.get("request_id")
        with self.lock:
            if delivery:
                if delivery in self.seen:
                    return False
                self.seen[delivery] = True
                if len(self.seen) > RELAY_DEDUP_SIZE:
                    self.seen.popitem(last=False)
        self.queue.put(payload)
        return True

    def _run(self):
        while True:
            self.respond(self.queue.get())

    def respond(self, payload):
        label = payload.get("request_id") or "-"
        request_id = payload.get("request_id")
        started = time.monotonic()
        streaming = None

        def on_audio(chunk, rate):
            nonlocal streaming
            if streaming is None:
                streaming = self.store.add("", request_id, None, sample_rate=rate)
                self.streams.open(streaming["id"], rate)
                print(f"[voice {label}] reply {streaming['id']} streaming after {time.monotonic() - started:.1f}s", flush=True)
            self.streams.append(streaming["id"], chunk)

        try:
            try:
                result = self.live.respond(self.decode(payload["audio"]), self.history.turns(), on_audio=on_audio)
            except Exception as e:
                print(f"[voice {label}] failed: {e}", flush=True)
                message = f"音声AIで返事を作れませんでした（{e}）"
                if streaming:
                    self.store.update(streaming["id"], text=message)
                else:
                    self.store.add(message, request_id, None)
                return None
            if not result.audio:
                print(f"[voice {label}] no audio in the reply", flush=True)
                self.store.add(result.reply_text or "音声AIから音声が返りませんでした", request_id, None)
                return None
            audio_b64 = base64.b64encode(pcm_to_wav(result.audio, result.sample_rate)).decode("ascii")
            # The final text and file must be stored before the stream ends: the iPhone reads them next.
            if streaming:
                reply = self.store.update(streaming["id"], text=result.reply_text, audio_base64=audio_b64)
            else:
                reply = self.store.add(result.reply_text, request_id, audio_b64)
            self.history.append(result.user_text or payload.get("text") or "", result.reply_text)
            print(f"[voice {label}] reply {reply['id']} done in {time.monotonic() - started:.1f}s: {result.reply_text[:60]}", flush=True)
            return reply
        finally:
            if streaming:
                self.streams.finish(streaming["id"])


class AudioStreams:
    """PCM of spoken replies, readable from the start while Gemini is still producing it."""

    def __init__(self, keep=20, idle_timeout=30):
        self.cond = threading.Condition()
        self.streams = OrderedDict()
        self.keep = keep
        self.idle_timeout = idle_timeout

    def open(self, reply_id, sample_rate):
        with self.cond:
            self.streams[reply_id] = {"chunks": [], "done": False, "rate": sample_rate}
            finished = [k for k, v in self.streams.items() if v["done"]]
            for k in finished[: max(0, len(finished) - self.keep)]:
                del self.streams[k]

    def append(self, reply_id, data):
        with self.cond:
            self.streams[reply_id]["chunks"].append(data)
            self.cond.notify_all()

    def finish(self, reply_id):
        with self.cond:
            if reply_id in self.streams:
                self.streams[reply_id]["done"] = True
                self.cond.notify_all()

    def exists(self, reply_id):
        with self.cond:
            return reply_id in self.streams

    def read(self, reply_id):
        """Yields the stream's chunks from the beginning until it is finished (or stalls)."""
        sent = 0
        while True:
            with self.cond:
                stream = self.streams.get(reply_id)
                if stream is None:
                    return
                if sent >= len(stream["chunks"]) and not stream["done"]:
                    self.cond.wait(self.idle_timeout)
                chunks = stream["chunks"][sent:]
                done = stream["done"]
            if not chunks and not done:
                return
            for chunk in chunks:
                yield chunk
            sent += len(chunks)
            if done and sent >= len(stream["chunks"]):
                return


class ReplyStore:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.replies = []
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                self.replies = [json.loads(line) for line in f if line.strip()]

    def add(self, text, request_id, audio_b64, sample_rate=None):
        """With [sample_rate], the reply is announced before its audio exists: the iPhone streams it."""
        with self.lock:
            next_id = (self.replies[-1]["id"] + 1) if self.replies else 1
            reply = {
                "id": next_id,
                "request_id": request_id,
                "text": text,
                "audio_base64": audio_b64,
                "created_at": int(time.time() * 1000),
            }
            if sample_rate:
                reply["stream"] = f"/replies/{next_id}/stream"
                reply["sample_rate"] = sample_rate
            self.replies = (self.replies + [reply])[-MAX_REPLIES:]
            self._write()
            return reply

    def update(self, reply_id, **fields):
        with self.lock:
            for reply in self.replies:
                if reply["id"] == reply_id:
                    reply.update(fields)
                    self._write()
                    return reply
            return None

    def get(self, reply_id):
        with self.lock:
            return next((r for r in self.replies if r["id"] == reply_id), None)

    def _write(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            for r in self.replies:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        os.replace(tmp, self.path)

    def after(self, cursor):
        with self.lock:
            return [r for r in self.replies if cursor is None or r["id"] > cursor]


def make_handler(store, token, relay=None, voice=None):
    class Handler(BaseHTTPRequestHandler):
        def _authorized(self):
            if not token:
                return True
            got = self.headers.get("Authorization", "")
            return hmac.compare_digest(got, f"Bearer {token}")

        def _send(self, status, body):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            url = urlparse(self.path)
            parts = url.path.strip("/").split("/")
            if parts[0] != "replies" or len(parts) > 3 or (len(parts) == 3 and parts[2] != "stream"):
                return self._send(404, {"error": "not found"})
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            if len(parts) > 1:
                if not parts[1].isdigit():
                    return self._send(404, {"error": "not found"})
                return self._stream(int(parts[1])) if len(parts) == 3 else self._reply(int(parts[1]))
            after = parse_qs(url.query).get("after", [None])[0]
            try:
                cursor = int(after) if after is not None else None
            except ValueError:
                return self._send(400, {"error": "bad after"})
            self._send(200, {"replies": store.after(cursor)})

        def do_POST(self):
            path = urlparse(self.path).path
            if path not in ("/replies", "/webhook"):
                return self._send(404, {"error": "not found"})
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            length = int(self.headers.get("Content-Length", "0"))
            if length > MAX_BODY_BYTES:
                return self._send(413, {"error": "too large"})
            if path == "/webhook":
                return self._webhook(self.rfile.read(length))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "invalid json"})
            text = body.get("text", "")
            audio = body.get("audio_base64")
            if not text and not audio:
                return self._send(400, {"error": "text or audio_base64 required"})
            reply = store.add(text, body.get("request_id"), audio)
            print(f"[reply {reply['id']}] {text[:60]}", flush=True)
            self._send(201, {"id": reply["id"]})

        def _webhook(self, body):
            if self.headers.get("X-Index-Test") == "true":
                return self._send(200, {"status": "ignored test event"})
            content_type = self.headers.get("Content-Type", "")
            # Button events arrive as JSON and carry no transcription.
            fields, files = parse_form(content_type, body) if content_type.startswith("multipart/") else ({}, {})
            text = fields.get("transcription", "").strip()
            audio = files.get("audio")
            if audio and voice is not None:
                return self._voice(audio, text)
            if not text:
                return self._send(200, {"status": "no transcription"})
            if relay is None:
                return self._send(200, {"status": "relay disabled"})
            recorded_at = fields.get("recordedAt")
            payload = {
                "text": text,
                "request_id": self.headers.get("X-Index-Delivery"),
                "recorded_at": int(recorded_at) if recorded_at and recorded_at.isdigit() else None,
                "trigger": self.headers.get("X-Index-Trigger"),
            }
            if not relay.enqueue(payload):
                return self._send(200, {"status": "duplicate"})
            print(f"[webhook {payload['request_id']}] {text[:60]}", flush=True)
            self._send(200, {"status": "queued"})

        def _reply(self, reply_id):
            reply = store.get(reply_id)
            if reply is None:
                return self._send(404, {"error": "not found"})
            self._send(200, reply)

        def _stream(self, reply_id):
            streams = voice.streams if voice else None
            if streams is None or not streams.exists(reply_id):
                return self._send(404, {"error": "no stream"})
            self.send_response(200)
            self.send_header("Content-Type", f"audio/L16; rate={streams.streams[reply_id]['rate']}; channels=1")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                for chunk in streams.read(reply_id):
                    self.wfile.write(chunk)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

        def _voice(self, audio, text):
            payload = {"audio": audio, "text": text, "request_id": self.headers.get("X-Index-Delivery")}
            if not voice.enqueue(payload):
                return self._send(200, {"status": "duplicate"})
            print(f"[webhook {payload['request_id']}] recording {len(audio)} bytes -> voice", flush=True)
            self._send(200, {"status": "voice queued"})

        def log_message(self, fmt, *args):
            if not self.path.startswith("/replies?") and self.path != "/replies":
                super().log_message(fmt, *args)

    return Handler


def synthesize_with_say(text):
    out = os.path.join(tempfile.mkdtemp(), "reply.m4a")
    subprocess.run(
        ["say", "-o", out, "--file-format=m4af", "--data-format=aac", text],
        check=True,
    )
    return out


def cmd_serve(args):
    store = ReplyStore(args.store)
    relay = None
    if args.relay_url:
        relay = Relay(args.relay_url, args.relay_token)
        relay.start()
    voice = None
    if args.gemini_api_key and args.gemini_model:
        live = GeminiLive(args.gemini_api_key, args.gemini_model, args.gemini_prompt or DEFAULT_SYSTEM_PROMPT, args.gemini_voice)
        voice = VoiceResponder(store, live, ConversationHistory(args.history, args.history_turns))
        voice.start()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(store, args.token, relay, voice))
    print(f"Reply server on http://{args.host}:{args.port} (store: {args.store})", flush=True)
    print(f"Webhook relay: {args.relay_url or 'off (set RELAY_URL in .env)'}", flush=True)
    print(
        f"Voice replies: {args.gemini_model if voice else 'off (set GEMINI_API_KEY in .env)'}",
        flush=True,
    )
    server.serve_forever()


def cmd_clear_history(args):
    ConversationHistory(args.history).clear()
    print("conversation history cleared")


def cmd_push(args):
    audio_path = synthesize_with_say(args.text) if args.say else args.audio
    audio_b64 = None
    if audio_path:
        with open(audio_path, "rb") as f:
            data = f.read()
        if len(data) > MAX_AUDIO_BYTES:
            sys.exit(f"audio is larger than {MAX_AUDIO_BYTES} bytes")
        audio_b64 = base64.b64encode(data).decode("ascii")
    payload = json.dumps(
        {"text": args.text, "request_id": args.request_id, "audio_base64": audio_b64}
    ).encode("utf-8")
    req = urllib.request.Request(
        args.server.rstrip("/") + "/replies",
        data=payload,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {args.token}"},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        print(resp.read().decode("utf-8"))


def main():
    load_env(ENV_PATH)
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve")
    s.add_argument("--host", default="0.0.0.0")
    s.add_argument("--port", type=int, default=8765)
    s.add_argument("--token", default=os.environ.get("REPLY_TOKEN", ""))
    s.add_argument("--relay-url", default=os.environ.get("RELAY_URL", ""))
    s.add_argument("--relay-token", default=os.environ.get("RELAY_TOKEN", ""))
    s.add_argument("--store", default=os.path.join(HERE, "replies.jsonl"))
    s.add_argument(
        "--gemini-api-key", default=os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_CLOUD_API_KEY", "")
    )
    s.add_argument("--gemini-model", default=os.environ.get("GEMINI_LIVE_MODEL") or DEFAULT_LIVE_MODEL)
    s.add_argument("--gemini-voice", default=os.environ.get("GEMINI_VOICE", ""))
    s.add_argument("--gemini-prompt", default=os.environ.get("GEMINI_SYSTEM_PROMPT", ""))
    s.add_argument("--history", default=HISTORY_PATH)
    s.add_argument("--history-turns", type=int, default=int(os.environ.get("GEMINI_HISTORY_TURNS", "20")))
    s.set_defaults(func=cmd_serve)

    c = sub.add_parser("clear-history")
    c.add_argument("--history", default=HISTORY_PATH)
    c.set_defaults(func=cmd_clear_history)

    u = sub.add_parser("push")
    u.add_argument("--server", default="http://127.0.0.1:8765")
    u.add_argument("--token", default=os.environ.get("REPLY_TOKEN", ""))
    u.add_argument("--text", required=True)
    u.add_argument("--request-id")
    g = u.add_mutually_exclusive_group()
    g.add_argument("--audio", help="path to an M4A/MP3/WAV file")
    g.add_argument("--say", action="store_true", help="generate audio from --text with macOS `say`")
    u.set_defaults(func=cmd_push)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
