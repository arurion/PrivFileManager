// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.shell

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Shizuku(ADBまたはrootにより起動されたシステムレベルのブローカプロセス)経由のシェル実行。
 *
 * Shizukuの公開APIは主にAIDL UserServiceの利用を推奨しているが、
 * 汎用シェルコマンド実行にはShizuku内部の `newProcess` (shell UIDでのプロセス生成) を
 * リフレクション経由で呼び出す方式を採用する。これは公開されているAPI JARに
 * 実体が含まれているメソッドであり、多くのShizuku対応アプリで採用されている手法。
 *
 * 将来的な互換性を重視する場合は AIDL UserService 方式への移行を検討すること。
 */
object ShizukuShell : ShellExecutor {

    override fun label(): String = "Shizuku"

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /** 権限要求。結果は Shizuku.OnRequestPermissionResultListener で受け取る。 */
    fun requestPermission(requestCode: Int) {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.isPreV11()) {
            // 公式ガイド: pre-v11は非サポート
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            // ユーザーが「今後表示しない」を選択済み。設定から手動許可してもらう必要がある。
            return
        }
        Shizuku.requestPermission(requestCode)
    }

    override fun exec(command: String, stdinBase64: String?): ShellResult {
        return try {
            val process = newShizukuProcess(arrayOf("sh", "-c", command))
                ?: return ShellResult(-1, "", "Shizukuプロセスの生成に失敗しました")

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
            ShellResult(-1, "", e.message ?: "Shizuku実行エラー")
        }
    }

    private var cachedMethod: Method? = null

    /**
     * Shizuku.newProcess(String[] cmd, String[] env, String dir) を
     * リフレクションで呼び出し、shell(またはroot)UIDのProcessを取得する。
     */
    private fun newShizukuProcess(cmd: Array<String>): Process? {
        val method = cachedMethod ?: Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            .also { it.isAccessible = true; cachedMethod = it }

        return method.invoke(null, cmd, null, null) as? Process
    }
}
