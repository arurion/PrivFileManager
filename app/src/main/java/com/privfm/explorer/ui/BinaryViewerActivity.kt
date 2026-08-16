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
 *
 * 巨大なファイル(動画・ディスクイメージ等)でもメモリ不足で落ちないよう、
 * 常に先頭[HEX_PREVIEW_BYTES]バイトのみを読み込む(全体をメモリに載せない)。
 */
class BinaryViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBinaryViewerBinding
    private var path: String = ""
    private var runAsPackage: String? = null

    private var currentOffset: Long = 0L
    private var fileTotalSize: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBinaryViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.binaryToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        path = intent.getStringExtra(TextEditorActivity.EXTRA_PATH) ?: ""
        runAsPackage = intent.getStringExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE)
        supportActionBar?.title = path.substringAfterLast('/')
        binding.binaryInfoView.text = getString(R.string.warn_binary_file)

        binding.btnPrevChunk.setOnClickListener {
            if (currentOffset > 0) loadHexChunk(maxOf(0L, currentOffset - HEX_PREVIEW_BYTES))
        }
        binding.btnNextChunk.setOnClickListener {
            if (currentOffset + HEX_PREVIEW_BYTES < fileTotalSize) loadHexChunk(currentOffset + HEX_PREVIEW_BYTES)
        }

        loadHexChunk(0L)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
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

    /**
     * 先頭固定8KBだけでなく、offsetを指定して任意の範囲を読み込めるようにしたページング対応版。
     * 「前へ/次へ」ボタンで、巨大ファイルでもメモリに全体を載せずに少しずつ閲覧できる。
     */
    private fun loadHexChunk(offset: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            if (fileTotalSize == 0L) {
                fileTotalSize = fs.fileSize(path).getOrDefault(0L)
            }
            val result = fs.readRange(path, offset, HEX_PREVIEW_BYTES)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    currentOffset = offset
                    binding.hexDumpView.text = FileTypeDetector.hexDump(it, baseOffset = offset)
                    updateInfoAndButtons()
                }.onFailure {
                    Toast.makeText(this@BinaryViewerActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateInfoAndButtons() {
        val baseInfo = getString(R.string.warn_binary_file)
        val end = minOf(currentOffset + HEX_PREVIEW_BYTES, fileTotalSize)
        binding.binaryInfoView.text = if (fileTotalSize > 0) {
            "$baseInfo (${formatSize(currentOffset)} 〜 ${formatSize(end)} / 全 ${formatSize(fileTotalSize)})"
        } else {
            baseInfo
        }
        binding.btnPrevChunk.isEnabled = currentOffset > 0
        binding.btnNextChunk.isEnabled = currentOffset + HEX_PREVIEW_BYTES < fileTotalSize
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return if (i < 0) "${bytes}B" else String.format("%.1f%s", value, units[i])
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

    companion object {
        private const val HEX_PREVIEW_BYTES = 8192
    }
}
