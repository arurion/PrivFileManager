// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.privfm.explorer.databinding.ActivitySettingsBinding
import com.privfm.explorer.shell.ShellMode
import com.privfm.explorer.shell.ShellManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        when (ShellManager.preferredMode) {
            ShellMode.AUTO -> binding.modeAuto.isChecked = true
            ShellMode.SHIZUKU -> binding.modeShizuku.isChecked = true
            ShellMode.ROOT -> binding.modeRoot.isChecked = true
            ShellMode.NORMAL -> binding.modeNormal.isChecked = true
        }

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            ShellManager.preferredMode = when (checkedId) {
                binding.modeShizuku.id -> ShellMode.SHIZUKU
                binding.modeRoot.id -> ShellMode.ROOT
                binding.modeNormal.id -> ShellMode.NORMAL
                else -> ShellMode.AUTO
            }
        }

        // AccessibilityServiceはアプリから直接ON/OFFできないため、OSの設定画面へ誘導する。
        // (ユーザーが明示的に有効化しない限り、フォアグラウンドアプリ検知機能は一切動作しない)
        binding.openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
