// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

import android.util.Base64
import com.privfm.explorer.shell.ShellExecutor

/**
 * ShellExecutor(Shizuku / Root / 通常)を介して任意パスのファイル操作を行うクラス。
 *
 * runAsPackage を指定すると、対象パッケージが android:debuggable="true" である場合に限り
 * `run-as <package> <cmd>` を経由して実行される。これはAndroid自身が提供する開発者機能であり、
 * デバッグ可能アプリのみに対して許可される標準的な仕組み(Android Studioのデバッグ機能と同じ経路)。
 */
class PrivilegedFileSystem(
    private val shell: ShellExecutor,
    private val runAsPackage: String? = null
) {

    private fun wrap(cmd: String): String {
        return if (runAsPackage != null) {
            "run-as $runAsPackage sh -c '${cmd.replace("'", "'\\''")}'"
        } else {
            cmd
        }
    }

    /** 対象パッケージが debuggable かどうか(run-asが通るか)を確認する */
    fun isTargetDebuggable(): Boolean {
        if (runAsPackage == null) return true
        val result = shell.exec("run-as $runAsPackage id")
        return result.isSuccess
    }

    fun listDirectory(path: String): Result<List<FileEntry>> {
        val result = shell.exec(wrap("ls -la \"$path\""))
        if (!result.isSuccess) {
            return Result.failure(IllegalStateException(result.stderr.ifBlank { "一覧取得に失敗しました (exit=${result.exitCode})" }))
        }
        val entries = result.stdout.lineSequence()
            .mapNotNull { parseLsLine(it, path) }
            .filter { it.name != "." && it.name != ".." }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
        return Result.success(entries)
    }

    private val lsRegex = Regex(
        "^([dl\\-])([rwxsStT\\-]{9})\\s+\\d+\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\S+\\s+\\S+)\\s+(.+)$"
    )

    private fun parseLsLine(line: String, basePath: String): FileEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("total")) return null
        val m = lsRegex.find(trimmed) ?: return null
        val g = m.groupValues
        val type = g[1]
        val perms = g[2]
        val owner = g[3]
        val group = g[4]
        val size = g[5]
        val rawName = g[7]
        val isDir = type == "d"
        val isLink = type == "l"
        val name = if (isLink && rawName.contains(" -> ")) rawName.substringBefore(" -> ") else rawName
        val base = if (basePath.endsWith("/")) basePath else "$basePath/"
        return FileEntry(
            name = name,
            path = base + name,
            isDirectory = isDir,
            isSymlink = isLink,
            sizeBytes = size.toLongOrNull() ?: 0L,
            permissions = type + perms,
            owner = owner,
            group = group
        )
    }

    /** ファイルサイズをバイト単位で取得する(大容量ファイルを丸ごとメモリに載せる前の事前チェック用) */
    fun fileSize(path: String): Result<Long> {
        val result = shell.exec(wrap("wc -c < \"$path\""))
        if (!result.isSuccess) {
            return Result.failure(IllegalStateException(result.stderr.ifBlank { "サイズ取得に失敗しました" }))
        }
        val size = result.stdout.trim().toLongOrNull()
            ?: return Result.failure(IllegalStateException("サイズを解釈できませんでした: ${result.stdout}"))
        return Result.success(size)
    }

    /** ファイル種別判定などのため、先頭バイトのみを効率的に読み取る */
    fun peekFile(path: String, maxBytes: Int = 4096): Result<ByteArray> {
        val result = shell.exec(wrap("head -c $maxBytes \"$path\" | base64"))
        if (!result.isSuccess) {
            return Result.failure(IllegalStateException(result.stderr.ifBlank { "読み込みに失敗しました" }))
        }
        return try {
            Result.success(Base64.decode(result.stdout.replace("\n", ""), Base64.DEFAULT))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * ファイルの任意の範囲だけをバイト単位で読み取る(Hexビューアのページング用)。
     * ファイル全体をメモリに載せずに、指定範囲のみをシェル側で切り出してから転送する。
     */
    fun readRange(path: String, offset: Long, length: Int): Result<ByteArray> {
        val cmd = "tail -c +${offset + 1} \"$path\" | head -c $length | base64"
        val result = shell.exec(wrap(cmd))
        if (!result.isSuccess) {
            return Result.failure(IllegalStateException(result.stderr.ifBlank { "読み込みに失敗しました" }))
        }
        return try {
            Result.success(Base64.decode(result.stdout.replace("\n", ""), Base64.DEFAULT))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** テキスト/バイナリ問わずファイル内容を読み込む(Base64経由で安全に転送) */
    fun readFile(path: String): Result<ByteArray> {
        val result = shell.exec(wrap("base64 \"$path\""))
        if (!result.isSuccess) {
            return Result.failure(IllegalStateException(result.stderr.ifBlank { "読み込みに失敗しました" }))
        }
        return try {
            Result.success(Base64.decode(result.stdout.replace("\n", ""), Base64.DEFAULT))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun readTextFile(path: String, charset: Charset = Charsets.UTF_8): Result<String> =
        readFile(path).map { String(it, charset) }

    /** ファイルへ書き込む(上書き)。Base64エンコードして標準入力経由で転送する。 */
    fun writeFile(path: String, content: ByteArray): Result<Unit> {
        val b64 = Base64.encodeToString(content, Base64.NO_WRAP)
        val cmd = wrap("sh -c 'base64 -d > \"$path\"'")
        val result = shell.exec(cmd, stdinBase64 = b64)
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.stderr.ifBlank { "書き込みに失敗しました" }))
    }

    fun writeTextFile(path: String, text: String, charset: Charset = Charsets.UTF_8): Result<Unit> =
        writeFile(path, text.toByteArray(charset))

    fun delete(path: String, recursive: Boolean = false): Result<Unit> {
        val flag = if (recursive) "-rf" else "-f"
        val result = shell.exec(wrap("rm $flag \"$path\""))
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.stderr.ifBlank { "削除に失敗しました" }))
    }

    fun mkdir(path: String): Result<Unit> {
        val result = shell.exec(wrap("mkdir -p \"$path\""))
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.stderr.ifBlank { "ディレクトリ作成に失敗しました" }))
    }

    fun createEmptyFile(path: String): Result<Unit> {
        val result = shell.exec(wrap("touch \"$path\""))
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.stderr.ifBlank { "ファイル作成に失敗しました" }))
    }

    fun rename(oldPath: String, newPath: String): Result<Unit> {
        val result = shell.exec(wrap("mv \"$oldPath\" \"$newPath\""))
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.stderr.ifBlank { "リネームに失敗しました" }))
    }

    fun copy(srcPath: String, dstPath: String): Result<Unit> {
        val result = shell.exec(wrap("cp -r \"$srcPath\" \"$dstPath\""))
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.stderr.ifBlank { "コピーに失敗しました" }))
    }

    fun chmod(path: String, mode: String): Result<Unit> {
        val result = shell.exec(wrap("chmod $mode \"$path\""))
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.stderr.ifBlank { "権限変更に失敗しました" }))
    }
}

private typealias Charset = java.nio.charset.Charset
