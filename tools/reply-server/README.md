# 返事サーバー（Reply server）

Pebble アプリの **Reply audio** 機能で使う、Mac 側の小さなサーバー。エージェントや TTS が返事（文字＋音声）を登録し、iPhone アプリが3秒ごとに取りに来て自動再生する。

| 資料 | 内容 |
|---|---|
| この README | インストール・起動・iPhone の設定・困ったとき |
| [API.md](API.md) | HTTP API 仕様（`GET` / `POST /replies`） |
| [SENDER_SPEC.md](SENDER_SPEC.md) | 返事を送り出す側（エージェント／TTS）の仕様 |
| `docs/tts-reply/DESIGN.md` | 機能全体の設計 |

## 1. 必要なもの

- macOS（`--say` で音声を作る機能を使う場合。サーバー自体は Python が動けばよい）
- Python 3.9 以上（標準ライブラリだけで動く。`pip install` は不要）

確認：

```bash
python3 --version
```

## 2. インストール

このリポジトリを取得すれば、そのまま使える。

```bash
git clone https://github.com/Ramo-Inc/Pebble-mobileapp.git
cd Pebble-mobileapp
```

使うファイルは `tools/reply-server/reply_server.py` の1つだけ。別の場所で使う場合は、このファイルだけコピーすればよい。

## 3. 起動

```bash
python3 tools/reply-server/reply_server.py serve --token <トークン> --port 8765
```

| オプション | 初期値 | 内容 |
|---|---|---|
| `--token` | 環境変数 `REPLY_TOKEN` | 認証用の文字列。推測されにくいものにする。空だと認証なし |
| `--port` | `8765` | 待ち受けるポート |
| `--host` | `0.0.0.0` | 待ち受けるアドレス。`127.0.0.1` にすると Mac の中からしか使えない |
| `--store` | `tools/reply-server/replies.jsonl` | 返事の保存ファイル（gitには入らない） |

起動すると `Reply server on http://0.0.0.0:8765 (store: ...)` と出る。返事が登録されるたびに `[reply 12] 結果です…` と出る（3秒ごとの取得は表示しない）。

止めるときは `Ctrl+C`。

### iPhone から使うアドレスを調べる

- Tailscale：`tailscale ip -4`
- 同じWi-Fi・LAN：`ipconfig getifaddr en0`

macOS のファイアウォールで「受信接続を許可しますか」と出たら、Python を**許可**する。

### 動いているか確認する

```bash
curl -s -H "Authorization: Bearer <トークン>" "http://<アドレス>:8765/replies?after=0"
# => {"replies": [...]}
```

## 4. iPhone アプリの設定

Index タブ → 右上の歯車 → **Reply audio**：

- **Server URL**：`http://<Macのアドレス>:8765`
- **Token**：起動時の `--token` と同じ値
- **Auto-play replies**：ON → **Save**

初回は iOS の「ローカルネットワーク」の許可を求められるので**許可**する。断った場合は、iPhone の「設定 → プライバシーとセキュリティ → ローカルネットワーク」で Pebble を ON にする。

ロック中や他のアプリを表示中も鳴らすには、リング（Index 01）をペアリングしておく。

## 5. 返事を送る

```bash
# macOS の say で音声を作って送る（動作確認用）
python3 tools/reply-server/reply_server.py push --token <トークン> --text "テストです" --say

# TTS で作った音声ファイルを送る（M4A推奨・MP3・WAVも可、5MBまで）
python3 tools/reply-server/reply_server.py push --token <トークン> --text "結果です" --audio reply.m4a

# 録音と紐付ける（Webhook の X-Index-Delivery の値）
python3 tools/reply-server/reply_server.py push --token <トークン> --text "..." --audio reply.m4a --request-id "<X-Index-Delivery>"
```

`--server`（初期値 `http://127.0.0.1:8765`）で送り先を変えられる。プログラムから送る方法は [SENDER_SPEC.md](SENDER_SPEC.md)。

## 6. 困ったとき

| 症状 | 原因と対処 |
|---|---|
| アプリに `Error: Reply server returned HTTP 401` | アプリとサーバーのトークンが違う |
| アプリに接続エラー / `Local network prohibited` | URLのアドレス・ポート、サーバーが動いているか、iPhone のローカルネットワーク許可、Mac のファイアウォールを確認 |
| 返事を送っても**何も届かなくなった** | `replies.jsonl` を消した（番号が1に戻った）可能性。アプリの Server URL を一度別の値にして Save → 元に戻して Save すると、アプリ側の番号がリセットされる |
| 届いたが鳴らない | 作成から15分以上たっていた、音声なしで送った、または音声ファイルが壊れている。アプリのログで `ReplyAudio` を検索し、`received` / `playback start` / `playback end` / `playback failed` を確認 |
| ロック中・他アプリ表示中に鳴らない | リングがペアリングされているか確認 |
| ポートが使用中で起動しない | `--port` で別の番号にし、アプリの Server URL も合わせる |
