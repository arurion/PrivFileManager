// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.shell

import com.privfm.explorer.util.AppPreferences

enum class ShellMode { AUTO, SHIZUKU, ROOT, NORMAL }

/**
 * アプリ全体で使うシェルエンジンの選択ロジック。
 * AUTOの場合、Shizuku > Root > 通常 の優先順で利用可能なものを選択する。
 * 選択したモードは[AppPreferences]経由でアプリ再起動後も維持される。
 */
object ShellManager {

    var preferredMode: ShellMode
        get() = AppPreferences.shellMode
        set(value) { AppPreferences.shellMode = value }

    fun current(): ShellExecutor {
        return when (preferredMode) {
            ShellMode.SHIZUKU -> ShizukuShell
            ShellMode.ROOT -> RootShell
            ShellMode.NORMAL -> NormalShell
            ShellMode.AUTO -> when {
                ShizukuShell.isAvailable() -> ShizukuShell
                RootShell.isAvailable() -> RootShell
                else -> NormalShell
            }
        }
    }

    fun availableEngines(): List<ShellExecutor> =
        listOf(ShizukuShell, RootShell, NormalShell).filter { it.isAvailable() }

    fun hasPrivilegedAccess(): Boolean =
        ShizukuShell.isAvailable() || RootShell.isAvailable()
}
