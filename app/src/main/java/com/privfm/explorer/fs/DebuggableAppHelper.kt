// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class DebuggableApp(
    val packageName: String,
    val label: String,
    val dataDir: String
)

/**
 * 端末にインストールされている android:debuggable="true" のアプリを列挙する。
 * PackageManagerのAPIのみで完結し、特権シェルは不要。
 * (Android 11+ではマニフェストの <queries> またはQUERY_ALL_PACKAGES権限が
 *  無いと他アプリの列挙が制限される点に注意。エミュレータ/開発端末での利用を想定。)
 */
object DebuggableAppHelper {

    fun listDebuggableApps(context: Context): List<DebuggableApp> {
        val pm = context.packageManager
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            emptyList()
        }
        return apps
            .filter { (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
            .map {
                DebuggableApp(
                    packageName = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    dataDir = it.dataDir ?: "/data/data/${it.packageName}"
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
