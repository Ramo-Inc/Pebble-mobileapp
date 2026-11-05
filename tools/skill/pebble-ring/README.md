# pebble-ring（ユーザーの連絡口になる仮想エージェント）

Catchball（session-mail）のルームに `pebble-ring` という参加者として入り、**ユーザーと秘書AIセッションの連絡口**になる常駐プログラム。Python 標準ライブラリだけで動く。

```text
リング → スマホ（文字起こし）→ 返事サーバー POST /webhook ──転送──▶ pebble-ring POST /stt
                                                                     │ [ユーザーの声] として秘書（main）へ send
                                                                     ▼
                                            秘書セッション（Claude Code / Codex。普段どおり画面で動く）
                                                                     │ pebble-ring 宛てに返事を send（何度でも）
                                                                     ▼
スマホで自動再生 ◀── 返事サーバー POST /replies ◀── pebble-ring（全文 ＋ 要約を TTS した音声）
```

## 動き

- **参加**：起動すると、参加用キー（`join.key`）でルームに `pebble-ring` として参加する。ルームがなければ作る。役割の文に「ユーザーとの連絡口。返事は pebble-ring 宛てに」と書く。
- **紹介**：秘書（`SECRETARY_ID`）がルームにいれば、使い方の説明を1回送る（起動のたび。`POST /intro` で送り直せる）。
- **ユーザー → 秘書**：返事サーバーから届いた文字起こしを、`[ユーザーの声] <本文>` として秘書に送る。
- **相づち**：秘書に送る前に、話の種類を Jev（TypeSafe）が `acks.json` の約20種類から選ぶ（直近2往復の会話も渡す）。送った直後に、その種類の短い相づちを音声で返す。キーがないとき・自信が低いときは `default` の言葉にする。音声は起動時に作っておく。
- **聞き返し**：Jev が「意味が取れない（`unclear`）」と強く判断したとき（確信度0.8以上）は秘書に送らず、`unclear` の言葉で聞き返す。Jev が失敗したり3秒以上かかったときは、そのまま秘書に送る。
- **秘書 → ユーザー**：`pebble-ring` 宛ての**個別メッセージ**を受け取るたびに、返事サーバーへ送る。全員宛ては送らない。
  - 文字：本文そのまま（アプリの返事ログに全文表示）
  - 音声：要約API で、内容に合った長さの話し言葉に要約し（注意書き・印・括弧は読まない。英字と数字はそのまま）、TTS で M4A にする
  - 秘書が `reply_to` を付ければ、元の録音（`request_id`）とひも付く
- **失敗したとき**：要約できなければ本文を読む（80文字を超える分は省く）。音声が作れなければ文字だけ送る。秘書がいなければ「届けられませんでした」とユーザーに返す。Catchball に届かないときは2秒ごとにやり直す（同じ文字起こしが二重に届くことはない）。

## 必要なもの

- Python 3.9 以上
- Catchball のブローカー（`~/Services/Catchball`、`com.ramo.catchball` で常駐）
- 返事サーバー（`tools/reply-server/`、`com.ramo.reply-server` で常駐）
- OpenAI 互換の TTS（この Mac では AivisSpeech のアダプタ `:10102`）と、macOS の `afconvert`
- 要約に使う API キー（Anthropic、または OpenAI 互換の DeepSeek・OpenAI など）

## 設定（.env）

```bash
cp tools/skill/pebble-ring/.env.example tools/skill/pebble-ring/.env
```

| 項目 | 内容 |
|---|---|
| `PEBBLE_RING_PORT` / `PEBBLE_RING_TOKEN` | 文字起こしの受け口。返事サーバーの `.env` の `RELAY_URL=http://127.0.0.1:<PORT>/stt` と `RELAY_TOKEN` を同じ値にする |
| `CATCHBALL_ROOM` / `SECRETARY_ID` | 参加するルームと、秘書の ID（初期値 `voice` / `main`） |
| `REPLY_SERVER_URL` / `REPLY_TOKEN` | 返事サーバーと、その `REPLY_TOKEN` |
| `SUMMARY_PROVIDER` | `anthropic` か `openai`（OpenAI 互換なら何でも） |
| `SUMMARY_MODEL` / `SUMMARY_API_KEY` / `SUMMARY_BASE_URL` | 例：`claude-haiku-5-5`。DeepSeek なら `openai` ＋ `deepseek-chat` ＋ `https://api.deepseek.com`。キーが空なら要約せず先頭を読む |
| `TTS_URL` / `TTS_VOICE` | TTS のアドレスと話者（空ならアダプタの既定） |
| `PEBBLE_JEV_API_KEY` | 相づちの種類を選ぶ Jev の API キー（console.typesafe.ai で発行）。空なら相づちは常に `default` |

## 起動（launchd で常駐）

`~/Library/LaunchAgents/com.ramo.pebble-ring.plist` に登録する（ログは `~/Library/Logs/pebble-ring.log`）。中身は `com.ramo.reply-server.plist` と同じ形で、`ProgramArguments` が `python3 tools/skill/pebble-ring/pebble_ring.py serve`。

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.ramo.pebble-ring.plist   # 登録・起動
launchctl kickstart -k gui/$(id -u)/com.ramo.pebble-ring                               # 再起動
launchctl bootout gui/$(id -u)/com.ramo.pebble-ring                                    # 停止・登録解除
```

手で動かす場合：`python3 tools/skill/pebble-ring/pebble_ring.py serve`

## 受け口（HTTP）

| パス | 内容 |
|---|---|
| `POST /stt` | 返事サーバーからの転送（`{"text", "request_id", "recorded_at", "trigger"}`）。`Authorization: Bearer <PEBBLE_RING_TOKEN>` |
| `POST /intro` | 秘書に使い方の説明を送り直す（秘書の会話を新しくしたとき） |

## 秘書の起動

ターミナルで1行。

```bash
~/Projects/Pebble-mobileapp/tools/skill/pebble-ring/secretary.sh          # Claude Code
~/Projects/Pebble-mobileapp/tools/skill/pebble-ring/secretary.sh codex    # Codex
```

- Catchball の MCP（`session_mail`）は**秘書のフォルダー（`~/Projects/secretary`）だけ**で有効にする。ほかのプロジェクトには登録しない。
  - Claude Code：初回だけ `--scope local` で登録し、送受信ツールの許可を `~/Projects/secretary/.claude/settings.local.json` に追加する
  - Codex：設定ファイルは変えず、起動のときだけ `-c` で渡す
- `~/Projects/secretary` で秘書を起動し、「[SECRETARY.md](SECRETARY.md) を読んで接続して」を最初のメッセージとして渡す。秘書はルーム `voice` に `main` として参加し、pebble-ring からも説明が届く。
- Claude Code は起動のたびに Channel 機能の確認が出るので「I am using this for local development」を選ぶ。

`SECRETARY.md` が秘書向けの説明書（Catchball とは何か、参加の引数、pebble-ring を通したユーザーとのやり取り）。

## テスト

```bash
python3 -m unittest discover tools/skill/pebble-ring
```
