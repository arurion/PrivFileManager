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
                        onLongClick = { true }
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
        } else {
            val intent = Intent(this, TextEditorActivity::class.java).apply {
                putExtra(TextEditorActivity.EXTRA_PATH, entry.path)
                putExtra(TextEditorActivity.EXTRA_RUN_AS_PACKAGE, app.packageName)
            }
            startActivity(intent)
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
