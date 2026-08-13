// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.privfm.explorer.R
import com.privfm.explorer.fs.ArchiveUtil
import com.privfm.explorer.fs.ClipboardHolder
import com.privfm.explorer.fs.FileEntry
import com.privfm.explorer.fs.PrivilegedFileSystem
import com.privfm.explorer.shell.ShellManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 圧縮/展開のような時間のかかる操作をフォアグラウンドサービスとして実行する。
 * Activityがバックグラウンドに回っても処理がOSに殺されないようにし、
 * 進捗を通知で示す(POST_NOTIFICATIONS / FOREGROUND_SERVICE 権限を利用)。
 * 完了後はブロードキャストで呼び出し元に結果を通知する。
 */
class ArchiveService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("処理を実行しています…"))

        val action = intent?.getStringExtra(EXTRA_ACTION)
        val runAsPackage = intent?.getStringExtra(EXTRA_RUN_AS_PACKAGE)

        scope.launch {
            val fs = PrivilegedFileSystem(ShellManager.current(), runAsPackage)
            val result: Result<Unit> = when (action) {
                ACTION_COMPRESS -> {
                    val destZipPath = intent?.getStringExtra(EXTRA_DEST_PATH) ?: ""
                    val entries = ClipboardHolder.archiveTargets
                    ArchiveUtil.compress(fs, entries, destZipPath)
                }
                ACTION_EXTRACT -> {
                    val zipPath = intent?.getStringExtra(EXTRA_SOURCE_PATH) ?: ""
                    val destDir = intent?.getStringExtra(EXTRA_DEST_PATH) ?: ""
                    ArchiveUtil.extract(fs, zipPath, destDir)
                }
                else -> Result.failure(IllegalArgumentException("不明な操作: $action"))
            }

            val resultIntent = Intent(BROADCAST_ACTION).apply {
                setPackage(packageName)
                putExtra(EXTRA_SUCCESS, result.isSuccess)
                putExtra(EXTRA_MESSAGE, result.exceptionOrNull()?.message)
                putExtra(EXTRA_REFRESH_PATH, intent?.getStringExtra(EXTRA_REFRESH_PATH))
            }
            sendBroadcast(resultIntent)

            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(
                NOTIFICATION_ID,
                buildNotification(if (result.isSuccess) "完了しました" else "失敗しました: ${result.exceptionOrNull()?.message}")
            )
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PrivFileManager")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_file)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "ファイル操作", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        const val ACTION_COMPRESS = "compress"
        const val ACTION_EXTRACT = "extract"

        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_RUN_AS_PACKAGE = "extra_run_as_package"
        const val EXTRA_SOURCE_PATH = "extra_source_path"
        const val EXTRA_DEST_PATH = "extra_dest_path"
        const val EXTRA_REFRESH_PATH = "extra_refresh_path"

        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_MESSAGE = "extra_message"
        const val BROADCAST_ACTION = "com.privfm.explorer.ARCHIVE_RESULT"

        private const val CHANNEL_ID = "archive_ops"
        private const val NOTIFICATION_ID = 42
    }
}
