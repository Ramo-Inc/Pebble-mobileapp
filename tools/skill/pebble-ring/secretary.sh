#!/usr/bin/env bash
# Starts the secretary session and has it join the Catchball room through SECRETARY.md.
# Registers the Catchball MCP server (session_mail) on first use. Safe to re-run.
# Usage: secretary.sh [claude|codex]   (default: claude)
set -euo pipefail

HARNESS="${1:-claude}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BRIDGE="$HOME/Services/Catchball/dist/bridge/main.js"
WORKDIR="$HOME/Projects/secretary"
PROMPT="$HERE/SECRETARY.md を読んで、その手順どおりに接続して。"

[ -f "$BRIDGE" ] || { echo "Catchball not found at $BRIDGE (see ~/Services/Catchball/README.md)" >&2; exit 1; }
mkdir -p "$WORKDIR"

case "$HARNESS" in
  claude)
    if ! claude mcp get session_mail >/dev/null 2>&1; then
      claude mcp add --scope user session_mail -- node "$BRIDGE"
      python3 - <<'EOF'
import json, os
p = os.path.expanduser("~/.claude/settings.json")
d = json.load(open(p)) if os.path.exists(p) else {}
allow = d.setdefault("permissions", {}).setdefault("allow", [])
for tool in ("join", "members", "send", "receive", "rooms", "room_create"):
    name = f"mcp__session_mail__channel_{tool}"
    if name not in allow:
        allow.append(name)
open(p, "w").write(json.dumps(d, indent=2, ensure_ascii=False) + "\n")
EOF
      echo "Registered session_mail for Claude Code."
    fi
    cd "$WORKDIR"
    exec claude --dangerously-load-development-channels server:session_mail "$PROMPT"
    ;;
  codex)
    if ! grep -q '^\[mcp_servers\.session_mail\]' "$HOME/.codex/config.toml" 2>/dev/null; then
      cat >> "$HOME/.codex/config.toml" <<EOF

[mcp_servers.session_mail]
command = "node"
args = ["$BRIDGE"]
default_tools_approval_mode = "approve"

[mcp_servers.session_mail.tools.channel_leave]
approval_mode = "prompt"

[mcp_servers.session_mail.tools.channel_room_delete]
approval_mode = "prompt"
EOF
      echo "Registered session_mail for Codex."
    fi
    cd "$WORKDIR"
    exec codex "$PROMPT"
    ;;
  *)
    echo "Usage: $0 [claude|codex]" >&2
    exit 2
    ;;
esac
