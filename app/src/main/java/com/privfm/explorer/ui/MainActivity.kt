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
import com.privfm.explorer.util.SafPathResolver
import com.privfm.explorer.util.TermuxIntegration
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
    private var searchMenuItem: android.view.MenuItem? = null
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
        // ツールバー左上のアイコンは、Amaze/Material Files/AOSP DocumentsUIと同様に
        // 常時「クイックアクセスを開くハンバーガー」の役割に統一する。
        // ディレクトリの階層移動(上へ)は一覧先頭の".."行に一本化し(既存機能)、
        // ここでは行わない。選択モード中のみ「選択解除」の×アイコンに切り替わる
        // (setSelectionMode内で制御)。
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)

        rootPath = intent.getStringExtra(EXTRA_ROOT_PATH) ?: rootPath
        runAsPackage = intent.getStringExtra(EXTRA_RUN_AS_PACKAGE)
        rootLabel = intent.getStringExtra(EXTRA_TITLE) ?: rootLabel
        // ルート自体は"/"(全ファイルシステム)のままにしつつ、初期表示は使いやすい
        // ストレージ領域から始める。EXTRA_START_PATHで明示的に上書き可能。
        currentPath = intent.getStringExtra(EXTRA_START_PATH)
            ?: if (runAsPackage == null && rootPath == "/") "/storage/emulated/0" else rootPath
        // GET_CONTENT/PICK/CREATE_DOCUMENTで他アプリから呼び出された場合(EXTRA_ROOT_PATHは
        // 渡らない)、rootPathを"/"のままにしていると、実際の初期表示
        // ("/storage/emulated/0")との食い違いにより、戻るボタンを押した際に
        // 本来の起点を通り越して延々と上のディレクトリへ遡ってしまい、通常権限では
        // 権限不足で一覧取得に失敗するディレクトリに突き当たって「戻れなくなる」
        // 不具合があった。ピッカー系の呼び出しに限り、実際に表示を始めるディレクトリを
        // そのままrootPathとして扱う(アプリを通常起動した場合は、これまで通り
        // ファイルシステム全体を自由に行き来できるようにするため、他の起動経路では変更しない)。
        if (isPickMode || isCreateDocumentMode) {
            rootPath = currentPath
        }
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)

        // 他アプリからGET_CONTENT/PICK/CREATE_DOCUMENTで呼び出された場合は「選択モード」になる。
        // Fossify File Manager等の実装(独自のDocumentsProviderではなく、アプリ自身をこれらの
        // intent-filterで受ける方式)を参考にした。詳細はNOTICE.mdを参照。
        if (isPickMode) {
            supportActionBar?.subtitle = "ファイルを選択してください"
        } else if (isCreateDocumentMode) {
            supportActionBar?.subtitle = "保存先のフォルダを選んで下さい"
        }

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

        setupNavigationDrawer()

        binding.fabCreate.setOnClickListener {
            if (isCreateDocumentMode) createDocumentAndReturn() else showCreateChoiceMenu(it)
        }

        refreshStatusBar()
        if (runAsPackage == null) maybeRequestAllFilesAccess()
        loadDirectory(currentPath)
    }

    /** GET_CONTENT/PICK: ファイルをタップしたら開かず、呼び出し元アプリへ選択結果を返す */
    private val isPickMode: Boolean by lazy {
        intent.action == Intent.ACTION_GET_CONTENT || intent.action == Intent.ACTION_PICK
    }

    /** CREATE_DOCUMENT: 「名前を付けて保存」。フォルダを選んでFABから保存する */
    private val isCreateDocumentMode: Boolean by lazy {
        intent.action == Intent.ACTION_CREATE_DOCUMENT
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
            searchMenuItem = searchItem
            val searchView = searchItem?.actionView as? SearchView
            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    searchQuery = newText.orEmpty()
                    applyFilterAndSort()
                    return true
                }
            })
            // 選択モードへ出入りするとinvalidateOptionsMenu()で検索メニュー自体が
            // 作り直され、以前入力していた検索語がそのまま残っているにもかかわらず、
            // SearchViewは折りたたまれた「検索していない」見た目で再生成されてしまい、
            // 閉じる手段(×ボタン)も無いまま検索状態から抜け出せなくなるバグがあった。
            // 検索語が残っている場合は展開状態を復元し、ユーザーが続きを確認・
            // クリアできるようにする。
            if (searchQuery.isNotBlank()) {
                searchItem.expandActionView()
                searchView?.setQuery(searchQuery, false)
            }
            // SearchViewの折りたたみ(端末の戻るボタン・×ボタン経由)を検知して
            // 検索状態を確実にリセットする。これが無いと「検索を閉じたつもりが
            // 実は検索語だけが残ったまま」という状態になり得る。
            searchItem?.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: android.view.MenuItem): Boolean = true
                override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean {
                    searchQuery = ""
                    applyFilterAndSort()
                    return true
                }
            })
        } else {
            supportActionBar?.title = "${selectedPaths.size}件"
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
            R.id.action_sort -> { showSortDialog(); true }
            R.id.action_new_folder -> { showCreateDialog(isDirectory = true); true }
            R.id.action_new_file -> { showCreateDialog(isDirectory = false); true }
            R.id.action_paste -> { pasteClipboard(); true }
            R.id.action_selection_mode -> { setSelectionMode(true); true }
            R.id.action_delete_selected -> { confirmDeleteSelected(); true }
            R.id.action_selection_more -> { showSelectionMoreMenu(); true }
            android.R.id.home -> {
                if (selectionMode) setSelectionMode(false) else binding.drawerLayout.openDrawer(binding.navigationView)
                true
            }
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

    /**
     * クイックアクセス(内部ストレージ・SAFブックマーク・特殊な操作)をナビゲーション
     * ドロワーへ組み込む。Amaze File Manager / Material Files / AOSP DocumentsUIと
     * 同様、これらの導線をオーバーフローメニューの奥に埋めず、スワイプまたは
     * ハンバーガーアイコンでいつでも開けるようにする。
     *
     * NavigationViewのメニューは、SAFブックマークが実行時に増減するため、
     * 静的なmenu XMLではなくコードから都度組み立て直す。
     */
    private fun setupNavigationDrawer() {
        binding.navigationView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawers()
            when (item.itemId) {
                NAV_ID_ADD_SAF -> launchSafTreePicker()
                NAV_ID_GO_TO_PATH -> showGoToPathDialog()
                NAV_ID_JUMP_FOREGROUND -> jumpToForegroundApp()
                NAV_ID_TERMUX -> openInTermux(currentPath)
                NAV_ID_APP_DATA -> startActivity(Intent(this, AppDataBrowserActivity::class.java))
                NAV_ID_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> {
                    val path = navPlacePaths[item.itemId]
                    val bookmarkPath = navBookmarkPaths[item.itemId]
                    when {
                        path != null -> loadDirectory(path)
                        bookmarkPath != null -> loadDirectory(bookmarkPath)
                    }
                }
            }
            true
        }
        rebuildNavigationDrawerMenu()
    }

    private val navPlacePaths = mutableMapOf<Int, String>()
    private val navBookmarkPaths = mutableMapOf<Int, String>()

    private fun rebuildNavigationDrawerMenu() {
        val menu = binding.navigationView.menu
        menu.clear()
        navPlacePaths.clear()
        navBookmarkPaths.clear()

        val placesGroup = menu.addSubMenu("よく使う場所")
        val places = linkedMapOf(
            "内部ストレージ" to "/storage/emulated/0",
            "Download" to "/storage/emulated/0/Download",
            "Android/data" to "/storage/emulated/0/Android/data",
            "デバイスのルート (/)" to "/",
            "/system" to "/system",
            "/data" to "/data"
        )
        var nextId = NAV_ID_PLACES_BASE
        for ((label, path) in places) {
            val id = nextId++
            placesGroup.add(0, id, 0, label).setIcon(R.drawable.ic_folder)
            navPlacePaths[id] = path
        }

        // SAF(ACTION_OPEN_DOCUMENT_TREE)経由で追加したブックマーク
        // (SDカード等、通常権限だけではアクセスできない領域向け)
        val bookmarks = AppPreferences.safBookmarks
        if (bookmarks.isNotEmpty()) {
            val bookmarkGroup = menu.addSubMenu("外部ストレージ")
            var bookmarkId = NAV_ID_BOOKMARKS_BASE
            for ((name, path) in bookmarks) {
                val id = bookmarkId++
                bookmarkGroup.add(0, id, 0, name).setIcon(R.drawable.ic_folder)
                navBookmarkPaths[id] = path
            }
        }
        menu.add(0, NAV_ID_ADD_SAF, 0, "＋ 外部ストレージを追加").setIcon(R.drawable.ic_add)

        val toolsGroup = menu.addSubMenu("ツール")
        toolsGroup.add(0, NAV_ID_GO_TO_PATH, 0, "パスを指定して移動")
        toolsGroup.add(0, NAV_ID_JUMP_FOREGROUND, 0, getString(R.string.action_jump_foreground_app))
        toolsGroup.add(0, NAV_ID_APP_DATA, 0, getString(R.string.menu_app_data))
        toolsGroup.add(0, NAV_ID_TERMUX, 0, getString(R.string.action_open_termux))
        menu.add(0, NAV_ID_SETTINGS, 0, getString(R.string.menu_settings)).setIcon(R.drawable.ic_close)
    }

    /**
     * SAF(ACTION_OPEN_DOCUMENT_TREE)でフォルダを選んでもらい、実際の絶対パスを
     * 推定してクイックアクセスに追加する。詳細は[SafPathResolver]のコメントを参照。
     * SAFの許可自体は「実パスを特定する」ためだけに使い、以降のファイル操作は
     * 通常通り特権シェル経由で行う(SAFのcontent://を経由し続けるわけではない)。
     */
    private fun launchSafTreePicker() {
        safTreePickerLauncher.launch(null)
    }

    private val safTreePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // 永続化に失敗しても、今回のパス推定自体は試す
        }
        val resolvedPath = SafPathResolver.resolveTreeUriToPath(this, uri)
        if (resolvedPath == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("パスを特定できませんでした")
                .setMessage("選択された場所の実際の保存先を特定できませんでした。お使いの端末では、この方法での外部ストレージ追加に対応していない可能性があります。")
                .setPositiveButton("OK", null)
                .show()
            return@registerForActivityResult
        }
        val input = android.widget.EditText(this).apply { setText(resolvedPath.substringAfterLast('/')) }
        MaterialAlertDialogBuilder(this)
            .setTitle("ブックマーク名")
            .setMessage("推定した保存先: $resolvedPath")
            .setView(input)
            .setPositiveButton("追加") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { resolvedPath.substringAfterLast('/') }
                AppPreferences.addSafBookmark(name, resolvedPath)
                rebuildNavigationDrawerMenu()
                loadDirectory(resolvedPath)
            }
            .setNegativeButton("キャンセル", null)
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

    /**
     * 「コピー/切り取り/削除/全選択/反転/圧縮/キャンセル」を全てツールバーの
     * 文字ラベルで並べると窮屈になっていたため、削除だけをアイコンで常時表示し、
     * 残りは「その他(⋮)」から確認できるメニュー(ダイアログ/ボトムシート)にまとめた。
     */
    private fun showSelectionMoreMenu() {
        val options = listOf(
            getString(R.string.action_select_all),
            getString(R.string.action_invert_selection),
            getString(R.string.action_copy),
            getString(R.string.action_cut),
            getString(R.string.action_compress),
        )
        showActionMenu("${selectedPaths.size}件選択中", options) { which ->
            when (which) {
                0 -> selectAll()
                1 -> invertSelection()
                2 -> copySelectedToClipboard(ClipboardHolder.Mode.COPY)
                3 -> copySelectedToClipboard(ClipboardHolder.Mode.CUT)
                4 -> compressSelected()
            }
        }
    }

    private fun selectableEntries(): List<FileEntry> = currentEntries.filter { !it.isParentEntry }

    private fun selectAll() {
        selectedPaths.clear()
        selectedPaths.addAll(selectableEntries().map { it.path })
        if (selectedPaths.isEmpty()) { setSelectionMode(false); return }
        supportActionBar?.title = "${selectedPaths.size}件"
        adapter.notifyDataSetChanged()
    }

    private fun invertSelection() {
        val all = selectableEntries().map { it.path }
        val inverted = all.filterNot { selectedPaths.contains(it) }
        selectedPaths.clear()
        selectedPaths.addAll(inverted)
        if (selectedPaths.isEmpty()) { setSelectionMode(false); return }
        supportActionBar?.title = "${selectedPaths.size}件"
        adapter.notifyDataSetChanged()
    }

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedPaths.clear()
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
        // 以前は "5件選択中" のような長い文言をタイトルに詰め込んでいたため、
        // 戻る矢印やメニューアイコンと合わせて表示スペースが狭く文字が潰れていた。
        // 選択モードであること自体はナビゲーションアイコンを「閉じる(×)」に
        // 差し替えることで示し、タイトルは短い件数表示のみにする。
        if (enabled) {
            supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close)
        } else {
            supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        supportActionBar?.title = if (enabled) "${selectedPaths.size}件" else (intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name))
    }

    private fun toggleSelection(entry: FileEntry) {
        if (entry.isParentEntry) return
        if (!selectionMode) setSelectionMode(true)
        if (selectedPaths.contains(entry.path)) selectedPaths.remove(entry.path) else selectedPaths.add(entry.path)
        if (selectedPaths.isEmpty()) {
            // 選択数が0になったら、選択モード表示だけが空虚に残らないよう自動で解除する
            setSelectionMode(false)
        } else {
            supportActionBar?.title = "${selectedPaths.size}件"
            adapter.notifyDataSetChanged()
        }
    }

    /** 検索語をクリアし、一覧を通常表示に戻す(検索結果に対する操作が完了した後などに呼ぶ) */
    private fun clearSearch() {
        if (searchQuery.isBlank()) return
        searchQuery = ""
        applyFilterAndSort()
        invalidateOptionsMenu()
    }

    private fun copySelectedToClipboard(mode: ClipboardHolder.Mode) {
        val entries = currentEntries.filter { selectedPaths.contains(it.path) }
            .map { ClipboardHolder.Entry(it.path, it.name, it.isDirectory) }
        ClipboardHolder.set(entries, mode)
        setSelectionMode(false)
        clearSearch()
        showClipboardReadySnackbar(entries.size, mode)
    }

    /**
     * コピー/切り取り後、Amaze File Manager等の一般的なファイルマネージャーと同様、
     * 一瞬で消えるToastだけでなく「あと何件貼り付け可能か」が分かる、押すまで
     * 消えないSnackbarを表示する(「貼り付け」「取消」ボタン付き)。
     * 以前はToastのみで、コピー内容を後から確認・取り消す手段が無かった。
     */
    private fun showClipboardReadySnackbar(count: Int, mode: ClipboardHolder.Mode) {
        val verb = if (mode == ClipboardHolder.Mode.CUT) "切り取り" else "コピー"
        com.google.android.material.snackbar.Snackbar
            .make(binding.fileListView, "$count 件を${verb}しました。貼り付け先のフォルダへ移動してください", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAction("貼り付け") { pasteClipboard() }
            .setActionTextColor(getColor(R.color.chrome_tint))
            .show()
    }

    private fun pasteClipboard() {
        if (ClipboardHolder.isEmpty()) return
        val items = ClipboardHolder.items
        val cutMode = ClipboardHolder.mode == ClipboardHolder.Mode.CUT
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            var okCount = 0
            for (item in items) {
                // 貼り付け先に同名のファイル/フォルダが既にある場合、多くのファイル
                // マネージャー(Amaze等)と同様に無言で上書きするのではなく、
                // "name (1)"のように自動的にリネームして衝突を避ける。
                val dest = resolveUniqueDestination(fs, currentPath, item.name)
                val result = if (cutMode) fs.rename(item.path, dest) else fs.copy(item.path, dest)
                if (result.isSuccess) okCount++
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "$okCount / ${items.size} 件を貼り付けました", Toast.LENGTH_SHORT).show()
                if (cutMode) ClipboardHolder.clear()
                invalidateOptionsMenu()
                loadDirectory(currentPath)
            }
        }
    }

    /**
     * 貼り付け先に同名のエントリが既にある場合、"name (1).ext"のように
     * 末尾へ連番を振って衝突しないパスを返す(拡張子はできるだけ保持する)。
     */
    private fun resolveUniqueDestination(fs: PrivilegedFileSystem, destDir: String, originalName: String): String {
        val baseDir = destDir.trimEnd('/')
        var candidate = "$baseDir/$originalName"
        if (!fs.exists(candidate)) return candidate
        val dotIndex = originalName.lastIndexOf('.')
        val hasExt = dotIndex > 0
        val stem = if (hasExt) originalName.substring(0, dotIndex) else originalName
        val ext = if (hasExt) originalName.substring(dotIndex) else ""
        var counter = 1
        do {
            candidate = "$baseDir/$stem ($counter)$ext"
            counter++
        } while (fs.exists(candidate) && counter < 1000)
        return candidate
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
        if (entry.isParentEntry) {
            // 矢印ボタン(navigateUp)と挙動を統一: 選択中なら解除した上でそのまま移動する
            if (selectionMode) setSelectionMode(false)
            loadDirectory(entry.path)
            return
        }
        if (entry.isDirectory) {
            loadDirectory(entry.path)
            return
        }
        if (isPickMode) { pickedPath(entry); return }
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

    /**
     * GET_CONTENT/PICKで呼び出された場合の「ファイルを選択した」処理。
     * Fossify File Manager等と同じ方式で、選択したファイルを直接開かず、
     * content:// URIを結果として呼び出し元アプリへ返す。
     * 特権経路(Shizuku/Root/run-as)配下のファイルは、呼び出し元アプリから
     * 直接アクセスできないため、一旦アプリのキャッシュへコピーしてから共有する
     * (「外部アプリで開く」と同じ仕組みを再利用)。
     */
    private fun pickedPath(entry: FileEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val size = fs.fileSize(entry.path).getOrNull()
            if (size != null && size > MAX_EXTERNAL_OPEN_BYTES) {
                withContext(Dispatchers.Main) { showFileTooLargeDialog(entry.name, size) }
                return@launch
            }
            val result = fs.readFile(entry.path)
            withContext(Dispatchers.Main) {
                result.onSuccess { bytes ->
                    try {
                        val cacheFile = ExternalOpener.cacheFile(this@MainActivity, bytes, entry.name)
                        val uri = ExternalOpener.uriForCacheFile(this@MainActivity, cacheFile)
                        val resultIntent = Intent().apply {
                            data = uri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "選択に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }.onFailure {
                    Toast.makeText(this@MainActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * CREATE_DOCUMENTで呼び出された場合の「名前を付けて保存」処理。
     * 呼び出し元アプリが以後この場所へ直接書き込めるよう、キャッシュ経由ではなく
     * 実ファイルへ直接のcontent:// URIを発行する(file_paths.xmlのroot-path設定により可能)。
     * このため、privileged専用領域(Shizuku/Root配下のみアクセス可能な場所)では
     * 通常のFileオブジェクトから直接アクセスできず失敗するため、
     * 通常アクセス可能な保存先(外部ストレージ等)でのみ機能する。
     */
    private fun createDocumentAndReturn() {
        val suggested = intent.getStringExtra(Intent.EXTRA_TITLE) ?: "new_file.txt"
        val input = android.widget.EditText(this).apply { setText(suggested) }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存するファイル名")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val targetPath = "${currentPath.trimEnd('/')}/$name"
                lifecycleScope.launch(Dispatchers.IO) {
                    val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
                    val result = fs.writeFile(targetPath, ByteArray(0))
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            try {
                                val file = java.io.File(targetPath)
                                val uri = ExternalOpener.uriForCacheFile(this@MainActivity, file)
                                val resultIntent = Intent().apply {
                                    data = uri
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                setResult(RESULT_OK, resultIntent)
                                finish()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "この場所には保存できません(特権専用領域の可能性があります): ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.onFailure {
                            Toast.makeText(this@MainActivity, "保存に失敗しました: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
        // 削除・リネーム・圧縮・展開・パーミッション変更・コピー/切り取りといった、
        // より詳しい操作専用にする。
        // (以前は選択モードにしてからでないとコピー/切り取りができず、単一ファイルへの
        // よく行う操作としては手数が多かったため、長押しからも直接行えるようにした)
        //
        // ラベルと処理をペアの配列で管理し、以前あったような「オプションの並びを
        // 変えたら分岐のインデックスがずれる」事故を防ぐ。
        val isArchive = !entry.isDirectory && ArchiveUtil.detectFormat(entry.name) != null
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (isArchive) actions.add(getString(R.string.action_extract) to { extractArchive(entry) })
        actions.add(getString(R.string.action_compress) to { compressSingle(entry) })
        actions.add(getString(R.string.action_copy) to { copySingleToClipboard(entry, ClipboardHolder.Mode.COPY) })
        actions.add(getString(R.string.action_cut) to { copySingleToClipboard(entry, ClipboardHolder.Mode.CUT) })
        if (entry.isDirectory) actions.add("Termuxで開く" to { openInTermux(entry.path) })
        actions.add("削除" to { confirmDelete(entry) })
        actions.add("リネーム" to { renameEntry(entry) })
        actions.add("パーミッションを変更" to { showChmodDialog(entry) })

        showActionMenu(entry.name, actions.map { it.first }) { which ->
            actions[which].second.invoke()
        }
        return true
    }

    /** 単一ファイル/フォルダを直接クリップボードへコピー/切り取りする(長押しメニューから) */
    private fun copySingleToClipboard(entry: FileEntry, mode: ClipboardHolder.Mode) {
        ClipboardHolder.set(listOf(ClipboardHolder.Entry(entry.path, entry.name, entry.isDirectory)), mode)
        invalidateOptionsMenu()
        showClipboardReadySnackbar(1, mode)
    }

    /**
     * 現在のディレクトリ、または長押ししたフォルダをTermuxのターミナルで開く。
     * Termuxが入っていなければ公式配布ページへ誘導し、権限が無ければ設定を促す。
     */
    private fun openInTermux(path: String) {
        if (!TermuxIntegration.isTermuxInstalled(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Termuxが見つかりません")
                .setMessage("Termuxをインストールすると、このフォルダをターミナルで直接開けます。")
                .setPositiveButton("ダウンロードページを開く") { _, _ -> TermuxIntegration.openTermuxDownloadPage(this) }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        if (!TermuxIntegration.hasRunCommandPermission(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("権限が必要です")
                .setMessage(
                    "Termux連携には com.termux.permission.RUN_COMMAND 権限が必要です。" +
                        "端末の設定アプリからこのアプリの権限一覧を開き、許可してください。\n\n" +
                        "また、Termux側で ~/.termux/termux.properties に " +
                        "allow-external-apps=true を追記し、termux-reload-settings を" +
                        "実行しておく必要があります。"
                )
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        try {
            TermuxIntegration.openTermuxHere(this, path)
        } catch (e: Exception) {
            Toast.makeText(this, "Termuxの起動に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    /**
     * 戻るキーの優先順位は、AOSP DocumentsUIの実際のソースコード
     * (`SharedInputHandler.onBack()`)で確認した順序に合わせている:
     * ドロワーが開いていれば閉じる → 検索中なら検索をキャンセル →
     * 選択中なら選択解除 → それ以外はディレクトリを1つ上へ(ルートならアプリを抜ける)。
     * 以前は「検索をキャンセルする」段階が抜けており、検索中に選択モードへ入って
     * しまうと検索状態が宙に浮いたまま残ってしまう不具合の一因になっていた。
     */
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(binding.navigationView)) {
            binding.drawerLayout.closeDrawers()
            return
        }
        if (searchMenuItem?.isActionViewExpanded == true) {
            searchMenuItem?.collapseActionView()
            return
        }
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
     *
     * 選択中にこのボタンを押した場合、選択内容と移動先のディレクトリが食い違ったまま
     * 残ってしまう事故を防ぐため、選択を解除した上で移動を続行する
     * (選択解除だけでその場に留まる仕様だと、もう一度押さないと移動できず不便なため)。
     */
    private fun navigateUp() {
        if (selectionMode) setSelectionMode(false)
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

        // ナビゲーションドロワーのメニューID(動的に組み立てるため、
        // menu XMLではなくここで採番する)
        private const val NAV_ID_ADD_SAF = 9001
        private const val NAV_ID_GO_TO_PATH = 9002
        private const val NAV_ID_JUMP_FOREGROUND = 9003
        private const val NAV_ID_TERMUX = 9004
        private const val NAV_ID_APP_DATA = 9005
        private const val NAV_ID_SETTINGS = 9006
        private const val NAV_ID_PLACES_BASE = 9100
        private const val NAV_ID_BOOKMARKS_BASE = 9200
    }
}
