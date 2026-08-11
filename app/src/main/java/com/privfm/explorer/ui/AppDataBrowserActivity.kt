package com.privfm.explorer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.privfm.explorer.databinding.ActivityAppDataBinding
import com.privfm.explorer.fs.DebuggableApp
import com.privfm.explorer.fs.DebuggableAppHelper
import com.privfm.explorer.fs.FileEntry
import com.privfm.explorer.fs.FileTypeDetector
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.shell.ShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * debuggable=true のアプリを列挙し、選択後は run-as 経由でその /data/data/<pkg> 以下を
 * 閲覧・編集できるようにする画面。
 */
class AppDataBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDataBinding
    private var selectedApp: DebuggableApp? = null
    private var currentPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appDataToolbar)
        binding.appListView.layoutManager = LinearLayoutManager(this)

        showAppList()
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
        val options = arrayOf("内部テキストエディタ", "内部Hexビューア", "外部アプリで開く(標準機能)")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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

    /**
     * テキスト/コード系は内部エディタ、それ以外は他のファイルマネージャー同様
     * Android標準の「開くアプリを選択」(ACTION_VIEW chooser)へ委譲する。
     */
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
                if (preferInternalText) {
                    openInternalEditor(entry.path, runAsPackage)
                } else {
                    openWithSystemChooser(entry, runAsPackage)
                }
            }
        }
    }

    private fun openWithSystemChooser(entry: FileEntry, runAsPackage: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val mime = com.privfm.explorer.util.ExternalOpener.mimeTypeFor(entry.name)
            val result = fs.readFile(entry.path)
            withContext(Dispatchers.Main) {
                result.onSuccess { bytes ->
                    try {
                        val uri = com.privfm.explorer.util.ExternalOpener.cacheAndGetUri(this@AppDataBrowserActivity, bytes, entry.name)
                        if (com.privfm.explorer.util.ExternalOpener.canResolve(this@AppDataBrowserActivity, uri, mime)) {
                            startActivity(
                                com.privfm.explorer.util.ExternalOpener.buildChooserIntent(
                                    this@AppDataBrowserActivity, uri, mime, "${entry.name} を開く"
                                )
                            )
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
