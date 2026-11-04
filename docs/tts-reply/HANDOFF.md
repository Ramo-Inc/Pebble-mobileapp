# 引き継ぎ：Reply audio ＋ 返事サーバー ＋ pebble-ring（2026-11-05 時点）

次のセッションは、この文書と下の「関連資料」を読んでから作業してください。
詳しい仕様は各資料にあるので、ここでは**今の状態・残っている課題・注意点**だけを書きます。

## 0. ユーザーとのやり取りのルール（最重要）

- **返答は必ず日本語。** 途中で英語に戻らない（何度も指摘されている）。長いツール出力のあとも日本語で書く。
- **やることを先に一言で伝えてから**手順を書く。
- **細かい実装判断は聞かない。** 一番シンプルなベストプラクティスで決めて、決めたことを伝える。聞くのは作るものが変わる判断だけ。
- **ユーザーに渡す前に、使う側の手順を最後まで通して確かめる。** 前提（MCP 登録など）を先に済ませ、1コマンドで動く形にして渡す。複数のコマンドを貼らせない。
- 推測を事実のように言わない。ログ・コードで確かめてから言う（例：「リングは定期的に通信している」は誤りだった。§4-1）。

## 1. 全体像

```text
リング(Index 01) → iPhone アプリ（端末内 STT）→ Webhook
  → 返事サーバー :8765  POST /webhook → 転送（JSON）
  → pebble-ring :8766  POST /stt → Catchball ルーム voice で秘書(main)へ send
  → 秘書セッション（Claude Code。~/Projects/secretary で起動）が pebble-ring 宛てに返事
  → pebble-ring：全文 ＋ 要約(Anthropic Haiku)を AivisSpeech で音声化 → 返事サーバー POST /replies
  → iPhone が3秒ごとに GET /replies → 自動再生・Index タブの「Replies」に表示
```

## 2. 関連資料（中身はここを読む）

| 内容 | 場所 |
|---|---|
| Reply audio の設計（アプリ側） | `docs/tts-reply/DESIGN.md` |
| Mac でのビルド・実機テスト手順（T1〜T8） | `docs/tts-reply/MAC_BUILD_AND_TEST.md` |
| 返事を Index フィードに表示する計画（実装済み） | `docs/tts-reply/FEED_REPLIES_PLAN.md` |
| 返事サーバーの使い方・API・送り手の仕様 | `tools/reply-server/README.md`・`API.md`・`SENDER_SPEC.md` |
| pebble-ring（仮想エージェント） | `tools/skill/pebble-ring/README.md`・`SKILL.md` |
| 秘書セッション向けの説明書（1本） | `tools/skill/pebble-ring/SECRETARY.md` |
| Catchball（メッセージバス） | `~/Services/Catchball/README.md`、`docs/MCP_TOOLS.md`、`docs/ARCHITECTURE.md` |
| アプリの Webhook 仕様 | `experimental/src/commonMain/kotlin/coredevices/ring/external/indexwebhook/INDEX_WEBHOOK_API.md` |
| 変更履歴 | `git log --oneline 64edf704..HEAD`（このプロジェクトの作業はすべて master に push 済み） |

## 3. 今の状態

### 常駐サービス（launchd、ユーザー LaunchAgent。自動ログインが有効なので再起動後も起動）

| サービス | plist | アドレス | ログ |
|---|---|---|---|
| Catchball ブローカー | `~/Library/LaunchAgents/com.ramo.catchball.plist` | AI 用 `127.0.0.1:47821`、WebUI `http://192.168.0.111:47822/` | `~/Library/Logs/catchball.log` |
| 返事サーバー | `com.ramo.reply-server.plist` | `0.0.0.0:8765` | `~/Library/Logs/reply-server.log` |
| pebble-ring | `com.ramo.pebble-ring.plist` | `127.0.0.1:8766` | `~/Library/Logs/pebble-ring.log` |

再起動：`launchctl kickstart -k gui/$(id -u)/com.ramo.<名前>`

### 設定（git に入らないローカルファイル。値はここに書かない）

- `tools/reply-server/.env`：`REPLY_TOKEN`（iPhone と共有）、`RELAY_URL=http://127.0.0.1:8766/stt`、`RELAY_TOKEN`
- `tools/skill/pebble-ring/.env`：`PEBBLE_RING_TOKEN`（= 返事サーバーの `RELAY_TOKEN`）、`REPLY_TOKEN`、`SUMMARY_PROVIDER=anthropic`、`SUMMARY_MODEL=claude-haiku-5-5`、`SUMMARY_API_KEY`（設定済み）、`SUMMARY_BASE_URL` は空（Anthropic で `/v1` を付けると URL が二重になる）、`TTS_URL=http://127.0.0.1:10102`（AivisSpeech の OpenAI 互換アダプタ）
- iPhone アプリ：Reply audio の Server URL `http://192.168.0.111:8765`。Webhook は URL `http://192.168.0.111:8765/webhook`、ヘッダー `Authorization: Bearer <REPLY_TOKEN>`、Transcription only
- ビルド用ローカル設定（コミットしない）：`iosApp/iosApp.xcodeproj/project.pbxproj`（Team・Bundle ID。skip-worktree）、`iosApp/Configuration/Config.xcconfig`（skip-worktree）、`iosApp/iosApp/iosApp.entitlements`（skip-worktree）、ダミーの `GoogleService-Info.plist`、`local.properties`、`iosApp/Podfile.lock`（変更あり・未コミットのまま）

### 秘書

- 起動：普通のターミナルで `~/Projects/Pebble-mobileapp/tools/skill/pebble-ring/secretary.sh`（Codex は末尾に ` codex`）。MCP `session_mail` を `~/Projects/secretary` だけに登録（Claude は local スコープ、Codex は起動時の `-c`）し、「SECRETARY.md を読んで接続して」を最初のメッセージで渡す。
- 現在、ルーム `voice` に `main`（秘書）と `pebble-ring` が参加済み。通しテスト（Webhook → 秘書 → 音声 → iPhone）は成功している。

### iOS アプリ

- ビルド〜インストール〜起動：普通のターミナルで `~/Projects/Pebble-mobileapp/tools/mac-build/ios-build-install.sh`（iPhone を USB 接続・ロック解除）。
- 実機テスト T1〜T8 は合格（結果は会話の報告のみ。要点：T2/T5 は修正 `a77ef748`・`ffd0f0c9` 後に合格）。
- 最新の動作：アプリが起きたとき（起動・iOS の一時停止からの再開）に、未再生の返事を古い順に全部再生する（`a14ed894`）。

## 4. 残っている課題

1. **画面を閉じている間、返事がすぐ鳴らない（未解決・ユーザー判断待ち）**
   - 原因（ログで確認済み）：iOS はバックグラウンドのアプリを一時停止する。アプリを起こすのはリングの BLE 通信だけだが、リングは**使われていると15〜30秒ごとに約1秒つながる**だけで、しばらく使わないと**まったく通信しなくなる**（2026-11-05 01:18 を最後に3時間以上接続なし）。リング側の省電力と推測（ファームの挙動なのでアプリのコードからは確認できない）。
   - 選択肢：A. 無音の音声を流し続けて一時停止させない（無料 Apple ID で可、電池を使う）／B. プッシュ通知（有料の Apple Developer Program が必要）／C. 今のまま（起きたときにまとめて再生）。ユーザーはまだ選んでいない。
   - 確認方法の提案中：リングのボタンを押すとログに `Updated connection state to: connected` が出るか。
2. **秘書の返事の先頭の `[確認]` などの印も読み上げられる。** 読み上げ用の要約からだけ外すかは未決。
3. **リングの録音処理直後に iOS がアプリを強制終了することがある**（端末内 AI モデル読み込み直後。録音処理側なので変更しない約束）。
4. **ダミー Firebase のためサインイン不可。** オンボーディングは「Watch」→「Skip」→ 設定で Enable Index Feed。文字起こしは端末内方式（PlatformOnly）でのみ動く。

## 5. 注意点（ハマりどころ）

- **Claude Code / Codex の設定ファイル（`~/.claude*`、`~/.codex/config.toml`）は、このエージェントからは変更できない**（自己変更として拒否される）。必要ならスクリプトにしてユーザーに実行してもらう。
- **署名（codesign）はエージェントのシェルからできない**（`errSecInternalComponent`）。iOS ビルドはユーザーのターミナルで `ios-build-install.sh`。
- Xcode が作り直した `ComposeApp.framework` をアプリに入れ直さないことがある。スクリプトは毎回 `Pebble.app/Frameworks/ComposeApp.framework` を消してからビルドする。
- iPhone のアプリログは `Library/Caches/logs/latest.log`（`xcrun devicectl device copy from --domain-type appDataContainer --domain-identifier com.ramo.coreapp ...`）。**バッファされ、アプリが裏に回るまで書き出されない**。古い分は `previous.log.gz`（zlib）。
- 時刻：アプリのログは UTC。この Mac のローカル時刻は UTC+8 で表示されていた。
- `claude --dangerously-load-development-channels` は後ろの引数を全部チャンネル名として食べる。最初のメッセージはこのオプションより**前**に置く。
- Catchball の `send` は `client_message_id` が必須。pebble-ring は `provider: claude` として自己参加している。再起動直後の約30秒は `AGENT_IN_USE` で入り直せない（自動で回復する）。
- 返事サーバーの `replies.jsonl` を消すと番号が1に戻り、iPhone に新しい返事が届かなくなる（直し方は `tools/reply-server/README.md`）。
- 変更してよい範囲：最初の指示では `replyaudio` パッケージと `tools/` のみ。それ以外（Index 画面など）はユーザーの許可を得て変更した。リング録音・文字起こし・Webhook のコードは変更しない。

## 6. おすすめのスキル

| スキル | 使う場面 |
|---|---|
| `diagnosing-bugs` | バックグラウンドで鳴らない問題（§4-1）を、ログを使って切り分けるとき |
| `grilling` | §4-1 の方式（A/B/C）を決めるとき。ただし聞くのは本当に必要な判断だけ |
| `tdd` | Reply audio や pebble-ring に機能を足すとき（テストは `./gradlew :experimental:testAndroidHostTest --tests 'coredevices.ring.external.replyaudio.*'`、`python3 -m unittest discover tools/skill/pebble-ring`、`python3 -m unittest discover tools/reply-server`） |
| `code-review` | 変更をまとめて見直すとき（基準は `CLAUDE.md` の Code Guidelines） |
