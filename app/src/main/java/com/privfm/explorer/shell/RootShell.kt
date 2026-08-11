package com.privfm.explorer.shell

/**
 * root権限(su)経由のシェル実行。
 * デバイスがroot化されておりsuバイナリからのアクセス許可が得られている場合のみ利用可能。
 */
object RootShell : ShellExecutor {

    @Volatile
    private var availabilityChecked = false

    @Volatile
    private var available = false

    override fun label(): String = "Root"

    override fun isAvailable(): Boolean {
        if (!availabilityChecked) {
            val result = NormalShell.runProcess(arrayOf("su", "-c", "id"), null)
            available = result.isSuccess && result.stdout.contains("uid=0")
            availabilityChecked = true
        }
        return available
    }

    /** su可用性判定のキャッシュを破棄し、再チェックさせる */
    fun invalidate() {
        availabilityChecked = false
    }

    override fun exec(command: String, stdinBase64: String?): ShellResult {
        return NormalShell.runProcess(arrayOf("su", "-c", command), stdinBase64)
    }
}
