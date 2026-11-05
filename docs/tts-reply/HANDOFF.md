# 引き継ぎ：リングの音声会話（2026-11-05 夜の時点）

この文書が**今の状態の唯一の正本**です。ほかの `docs/tts-reply/*.md` は、それぞれの作成日時点の設計記録です（冒頭に注記あり）。食い違ったら、この文書とコードを信じてください。

## 0. ユーザーとのやり取りのルール（最重要）

- **返答は必ず日本語。** 長いツール出力のあとも日本語。
- **推測を事実のように言わない。** 言う前にログ・コード・ライブラリの中身で確かめる。「できない」と言う前に、依存ライブラリの公開 API まで調べる（今日、確かめずに「Haversine は途中の音声を出さない」と言って誤りだった）。
- **呼び名を混ぜない。** 「Gemini」が2種類あるので、必ず §1 の名前（**Mac 経由の Gemini** ／ **アプリ内 Gemini**）で呼ぶ。
- 細かい実装判断は聞かずに決めて伝える。聞くのは作るものが変わる判断だけ。
- 長い作業の途中でも、数分おきに一言で今していることを伝える（黙ると「止まっている」と思われる）。
- キー・トークンの値は画面・ログ・コミットに出さない。

## 1. 3つの経路

リングで話した録音は、**ボタンの押し方ごとの「行き先」**（アプリのボタン設定 `ButtonSwitchboard`）と、その押し方の **Webhook 設定**で、次のどれかに行く。

| 呼び名 | 選び方 | 流れ | Mac で要るもの | 実測（ボタンを離す→鳴り始め） |
|---|---|---|---|---|
| **アプリ内 Gemini** | 行き先を **Gemini Live** にする（アプリに Gemini の API キーを保存） | リング → スマホ ──WebSocket──▶ Gemini Live → スマホで流しながら再生 | なし | **3.6〜5.3秒**（Android、区切りを流し込む方式） |
| **Mac 経由の Gemini** | 行き先は Gemini Live 以外。Webhook の「送る内容」を **Recording only**（または Both） | リング → スマホ → Webhook → 返事サーバー → Gemini Live → 返事サーバー → スマホが1秒ごとに取得して流しながら再生 | 返事サーバー（`.env` に `GEMINI_API_KEY`） | 6.5〜7.3秒 |
| **秘書** | Webhook の「送る内容」を **Transcription only** | リング → スマホ（端末内で文字起こし）→ Webhook → 返事サーバー → pebble-ring（Jev で相づち・聞き返し）→ 秘書（Claude Code）→ pebble-ring（Haiku で要約・AivisSpeech で音声化）→ 返事サーバー → スマホ | 返事サーバー・pebble-ring・Catchball・秘書セッション・AivisSpeech | 相づちは数秒、本番の返事は秘書しだい（数十秒） |

- ボタン設定の表示名「**Gemini Live**」は、**アプリ内 Gemini** のこと。
- 行き先が Gemini Live の押し方には Webhook を被せない（`IndexWebhookConfig.sendsFor` で除外）。その押し方の Webhook 設定は残っていても使われない。
- どちらの Gemini にも**道具（検索など）も時計もつながっていない**。「今何時」は間違える（実例：16:24 に「午前8時24分」）。調べものは秘書の経路で。

### 今の設定（2026-11-05 夜）

| 押し方 | 行き先 | 結果 |
|---|---|---|
| Hold & Talk（長押し） | Gemini Live | アプリ内 Gemini |
| Double click & hold | Web search | 端末内の検索エージェント（Mac は使わない） |

Hold & Talk の Webhook 設定（`http://192.168.0.111:8765/webhook`）は残っているが、上の理由で使われない。秘書や Mac 経由の Gemini を使うときは、使う押し方の行き先を Gemini Live 以外にして、Webhook の送る内容を選ぶ。

## 2. アプリ内 Gemini のしくみ

コード：`experimental/src/commonMain/kotlin/coredevices/ring/external/geminilive/`（設計の経緯は `CLIENT_GEMINI_LIVE_DESIGN.md`）

1. **区切りの横取り**（`RingAudioTap`）：リングのライブラリ（Haversine）の公開項目 `environment.transferDelegate`（Android は `KMPHaversineSatelliteManager.environment`、iOS は `.wrap.environment()`）を包み、元の処理を先に呼んでから、区切りの生データを受け取る。`PPCollection(番号, データ).audioTimeline` で音声に戻す（約 10 kHz・16bit）。リングの録音・転送・前処理・文字起こしのコードは変えていない。
2. **流し込み**（`GeminiLiveStreamer` → `GeminiLiveResponder.respondToParts`）：最初の区切りで Gemini のセッションを取り、区切りが届くたびにそのままのレートで送る。最後の区切りに押し方が入っているので、そこで行き先が Gemini Live なら「話し終わり」を送り、返事を流しながら再生する。違えば返事を作らせずに切る。1秒未満の録音（ボタン操作）は送らない。
3. **後始末**：録音処理の順番待ちが `GeminiLiveRecordingOperation` を動かすと、転送の開始番号で流し込み済みの返事を探し、答え済みなら何もしない。流し込みが答えられなかったとき（途中の区切りが欠けた等）だけ、保存済みの録音で答え直す。
4. **事前接続**：リングの転送が始まった時点で Gemini に接続・初期設定を済ませておく（`GeminiLiveSessionManager`、使われなければ30秒で閉じる）。
5. **会話履歴**：直近20往復をアプリに保存して、毎回のセッションの初期設定に入れる。ボタン設定の Gemini Live の下で「会話をリセット」できる。返事は Index タブの Replies に、自分の発話と一緒に残る。

### 遅延の内訳（Android 実測、17:38）

| 区間 | 1回目（録音2.2秒） | 2回目（録音3.4秒） |
|---|---|---|
| 離す → リングが送り始める | 1.8秒 | 0.9秒 |
| リングが全区切りを送る | 1.5秒 | 1.7秒 |
| 最後の区切り → Gemini に「話し終わり」 | 0.02秒 | 0.02秒 |
| Gemini → 最初の音声 | 1.9秒 | 1.05秒 |
| 合計 | 5.3秒 | 3.6秒 |

- リングは、つながっていれば押している間から区切りを送る（17:09 の回）。つながっていなければ、離してからつないでまとめて送る（17:38 の2回）。ここはリングとライブラリの動きで、アプリからは変えられない。
- 全部そろってから送っていた前の方式は 6.0〜6.7秒だった。

## 3. 課金の安全策（接続が残らないように）

| 経路 | 仕組み |
|---|---|
| アプリ内 Gemini | 録音1件ごとに1セッション。返事が終わったら切る。初期設定と返事待ちはそれぞれ最長2分、流し込み中は区切りが30秒来なければ切る。事前接続は30秒使われなければ切る。自動のやり直しなし |
| Mac 経由の Gemini | 録音1件ごとに1接続。返事が終わったら切る。最長2分で打ち切り（`MAX_SESSION_SECONDS`）、無通信60秒でも切る。自動のやり直しなし。実測で返事の後の接続は0本 |

- 流し込みは、押し方が最後の区切りまで分からないので、**どれかの押し方が Gemini Live のとき、1秒以上の録音はいったん全部 Gemini に送る**。行き先が違えば返事は作らせないので、かかるのは送った音声の分だけ。

## 4. 端末の状態

### Android（moto g24、USB で Mac mini に接続、`adb` は `~/Library/Android/sdk/platform-tools/adb`）

- debug ビルドを `./gradlew :androidApp:assembleDebug` → `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` で入れている（最新 master `be6e54a8` 相当）。
- 文字起こしは端末内の Google 音声認識（`PlatformSpeechRecognizer.android.kt`）。
- アプリの記録：`adb logcat -v time`。タグ `RingAudioTap`（区切り）、`GeminiLive`（送信・最初の音声・返事）、`RingSync`（転送）。
- 「Firestore にアップロードできない（未ログイン）」のエラーが繰り返し出るが、ダミー Firebase のためで無害。

### iPhone

- 今日の変更（アプリ内 Gemini・流し込み）は iOS 用にコンパイルが通ることまで確認。**実機には入れていない。**
- 入れるとき：普通のターミナルで `~/Projects/Pebble-mobileapp/tools/mac-build/ios-build-install.sh`（エージェントのシェルは署名できない）。
- iOS はバックグラウンドのアプリを一時停止するので、画面を閉じている間の Mac からの返事が遅れる問題は未解決（`WAKE_WITHOUT_PUSH_RESEARCH.md`）。アプリ内 Gemini はリングの通信でアプリが起きている間に完結するので、この影響を受けにくい見込み（未確認）。

## 5. Mac の常駐サービス（launchd）

| サービス | アドレス | ログ | 使う経路 |
|---|---|---|---|
| 返事サーバー `com.ramo.reply-server` | `0.0.0.0:8765` | `~/Library/Logs/reply-server.log` | Mac 経由の Gemini・秘書 |
| pebble-ring `com.ramo.pebble-ring` | `127.0.0.1:8766` | `~/Library/Logs/pebble-ring.log` | 秘書 |
| Catchball `com.ramo.catchball` | `127.0.0.1:47821`（WebUI `:47822`） | `~/Library/Logs/catchball.log` | 秘書 |

- 再起動：`launchctl kickstart -k gui/$(id -u)/com.ramo.<名前>`。launchd は `~/.zshrc` を読まないので、キーは各 `.env` に書く。
- 秘書の起動：普通のターミナルで `~/Projects/Pebble-mobileapp/tools/skill/pebble-ring/secretary.sh`（Codex は末尾に ` codex`）。
- 設定ファイル（git に入らない。値はここに書かない）
  - `tools/reply-server/.env`：`REPLY_TOKEN`、`RELAY_URL=http://127.0.0.1:8766/stt`、`RELAY_TOKEN`、`GEMINI_API_KEY`
  - `tools/skill/pebble-ring/.env`：`PEBBLE_RING_TOKEN`、`REPLY_TOKEN`、`SUMMARY_*`（`claude-haiku-5-5`、effort medium、max_tokens 4000）、`TTS_URL`、`PEBBLE_JEV_API_KEY`
- Mac 経由の Gemini の会話履歴は `tools/reply-server/conversation.jsonl`（消す：`python3 tools/reply-server/reply_server.py clear-history`）。アプリ内 Gemini の履歴とは別。

## 6. 今日分かったこと・直したこと

- **リングは長押し中の誤検出の押し方を報告する**（`long short short …`、`long long long`）。前はどの押し方にも当たらず Index agent に回っていた。録音の振り分けでは、最初の長押しより後の押し方を無視する（`RingGesture.forRecordingSequence`）。短押しは、それぞれ0.001秒ほどの別の録音としても届く。
- `ExperimentalDevices` の部品が Koin の `singleOf` の上限（22個）を超えたので、`single { ExperimentalDevices(get(), …) }` で登録している。部品を足したら `get()` の数も足す。
- Haversine のソースは非公開（GitHub `coredevices/haversine-kmp` は 404、Maven Central の sources.jar は空）。API は `javap`（Android の aar）と `klib dump-metadata`（iOS）で読める。

## 7. 残っている課題

1. iPhone 実機でのアプリ内 Gemini の確認。
2. Gemini に時刻（と必要なら検索）を渡すか。今は指示文だけ。
3. 流し込みでは前処理（雑音除去・音量4倍前後）を通さない。聞き取りの精度が落ちていないかを見る（流し込みで試した2回は、返事が発話の内容に合っていた）。
4. 返事サーバーの `replies.jsonl` を消すと番号が1に戻り、スマホに新しい返事が届かなくなる（直し方は `tools/reply-server/README.md`）。

## 8. 関連資料

| 内容 | 場所 |
|---|---|
| アプリ内 Gemini の設計 | `docs/tts-reply/CLIENT_GEMINI_LIVE_DESIGN.md` |
| 返事の再生（Reply audio）の最初の設計 | `docs/tts-reply/DESIGN.md`、`FEED_REPLIES_PLAN.md`、`MAC_BUILD_AND_TEST.md` |
| iOS をプッシュなしで起こす方法の調査 | `docs/tts-reply/WAKE_WITHOUT_PUSH_RESEARCH.md` |
| 返事サーバー（Mac 経由の Gemini・転送） | `tools/reply-server/README.md`・`API.md`・`SENDER_SPEC.md` |
| pebble-ring・秘書 | `tools/skill/pebble-ring/README.md`・`SKILL.md`・`SECRETARY.md` |
| アプリの Webhook 仕様 | `experimental/src/commonMain/kotlin/coredevices/ring/external/indexwebhook/INDEX_WEBHOOK_API.md` |
| テスト | `./gradlew :experimental:testAndroidHostTest --tests 'coredevices.ring.external.geminilive.*'`（`replyaudio.*`・`indexwebhook.*`・`coredevices.ring.service.button.*` も）、`python3 -m unittest discover tools/reply-server`、`python3 -m unittest discover tools/skill/pebble-ring` |
