#!/usr/bin/env python3
"""Minimal reply server for the Pebble app's "Reply audio" feature.

The agent pushes replies (text + optional audio); the iPhone polls GET /replies and auto-plays them.
Standard library only.

  Run the server:   python3 reply_server.py serve --token SECRET [--port 8765]
  Push a reply:     python3 reply_server.py push --token SECRET --text "了解" [--audio reply.m4a | --say]
                                              [--request-id <X-Index-Delivery>]
"""
import argparse
import base64
import hmac
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_REPLIES = 200
MAX_AUDIO_BYTES = 5 * 1024 * 1024


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


def make_handler(store, token):
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
            if urlparse(self.path).path != "/replies":
                return self._send(404, {"error": "not found"})
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            length = int(self.headers.get("Content-Length", "0"))
            if length > MAX_AUDIO_BYTES * 2:
                return self._send(413, {"error": "too large"})
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
    server = ThreadingHTTPServer((args.host, args.port), make_handler(store, args.token))
    print(f"Reply server on http://{args.host}:{args.port} (store: {args.store})", flush=True)
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
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve")
    s.add_argument("--host", default="0.0.0.0")
    s.add_argument("--port", type=int, default=8765)
    s.add_argument("--token", default=os.environ.get("REPLY_TOKEN", ""))
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
