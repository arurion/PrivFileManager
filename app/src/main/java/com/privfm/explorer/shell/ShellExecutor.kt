// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.shell

/**
 * シェルコマンド実行結果
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * 特権度の異なる実行エンジン(通常 / Shizuku / Root)の共通インターフェース。
 * 実装は同期的だが、呼び出し側は必ずバックグラウンドスレッド(coroutine等)から呼ぶこと。
 */
interface ShellExecutor {

    /** この実行エンジンが現在利用可能か (権限付与済み・バインダー生存中など) */
    fun isAvailable(): Boolean

    /**
     * コマンドを実行する。
     * @param command 実行するシェルコマンド文字列 (sh -c 経由)
     * @param stdinBase64 標準入力へ渡すデータ (Base64エンコード済み文字列)。バイナリ書き込み等に使用。
     */
    fun exec(command: String, stdinBase64: String? = null): ShellResult

    /** 表示用の名称 */
    fun label(): String
}
