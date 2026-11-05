# 返事をIndexフィードに表示する — 設計プラン

> **注記（2026-11-05 夜）**：この文書は作成日時点の設計記録です。今の状態は `docs/tts-reply/HANDOFF.md` を見てください。返事の取得間隔は今は1秒です。

- 作成日：2026-11-04
- 前提：`docs/tts-reply/DESIGN.md` のMVP（返事の受信・自動再生・返事ログ）が動いていること
- 実施時期：実機テスト T1〜T8 の完了後

## 1. やること

返事ログを設定画面（Reply audio ダイアログ）から、**Indexタブのホーム（フィード）**に移す。

```text
Indexタブ
  ├ Index feed（録音）      … 既存
  ├ Replies（返事）  ★追加  … 新しい順に5件。タップで再生。未再生は●
  ├ Reminders               … 既存
  ├ Notes                   … 既存
  └ You asked               … 既存

設定 → Reply audio
  └ Server URL / Token / Auto-play / Save / エラー表示 のみ（返事一覧は削除）
```

## 2. 決めたこと

| 項目 | 内容 |
|---|---|
| 置き場所 | 「Index feed」の下、「Reminders」の上 |
| 表示件数 | 新しい順に5件。見出しの右に総件数。「Show more」で同じ場所に全件を展開（新しい画面は作らない） |
| カード | 「You asked」のカードと同じ見た目。上段に●（未再生のみ）と相対時刻、下段に本文（最大3行） |
| タップ | 音声ありの返事は再生（既存の `ReplyAudioService.replay`）。音声なしはタップ不可 |
| 検索中 | 返事のセクションは隠す（他セクションの件数表示と食い違わないため） |
| 状態の取得 | 既存の `ReplyAudioViewModel`（`entries` / `replay`）をフィード画面から使う。`IndexFeedViewModel` は変更しない |

## 3. 変更するファイル

| ファイル | 変更 |
|---|---|
| `ring/ui/screens/home/ReplyFeedSection.kt`（新規） | `LazyListScope.repliesSection(...)` とカード。`FeedSectionHeader`・`IndexTheme` の色・`relativeTime` を再利用 |
| `ring/ui/screens/home/IndexFeedScreen.kt` | `ReplyAudioViewModel` を取得し、録音セクションの後に `repliesSection` を1行で呼ぶ |
| `ring/external/replyaudio/ReplyAudioModels.kt` | `ReplyAudioRules.feedPreview(log, expanded)`（表示する件の選択）を追加 |
| `ring/external/replyaudio/ReplyAudioDialog.kt` | 返事一覧（`LazyColumn`・`ReplyRow`）を削除 |
| `ring/ui/screens/settings/IndexSettings.kt` | 「Reply audio」行の説明文を「Auto-play agent replies」に変更 |
| `commonTest/.../replyaudio/ReplyAudioServiceTest.kt` | `ReplyAudioRulesTest` に `feedPreview` のテストを追加 |

（パスはすべて `experimental/src/commonMain/kotlin/coredevices/` から）

DI・ナビゲーション・データベースの変更はなし。

## 4. 影響しないもの（確認済み）

- リング録音・BLE・文字起こし・エージェント処理・Webhook送信
- `IndexFeedViewModel` とそのテスト（`IndexFeedViewModelComputeTest`）
- データベースのスキーマ（返事ログは引き続き Settings に保存）

## 5. 注意点

- LazyColumnのキーは `reply-header` / `reply-<id>` とし、他セクションと重ならないようにする。
- 先頭の `__top_anchor__` より前には入れない（スクロール位置が勝手にずれる問題の対策を壊さないため）。
- 再生中に同じカードを連打すると、その回数だけ続けて再生される（既存の再生キューの仕様。MVPでは変更しない）。
- ●は再生が終わった時点で消える。

## 6. 確認方法

1. `./gradlew :experimental:testAndroidHostTest --tests 'coredevices.ring.external.replyaudio.*'`
2. iOS再ビルド → 実機に上書きインストール（アプリのデータと信頼設定は残る）
3. Mac から返事を2件送る → Indexタブに2件並ぶ、未再生の●、タップで再生、再生後に●が消える
4. 設定 → Reply audio に一覧が出ないこと、保存・エラー表示が今までどおり動くこと
