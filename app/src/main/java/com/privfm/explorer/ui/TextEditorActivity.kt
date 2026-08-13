// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.editorToolbar)

        path = intent.getStringExtra(EXTRA_PATH) ?: ""
        runAsPackage = intent.getStringExtra(EXTRA_RUN_AS_PACKAGE)
        supportActionBar?.title = path.substringAfterLast('/')

        loadFile()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_save) {
            saveFile()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadFile() {
        lifecycleScope.launch(Dispatchers.IO) {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val result = fs.readTextFile(path)
            withContext(Dispatchers.Main) {
                result.onSuccess { binding.editorText.setText(it) }
                    .onFailure { Toast.makeText(this@TextEditorActivity, "読み込み失敗: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
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
    }
}
