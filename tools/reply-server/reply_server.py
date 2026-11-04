#!/usr/bin/env python3
"""Minimal reply server for the Pebble app's "Reply audio" feature.

The agent pushes replies (text + optional audio); the iPhone polls GET /replies and auto-plays them.
Optionally receives the app's Index webhook at POST /webhook and relays the transcription as JSON
to RELAY_URL. Settings are read from .env next to this file. Standard library only.

  Run the server:   python3 reply_server.py serve [--token SECRET] [--port 8765]
  Push a reply:     python3 reply_server.py push --token SECRET --text "了解" [--audio reply.m4a | --say]
                                              [--request-id <X-Index-Delivery>]
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

MAX_REPLIES = 200
MAX_AUDIO_BYTES = 5 * 1024 * 1024
MAX_BODY_BYTES = MAX_AUDIO_BYTES * 2
ENV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
RELAY_RETRY_DELAYS = (5, 15, 30)
RELAY_TIMEOUT = 10
RELAY_DEDUP_SIZE = 1000


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


def parse_multipart(content_type, body):
    """Returns the text fields of a multipart/form-data body as {name: value}."""
    message = BytesParser(policy=HTTP).parsebytes(
        f"Content-Type: {content_type}\r\n\r\n".encode("latin-1") + body
    )
    fields = {}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if name and part.get_filename() is None:
            fields[name] = part.get_payload(decode=True).decode("utf-8")
    return fields


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


class ReplyStore:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.replies = []
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                self.replies = [json.loads(line) for line in f if line.strip()]

    def add(self, text, request_id, audio_b64):
        with self.lock:
            next_id = (self.replies[-1]["id"] + 1) if self.replies else 1
            reply = {
                "id": next_id,
                "request_id": request_id,
                "text": text,
                "audio_base64": audio_b64,
                "created_at": int(time.time() * 1000),
            }
            self.replies = (self.replies + [reply])[-MAX_REPLIES:]
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                for r in self.replies:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
            os.replace(tmp, self.path)
            return reply

    def after(self, cursor):
        with self.lock:
            return [r for r in self.replies if cursor is None or r["id"] > cursor]


def make_handler(store, token, relay=None):
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
            if url.path != "/replies":
                return self._send(404, {"error": "not found"})
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
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
            fields = parse_multipart(content_type, body) if content_type.startswith("multipart/") else {}
            text = fields.get("transcription", "").strip()
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

        def log_message(self, fmt, *args):
            if not self.path.startswith("/replies?") and not self.path == "/replies":
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
    server = ThreadingHTTPServer((args.host, args.port), make_handler(store, args.token, relay))
    print(f"Reply server on http://{args.host}:{args.port} (store: {args.store})", flush=True)
    print(f"Webhook relay: {args.relay_url or 'off (set RELAY_URL in .env)'}", flush=True)
    server.serve_forever()


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
    s.add_argument("--store", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "replies.jsonl"))
    s.set_defaults(func=cmd_serve)

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
