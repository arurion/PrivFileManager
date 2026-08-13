// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.privfm.explorer.databinding.ActivityAppDataBinding
import com.privfm.explorer.fs.DebuggableAppHelper

/**
 * debuggable=true のアプリを一覧表示する画面。
 *
 * 選択すると [MainActivity] を「そのアプリの `/data/data/<package>` をルートとした
 * run-asブラウザ」として起動する。ファイル一覧・複数選択・コピー/切り取り/貼り付け・
 * 圧縮/展開・検索・並び替えなど、通常のストレージブラウズと全く同じ機能一式が
 * そのまま使える(以前はこの画面専用の簡易実装しかなく、機能差があった)。
 */
class AppDataBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDataBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appDataToolbar)
        binding.appListView.layoutManager = LinearLayoutManager(this)

        val apps = DebuggableAppHelper.listDebuggableApps(this)
        binding.appListView.adapter = AppAdapter(apps) { app ->
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ROOT_PATH, app.dataDir)
                putExtra(MainActivity.EXTRA_RUN_AS_PACKAGE, app.packageName)
                putExtra(MainActivity.EXTRA_TITLE, app.label)
            }
            startActivity(intent)
        }
    }
}
