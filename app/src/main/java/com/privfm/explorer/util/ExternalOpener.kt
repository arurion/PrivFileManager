// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * 「〇〇で開く」のように、他の一般的なファイルマネージャーと同様に
 * Android標準のACTION_VIEW + アプリ選択(chooser)へ処理を委譲するためのヘルパー。
 *
 * Shizuku/Root/run-as経由で読んだファイルは、このアプリの通常プロセスから
 * 直接は参照できない(特権シェル配下にあるため)。そのため一旦アプリの
 * キャッシュ領域へコピーし、そのコピーをFileProvider経由で外部アプリに共有する。
 */
object ExternalOpener {

    fun mimeTypeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** バイト列をアプリキャッシュへ書き出し、そのFileオブジェクトを返す(書き戻し検知用にmtimeを見るため) */
    fun cacheFile(context: Context, bytes: ByteArray, fileName: String): File {
        val dir = File(context.cacheDir, "open_with").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, fileName)
        target.writeBytes(bytes)
        return target
    }

    fun uriForCacheFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** バイト列をアプリキャッシュへ書き出し、外部アプリと共有可能なcontent:// URIを返す */
    fun cacheAndGetUri(context: Context, bytes: ByteArray, fileName: String): Uri =
        uriForCacheFile(context, cacheFile(context, bytes, fileName))

    /**
     * システムの「開くアプリを選択」チューザーへ渡すIntentを組み立てる。
     * 編集して保存し直せるよう、読み取りだけでなく書き込み権限も付与しておく
     * (対応アプリがContentResolver経由で書き戻せば、キャッシュ側ファイルが更新される)。
     */
    fun buildChooserIntent(context: Context, uri: Uri, mimeType: String, title: String): Intent {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return Intent.createChooser(viewIntent, title)
    }

    fun canResolve(context: Context, uri: Uri, mimeType: String): Boolean {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return viewIntent.resolveActivity(context.packageManager) != null
    }
}
