// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privfm.explorer.R
import com.privfm.explorer.databinding.ActivityBinaryViewerBinding
import com.privfm.explorer.fs.FileTypeDetector
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.shell.ShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * バイナリと判定されたファイルを、文字コード破損の危険なしに閲覧するための画面。
 * 誤ってテキストエディタで開いて保存し、バイナリを壊してしまう事故を防ぐ。
 */
class BinaryViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBinaryViewerBinding
    private var path: String = ""
    private var runAsPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBinaryViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.binaryToolbar)

        path = intent.getStringExtra(TextEditorActivity.EXTRA_PATH) ?: ""
        runAsPackage = intent.getStringExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE)
        supportActionBar?.title = path.substringAfterLast('/')
        binding.binaryInfoView.text = getString(R.string.warn_binary_file)

        loadHexPreview()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_binary_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_force_open_text) {
            confirmForceOpenAsText()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadHexPreview() {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val result = fs.peekFile(path, maxBytes = 8192)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    binding.hexDumpView.text = FileTypeDetector.hexDump(it)
                }.onFailure {
                    Toast.makeText(this@BinaryViewerActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmForceOpenAsText() {
        MaterialAlertDialogBuilder(this)
            .setTitle("注意")
            .setMessage("バイナリファイルをテキストとして開き、そのまま保存すると内容が破損する可能性があります。続行しますか?")
            .setPositiveButton("開く") { _, _ ->
                val intent = Intent(this, TextEditorActivity::class.java).apply {
                    putExtra(TextEditorActivity.EXTRA_PATH, path)
                    putExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE, runAsPackage)
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
