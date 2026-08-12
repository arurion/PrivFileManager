// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privfm.explorer.databinding.ActivityAppDataBinding
import com.privfm.explorer.fs.DebuggableApp
import com.privfm.explorer.fs.DebuggableAppHelper
import com.privfm.explorer.fs.FileEntry
import com.privfm.explorer.fs.FileTypeDetector
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.shell.ShellManager
import com.privfm.explorer.util.ExternalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * debuggable=true のアプリを列挙し、選択後は run-as 経由でその /data/data/<pkg> 以下を
 * 閲覧・編集できるようにする画面。
 *
 * 「外部アプリに編集を渡せない」問題への対応:
 * run-as配下のファイルは本アプリの通常プロセスから直接は読めないため、
 * 一旦アプリキャッシュへコピー(privileged shell経由で読み込み)→FileProviderで共有→
 * 外部アプリでの編集後、キャッシュのmtime変化を検知して特権パスへ書き戻す。
 */
class AppDataBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDataBinding
    private var selectedApp: DebuggableApp? = null
    private var currentPath: String = ""

    private data class PendingEdit(val cacheFile: File, val originalPath: String, val runAsPackage: String?, val originalMtime: Long)
    private var pendingEdit: PendingEdit? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appDataToolbar)
        binding.appListView.layoutManager = LinearLayoutManager(this)

        showAppList()
    }

    override fun onResume() {
        super.onResume()
        checkPendingEditWriteBack()
    }

    private fun showAppList() {
        selectedApp = null
        supportActionBar?.title = "debuggableアプリ一覧"
        val apps = DebuggableAppHelper.listDebuggableApps(this)
        binding.appListView.adapter = AppAdapter(apps) { app ->
            selectedApp = app
            currentPath = app.dataDir
            openAppFiles(app)
        }
    }

    private fun openAppFiles(app: DebuggableApp) {
        supportActionBar?.title = app.label
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), app.packageName)
            val debuggable = fs.isTargetDebuggable()
            if (!debuggable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AppDataBrowserActivity,
                        "run-as が利用できません(端末がuserビルド、または権限不足の可能性があります)",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            loadAppDirectory(app.dataDir)
        }
    }

    private fun loadAppDirectory(path: String) {
        val app = selectedApp ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), app.packageName)
            val result = fs.listDirectory(path)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    currentPath = path
                    binding.appListView.adapter = FileAdapter(
                        items = it,
                        onClick = { entry -> onFileClicked(entry) },
                        onLongClick = { entry -> onFileLongClicked(entry) }
                    )
                }.onFailure {
                    Toast.makeText(this@AppDataBrowserActivity, "エラー: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onFileClicked(entry: FileEntry) {
        val app = selectedApp ?: return
        if (entry.isDirectory) {
            loadAppDirectory(entry.path)
            return
        }
        openFileSmart(entry, app.packageName)
    }

    private fun onFileLongClicked(entry: FileEntry): Boolean {
        if (entry.isDirectory) return true
        val app = selectedApp ?: return true
        val options = arrayOf("内部テキストエディタ", "内部Hexビューア", "外部アプリで開く(標準機能・書き戻し対応)")
        MaterialAlertDialogBuilder(this)
            .setTitle("${entry.name} の開き方")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openInternalEditor(entry.path, app.packageName)
                    1 -> openBinaryViewer(entry.path, app.packageName)
                    2 -> openWithSystemChooser(entry, app.packageName)
                }
            }
            .show()
        return true
    }

    private fun openInternalEditor(path: String, runAsPackage: String?) {
        val intent = Intent(this, TextEditorActivity::class.java).apply {
            putExtra(TextEditorActivity.EXTRA_PATH, path)
            putExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE, runAsPackage)
        }
        startActivity(intent)
    }

    private fun openBinaryViewer(path: String, runAsPackage: String?) {
        val intent = Intent(this, BinaryViewerActivity::class.java).apply {
            putExtra(TextEditorActivity.EXTRA_PATH, path)
            putExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE, runAsPackage)
        }
        startActivity(intent)
    }

    private fun openFileSmart(entry: FileEntry, runAsPackage: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val kind = FileTypeDetector.kindByExtension(entry.name)
            val preferInternalText = when (kind) {
                FileTypeDetector.Kind.TEXT -> true
                FileTypeDetector.Kind.BINARY -> false
                FileTypeDetector.Kind.UNKNOWN -> {
                    val peek = fs.peekFile(entry.path, maxBytes = 4096)
                    peek.map { !FileTypeDetector.looksLikeBinary(it) }.getOrDefault(false)
                }
            }
            withContext(Dispatchers.Main) {
                if (preferInternalText) openInternalEditor(entry.path, runAsPackage)
                else openWithSystemChooser(entry, runAsPackage)
            }
        }
    }

    /**
     * run-as配下のファイルをキャッシュへコピーして外部アプリの標準チューザーで開き、
     * 戻ってきたときにキャッシュの変更を検知して /data/data/<pkg> へ書き戻す。
     */
    private fun openWithSystemChooser(entry: FileEntry, runAsPackage: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val mime = ExternalOpener.mimeTypeFor(entry.name)
            val result = fs.readFile(entry.path)
            withContext(Dispatchers.Main) {
                result.onSuccess { bytes ->
                    try {
                        val cacheFile = ExternalOpener.cacheFile(this@AppDataBrowserActivity, bytes, entry.name)
                        val uri = ExternalOpener.uriForCacheFile(this@AppDataBrowserActivity, cacheFile)
                        if (ExternalOpener.canResolve(this@AppDataBrowserActivity, uri, mime)) {
                            pendingEdit = PendingEdit(cacheFile, entry.path, runAsPackage, cacheFile.lastModified())
                            startActivity(ExternalOpener.buildChooserIntent(this@AppDataBrowserActivity, uri, mime, "${entry.name} を開く"))
                        } else {
                            Toast.makeText(this@AppDataBrowserActivity, "対応する外部アプリが見つかりません。内部ビューアで開きます", Toast.LENGTH_SHORT).show()
                            openBinaryViewer(entry.path, runAsPackage)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@AppDataBrowserActivity, "開けませんでした: ${e.message}", Toast.LENGTH_LONG).show()
                        openBinaryViewer(entry.path, runAsPackage)
                    }
                }.onFailure {
                    Toast.makeText(this@AppDataBrowserActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPendingEditWriteBack() {
        val pending = pendingEdit ?: return
        if (!pending.cacheFile.exists()) { pendingEdit = null; return }
        val currentMtime = pending.cacheFile.lastModified()
        if (currentMtime == pending.originalMtime) { pendingEdit = null; return }

        MaterialAlertDialogBuilder(this)
            .setTitle("変更を書き戻しますか?")
            .setMessage("外部アプリでの編集を検出しました。\n${pending.originalPath}\nへ書き戻しますか?")
            .setCancelable(false)
            .setPositiveButton("書き戻す") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val fs = PrivilegedFileSystem(ShellManager.current(), pending.runAsPackage)
                    val bytes = pending.cacheFile.readBytes()
                    val result = fs.writeFile(pending.originalPath, bytes)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { Toast.makeText(this@AppDataBrowserActivity, "書き戻しました", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@AppDataBrowserActivity, "書き戻し失敗: ${it.message}", Toast.LENGTH_LONG).show() }
                        pendingEdit = null
                        loadAppDirectory(currentPath)
                    }
                }
            }
            .setNegativeButton("破棄") { _, _ -> pendingEdit = null }
            .show()
    }

    override fun onBackPressed() {
        val app = selectedApp
        if (app == null) {
            super.onBackPressed()
            return
        }
        val parent = currentPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent.length >= app.dataDir.length) {
            loadAppDirectory(parent)
        } else {
            showAppList()
        }
    }
}
