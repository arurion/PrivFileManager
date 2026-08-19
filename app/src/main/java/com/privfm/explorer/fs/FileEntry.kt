// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val permissions: String,
    val owner: String,
    val group: String,
    /** 一覧の先頭に挿入する「上のディレクトリへ」の疑似エントリかどうか */
    val isParentEntry: Boolean = false,
    /**
     * `ls -la`の日時欄をベストエフォートでエポックミリ秒に変換したもの(更新日時ソート用)。
     * 端末のlsの実装(toybox/busybox/GNU coreutils)によって日時のフォーマットが異なり、
     * 完全な信頼性は保証できないため、解釈できなかった場合は0とし、その場合は
     * 単に一覧の端に寄る形で緩やかに劣化させる(クラッシュはしない)。
     */
    val lastModifiedMillis: Long = 0L
)
