// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.shell

import android.content.Context
import com.privfm.explorer.util.AppPreferences

enum class ShellMode { AUTO, SHIZUKU, ROOT, ADB, NORMAL }

/**
 * アプリ全体で使うシェルエンジンの選択ロジック。
 * AUTOの場合、Shizuku > Root > ADB(ワイヤレスデバッグ) > 通常 の優先順で
 * 利用可能なものを選択する。選択したモードは[AppPreferences]経由でアプリ再起動後も維持される。
 */
object ShellManager {

    /**
     * [AdbShell]がADB接続(libadb-androidの`AdbConnectionManager`)を初期化するために
     * Contextを必要とするため、アプリ起動時に[com.privfm.explorer.PrivFmApplication]から
     * アプリケーションContextを設定してもらう。
     */
    @Volatile
    var applicationContext: Context? = null
        private set

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    var preferredMode: ShellMode
        get() = AppPreferences.shellMode
        set(value) { AppPreferences.shellMode = value }

    fun current(): ShellExecutor {
        return when (preferredMode) {
            ShellMode.SHIZUKU -> ShizukuShell
            ShellMode.ROOT -> RootShell
            ShellMode.ADB -> AdbShell
            ShellMode.NORMAL -> NormalShell
            ShellMode.AUTO -> when {
                ShizukuShell.isAvailable() -> ShizukuShell
                RootShell.isAvailable() -> RootShell
                AdbShell.isAvailable() -> AdbShell
                else -> NormalShell
            }
        }
    }

    fun availableEngines(): List<ShellExecutor> =
        listOf(ShizukuShell, RootShell, AdbShell, NormalShell).filter { it.isAvailable() }

    fun hasPrivilegedAccess(): Boolean =
        ShizukuShell.isAvailable() || RootShell.isAvailable() || AdbShell.isAvailable()
}
