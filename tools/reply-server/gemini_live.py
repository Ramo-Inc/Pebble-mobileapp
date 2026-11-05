"""Speech-to-speech replies with the Gemini Live API, plus the conversation history they build on.

Standard library only: a minimal WebSocket client, the Live API session, audio conversion helpers.
"""
import base64
import hashlib
import io
import json
import os
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import wave
from dataclasses import dataclass
from urllib.parse import urlparse
from urllib.request import getproxies, proxy_bypass

LIVE_URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
)
DEFAULT_LIVE_MODEL = "gemini-3.8-live"
INPUT_RATE = 16000
DEFAULT_OUTPUT_RATE = 24000
AUDIO_CHUNK_BYTES = INPUT_RATE * 2  # one second of 16-bit mono
MAX_SESSION_SECONDS = 120
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
DEFAULT_SYSTEM_PROMPT = (
    "あなたは、ユーザーが指輪型デバイス越しに話しかける音声アシスタントです。"
    "日本語で、話し言葉で、短く（だいたい3文以内で）答えてください。"
    "記号や箇条書き、URLは読み上げに向かないので使わないでください。"
)


class LiveError(Exception):
    pass


class WebSocket:
    """Client side of RFC 6455: masked frames out, reassembled text/binary messages in."""

    def __init__(self, sock, buffered=b""):
        self.sock = sock
        self.buf = buffered

    @classmethod
    def connect(cls, url, timeout=60):
        u = urlparse(url)
        secure = u.scheme == "wss"
        port = u.port or (443 if secure else 80)
        sock = open_socket(u.hostname, port, "https" if secure else "http", timeout)
        if secure:
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=u.hostname)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        path = (u.path or "/") + (f"?{u.query}" if u.query else "")
        sock.sendall(
            (
                f"GET {path} HTTP/1.1\r\nHost: {u.hostname}\r\nUpgrade: websocket\r\n"
                f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
            ).encode("ascii")
        )
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = sock.recv(4096)
            if not chunk:
                raise LiveError("connection closed during the WebSocket handshake")
            response += chunk
        head, rest = response.split(b"\r\n\r\n", 1)
        lines = head.decode("latin-1").split("\r\n")
        if " 101 " not in lines[0] + " ":
            raise LiveError(f"WebSocket handshake failed: {lines[0]}")
        expected = base64.b64encode(hashlib.sha1((key + WS_GUID).encode("ascii")).digest()).decode("ascii")
        headers = {k.strip().lower(): v.strip() for k, _, v in (line.partition(":") for line in lines[1:])}
        if headers.get("sec-websocket-accept") != expected:
            raise LiveError("WebSocket handshake failed: bad Sec-WebSocket-Accept")
        return cls(sock, rest)

    def send_json(self, obj):
        self._send(0x1, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def recv_json(self):
        return json.loads(self.recv())

    def recv(self):
        """Returns the next text or binary message; answers pings; raises LiveError on close."""
        message = b""
        while True:
            fin, opcode, payload = self._read_frame()
            if opcode == 0x8:
                code = int.from_bytes(payload[:2], "big") if len(payload) >= 2 else None
                reason = payload[2:].decode("utf-8", "replace")
                raise LiveError(f"server closed the connection ({code}) {reason}".strip())
            if opcode == 0x9:
                self._send(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            message += payload
            if fin:
                return message

    def close(self):
        try:
            self._send(0x8, (1000).to_bytes(2, "big"))
        except OSError:
            pass
        finally:
            self.sock.close()

    def _send(self, opcode, payload):
        n = len(payload)
        if n < 126:
            header = bytes([0x80 | opcode, 0x80 | n])
        elif n < 65536:
            header = bytes([0x80 | opcode, 0x80 | 126]) + n.to_bytes(2, "big")
        else:
            header = bytes([0x80 | opcode, 0x80 | 127]) + n.to_bytes(8, "big")
        mask = os.urandom(4)
        self.sock.sendall(header + mask + mask_bytes(payload, mask))

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(max(65536, n - len(self.buf)))
            if not chunk:
                raise LiveError("connection closed")
            self.buf += chunk
        data, self.buf = self.buf[:n], self.buf[n:]
        return data

    def _read_frame(self):
        b0, b1 = self._read_exact(2)
        length = b1 & 0x7F
        if length == 126:
            length = int.from_bytes(self._read_exact(2), "big")
        elif length == 127:
            length = int.from_bytes(self._read_exact(8), "big")
        mask = self._read_exact(4) if b1 & 0x80 else None
        payload = self._read_exact(length)
        if mask:
            payload = mask_bytes(payload, mask)
        return bool(b0 & 0x80), b0 & 0x0F, payload


def open_socket(host, port, scheme, timeout):
    """Connects directly, or through an HTTP CONNECT tunnel when the environment sets a proxy."""
    proxy = getproxies().get(scheme)
    if not proxy or proxy_bypass(host):
        return socket.create_connection((host, port), timeout=timeout)
    p = urlparse(proxy if "://" in proxy else f"http://{proxy}")
    sock = socket.create_connection((p.hostname, p.port or 80), timeout=timeout)
    auth = ""
    if p.username:
        token = base64.b64encode(f"{p.username}:{p.password or ''}".encode()).decode("ascii")
        auth = f"Proxy-Authorization: Basic {token}\r\n"
    sock.sendall(f"CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n{auth}\r\n".encode("ascii"))
    response = b""
    # Byte by byte, so nothing after the proxy's reply is consumed here.
    while not response.endswith(b"\r\n\r\n"):
        chunk = sock.recv(1)
        if not chunk:
            raise LiveError("proxy closed the connection")
        response += chunk
    status = response.split(b"\r\n", 1)[0].decode("latin-1")
    if " 200 " not in status + " ":
        sock.close()
        raise LiveError(f"proxy refused the tunnel: {status}")
    return sock


def mask_bytes(payload, mask):
    if not payload:
        return b""
    n = len(payload)
    key = (mask * (n // 4 + 1))[:n]
    return (int.from_bytes(payload, "big") ^ int.from_bytes(key, "big")).to_bytes(n, "big")


@dataclass
class LiveResult:
    user_text: str
    reply_text: str
    audio: bytes
    sample_rate: int


class GeminiLive:
    """One Live API session per utterance: send the recorded audio, collect the spoken reply."""

    def __init__(self, api_key, model, system_prompt=DEFAULT_SYSTEM_PROMPT, voice="", connect=WebSocket.connect, timeout=60,
                 max_session=MAX_SESSION_SECONDS, clock=time.monotonic):
        self.api_key = api_key
        self.model = model if model.startswith("models/") else f"models/{model}"
        self.system_prompt = system_prompt
        self.voice = voice
        self.connect = connect
        self.timeout = timeout
        self.max_session = max_session
        self.clock = clock

    def setup_message(self, history):
        generation = {"responseModalities": ["AUDIO"]}
        if self.voice:
            generation["speechConfig"] = {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": self.voice}}}
        return {
            "setup": {
                "model": self.model,
                "generationConfig": generation,
                "systemInstruction": {"parts": [{"text": self.system_prompt + history_text(history)}]},
                # The whole utterance is already recorded, so mark its start and end ourselves.
                "realtimeInputConfig": {"automaticActivityDetection": {"disabled": True}},
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
            }
        }

    def respond(self, pcm16k, history=(), on_audio=None):
        """Returns the whole reply; [on_audio](pcm, sample_rate) also gets each audio chunk as it arrives."""
        deadline = self.clock() + self.max_session
        ws = self.connect(f"{LIVE_URL}?key={self.api_key}", self.timeout)
        try:
            ws.send_json(self.setup_message(history))
            while "setupComplete" not in self._next(ws, deadline):
                pass
            ws.send_json({"realtimeInput": {"activityStart": {}}})
            for i in range(0, len(pcm16k), AUDIO_CHUNK_BYTES):
                chunk = base64.b64encode(pcm16k[i:i + AUDIO_CHUNK_BYTES]).decode("ascii")
                ws.send_json({"realtimeInput": {"audio": {"data": chunk, "mimeType": f"audio/pcm;rate={INPUT_RATE}"}}})
            ws.send_json({"realtimeInput": {"activityEnd": {}}})
            return self._collect(ws, on_audio, deadline)
        finally:
            ws.close()

    def _next(self, ws, deadline):
        if self.clock() > deadline:
            raise LiveError(f"session cut off after {self.max_session}s")
        message = ws.recv_json()
        if "error" in message:
            raise LiveError(f"Live API error: {message['error']}")
        return message

    def _collect(self, ws, on_audio, deadline):
        audio, user_text, reply_text, rate = [], [], [], DEFAULT_OUTPUT_RATE
        while True:
            content = self._next(ws, deadline).get("serverContent")
            if not content:
                continue
            for part in content.get("modelTurn", {}).get("parts", []):
                inline = part.get("inlineData")
                if inline and inline.get("mimeType", "").startswith("audio/pcm"):
                    rate = pcm_rate(inline["mimeType"], rate)
                    chunk = base64.b64decode(inline["data"])
                    audio.append(chunk)
                    if on_audio:
                        on_audio(chunk, rate)
            user_text.append(content.get("inputTranscription", {}).get("text", ""))
            reply_text.append(content.get("outputTranscription", {}).get("text", ""))
            if content.get("turnComplete"):
                return LiveResult("".join(user_text).strip(), "".join(reply_text).strip(), b"".join(audio), rate)


def pcm_rate(mime_type, default):
    for param in mime_type.split(";")[1:]:
        key, _, value = param.strip().partition("=")
        if key == "rate" and value.isdigit():
            return int(value)
    return default


def history_text(history):
    if not history:
        return ""
    lines = []
    for turn in history:
        lines.append(f"ユーザー: {turn['user']}")
        lines.append(f"あなた: {turn['assistant']}")
    return "\n\nここまでの会話（古い順）。続きとして答えてください。\n" + "\n".join(lines)


class ConversationHistory:
    """The latest exchanges, oldest first, kept in a JSON Lines file across restarts."""

    def __init__(self, path, max_turns=20):
        self.path = path
        self.max_turns = max_turns
        self.lock = threading.Lock()
        self._turns = []
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                self._turns = [json.loads(line) for line in f if line.strip()][-max_turns:]

    def turns(self):
        with self.lock:
            return list(self._turns)

    def append(self, user, assistant):
        with self.lock:
            self._turns = (self._turns + [{"user": user, "assistant": assistant}])[-self.max_turns:]
            self._write()

    def clear(self):
        with self.lock:
            self._turns = []
            self._write()

    def _write(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            for turn in self._turns:
                f.write(json.dumps(turn, ensure_ascii=False) + "\n")
        os.replace(tmp, self.path)


def decode_to_pcm16k(audio_bytes):
    """Decodes the app's M4A (or any format afconvert/ffmpeg reads) to 16 kHz 16-bit mono PCM."""
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "in.m4a")
        out = os.path.join(tmp, "out.wav")
        with open(src, "wb") as f:
            f.write(audio_bytes)
        if shutil.which("afconvert"):
            cmd = ["afconvert", "-f", "WAVE", "-d", f"LEI16@{INPUT_RATE}", "-c", "1", src, out]
        elif shutil.which("ffmpeg"):
            cmd = ["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-ar", str(INPUT_RATE), "-sample_fmt", "s16", out]
        else:
            raise LiveError("afconvert or ffmpeg is needed to decode the recording")
        subprocess.run(cmd, check=True, capture_output=True)
        with wave.open(out, "rb") as w:
            return w.readframes(w.getnframes())


def pcm_to_wav(pcm, sample_rate):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm)
    return buf.getvalue()
