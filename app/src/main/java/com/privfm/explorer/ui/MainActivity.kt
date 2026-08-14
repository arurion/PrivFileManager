// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.privfm.explorer.R
import com.privfm.explorer.databinding.ActivityMainBinding
import com.privfm.explorer.databinding.ItemBreadcrumbBinding
import com.privfm.explorer.fs.ArchiveUtil
import com.privfm.explorer.fs.ClipboardHolder
import com.privfm.explorer.fs.FileEntry
import com.privfm.explorer.fs.FileTypeDetector
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.fs.SortMode
import com.privfm.explorer.fs.sortEntries
import com.privfm.explorer.service.ArchiveService
import com.privfm.explorer.shell.RootShell
import com.privfm.explorer.shell.ShellManager
import com.privfm.explorer.shell.ShizukuShell
import com.privfm.explorer.util.ExternalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * ファイルブラウザ画面。
 *
 * 通常はランチャーから起動され、端末ストレージ("/storage/emulated/0")をルートとして動作する。
 * [EXTRA_ROOT_PATH] / [EXTRA_RUN_AS_PACKAGE] / [EXTRA_TITLE] を付与して起動すると、
 * 任意のディレクトリ(例: debuggableアプリの `/data/data/<package>`)を
 * run-as経由のルートとして同じ機能一式(複数選択・コピー/切り取り/貼り付け・圧縮/展開・
 * 検索・並び替え・新規作成など)でブラウズできる。これにより
 * 「debuggableアプリ側だけ機能が少ない」という非対称性を解消している。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter

    // ランチャーから起動した場合のデフォルトルート。以前は "/storage/emulated/0" に
    // 固定していたが、「アクセスできる範囲は全て見られるように」という要望に応え、
    // 実際のファイルシステムルート "/" を起点にする(Shizuku/Rootが無ければ通常権限の
    // 範囲でしか実際には読めないので、過剰な露出にはならない)。
    private var rootPath: String = "/"
    private var runAsPackage: String? = null
    private var rootLabel: String = "デバイス (/)"

    private var currentPath: String = "/"
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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 結果は問わない: 拒否時は通知なしで処理続行 */ }

    private val archiveResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(ArchiveService.EXTRA_SUCCESS, false)
            val message = intent.getStringExtra(ArchiveService.EXTRA_MESSAGE)
            Toast.makeText(
                this@MainActivity,
                if (success) "処理が完了しました" else "処理に失敗しました: $message",
                Toast.LENGTH_LONG
            ).show()
            if (success) loadDirectory(currentPath)
        }
    }

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

        rootPath = intent.getStringExtra(EXTRA_ROOT_PATH) ?: rootPath
        runAsPackage = intent.getStringExtra(EXTRA_RUN_AS_PACKAGE)
        rootLabel = intent.getStringExtra(EXTRA_TITLE) ?: rootLabel
        // ルート自体は"/"(全ファイルシステム)のままにしつつ、初期表示は使いやすい
        // ストレージ領域から始める。EXTRA_START_PATHで明示的に上書き可能。
        currentPath = intent.getStringExtra(EXTRA_START_PATH)
            ?: if (runAsPackage == null && rootPath == "/") "/storage/emulated/0" else rootPath
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)

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
        if (runAsPackage == null) maybeRequestAllFilesAccess()
        loadDirectory(currentPath)
    }

    private fun maybeRequestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager() && !ShellManager.hasPrivilegedAccess()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("ファイルアクセス許可が必要です")
                    .setMessage("Shizuku/Rootを使わない場合、全ファイルアクセスの許可が必要です。設定画面を開きますか?")
                    .setPositiveButton("設定を開く") { _, _ ->
                        val i = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        try {
                            startActivity(i)
                        } catch (e: Exception) {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .setNegativeButton("後で", null)
                    .show()
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatusBar()
        checkPendingEditWriteBack()
        val filter = IntentFilter(ArchiveService.BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(archiveResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(archiveResultReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(archiveResultReceiver) } catch (e: Exception) { /* 未登録なら無視 */ }
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
            R.id.action_quick_access -> { showQuickAccessDialog(); true }
            R.id.action_go_to_path -> { showGoToPathDialog(); true }
            R.id.action_jump_foreground_app -> { jumpToForegroundApp(); true }
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
            R.id.action_compress_selected -> { compressSelected(); true }
            R.id.action_exit_selection -> { setSelectionMode(false); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshStatusBar() {
        val engine = ShellManager.current()
        val scope = if (runAsPackage != null) " / run-as: $runAsPackage" else ""
        binding.statusBar.text = "モード: ${engine.label()}$scope"
    }

    // ---- 読み込み・パンくず ----

    private fun loadDirectory(path: String) {
        binding.loadingIndicator.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
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

    /**
     * パンくずの起点は実際のファイルシステムの "/" ではなく、このブラウザセッションの
     * [rootPath] にする。以前は常に絶対ルート "/" を先頭に追加していたため、
     * "/storage/emulated/0" をルートとする通常ブラウズでは
     * 「/」と「storage/emulated/0」が並んで表示され、ルート表現が二重に見える問題があった。
     */
    private fun renderBreadcrumb(path: String) {
        binding.breadcrumbContainer.removeAllViews()
        addBreadcrumbSegment(rootLabel, rootPath)

        val relative = path.removePrefix(rootPath).trim('/')
        if (relative.isEmpty()) return
        var accumulated = rootPath.trimEnd('/')
        for (seg in relative.split('/').filter { it.isNotEmpty() }) {
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

    // ---- クイックアクセス・パス直接指定 ----

    private fun showQuickAccessDialog() {
        val places = linkedMapOf(
            "内部ストレージ (/storage/emulated/0)" to "/storage/emulated/0",
            "Android/data" to "/storage/emulated/0/Android/data",
            "Download" to "/storage/emulated/0/Download",
            "デバイスのルート (/)" to "/",
            "/system" to "/system",
            "/data" to "/data",
            "/proc" to "/proc"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_quick_access))
            .setItems(places.keys.toTypedArray()) { _, which ->
                loadDirectory(places.values.toList()[which])
            }
            .show()
    }

    private fun showGoToPathDialog() {
        val input = android.widget.EditText(this).apply { setText(currentPath) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_go_to_path))
            .setView(input)
            .setPositiveButton("移動") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) loadDirectory(path)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * ForegroundAppAccessibilityService が検知した「今表示されているアプリ」の
     * パッケージ名を使い、そのアプリのデータ領域を(debuggableであれば)新しいブラウザとして開く。
     */
    private fun jumpToForegroundApp() {
        val pkg = com.privfm.explorer.service.ForegroundAppAccessibilityService.lastForegroundPackage
        if (pkg == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("検知できていません")
                .setMessage("フォアグラウンドアプリがまだ検知されていません。設定からAccessibilityサービスを有効にしてから、対象アプリを一度表示してください。")
                .setPositiveButton("設定を開く") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("閉じる", null)
                .show()
            return
        }
        val apps = com.privfm.explorer.fs.DebuggableAppHelper.listDebuggableApps(this)
        val app = apps.find { it.packageName == pkg }
        if (app == null) {
            Toast.makeText(this, "$pkg はdebuggableではないため、run-as経由のデータアクセスはできません", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_ROOT_PATH, app.dataDir)
            putExtra(EXTRA_RUN_AS_PACKAGE, app.packageName)
            putExtra(EXTRA_TITLE, app.label)
        }
        startActivity(intent)
    }

    // ---- パーミッション変更(chmod) ----

    private fun showChmodDialog(entry: FileEntry) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_chmod, null)
        val checks = listOf(
            R.id.chkOwnerRead, R.id.chkOwnerWrite, R.id.chkOwnerExec,
            R.id.chkGroupRead, R.id.chkGroupWrite, R.id.chkGroupExec,
            R.id.chkOtherRead, R.id.chkOtherWrite, R.id.chkOtherExec
        ).map { dialogView.findViewById<android.widget.CheckBox>(it) }

        // "drwxr-xr-x" のようなpermissions文字列末尾9文字を反映
        val perm = entry.permissions.takeLast(9).padStart(9, '-')
        val flags = perm.map { it != '-' }
        checks.forEachIndexed { i, cb -> cb.isChecked = flags.getOrElse(i) { false } }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_change_permissions))
            .setView(dialogView)
            .setPositiveButton("適用") { _, _ ->
                var mode = 0
                val bits = intArrayOf(0b100, 0b010, 0b001)
                for (group in 0 until 3) {
                    var groupVal = 0
                    for (b in 0 until 3) {
                        if (checks[group * 3 + b].isChecked) groupVal = groupVal or bits[b]
                    }
                    mode = mode or (groupVal shl ((2 - group) * 3))
                }
                val octal = String.format("%03o", mode)
                lifecycleScope.launch(Dispatchers.IO) {
                    val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
                    val result = fs.chmod(entry.path, octal)
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            Toast.makeText(this@MainActivity, "権限を $octal に変更しました", Toast.LENGTH_SHORT).show()
                            loadDirectory(currentPath)
                        }.onFailure { Toast.makeText(this@MainActivity, "変更失敗: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }



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
                    val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
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

    // ---- 複数選択・クリップボード・圧縮 ----

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedPaths.clear()
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
        supportActionBar?.title = if (enabled) "${selectedPaths.size}件選択中" else (intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name))
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
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
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
                    val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
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

    private fun compressSelected() {
        val targets = currentEntries.filter { selectedPaths.contains(it.path) }
        if (targets.isEmpty()) return
        val input = android.widget.EditText(this).apply { setText("archive.zip") }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_compress))
            .setView(input)
            .setPositiveButton("圧縮") { _, _ ->
                val zipName = input.text.toString().trim().let { if (it.endsWith(".zip")) it else "$it.zip" }
                val destPath = "${currentPath.trimEnd('/')}/$zipName"
                ensureNotificationPermission()
                ClipboardHolder.archiveTargets = targets
                val svcIntent = Intent(this, ArchiveService::class.java).apply {
                    putExtra(ArchiveService.EXTRA_ACTION, ArchiveService.ACTION_COMPRESS)
                    putExtra(ArchiveService.EXTRA_RUN_AS_PACKAGE, runAsPackage)
                    putExtra(ArchiveService.EXTRA_DEST_PATH, destPath)
                    putExtra(ArchiveService.EXTRA_REFRESH_PATH, currentPath)
                }
                startForegroundServiceCompat(svcIntent)
                setSelectionMode(false)
                Toast.makeText(this, "バックグラウンドで圧縮を開始しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun extractZip(entry: FileEntry) {
        val destDir = "${currentPath.trimEnd('/')}/${entry.name.removeSuffix(".zip")}"
        ensureNotificationPermission()
        val svcIntent = Intent(this, ArchiveService::class.java).apply {
            putExtra(ArchiveService.EXTRA_ACTION, ArchiveService.ACTION_EXTRACT)
            putExtra(ArchiveService.EXTRA_RUN_AS_PACKAGE, runAsPackage)
            putExtra(ArchiveService.EXTRA_SOURCE_PATH, entry.path)
            putExtra(ArchiveService.EXTRA_DEST_PATH, destDir)
            putExtra(ArchiveService.EXTRA_REFRESH_PATH, currentPath)
        }
        startForegroundServiceCompat(svcIntent)
        Toast.makeText(this, "バックグラウンドで展開を開始しました", Toast.LENGTH_SHORT).show()
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    // ---- ファイルを開く ----

    private fun onEntryClicked(entry: FileEntry) {
        if (entry.isDirectory) {
            loadDirectory(entry.path)
            return
        }
        openFileSmart(entry, runAsPackage)
    }

    private fun openFileSmart(entry: FileEntry, runAsPkg: String?) {
        binding.loadingIndicator.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPkg)
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
                withContext(Dispatchers.Main) { openInternalEditor(entry.path, runAsPkg) }
            } else {
                openWithSystemChooser(entry, runAsPkg)
            }
        }
    }

    private fun openInternalEditor(path: String, runAsPkg: String?) {
        val intent = Intent(this, TextEditorActivity::class.java).apply {
            putExtra(TextEditorActivity.EXTRA_PATH, path)
            putExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE, runAsPkg)
        }
        startActivity(intent)
    }

    private fun openBinaryViewer(path: String, runAsPkg: String?) {
        val intent = Intent(this, BinaryViewerActivity::class.java).apply {
            putExtra(TextEditorActivity.EXTRA_PATH, path)
            putExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE, runAsPkg)
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
    private fun openWithSystemChooser(entry: FileEntry, runAsPkg: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPkg)
            val mime = ExternalOpener.mimeTypeFor(entry.name)
            val result = fs.readFile(entry.path)
            withContext(Dispatchers.Main) {
                result.onSuccess { bytes ->
                    try {
                        val cacheFile = ExternalOpener.cacheFile(this@MainActivity, bytes, entry.name)
                        val uri = ExternalOpener.uriForCacheFile(this@MainActivity, cacheFile)
                        if (ExternalOpener.canResolve(this@MainActivity, uri, mime)) {
                            pendingEdit = PendingEdit(cacheFile, entry.path, runAsPkg, cacheFile.lastModified())
                            startActivity(ExternalOpener.buildChooserIntent(this@MainActivity, uri, mime, "${entry.name} を開く"))
                        } else {
                            Toast.makeText(this@MainActivity, "対応する外部アプリが見つかりません。内部ビューアで開きます", Toast.LENGTH_SHORT).show()
                            openBinaryViewer(entry.path, runAsPkg)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "開けませんでした: ${e.message}", Toast.LENGTH_LONG).show()
                        openBinaryViewer(entry.path, runAsPkg)
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
        val isZip = !entry.isDirectory && entry.name.endsWith(".zip", ignoreCase = true)
        val options = mutableListOf<String>()
        if (!entry.isDirectory) options.add("開き方を選択…")
        if (isZip) options.add(getString(R.string.action_extract))
        options.addAll(listOf("削除", "リネーム", "パーミッションを変更"))

        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(options.toTypedArray()) { _, which ->
                var idx = which
                if (!entry.isDirectory) {
                    if (idx == 0) { showOpenWithChooser(entry); return@setItems }
                    idx--
                }
                if (isZip) {
                    if (idx == 0) { extractZip(entry); return@setItems }
                    idx--
                }
                when (idx) {
                    0 -> confirmDelete(entry)
                    1 -> renameEntry(entry)
                    2 -> showChmodDialog(entry)
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
                    0 -> openInternalEditor(entry.path, runAsPackage)
                    1 -> openBinaryViewer(entry.path, runAsPackage)
                    2 -> openWithSystemChooser(entry, runAsPackage)
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
                    val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
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
                    val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
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
        if (currentPath.trimEnd('/') == rootPath.trimEnd('/')) {
            // ルートより上には行かない(runAsPackageブラウズ時は呼び出し元のアプリ一覧へ戻る)
            super.onBackPressed()
            return
        }
        val parent = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val rootTrimmed = rootPath.trimEnd('/')
        if (parent == "/" || parent.length >= rootTrimmed.length) {
            loadDirectory(parent)
        } else {
            loadDirectory(rootPath)
        }
    }

    companion object {
        private const val REQ_SHIZUKU = 1001

        const val EXTRA_ROOT_PATH = "extra_root_path"
        const val EXTRA_RUN_AS_PACKAGE = "extra_run_as_package"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_START_PATH = "extra_start_path"
    }
}
