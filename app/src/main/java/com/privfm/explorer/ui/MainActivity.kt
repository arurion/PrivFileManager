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
import com.privfm.explorer.util.AppPreferences
import com.privfm.explorer.util.ExternalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import com.google.android.material.bottomsheet.BottomSheetDialog

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
    private var sortMode: SortMode = AppPreferences.sortMode
    private var sortAscending: Boolean = AppPreferences.sortAscending

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
        // ツールバー左上に「上のディレクトリへ戻る」矢印を表示する(AOSP DocumentsUIの
        // Upナビゲーション相当)。呼び出し元アプリへ戻る手段はシステムの戻るジェスチャー/
        // ボタン(onBackPressed)に委ねる。
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back)

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

        binding.fabCreate.setOnClickListener { showCreateChoiceMenu(it) }

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
            android.R.id.home -> { navigateUp(); true }
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
        val hiddenFiltered = if (AppPreferences.showHiddenFiles) currentEntries
        else currentEntries.filter { !it.name.startsWith(".") }
        val filtered = if (searchQuery.isBlank()) hiddenFiltered
        else hiddenFiltered.filter { it.name.contains(searchQuery, ignoreCase = true) }
        val sorted = sortEntries(filtered, sortMode, sortAscending)
        val withParent = buildParentEntry()?.let { listOf(it) + sorted } ?: sorted
        adapter.submitList(withParent)
    }

    /**
     * 現在地がブラウズ範囲のルート([rootPath])でなければ、一覧の先頭に表示する
     * 「上のディレクトリへ」の疑似エントリを作る。検索中は一覧が絞り込み結果を示す
     * ものになるため表示しない。
     */
    private fun buildParentEntry(): FileEntry? {
        if (searchQuery.isNotBlank()) return null
        val parentPath = parentDirectoryPath() ?: return null
        return FileEntry(
            name = "..",
            path = parentPath,
            isDirectory = true,
            isSymlink = false,
            sizeBytes = 0,
            permissions = "",
            owner = "",
            group = "",
            isParentEntry = true
        )
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
                AppPreferences.sortMode = sortMode
                AppPreferences.sortAscending = sortAscending
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
     * `ps` で取得した実行中プロセスの一覧のうち、debuggableなアプリだけを選択肢として表示し、
     * 選んだアプリのデータ領域を新しいブラウザとして開く。
     *
     * 以前はAccessibilityServiceで「今画面に表示されているアプリ」を自動検知する方式を
     * 試みたが、com.android.systemui のオーバーレイウィンドウ(ステータスバー等)まで
     * 拾ってしまい実用に耐えなかったため、この一覧選択方式に置き換えている。
     */
    private fun jumpToForegroundApp() {
        binding.loadingIndicator.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val shell = ShellManager.current()
            if (!shell.isAvailable()) {
                withContext(Dispatchers.Main) {
                    binding.loadingIndicator.visibility = android.view.View.GONE
                    Toast.makeText(this@MainActivity, "実行中プロセスの取得にはShizukuまたはRootが必要です", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val running = com.privfm.explorer.fs.RunningAppsHelper.listRunningApps(shell)
                .map { it.packageName }.toSet()
            val debuggableApps = com.privfm.explorer.fs.DebuggableAppHelper.listDebuggableApps(this@MainActivity)
            val candidates = debuggableApps.filter { it.packageName in running }

            withContext(Dispatchers.Main) {
                binding.loadingIndicator.visibility = android.view.View.GONE
                if (candidates.isEmpty()) {
                    Toast.makeText(this@MainActivity, "実行中のdebuggableアプリが見つかりませんでした", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                val labels = candidates.map { "${it.label} (${it.packageName})" }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.action_jump_foreground_app))
                    .setItems(labels) { _, which ->
                        val app = candidates[which]
                        val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                            putExtra(EXTRA_ROOT_PATH, app.dataDir)
                            putExtra(EXTRA_RUN_AS_PACKAGE, app.packageName)
                            putExtra(EXTRA_TITLE, app.label)
                        }
                        startActivity(intent)
                    }
                    .show()
            }
        }
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



    /**
     * 操作メニューを表示する共通ヘルパー。[AppPreferences.useBottomSheetMenus]の設定に応じて、
     * 画面中央のダイアログ / 下からせり出すボトムシートのどちらかを出し分ける。
     * (開発者個人の好みを一方的に押し付けず、設定で選べるようにするため)
     */
    private fun showActionMenu(title: String, items: List<String>, onSelect: (Int) -> Unit) {
        if (AppPreferences.useBottomSheetMenus) {
            val sheet = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.bottomsheet_action_menu, null)
            view.findViewById<android.widget.TextView>(R.id.actionSheetTitle).text = title
            val container = view.findViewById<android.widget.LinearLayout>(R.id.actionSheetItems)
            items.forEachIndexed { index, label ->
                val itemView = layoutInflater.inflate(R.layout.item_action_sheet, container, false)
                itemView.findViewById<android.widget.TextView>(R.id.actionItemText).text = label
                itemView.setOnClickListener {
                    sheet.dismiss()
                    onSelect(index)
                }
                container.addView(itemView)
            }
            sheet.setContentView(view)
            sheet.show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items.toTypedArray()) { _, which -> onSelect(which) }
                .show()
        }
    }

    /** FABタップ時に「新規フォルダ」「新規ファイル」を選ぶポップアップメニュー */
    private fun showCreateChoiceMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.action_new_folder)
        popup.menu.add(0, 2, 1, R.string.action_new_file)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showCreateDialog(isDirectory = true)
                2 -> showCreateDialog(isDirectory = false)
            }
            true
        }
        popup.show()
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
        if (entry.isParentEntry) return
        if (entry.isParentEntry) return
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
        val targets = currentEntries.filter { selectedPaths.contains(it.path) }
        val performDelete: () -> Unit = {
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
        if (!AppPreferences.confirmBeforeDelete) { performDelete(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("削除確認")
            .setMessage("$count 件を削除しますか?")
            .setPositiveButton("削除") { _, _ -> performDelete() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 圧縮先として選べる形式(RAR/7Zはライセンス上/仕様上、展開専用のため含めない) */
    private val compressibleFormats = listOf(
        "ZIP (.zip)" to ArchiveUtil.Format.ZIP,
        "TAR (.tar)" to ArchiveUtil.Format.TAR,
        "TAR.GZ (.tar.gz)" to ArchiveUtil.Format.TAR_GZ,
        "TAR.BZ2 (.tar.bz2)" to ArchiveUtil.Format.TAR_BZ2,
        "TAR.XZ (.tar.xz)" to ArchiveUtil.Format.TAR_XZ,
    )

    private fun compressSelected() {
        val targets = currentEntries.filter { selectedPaths.contains(it.path) }
        compressEntries(targets, afterStart = { setSelectionMode(false) })
    }

    /** 単一ファイル/フォルダの長押しメニューから直接圧縮する */
    private fun compressSingle(entry: FileEntry) {
        compressEntries(listOf(entry))
    }

    private fun compressEntries(targets: List<FileEntry>, afterStart: () -> Unit = {}) {
        if (targets.isEmpty()) return
        val labels = compressibleFormats.map { it.first }.toTypedArray()
        var selectedIndex = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_compress))
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("次へ") { _, _ ->
                val format = compressibleFormats[selectedIndex].second
                val defaultExt = when (format) {
                    ArchiveUtil.Format.ZIP -> ".zip"
                    ArchiveUtil.Format.TAR -> ".tar"
                    ArchiveUtil.Format.TAR_GZ -> ".tar.gz"
                    ArchiveUtil.Format.TAR_BZ2 -> ".tar.bz2"
                    ArchiveUtil.Format.TAR_XZ -> ".tar.xz"
                    else -> ".zip"
                }
                val defaultName = if (targets.size == 1) targets[0].name.substringBeforeLast('.', targets[0].name) else "archive"
                val input = android.widget.EditText(this).apply { setText("$defaultName$defaultExt") }
                MaterialAlertDialogBuilder(this)
                    .setTitle("ファイル名")
                    .setView(input)
                    .setPositiveButton("圧縮") { _, _ ->
                        val name = input.text.toString().trim().let { if (it.endsWith(defaultExt)) it else "$it$defaultExt" }
                        val destPath = "${currentPath.trimEnd('/')}/$name"
                        ensureNotificationPermission()
                        ClipboardHolder.archiveTargets = targets
                        val svcIntent = Intent(this, ArchiveService::class.java).apply {
                            putExtra(ArchiveService.EXTRA_ACTION, ArchiveService.ACTION_COMPRESS)
                            putExtra(ArchiveService.EXTRA_RUN_AS_PACKAGE, runAsPackage)
                            putExtra(ArchiveService.EXTRA_DEST_PATH, destPath)
                            putExtra(ArchiveService.EXTRA_REFRESH_PATH, currentPath)
                        }
                        startForegroundServiceCompat(svcIntent)
                        afterStart()
                        Toast.makeText(this, "バックグラウンドで圧縮を開始しました", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * アーカイブ展開。ZIP/TAR系/7Z/RARいずれも本アプリ内蔵の純Javaライブラリで直接展開する
     * (Apache Commons Compress / junrar。ネイティブツールへの依存やShizuku越しのPATH探索は不要)。
     * RARは読み取り専用として扱う(UnRARライセンス条項に基づく)。
     */
    private fun extractArchive(entry: FileEntry) {
        val format = ArchiveUtil.detectFormat(entry.name)
        if (format == null) {
            Toast.makeText(this, "未対応の書庫形式です", Toast.LENGTH_SHORT).show()
            return
        }
        val destDir = "${currentPath.trimEnd('/')}/${entry.name.substringBefore(".")}"
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
        if (entry.isParentEntry) { loadDirectory(entry.path); return }
        if (entry.isDirectory) {
            loadDirectory(entry.path)
            return
        }
        // 以前は拡張子から自動判定してテキストエディタ/外部アプリを問答無用で開いていたが、
        // 判定が外れると意図しないアプリが開いてしまい違和感があるため、
        // 常に「開き方」を確認するメニュー(旧・長押しメニューの一項目)を出すようにした。
        // 詳細な操作(削除・リネーム・圧縮・展開・パーミッション変更)は引き続き長押しに割り当てる。
        showOpenWithChooser(entry)
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
    private fun openWithSystemChooser(entry: FileEntry, runAsPkg: String?, mimeOverride: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPkg)

            // 特権経路(Shizuku/Root/run-as)で読んだファイルは、シェル側でbase64化してから
            // このアプリのプロセスメモリを経由してデコードする方式のため、あまりに巨大な
            // ファイルだとメモリ不足でアプリごと落ちてしまっていた。事前にサイズを確認し、
            // 安全に扱える範囲を超える場合は無理に読み込まず、理由を添えて中止する。
            val size = fs.fileSize(entry.path).getOrNull()
            if (size != null && size > MAX_EXTERNAL_OPEN_BYTES) {
                withContext(Dispatchers.Main) { showFileTooLargeDialog(entry.name, size) }
                return@launch
            }

            val mime = mimeOverride ?: ExternalOpener.mimeTypeFor(entry.name)
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
                    } catch (e: OutOfMemoryError) {
                        Toast.makeText(this@MainActivity, "メモリ不足のため開けませんでした(ファイルが大きすぎます)", Toast.LENGTH_LONG).show()
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

    private fun showFileTooLargeDialog(name: String, size: Long) {
        val sizeLabel = formatSizeHuman(size)
        MaterialAlertDialogBuilder(this)
            .setTitle("ファイルが大きすぎます")
            .setMessage(
                "$name は $sizeLabel あります。このアプリは特権アクセス(Shizuku/Root/run-as)経由の" +
                    "ファイルを、一旦アプリのメモリを通してから外部アプリへ渡す方式のため、" +
                    "あまりに大きなファイルは安全に開けません。\n\n" +
                    "Hexビューア(先頭のみ)での確認は可能です。"
            )
            .setPositiveButton("Hexビューアで確認") { _, _ -> openBinaryViewer(currentEntries.firstOrNull { it.name == name }?.path ?: return@setPositiveButton, runAsPackage) }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun formatSizeHuman(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return if (i < 0) "${bytes}B" else String.format("%.1f%s", value, units[i])
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
        if (entry.isParentEntry) return false
        if (selectionMode) { toggleSelection(entry); return true }
        // 「開き方」はタップ側のメニューに一本化したため、長押しメニューは
        // 削除・リネーム・圧縮・展開・パーミッション変更といった、より詳しい操作専用にする。
        val isArchive = !entry.isDirectory && ArchiveUtil.detectFormat(entry.name) != null
        val options = mutableListOf<String>()
        if (isArchive) options.add(getString(R.string.action_extract))
        options.add(getString(R.string.action_compress))
        options.addAll(listOf("削除", "リネーム", "パーミッションを変更"))

        showActionMenu(entry.name, options) { which ->
            var idx = which
            if (isArchive) {
                if (idx == 0) { extractArchive(entry); return@showActionMenu }
                idx--
            }
            when (idx) {
                0 -> compressSingle(entry)
                1 -> confirmDelete(entry)
                2 -> renameEntry(entry)
                3 -> showChmodDialog(entry)
            }
        }
        return true
    }

    /**
     * 「〇〇を開く」メニュー。以前はタップ時に拡張子から自動判定して問答無用で
     * (テキストエディタ or 外部アプリへ)強制的に開いていたが、判定が外れた場合に
     * 意図しないアプリが開いて違和感があるため、常にこのメニューで確認する方式に変更した。
     */
    private fun showOpenWithChooser(entry: FileEntry) {
        val options = listOf(
            "テキストとして開く",
            "Hexビューアで開く",
            "外部アプリで開く(標準機能)",
            "ファイル形式を指定して開く…"
        )
        showActionMenu(entry.name, options) { which ->
            when (which) {
                0 -> openInternalEditor(entry.path, runAsPackage)
                1 -> openBinaryViewer(entry.path, runAsPackage)
                2 -> openWithSystemChooser(entry, runAsPackage)
                3 -> showOpenAsMenu(entry)
            }
        }
    }

    /**
     * Fossify File Managerの「開き方を指定」機能を参考に、拡張子による自動判定を
     * 無視して「このファイルは実際には画像/動画/音声/PDF/APKのはずだ」と
     * ユーザー自身がMIMEタイプを明示的に選べるようにする。
     * (ファイル名に拡張子が無い、または実態と異なる拡張子がついている場合に有用)
     */
    private fun showOpenAsMenu(entry: FileEntry) {
        val categories = listOf(
            "テキスト" to "text/plain",
            "画像" to "image/*",
            "動画" to "video/*",
            "音声" to "audio/*",
            "PDF" to "application/pdf",
            "APK(パッケージ)" to "application/vnd.android.package-archive",
            "汎用(すべてのアプリから選択)" to "*/*"
        )
        showActionMenu("形式を指定して開く", categories.map { it.first }) { which ->
            val (label, mime) = categories[which]
            if (label == "テキスト") openInternalEditor(entry.path, runAsPackage)
            else openWithSystemChooser(entry, runAsPackage, mimeOverride = mime)
        }
    }

    private fun confirmDelete(entry: FileEntry) {
        val performDelete: () -> Unit = {
            lifecycleScope.launch(Dispatchers.IO) {
                val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
                val result = fs.delete(entry.path, recursive = entry.isDirectory)
                withContext(Dispatchers.Main) {
                    result.onSuccess { loadDirectory(currentPath) }
                        .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
                }
            }
        }
        if (!AppPreferences.confirmBeforeDelete) { performDelete(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("削除確認")
            .setMessage("${entry.name} を削除しますか?")
            .setPositiveButton("削除") { _, _ -> performDelete() }
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
        navigateUp()
    }

    /** 現在地の1つ上のディレクトリパスを求める(ルート未満には行かない)。ルートなら null */
    private fun parentDirectoryPath(): String? {
        if (currentPath.trimEnd('/') == rootPath.trimEnd('/')) return null
        val parent = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val rootTrimmed = rootPath.trimEnd('/')
        return if (parent == "/" || parent.length >= rootTrimmed.length) parent else rootPath
    }

    /**
     * 現在地の1つ上のディレクトリへ移動する(ツールバー左上のUpボタン、
     * および一覧先頭の".."行から共通で呼ばれる)。
     * ルート([rootPath])では、それより上へは行けない
     * (debuggableアプリのデータブラウズ時は特にアプリのデータ領域より上を見せる意味がない)ため、
     * 物理・ジェスチャーの「戻る」と同じ挙動(呼び出し元のアプリ一覧などへ戻る)に統一する。
     * 以前はルートで何も起きない「死んだボタン」になっていた。
     */
    private fun navigateUp() {
        val parent = parentDirectoryPath()
        if (parent != null) loadDirectory(parent) else onBackPressed()
    }

    companion object {
        private const val REQ_SHIZUKU = 1001

        /**
         * 特権経路でファイルを読む際、shell側でbase64化した文字列をアプリの
         * プロセスメモリに丸ごと保持してからデコードする都合上、あまりに大きい
         * ファイルはメモリ不足でアプリごと落ちる原因になっていた。
         * 安全マージンを見て80MBを上限とする(base64化で約1.33倍、デコード後の
         * バイト配列と合わせて瞬間的に元サイズの2倍以上のメモリを使うため)。
         */
        private const val MAX_EXTERNAL_OPEN_BYTES = 80L * 1024 * 1024

        const val EXTRA_ROOT_PATH = "extra_root_path"
        const val EXTRA_RUN_AS_PACKAGE = "extra_run_as_package"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_START_PATH = "extra_start_path"
    }
}
