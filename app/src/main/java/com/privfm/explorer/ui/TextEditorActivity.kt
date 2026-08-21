// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privfm.explorer.R
import com.privfm.explorer.databinding.ActivityEditorBinding
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.shell.ShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var path: String = ""
    private var runAsPackage: String? = null

    /** trueの場合、大きすぎるファイルの先頭のみを表示するプレビューモード(保存は無効化) */
    private var isPreviewOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.editorToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        path = intent.getStringExtra(EXTRA_PATH) ?: ""
        runAsPackage = intent.getStringExtra(EXTRA_RUN_AS_PACKAGE)
        supportActionBar?.title = path.substringAfterLast('/')

        loadFile()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_save) {
            if (isPreviewOnly) {
                Toast.makeText(this, "プレビューのみのため保存できません(ファイルが大きすぎます)", Toast.LENGTH_LONG).show()
            } else {
                saveFile()
            }
            return true
        }
        if (item.itemId == R.id.action_find) {
            toggleFindBar()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---- 検索 ----

    private var findMatches: List<Int> = emptyList()
    private var findCurrentIndex: Int = -1

    private fun toggleFindBar() {
        val show = binding.findBar.visibility != android.view.View.VISIBLE
        binding.findBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        if (show) {
            binding.findQueryInput.requestFocus()
            binding.findQueryInput.setOnEditorActionListener { _, _, _ -> findNext(); true }
            binding.findQueryInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { runSearch(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            binding.btnFindNext.setOnClickListener { findNext() }
            binding.btnFindPrev.setOnClickListener { findPrev() }
            binding.btnFindClose.setOnClickListener {
                binding.findBar.visibility = android.view.View.GONE
                binding.editorText.requestFocus()
            }
        } else {
            findMatches = emptyList()
            findCurrentIndex = -1
        }
    }

    /**
     * 単純な文字列検索(正規表現や大文字小文字オプションなどは持たないが、
     * 「大きなファイルをプレビューだけで検索したい」という最低限の需要は満たす)。
     * すべての出現位置を一度に洗い出し、次へ/前へで巡回する。
     */
    private fun runSearch(query: String) {
        val fullText = binding.editorText.text?.toString().orEmpty()
        findMatches = if (query.isEmpty()) emptyList() else {
            val matches = mutableListOf<Int>()
            var idx = fullText.indexOf(query, 0, ignoreCase = true)
            while (idx >= 0) {
                matches.add(idx)
                idx = fullText.indexOf(query, idx + 1, ignoreCase = true)
            }
            matches
        }
        findCurrentIndex = if (findMatches.isEmpty()) -1 else 0
        updateFindCountLabel()
        if (findCurrentIndex >= 0) highlightCurrentMatch(query.length)
    }

    private fun findNext() {
        if (findMatches.isEmpty()) return
        findCurrentIndex = (findCurrentIndex + 1) % findMatches.size
        updateFindCountLabel()
        highlightCurrentMatch(binding.findQueryInput.text?.length ?: 0)
    }

    private fun findPrev() {
        if (findMatches.isEmpty()) return
        findCurrentIndex = (findCurrentIndex - 1 + findMatches.size) % findMatches.size
        updateFindCountLabel()
        highlightCurrentMatch(binding.findQueryInput.text?.length ?: 0)
    }

    private fun updateFindCountLabel() {
        binding.findCountView.text = if (findMatches.isEmpty()) "0件"
        else "${findCurrentIndex + 1}/${findMatches.size}"
    }

    private fun highlightCurrentMatch(queryLength: Int) {
        val start = findMatches.getOrNull(findCurrentIndex) ?: return
        val end = (start + queryLength).coerceAtMost(binding.editorText.text?.length ?: start)
        binding.editorText.requestFocus()
        binding.editorText.setSelection(start, end)
        // 選択範囲が画面内に見えるようスクロール
        val layout = binding.editorText.layout
        if (layout != null) {
            val line = layout.getLineForOffset(start)
            val y = layout.getLineTop(line)
            (binding.editorText.parent as? android.widget.ScrollView)?.smoothScrollTo(0, y)
        }
    }

    /**
     * 巨大なファイルをそのままEditTextへ丸ごと読み込むと、メモリ不足でアプリが
     * 落ちる問題があった(shell経由でbase64全文をKotlin文字列として保持するため、
     * 数百MB〜のファイルで容易にOutOfMemoryが発生していた)。加えて、たとえOOMを
     * 免れても、Android標準のEditText/TextViewは大量の文字列を保持すると
     * (Spannable/レイアウト計算のコストにより)著しく重くなりフリーズしたように
     * 見える問題がある。
     *
     * Amaze File Managerの実ソース(ReadTextFileCallable.java)を確認したところ、
     * `MAX_FILE_SIZE_CHARS = 50 * 1024`(50KB)を超える分は最初から読み込まず、
     * 超過を検知した場合は保存不可ではなく**編集そのものを禁止(読み取り専用化)**し、
     * 常時表示の警告バナーを出す設計だった。過去のOOMクラッシュ報告
     * (Issue #2461)を受けての対応であり、同じ方針を採用した。
     */
    private fun loadFile() {
        binding.editorText.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val sizeResult = fs.fileSize(path)
            val size = sizeResult.getOrNull()

            if (size != null && size > MAX_FULL_LOAD_BYTES) {
                val preview = fs.peekFile(path, maxBytes = MAX_FULL_LOAD_BYTES.toInt())
                withContext(Dispatchers.Main) {
                    isPreviewOnly = true
                    preview.onSuccess { bytes ->
                        setPreviewText(bytes, size)
                        showPreviewOnlyNotice(size)
                    }.onFailure {
                        Toast.makeText(this@TextEditorActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
                return@launch
            }

            val result = fs.readTextFile(path)
            withContext(Dispatchers.Main) {
                try {
                    result.onSuccess { binding.editorText.setText(it); binding.editorText.isEnabled = true }
                        .onFailure { Toast.makeText(this@TextEditorActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show() }
                } catch (e: OutOfMemoryError) {
                    // 上記のサイズ事前チェックをすり抜けた場合の最終防御線
                    // (Amazeも同様にOutOfMemoryErrorを明示的にcatchしている)。
                    Toast.makeText(this@TextEditorActivity, "メモリ不足のため開けませんでした(ファイルが大きすぎます)", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    /** プレビューモード: 編集は一切できない読み取り専用として表示する(保存操作自体をブロックするだけでは不十分なため) */
    private fun setPreviewText(bytes: ByteArray, fullSize: Long) {
        val text = String(bytes, Charsets.UTF_8)
        binding.editorText.setText(
            "$text\n\n----- (${formatSize(fullSize)} 中 先頭 ${formatSize(MAX_FULL_LOAD_BYTES)} のみ表示・読み取り専用) -----"
        )
        binding.editorText.isEnabled = false
        binding.editorText.keyListener = null
    }

    private fun showPreviewOnlyNotice(fullSize: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle("大きなファイルです")
            .setMessage(
                "このファイルは ${formatSize(fullSize)} あり、内部エディタで安全に扱える範囲(${formatSize(MAX_FULL_LOAD_BYTES)})を超えています。\n\n" +
                    "先頭部分のみを読み取り専用で表示しています。編集する場合は「外部アプリで開く」をご利用ください。"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return if (i < 0) "${bytes}B" else String.format("%.1f%s", value, units[i])
    }

    private fun saveFile() {
        val text = binding.editorText.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val result = fs.writeTextFile(path, text)
            withContext(Dispatchers.Main) {
                result.onSuccess { Toast.makeText(this@TextEditorActivity, "保存しました", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this@TextEditorActivity, "保存失敗: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_RUN_AS_PACKAGE = "extra_run_as_package"

        /**
         * これを超えるファイルは丸ごとメモリに載せず、先頭のみの読み取り専用プレビューにする。
         * Amaze File Managerの実ソース(ReadTextFileCallable.MAX_FILE_SIZE_CHARS)が
         * 50 * 1024(50KB)を採用していたのを参考に、同じ値を採用した。
         * 一見小さく感じる値だが、OOM回避だけでなくEditText自体のフリーズ回避
         * (大量文字列に対するSpannable/レイアウト計算コスト)が主目的であるため、
         * デバイスのメモリ量に関わらず一定の値にしている。
         */
        private const val MAX_FULL_LOAD_BYTES = 50L * 1024
    }
}
