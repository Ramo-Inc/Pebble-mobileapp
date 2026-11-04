# 返事サーバー API 仕様書

- 対象：`tools/reply-server/reply_server.py`
- 利用者：iPhone アプリ（取得側）、エージェント／TTS（登録側。詳しくは [SENDER_SPEC.md](SENDER_SPEC.md)）

## 1. 共通

| 項目 | 内容 |
|---|---|
| ベースURL | `http://<Macのアドレス>:<ポート>`（ポートの初期値は `8765`） |
| 形式 | JSON（UTF-8）。レスポンスは `Content-Type: application/json; charset=utf-8` |
| 認証 | `Authorization: Bearer <トークン>`。サーバーを `--token` 付きで起動したときだけ必須。トークンなしで起動した場合は認証なし |
| 通信 | HTTP（暗号化なし）。信頼できるネットワーク（同じLAN・Tailscale）の中で使う |
| パス | `/replies` のみ。それ以外は `404` |

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

## 5. 保存

- 返事は `replies.jsonl`（1行に1件のJSON）に保存し、**直近200件**だけ残す。
- 書き込みは一時ファイル経由で置き換えるので、途中で止まってもファイルは壊れない。
- サーバーを再起動しても、番号は保存ファイルの続きから振られる。
- **保存ファイルを消すと番号が1からになり、iPhone に新しい返事が届かなくなる**。直し方は [README.md](README.md) の「困ったとき」を参照。

## 6. iPhone アプリ側の決まり（参考）

| 項目 | 決まり |
|---|---|
| 取得の間隔 | 3秒 |
| 再生の順番 | `id` の小さい順に1件ずつ。重ならない |
| 自動再生の期限 | `created_at` から15分以内に受け取ったものだけ。それより古いものはログに残るだけ |
| 音声なしの返事 | 文字だけログに残る |
| 番号のリセット | アプリの Server URL を変えて保存すると、アプリ側の「最後に受け取った番号」が消える |
