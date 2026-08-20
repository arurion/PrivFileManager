# PrivFileManager

[![Build APK](https://github.com/arurion/PrivFileManager/actions/workflows/build.yml/badge.svg)](https://github.com/arurion/PrivFileManager/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/arurion/PrivFileManager)](https://github.com/arurion/PrivFileManager/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

Shizuku / Root / 通常権限を自動で切り替えて動作する、Android向けの高度なファイルマネージャーです。
一般的なファイルマネージャーの機能に加えて、開発者向けの特権アクセス(Shizuku・Root・`run-as`)に
対応しています。

Copyright (C) 2026 PrivFileManager Contributors
Licensed under the GNU General Public License v3.0 or later — see [LICENSE](LICENSE)

## 特徴

- **ファイル操作一式**: 一覧表示・複数選択・コピー/切り取り/貼り付け・削除・リネーム・新規作成・
  chmod・並び替え(名前/サイズ/種類)・検索・パンくずナビゲーション
- **種別ごとのファイルアイコン**: 画像/動画/音声/アーカイブ/PDF/APK/コード/文書ファイルを
  拡張子から判別し、それぞれ専用のベクターアイコンを表示(AOSP DocumentsUIのIconUtils/
  FileTypeMapと同様の「カテゴリ分類方式」を採用)
- **Shizuku連携**: [Shizuku](https://shizuku.rikka.app/) を使い、rootなしでADB相当のshell権限を利用
- **Root連携**: root化端末では `su` 経由でも動作(Shizuku未使用時のフォールバック)
- **debuggableアプリのデータ領域へのアクセス**: `android:debuggable="true"` の対象アプリに対して、
  `run-as` 経由で `/data/data/<package>` 以下を閲覧・編集できます(Android Studioのデバッガが
  使うのと同じ、OS標準の仕組みです)
- **外部アプリとの連携**: テキスト以外のファイルはAndroid標準の「開くアプリを選択」に委譲し、
  外部アプリで編集した内容を検知して元の場所へ書き戻せます
- **圧縮/展開**: 複数選択したファイル/フォルダをZIP/TAR系に圧縮、ZIP/TAR系/7Z/RARをその場で展開
  (バックグラウンドのフォアグラウンドサービス+通知で実行され、アプリを閉じても継続します。
  対応形式の詳細は上記「アーカイブの圧縮/展開」を参照)
- **debuggableアプリのブラウズも機能フル対応**: アプリのデータ領域を開くと、通常のストレージ
  ブラウズと全く同じ画面(複数選択・コピー/切り取り/貼り付け・圧縮/展開・検索・並び替え)が
  そのまま使えます
- **ダークモード対応**: システムのダーク/ライト設定に応じて配色を自動切り替え
- **Material You (Dynamic Color)**: Android 12+では端末の壁紙から生成される配色に対応
  (AOSPの標準アプリと同じ配色機構)。非対応端末では静的なフォールバックテーマを使用
- **アクセスできる範囲は全て閲覧可能**: 起動直後は内部ストレージを表示しつつ、実際のルート
  "/" まで含めてどこへでも移動できます(クイックアクセス・パス直接入力に対応)
- **パーミッション変更(chmod)**: 所有者/グループ/その他ごとにread/write/executeを
  チェックボックスで変更可能
- **実行中アプリ一覧からのジャンプ**: Shizuku/Root経由で`ps`を実行し、実行中プロセスの
  うちdebuggableなものを一覧表示、選ぶとそのデータ領域を直接開けます
  (AccessibilityServiceによる「今画面に表示中のアプリ」の自動検知は、
  `com.android.systemui`のステータスバー等のオーバーレイまで誤検知してしまうため採用していません)
- **アーカイブの圧縮/展開**: ZIP(JDK標準)に加えて、TAR/TAR.GZ/TAR.BZ2/TAR.XZ/7Z(展開のみ)を
  Apache Commons Compress(Apache-2.0)で、RAR展開をjunrar(UnRARライセンス、展開専用)で
  アプリ内蔵のライブラリとして直接処理します。純Java実装のためNDK不要で、端末側に
  `7z`/`unrar`等のツールが入っている必要はありません。RARは同ライセンスの条項に従い
  「展開専用」で、RAR形式での圧縮機能は実装していません(詳細は[NOTICE.md](./NOTICE.md))

## スクリーンショット

_準備中_

## 必要要件

- Android 8.0 (API 26) 以降
- 特権機能を使う場合: [Shizuku](https://shizuku.rikka.app/) アプリ、またはroot化済み端末
  (どちらも無くても、通常権限でのファイル閲覧・編集は可能です)

## インストール

[Releases](https://github.com/arurion/PrivFileManager/releases/latest) から最新の `PrivFileManager-release.apk` をダウンロードし、
端末にインストールしてください(提供元不明のアプリのインストールを許可する必要があります)。

## 使い方

1. アプリを起動し、必要に応じて権限を許可する
   - **Shizukuを使う場合**: 事前にShizukuアプリを起動しておき、本アプリのメニューから
     「Shizuku権限を要求」をタップして許可する
   - **Rootを使う場合**: root化済み端末であれば、メニューの「Root状態を確認」で自動検出される
   - **どちらも使わない場合**: 初回起動時に案内される「全ファイルアクセス」の許可のみでOK
2. ファイル一覧をタップして移動、長押しで開き方の選択・圧縮・リネーム・削除などの操作
3. ツールバーの「複数選択モード」でチェックボックスが表示され、まとめてコピー/切り取り/削除・圧縮が可能
4. debuggableなアプリのデータを見る場合は、メニューの「debuggableアプリのデータを開く」から対象アプリを選択

## 権限・プライバシーについて

- `QUERY_ALL_PACKAGES` は、端末にインストールされているdebuggableなアプリを一覧表示するために使用します
- `MANAGE_EXTERNAL_STORAGE` は、Shizuku/Rootを使わない場合の通常ストレージアクセスに使用します
- Shizuku/Root経由の操作は、対象アプリ・システムに対して強い権限で実行されます。
  信頼できる用途・対象にのみ使用してください
- 本アプリは通信を外部サーバーへ送信しません(GitHubリポジトリの取り込み機能を使った場合を除く)

## 開発者向け: ソースからビルドする

### GitHub Actions (推奨)

`.github/workflows/build.yml` により、`main` ブランチへのpush / PR / 手動実行 (`workflow_dispatch`) で
debug / release APKが自動ビルドされ、Actionsのartifactとしてダウンロードできます。
Gradle Wrapperのバイナリはリポジトリに含めず、`gradle/actions/setup-gradle` が都度取得します。

#### Releaseへの自動公開

タグをpushするか、GitHub UIでReleaseを作成すると、ビルドされたAPKが自動的にそのReleaseへ添付されます。

```bash
git tag v5.3.1
git push origin v5.3.1
```

通常のブランチpushやPull Requestではこのステップはスキップされ、Releaseページには影響しません。

#### 署名ビルド

リポジトリの Settings > Secrets and variables > Actions に以下を登録すると、release APKが
自動的に署名されます(未登録の場合はunsignedでビルドされます)。

| Secret名 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_20260801_BASE64` | keystoreファイルを `base64 -w0 your.keystore` した文字列 |
| `KEYSTORE_PASSWORD_20260801` | keystore/鍵パスワード(共通) |
| `KEY_ALIAS_20260801` | 鍵のエイリアス |

### ローカルビルド

Android Studioでプロジェクトを開けば、IDEが自動でセットアップします。CLIの場合:

```bash
gradle assembleDebug
```

### プロジェクト構成

```
shell/   実行エンジン (Shizuku / Root / 通常) の抽象化と切り替え
fs/      特権シェル経由のファイル操作、debuggableアプリの列挙
util/    外部アプリ連携 (FileProvider / 開くアプリを選択)
ui/      各画面 (ファイルブラウザ / アプリデータブラウザ / エディタ / 設定 など)
```

## 注意事項・免責

- `run-as` によるデータアクセスは、Android OSの制約により **`debuggable=true` のアプリにのみ** 機能します
- Root/Shizuku経由の操作はシステムやアプリの動作に影響を与える可能性があります。自己責任で使用してください
- 本アプリは開発・デバッグ・学習目的のツールです。第三者の端末やデータに対して無断で使用しないでください

## コントリビュート

Issue・Pull Requestを歓迎します。変更を加える際は、可能であれば動作確認の内容もあわせて記載してください。

## 更新履歴

過去の変更点は [`CHANGELOG.md`](./CHANGELOG.md) を参照してください。

## ライセンス

**GPL-3.0-or-later** です。全文は同梱の [`LICENSE`](./LICENSE) を参照してください。

一部のUI/UX設計は、AOSP DocumentsUI (Apache-2.0) や Amaze File Manager (GPL-3.0-or-later) の
一般的な機能構成を参考にしています。詳細は [`NOTICE.md`](./NOTICE.md) を参照してください。
ソースコードの逐語コピーは行っていません。
