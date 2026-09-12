// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.privfm.explorer.databinding.ActivitySettingsBinding
import com.privfm.explorer.shell.AdbShell
import com.privfm.explorer.shell.ShellMode
import com.privfm.explorer.shell.ShellManager
import com.privfm.explorer.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        when (ShellManager.preferredMode) {
            ShellMode.AUTO -> binding.modeAuto.isChecked = true
            ShellMode.SHIZUKU -> binding.modeShizuku.isChecked = true
            ShellMode.ROOT -> binding.modeRoot.isChecked = true
            ShellMode.ADB -> binding.modeAdb.isChecked = true
            ShellMode.NORMAL -> binding.modeNormal.isChecked = true
        }
        binding.adbSettingsGroup.visibility = if (ShellManager.preferredMode == ShellMode.ADB) View.VISIBLE else View.GONE

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                binding.modeShizuku.id -> ShellMode.SHIZUKU
                binding.modeRoot.id -> ShellMode.ROOT
                binding.modeAdb.id -> ShellMode.ADB
                binding.modeNormal.id -> ShellMode.NORMAL
                else -> ShellMode.AUTO
            }
            ShellManager.preferredMode = newMode
            binding.adbSettingsGroup.visibility = if (newMode == ShellMode.ADB) View.VISIBLE else View.GONE
        }

        binding.adbHostInput.setText(AppPreferences.adbHost)
        binding.adbPortInput.setText(if (AppPreferences.adbPort > 0) AppPreferences.adbPort.toString() else "")

        binding.adbPairButton.setOnClickListener {
            val host = binding.adbPairHostInput.text.toString().trim()
            val port = binding.adbPairPortInput.text.toString().trim().toIntOrNull()
            val code = binding.adbPairCodeInput.text.toString().trim()
            if (host.isEmpty() || port == null || port <= 0 || code.isEmpty()) {
                Toast.makeText(this, "IPアドレス・ポート・ペア設定コードを正しく入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.adbStatusView.text = "ペア設定中…"
            lifecycleScope.launch(Dispatchers.IO) {
                val result = AdbShell.pair(this@SettingsActivity, host, port, code)
                withContext(Dispatchers.Main) {
                    result.onSuccess {
                        binding.adbStatusView.text = "ペア設定に成功しました。続けて②の接続用IP/ポートを入力し、接続テストしてください。"
                    }.onFailure {
                        binding.adbStatusView.text = "ペア設定に失敗しました: ${it.message}"
                    }
                }
            }
        }

        binding.adbConnectTestButton.setOnClickListener {
            val host = binding.adbHostInput.text.toString().trim()
            val port = binding.adbPortInput.text.toString().trim().toIntOrNull()
            if (host.isEmpty() || port == null || port <= 0) {
                Toast.makeText(this, "IPアドレスとポート番号を正しく入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppPreferences.adbHost = host
            AppPreferences.adbPort = port
            binding.adbStatusView.text = "接続中…"
            lifecycleScope.launch(Dispatchers.IO) {
                val result = AdbShell.connect(this@SettingsActivity, host, port)
                withContext(Dispatchers.Main) {
                    result.onSuccess {
                        binding.adbStatusView.text = "接続成功: ADB経由でシェルを実行できる状態になりました"
                    }.onFailure {
                        binding.adbStatusView.text = "接続失敗: ${it.message}\n" +
                            "①のペア設定がまだの場合は、先にペア設定を行ってください。"
                    }
                }
            }
        }

        binding.switchShowHidden.isChecked = AppPreferences.showHiddenFiles
        binding.switchShowHidden.setOnCheckedChangeListener { _, checked ->
            AppPreferences.showHiddenFiles = checked
        }

        binding.switchConfirmDelete.isChecked = AppPreferences.confirmBeforeDelete
        binding.switchConfirmDelete.setOnCheckedChangeListener { _, checked ->
            AppPreferences.confirmBeforeDelete = checked
        }

        binding.switchBottomSheetMenus.isChecked = AppPreferences.useBottomSheetMenus
        binding.switchBottomSheetMenus.setOnCheckedChangeListener { _, checked ->
            AppPreferences.useBottomSheetMenus = checked
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
