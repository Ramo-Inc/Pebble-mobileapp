# Reply server (Mac)

The Pebble app's **Reply audio** feature polls this server and auto-plays new replies. Python 3 standard library only; nothing to install.

## Start

```bash
python3 tools/reply-server/reply_server.py serve --token CHANGE_ME --port 8765
```

Replies are kept in `tools/reply-server/replies.jsonl` (last 200).

## Connect the iPhone

Index settings → **Reply audio**:

- **Server URL**: an address the iPhone can reach, e.g. `http://<Mac's Tailscale IP>:8765` or `http://<Mac's LAN IP>:8765`
- **Token**: the value passed to `--token`
- Turn on **Auto-play replies**, then **Save**

## Push a reply

From the agent, or by hand:

```bash
# Text + macOS `say` voice (good for a first test)
python3 tools/reply-server/reply_server.py push --token CHANGE_ME --text "了解、調べます" --say

# Text + an audio file made by your TTS (M4A recommended; MP3/WAV also work)
python3 tools/reply-server/reply_server.py push --token CHANGE_ME --text "結果です" --audio reply.m4a

# Link it to the recording (the webhook's X-Index-Delivery header)
python3 tools/reply-server/reply_server.py push --token CHANGE_ME --text "..." --audio reply.m4a --request-id "<X-Index-Delivery>"
```

Or over HTTP:

```bash
curl -X POST http://127.0.0.1:8765/replies \
  -H "Authorization: Bearer CHANGE_ME" -H "Content-Type: application/json" \
  -d '{"text":"了解","audio_base64":"<base64 of the m4a>"}'
```

## API

- `GET /replies?after=<id>` → `{"replies":[{"id","request_id","text","audio_base64","created_at"}]}`
- `POST /replies` with `{"text", "request_id"?, "audio_base64"?}` → `{"id"}`
- Both require `Authorization: Bearer <token>` when the server was started with `--token`.
