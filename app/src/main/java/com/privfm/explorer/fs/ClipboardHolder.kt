// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

/**
 * 複数選択したファイル/フォルダの「コピー」「切り取り」→「貼り付け」を実現するための
 * アプリ内クリップボード。プロセス生存中のみ有効な単純なシングルトン保持で十分。
 */
object ClipboardHolder {
    enum class Mode { COPY, CUT }

    data class Entry(val path: String, val name: String, val isDirectory: Boolean)

    var mode: Mode = Mode.COPY
        private set

    var items: List<Entry> = emptyList()
        private set

    fun set(entries: List<Entry>, mode: Mode) {
        this.items = entries
        this.mode = mode
    }

    fun clear() {
        items = emptyList()
    }

    fun isEmpty(): Boolean = items.isEmpty()
}
