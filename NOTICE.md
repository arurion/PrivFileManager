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
| Apache Commons Compress (`org.apache.commons:commons-compress`) | Apache License 2.0 |
| XZ for Java (`org.tukaani:xz`, commons-compressのtar.xz対応に使用) | パブリックドメイン |
| junrar (`com.github.junrar:junrar`) | UnRARライセンス(下記参照) |

GPL-3.0の下で本プロジェクト全体を配布する場合、これらApache-2.0/パブリックドメインの
ライブラリとの組み合わせは問題ありません(Apache-2.0はGPL-3.0とのリンクが許容されています)。
junrarについては下記の個別条項を参照してください。

## アーカイブ機能について

ZIP / TAR / TAR.GZ / TAR.BZ2 / TAR.XZ は **Apache Commons Compress**(Apache License 2.0)、
7Z(展開のみ)も同じくApache Commons Compressで対応しています。いずれも純Java実装のため、
Android NDKによるネイティブ(.so)クロスコンパイルは不要です。

RARについては **junrar**(RAR展開に特化した純Java実装、NDK不要)を組み込み、
**展開(読み取り)専用**として対応しています。

### junrar / UnRARライセンスについて(重要な制限)

junrarが依拠するUnRARライセンスは以下を許可・禁止しています:

- 許可: RAR書庫を読み取る(展開する)機能をソフトウェアに組み込むことは、
  無償・無制限に許可されている
- 禁止: UnRARのソースコードを使って **RAR互換の圧縮アーカイバを作成すること**

このため本プロジェクトは、RAR形式について**展開機能のみ**を実装しており、
「フォルダをRAR形式で圧縮する」機能は意図的に実装していません(圧縮形式の
選択肢にはRARを含めていません)。この制限は7-Zip/UnRAR/UnEgg検討時に
参照したライセンス一覧に基づく判断です。

以前の版では「NDKでのクロスコンパイルが必要なため組み込めない」としていましたが、
これはUnRARの**ネイティブC実装**についての記述であり、junrar(Javaへの移植版)には
該当しません。訂正の上、実際に組み込みました。

EGG形式(UnEgg / ESTsoft独自ライセンス)については、標準的なOSSライセンスではなく
個別の利用制限付きライセンスであり、上記のような純Java実装のOSSポートも
確認できなかったため、引き続き非対応としています。
