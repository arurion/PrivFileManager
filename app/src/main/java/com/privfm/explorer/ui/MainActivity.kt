// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privfm.explorer.R
import com.privfm.explorer.databinding.ActivityMainBinding
import com.privfm.explorer.databinding.ItemBreadcrumbBinding
import com.privfm.explorer.fs.ClipboardHolder
import com.privfm.explorer.fs.FileEntry
import com.privfm.explorer.fs.FileTypeDetector
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.fs.SortMode
import com.privfm.explorer.fs.sortEntries
import com.privfm.explorer.shell.RootShell
import com.privfm.explorer.shell.ShellManager
import com.privfm.explorer.shell.ShizukuShell
import com.privfm.explorer.util.ExternalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter
    private var currentPath: String = "/storage/emulated/0"
    private var currentEntries: List<FileEntry> = emptyList()
    private var searchQuery: String = ""
    private var sortMode: SortMode = SortMode.NAME
    private var sortAscending: Boolean = true

    // 複数選択モード
    private var selectionMode = false
    private val selectedPaths = mutableSetOf<String>()

    // 外部アプリで開いて編集後、変更を特権パスへ書き戻すための保留情報
    private data class PendingEdit(val cacheFile: File, val originalPath: String, val runAsPackage: String?, val originalMtime: Long)
    private var pendingEdit: PendingEdit? = null

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
            onLongClick = { entry -> onEntryLongClicked(entry) },
            isSelectionMode = { selectionMode },
            isSelected = { entry -> selectedPaths.contains(entry.path) },
            onToggleSelect = { entry -> toggleSelection(entry) }
        )
        binding.fileListView.layoutManager = LinearLayoutManager(this)
        binding.fileListView.adapter = adapter

        refreshStatusBar()
        maybeRequestAllFilesAccess()
        loadDirectory(currentPath)
    }

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
        checkPendingEditWriteBack()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(if (selectionMode) R.menu.menu_selection else R.menu.menu_main, menu)
        if (!selectionMode) {
            menu.findItem(R.id.action_paste)?.isEnabled = !ClipboardHolder.isEmpty()
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem?.actionView as? SearchView
            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    searchQuery = newText.orEmpty()
                    applyFilterAndSort()
                    return true
                }
            })
        } else {
            supportActionBar?.title = "${selectedPaths.size}件選択中"
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_request_shizuku -> {
                if (!ShizukuShell.isAvailable() && Shizuku.pingBinder() && Shizuku.shouldShowRequestPermissionRationale()) {
                    Toast.makeText(this, "Shizuku側の設定画面から手動で権限を許可してください", Toast.LENGTH_LONG).show()
                } else {
                    ShizukuShell.requestPermission(REQ_SHIZUKU)
                }
                true
            }
            R.id.action_root_check -> {
                RootShell.invalidate()
                lifecycleScope.launch(Dispatchers.IO) {
                    val available = RootShell.isAvailable()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, if (available) "Root権限を検出しました" else "Rootは利用できません", Toast.LENGTH_SHORT).show()
                        refreshStatusBar()
                    }
                }
                true
            }
            R.id.action_git_clone -> { startActivity(Intent(this, GitCloneActivity::class.java)); true }
            R.id.action_app_data -> { startActivity(Intent(this, AppDataBrowserActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_sort -> { showSortDialog(); true }
            R.id.action_new_folder -> { showCreateDialog(isDirectory = true); true }
            R.id.action_new_file -> { showCreateDialog(isDirectory = false); true }
            R.id.action_paste -> { pasteClipboard(); true }
            R.id.action_selection_mode -> { setSelectionMode(true); true }
            R.id.action_select_all -> {
                selectedPaths.clear()
                selectedPaths.addAll(currentEntries.map { it.path })
                invalidateOptionsMenu(); adapter.notifyDataSetChanged(); true
            }
            R.id.action_copy_selected -> { copySelectedToClipboard(ClipboardHolder.Mode.COPY); true }
            R.id.action_cut_selected -> { copySelectedToClipboard(ClipboardHolder.Mode.CUT); true }
            R.id.action_delete_selected -> { confirmDeleteSelected(); true }
            R.id.action_exit_selection -> { setSelectionMode(false); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshStatusBar() {
        val engine = ShellManager.current()
        binding.statusBar.text = "モード: ${engine.label()}"
    }

    // ---- 読み込み・パンくず ----

    private fun loadDirectory(path: String) {
        binding.loadingIndicator.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current())
            val result = fs.listDirectory(path)
            withContext(Dispatchers.Main) {
                binding.loadingIndicator.visibility = android.view.View.GONE
                result.onSuccess {
                    currentPath = path
                    currentEntries = it
                    renderBreadcrumb(path)
                    applyFilterAndSort()
                }.onFailure {
                    Toast.makeText(this@MainActivity, "エラー: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyFilterAndSort() {
        val filtered = if (searchQuery.isBlank()) currentEntries
        else currentEntries.filter { it.name.contains(searchQuery, ignoreCase = true) }
        adapter.submitList(sortEntries(filtered, sortMode, sortAscending))
    }

    private fun renderBreadcrumb(path: String) {
        binding.breadcrumbContainer.removeAllViews()
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var accumulated = ""
        // ルート("/")
        addBreadcrumbSegment("/", "/")
        for (seg in segments) {
            accumulated += "/$seg"
            addBreadcrumbSegment(seg, accumulated)
        }
    }

    private fun addBreadcrumbSegment(label: String, fullPath: String) {
        val segBinding = ItemBreadcrumbBinding.inflate(layoutInflater, binding.breadcrumbContainer, false)
        segBinding.segmentLabel.text = label
        segBinding.segmentLabel.setOnClickListener { loadDirectory(fullPath) }
        binding.breadcrumbContainer.addView(segBinding.root)
    }

    // ---- ソート ----

    private fun showSortDialog() {
        val modes = SortMode.values()
        val labels = modes.map { it.label + if (it == sortMode) (if (sortAscending) " ▲" else " ▼") else "" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_sort))
            .setItems(labels) { _, which ->
                val picked = modes[which]
                sortAscending = if (picked == sortMode) !sortAscending else true
                sortMode = picked
                applyFilterAndSort()
            }
            .show()
    }

    // ---- 新規作成 ----

    private fun showCreateDialog(isDirectory: Boolean) {
        val input = android.widget.EditText(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isDirectory) getString(R.string.action_new_folder) else getString(R.string.action_new_file))
            .setView(input)
            .setPositiveButton("作成") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val newPath = "${currentPath.trimEnd('/')}/$name"
                lifecycleScope.launch(Dispatchers.IO) {
                    val fs = PrivilegedFileSystem(ShellManager.current())
                    val result = if (isDirectory) fs.mkdir(newPath) else fs.createEmptyFile(newPath)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { loadDirectory(currentPath) }
                            .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---- 複数選択・クリップボード ----

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedPaths.clear()
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
        supportActionBar?.title = if (enabled) "${selectedPaths.size}件選択中" else getString(R.string.app_name)
    }

    private fun toggleSelection(entry: FileEntry) {
        if (!selectionMode) setSelectionMode(true)
        if (selectedPaths.contains(entry.path)) selectedPaths.remove(entry.path) else selectedPaths.add(entry.path)
        supportActionBar?.title = "${selectedPaths.size}件選択中"
        adapter.notifyDataSetChanged()
    }

    private fun copySelectedToClipboard(mode: ClipboardHolder.Mode) {
        val entries = currentEntries.filter { selectedPaths.contains(it.path) }
            .map { ClipboardHolder.Entry(it.path, it.name, it.isDirectory) }
        ClipboardHolder.set(entries, mode)
        Toast.makeText(this, "${entries.size}件を${if (mode == ClipboardHolder.Mode.CUT) "切り取り" else "コピー"}しました", Toast.LENGTH_SHORT).show()
        setSelectionMode(false)
    }

    private fun pasteClipboard() {
        if (ClipboardHolder.isEmpty()) return
        val items = ClipboardHolder.items
        val cutMode = ClipboardHolder.mode == ClipboardHolder.Mode.CUT
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current())
            var okCount = 0
            for (item in items) {
                val dest = "${currentPath.trimEnd('/')}/${item.name}"
                val result = if (cutMode) fs.rename(item.path, dest) else fs.copy(item.path, dest)
                if (result.isSuccess) okCount++
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "$okCount / ${items.size} 件を貼り付けました", Toast.LENGTH_SHORT).show()
                if (cutMode) ClipboardHolder.clear()
                loadDirectory(currentPath)
            }
        }
    }

    private fun confirmDeleteSelected() {
        val count = selectedPaths.size
        if (count == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("削除確認")
            .setMessage("$count 件を削除しますか?")
            .setPositiveButton("削除") { _, _ ->
                val targets = currentEntries.filter { selectedPaths.contains(it.path) }
                lifecycleScope.launch(Dispatchers.IO) {
                    val fs = PrivilegedFileSystem(ShellManager.current())
                    var okCount = 0
                    for (t in targets) {
                        if (fs.delete(t.path, recursive = t.isDirectory).isSuccess) okCount++
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "$okCount / ${targets.size} 件削除しました", Toast.LENGTH_SHORT).show()
                        setSelectionMode(false)
                        loadDirectory(currentPath)
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---- ファイルを開く ----

    private fun onEntryClicked(entry: FileEntry) {
        if (entry.isDirectory) {
            loadDirectory(entry.path)
            return
        }
        openFileSmart(entry, runAsPackage = null)
    }

    private fun openFileSmart(entry: FileEntry, runAsPackage: String?) {
        binding.loadingIndicator.visibility = android.view.View.VISIBLE
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
            withContext(Dispatchers.Main) { binding.loadingIndicator.visibility = android.view.View.GONE }
            if (preferInternalText) {
                withContext(Dispatchers.Main) { openInternalEditor(entry.path, runAsPackage) }
            } else {
                openWithSystemChooser(entry, runAsPackage)
            }
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
     * FileProvider経由でAndroid標準のACTION_VIEWチューザーに渡す(読み書き両方の権限を付与)。
     * 戻ってきたとき(onResume)にキャッシュファイルのmtimeが変化していれば、
     * 「特権パスへ書き戻すか」を確認する(debuggableアプリのデータを外部エディタで
     * 編集できない問題への対応)。
     */
    private fun openWithSystemChooser(entry: FileEntry, runAsPackage: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val mime = ExternalOpener.mimeTypeFor(entry.name)
            val result = fs.readFile(entry.path)
            withContext(Dispatchers.Main) {
                result.onSuccess { bytes ->
                    try {
                        val cacheFile = ExternalOpener.cacheFile(this@MainActivity, bytes, entry.name)
                        val uri = ExternalOpener.uriForCacheFile(this@MainActivity, cacheFile)
                        if (ExternalOpener.canResolve(this@MainActivity, uri, mime)) {
                            pendingEdit = PendingEdit(cacheFile, entry.path, runAsPackage, cacheFile.lastModified())
                            startActivity(ExternalOpener.buildChooserIntent(this@MainActivity, uri, mime, "${entry.name} を開く"))
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
                        result.onSuccess { Toast.makeText(this@MainActivity, "書き戻しました", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@MainActivity, "書き戻し失敗: ${it.message}", Toast.LENGTH_LONG).show() }
                        pendingEdit = null
                        loadDirectory(currentPath)
                    }
                }
            }
            .setNegativeButton("破棄") { _, _ -> pendingEdit = null }
            .show()
    }

    // ---- 長押しメニュー ----

    private fun onEntryLongClicked(entry: FileEntry): Boolean {
        if (selectionMode) { toggleSelection(entry); return true }
        val options = if (entry.isDirectory)
            arrayOf("削除", "リネーム", "権限確認")
        else
            arrayOf("開き方を選択…", "削除", "リネーム", "権限確認")
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                if (!entry.isDirectory && which == 0) { showOpenWithChooser(entry); return@setItems }
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
        if (selectionMode) { setSelectionMode(false); return }
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
