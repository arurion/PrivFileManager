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
    val group: String
)
