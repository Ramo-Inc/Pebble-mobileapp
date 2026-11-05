# アプリ内 Gemini Live（クライアント側の音声会話）— 設計書

> **注記（2026-11-05 夜）**：§1 の「転送が終わってから送る」流れは、その後「区切りが届くたびに流し込む」方式に変えた（§8）。今の状態は `docs/tts-reply/HANDOFF.md` §2。

- 作成日：2026-11-05
- 対象：iPhone / Android アプリ（`experimental` モジュール）
- 目的：リングで話したら、**Mac を通さず、アプリから直接 Gemini Live と会話**し、返事を**届いた端からストリーム再生**する。遅延を最小にする。
- 方針：既存の Mac 経由の仕組み（返事サーバー・秘書）は残し、**既存のボタン設定の送り先に「Gemini Live」を足して切り替える**。会話の保存は **B（返事ログをチャット形式）＋ A（会話履歴20往復）**。

## 1. 流れと遅延

```text
今（Mac 経由）：
リング →(転送)→ iPhone → Webhook → Mac 返事サーバー → Gemini → Mac →(1秒ごとの確認)→ iPhone → 再生

新（アプリ内）：
リング →(転送)→ iPhone ──WebSocket──▶ Gemini Live ──音声の断片──▶ iPhone で即再生
            │
            └ 転送が始まった時点で Gemini に接続・初期設定を済ませておく
```

| 区間 | 新しい流れでの扱い | 見込み |
|---|---|---|
| リング → iPhone の転送 | 変わらない（リングのファームウェアが、ボタンを離したあとにまとめて送る。`RingSync.kt` の `TransferComplete`） | 今と同じ |
| Gemini への接続・初期設定 | **転送中に先に済ませる**（実測 約0.75秒を隠す） | 0秒 |
| 録音の前処理 | 既存のまま（ハイパス・音量、数ミリ秒） | ほぼ0秒 |
| 録音を Gemini へ送る | 16kHz・16bit・モノラルの生データを**変換なし**でそのまま送る | 約0.1秒 |
| Gemini の最初の音声 | Gemini 側の処理（実測：送り終わってから約1.3秒） | 約1.3秒 |
| 再生開始 | 届いた端から再生（0.3秒だけためる） | 約0.3秒 |
| **合計（転送完了 → 鳴り始め）** | | **約1.5〜2秒** |

なくなるもの：Webhook、Mac、1秒ごとの確認、M4A の変換、iPhone 上での文字起こし（Gemini が話した内容の文字も返すため）。

## 2. 設定（既存のボタン設定に選択肢を1つ足すだけ）

アプリには、ボタンの押し方（長押し／1回押してから長押し）ごとに「録音をどこへ送るか」を選ぶ画面がすでにある（`ui/screens/settings/ButtonSwitchboard.kt`、`service/button/RingGestureRouting.kt`）。今の選択肢は Index agent / Local agent / Web search / Webhook only / Nothing。

ここに **「Gemini Live」** を1つ足す。

- 押し方ごとに Gemini Live を選べば、その押し方で話したときは自動でアプリ内の Gemini 会話になる。新しい ON/OFF やジェスチャー選択の設定は作らない。
- Gemini Live を選んだときだけ、同じ画面の下に次が出る（Webhook only を選んだときに Webhook 設定が出るのと同じ作り）。
  - **API キー**：安全な保存場所（iOS はキーチェーン）に保存。伏せ字で表示。
  - **会話をリセット**：会話履歴（A）を消して新しい会話にする。
- 他の選択肢と同じく、Gemini Live を選んだ押し方の録音は Gemini だけに回る（他の送り先は「Also send to webhook」を ON にしたときの Webhook 以外は動かない）。
- キーが空のまま選ばれていたら、返事ログに「API キーが未設定です」と残して何もしない。
- モデルは `gemini-3.8-live`（Mac 側で実在と動作を確認済み）。声・指示文は Mac 側と同じ既定値。

## 3. 会話の保存（B ＋ A）

1つの保存先から、2つの用途に使う。

### A. 会話履歴（Gemini に渡す文脈）

- 直近 **20往復**（「ユーザーが話した内容」と「返事」の組）。
- 新しいセッションの初期設定で、指示文の後ろに「ここまでの会話」として付ける（Mac 側 `gemini_live.py` の `history_text` と同じ形）。
- 保存：アプリの設定（Settings）に JSON。20往復を超えたら古いものから消す。
- 「会話をリセット」で消せる。

### B. 返事ログをチャット形式に

- 今の返事ログ（`ReplyAudioLog`、最新200件、Index タブの Replies と Reply audio 画面）に、**「自分が話した内容」**を追加して表示する。

```text
11/5 14:02  🎤 明日の天気は？
            🔊 明日は晴れ、最高気温は18度の予報です。   [▶ 再生]
```

- 返事の音声は、再生しながら受け取った PCM を WAV にして保存する → あとから聞き直せる（今の仕組みと同じ）。
- Mac 経由の返事は今までどおり（「自分が話した内容」は空）。

### データの変更

`ReplyLogEntry` に次を足す（既存のデータはそのまま読める既定値付き）。

| 追加する項目 | 内容 |
|---|---|
| `userText: String?` | Gemini が聞き取ったユーザーの発話（`inputTranscription`） |
| `source: String` | `"server"`（Mac 経由。既定）/ `"gemini"`（アプリ内） |

アプリ内の返事の `id` は、Mac 側の番号とぶつからないように**負の数**（`-(作成時刻ミリ秒)`）にする。

## 4. 部品

新規（`experimental/src/commonMain/kotlin/coredevices/ring/external/geminilive/`）：

| 部品 | 役割 | 下敷き |
|---|---|---|
| `GeminiLiveClient` | Live API の WebSocket セッション。初期設定（モデル・音声出力・自動発話検出オフ・聞き取り/返事の文字起こしオン・履歴入り指示文）、`activityStart` → 音声 → `activityEnd`、受信（音声断片・文字・`turnComplete`）を Flow で返す | Mac 側 `tools/reply-server/gemini_live.py`（実 API で検証済み）、アプリ内 `WisprFlowTranscriptionService.kt`（Ktor WebSockets の使い方） |
| `GeminiLiveKeyStorage` | API キー（`IntegrationTokenStorage`） | `IndexWebhookSigningSecretStorage` |
| `GeminiConversationHistory` | A：20往復の会話履歴 | `ReplyAudioLog` の保存方法 |
| `GeminiLiveSessionManager` | 転送中に先に接続（`RingSync.ringEvents` の `Transfer.InProgress` を購読）。使われなければ30秒で閉じる。1回の会話ごとに1セッション | `WisprFlowTranscriptionService` の `PreconnectedSession` |
| `GeminiLiveRecordingOperation` | 録音（`RecordingStorage.openRecordingSource`）を読んで送り、届いた音声を `ReplyStreamPlayer` へ流し、終わったら返事ログ（B）と履歴（A）に保存 | `IndexWebhookUploadRecordingOperation` |
| `ReplyPlaybackLock` | Mac 経由の返事とアプリ内の返事が同時に鳴らないようにする共通の順番待ち | ― |

既存ファイルへの変更（最小限）：

| ファイル | 変更 |
|---|---|
| `RingGestureRouting.kt` | 送り先に `GestureDestination.GeminiLive` を追加 |
| `ButtonSwitchboard.kt` | 選択肢に「Gemini Live」を追加。選んだときに API キーと会話リセットの行を出す |
| `RecordingOperationFactory.kt` | 送り先が `GeminiLive` なら `GeminiLiveRecordingOperation` を返す（他の送り先と同じ分岐に1行足す。録音処理そのものは変えない） |
| `ReplyAudioModels.kt` / `ReplyAudioLog.kt` | `userText`・`source` の追加、アプリ内の返事を保存する関数 |
| `ReplyAudioService.kt` | 再生を `ReplyPlaybackLock` 経由にする |
| `ReplyAudioDialog.kt` / `ReplyFeedSection.kt` | ログに「自分が話した内容」を表示 |
| `experimentalModule.kt` / `viewmodelModule.kt` / `ExperimentalDevices.kt` | DI 登録、起動時に事前接続の購読を開始 |
| `experimental/build.gradle.kts` | `libs.ktor.websockets` を追加（`util`・`libpebble3` で使用中のもの） |

変更しないもの：リングの録音・BLE 転送・前処理・文字起こし・エージェント処理・Webhook 送信。

## 5. 失敗したとき

| 状況 | 動き |
|---|---|
| キーが違う・モデルが使えない（接続直後に切断） | 返事ログに理由を文字で残す。再生はしない |
| 通信が途中で切れた | それまでに届いた音声は再生済み。ログには届いた分の音声と「途中で切れました」を残す |
| 事前接続が使えない（切れていた・期限切れ） | その場で接続し直す（約0.75秒遅くなるだけ） |
| 電話などで音が中断された | 再生をやめ、ログには音声を保存（あとで聞き直せる） |

ログ（Kermit、タグ `GeminiLive`）には、時刻付きで「接続」「初期設定完了」「送信完了」「最初の音声」「再生開始」「完了」を出す。キーと音声の中身は出さない。

## 6. テスト

単体テスト（`commonTest`）：

- 送るメッセージの形：初期設定（モデル・履歴入り指示文・自動発話検出オフ）、`activityStart` → 音声 → `activityEnd`
- 受信の解釈：音声断片・聞き取り文字・返事文字・`turnComplete`・エラー
- 会話履歴：20往復を超えると古いものから消える、再起動後も残る、リセット
- 返事ログ：アプリ内の返事が負の `id`・`userText` 付きで入る、Mac 経由の返事とぶつからない
- 振り分け：送り先が Gemini Live の押し方だけ `GeminiLiveRecordingOperation` になる、他の送り先は今まで通り
- 事前接続：転送開始で接続、30秒使われなければ閉じる、使ったら次は新しく接続

実機確認（Mac mini）：

| No | 状態 | 合格条件 |
|---|---|---|
| G1 | アプリ表示中 | 転送完了から約2秒以内に鳴り始める。ログに自分の発話と返事 |
| G2 | ロック中 | 同上 |
| G3 | 続けて質問 | 前の会話を踏まえて答える |
| G4 | 会話をリセット | 前の会話を覚えていない |
| G5 | ボタン設定で別の送り先に戻す | 今まで通り（Webhook・Mac 経由など）に戻る |
| G6 | キーを間違える | ログに理由が出て、アプリは落ちない |

## 7. 作業の順番

1. `GeminiLiveClient`（送受信）＋単体テスト
2. 会話履歴（A）と返事ログの拡張（B）＋単体テスト
3. `GeminiLiveRecordingOperation` と振り分け、`ReplyPlaybackLock`＋単体テスト
4. 事前接続（`GeminiLiveSessionManager`）＋単体テスト
5. ボタン設定の選択肢（Gemini Live・API キー・会話リセット）とログ表示
6. Mac mini でビルド・実機確認（G1〜G6）

## 8. 実装状況（2026-11-05 夜）

- 1〜6 を実装・実機確認済み（Android、moto g24）。コードは `experimental/src/commonMain/kotlin/coredevices/ring/external/geminilive/`、テストは `experimental/src/commonTest/kotlin/coredevices/ring/external/geminilive/GeminiLiveTest.kt`。
- 設計からの追加：Gemini Live を選んだ押し方には Webhook を被せない（`IndexWebhookConfig.sendsFor` で除外）。既存の Webhook ラッパーは、文字起こしを使う送り先以外の処理を「文字起こしだけ」に置き換えるため、被せると Gemini が動かなくなる。シートの「Also send to webhook」も出さない。
- ビルドの修正：`ExperimentalDevices` の部品が23個になり Koin の `singleOf` の上限（22個）を超えたので、`single { ExperimentalDevices(get(), …) }` で登録（`07e3f392`）。
- テストの修正：返事ログの保存先を渡す部分が呼ばれるたびに別のフォルダを返していたので、1つに固定（`729dfeb5`）。
- 接続の上限：初期設定と返事待ちを最長2分で打ち切る（`9a893ffc`）。
- 押し方の判定：リングは長押し中に誤検出の短押しを報告する（`long short short …`）ので、録音の振り分けでは最後の長押しより後の短押しを無視する（`411f0dfd`）。前は Index agent に回っていて、Gemini に届かなかった。
- **区切りの流し込み**（`be6e54a8`）：リングの区切りを、ライブラリの公開項目 `environment.transferDelegate` を包んで横から受け取り（`RingAudioTap`）、届くたびに Gemini に送る（`GeminiLiveStreamer`）。最後の区切りの押し方が Gemini Live なら、すぐ `activityEnd` を送って返事を受ける。録音処理の順番待ちの `GeminiLiveRecordingOperation` は、流し込み済みならそれを使い、答えられていなければ保存済みの録音で答える。1秒未満の録音（ボタン操作）は送らない。
- 実測（ボタンを離す→鳴り始め）：全部そろってから送る方式で 6.0〜6.7秒 → 流し込み方式で 3.6〜5.3秒。最後の区切りから `activityEnd` までは0.02秒。残りはリングが送り始めるまで（0.9〜1.8秒）、リングが送る時間（1.5〜1.7秒）、Gemini の考える時間（1.0〜1.9秒）。
- 未確認：iPhone 実機（コンパイルは通る）、G2（ロック中）、G4（会話をリセット）、G6（キーを間違える）。
