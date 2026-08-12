# サードパーティ由来の設計・ライセンスに関する注記 (NOTICE)

## このプロジェクトのライセンス

本プロジェクトは **GPL-3.0-or-later** です。全文は同梱の `LICENSE` を参照してください。

## 参考にした設計パターン

- **AOSP DocumentsUI** (Apache License 2.0, Android Open Source Project)
  - パンくずナビゲーション(タップで任意の親ディレクトリへ直接移動)
  - ソートメニューの構成(名前/サイズ/種類、昇順・降順の切り替え)
  - mimeタイプに応じたアイコン表示の考え方(絵文字ではなくベクター画像で種別を示す)

- **Amaze File Manager** (TeamAmaze, GPL-3.0-or-later)
  - Copyright (C) 2014- Arpit Khurana, Vishal Nehra, Emmanuel Messulam, Raymond Lai, Vishnu Sanal T and Contributors
  - https://github.com/TeamAmaze/AmazeFileManager
  - 参考にした点: 複数選択モード(チェックボックス・全選択・まとめて削除/コピー/切り取り)、
    内部クリップボードによるコピー&ペースト操作フロー、ファイル一覧のソートUI構成
  - **本プロジェクトのコードは、Amaze File Managerのソースコードを逐語的にコピー・移植した
    ものではありません。** 実装は独自に(Kotlin/Shizuku/run-as前提のアーキテクチャに合わせて)
    行っています。あくまで「一般的なAndroidファイルマネージャーとして期待される機能・UI構成」
    としての参考です。
  - もし今後、Amaze File Manager (またはその他のGPLプロジェクト) の具体的なソースコードを
    本プロジェクトに直接組み込む場合は、当該ファイルの元の著作権表示・GPLヘッダーを保持し、
    変更箇所を明記した上で、GPL-3.0の条件に従ってください。

## ランタイム依存ライブラリのライセンス

| ライブラリ | ライセンス |
|---|---|
| AndroidX (core-ktx, appcompat, recyclerview, constraintlayout, documentfile, lifecycle) | Apache License 2.0 |
| Material Components for Android | Apache License 2.0 |
| Kotlin標準ライブラリ / kotlinx.coroutines | Apache License 2.0 |
| Shizuku API (`dev.rikka.shizuku:api`, `:provider`) | Apache License 2.0 |
| JGit (`org.eclipse.jgit`) | BSD-3-Clause (Eclipse Distribution License 1.0) |

GPL-3.0の下で本プロジェクト全体を配布する場合、これらApache-2.0のライブラリとの組み合わせは
問題ありません(Apache-2.0はGPL-3.0とのリンクが許容されています)。
