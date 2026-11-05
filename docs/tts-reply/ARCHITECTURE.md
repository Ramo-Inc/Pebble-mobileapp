# リングの音声会話 — 全体の地図（2026-11-05 時点）

リング（Pebble Index 01）で話しかけると、音声で返事が返ってくる仕組み一式の、**今の実装**の説明です。どの機能がどのファイルにあるかを書きます。今の運用状態と残りの課題は [HANDOFF.md](HANDOFF.md) にあります。

## 1. 3つの経路

リングの録音は、アプリの**ボタン設定**（押し方ごとの行き先）と、押し方ごとの **Webhook 設定**で、次のどれかに流れる。

```text
                       ┌─ 行き先 = Gemini Live ───────────────▶ ① アプリ内 Gemini（Mac を通らない）
リング ─BLE─▶ スマホ ──┤
                       └─ 行き先 = それ以外 ─┬─ Webhook: Recording only / Both ─▶ ② Mac 経由の Gemini
                                            ├─ Webhook: Transcription only ───▶ ③ 秘書（Claude Code）
                                            └─ Webhook なし ──────────────────▶ アプリ本来の処理（Index agent など）
```

| | ① アプリ内 Gemini | ② Mac 経由の Gemini | ③ 秘書 |
|---|---|---|---|
| 返事を作るもの | Gemini Live（スマホから直接） | Gemini Live（Mac の返事サーバーから） | 秘書セッション（Claude Code）＋要約（Claude Haiku） |
| 音声 | Gemini の声 | Gemini の声 | AivisSpeech |
| 返事の届き方 | その場で流しながら再生 | 返事サーバー → スマホが1秒ごとに取得、流しながら再生 | 返事サーバー → スマホが1秒ごとに取得して再生 |
| 調べもの・道具 | なし | なし | 秘書が使える道具すべて |
| 会話履歴 | アプリに20往復 | Mac の `conversation.jsonl` に20往復 | 秘書セッションの文脈＋pebble-ring が直近2往復 |
| 実測（離す→鳴り始め） | 3.6〜5.3秒 | 6.5〜7.3秒 | 相づちは数秒、本番は数十秒 |

- ボタン設定の表示名「**Gemini Live**」は ① のこと。② は Webhook の送る内容で選ぶもので、ボタン設定には出てこない。
- 行き先が Gemini Live の押し方には Webhook を被せない（`IndexWebhookConfig.sendsFor`）。

## 2. アプリ（`experimental` モジュール）

パスは `experimental/src/<sourceSet>/kotlin/coredevices/ring/` からの相対。

### 2-1. ボタンの押し方と振り分け

| 機能 | 場所 |
|---|---|
| 押し方の定義（Click / DoubleClick / TripleClick / Hold / ClickHold）と行き先（IndexAgent / LocalAgent / WebSearch / WebhookOnly / AgentPlugin / **GeminiLive** / Nothing ほか音楽操作） | `commonMain/.../service/button/RingGestureRouting.kt` |
| 録音の押し方の判定。最初の長押しより後に届いた押し方は無視（リングが長押し中に誤検出の押し方を報告するため） | 同上 `RingGesture.forRecordingSequence` |
| 押し方ごとの行き先の保存 | `commonMain/.../service/button/GestureRoutingPreferences.kt` |
| 録音 → 行き先ごとの処理を作る（Webhook を被せるかもここ） | `commonMain/.../service/recordings/button/RecordingOperationFactory.kt` |
| ボタン設定の画面（行き先の選択、Gemini Live を選ぶとキーと会話リセット、Webhook 設定への入口） | `commonMain/.../ui/screens/settings/ButtonSwitchboard.kt` |

リングの録音・BLE 転送・前処理・文字起こし（`service/RingSync.kt`、`service/recordings/` の処理本体）は**変更しない約束**（`CLAUDE.md`）。今回の機能はすべて、その外側に足している。

### 2-2. アプリ内 Gemini（`external/geminilive/`）

| ファイル | 役割 |
|---|---|
| `RingAudioTap.kt`（＋ `androidMain/…/RingAudioTap.android.kt`、`iosMain/…/RingAudioTap.ios.kt`） | リングのライブラリ（Haversine）の公開項目 `environment.transferDelegate` を包み、元の処理の後に**区切り（collection）の生データ**を受け取る。`PPCollection(番号, データ).audioTimeline` で音声（約 10 kHz・16bit）に戻し、`parts` として流す |
| `GeminiLiveStreamer.kt` | 区切りを録音ごとにまとめ、届くたびに `GeminiLiveResponder.respondToParts` へ渡す。どれかの押し方が Gemini Live のときだけ動く。1秒未満の録音（ボタン操作）は送らない |
| `GeminiLiveResponder.kt` | `respondToParts`：区切りを順に送り、最後の区切りの押し方が Gemini Live なら `activityEnd` を送って返事を流しながら再生 → 返事ログと会話履歴に保存。違えば返事を作らせずに閉じる。`respond(fileId)`：保存済みの録音をまとめて送る従来の方法（流し込みが答えられなかったときの予備） |
| `GeminiLiveRecordingOperation.kt` | 録音処理の順番待ちから呼ばれる。転送の開始番号で流し込み済みの返事を探し、答え済みなら何もしない |
| `GeminiLiveSession.kt` | Live API の WebSocket 1本＝会話1回。`open`（初期設定）→ `beginUtterance` → `sendAudio` → `endUtterance`。初期設定と返事待ちはそれぞれ最長2分 |
| `GeminiLiveProtocol.kt` | 送受信する JSON。モデル `gemini-3.8-live`、音声出力、自動の発話区切りはオフ、聞き取りと返事の文字起こしはオン、指示文の後ろに会話履歴 |
| `GeminiLiveStores.kt` | API キー（`IntegrationTokenStorage`、Android は Keystore で暗号化）と会話履歴（20往復） |
| `GeminiLiveSettings.kt` | ボタン設定内の「Gemini API key」「Save key」「Reset conversation」 |
| `GeminiLiveSessionManager`（`GeminiLiveResponder.kt` 内） | リングの転送が始まったら先に接続・初期設定を済ませる。30秒使われなければ閉じる |

区切りの流し込みの詳細と実測は [CLIENT_GEMINI_LIVE_DESIGN.md](CLIENT_GEMINI_LIVE_DESIGN.md)。

### 2-3. 返事の受信と再生（`external/replyaudio/`）

Mac の返事サーバーに溜まった返事（② と ③）を取りに行き、自動で再生する。アプリ内 Gemini の返事も同じログに入る。

| ファイル | 役割 |
|---|---|
| `ReplyAudioService.kt` | 1秒ごとに `GET /replies?after=<最後の番号>`。音声付きで未再生の返事を古い順に1件ずつ再生。`stream` 付きの返事は `GET /replies/{id}/stream` を流しながら再生し、終わったら `GET /replies/{id}` で最終の文字と WAV を保存。取得の間が10秒以上空いたら（iOS に止められていた）、溜まった未再生を全部並べる |
| `ReplyAudioApi.kt` | 上の3つの HTTP 呼び出し |
| `ReplyStreamPlayer.kt`（＋ android は `AudioTrack`、ios は `AVAudioEngine`） | PCM を届いた分から再生。最初に0.3秒ためる。PCM を WAV に包む関数もここ |
| `ReplyAudioPlayer.*.kt` | 保存済みの音声ファイル（M4A/MP3/WAV）の再生 |
| `ReplyPlaybackLock.kt` | サーバーの返事と Gemini の返事が重ならないための共通の順番待ち |
| `ReplyAudioLog.kt`・`ReplyAudioModels.kt` | 端末内の返事ログ（最新200件。文字は設定、音声は1件1ファイル）。アプリ内 Gemini の返事は負の番号で入り、自分の発話（`userText`）も持つ |
| `ReplyAudioPreferences.kt`・`ReplyAudioDialog.kt`・`ReplyAudioViewModel.kt` | Server URL・Token・自動再生の設定画面（Index 設定 → Reply audio） |
| `ui/screens/home/ReplyFeedSection.kt` | Index タブのホームに返事ログを表示（最新5件、展開で全部、タップで再生） |

### 2-4. Webhook（`external/indexwebhook/`）

録音を外部（ここでは Mac の返事サーバー）へ送るアプリ本来の機能。仕様は `INDEX_WEBHOOK_API.md`。押し方ごとに URL・ヘッダー・送る内容（Recording only / Transcription only / Both）を持つ（`IndexWebhookPreferences.kt`、画面は `IndexWebhookSheet.kt`）。このプロジェクトではコードを変えていない（行き先が Gemini Live の押し方を外す1行を除く）。

### 2-5. Android の文字起こし（`util` モジュール）

`util/src/androidMain/kotlin/coredevices/util/transcription/PlatformSpeechRecognizer.android.kt`：Android 13 以上の端末内音声認識（Google）で、録音の PCM を `EXTRA_AUDIO_SOURCE` から読ませる。日本語の区切りはつなげるだけ。③ の経路（Transcription only）で使う。iOS は `PlatformSpeechRecognizer.ios.kt`（Apple の音声認識）。

### 2-6. DI と起動

- 登録：`commonMain/kotlin/coredevices/experimentalModule.kt`（`GeminiLive*`、`RingAudioTap`、`ReplyAudio*`）。`ExperimentalDevices` は部品が多く Koin の `singleOf` の上限（22個）を超えるので `single { ExperimentalDevices(get(), …) }`。部品を足したら `get()` も足す。
- 起動：`commonMain/kotlin/coredevices/ExperimentalDevices.kt` の `appInit()` で、`replyAudioService.start()`、`geminiLiveSessionManager.start(...)`、`ringAudioTap.start()`、`geminiLiveStreamer.start(ringAudioTap.parts)`。

## 3. Mac（`tools/`）

| 部品 | 場所 | 役割 | 説明書 |
|---|---|---|---|
| 返事サーバー | `tools/reply-server/reply_server.py`（`:8765`） | 返事の置き場（`/replies`）。Webhook の受け口（`/webhook`）：録音付きなら Gemini Live で返事（②）、文字起こしだけなら `RELAY_URL` へ転送（③） | [README](../../tools/reply-server/README.md)・[API](../../tools/reply-server/API.md)・[SENDER_SPEC](../../tools/reply-server/SENDER_SPEC.md) |
| Gemini Live の接続 | `tools/reply-server/gemini_live.py` | WebSocket・会話履歴・M4A→PCM 変換。1回最長2分で打ち切り | 同上 README §6-2 |
| pebble-ring | `tools/skill/pebble-ring/pebble_ring.py`（`:8766`） | 文字起こしを Catchball 経由で秘書へ送る。Jev（`acks.json` の21種類）で内容に合った相づち、意味が取れなければ聞き返して秘書に送らない。秘書の返事を Haiku で要約し AivisSpeech で音声化して返事サーバーへ | [README](../../tools/skill/pebble-ring/README.md)・[SKILL](../../tools/skill/pebble-ring/SKILL.md)・[SECRETARY](../../tools/skill/pebble-ring/SECRETARY.md) |
| 秘書の起動 | `tools/skill/pebble-ring/secretary.sh` | `~/Projects/secretary` で Claude Code（または ` codex`）を起動して pebble-ring とつなぐ | 同上 |
| iOS ビルド | `tools/mac-build/setup.sh`・`ios-build-install.sh` | 自分の iPhone 用の準備と、ビルド→インストール→起動 | [BUILD_AND_TEST.md](BUILD_AND_TEST.md) |

常駐（launchd）：`com.ramo.reply-server`、`com.ramo.pebble-ring`、`com.ramo.catchball`。設定は各 `.env`（git に入らない）。

## 4. テスト

| 対象 | コマンド |
|---|---|
| アプリ内 Gemini・押し方・Webhook・返事の再生 | `./gradlew :experimental:testAndroidHostTest --tests 'coredevices.ring.external.geminilive.*' --tests 'coredevices.ring.service.button.*' --tests 'coredevices.ring.external.indexwebhook.*' --tests 'coredevices.ring.external.replyaudio.*'` |
| Android の文字起こし | `./gradlew :util:testAndroidHostTest --tests 'coredevices.util.transcription.*'` |
| 返事サーバー | `python3 -m unittest discover tools/reply-server` |
| pebble-ring | `python3 -m unittest discover tools/skill/pebble-ring` |
| iOS のコンパイル確認 | `./gradlew :experimental:compileKotlinIosArm64` |

## 5. 変更の履歴

このプロジェクトの作業はすべて `master` にある。`git log --oneline 64edf704..master -- experimental tools docs util` で追える。主なもの：

| コミット | 内容 |
|---|---|
| `a14ed894` まで | 返事の受信・自動再生・フィード表示（Reply audio） |
| `65be8083`・`ad4a14fb` | 読み上げ用の要約、Jev の相づちと聞き返し |
| `3434b96e` | Android の端末内音声認識 |
| `b08b578b`・`b9559463` | Mac 経由の Gemini、流しながら再生 |
| `a2f39f85` | アプリ内 Gemini（ボタンの行き先） |
| `bda78a7b`・`9a893ffc` | Gemini の接続に上限（Mac・アプリ） |
| `411f0dfd`・`a99d8c1e` | リングの誤検出の押し方を無視 |
| `be6e54a8` | リングの区切りを Gemini に流し込む |
