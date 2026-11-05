# ビルド・インストール・動作確認（Mac mini）

リングの音声会話（[ARCHITECTURE.md](ARCHITECTURE.md)）を、Mac mini でビルドして端末に入れ、動きを確かめる手順。

## 1. Android（今メインで使っている端末：moto g24）

前提：Android SDK（`~/Library/Android/sdk`）、JDK 17、ダミーの `androidApp/src/google-services.json`（`.git/info/exclude` 済み）。`adb` は `~/Library/Android/sdk/platform-tools/adb`（PATH に入っていない）。

```bash
cd ~/Projects/Pebble-mobileapp
./gradlew :androidApp:assembleDebug
A=~/Library/Android/sdk/platform-tools/adb
$A install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk   # 消さずに上書き（設定とペアリングが残る）
$A shell monkey -p coredevices.coreapp -c android.intent.category.LAUNCHER 1
```

- 起動後に `adb logcat -d | grep -E 'FATAL EXCEPTION|InstanceCreationException'` で落ちていないかを確かめる。
- release ビルドの手順は `CLAUDE.md` の「Android local release install」。
- 端末の画面がロック中だと adb で操作できない。ユーザーに解除してもらう。横向きだとボタン設定のシートが画面からはみ出すので、操作中だけ `settings put system user_rotation 0`・`accelerometer_rotation 0` で縦にして、終わったら戻す。

### 記録の見方

```bash
$A logcat -c; $A logcat -v time > /tmp/ring.log   # 別のターミナルで流しっぱなし
grep -E 'RingAudioTap|GeminiLive|Transfer (started|complete) for|Debounced gesture' /tmp/ring.log
```

| タグ | 出るもの |
|---|---|
| `RingSync` | `Transfer started/complete`（ボタンを離した時刻・音声の長さ付き） |
| `RingAudioTap` | 区切りごとの到着（番号・サンプル数・押し方） |
| `GeminiLive` | `streamed to Gemini, utterance ended` → `First Gemini audio, playback starts` → `Gemini reply for …` |
| `IndexButtonSequenceRecorder` | 押し方の確定（`Debounced gesture`） |
| `ReplyAudioService` | Mac の返事の受信と再生（`Reply N received`、`stream start/end`） |

「Firestore にアップロードできない（Not authenticated）」は、ダミー Firebase でログインしていないためで無害。

### 端末の設定を読む（debug ビルドのみ）

```bash
$A shell run-as coredevices.coreapp cat shared_prefs/coredevices.coreapp_preferences.xml | grep ring_gesture_route
```

トークンなどの値も入っているので、全部は表示しない。API キーは Keystore で暗号化された `integration_tokens.xml` にあり、adb からは書き込めない（アプリの画面から入れる）。

## 2. iPhone

1. 初回だけ：`tools/mac-build/setup.sh --trim-entitlements`（前提ツールの確認、`local.properties`、ダミーの `GoogleService-Info.plist`、バージョンタグ、個人署名で使えない権限の除去、`podInstall`）。必要なもの：Xcode、JDK 17、CocoaPods、Android SDK、無料の Apple ID、デベロッパモードを ON にした iPhone。
2. 署名用のローカル設定（コミットしない）：`iosApp/Configuration/Config.xcconfig` の `BUNDLE_ID`（今は `com.ramo.coreapp`）、Xcode の Signing & Capabilities で Personal Team。
3. ビルド〜インストール〜起動：**普通のターミナルで** `~/Projects/Pebble-mobileapp/tools/mac-build/ios-build-install.sh`（iPhone を USB 接続・ロック解除）。エージェントのシェルからは署名できない（`errSecInternalComponent`）。
4. コンパイルだけの確認ならエージェントでもできる：`./gradlew :experimental:compileKotlinIosArm64`。

- シミュレーターはリンクできない（`README.md`）。必ず実機。
- 無料 Apple ID の署名は7日で切れる。切れたら 3 をやり直す。
- iPhone のアプリの記録は `Library/Caches/logs/latest.log`（`xcrun devicectl device copy from --domain-type appDataContainer --domain-identifier com.ramo.coreapp …`）。アプリが裏に回るまで書き出されない。

## 3. Mac の常駐サービス

| サービス | 再起動 | ログ |
|---|---|---|
| 返事サーバー | `launchctl kickstart -k gui/$(id -u)/com.ramo.reply-server` | `~/Library/Logs/reply-server.log` |
| pebble-ring | `launchctl kickstart -k gui/$(id -u)/com.ramo.pebble-ring` | `~/Library/Logs/pebble-ring.log` |
| Catchball | `launchctl kickstart -k gui/$(id -u)/com.ramo.catchball` | `~/Library/Logs/catchball.log` |

返事サーバーの起動直後に `Voice replies: gemini-3.8-live` と出ていれば Mac 経由の Gemini が有効（`off` なら `tools/reply-server/.env` の `GEMINI_API_KEY`）。launchd は `~/.zshrc` を読まないので、キーは `.env` に書く。

## 4. 単体テスト

[ARCHITECTURE.md](ARCHITECTURE.md) §4 のコマンド。直したら関係するものを全部流す。

## 5. 実機での確認

### アプリ内 Gemini（行き先 = Gemini Live）

| No | 操作 | 合格 |
|---|---|---|
| G1 | 長押しで5秒ほど話す | 離してから数秒で鳴り始める。`GeminiLive` に `streamed to Gemini` と `First Gemini audio` |
| G2 | 続けて前の話の続きを聞く | 前の内容を踏まえて答える |
| G3 | 長押しが乱れる（途中で押し直す） | それでも答える（`Debounced gesture` が `long short …` や `long long long` でも可） |
| G4 | 1回押し・2回押し（音楽操作） | Gemini に接続しない |
| G5 | Reset conversation の後に続きを聞く | 前の内容を覚えていない |
| G6 | キーを間違える | 返事ログに理由が出る。アプリは落ちない |
| G7 | iPhone で G1〜G2 | 同じ（未確認） |
| G8 | Reply audio の設定と秘書の起動を確認し、「今日のニュースを3件探して」と話す | Gemini は依頼の受付だけ話して終了。秘書が依頼を受け、既存の音声経路で進捗・結果を返す |
| G9 | Reply audio の自動再生を無効にし、仕事を依頼する | Gemini が設定不足を伝える。送信したとは言わず、秘書にも仕事を送らない |
| G10 | サーバーの認証エラーや転送無効の状態で仕事を依頼する | Gemini が受付失敗を伝える。成功したとは言わない |

G8〜G10 は Android と iPhone でそれぞれ確認する。秘書の進捗・結果は Gemini に戻さない。iPhone の一時停止中に返事が遅れる制約は従来どおり。

### Mac 経由の Gemini（Webhook = Recording only）

Mac だけでの確認：`say -v Kyoko -o q.m4a --file-format=m4af --data-format=aac "…"` で作った音声を、`audio` フィールドの multipart で `http://127.0.0.1:8765/webhook` に POST する（`Authorization: Bearer <REPLY_TOKEN>`、`X-Index-Delivery: <一意の値>`）。`GET /replies` に `stream` 付きの返事が出て、`/replies/{id}/stream` から PCM が流れ、最後に `/replies/{id}` に文字と `audio_base64` が入れば合格。試した後の会話履歴は退避して戻す（または `clear-history`）。

### 秘書（Webhook = Transcription only）

秘書を `secretary.sh` で起動してから話す。pebble-ring のログに `[ack]`（相づち）→ 秘書の返事 → `[spoken]`（読み上げた要約）が出て、スマホで順に鳴れば合格。途中で切れた発話には聞き返しが鳴り、秘書には送られない。
