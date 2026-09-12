// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Termux(https://github.com/termux/termux-app, GPL-3.0-or-later)との連携。
 *
 * Termuxは0.95以降、他アプリからのコマンド実行を`RunCommandService`への
 * Intent送信(`com.termux.RUN_COMMAND`)で受け付ける公式APIを提供している。
 * 本実装はTermuxの実際のWikiドキュメント(termux/termux-app wiki:
 * "RUN_COMMAND Intent")で示されているコンポーネント名・Extraキーに基づく。
 *
 * 利用にはあらかじめ以下がユーザー側で必要(アプリからは自動化できない):
 *  - Termuxがインストールされていること
 *  - Termux起動後、`~/.termux/termux.properties` に `allow-external-apps=true`
 *    を追記して `termux-reload-settings` していること
 *  - 本アプリに `com.termux.permission.RUN_COMMAND` 権限が付与されていること
 *    (Android設定画面から、通常のランタイム権限と同様に許可する)
 */
object TermuxIntegration {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    /** ログイン後の標準的なシェルバイナリのパス(Termuxの既定インストール構成) */
    private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasRunCommandPermission(context: Context): Boolean {
        return context.packageManager.checkPermission(PERMISSION_RUN_COMMAND, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 指定ディレクトリで対話的なシェルセッションを開く(バックグラウンド実行ではなく、
     * Termuxのフォアグラウンドにターミナル画面が表示される)。
     *
     * 特権シェル(Shizuku/Root/run-as)経由でのみアクセスできる領域は、Termux自身が
     * 一般アプリの権限で動くプロセスである以上、そのままでは`cd`できない
     * (Termuxをroot化して使っている場合を除く)。この制約はアプリ側では解消できないため、
     * 対象ディレクトリへ`cd`できるかどうかはTermux側のターミナル出力に委ねる。
     */
    fun openTermuxHere(context: Context, path: String) {
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH_PATH)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", path)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        }
        context.startService(intent)
    }

    /** Termux未インストール時、ストアではなく公式サイト(GitHub Releases)へ誘導する */
    fun openTermuxDownloadPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases"))
        context.startActivity(intent)
    }
}
