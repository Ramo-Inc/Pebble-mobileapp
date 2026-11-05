# プッシュ通知を使わずに、Mac から裏にいる iPhone アプリを起こす方法（調査）

調査日：2026-11-05。対象：無料 Apple ID（Personal Team、7日間のプロビジョニング）で署名した個人ビルド。
目的：返事の音声ができたら、数秒以内に iPhone アプリを起こして自動再生したい。APNs（プッシュ通知）は使えない。

表記ルール：
- 文末の `[S番号]` は最後の「出典」の番号です。出典は Apple の公式ページ（developer.apple.com / support.apple.com）と、Apple 社員（DTS）のフォーラム回答です。
- **【二次情報】** と書いたものは、Apple 社員の回答と確認できなかったフォーラム投稿などです。
- **【推測】** と書いたものは、資料から私が考えたことで、資料には書かれていません。
- **【未検証】** は、実機で測る必要があることです。

---

## 結論

無料 Apple ID では、APNs・PushKit・Live Activity・Local Push Connectivity（Network Extension）は**すべて使えません**。Apple の表で、Push notifications・Network extensions・Push to Talk・Time Sensitive Notifications は無料の「Apple Developer」の欄に印がありません [S1]。無料でも使えるのは **Background modes** で [S1]、その中の Bluetooth と audio が今回の手段になります。

**おすすめ（第1案）：Mac を BLE のペリフェラル（周辺機器役）にして、iPhone アプリからいつも接続しておき、返事ができたら Mac から notify（特性の値の通知）を送ってアプリを起こす。** 周辺機器が値を送ると、iOS はアプリを起こします [S2]。アプリが強制終了されていなければ（メモリ不足で落とされた場合も含めて）、iOS はアプリを裏で起動し直します [S3]。起きたあと、`.playback` ＋ `mixWithOthers` で再生を始められれば、再生中はアプリが止まりません [S6]。ただし、次の点は実機で確かめる必要があります。(1) BLE で起きたときに、本当に再生を始められるか（Apple 社員と確認できない投稿で「起きた理由によっては再生できない」という話がある [S13]）。(2) 起きるまでの秒数（Apple の資料に数字がない）。(3) Mac の BLE アドレスが変わったときに、つなぎ直せるか。

**実機ログでの確認（2026-11-05）：** (1) については、リングの BLE 通信で裏で起きている間に、返事10〜13 が届いてから約1秒で再生された（アプリのログ 16:36〜17:13 UTC、画面は閉じたまま）。BLE で起きたアプリは、今の `mixWithOthers` 系のセッションで再生を始められる。

**確実さを優先するなら（第2案・今の「A案」）：無音を流し続けて、アプリを止めさせない。** 再生している間はアプリが止まらない、と Apple が書いています [S6]。ただし Apple は「裏で流すのは聞こえる音で、無音ではいけない」とも書いています [S6]。これは App Store 審査のルールで、個人ビルドには審査がありません。電池は使います。電話などで音が中断されると、アプリは止まります。

**「返事ができるまで HTTP リクエストを1本開けたままにする（ロングポーリング）」は、数秒で何回も届ける用途には使えません。** 普通の URLSession では、止まっているアプリは届いたデータを処理できません [S19]。バックグラウンド用の URLSession なら、終わったときにアプリを起こせます [S29]。しかし、裏で次のリクエストを始めると、システムの判断で後回しにされます [S26]。さらに、アプリを起こすたびに待ち時間が倍になる仕組みがあります [S24][S30]。1回目は届いても、2回目からだんだん遅れます（§8）。

---

## 比較表

| # | 方式 | (a) 無料 Apple ID で使えるか（必要な権限） | (b) Mac から好きなときに起こせるか | (c) 起きるまでの時間 | (d) 起きたあと動ける時間・再生できるか | (e) 弱点（強制終了・低電力モード・再起動） |
|---|---|---|---|---|---|---|
| 1 | **Mac を BLE ペリフェラルにする**（接続しっぱなし＋notify、または接続待ち） | **使える**。Background modes（bluetooth-central）だけで足りる。特別な権限は不要 [S1][S41] | **起こせる**。値の通知・接続・切断でアプリが起きる [S2] | Apple の資料に数字なし【未検証】。接続しっぱなしなら速いはず【推測】。接続待ちは、裏のスキャンが遅くなるぶん遅れることがある [S2] | 約10秒 [S2]。再生を始められれば、再生中は動き続ける [S6]。再生を始められるかは【未検証】[S7][S8][S13] | ユーザーが強制終了すると起動し直さない（iOS 26 以降、AccessorySetupKit を使うアプリは例外という注があるが、注の範囲ははっきりしない。§1）[S3]。再起動後は最初のロック解除まで起動し直さない [S3]。設定アプリで Bluetooth を切ると戻らない [S3]。Mac がスリープすると止まる【推測】。アドレスが変わる問題あり（§1） |
| 2 | iBeacon（Mac がビーコンを出し、iPhone が領域監視） | 使える（位置情報「常に許可」が必要。特別な権限は不要）[S34] | 一応できる（Mac は iBeacon を出せる [S35]） | 遅い・数字の保証なし。出る（exit）判定には時間がかかる【二次情報】。Background App Refresh が切れていると起こさない [S33] | 約10秒 [S33]。位置情報で起きたときは再生できない例がある【二次情報】[S13] | 強制終了後も位置情報アプリは起動し直す [S6]。低電力モードで Background App Refresh が切れる→起きない [S37][S33]。再起動後はロック解除まで監視しない [S34] |
| 3 | PushKit（VoIP プッシュ）＋CallKit | **使えない**（APNs が必要。Push notifications は無料欄に印なし）[S16][S1] | APNs 経由のみ | ― | 着信画面（CallKit）を必ず出す必要あり [S17] | ― |
| 4 | Local Push Connectivity（NEAppPushProvider） | **使えない**（Network Extensions の `app-push-provider` 権限が必要。無料欄に印なし）[S14][S1] | 権限があればできる（LAN 内でプッシュの代わり）[S14] | ― | 拡張機能はローカル通知か CallKit の着信しか出せない。アプリ本体で再生はできない [S14] | 指定した Wi-Fi（SSID）の中だけで動く [S14] |
| 5 | その他の Network Extension（VPN など） | **使えない**（Network extensions・Personal VPN とも無料欄に印なし）[S1] | ― | ― | ― | ― |
| 6 | 位置情報（大きな移動・領域・連続取得） | 使える | **起こせない**（端末の移動で決まり、Mac から操作できない）[S6][S33] | ― | ― | ― |
| 7 | BGTaskScheduler（App Refresh／Processing） | 使える（Background modes の fetch／processing）[S1][S41] | **起こせない**（いつ動くかはシステムが決める）[S22][S23] | 「指定した時刻より前には始まらない」だけで、保証なし [S23] | App Refresh は最大30秒 [S22] | 低電力モードで Background App Refresh が切れる [S37] |
| 8 | バックグラウンド URLSession（ロングポーリング） | 使える（特別な権限は不要） | 1回目は「返事ができたら応答を返す」で起こせる。2回目以降は遅れていく [S24][S26][S30] | 起こすまでの時間は資料に数字なし。裏で始めた次のリクエストは後回しにされ、さらに待ち時間が倍々になる [S26][S30] | 終わったときに起こされる [S29]。再生できるかは方式1と同じく【未検証】 | 強制終了すると転送は取り消され、起動し直さない [S25]。低電力モードなどで後回しにされる [S30] |
| 9a | Live Activity のプッシュ更新 | **使えない**（APNs と Push Notifications 機能が必要）[S21][S1] | ― | ― | ― | ― |
| 9b | Push to Talk | **使えない**（APNs を使う。Push to Talk は無料欄に印なし）[S20][S1] | ― | ― | ― | ― |
| 9c | ショートカットの個人用オートメーション（例：メッセージ受信） | アプリ側は App Intent を作るだけで、特別な権限は不要【推測】 | Mac から iMessage を送るなどで、引き金を引ける可能性あり【未検証】 | 不明 | `AudioPlaybackIntent` という「音を流すインテント」の仕組みはある [S39]。裏で起動して再生できるかは【未検証】 | ロック中に動かすには「ロック中も実行を許可」の設定が必要 [S38]。iMessage はインターネット経由 |
| 9d | その他（Apple Watch、Nearby Interaction、AirPlay、Find My など） | ― | 今回の目的に使える公開の仕組みは見つからなかった | ― | ― | ― |
| 10 | **無音を流し続けて止めさせない**（基準案） | **使える**（Background modes の audio）[S1][S10] | 起こす必要がない（止まらない）。今の3秒ごとの問い合わせがそのまま動く | 0秒＋問い合わせ間隔（今は3秒） | 再生している間はずっと動ける [S6]。すでに音声セッションが動いているので、返事の再生も始めやすい【推測】 | 電話・Siri・アラームなどで中断されると止まる [S12]。Apple は「無音ではなく聞こえる音を」と書いている [S6]、「無音を流すのでなくバックグラウンドタスクを使え」とも [S11]。電池を使う。強制終了や再起動のあとは、アプリを開くまで戻らない [S6] |

---

## 1. Mac を BLE ペリフェラルにする（第1案）

### (a) 無料 Apple ID で使えるか
- 使えます。iOS 側で必要なのは Background modes の `bluetooth-central` だけで、Background modes は無料の「Apple Developer」でも使えます [S1]。このアプリの Info.plist にはすでに入っています。
- `bluetooth-central` は「Bluetooth のアクセサリーと裏で通信する」ためのモードです [S41]。

### (b) Mac から好きなときに起こせるか
- 起こせます。Apple の資料には「`bluetooth-central` のアプリは、`CBCentralManagerDelegate` か `CBPeripheralDelegate` のメソッドが呼ばれるときにシステムが起こす。たとえば、接続ができたとき・切れたとき、**周辺機器が特性の値を更新して送ってきたとき**」とあります [S2]。
- 接続の要求（`connect`）には期限がありません。「接続要求はタイムアウトしない」と書かれています [S2]。つまり、iPhone から接続を頼んでおけば、Mac が見つかった時点で接続ができて、アプリが起きます。
- Mac 側は macOS の `CBPeripheralManager` でペリフェラルになれます（macOS 10.9 以降）[S4]。広告に入れられるのは、名前とサービス UUID の2つだけです [S4]。

**作り方の候補（2通り）**
1. **接続しっぱなし＋notify（おすすめ）**：アプリが前にいる間に Mac へ接続し、通知用の特性を購読しておく。返事ができたら Mac が値を送る → アプリが起きる [S2]。
2. **接続待ち**：iPhone から `connect` を出したままにして、Mac は返事ができたときだけ広告を出す → 接続ができたときにアプリが起きる [S2]。

**逆向き（iPhone をペリフェラル、Mac をセントラル）はおすすめしません。** 裏にいる iOS アプリの広告では、サービス UUID が特別な「あふれ領域」に入り、「それを明示的に探している **iOS 端末**だけが見つけられる」と書かれています [S2][S4]。Mac から見つけられるかは保証されていません。

### (c) 起きるまでの時間
- **Apple の資料には秒数がありません。【未検証】**
- 接続しっぱなしなら、値を送ってから届くまでは BLE の接続間隔（短い）程度で、すぐ起きるはずです【推測】。最初にこれを実機で測るのがよいです。
- 接続待ちの場合は、裏にいるアプリしかスキャンしていないと、スキャンの間隔が長くなり、見つけるまで時間がかかることがあります [S2]。

### (d) 起きたあと動ける時間・再生
- 起きたアプリには「約10秒」あります [S2]。
- その間に再生を始められれば、audio モードのアプリは「再生している間は裏で動き続ける。再生が止まると一時停止される」[S6]。短い返事なら最後まで流せます。
- 再生を始める条件：
  - 裏で、他の音と混ざらない（non-mixable）セッションを有効にしようとするとエラーになります（`cannotInterruptOthers`）[S8]。**`mixWithOthers` を必ず付けます** [S9]。
  - 「裏にいて、裏で音を出せないカテゴリーを使っている」と `cannotStartPlaying` になります [S7]。カテゴリーは `.playback` にします [S10]。
  - **【二次情報】** BLE で起きたアプリが再生できなかった、という 2016 年ごろのフォーラムの話があります [S13]。回答者は「mixable にしても、アプリが**なぜ起きたか**をシステムが内部で調べてから再生を許す。たとえば位置情報で起きたときは再生できない」と書いています。この回答には Apple 社員の表示がありません。
  - **【未検証】** このアプリでは「起きたときに未再生の返事を全部流す」処理がすでにあります（`HANDOFF.md` §3）。しかし、それが**画面を閉じたまま（裏で）BLE で起きたとき**に鳴ったかどうかは、記録から読み取れません。実機で確かめる必要があります。
  - 再生が始められなかったときの予備として、**ローカル通知の音**で鳴らす方法があります。ローカル通知はプッシュの権限がいりません【推測】。通知の音には、`Library/Sounds` に置いた30秒未満の aiff / wav / caf ファイルを使えます [S40]。消音スイッチや集中モードでどうなるかは【未検証】です。

### (e) 弱点
- **強制終了**：TN3115 の表では「ユーザーが強制終了 → 起動し直さない」。注5に「iOS 26 以降は、AccessorySetupKit で Bluetooth アクセサリーを設定したアプリだけが起動し直される」とあります [S3]。ただし、この注5は「コントロールセンターの Bluetooth ボタン → 起動し直す」の行にも付いていて、どの行の話なのかはっきりしません。AccessorySetupKit は iOS 18 からの、アクセサリーを選んで登録する仕組みです [S5]。このアプリは Mac を AccessorySetupKit で登録していないので、**強制終了したら、ユーザーが開くまで起きない**と考えるのが安全です。
- **メモリ不足（jetsam）・クラッシュで落ちた場合**：「メモリから外された」「クラッシュした」は「起動し直す」です [S3]。ただし、状態の復元（`CBCentralManagerOptionRestoreIdentifierKey`）を使っている必要があります [S2]。また、起動し直すのは「待っていた Bluetooth のできごと（スキャン・接続・購読している通知など）が実際に起きたとき」だけです [S3]。
- **再起動**：起動し直しますが、パスコードがある端末では、再起動後に最初にロックを解除するまで起動し直しません [S3]。
- **Bluetooth を設定アプリで切る**：起動し直しません [S3]。
- **低電力モード**：Apple の資料には、Core Bluetooth の起こし方が低電力モードや Background App Refresh で変わる、という記載は見つかりませんでした（低電力モードで切れるのは Background App Refresh など [S37]）。【未検証】
- **Mac のアドレスが変わる問題【二次情報・推測】**：Mac は BLE のアドレスを定期的に変えると言われています。iOS が周辺機器を区別する `identifier` が変わらない保証は、Apple 社員の発言としては見つかりませんでした（フォーラムの一般利用者は「長期間同じとは限らない」と書いている [S42]）。もし変わると、古い `identifier` への「接続待ち」はいつまでも終わりません。対策の案【推測】：
  - Mac 側の特性を「暗号化が必要」にして、iPhone と Mac をペアリング（ボンディング）させる。ペアリングした相手はアドレスが変わっても見分けられるはず。
  - 接続が切れたら、サービス UUID でスキャンし直してから `connect` を出し直す。アプリを開いたときにも出し直す。
- **Mac 側**：Mac がスリープすると Bluetooth の広告や接続は止まると考えられます【推測】。返事サーバーと同じく、Mac をスリープさせない設定が必要です。Mac のプログラムには Bluetooth の利用許可（プライバシー設定）が必要です【推測】。
- **リングとの関係**：今のアプリはリングのために `CBCentralManager`（状態の復元つき）をすでに使っています。Mac 用の接続もこの仕組みに足す形になります。リング側の処理は変えない約束なので、別の部品として足すのがよいです【推測】。

---

## 2. iBeacon（Mac がビーコンを出す）

- (a) 使えます。位置情報の「常に許可」が必要です。会員種別の表に位置情報の項目はなく、特別な権限は不要です [S1][S34]。
- (b) Mac は iBeacon を出せます。`CLBeaconRegion.peripheralData(withMeasuredPower:)` は macOS 10.15 から使えて、それを `CBPeripheralManager` で広告します [S35][S36]。ただし iOS 27.3 / macOS 27.3 で非推奨になっています [S35]。
- (c) 遅いです。領域の「入った／出た」は、すぐには来ないと Apple 自身が書いています（地理的な領域の例では「境界を越えて、一定の距離を20秒以上離れてから」）[S33]。ビーコン領域の具体的な秒数は Apple の資料に見つかりませんでした。Mac が広告を止めて「出た」と判定されるまでにも時間がかかります【二次情報】。返事のたびに「出る→入る」を繰り返す必要があり、数秒では無理です。
- (d) 起きたら約10秒 [S33]。位置情報で起きたときは再生が許されない、という話があります【二次情報】[S13]。
- (e) **Background App Refresh が切れていると、領域のイベントでは起こさない** [S33]。低電力モードでは Background App Refresh が切れます [S37]。再起動後はロック解除まで監視しません [S34]。強制終了されても位置情報アプリは起動し直されます [S6]（この点だけは BLE より強い）。
- 結論：遅くて不安定なので、今回は向きません。

---

## 3. PushKit（VoIP プッシュ）＋ CallKit

- PushKit も通常の通知も、APNs を使って届けます [S16]。APNs に入れない環境用の古い「VoIP ソケット」の仕組みは、iOS 10 の SDK でビルドすると**無効**になります [S18]。
- iOS 13 SDK 以降では、VoIP プッシュを受けたら CallKit で着信を出す必要があります。CallKit を使えないなら PushKit は使えません [S17]。
- Push notifications は無料の Apple ID では使えません [S1]。→ **使えません。**

---

## 4. Local Push Connectivity（NEAppPushProvider、iOS 14 以降）

- APNs に届かない LAN の中で、プッシュの代わりにサーバーと常時接続を保つ仕組みです [S14]。
- **Network Extensions の権限（`app-push-provider`）が必要で、Apple に申請して受け取る**、と書かれています [S14]。Network extensions は無料の Apple ID の欄に印がありません [S1]。→ **使えません。**
- 仮に使えても、拡張機能ができるのは「ローカル通知を出す」か「CallKit の着信を出す」ことだけです [S14]。アプリ本体を起こして音を流す用途とは違います。
- 指定した Wi-Fi（SSID）に入っているときだけ動きます [S14]。

---

## 5. その他の Network Extension

- VPN（Personal VPN を含む）、パケットトンネルなどは、Network extensions または Personal VPN の権限が必要です。どちらも無料欄に印がありません [S1][S15]。→ **使えません。**
- 参考：表の「Sustained Execution」も無料欄に印がありません [S1]。中身を説明する Apple の資料は今回見つけられませんでした。

---

## 6. 位置情報（大きな移動・領域・裏での連続取得）

- これらは**端末が動いたこと**で起きる仕組みです [S6][S33]。Mac から起こすことはできません（iBeacon は §2）。
- 位置情報の連続取得（`location` モード）でアプリを止めないようにすることはできます [S41] が、目的外の利用で電池も使うため、§10 の無音再生と同じ「止めさせない」系の案になります【推測】。
- → **Mac から起こす手段としては使えません。**

---

## 7. BGTaskScheduler（App Refresh／Processing）

- いつ動くかは**システムが決めます** [S22]。`earliestBeginDate` は「これより前には始めない」だけで、その時刻に始まる保証はありません [S23]。
- App Refresh は最大30秒動けます [S22]。Processing は充電中の夜など、空いている時間に動きます [S22]。
- 低電力モードでは Background App Refresh が切れます [S37]。
- → **Mac から好きなときに起こせないので、使えません。**

---

## 8. HTTP を開けたまま待つ（ロングポーリング）／バックグラウンド URLSession

ユーザーの案：「返事ができるまで HTTP リクエストを1本開けておき、応答が届いたらアプリが起きる」。

### 8-1. 普通の URLSession やソケットで待つ場合 → 起きない
- 止まっているアプリでは、アプリのコードは一切動かず、**届いたデータを処理できません** [S19]。
- 止まっている間に、システムがソケットの資源を回収して、接続が切れることがあります [S19]。
- 昔は「VoIP ソケット」として登録すると、データが届いたときにアプリを起こせました [S19]。しかしこの仕組みは iOS 10 SDK でビルドすると無効です [S18]。
- **結論：普通のリクエストに応答が届いても、止まっているアプリは起きません。**

### 8-2. バックグラウンド用の URLSession（`background(withIdentifier:)`）で待つ場合
- **使える種類のタスク**：バックグラウンドセッションは、アップロードとダウンロードを別のプロセスに任せる仕組みです [S25][S32]。Apple 社員（Quinn）の表では、バックグラウンドセッションで「データタスク（delegate 方式）」も作れますが、「**リクエスト中にアプリが止まらない場合だけ動く**」とされています [S31]。つまりロングポーリングには**ダウンロードタスク**を使う必要があります。
- **長く待たせてよいか**：
  - `timeoutIntervalForRequest`（既定60秒）は「次のデータが来るまで待つ時間」で、データが来るたびにリセットされます [S27]。サーバーが途中で少しずつデータを送れば延ばせるかもしれません【推測】。
  - バックグラウンドセッションのアップロード／ダウンロードは、タイムアウトで失敗すると**自動で再試行**されます。全体の上限は `timeoutIntervalForResource`（既定7日）です [S27][S28]。
- **終わったときにアプリが起きるか**：起きます。`sessionSendsLaunchEvents` が `true`（既定）なら、タスクが終わったときにシステムがアプリを起こすか起動し、`application(_:handleEventsForBackgroundURLSession:completionHandler:)` を呼びます [S29][S24]。**ただし、終わってから何秒で起こすかは、資料に書かれていません。【未検証】**
- **裏で始めたタスクは「後回し可」になる**：「アプリが裏にいるときに始めた転送は、システムが都合のよいときに始める。`isDiscretionary` を `true` とみなし、指定した値は無視する」[S26]。後回しの理由には、低電力や Wi-Fi がないことなどがあります [S30]。
- **起こすたびに待ち時間が倍になる（再開の頻度制限）**：
  - Apple の公式資料：「システムがアプリを再開・起動するとき、頻度を制限する。裏で新しいダウンロードを始めると、待ち時間が過ぎるまで始まらない。待ち時間は、アプリを再開・起動するたびに長くなる」。待ち時間がリセットされるのは、ユーザーがアプリを前に出したときと、待ち時間の間にアプリが起こされなかったときです [S24]。
  - Quinn（Apple DTS）の説明：待ち時間は**起こすたびに2倍**になり、「やがて、アプリが止まったように見えるほど長くなる」[S30]。
- **強制終了**：ユーザーが強制終了すると、バックグラウンドの転送は取り消され、アプリは起動し直されません [S25]。

### 8-3. 起きたあと再生できるか
- 起きる仕組みが違うだけで、条件は §1(d) と同じです。`.playback` ＋ `mixWithOthers` が必要です [S7][S8][S9]。BLE で起きたとき・URLSession で起きたときに再生が許されるかは、Apple の資料に書かれていません。【未検証】

### 8-4. 判定
- **1回目**（アプリが前にいるときにリクエストを始めた場合）は、返事ができた時点で応答を返せば、アプリが起きる可能性があります。起きるまでの秒数は不明です。
- **2回目以降**は、裏で次のリクエストを始めることになるので、(1) システムの判断で後回しにされ [S26]、(2) 起こすたびに待ち時間が倍になります [S24][S30]。
- **→「数秒以内に、何回も」届ける目的には使えません。** 「アプリを開いていない間に返事が1件だけ来る」なら役に立つ可能性はありますが、BLE 案（§1）のほうが仕組みとして素直です。

---

## 9. その他（短く）

- **Live Activity のプッシュ更新**：APNs に送り、Push Notifications 機能が必要です [S21]。→ 使えません [S1]。
- **Push to Talk**：APNs のトークンでアプリを起こす仕組みです [S20]。無料欄に印がありません [S1]。→ 使えません。
- **ショートカットの個人用オートメーション**：引き金には「メッセージ」「メール」「Wi-Fi」「Bluetooth」「NFC」「App」などがあり、確認なしで動かせるものがあります。ロック中に動かすには「ロック中も実行を許可」が必要です [S38]。たとえば Mac から決まった文面の iMessage を送り、オートメーションでこのアプリの App Intent（音を流すことを示す `AudioPlaybackIntent` [S39]）を動かす、という形が考えられます。**ただし、アプリが裏で起動して音を流せるか、どれくらい速いかは確かめていません。【未検証】** iMessage はインターネットと Apple のサーバーを通ります。
- **Apple Watch・Nearby Interaction・AirPlay・Find My**：Mac から iPhone アプリを好きなときに起こす公開の仕組みは見つかりませんでした。Nearby Interaction の裏モード（`nearby-interaction`）は対応アクセサリー向けです [S41]。【未調査に近い】

---

## 10. 無音を流し続けて、アプリを止めさせない（基準案・今の「A案」）

- (a) 使えます。Background modes の `audio` は無料で使えます [S1][S10]。このアプリにはすでに入っています。
- (b)(c) そもそもアプリが止まらないので、今の「3秒ごとに Mac に問い合わせる」処理がそのまま動きます。遅れは問い合わせ間隔だけです。
- (d) 「audio を持つアプリは、再生している間は裏で動き続ける。再生が止まると一時停止される」[S6]。音声セッションはすでに有効なので、返事の再生も始めやすいはずです【推測】。他のアプリの音を止めないよう `mixWithOthers` を使います [S9]。
- (e) 弱点：
  - **Apple の方針**：「裏で音を流すアプリは、聞こえる音を流すこと。無音ではいけない」[S6]。「止めさせないために無音を流すのではなく、バックグラウンドタスクを使うこと」[S11]。これは App Store の審査向けのルールで、個人ビルドには審査がありません。ただし、システムが無音を検出して止めるかどうかは資料に書かれていません。【未検証】
  - **中断**：電話の着信などで音声セッションは中断されます [S12]。中断のあと再開しないと、再生が止まった状態になり、アプリは一時停止されます [S6]。中断が終わったら再開する処理が必要です（iOS 27 以降は新しい通知もある）[S12]。
  - **強制終了・再起動**：強制終了されると、ユーザーが開くまで動きません [S6]。再起動のあとも同じです。
  - **電池**：ずっと音声の処理が動くので電池を使います。
  - **併用**：第1案（BLE）と組み合わせると、無音再生が中断されて止まっても、BLE で起こして戻せます【推測】。

---

## 確かめられなかったこと（実機で測る必要があるもの）

1. BLE（notify・接続）でアプリが起きたとき、`.playback`＋`mixWithOthers` で**再生を始められるか**。Apple の公式資料には、起きた理由ごとの可否が書かれていない。
2. Mac が notify を送ってから、アプリが起きるまでの**秒数**（接続しっぱなしの場合・接続待ちの場合）。
3. Mac のペリフェラルの `identifier` が、時間・再起動・OS 更新で**変わらないか**。ペアリングすると安定するか。
4. 低電力モードや Background App Refresh オフで、BLE による起こし方が変わるか。
5. バックグラウンド URLSession の応答が届いてから、アプリが起きるまでの秒数。
6. 無音再生を長時間続けたとき、システムが止めることがあるか。
7. TN3115 注5（iOS 26 以降の AccessorySetupKit）がどの行に当てはまるのか。
8. ショートカットのオートメーションから App Intent を裏で動かして再生できるか。

---

## 出典

- [S1] Apple Developer Account Help「Supported capabilities (iOS)」（会員種別ごとの機能表。ADP / ADEP / Apple Developer（無料）の列）https://developer.apple.com/help/account/reference/supported-capabilities-ios
  - 無料（Apple Developer）の列に印があったもの：App groups、Background modes、Data protection、HealthKit、HomeKit、Inter-App Audio、Keychain sharing、Maps、Wireless Accessory Configuration。
  - 印がなかったもの（抜粋）：Push notifications、Network extensions、Personal VPN、Push to Talk、Time Sensitive Notifications、Communication Notifications、Siri、Sustained Execution、Access WiFi information、Multipath。
- [S2] Core Bluetooth Programming Guide「Core Bluetooth Background Processing for iOS Apps」https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html
- [S3] TN3115「Bluetooth State Restoration app relaunch rules」（2025-09-15 に iOS 26 向けに更新）https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules
- [S4] `CBPeripheralManager.startAdvertising(_:)` https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)
- [S5] AccessorySetupKit https://developer.apple.com/documentation/accessorysetupkit ／ Discovering and configuring accessories https://developer.apple.com/documentation/accessorysetupkit/discovering-and-configuring-accessories
- [S6] App Programming Guide for iOS「Background Execution」（2018-01-16 保存。今の URL は転送されるため Wayback Machine の保存版）https://web.archive.org/web/20180116140424/https://developer.apple.com/library/content/documentation/iPhone/Conceptual/iPhoneOSProgrammingGuide/BackgroundExecution/BackgroundExecution.html
- [S7] `AVAudioSession.ErrorCode.cannotStartPlaying` https://developer.apple.com/documentation/coreaudiotypes/avaudiosession/errorcode/cannotstartplaying
- [S8] `AVAudioSession.ErrorCode.cannotInterruptOthers` https://developer.apple.com/documentation/coreaudiotypes/avaudiosession/errorcode/cannotinterruptothers
- [S9] `AVAudioSession.CategoryOptions.mixWithOthers` https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/mixwithothers
- [S10] Configuring your app for media playback https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback
- [S11] Audio Session Programming Guide「Audio Guidelines By App Type」https://developer.apple.com/library/archive/documentation/Audio/Conceptual/AudioSessionProgrammingGuide/AudioGuidelinesByAppType/AudioGuidelinesByAppType.html
- [S12] Handling audio interruptions https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions
- [S13] **【二次情報】** Apple Developer Forums「AVAudioSession setActive throws unknown error」（回答者 theanalogkid。ページ上に Apple 社員の表示なし）https://developer.apple.com/forums/thread/38917
- [S14] Local push connectivity https://developer.apple.com/documentation/networkextension/local-push-connectivity
- [S15] Network Extensions Entitlement https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.networking.networkextension
- [S16] PushKit https://developer.apple.com/documentation/pushkit
- [S17] Responding to VoIP Notifications from PushKit https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit
- [S18] Technical Q&A QA1938「iOS 10 and the Legacy VoIP Architecture」https://developer.apple.com/library/archive/qa/qa1938/_index.html
- [S19] Technical Note TN2277「Networking and Multitasking」https://developer.apple.com/library/archive/technotes/tn2277/_index.html
- [S20] Push to Talk https://developer.apple.com/documentation/pushtotalk
- [S21] Starting and updating Live Activities with ActivityKit push notifications https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
- [S22] Choosing Background Strategies for Your App https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app
- [S23] `BGTaskRequest.earliestBeginDate` https://developer.apple.com/documentation/backgroundtasks/bgtaskrequest/earliestbegindate
- [S24] Downloading files in the background（「Use background sessions efficiently」の節を含む）https://developer.apple.com/documentation/foundation/downloading-files-in-the-background
- [S25] `URLSessionConfiguration.background(withIdentifier:)` https://developer.apple.com/documentation/foundation/urlsessionconfiguration/background(withidentifier:)
- [S26] `URLSessionConfiguration.isDiscretionary` https://developer.apple.com/documentation/foundation/urlsessionconfiguration/isdiscretionary
- [S27] `URLSessionConfiguration.timeoutIntervalForRequest` https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforrequest
- [S28] `URLSessionConfiguration.timeoutIntervalForResource` https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforresource
- [S29] `URLSessionConfiguration.sessionSendsLaunchEvents` https://developer.apple.com/documentation/foundation/urlsessionconfiguration/sessionsendslaunchevents
- [S30] Apple Developer Forums「NSURLSession's Resume Rate Limiter」（Quinn "The Eskimo!"、Apple DTS）https://developer.apple.com/forums/thread/14854
- [S31] Apple Developer Forums「URLSession Task Types vs Session Types」（Quinn "The Eskimo!"、Apple DTS）https://developer.apple.com/forums/thread/746759
- [S32] URLSession https://developer.apple.com/documentation/foundation/urlsession
- [S33] Location and Maps Programming Guide「Region Monitoring and iBeacon」https://developer.apple.com/library/archive/documentation/UserExperience/Conceptual/LocationAwarenessPG/RegionMonitoring/RegionMonitoring.html
- [S34] Monitoring the user's proximity to geographic regions https://developer.apple.com/documentation/corelocation/monitoring-the-user-s-proximity-to-geographic-regions
- [S35] `CLBeaconRegion.peripheralData(withMeasuredPower:)` https://developer.apple.com/documentation/corelocation/clbeaconregion/peripheraldata(withmeasuredpower:)
- [S36] Turning an iOS device into an iBeacon device https://developer.apple.com/documentation/corelocation/turning-an-ios-device-into-an-ibeacon-device
- [S37] Apple サポート「Use Low Power Mode to save battery life on your iPhone」https://support.apple.com/en-us/101604
- [S38] ショートカット ユーザガイド「Create a new personal automation」https://support.apple.com/guide/shortcuts/create-a-new-personal-automation-apdfbdbd7123/ios
- [S39] `AudioPlaybackIntent` https://developer.apple.com/documentation/appintents/audioplaybackintent
- [S40] `UNNotificationSound` https://developer.apple.com/documentation/usernotifications/unnotificationsound
- [S41] Configuring background execution modes https://developer.apple.com/documentation/xcode/configuring-background-execution-modes
- [S42] **【二次情報】** Apple Developer Forums「CBPeripheral identifier is changing」（回答者は一般利用者、2015年）https://developer.apple.com/forums/thread/27039
