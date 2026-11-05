# アプリ内 Gemini Live（クライアント側の音声会話）— 設計書

- 作成日：2026-11-05
- 対象：iPhone / Android アプリ（`experimental` モジュール）
- 目的：リングで話したら、**Mac を通さず、アプリから直接 Gemini Live と会話**し、返事を**届いた端からストリーム再生**する。遅延を最小にする。
- 方針：既存の Mac 経由の仕組み（返事サーバー・秘書）は残し、**設定で切り替える**。会話の保存は **B（返事ログをチャット形式）＋ A（会話履歴20往復）**。

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

## 2. 設定（Index 設定 → Reply audio に追加）

| 項目 | 内容 | 既定 |
|---|---|---|
| **Gemini Live** | ON / OFF。ON のとき、下のジェスチャーの録音はアプリ内で Gemini と会話する | OFF |
| **ジェスチャー** | Hold & talk / Double click & hold | Hold & talk |
| **API キー** | Gemini の API キー。安全な保存場所（iOS はキーチェーン）に保存。画面には伏せ字で表示 | 空 |
| **会話をリセット** | 会話履歴（A）を消して新しい会話にするボタン | ― |

- モデルは `gemini-3.8-live`（Mac 側で実在と動作を確認済み）。声・指示文は Mac 側と同じ既定値。
- OFF、またはキーが空のときは、今までどおり（ジェスチャーの送り先・Webhook・Mac 経由の返事）。
- ON のジェスチャーの録音は **Gemini だけ**に回す（そのジェスチャーの Webhook・Index エージェント・文字起こしは動かさない）。速さを優先し、二重に返事が来ないようにするため。もう一方のジェスチャーは今までどおり使える。

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
| `GeminiLivePreferences` | ON/OFF・ジェスチャー（Settings）、API キー（`IntegrationTokenStorage`） | `IndexWebhookPreferences` / `IndexWebhookSigningSecretStorage` |
| `GeminiConversationHistory` | A：20往復の会話履歴 | `ReplyAudioLog` の保存方法 |
| `GeminiLiveSessionManager` | 転送中に先に接続（`RingSync.ringEvents` の `Transfer.InProgress` を購読）。使われなければ30秒で閉じる。1回の会話ごとに1セッション | `WisprFlowTranscriptionService` の `PreconnectedSession` |
| `GeminiLiveRecordingOperation` | 録音（`RecordingStorage.openRecordingSource`）を読んで送り、届いた音声を `ReplyStreamPlayer` へ流し、終わったら返事ログ（B）と履歴（A）に保存 | `IndexWebhookUploadRecordingOperation` |
| `ReplyPlaybackLock` | Mac 経由の返事とアプリ内の返事が同時に鳴らないようにする共通の順番待ち | ― |

既存ファイルへの変更（最小限）：

| ファイル | 変更 |
|---|---|
| `RecordingOperationFactory.kt` | Gemini Live が ON のジェスチャーなら `GeminiLiveRecordingOperation` を返す（Webhook を被せる場所と同じ所で分岐。録音処理そのものは変えない） |
| `ReplyAudioModels.kt` / `ReplyAudioLog.kt` | `userText`・`source` の追加、アプリ内の返事を保存する関数 |
| `ReplyAudioService.kt` | 再生を `ReplyPlaybackLock` 経由にする |
| `ReplyAudioDialog.kt` / `ReplyFeedSection.kt` | 設定項目の追加、ログに「自分が話した内容」を表示 |
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
- 振り分け：ON のジェスチャーだけ `GeminiLiveRecordingOperation` になる、OFF・キー空なら今まで通り
- 事前接続：転送開始で接続、30秒使われなければ閉じる、使ったら次は新しく接続

実機確認（Mac mini）：

| No | 状態 | 合格条件 |
|---|---|---|
| G1 | アプリ表示中 | 転送完了から約2秒以内に鳴り始める。ログに自分の発話と返事 |
| G2 | ロック中 | 同上 |
| G3 | 続けて質問 | 前の会話を踏まえて答える |
| G4 | 会話をリセット | 前の会話を覚えていない |
| G5 | OFF にする | 今まで通り（Webhook・Mac 経由）に戻る |
| G6 | キーを間違える | ログに理由が出て、アプリは落ちない |

## 7. 作業の順番

1. `GeminiLiveClient`（送受信）＋単体テスト
2. 会話履歴（A）と返事ログの拡張（B）＋単体テスト
3. `GeminiLiveRecordingOperation` と振り分け、`ReplyPlaybackLock`＋単体テスト
4. 事前接続（`GeminiLiveSessionManager`）＋単体テスト
5. 設定画面とログ表示
6. Mac mini でビルド・実機確認（G1〜G6）
