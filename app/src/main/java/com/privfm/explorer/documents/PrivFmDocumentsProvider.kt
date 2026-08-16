// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.documents

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.privfm.explorer.R
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * 他のOSSファイルマネージャー(Amaze / Fossify File Manager等)と同様、
 * 他のアプリがAndroid標準の「ファイルを開く/保存する」ダイアログ(システムの
 * DocumentsUI)を呼び出した際、その左メニューに本アプリが並ぶようにするための
 * DocumentsProvider実装。
 *
 * Shizuku/Root/`run-as`経由の特権アクセスは、DocumentsProviderの同期的な
 * Cursor/ParcelFileDescriptorベースのAPIとは相性が悪い(シェルコマンドの
 * 結果を都度同期的に返す必要があり、実装・パフォーマンス両面でリスクが大きい)ため、
 * ここでは通常のjava.io.Fileで直接アクセスできる範囲(外部ストレージ、
 * 本アプリがMANAGE_EXTERNAL_STORAGE/READ・WRITE権限を持つ範囲)のみを公開する。
 * これは他の主要ファイルマネージャーの実装方針とも一致する。
 */
class PrivFmDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "primary"
        private const val ROOT_DOC_ID = "root:"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        private fun getBaseDir(): File = Environment.getExternalStorageDirectory()
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, context?.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context?.getString(R.string.documents_provider_summary))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
            )
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(cursor, documentId, getFileForDocId(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        parent.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }?.forEach { child ->
            includeFile(cursor, getDocIdForFile(child), child)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? = null

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = getFileForDocId(parentDocumentId)
        val safeName = sanitizeFileName(displayName)
        val target = File(parent, safeName)
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("作成に失敗しました: $safeName")
        return getDocIdForFile(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) throw FileNotFoundException("削除に失敗しました: ${file.path}")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = getFileForDocId(documentId)
        val target = File(file.parentFile, sanitizeFileName(displayName))
        if (!file.renameTo(target)) throw FileNotFoundException("リネームに失敗しました: ${file.path}")
        return getDocIdForFile(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return getFileForDocId(documentId).path.startsWith(getFileForDocId(parentDocumentId).path)
    }

    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return if (file.isDirectory) Document.MIME_TYPE_DIR else mimeTypeFor(file.name)
    }

    // ---- ヘルパー ----

    private fun getDocIdForFile(file: File): String {
        val basePath = getBaseDir().path
        val filePath = file.path
        val relative = if (filePath == basePath) "" else filePath.removePrefix("$basePath/")
        return ROOT_DOC_ID + relative
    }

    private fun getFileForDocId(documentId: String): File {
        val relative = documentId.removePrefix(ROOT_DOC_ID)
        return if (relative.isEmpty()) getBaseDir() else File(getBaseDir(), relative)
    }

    private fun sanitizeFileName(name: String): String = name.replace('/', '_').trim()

    private fun mimeTypeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun includeFile(cursor: MatrixCursor, docId: String, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        val mimeType = if (file.isDirectory) Document.MIME_TYPE_DIR else mimeTypeFor(file.name)
        val displayName = if (docId == ROOT_DOC_ID) getBaseDir().name else file.name
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }
}
