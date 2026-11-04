---
name: pebble-ring
description: Operate pebble-ring, the user's voice contact point in a Catchball room. Use when the user wants to start, stop, check, or reconfigure pebble-ring, resend its introduction to the secretary, or set up a secretary session that talks to the user through the Index ring and iPhone.
---

# pebble-ring

`pebble-ring` is a virtual agent in a Catchball (session-mail) room. It is **the user's contact point**:

- Messages it sends are what the user said into the Index 01 ring (transcribed, may contain recognition mistakes).
- Anything sent to `pebble-ring` reaches the user: the full text appears on the iPhone and a 2-3 sentence summary is read aloud.

```text
ring → iPhone (STT) → reply server /webhook → pebble-ring /stt → Catchball → secretary (main)
secretary → channel_send to pebble-ring → summary + TTS → reply server /replies → iPhone plays it
```

Code and settings: `tools/skill/pebble-ring/` (`pebble_ring.py`, `.env`). Details: `README.md` in the same folder.

## If you are the secretary session

Talk to the user only through `pebble-ring`:

- Reply, report progress, ask questions with `channel_send` to `pebble-ring`. When answering a request, set `reply_to` to that request's `message_id`.
- Put the conclusion in the first 1-2 sentences; the spoken version is a summary.
- Tell the user when work is accepted, when it is done, and when a decision is needed.
- Broadcasts (`to: "all"`) never reach the user.

## Operations

| Task | Command |
|---|---|
| Status | `launchctl print gui/$(id -u)/com.ramo.pebble-ring \| grep -E "state =\|pid ="` |
| Logs | `tail -f ~/Library/Logs/pebble-ring.log` |
| Restart (after editing `.env`) | `launchctl kickstart -k gui/$(id -u)/com.ramo.pebble-ring` |
| Stop and unregister | `launchctl bootout gui/$(id -u)/com.ramo.pebble-ring` |
| Resend the introduction to the secretary (e.g. after starting a new secretary conversation) | `curl -X POST -H "Authorization: Bearer $PEBBLE_RING_TOKEN" http://127.0.0.1:8766/intro` |
| Tests | `python3 -m unittest discover tools/skill/pebble-ring` |

## Setting up a secretary

Run `tools/skill/pebble-ring/secretary.sh` (or `secretary.sh codex`) in a terminal. It enables the Catchball MCP server for the secretary folder only (Claude Code: local-scope registration on first use; Codex: `-c` overrides per launch), starts the secretary in `~/Projects/secretary`, and hands it "read SECRETARY.md and connect" as the first message.

`SECRETARY.md` is the single instruction file for the secretary: what Catchball is, the exact `channel_join` arguments, and how to reach the user through pebble-ring.
pebble-ring sends the secretary an introduction once it sees `main` in the room. The room `voice` is created by pebble-ring if it does not exist.

Room, IDs, summary API and TTS voice are set in `.env` (see `.env.example`).
