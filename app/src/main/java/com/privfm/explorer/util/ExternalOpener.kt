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

    /** バイト列をアプリキャッシュへ書き出し、外部アプリと共有可能なcontent:// URIを返す */
    fun cacheAndGetUri(context: Context, bytes: ByteArray, fileName: String): Uri {
        val dir = File(context.cacheDir, "open_with").apply { mkdirs() }
        // 同名ファイルの衝突を避けるため都度クリアしてから書き込む
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, fileName)
        target.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    /** システムの「開くアプリを選択」チューザーへ渡すIntentを組み立てる */
    fun buildChooserIntent(context: Context, uri: Uri, mimeType: String, title: String): Intent {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
