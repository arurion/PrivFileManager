// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 「今どのアプリが画面に表示されているか」を検知するためだけのAccessibilityService。
 *
 * 重要な設計方針(意図的な制限):
 * - TYPE_WINDOW_STATE_CHANGED イベントの `packageName` のみを読み取る
 * - ノードツリーの走査(findAccessibilityNodeInfosByText等)は一切行わない
 * - 画面上のテキスト・入力内容・座標等、パッケージ名以外の情報は取得しない
 *
 * これは「debuggableなアプリのデータへワンタップで移動する」という
 * ファイルマネージャーとして正当な用途に絞るためであり、他アプリの画面内容を
 * 収集する汎用スパイウェア的な機能にしないための明確な線引きである。
 */
class ForegroundAppAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // 自アプリ自身は無視

        lastForegroundPackage = pkg
        sendBroadcast(
            Intent(BROADCAST_ACTION).apply {
                setPackage(packageName)
                putExtra(EXTRA_PACKAGE_NAME, pkg)
            }
        )
    }

    override fun onInterrupt() { /* 何もしない */ }

    companion object {
        const val BROADCAST_ACTION = "com.privfm.explorer.FOREGROUND_APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        /** 直近に検知したフォアグラウンドアプリのパッケージ名(プロセス生存中のみ有効な簡易キャッシュ) */
        var lastForegroundPackage: String? = null
            private set
    }
}
