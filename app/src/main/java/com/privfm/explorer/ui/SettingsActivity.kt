// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.privfm.explorer.databinding.ActivitySettingsBinding
import com.privfm.explorer.shell.ShellMode
import com.privfm.explorer.shell.ShellManager
import com.privfm.explorer.util.AppPreferences

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

        binding.switchShowHidden.isChecked = AppPreferences.showHiddenFiles
        binding.switchShowHidden.setOnCheckedChangeListener { _, checked ->
            AppPreferences.showHiddenFiles = checked
        }

        binding.switchConfirmDelete.isChecked = AppPreferences.confirmBeforeDelete
        binding.switchConfirmDelete.setOnCheckedChangeListener { _, checked ->
            AppPreferences.confirmBeforeDelete = checked
        }
    }
}
