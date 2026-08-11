package com.privfm.explorer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privfm.explorer.databinding.ActivityMainBinding
import com.privfm.explorer.fs.FileEntry
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.shell.RootShell
import com.privfm.explorer.shell.ShellManager
import com.privfm.explorer.shell.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter
    private var currentPath: String = "/storage/emulated/0"

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (granted) "Shizuku権限が許可されました" else "Shizuku権限が拒否されました",
            Toast.LENGTH_SHORT
        ).show()
        refreshStatusBar()
        loadDirectory(currentPath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        adapter = FileAdapter(
            items = emptyList(),
            onClick = { entry -> onEntryClicked(entry) },
            onLongClick = { entry -> onEntryLongClicked(entry) }
        )
        binding.fileListView.layoutManager = LinearLayoutManager(this)
        binding.fileListView.adapter = adapter

        refreshStatusBar()
        maybeRequestAllFilesAccess()
        loadDirectory(currentPath)
    }

    /**
     * 通常権限モードでファイル一覧を見るには Android 11+ で
     * MANAGE_EXTERNAL_STORAGE の実行時許可が別途必要。
     * Shizuku/Root利用時は不要だが、未許可だと通常モードへのフォールバックが機能しないため誘導する。
     */
    private fun maybeRequestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager() && !ShellManager.hasPrivilegedAccess()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("ファイルアクセス許可が必要です")
                    .setMessage("Shizuku/Rootを使わない場合、全ファイルアクセスの許可が必要です。設定画面を開きますか?")
                    .setPositiveButton("設定を開く") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .setNegativeButton("後で", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatusBar()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(com.privfm.explorer.R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            com.privfm.explorer.R.id.action_request_shizuku -> {
                if (!com.privfm.explorer.shell.ShizukuShell.isAvailable() &&
                    rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.shouldShowRequestPermissionRationale()
                ) {
                    Toast.makeText(
                        this,
                        "Shizuku側の設定画面から手動で権限を許可してください",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    ShizukuShell.requestPermission(REQ_SHIZUKU)
                }
                true
            }
            com.privfm.explorer.R.id.action_root_check -> {
                RootShell.invalidate()
                lifecycleScope.launch(Dispatchers.IO) {
                    val available = RootShell.isAvailable()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            if (available) "Root権限を検出しました" else "Rootは利用できません",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshStatusBar()
                    }
                }
                true
            }
            com.privfm.explorer.R.id.action_git_clone -> {
                startActivity(Intent(this, GitCloneActivity::class.java))
                true
            }
            com.privfm.explorer.R.id.action_app_data -> {
                startActivity(Intent(this, AppDataBrowserActivity::class.java))
                true
            }
            com.privfm.explorer.R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshStatusBar() {
        val engine = ShellManager.current()
        binding.statusBar.text = "モード: ${engine.label()}"
    }

    private fun loadDirectory(path: String) {
        binding.currentPathView.text = path
        binding.loadingIndicator.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current())
            val result = fs.listDirectory(path)
            withContext(Dispatchers.Main) {
                binding.loadingIndicator.visibility = android.view.View.GONE
                result.onSuccess {
                    currentPath = path
                    adapter.submitList(it)
                }.onFailure {
                    Toast.makeText(this@MainActivity, "エラー: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onEntryClicked(entry: FileEntry) {
        if (entry.isDirectory) {
            loadDirectory(entry.path)
            return
        }
        openFileSmart(entry, runAsPackage = null)
    }

    /**
     * ファイルを開く。テキスト/コード系は内部エディタ、
     * それ以外(画像・動画・PDF・APK・不明バイナリ等)は他の一般的なファイルマネージャー同様、
     * Android標準の「開くアプリを選択」(ACTION_VIEW chooser)へ委譲する。
     * 対応アプリが端末に無い場合のみ内部のHexビューアへフォールバックする。
     */
    private fun openFileSmart(entry: FileEntry, runAsPackage: String?) {
        binding.loadingIndicator.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val kind = com.privfm.explorer.fs.FileTypeDetector.kindByExtension(entry.name)
            val preferInternalText = when (kind) {
                com.privfm.explorer.fs.FileTypeDetector.Kind.TEXT -> true
                com.privfm.explorer.fs.FileTypeDetector.Kind.BINARY -> false
                com.privfm.explorer.fs.FileTypeDetector.Kind.UNKNOWN -> {
                    val peek = fs.peekFile(entry.path, maxBytes = 4096)
                    peek.map { !com.privfm.explorer.fs.FileTypeDetector.looksLikeBinary(it) }.getOrDefault(false)
                }
            }
            withContext(Dispatchers.Main) { binding.loadingIndicator.visibility = android.view.View.GONE }

            if (preferInternalText) {
                withContext(Dispatchers.Main) { openInternalEditor(entry.path, runAsPackage) }
                return@launch
            }

            // テキスト以外は標準の「開くアプリを選択」に委譲する
            openWithSystemChooser(entry, runAsPackage)
        }
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
     * privileged経路で読んだファイルをアプリキャッシュへコピーし、
     * FileProvider経由でAndroid標準のACTION_VIEWチューザーに渡す。
     * 対応アプリが無ければ内部Hexビューアへフォールバックする。
     */
    private fun openWithSystemChooser(entry: FileEntry, runAsPackage: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val mime = com.privfm.explorer.util.ExternalOpener.mimeTypeFor(entry.name)
            val result = fs.readFile(entry.path)
            withContext(Dispatchers.Main) {
                result.onSuccess { bytes ->
                    try {
                        val uri = com.privfm.explorer.util.ExternalOpener.cacheAndGetUri(this@MainActivity, bytes, entry.name)
                        if (com.privfm.explorer.util.ExternalOpener.canResolve(this@MainActivity, uri, mime)) {
                            startActivity(
                                com.privfm.explorer.util.ExternalOpener.buildChooserIntent(
                                    this@MainActivity, uri, mime, "${entry.name} を開く"
                                )
                            )
                        } else {
                            Toast.makeText(this@MainActivity, "対応する外部アプリが見つかりません。内部ビューアで開きます", Toast.LENGTH_SHORT).show()
                            openBinaryViewer(entry.path, runAsPackage)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "開けませんでした: ${e.message}", Toast.LENGTH_LONG).show()
                        openBinaryViewer(entry.path, runAsPackage)
                    }
                }.onFailure {
                    Toast.makeText(this@MainActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onEntryLongClicked(entry: FileEntry): Boolean {
        val options = if (entry.isDirectory)
            arrayOf("削除", "リネーム", "権限確認")
        else
            arrayOf("開き方を選択…", "削除", "リネーム", "権限確認")
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                if (!entry.isDirectory && which == 0) {
                    showOpenWithChooser(entry)
                    return@setItems
                }
                val offset = if (entry.isDirectory) 0 else 1
                when (which - offset) {
                    0 -> confirmDelete(entry)
                    1 -> renameEntry(entry)
                    2 -> Toast.makeText(this, entry.permissions, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
        return true
    }

    /** テキストエディタ / Hexビューア / 外部アプリ を明示的に選ばせるダイアログ */
    private fun showOpenWithChooser(entry: FileEntry) {
        val options = arrayOf("内部テキストエディタ", "内部Hexビューア", "外部アプリで開く(標準機能)")
        MaterialAlertDialogBuilder(this)
            .setTitle("${entry.name} の開き方")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openInternalEditor(entry.path, null)
                    1 -> openBinaryViewer(entry.path, null)
                    2 -> openWithSystemChooser(entry, null)
                }
            }
            .show()
    }

    private fun confirmDelete(entry: FileEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("削除確認")
            .setMessage("${entry.name} を削除しますか?")
            .setPositiveButton("削除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val fs = PrivilegedFileSystem(ShellManager.current())
                    val result = fs.delete(entry.path, recursive = entry.isDirectory)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { loadDirectory(currentPath) }
                            .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun renameEntry(entry: FileEntry) {
        val input = android.widget.EditText(this).apply { setText(entry.name) }
        MaterialAlertDialogBuilder(this)
            .setTitle("リネーム")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString()
                val newPath = entry.path.substringBeforeLast('/') + "/" + newName
                lifecycleScope.launch(Dispatchers.IO) {
                    val fs = PrivilegedFileSystem(ShellManager.current())
                    val result = fs.rename(entry.path, newPath)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { loadDirectory(currentPath) }
                            .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onBackPressed() {
        val parent = currentPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != currentPath) {
            loadDirectory(parent)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
    }
}
