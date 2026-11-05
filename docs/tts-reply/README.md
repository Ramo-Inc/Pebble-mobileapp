# リングの音声会話 — ドキュメント

リング（Pebble Index 01）で話しかけて、音声で返事をもらう仕組み一式のドキュメントです。

| 文書 | 内容 |
|---|---|
| [HANDOFF.md](HANDOFF.md) | **最初に読む。** 今の状態、ユーザーの返事待ち、残っている課題、守ること |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 3つの経路と、どの機能がどのファイルにあるか |
| [CLIENT_GEMINI_LIVE_DESIGN.md](CLIENT_GEMINI_LIVE_DESIGN.md) | アプリ内 Gemini（リングの区切りを Gemini に流し込む）の設計・実装・実測 |
| [BUILD_AND_TEST.md](BUILD_AND_TEST.md) | Android・iPhone のビルドとインストール、記録の見方、実機での確認項目 |
| [WAKE_WITHOUT_PUSH_RESEARCH.md](WAKE_WITHOUT_PUSH_RESEARCH.md) | iPhone のアプリをプッシュ通知なしで起こす方法の調査（2026-11-05） |

Mac 側の説明書：[返事サーバー](../../tools/reply-server/README.md)（[API](../../tools/reply-server/API.md)・[送り手の仕様](../../tools/reply-server/SENDER_SPEC.md)）、[pebble-ring と秘書](../../tools/skill/pebble-ring/README.md)。
