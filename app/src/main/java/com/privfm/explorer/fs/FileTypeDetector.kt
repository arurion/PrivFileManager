// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

import java.util.Locale

/**
 * 拡張子やバイト列の内容から、そのファイルをテキストエディタで安全に開いてよいかを判定する。
 * ファイルマネージャーとして、バイナリ(実行ファイル・画像・DBファイル等)をそのままテキスト扱いで
 * 開いて文字化け・破損保存させないためのガード。
 */
object FileTypeDetector {

    private val KNOWN_TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "xml", "yml", "yaml", "properties",
        "gradle", "kts", "kt", "java", "py", "sh", "bash", "conf", "cfg",
        "ini", "log", "csv", "tsv", "html", "htm", "css", "js", "ts",
        "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "sql", "gitignore",
        "toml", "env", "smali"
    )

    private val KNOWN_BINARY_EXTENSIONS = setOf(
        "apk", "so", "dex", "jar", "zip", "gz", "tar", "7z", "rar",
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico",
        "db", "sqlite", "sqlite3", "realm",
        "mp3", "mp4", "wav", "ogg", "webm", "mov", "avi",
        "ttf", "otf", "woff", "woff2",
        "pdf", "exe", "bin", "dat"
    )

    enum class Kind { TEXT, BINARY, UNKNOWN }

    fun kindByExtension(fileName: String): Kind {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext.isEmpty() -> Kind.UNKNOWN
            ext in KNOWN_TEXT_EXTENSIONS -> Kind.TEXT
            ext in KNOWN_BINARY_EXTENSIONS -> Kind.BINARY
            else -> Kind.UNKNOWN
        }
    }

    /**
     * バイト列を検査してバイナリらしいかどうかを判定する。
     * NULLバイトの存在、または制御文字(改行・タブ以外)の比率が高い場合にバイナリと判定する。
     */
    fun looksLikeBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.contains(0)) return true

        var suspicious = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val isPrintable = v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D
            val isLikelyUtf8Continuation = v >= 0x80
            if (!isPrintable && !isLikelyUtf8Continuation) {
                suspicious++
            }
        }
        return suspicious.toDouble() / bytes.size > 0.10
    }

    /** 先頭バイトを検査し、テキストとして安全に開けるかを総合判定する */
    fun shouldOpenAsText(fileName: String, headBytes: ByteArray): Boolean {
        return when (kindByExtension(fileName)) {
            Kind.TEXT -> true
            Kind.BINARY -> false
            Kind.UNKNOWN -> !looksLikeBinary(headBytes)
        }
    }

    /** 古典的な hexdump -C 風の整形 (16byte/行、アドレス + hex + ASCII) */
    fun hexDump(bytes: ByteArray, bytesPerLine: Int = 16): String {
        val sb = StringBuilder()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + bytesPerLine, bytes.size)
            sb.append(String.format(Locale.ROOT, "%08X  ", offset))
            for (i in offset until offset + bytesPerLine) {
                if (i < end) {
                    sb.append(String.format(Locale.ROOT, "%02X ", bytes[i]))
                } else {
                    sb.append("   ")
                }
                if (i - offset == 7) sb.append(" ")
            }
            sb.append(" |")
            for (i in offset until end) {
                val v = bytes[i].toInt() and 0xFF
                sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
            }
            sb.append("|\n")
            offset += bytesPerLine
        }
        return sb.toString()
    }
}
