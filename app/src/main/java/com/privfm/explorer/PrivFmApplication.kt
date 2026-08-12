// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer

import android.app.Application
import rikka.shizuku.Shizuku

class PrivFmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Shizukuのバインダーが (再)接続された際のログ用リスナー。
        // 実際の権限リクエスト結果は各Activity側のリスナーで処理する。
        Shizuku.addBinderReceivedListenerSticky {
            // no-op: バインダー生存確認のみ。UI側は isAvailable() で都度判定する。
        }
        Shizuku.addBinderDeadListener {
            // Shizukuサービスが停止した場合の後始末が必要であればここに実装する。
        }
    }
}
