# PrivFileManager

Shizuku / Root / 通常権限を切り替えて動作するAndroid向けファイルマネージャーです。
`android:debuggable="true"` の対象アプリに対しては `run-as` 経由で `/data/data/<package>` 以下の
閲覧・編集も行えます。GitHubリポジトリの clone/pull にも対応しています。

## 主な機能

- **通常のファイル操作**: 一覧表示 / 閲覧 / 編集 / 削除 / リネーム / コピー / chmod
- **Shizuku連携**: [Shizuku](https://shizuku.rikka.app/) 経由でADBまたはroot由来のshell権限を使用
- **Root連携**: `su` が利用可能な端末ではRoot権限での操作にも対応 (Shizuku未使用時のフォールバック)
- **debuggableアプリのデータ領域アクセス**:
  `PackageManager` で `FLAG_DEBUGGABLE` が立っているアプリを列挙し、
  選択したアプリに対して `run-as <package> ...` を発行して `/data/data/<package>` を操作します。
  これはAndroid OS自身が提供する開発者向け機能で、**debuggable=true のアプリにのみ**動作します
  (Android Studioのデバッガが使うのと同じ仕組みです)。
- **GitHubプロジェクトの読み込み**: JGit (純Java実装のgit) を使い、ネイティブgitバイナリなしで
  リポジトリをclone/pullし、そのままアプリ内で閲覧・編集できます。private repoの場合は
  Personal Access Tokenを入力してください。

## アーキテクチャ

```
shell/
  ShellExecutor     .. 実行エンジンの共通インターフェース
  ShizukuShell      .. Shizuku.newProcess (reflection) 経由でshell UIDのプロセスを実行
  RootShell         .. su -c 経由でroot権限のプロセスを実行
  NormalShell       .. アプリ自身のUIDでプロセスを実行
  ShellManager      .. AUTO/SHIZUKU/ROOT/NORMAL の優先順位・切り替えを管理

fs/
  PrivilegedFileSystem .. ls/cat/write/rm/mv/cp/chmod を上記シェル経由で実行
                           (Base64エンコードでバイナリ安全に転送)
  DebuggableAppHelper   .. debuggable=true のアプリ一覧をPackageManagerから取得

git/
  GitHubRepoLoader  .. JGitによるclone/pull

ui/
  MainActivity            .. メインのファイルブラウザ
  AppDataBrowserActivity  .. debuggableアプリのデータ領域ブラウザ
  TextEditorActivity      .. テキストエディタ
  GitCloneActivity        .. GitHubリポジトリ読み込み画面
  SettingsActivity        .. シェルエンジンの手動選択
```

## 使い方

1. 端末に [Shizuku](https://shizuku.rikka.app/) をインストールし、ADB or Root経由で起動しておく
   (Wireless debugging / `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` など)。
2. 本アプリを起動し、メニューから「Shizuku権限を要求」をタップして許可する。
3. Shizukuが無い/使わない場合、Root化端末であれば自動的にRootシェルにフォールバックします。
4. 「debuggableアプリのデータを開く」から対象アプリを選ぶと `/data/data/<package>` を閲覧できます
   (対象アプリが `debuggable=false` の場合、OSの制約により `run-as` は失敗します)。
5. 「GitHubプロジェクトを読み込む」からリポジトリURLを入力してclone。

## ビルド方法 (GitHub Actions)

`.github/workflows/build.yml` により、`main` ブランチへのpush / PR / 手動実行 (`workflow_dispatch`) で
自動的に debug / release APKがビルドされ、Actionsのartifactとしてアップロードされます。
Gradle Wrapperバイナリはリポジトリに含めず、`gradle/actions/setup-gradle` で最新のGradleを都度取得します。

### 署名ビルド

リポジトリの Settings > Secrets and variables > Actions に以下を登録すると、
release APKが自動的に署名されます(未登録の場合は unsigned のままビルドされます)。

| Secret名 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_20260801_BASE64` | keystoreファイルを `base64 -w0 your.keystore` した文字列 |
| `KEYSTORE_PASSWORD_20260801` | keystore/鍵パスワード(共通) |
| `KEY_ALIAS_20260801` | 鍵のエイリアス |

ローカルでビルドする場合:

```bash
gradle assembleDebug
```
(Android Studioで開いた場合はIDEが自動的にwrapperを生成します)

## 注意事項・免責

- `run-as` によるデータアクセスは **debuggable=true のアプリのみ** に対して機能する、Android OS標準の
  開発者向け機能です。debuggable=false の一般公開アプリのデータへは(root権限がない限り)アクセスできません。
- Root/Shizuku経由の操作はシステムやアプリの動作を破壊しうるため、自己責任で使用してください。
- 本プロジェクトは開発・デバッグ・学習目的のツールとして提供されます。第三者の端末・データに対して
  無断で使用しないでください。
