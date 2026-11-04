# 送り出す側（エージェント／TTS）の仕様

- 作成日：2026-11-05
- 対象：エージェント／TTS など、iPhone に返事（文字＋音声）を届けたいプログラム
- 前提：返事サーバーが Mac で動いていること（起動方法は [README.md](README.md)、API の全体は [API.md](API.md)）

## 1. 全体の流れ

```text
送り出す側（エージェント / TTS）
   │  POST /replies  （文字 + 音声のbase64）
   ▼
返事サーバー（Mac, reply_server.py）  … 番号(id)と作成時刻を付けて保存（直近200件）
   ▲
   │  GET /replies?after=<最後のid>  … 3秒ごと
iPhone アプリ  … 新しい返事を id の小さい順に1件ずつ自動再生し、返事ログに残す
```

送り出す側がやることは **`POST /replies` を1回呼ぶことだけ**。iPhone との通信、順番、再生はサーバーとアプリが行う。

## 2. 返事の登録 `POST /replies`

### リクエスト

```
POST http://<サーバー>:8765/replies
Authorization: Bearer <トークン>
Content-Type: application/json
```

```json
{
  "text": "了解、調べます。",
  "audio_base64": "AAAAHGZ0eXBNNEEg...",
  "request_id": "d2b7c1e0-..."
}
```

| フィールド | 型 | 必須 | 内容 |
|---|---|---|---|
| `text` | 文字列 | △ | 返事の文字。アプリの返事ログに表示される |
| `audio_base64` | 文字列 / `null` | △ | 音声ファイルの中身をbase64にしたもの（改行なし）。なければ文字だけ記録され、音は鳴らない |
| `request_id` | 文字列 / `null` | 任意 | 元の録音との紐付け用。Webhook の `X-Index-Delivery` ヘッダーの値を入れる。MVPでは表示に使わない |

△：`text` と `audio_base64` の**どちらか一方は必須**。両方なければ 400。

`id` と `created_at` は**サーバーが付ける**。送り出す側からは指定できない（指定しても無視される）。

### レスポンス

| ステータス | ボディ | 意味 |
|---|---|---|
| `201` | `{"id": 12}` | 登録できた。`id` はこの返事の番号 |
| `400` | `{"error": "invalid json"}` | ボディがJSONでない |
| `400` | `{"error": "text or audio_base64 required"}` | `text` も `audio_base64` もない |
| `401` | `{"error": "unauthorized"}` | トークンが違う、または `Authorization` がない |
| `404` | `{"error": "not found"}` | パスが `/replies` でない |
| `413` | `{"error": "too large"}` | ボディが 10MB を超えた |

再送の扱い：同じ内容を2回 POST すると、**別の返事として2件**登録され、2回鳴る。サーバー側に重複排除はない。タイムアウト等で再送するときは注意する。

## 3. 音声の決まり

| 項目 | 決まり |
|---|---|
| 形式 | **M4A（AAC）推奨**。MP3・WAV も再生できる |
| サイズ | **5MB以下**（`push` コマンドの上限。サーバーはボディ全体で10MBまで受け付ける） |
| 長さ | 短いほどよい（目安：1件30秒以内）。長い話は分けて送る |
| 中身 | ファイルの中身そのもの（バイト列）をbase64にする。`data:` などの接頭辞は付けない |

サーバーは音声の中身を検査しない。壊れた音声は、iPhone 側で再生に失敗し、返事ログに文字だけが残る（未再生のまま）。

## 4. iPhone 側の動き（送り出す側が知っておくこと）

| 項目 | 動き |
|---|---|
| 届くまでの時間 | 最大3秒程度（3秒ごとに取りに来る） |
| 順番 | `id` の小さい順に1件ずつ再生。前の再生が終わってから次を鳴らす（重ならない） |
| 自動再生の期限 | サーバーが付けた `created_at` から**15分以内**に iPhone が受け取ったものだけ自動再生する。それより古いもの（iPhone が長くオフラインだった等）はログに残るだけで鳴らない |
| 鳴り方 | マナーモード中でも鳴る。音楽など他の音が流れていれば、その音量を下げて重ねて鳴らす |
| ロック中・他アプリ表示中 | リングとペアリング済みでアプリが動いていれば鳴る（実機確認済み：他アプリ表示中、ロック直後、ロック5分後） |
| 音声なしの返事 | 文字だけログに残る。音は鳴らない |
| 保存件数 | サーバー・アプリとも直近200件 |

## 5. 注意点（壊さないために）

- **`replies.jsonl` を消さない。** サーバーは保存ファイルの最後の `id` の続きから番号を付ける。ファイルを消すと番号が1から振り直しになる。iPhone は「最後に受け取った番号より大きいもの」しか取りに行かないため、**新しい返事が届かなくなる**。直し方は [README.md](README.md) の「困ったとき」。
- **サーバーは1台だけ使う。** 複数のサーバーに分けて送ると番号が混ざる。
- **時計を合わせておく。** 自動再生の15分の判定は、サーバー（Mac）の時刻と iPhone の時刻の差で行う。Mac の時計が大きくずれていると鳴らない。
- **通信は暗号化されていない（MVP）。** 同じWi-Fi・Tailscale など、信頼できるネットワークの中だけで使う。トークンは推測されにくい文字列にする。

## 6. 呼び出し例

### curl

```bash
AUDIO=$(base64 -i reply.m4a | tr -d '\n')
curl -sS -X POST http://127.0.0.1:8765/replies \
  -H "Authorization: Bearer $REPLY_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"結果です\",\"audio_base64\":\"$AUDIO\",\"request_id\":\"$DELIVERY_ID\"}"
# => {"id": 12}
```

### Python（標準ライブラリのみ）

```python
import base64, json, urllib.request

def send_reply(text, audio_path=None, request_id=None,
               server="http://127.0.0.1:8765", token="CHANGE_ME"):
    audio_b64 = None
    if audio_path:
        with open(audio_path, "rb") as f:
            audio_b64 = base64.b64encode(f.read()).decode("ascii")
    body = json.dumps({"text": text, "audio_base64": audio_b64, "request_id": request_id}).encode()
    req = urllib.request.Request(
        server.rstrip("/") + "/replies", data=body, method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.load(resp)["id"]
```

### 付属のコマンド

```bash
# 音声ファイルを送る
python3 tools/reply-server/reply_server.py push --token <トークン> --text "結果です" --audio reply.m4a

# macOS の say で音声を作って送る（動作確認用）
python3 tools/reply-server/reply_server.py push --token <トークン> --text "テストです" --say

# 録音と紐付ける
python3 tools/reply-server/reply_server.py push --token <トークン> --text "..." --audio reply.m4a --request-id "<X-Index-Delivery>"
```

`--server` を省略すると `http://127.0.0.1:8765` に送る。トークンは `--token` か環境変数 `REPLY_TOKEN` で渡す。
