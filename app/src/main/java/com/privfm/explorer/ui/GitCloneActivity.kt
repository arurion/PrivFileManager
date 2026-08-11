package com.privfm.explorer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.privfm.explorer.databinding.ActivityGitCloneBinding
import com.privfm.explorer.git.CloneProgress
import com.privfm.explorer.git.CloneRequest
import com.privfm.explorer.git.GitHubRepoLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GitCloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGitCloneBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGitCloneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cloneButton.setOnClickListener { startClone() }
    }

    private fun startClone() {
        val url = binding.repoUrlInput.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            binding.cloneStatus.text = "リポジトリURLを入力してください"
            return
        }
        val branch = binding.branchInput.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
        val token = binding.tokenInput.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }

        val repoName = url.substringAfterLast('/').removeSuffix(".git")
        val destDir = File(getExternalFilesDir(null), "repos/$repoName")
        destDir.mkdirs()

        binding.cloneStatus.text = "開始しています..."

        lifecycleScope.launch(Dispatchers.IO) {
            GitHubRepoLoader.clone(
                CloneRequest(repoUrl = url, destDir = destDir, branch = branch, accessToken = token)
            ) { progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    when (progress) {
                        is CloneProgress.Message -> binding.cloneStatus.text = progress.text
                        is CloneProgress.Done -> binding.cloneStatus.text = "完了: ${progress.localPath}"
                        is CloneProgress.Failed -> binding.cloneStatus.text = "失敗: ${progress.error.message}"
                    }
                }
            }
        }
    }
}
