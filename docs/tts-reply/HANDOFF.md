# 引き継ぎ（2026-11-05 夜）

次のセッションは、まずこの文書と [ARCHITECTURE.md](ARCHITECTURE.md)（どの機能がどこにあるか）を読んでください。手順は [BUILD_AND_TEST.md](BUILD_AND_TEST.md)。食い違ったら、コードとこの文書を信じてください。

## 0. ユーザーとのやり取りのルール（最重要）

- **返答は必ず日本語。** 長いツール出力のあとも日本語。結論を先に。
- **推測を事実のように言わない。** ログ・コード・依存ライブラリの中身で確かめてから言う。「できない」と言う前に、ライブラリの公開 API まで調べる。今日は確かめずに「Haversine は途中の音声を出さない」と言い、誤りだった（実際は公開項目を包めば取れた）。
- **呼び名を混ぜない。** 「Gemini」は2種類ある。必ず **アプリ内 Gemini**（ボタンの行き先「Gemini Live」）と **Mac 経由の Gemini**（Webhook の Recording only）で呼び分ける。今日はこれを混ぜて、ユーザーを混乱させた。
- **頼まれたものを作る。** 代わりの案を出すのは、作れない理由を確かめた後。スマホのマイクで話す案は「リングの意味がない」と却下済み。
- 細かい実装判断は聞かずに決めて伝える。聞くのは作るものが変わる判断だけ。
- 長い作業の途中でも、数分おきに一言で今していることを伝える（黙ると「止まっている」と思われる）。
- キー・トークンの値は画面・ログ・コミットに出さない。端末の設定ファイルを読むときは、必要な行だけを grep する（今日は `reply_audio_token` の値を作業ログに出してしまった。ユーザーには伝え済み）。

## 1. 今の状態

| もの | 状態 |
|---|---|
| コード | すべて `master` に push 済み（機能は `a99d8c1e` まで。その後はドキュメントの整理） |
| Android（moto g24、USB 接続） | 最新の debug ビルドを入れてある。**Hold & Talk → Gemini Live（アプリ内 Gemini）**、Double click & hold → Web search。アプリに Gemini の API キーを保存済み |
| iPhone | 今日の変更（アプリ内 Gemini・流し込み）は**入っていない**。コンパイルが通ることだけ確認 |
| Mac | 返事サーバー・pebble-ring・Catchball が常駐。Mac 経由の Gemini は有効（`Voice replies: gemini-3.8-live`）。秘書は必要なときに `secretary.sh` で起動 |
| リング | Android とペアリング済み。ファームウェア 3.86.0 |

実測（ボタンを離す→鳴り始め）：アプリ内 Gemini 3.6〜5.3秒、Mac 経由の Gemini 6.5〜7.3秒。内訳と理由は [CLIENT_GEMINI_LIVE_DESIGN.md](CLIENT_GEMINI_LIVE_DESIGN.md) §4。

## 2. ユーザーの返事待ち

**リングのファームウェア更新をどうするか。**

- 更新の確認には Pebble のアカウントへのログインが要る（`util/src/commonMain/kotlin/coredevices/api/EngDashOtaApi.kt` → `dash.repebble.com/api/ota/latest`、Firebase 認証）。自前ビルドはダミーの Firebase なのでログインできず、毎回 `401`。公式アプリの設定を抜き出して使うのは、なりすましになるのでやらない（ユーザーにもそう説明済み）。
- 提案した2案：
  - **A**（おすすめ）：Android の自前アプリを消す → Play ストアの公式「Pebble」でログイン・ペアリング・更新 → 公式を消して自前を入れ直し、ペアリングと設定（行き先、API キー）を戻す。公式と自前は同じアプリ ID `coredevices.coreapp` なので共存できない。
  - **B**：iPhone に App Store の「Pebble Core」を足す（自前は `com.ramo.coreapp` なので共存できる）→ リングをリセットして iPhone で更新 → リセットして Android にペアリングし直す。
- A で進める場合は、消す前に設定を控える。adb で戻せるもの：アプリの入れ直し、行き先の選択、API キーの入力（画面を adb で操作し、値は `~/.zshrc` の `GEMINI_API_KEY` から表示せずに入れる）。
- リングのリセットは「SOS」の押し方（短く3回・長く3回・短く3回）。

## 3. 残っている課題

1. **リングの長押しの反応が悪い**（ユーザーの不満）。2026-11-05 の17回中10回で、長押しの最中に余分な押し方が記録された。アプリ側は吸収済み（最初の長押しで判定）。公式の説明に強さの指定はなく、「同じ力で押さえ続ける」と案内した（記録からの推測）。新しいファームウェアで直るかは §2 しだい。
2. **iPhone 実機でのアプリ内 Gemini の確認**（[BUILD_AND_TEST.md](BUILD_AND_TEST.md) §5 の G7）。iOS の差し込み（`RingAudioTap.ios.kt`）は、ObjC の弱参照を避けるため包んだ物を強く持っている。
3. **Gemini に時計も検索もない。** 「今何時」を間違える。指示文に今の時刻を入れるのは簡単にできる（ユーザーにはまだ聞いていない）。
4. **流し込みは前処理を通さない。** 聞き取りの精度を引き続き見る。
5. **画面を閉じた iPhone で、Mac からの返事がすぐ鳴らない**（iOS のアプリ一時停止）。調査は [WAKE_WITHOUT_PUSH_RESEARCH.md](WAKE_WITHOUT_PUSH_RESEARCH.md)。無料 Apple ID ではプッシュ通知が使えない。Android では起きない。
6. 返事ログに、秘書経路の相づち・聞き返しも並ぶ。隠すかはユーザー未回答。

## 4. 守ること

- **リングの録音・転送・前処理・文字起こしのコードは変えない**（`CLAUDE.md`）。足すときは外側に足す（今日の `RingAudioTap` のように、ライブラリの公開項目を包んで元の処理を先に呼ぶ）。
- **Android と iPhone の両方で動かす**（`CLAUDE.md`）。片方でできないときは、作る前にユーザーに言う。
- 修正は `master` から作業ブランチを切り、テストを通してから `master` に fast-forward で入れて push。`iosApp/Podfile.lock`（手元のビルド用）と `cactus-native/.cxx/`（ビルドの残り）はコミットしない。
- `~/.claude*` と `~/.codex/config.toml` はエージェントから変えられない。署名（codesign）もできない。要るときは、普通のターミナルで実行してもらうコマンドを渡す。
- 返事サーバーの `replies.jsonl` を消さない（番号が戻り、アプリに返事が届かなくなる）。
- 依存ライブラリ（Haversine など）のソースは非公開。API は Android の aar を `javap -p -c`、iOS の klib を `~/.konan/kotlin-native-prebuilt-*/bin/klib dump-metadata` で読める（`~/.gradle/caches/modules-2/files-2.1/io.github.coredevices.haversine/`）。

## 5. 今日やったこと（詳しくはコミット）

`git log --oneline 663583f6..master`。要点：

- Mac 経由の Gemini を有効化（`.env` に `GEMINI_API_KEY`）し、1回の接続に2分の上限（`bda78a7b`）。
- アプリ内 Gemini を使えるようにした：ビルド修正（`07e3f392`）、テスト修正（`729dfeb5`）、接続の上限（`9a893ffc`）、押し方の誤検出の吸収（`411f0dfd`・`a99d8c1e`）。
- リングの区切りを Gemini に流し込む（`be6e54a8`）。6.0〜6.7秒 → 3.6〜5.3秒。
- ドキュメントの全面書き直し（この文書、`ARCHITECTURE.md`、`BUILD_AND_TEST.md`、`CLIENT_GEMINI_LIVE_DESIGN.md`、返事サーバーの説明書）。古い `DESIGN.md`・`FEED_REPLIES_PLAN.md`・`MAC_BUILD_AND_TEST.md` は `ARCHITECTURE.md`・`BUILD_AND_TEST.md` に吸収した（git の履歴には残っている）。

## 6. おすすめのスキル

| スキル | 使う場面 |
|---|---|
| `diagnosing-bugs` | 「返事が来ない」「途中で切れた」。まず adb の記録で `RingAudioTap`・`GeminiLive`・`Debounced gesture` の時刻を並べる |
| `tdd` | アプリ内 Gemini や押し方の判定を変えるとき（`GeminiLiveTest.kt`、`GestureRoutingTest.kt`） |
| `research` | リングのファームウェアや Gemini Live API の仕様を調べるとき。Pebble 公式の更新履歴は Notion で、`https://ndocs.repebble.com/api/v3/` の公開 API から JSON で読める |
| `code-review` | まとまった変更を master に入れる前 |
| `claude-api` | pebble-ring の要約（Claude Haiku）を触るとき |
