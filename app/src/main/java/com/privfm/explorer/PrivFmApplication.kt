// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer

import android.app.Application
import com.google.android.material.color.DynamicColors
import rikka.shizuku.Shizuku

class PrivFmApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Android 12+ では端末の壁紙から生成されるMaterial You(Dynamic Color)を最優先で適用する。
        // これはAOSP自身(設定アプリ・ファイルアプリ等)が使う標準の配色機構であり、
        // 独自パレットより先にこちらを使うことで「AOSPに近い配色」を実現する。
        // 非対応端末(Android 11以前)では自動的に静的テーマ(values / values-night)にフォールバックする。
        DynamicColors.applyToActivitiesIfAvailable(this)

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
