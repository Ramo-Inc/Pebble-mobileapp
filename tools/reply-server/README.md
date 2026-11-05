# 返事サーバー（Reply server）

Pebble アプリ（Android・iPhone）の **Reply audio** 機能で使う、Mac 側の小さなサーバー。全体の中での位置づけは [`docs/tts-reply/ARCHITECTURE.md`](../../docs/tts-reply/ARCHITECTURE.md)。

- **返事**：エージェントや TTS が返事（文字＋音声）を登録し、アプリが1秒ごとに取りに来て自動再生する。
- **Webhook の受け口**：アプリの Webhook を受け取り、録音付きなら Gemini Live で返事を作る（6-2）。文字起こしだけなら、文字起こしを JSON で指定のサーバー（`RELAY_URL`）へ転送する。

| 資料 | 内容 |
|---|---|
| この README | インストール・起動・アプリの設定・困ったとき |
| [API.md](API.md) | HTTP API 仕様（`GET` / `POST /replies`、`POST /webhook`） |
| [SENDER_SPEC.md](SENDER_SPEC.md) | エージェント／TTS 側の仕様（文字起こしの受け取り・返事の送り方） |
| `docs/tts-reply/ARCHITECTURE.md` | 機能全体の地図（アプリ側のどこに何があるか） |

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

## 3. 設定（.env）

見本をコピーして、値を書き換える。`.env` は git に入らない。

```bash
cp tools/reply-server/.env.example tools/reply-server/.env
```

```
REPLY_TOKEN=<アプリと共有するトークン>
RELAY_URL=http://127.0.0.1:9000/stt     # 文字起こしの転送先。空なら転送しない
RELAY_TOKEN=<転送先が発行したトークン>    # Authorization: Bearer で付けて送る
```

起動時に自動で読み込む。シェルの環境変数や起動オプションで同じ値を渡したときは、そちらが優先。

## 4. 起動

```bash
python3 tools/reply-server/reply_server.py serve
```

| オプション | 初期値 | 内容 |
|---|---|---|
| `--token` | `.env` の `REPLY_TOKEN` | 認証用の文字列。推測されにくいものにする。空だと認証なし |
| `--relay-url` | `.env` の `RELAY_URL` | 文字起こしの転送先。空なら転送しない |
| `--relay-token` | `.env` の `RELAY_TOKEN` | 転送先に送るトークン |
| `--port` | `8765` | 待ち受けるポート |
| `--host` | `0.0.0.0` | 待ち受けるアドレス。`127.0.0.1` にすると Mac の中からしか使えない |
| `--store` | `tools/reply-server/replies.jsonl` | 返事の保存ファイル（gitには入らない） |

起動すると次のように出る。

```
Reply server on http://0.0.0.0:8765 (store: ...)
Webhook relay: http://127.0.0.1:9000/stt
```

- 返事が登録されるたびに `[reply 12] 結果です…`（1秒ごとの取得は表示しない）
- Webhook を受け取るたびに `[webhook <id>] <文字起こし>`、転送できたら `[relay <id>] delivered`

止めるときは `Ctrl+C`。

### スマホから使うアドレスを調べる

- Tailscale：`tailscale ip -4`
- 同じWi-Fi・LAN：`ipconfig getifaddr en0`

macOS のファイアウォールで「受信接続を許可しますか」と出たら、Python を**許可**する。

### 動いているか確認する

```bash
curl -s -H "Authorization: Bearer <トークン>" "http://<アドレス>:8765/replies?after=0"
# => {"replies": [...]}
```

## 5. アプリの設定（Reply audio）

Index タブ → 右上の歯車 → **Reply audio**：

- **Server URL**：`http://<Macのアドレス>:8765`
- **Token**：起動時の `--token` と同じ値
- **Auto-play replies**：ON → **Save**

iPhone では、初回に「ローカルネットワーク」の許可を求められるので**許可**する。断った場合は、iPhone の「設定 → プライバシーとセキュリティ → ローカルネットワーク」で Pebble を ON にする。

iPhone でロック中や他のアプリを表示中も鳴らすには、リング（Index 01）をペアリングしておく（リングの通信でアプリが起きる）。Android は常駐サービスで動き続ける。

## 6. アプリの設定（Webhook → 文字起こしの転送＝秘書）

Index 設定 → **Webhook** → ジェスチャー（Hold & talk など）を選ぶ：

- **URL**：`http://<Macのアドレス>:8765/webhook`
- **Headers**：名前 `Authorization`、値 `Bearer <REPLY_TOKEN の値>`
- **送る内容**：Transcription only（Recording only・Both にすると、`GEMINI_API_KEY` があれば 6-2 の Gemini が答える）
- この押し方のボタン設定の行き先は、Gemini Live 以外にする（Gemini Live の押し方には Webhook を送らない）
- **Save**

録音すると、文字起こしが `RELAY_URL` に転送される。転送の形式は [SENDER_SPEC.md](SENDER_SPEC.md)。

- アプリにはすぐ `200` を返し、転送は裏で行う。失敗したら 5秒・15秒・30秒あけて3回までやり直し、だめなら捨ててログに残す（待っている分はサーバーを止めると消える）。
- 同じ録音（`X-Index-Delivery` が同じ）は1回しか転送しない。
- 「Send test event」のテスト送信とボタン操作（クリック）は転送しない。

## 6-2. Mac 経由の Gemini で直接返事する（Speech to Speech）

> アプリのボタン設定の行き先「Gemini Live」は、これとは別の**アプリ内 Gemini**（Mac を通らない）。行き先が Gemini Live の押し方は Webhook を送らないので、この 6-2 は使われない。全体は `docs/tts-reply/HANDOFF.md` §1。

Webhook で**録音そのもの**を送ると、Gemini Live が音声で直接返事をする。秘書セッションを通さないので速い。

1. `.env` に追加して、サーバーを再起動する。

   ```
   GEMINI_API_KEY=<Google の API キー>   # 空なら環境変数 GOOGLE_CLOUD_API_KEY を使う
   GEMINI_LIVE_MODEL=        # 任意。空なら gemini-3.8-live
   GEMINI_VOICE=            # 任意。例 Kore。空ならモデルの標準の声
   GEMINI_SYSTEM_PROMPT=    # 任意。空なら「日本語で短く話す音声アシスタント」の指示
   GEMINI_HISTORY_TURNS=20  # 返事に使う過去のやり取りの数
   ```

   起動時に `Voice replies: <モデルID>` と出れば有効。

2. アプリ：Index 設定 → **Webhook** → ジェスチャーを選び、**送る内容**を切り替える。

   | 送る内容 | 返事の作り方 |
   |---|---|
   | **Recording only**（おすすめ） | 音声AI。アプリは文字起こしを待たずにすぐ送る |
   | Both | 音声AI（文字起こしを待ってから送るぶん遅い） |
   | Transcription only | 今までの仕組み（`RELAY_URL` へ転送） |

- **ストリーム再生**：Gemini が最初の音声を返した時点（実測で録音を受け取ってから約2.5秒）で返事が登録され、アプリは話し終わるのを待たずに再生を始める。話し終わると、同じ返事に文字と音声ファイルが入り、返事ログから聞き直せる。
- 会話履歴は `tools/reply-server/conversation.jsonl`（gitには入らない）。直近 `GEMINI_HISTORY_TURNS` 件のやり取りを毎回 Gemini に渡すので、続きの会話ができる。
- 履歴を消して新しい会話にする：`python3 tools/reply-server/reply_server.py clear-history`
- 録音の変換に macOS の `afconvert` を使う（なければ `ffmpeg`）。
- 環境変数 `HTTPS_PROXY` があれば、Gemini への接続はそのプロキシを通る。
- Gemini への接続は録音1件ごとに開き、返事が終わったら閉じる。終わりの合図が来なくても2分で打ち切って閉じる（やり直しはしない）。
- 失敗したときは、理由を書いた文字だけの返事がアプリの返事ログに届く。ログには `[voice <id>] failed: ...`。

## 7. 返事を送る

```bash
# macOS の say で音声を作って送る（動作確認用）
python3 tools/reply-server/reply_server.py push --token <トークン> --text "テストです" --say

# TTS で作った音声ファイルを送る（M4A推奨・MP3・WAVも可、5MBまで）
python3 tools/reply-server/reply_server.py push --token <トークン> --text "結果です" --audio reply.m4a

# 録音と紐付ける（Webhook の X-Index-Delivery の値）
python3 tools/reply-server/reply_server.py push --token <トークン> --text "..." --audio reply.m4a --request-id "<X-Index-Delivery>"
```

`--server`（初期値 `http://127.0.0.1:8765`）で送り先を変えられる。プログラムから送る方法は [SENDER_SPEC.md](SENDER_SPEC.md)。

## 8. 困ったとき

| 症状 | 原因と対処 |
|---|---|
| アプリに `Error: Reply server returned HTTP 401` | アプリとサーバーのトークンが違う |
| アプリに接続エラー / `Local network prohibited` | URLのアドレス・ポート、サーバーが動いているか、iPhone ならローカルネットワーク許可、Mac のファイアウォールを確認 |
| 返事を送っても**何も届かなくなった** | `replies.jsonl` を消した（番号が1に戻った）可能性。アプリの Server URL を一度別の値にして Save → 元に戻して Save すると、アプリ側の番号がリセットされる |
| 届いたが鳴らない | アプリが iOS に止められている（開くかリングの通信で起きたときに再生される）、音声なしで送った、または音声ファイルが壊れている。アプリのログで `ReplyAudio` を検索し、`received` / `playback start` / `playback end` / `playback failed` を確認 |
| ロック中・他アプリ表示中に鳴らない | リングがペアリングされているか確認 |
| ポートが使用中で起動しない | `--port` で別の番号にし、アプリの Server URL も合わせる |
| 音声AIの返事が来ない | 起動時の `Voice replies:` が `off` なら `.env` の `GEMINI_API_KEY`（または環境変数 `GOOGLE_CLOUD_API_KEY`）を確認。アプリの送る内容が Recording only か確認。サーバーのログの `[voice ...] failed:` に理由が出る（モデルIDの間違いは接続直後に切断される） |
| 文字起こしが転送されない | 起動時の `Webhook relay:` が `off` なら `.env` の `RELAY_URL` を確認。アプリの Webhook の Recent runs が失敗なら URL・`Authorization` ヘッダーを確認。サーバーのログに `dropped` があれば転送先の状態を確認 |
