# 返事音声の自動再生（Reply audio）— MVP設計書

- 作成日：2026-11-04
- ブランチ：`feature/tts-reply-playback`（`master` = 公式 `coredevices/mobileapp@38ab8a0` から分岐）
- 方針：**一番簡単な仕組みで、まず動くところまで**。細かい制御は後回し。

## 1. やること

```text
[既存・変更しない]
リング → iPhone Pebbleアプリ（録音・文字起こし）→ Webhook → Mac（AIエージェント）

[今回作ったもの]
Mac: reply_server.py（返事置き場）
  ▲ エージェントが返事を登録（文字 + 音声）
  │
  └── iPhoneが3秒ごとに「新しい返事ある？」と取りに行く
        ├ 新しい返事の音声を自動再生（届いた順に1件ずつ）
        └ 返事ログに保存（文字 + 音声。あとから再生できる）
```

前提：ロック中・スリープ中でも、リング録音→文字起こしまでアプリが動いている（実機確認済み）。返事の取得と再生も同じくアプリが動いている間にバックグラウンドで行う。

## 2. 決めたこと（MVP）

| 項目 | 内容 |
|---|---|
| 届け方 | iPhoneが3秒ごとにMacへ問い合わせ（ポーリング） |
| 通信 | HTTP（またはHTTPS）＋トークン（`Authorization: Bearer`） |
| 音声の渡し方 | JSONの中にbase64で入れる（音声用の別URLなし） |
| 音声形式 | M4A推奨（MP3・WAVも可） |
| 再生 | 既存の `AudioPlayer.playAAC()` を使用。マナーモード中・音楽再生中も鳴らす |
| 自動再生 | 音声付きで未再生の返事はすべて、古い順に1件ずつ。アプリが止められていた間の分は、起きたとき（起動・再開時）にまとめて再生する |
| 返事ログ | 直近200件。文字・日時・未再生マーク（●）・再生ボタン |
| 保存場所 | ログ：アプリ設定（Settings）、音声：アプリのデータフォルダ `replies/` |
| 録音との紐付け | 返事に `request_id`（Webhookの `X-Index-Delivery`）を任意で付けられる。MVPでは表示に使わない |

## 3. Mac側API

### 返事の取得（iPhone → Mac）

```
GET /replies?after=<最後に受け取ったid>
Authorization: Bearer <token>
```

```json
{
  "replies": [
    {
      "id": 2,
      "request_id": "rec-123",
      "text": "結果です。…",
      "audio_base64": "AAAAHGZ0eXBNNEEg...",
      "created_at": 1793786333807
    }
  ]
}
```

- `id`：1から増える番号。iPhoneは最後に受け取った `id` を覚え、次回 `after` に付ける。
- `audio_base64`：なければ `null`（文字だけログに残る）。
- `created_at`：Unix時刻（ミリ秒）。

### 返事の登録（エージェント → Mac）

```
POST /replies
Authorization: Bearer <token>
Content-Type: application/json

{"text": "了解、調べます", "request_id": "rec-123", "audio_base64": "..."}
```

コマンドでも登録できる（`tools/reply-server/README.md` 参照）。

## 4. 追加・変更したファイル

新規（`experimental/src/commonMain/kotlin/coredevices/ring/external/replyaudio/`）：

| ファイル | 役割 |
|---|---|
| `ReplyAudioModels.kt` | 返事のデータ型、判定ルール（新着の抽出・自動再生するか・ログの上限） |
| `ReplyAudioPreferences.kt` | URL・トークン・ON/OFF・最後に受け取ったid |
| `ReplyAudioApi.kt` | `GET /replies` のクライアント |
| `ReplyAudioLog.kt` | 返事ログ（200件）と音声ファイルの保存 |
| `ReplyAudioService.kt` | 3秒ごとのポーリング、順番再生 |
| `ReplyAudioViewModel.kt` / `ReplyAudioDialog.kt` | 設定と返事ログの画面 |

既存ファイルへの変更（呼び出しの追加のみ）：

| ファイル | 変更 |
|---|---|
| `experimental/.../experimentalModule.kt` | DI登録を追加 |
| `experimental/.../ring/viewmodelModule.kt` | ViewModel登録を追加 |
| `experimental/.../ExperimentalDevices.kt` | アプリ起動時に `replyAudioService.start()` |
| `experimental/.../ui/screens/settings/IndexSettings.kt` | 設定画面に「Reply audio」行を追加 |

テスト：`experimental/src/commonTest/kotlin/coredevices/ring/external/replyaudio/ReplyAudioServiceTest.kt`

Mac側：`tools/reply-server/reply_server.py`（Python標準ライブラリのみ）

**変更していないもの：** リング録音・BLE・文字起こし・エージェント処理・Webhook送信・`AudioPlayer`。

## 5. 動かし方

1. Macで返事サーバーを起動（`tools/reply-server/README.md`）。
2. iPhoneアプリ：Index設定 → **Reply audio** → Server URL・Tokenを入力 → Auto-play replies をON → Save。
3. Macから返事を登録 → iPhoneで自動再生されることを確認。
4. エージェントの返事処理から `POST /replies`（または `push` コマンド）を呼ぶようにする。

## 6. 実機確認の項目

| No | iPhoneの状態 | 確認すること |
|---|---|---|
| T1 | アプリ表示中 | Macから登録 → 数秒で自動再生、ログに文字 |
| T2 | 他のアプリ表示中 | 同上 |
| T3 | ロック中・録音直後 | リングで話す → すぐの返事が自動再生 |
| T4 | ロック中・数分後 | 結果の返事が自動再生 |
| T5 | ロック中・数十分後 | 同上 |
| T6 | 返事が続けて届く | 順番に1件ずつ再生 |
| T7 | 聞き逃した返事 | ログに●が付き、Playで再生できる |

うまく鳴らないケースがあった場合は、アプリのログ（`ReplyAudioService` タグの `received` / `playback start` / `playback end`）の時刻から、どの段階で止まったかを特定して対策する。
