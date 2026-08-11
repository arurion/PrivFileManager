package com.privfm.explorer.shell

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 非特権(アプリ自身のUID)でのシェル実行。
 * 自アプリのサンドボックス内、または一般に読み取り可能な領域のみアクセス可能。
 */
object NormalShell : ShellExecutor {

    override fun isAvailable(): Boolean = true

    override fun label(): String = "通常権限"

    override fun exec(command: String, stdinBase64: String?): ShellResult {
        return runProcess(arrayOf("sh", "-c", command), stdinBase64)
    }

    internal fun runProcess(cmd: Array<String>, stdinBase64: String?): ShellResult {
        return try {
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(false)
                .start()

            if (stdinBase64 != null) {
                process.outputStream.use { it.write(stdinBase64.toByteArray(Charsets.US_ASCII)) }
            } else {
                process.outputStream.close()
            }

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()
            ShellResult(exit, stdout, stderr)
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "unknown error")
        }
    }
}
