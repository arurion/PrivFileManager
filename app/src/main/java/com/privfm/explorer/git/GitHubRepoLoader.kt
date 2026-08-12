// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

data class CloneRequest(
    val repoUrl: String,
    val destDir: File,
    val branch: String? = null,
    /** private repoやAPIレート制限緩和用のPersonal Access Token (任意) */
    val accessToken: String? = null
)

sealed class CloneProgress {
    data class Message(val text: String) : CloneProgress()
    data class Done(val localPath: String) : CloneProgress()
    data class Failed(val error: Throwable) : CloneProgress()
}

/**
 * ネイティブgitバイナリに依存せず、JGit(純Javaのgit実装)でGitHubリポジトリを
 * 端末内へclone/pullするローダー。cloneしたディレクトリはPrivilegedFileSystemで
 * そのまま閲覧・編集できる。
 */
object GitHubRepoLoader {

    fun clone(request: CloneRequest, onProgress: (CloneProgress) -> Unit) {
        try {
            if (request.destDir.exists() && request.destDir.list()?.isNotEmpty() == true) {
                onProgress(CloneProgress.Message("既存ディレクトリを更新(pull)します"))
                pull(request, onProgress)
                return
            }

            onProgress(CloneProgress.Message("clone 開始: ${request.repoUrl}"))

            val cmd = Git.cloneRepository()
                .setURI(request.repoUrl)
                .setDirectory(request.destDir)

            request.branch?.let { cmd.setBranch(it) }
            request.accessToken?.let {
                cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(it, ""))
            }

            cmd.call().use { git ->
                onProgress(CloneProgress.Done(git.repository.workTree.absolutePath))
            }
        } catch (e: Exception) {
            onProgress(CloneProgress.Failed(e))
        }
    }

    private fun pull(request: CloneRequest, onProgress: (CloneProgress) -> Unit) {
        try {
            Git.open(request.destDir).use { git ->
                val pullCmd = git.pull()
                request.accessToken?.let {
                    pullCmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(it, ""))
                }
                pullCmd.call()
                onProgress(CloneProgress.Done(git.repository.workTree.absolutePath))
            }
        } catch (e: Exception) {
            onProgress(CloneProgress.Failed(e))
        }
    }
}
