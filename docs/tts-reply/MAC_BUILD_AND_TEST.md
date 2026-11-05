# 作業指示書：Mac miniでビルドしてiPhone実機でテストする（Reply audio MVP）

> **注記（2026-11-05 夜）**：この文書は作成日時点の設計記録です。今の状態は `docs/tts-reply/HANDOFF.md` を見てください。返事の取得間隔は今は1秒です。

- 対象リポジトリ：https://github.com/Ramo-Inc/Pebble-mobileapp
- 対象ブランチ：`master`
- 設計書：`docs/tts-reply/DESIGN.md`
- この手順は、人がそのまま実行しても、Mac mini上のAIエージェント（Claude Code等）に渡しても使える。

## 0. ゴール

1. Mac miniでアプリをビルドし、iPhone実機にインストールする。
2. Mac miniで返事サーバーを起動し、iPhoneがその返事を**操作なしで自動再生**することを確認する。
3. 結果を「8. 報告フォーマット」で報告する。

**守ること：**
- リング録音・文字起こし・Webhookのコードは変更しない。
- ビルドを通すための**ローカル設定**（署名、entitlements、Firebase設定ファイル、`local.properties`）はコミットしない。
- コード修正が必要になった場合は、修正内容とエラーログを報告する。直してよいのは `replyaudio` パッケージと `tools/` 配下のみ。

## 1. 事前に必要なもの

| もの | 確認方法 | 入れ方 |
|---|---|---|
| Xcode（最新） | `xcodebuild -version` | App Store。入れたら `sudo xcode-select -s /Applications/Xcode.app` |
| iOSプラットフォーム | Xcodeで実機ビルドできるか | `xcodebuild -downloadPlatform iOS`（約8GB） |
| JDK 17 | `/usr/libexec/java_home -v 17` | `brew install openjdk@17` → `sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk` |
| CocoaPods | `pod --version` | `brew install cocoapods` |
| Android SDK（iOSビルドでも必須） | `ls ~/Library/Android/sdk` | Android Studioを入れてセットアップウィザードを完了 |
| Apple ID（署名用） | Xcode → Settings → Accounts | 無料のApple IDで可（7日ごとに再インストールが必要） |
| iPhone | Mac miniとUSB接続、またはXcodeでワイヤレス接続済み | iPhoneの「設定 → プライバシーとセキュリティ → デベロッパモード」をON |

**注意：** シミュレーターは既知の問題でリンクできない（README記載）。**必ず実機**でビルドする。

## 2. コードを取得する

初回：

```bash
git clone https://github.com/Ramo-Inc/Pebble-mobileapp.git
cd Pebble-mobileapp
```

2回目以降：

```bash
git checkout master && git pull
```

## 3. 準備スクリプトを実行する

リポジトリのルートで実行する。前提ツールの確認、ローカル設定、単体テスト、iOS向けKotlinのコンパイル、CocoaPodsの準備までを自動で行う。

```bash
tools/mac-build/setup.sh --trim-entitlements
```

スクリプトが行うこと：

1. Xcode / JDK 17 / CocoaPods / Android SDK の有無を確認（NGがあれば止まる → 1章の表で入れて再実行）
2. `local.properties` に `sdk.dir` を書く
3. ダミーの `GoogleService-Info.plist` を置く（Firebaseのアカウント不要）
4. バージョン用のgitタグがなければ `1.0.0` を作る
5. `--trim-entitlements`：個人署名で使えない権限（プッシュ通知、Sign in with Apple、Associated Domains）を外す。このファイルはgitに変更として出ないよう設定される
6. Reply audioの単体テスト：`./gradlew :experimental:testAndroidHostTest --tests 'coredevices.ring.external.replyaudio.*'`
7. iOS向けKotlinコンパイル：`./gradlew :composeApp:compileKotlinIosArm64`
8. `./gradlew podInstall`

初回はGradleの依存ダウンロードで30分以上かかることがある。止まっているように見えても中断しない。

6・7で失敗した場合は、**エラーの全文**を保存して報告する。

## 4. 署名してiPhoneにインストールする

1. バンドルIDを自分用にする：`iosApp/Configuration/Config.xcconfig` の `BUNDLE_ID` を変更する（`TEAM_ID` は空のまま）。

   ```
   BUNDLE_ID=com.<自分の名前>.coreapp
   ```

2. Xcodeで **`iosApp/iosApp.xcworkspace`** を開く（`.xcodeproj` ではない）。
3. 左のプロジェクト → ターゲット `iosApp` → **Signing & Capabilities**：
   - **Team**：自分のApple IDのチーム（Personal Team）を選ぶ
   - 「Automatically manage signing」をON
   - 赤いエラーで使えないCapabilityが出た場合（例：HealthKit）は、その項目を `-` で削除する
4. 上部の実行先で、接続したiPhoneを選ぶ → **▶（Run）**。
   - 初回ビルドは30分程度かかる。
   - iPhoneで「信頼されていないデベロッパ」と出たら、「設定 → 一般 → VPNとデバイス管理」で自分のApple IDを信頼する。
5. アプリが起動したら、これまでどおりリングとペアリングし、録音・文字起こしができることを確認する（既存機能が壊れていないことの確認）。

## 5. 返事サーバーを起動する（Mac mini）

別のターミナルで、リポジトリのルートから実行する。`CHANGE_ME` は好きな文字列に変える。

```bash
python3 tools/reply-server/reply_server.py serve --token CHANGE_ME --port 8765
```

iPhoneからアクセスするMac miniのIPアドレスを調べる。

- Tailscaleを使っている場合：

  ```bash
  tailscale ip -4
  ```

- 同じWi-Fi・LANの場合：

  ```bash
  ipconfig getifaddr en0
  ```

macOSのファイアウォールで「受信接続を許可しますか」と出たら、Pythonを**許可**する。

## 6. iPhoneアプリの設定

1. アプリで **Index設定 → Reply audio** を開く。
2. 次のように入力する：
   - **Server URL**：`http://<5章のIP>:8765`
   - **Token**：5章の `--token` と同じ値
   - **Auto-play replies**：ON
3. **Save** を押す。
4. 画面に `Error: ...` が出ていないことを確認する（出ていたら、URL・トークン・ファイアウォールを確認）。

## 7. テスト

テスト用の返事は、Mac miniで次のように送る（`say` でMacの音声を作って送る）。

```bash
python3 tools/reply-server/reply_server.py push --token CHANGE_ME --text "テストです。聞こえますか。" --say
```

| No | 手順 | 合格条件 |
|---|---|---|
| T1 | アプリを表示したまま、上のコマンドで返事を送る | 数秒以内に自動で音声が流れる。Reply audioのログに文字が出る |
| T2 | 別のアプリ（Safari等）を表示した状態で返事を送る | 自動で流れる |
| T3 | iPhoneを**ロック**し、直後に返事を送る | ロックを解除せずに流れる |
| T4 | ロックして**5分**放置してから返事を送る | 流れる |
| T5 | ロックして**30分**放置してから返事を送る | 流れる |
| T6 | 返事を2件続けて送る（コマンドを2回実行） | 1件ずつ順番に流れる。重ならない |
| T7 | Reply audioのログを開く | 全件の文字が並ぶ。Playで聞き直せる |
| T8 | リングで話しかけてWebhookを送り、そのあとロック中に返事を送る | 既存の録音・文字起こしが今までどおり動き、返事も流れる |

T3〜T5でロック後すぐに返事を送るときは、別のターミナル、または `sleep 300 && python3 ...` のように待ち時間を付けて実行する。

### うまく鳴らないときのログの見方

1. iPhoneをMac miniに接続したまま、Xcodeで実行中のアプリのコンソールを見るか、**Console.app**でiPhoneを選ぶ。
2. 検索欄に `ReplyAudio` と入れる。
3. 次の行の有無と時刻を確認する：
   - `Reply N received` … 返事を受け取った
   - `Reply N playback start` … 再生を始めた
   - `Reply N playback end` … 再生が終わった
   - `Reply poll failed: ...` … Macにつながっていない（原因が書かれている）
4. 「どの行まで出て、どこで止まったか」を報告に書く。

## 8. 報告フォーマット

```text
■ 環境
- Mac mini: macOS __ / Xcode __
- iPhone: 機種 __ / iOS __
- コミット: (git log -1 --oneline の結果)

■ ビルド
- setup.sh: 成功 / 失敗（失敗した段階とエラー全文）
- Xcodeビルド・インストール: 成功 / 失敗（エラー全文）
- 既存機能（録音・文字起こし）: 動く / 動かない

■ テスト結果
T1: OK / NG（NGならログの該当行）
T2:
T3:
T4:
T5:
T6:
T7:
T8:

■ 気づいたこと
```

## 9. 修正が必要になったら

Reply audioのコードは `master` に取り込み済み。修正は `master` から作業ブランチを切って行い、確認できたら `master` にマージする。

## 10. よくあるエラー

| 症状 | 対処 |
|---|---|
| `SDK location not found` | `local.properties` に `sdk.dir=/Users/<you>/Library/Android/sdk` |
| `iOS X.Y is not installed` | `xcodebuild -downloadPlatform iOS` |
| `No such module 'FirebaseCore'` 等 | `.xcworkspace` を開いているか確認 → `./gradlew podInstall` → Xcodeで Product → Clean Build Folder |
| `Provisioning profile ... doesn't include the ... entitlement` | `setup.sh --trim-entitlements` を実行済みか確認。それでも出るCapabilityはSigning & Capabilitiesから削除 |
| `git describe` / バージョンのエラー | `git tag 1.0.0` |
| 7日後にアプリが起動しない | 無料Apple IDの署名期限。Xcodeから再度Run |
| `Error: Reply server returned HTTP 401` | アプリとサーバーのトークンが違う |
| `Error:` に接続エラー | URLのIP・ポート、Mac側のファイアウォール、サーバーが起動しているかを確認 |
