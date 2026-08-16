// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.util

import android.content.Context
import android.content.SharedPreferences
import com.privfm.explorer.fs.SortMode
import com.privfm.explorer.shell.ShellMode

/**
 * アプリの設定値をSharedPreferencesへ永続化する。
 *
 * 以前は [com.privfm.explorer.shell.ShellManager] の優先モードなどが単なる
 * メモリ上の変数で、アプリを再起動すると毎回「自動判定」に戻ってしまっていた。
 * 一般的なファイルマネージャー(Amaze File Manager等)と同様、ユーザーが選んだ
 * 設定は再起動後も維持されるべきなので、ここに集約して永続化する。
 */
object AppPreferences {

    private const val PREFS_NAME = "privfm_prefs"
    private const val KEY_SHELL_MODE = "shell_mode"
    private const val KEY_SHOW_HIDDEN = "show_hidden_files"
    private const val KEY_CONFIRM_DELETE = "confirm_before_delete"
    private const val KEY_SORT_MODE = "sort_mode"
    private const val KEY_SORT_ASCENDING = "sort_ascending"
    private const val KEY_BOTTOM_SHEET_MENUS = "bottom_sheet_menus"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initialized = true
    }

    var shellMode: ShellMode
        get() = ShellMode.entries.find { it.name == prefs.getString(KEY_SHELL_MODE, null) } ?: ShellMode.AUTO
        set(value) = prefs.edit().putString(KEY_SHELL_MODE, value.name).apply()

    /** ドットファイル(隠しファイル)を一覧に表示するかどうか。デフォルトは非表示 */
    var showHiddenFiles: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()

    /** 削除前に確認ダイアログを出すかどうか。デフォルトはON(誤操作防止) */
    var confirmBeforeDelete: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_DELETE, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_DELETE, value).apply()

    var sortMode: SortMode
        get() = SortMode.entries.find { it.name == prefs.getString(KEY_SORT_MODE, null) } ?: SortMode.NAME
        set(value) = prefs.edit().putString(KEY_SORT_MODE, value.name).apply()

    var sortAscending: Boolean
        get() = prefs.getBoolean(KEY_SORT_ASCENDING, true)
        set(value) = prefs.edit().putBoolean(KEY_SORT_ASCENDING, value).apply()

    /**
     * ファイル操作メニューをボトムシート(下からせり出す形)で出すか、
     * 従来のダイアログ(画面中央)で出すか。どちらが好みかは人によるため、
     * 開発者の好みを一方的に押し付けず設定項目として選べるようにする。
     * デフォルトはFossify File Manager/Files by Google等、近年の主要な
     * ファイルマネージャーで一般的なボトムシートに合わせている。
     */
    var useBottomSheetMenus: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_SHEET_MENUS, true)
        set(value) = prefs.edit().putBoolean(KEY_BOTTOM_SHEET_MENUS, value).apply()
}
