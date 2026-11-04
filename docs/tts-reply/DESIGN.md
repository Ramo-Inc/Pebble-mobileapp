# 返事音声の自動再生（TTS Reply）設計書

- 作成日：2026-11-04
- 対象ブランチ：`feature/tts-reply-playback`（`master` = 公式 `coredevices/mobileapp@38ab8a0` から分岐）
- 元資料：`pebble_index01_ios_tts_modification_spec.md`（要件定義・改修指示書）

## 1. やりたいこと

```text
[既存・変更しない]
リング → iPhone Pebbleアプリ（録音・文字起こし）→ Webhook → Mac（AIエージェント + TTS）

[今回作る]
Mac の返事置き場 ──(iPhoneが取りに行く)──▶ iPhone Pebbleアプリ
                                              ├ 返事音声をスピーカーで自動再生
                                              └ 返事ログ（文字 + 音声の聞き直し）
```

- リングで話す → Macのエージェントが返事を作る → **iPhoneが操作なしで返事を声で流す**。
- 1回の発話に対して返事は**複数回**ある（すぐ返る1回目、数分〜数十分後の結果）。
- 聞き逃した返事は、アプリ内の**返事ログで文字でも音声でも確認・再生**できる。

## 2. 前提（確認済みの事実）

| 事実 | 根拠 |
|---|---|
| ロック中・スリープ中でも、リング録音→文字起こし→Webhook送信がiPhone上で完了している | ユーザー実機確認（2026-11-04） |
| iOSのバックグラウンドモードに `audio` / `bluetooth-central` / `remote-notification` / `fetch` が既に宣言済み | `iosApp/iosApp/Info.plist:164-171` |
| Webhookは録音ごとに固定ID `X-Index-Delivery` を送る（= 返事の紐付けキーに使える） | `experimental/.../external/indexwebhook/INDEX_WEBHOOK_API.md`、`IndexWebhookApi.kt` |
| M4A(AAC)をバイト列から再生する既存プレイヤーがある（iOS: `AVAudioPlayer`、Playbackカテゴリ） | `experimental/src/commonMain/kotlin/coredevices/ring/util/AudioPlayer.kt`、`AudioPlayer.ios.kt` |
| 永続キュー＋再試行の既存基盤がある | `util/src/commonMain/kotlin/coredevices/util/queue/PersistentQueueScheduler.kt` |
| Ringのデータベースは Room（現在 version 45） | `experimental/.../database/room/RingDatabase.kt:101` |

この事実から、**返事の受信と再生も、録音処理と同じくアプリが動いている間にバックグラウンドで行う**設計とする。

## 3. 方針

1. **既存の録音・文字起こし・Webhook処理は一切変更しない。** 返事機能は独立したパッケージとして追加する。
2. **iPhoneがMacへ取りに行く（ロングポーリング）。** iPhoneは「新しい返事があれば返して、なければ最大25秒待って」というリクエストを繰り返す。チャットアプリ等で一般的な方式で、Mac側も単純なHTTPSサーバーで済む。
3. **音声形式は M4A(AAC)。** 既存の `AudioPlayer.playAAC()` をそのまま使う。
4. **共通コード（commonMain）で作る。** プレイヤーがAndroidにもあるので、Androidでもほぼそのまま動く（`CLAUDE.md` の両OS対応ルールに沿う）。
5. 数十分後の返事も同じ仕組みで受け取る。実機で取りこぼしが出た場合のみ、追加対策（プッシュ通知等）を別途検討する。

## 4. Mac側API（返事置き場）の仕様

Mac側はこの仕様で新規に作る。すべて **HTTPS**、すべてのリクエストに `Authorization: Bearer <token>` を付ける。

### 4.1 返事一覧の取得（ロングポーリング）

```
GET /v1/replies?after=<cursor>&wait=25
```

- `after`：前回受け取った最後の `cursor`。初回は省略（＝直近の未取得分）。
- `wait`：新しい返事がないとき、最大何秒待ってから空で返すか。
- 新しい返事ができたら、待たずに即座に返す。

レスポンス `200 OK`：

```json
{
  "replies": [
    {
      "reply_id": "rep_01JC...",
      "request_id": "<X-Index-Delivery の値>",
      "cursor": 1042,
      "kind": "ack",
      "text": "了解、調べます。",
      "audio": {
        "url": "/v1/replies/rep_01JC.../audio",
        "content_type": "audio/mp4",
        "size_bytes": 48211,
        "duration_ms": 2300
      },
      "created_at": "2026-11-04T09:00:00Z",
      "expires_at": "2026-11-04T10:00:00Z"
    }
  ],
  "next_cursor": 1042
}
```

| 項目 | 内容 |
|---|---|
| `reply_id` | 返事ごとに一意。iPhone側の重複排除キー |
| `request_id` | どの録音への返事か（Webhookの `X-Index-Delivery`）。不明なら `null` 可 |
| `cursor` | Mac側で単調増加する番号。取りこぼし防止用 |
| `kind` | `ack`（すぐの返事）/ `result`（結果）/ `info`（その他）。表示用 |
| `text` | 返事の文字。返事ログに表示 |
| `audio` | 音声がない返事は `null`（文字だけログに残る） |
| `expires_at` | これを過ぎた返事は**自動再生しない**（ログには残す）。省略時は作成から60分 |

### 4.2 音声の取得

```
GET /v1/replies/{reply_id}/audio
```

- `200 OK`、`Content-Type: audio/mp4`、本体はM4A。
- iPhoneは **5MB超、または `audio/mp4` 以外は拒否**する。
- 音声URLは同じホスト配下の相対パスのみ受け付ける（別ホストへのリダイレクトは拒否）。

### 4.3 エラー

- `401`：トークン不一致 → iPhoneはポーリングを止め、設定画面にエラーを表示する。
- `5xx` / 通信エラー：数秒〜最大1分の間隔で再試行する。

## 5. iPhoneアプリ側の構成

追加先：`experimental/src/commonMain/kotlin/coredevices/ring/external/replyaudio/`（新規パッケージ）

| 部品 | 役割 |
|---|---|
| `ReplyAudioPreferences` | MacのURL、ON/OFF。トークンは既存の署名シークレットと同じくKeychainに保存 |
| `ReplyApi` | 4章のAPIクライアント（Ktor、既存 `ApiClient` を継承） |
| `ReplyPoller` | アプリが動いている間、ロングポーリングを繰り返す。新着を保存して再生キューへ渡す |
| `ReplyEntity` / `ReplyDao` | 返事ログのDB（Room、version 45→46のマイグレーション追加） |
| `ReplyPlaybackQueue` | 届いた順に1件ずつ再生。重複排除・期限切れスキップ |
| 返事ログ画面 | 文字一覧、聞いたかどうかの目印、再生ボタン |
| 設定シート | URL・トークン入力、接続テスト、ON/OFF（Index設定の「Webhook」の隣に追加） |

### 5.1 返事の流れ

```text
ReplyPoller ─ GET /v1/replies?after=… ─▶ Mac
   │ 新着あり
   ├─ ReplyEntity を保存（文字）
   ├─ 音声をダウンロードして端末に保存
   └─ ReplyPlaybackQueue へ
          ├ 期限切れ → 再生しない（ログに「未再生」で残る）
          ├ 再生中 → 終わってから次を再生（順番待ち）
          └ 再生 → AudioPlayer.playAAC() → 再生済みに更新
```

### 5.2 返事ログ（DB項目）

| 項目 | 内容 |
|---|---|
| `replyId`（一意） | Macの `reply_id` |
| `requestId` | どの録音への返事か |
| `kind` / `text` | 返事の種類と文字 |
| `audioFile` | 端末に保存した音声ファイル名（音声なしは `null`） |
| `createdAt` / `expiresAt` | Mac側の作成日時・期限 |
| `status` | `Received` → `Playing` → `Played` / `Skipped`（期限切れ）/ `Failed` |
| `playedAt` | 自動再生または手動再生した日時 |

### 5.3 既定の動作（変更したい場合は指示してください）

| 項目 | 既定 |
|---|---|
| 再生先 | iOSの標準に従う（イヤホン・Bluetooth接続中はそちら、なければスピーカー） |
| マナーモード中 | **鳴らす**（既存プレイヤーと同じPlaybackカテゴリ） |
| 音楽再生中 | 音楽を一時停止して返事を流す（既存プレイヤーの動作） |
| 電話中 | iOSが自動で中断。通話後は自動再生せず、ログに未再生として残す |
| 連続で届いた場合 | 届いた順に1件ずつ再生 |
| 保存期間 | 直近200件（音声ファイルも同じ件数で削除） |
| ログに残す情報 | 返事ID・状態・時刻のみ。トークンや音声の中身はログ出力しない |

## 6. 変更しないもの

- リングの録音・BLE転送・文字起こし・エージェント処理（`CLAUDE.md` の禁止事項）
- Webhook送信（`IndexWebhookApi` / `IndexWebhookDeliveryQueue` 等）
- 既存の `AudioPlayer` の実装（呼び出すだけ）

## 7. 作業の分け方（ブランチ運用）

`master` から機能ごとに作業ブランチを切り、完成したら `master` にマージする。

| 順 | 作業 | 確認方法 |
|---|---|---|
| 1 | 返事のデータ型・JSON解析・再生キューの判定ロジック（重複・期限・順番） | 単体テスト |
| 2 | 返事ログDB（Room 46）とマイグレーション | 単体テスト |
| 3 | Mac APIクライアント＋ロングポーリング | 単体テスト（モックサーバー） |
| 4 | 自動再生（既存 `AudioPlayer.playAAC`）＋設定シート | 実機：アプリ表示中に再生 |
| 5 | 返事ログ画面（文字一覧・聞き直し） | 実機 |
| 6 | 動作確認用のMac側サンプルサーバー（4章の仕様どおり、最小構成） | Mac上で起動しiPhoneから受信 |
| 7 | 実機検証（ロック中・数分後・数十分後・連続返事など） | 8章の表 |

※ このクラウド環境ではiOSのビルドはできないため、ビルドと実機確認はMacのXcodeで行う。単体テストはこちらで実行する。

## 8. 実機確認の項目

| No | iPhoneの状態 | 返事のタイミング | 期待 |
|---|---|---|---|
| T1 | Pebbleアプリ表示中 | すぐ | 自動再生、ログに文字 |
| T2 | 他のアプリ表示中 | すぐ | 自動再生 |
| T3 | ロック中 | すぐ（1回目の返事） | 自動再生 |
| T4 | ロック中 | 数分後（結果） | 自動再生 |
| T5 | ロック中 | 数十分後（結果） | 自動再生 |
| T6 | 返事が2件続けて届く | — | 順番に1件ずつ再生 |
| T7 | 同じ返事が2回届く | — | 1回だけ再生 |
| T8 | 期限切れの返事 | — | 再生せずログに残る |
| T9 | 音楽再生中 / イヤホン接続中 | — | 5.3の既定どおり |
| T10 | 通信が切れて復帰 | — | 取りこぼし・重複なし |
| T11 | アプリを手動で終了していた | — | 次にアプリが起きた時、ログに届く |

T4・T5で取りこぼしが出た場合は、ログの時刻（ポーリング停止・再開・再生）から原因を特定し、対策を別途提案する。
