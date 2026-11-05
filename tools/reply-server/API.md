# 返事サーバー API 仕様書

- 対象：`tools/reply-server/reply_server.py`
- 利用者：iPhone アプリ（取得側・Webhook 送信側）、エージェント／TTS（登録側・転送の受け取り側。詳しくは [SENDER_SPEC.md](SENDER_SPEC.md)）

## 1. 共通

| 項目 | 内容 |
|---|---|
| ベースURL | `http://<Macのアドレス>:<ポート>`（ポートの初期値は `8765`） |
| 形式 | JSON（UTF-8）。レスポンスは `Content-Type: application/json; charset=utf-8` |
| 認証 | `Authorization: Bearer <トークン>`。サーバーを `--token` 付きで起動したときだけ必須。トークンなしで起動した場合は認証なし |
| 通信 | HTTP（暗号化なし）。信頼できるネットワーク（同じLAN・Tailscale）の中で使う |
| パス | `/replies` と `/webhook`。それ以外は `404` |

## 2. 返事のデータ

```json
{
  "id": 12,
  "request_id": "d2b7c1e0-...",
  "text": "結果です。",
  "audio_base64": "AAAAHGZ0eXBNNEEg...",
  "created_at": 1793786333807
}
```

| フィールド | 型 | 付ける人 | 内容 |
|---|---|---|---|
| `id` | 整数 | サーバー | 1から1ずつ増える番号。保存ファイルの最後の番号の続きから振る |
| `request_id` | 文字列 / `null` | 登録側 | 元の録音との紐付け（Webhook の `X-Index-Delivery`）。任意 |
| `text` | 文字列 | 登録側 | 返事の文字。空文字のこともある |
| `audio_base64` | 文字列 / `null` | 登録側 | 音声ファイル（M4A推奨・MP3・WAV）の中身をbase64にしたもの |
| `created_at` | 整数 | サーバー | 登録した時刻。Unix時刻（ミリ秒） |
| `stream` | 文字列 | サーバー | 音声AIの返事だけ。音声を作っている途中から聞ける PCM ストリームのパス（例 `/replies/12/stream`） |
| `sample_rate` | 整数 | サーバー | `stream` のサンプリングレート（Gemini Live は 24000） |

音声AIの返事は、最初の音声ができた時点で `text` が空・`audio_base64` が `null`・`stream` 付きで登録され、話し終わると同じ `id` のまま `text` と `audio_base64`（WAV）が入る。

## 3. `POST /replies` — 返事を登録する

リクエスト：

```
POST /replies
Authorization: Bearer <トークン>
Content-Type: application/json

{"text": "了解、調べます", "audio_base64": "...", "request_id": "..."}
```

- `text` と `audio_base64` の**どちらか一方は必須**。
- `id`・`created_at` を送っても無視される（サーバーが付ける）。
- ボディは 10MB まで。

レスポンス：

| ステータス | ボディ | 意味 |
|---|---|---|
| `201` | `{"id": 12}` | 登録できた |
| `400` | `{"error": "invalid json"}` | ボディがJSONでない |
| `400` | `{"error": "text or audio_base64 required"}` | `text` も `audio_base64` もない |
| `401` | `{"error": "unauthorized"}` | トークンが違う、またはない |
| `413` | `{"error": "too large"}` | ボディが10MBを超えた |

## 4. `GET /replies` — 返事を取得する

リクエスト：

```
GET /replies?after=<id>
Authorization: Bearer <トークン>
```

- `after`：この番号より大きい返事だけを返す。省略すると全件。
- iPhone アプリは3秒ごとに、最後に受け取った番号を `after` に付けて呼ぶ。

レスポンス：

| ステータス | ボディ | 意味 |
|---|---|---|
| `200` | `{"replies": [ <返事>, ... ]}` | 番号の小さい順。新しい返事がなければ空の配列 |
| `400` | `{"error": "bad after"}` | `after` が整数でない |
| `401` | `{"error": "unauthorized"}` | トークンが違う、またはない |

## 4-2. `GET /replies/{id}` — 返事を1件取得する

返事のデータ（2章）をそのまま返す。ないときは `404`。ストリーム再生のあと、最終の文字と音声を取りに来るのに使う。

## 4-3. `GET /replies/{id}/stream` — 返事の音声をストリームで受け取る

```
GET /replies/12/stream
Authorization: Bearer <トークン>
```

- `200`、`Content-Type: audio/L16; rate=24000; channels=1`。本体は 16bit リトルエンディアン・モノラルの PCM。
- 最初から送り、音声AIが話し終わるまで、できた分をすぐ流す。終わったら接続を閉じる（長さは事前に分からない）。
- 話し終わったあとも、直近20件のストリームはメモリに残るので取り直せる。サーバーを再起動すると消える（`404`）→ `GET /replies/{id}` の `audio_base64` を使う。

## 5. `POST /webhook` — iPhone の Webhook を受け取り、音声AIで返事する／文字起こしを転送する

iPhone アプリの Index Webhook（`experimental/.../indexwebhook/INDEX_WEBHOOK_API.md`、バージョン1）をそのまま受け取る。

リクエスト（アプリが送るもの）：

```
POST /webhook
Authorization: Bearer <トークン>          … アプリの Webhook 設定の Headers に追加する
Content-Type: multipart/form-data; boundary=...
X-Index-Delivery: <録音ごとのID>
X-Index-Trigger: single-click-hold | double-click-hold
X-Index-Test: true                       … テスト送信のときだけ
```

使うフィールドは `audio`（録音、M4A）、`transcription`（文字起こし）、`recordedAt`。

振り分け：

| 届いたもの | 動き |
|---|---|
| `audio` あり（Recording only / Both）で、音声AIが有効（`GEMINI_API_KEY` または `GOOGLE_CLOUD_API_KEY` が設定済み） | **音声AI（Gemini Live）が会話履歴を踏まえて返事を作り**、`/replies` に登録する（文字＋WAV音声）。転送はしない |
| `transcription` だけ（Transcription only）、または音声AIが無効 | 今までどおり `RELAY_URL` へ転送する |

レスポンス（すべて受け取った時点で返す。転送の結果は待たない）：

| ステータス | ボディ | 意味 |
|---|---|---|
| `200` | `{"status": "voice queued"}` | 音声AIの順番待ちに入れた |
| `200` | `{"status": "queued"}` | 転送の順番待ちに入れた |
| `200` | `{"status": "duplicate"}` | 同じ `X-Index-Delivery` をすでに受け取っている（アプリの再送）。転送しない |
| `200` | `{"status": "ignored test event"}` | テスト送信。転送しない |
| `200` | `{"status": "no transcription"}` | 文字起こしがなく、音声AIも使えない（音声AIが無効で録音だけの設定、ボタン操作のJSON）。転送しない |
| `200` | `{"status": "relay disabled"}` | `RELAY_URL` が未設定。転送しない |
| `401` | `{"error": "unauthorized"}` | トークンが違う、またはない |
| `413` | `{"error": "too large"}` | ボディが10MBを超えた |

転送（返事サーバー → `RELAY_URL`）：

```
POST <RELAY_URL>
Content-Type: application/json
Authorization: Bearer <RELAY_TOKEN>      … RELAY_TOKEN が空なら付けない

{"text": "牛乳を買うリマインダー", "request_id": "<X-Index-Delivery>", "recorded_at": 1793786333807, "trigger": "single-click-hold"}
```

| 項目 | 決まり |
|---|---|
| 順番 | 受け取った順に1件ずつ |
| 成功 | 転送先が 2xx を返したとき |
| やり直し | 接続エラー・タイムアウト（10秒）・408・429・5xx のとき、5秒・15秒・30秒あけて最大3回 |
| あきらめる | 3回やり直してもだめなとき、またはそれ以外の 4xx のとき。ログに `dropped` と出して捨てる |
| 保存 | しない（メモリだけ）。サーバーを止めると、順番待ちの分は消える |
| 重複 | 直近1000件の `X-Index-Delivery` を覚えて、同じものは転送しない |

## 6. 保存

- 返事は `replies.jsonl`（1行に1件のJSON）に保存し、**直近200件**だけ残す。
- 書き込みは一時ファイル経由で置き換えるので、途中で止まってもファイルは壊れない。
- サーバーを再起動しても、番号は保存ファイルの続きから振られる。
- **保存ファイルを消すと番号が1からになり、iPhone に新しい返事が届かなくなる**。直し方は [README.md](README.md) の「困ったとき」を参照。

## 7. iPhone アプリ側の決まり（参考）

| 項目 | 決まり |
|---|---|
| 取得の間隔 | 3秒 |
| 再生の順番 | `id` の小さい順に1件ずつ。重ならない |
| 自動再生 | 音声付きで未再生の返事はすべて、古い順に再生する。アプリが iOS に止められていた場合は、起きたとき（起動・再開時）に溜まった分をまとめて再生する |
| 音声なしの返事 | 文字だけログに残る |
| 番号のリセット | アプリの Server URL を変えて保存すると、アプリ側の「最後に受け取った番号」が消える |
