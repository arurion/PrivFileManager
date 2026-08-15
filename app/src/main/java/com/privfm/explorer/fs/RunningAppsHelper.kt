// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

import com.privfm.explorer.shell.ShellExecutor

data class RunningApp(
    val packageName: String,
    val pid: Int
)

/**
 * `ps -A` (toybox/AOSP標準のps) を使い、現在実行中のプロセスの一覧を取得する。
 *
 * AccessibilityServiceで「今画面に表示されているアプリ」を検知する方式は、
 * ステータスバーや通知シェードなど com.android.systemui のオーバーレイウィンドウまで
 * 拾ってしまい実用に耐えなかったため、この方式に置き換えている。
 * psはUIの状態に関係なく素直にプロセス一覧を返すため、systemuiの常駐プロセスは
 * 出てくるが「今表示されているアプリ」と誤認することはない(一覧から選ぶ形式なので)。
 *
 * 通常権限のシェルでは他アプリのプロセスは基本的に見えない(Android 7+のhidepid制限)。
 * Shizuku(shell UID)またはRootが必要。
 */
object RunningAppsHelper {

    fun listRunningApps(shell: ShellExecutor): List<RunningApp> {
        // NAME列(プロセス名 ≒ パッケージ名)とPIDを取得する。
        // 端末/toyboxのバージョンにより列の並びが異なるため、まずtoybox形式を試し、
        // 失敗したら素の "ps" にフォールバックする。
        val result = shell.exec("ps -A -o PID,NAME")
        val lines = if (result.isSuccess) result.stdout.lineSequence() else shell.exec("ps").stdout.lineSequence()

        val apps = mutableListOf<RunningApp>()
        val seen = mutableSetOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("PID") || trimmed.startsWith("USER")) continue
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.isEmpty()) continue

            // "PID NAME" 形式と、素のpsの "USER PID PPID VSIZE RSS WCHAN ADDR S NAME" 形式の両方に対応
            val pid = tokens.getOrNull(0)?.toIntOrNull() ?: tokens.getOrNull(1)?.toIntOrNull() ?: continue
            val name = tokens.last()

            // パッケージ名らしきもの(ドットを含み、プロセスサフィックス ":xxx" を除去)のみ採用
            val pkg = name.substringBefore(':')
            if (!pkg.contains('.')) continue
            if (!seen.add(pkg)) continue

            apps.add(RunningApp(pkg, pid))
        }
        return apps
    }
}
