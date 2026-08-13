// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ファイルの圧縮(ZIP作成)・展開(ZIP展開)。
 *
 * Shizuku/Root/run-as配下のファイルはネイティブの `zip`/`unzip` バイナリが常に
 * 存在するとは限らないため、PrivilegedFileSystemのread/write経由でバイト列を
 * やり取りし、java.util.zip でアーカイブを組み立てる方式を採る。
 * (大容量ファイルは一度メモリに載る点に留意。一般的な用途での利用を想定)
 */
object ArchiveUtil {

    /**
     * 複数のファイル/フォルダをまとめて1つのZIPへ圧縮する。
     * @param entries 圧縮対象(ディレクトリは再帰的に中身を辿る)
     * @param destZipPath 出力先のZIPファイルパス
     */
    fun compress(fs: PrivilegedFileSystem, entries: List<FileEntry>, destZipPath: String): Result<Unit> {
        return try {
            val buffer = ByteArrayOutputStream()
            ZipOutputStream(buffer).use { zos ->
                for (entry in entries) {
                    addToZip(fs, zos, entry, entry.name)
                }
            }
            fs.writeFile(destZipPath, buffer.toByteArray())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun addToZip(fs: PrivilegedFileSystem, zos: ZipOutputStream, entry: FileEntry, relativePath: String) {
        if (entry.isDirectory) {
            zos.putNextEntry(ZipEntry("$relativePath/"))
            zos.closeEntry()
            val children = fs.listDirectory(entry.path).getOrDefault(emptyList())
            for (child in children) {
                addToZip(fs, zos, child, "$relativePath/${child.name}")
            }
        } else {
            val bytes = fs.readFile(entry.path).getOrNull() ?: return
            zos.putNextEntry(ZipEntry(relativePath))
            zos.write(bytes)
            zos.closeEntry()
        }
    }

    /**
     * ZIPファイルを指定ディレクトリへ展開する。
     * @param zipPath 展開元のZIPファイルパス
     * @param destDir 展開先のディレクトリ(存在しなければ作成)
     */
    fun extract(fs: PrivilegedFileSystem, zipPath: String, destDir: String): Result<Unit> {
        return try {
            val zipBytes = fs.readFile(zipPath).getOrElse { return Result.failure(it) }
            fs.mkdir(destDir)
            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outPath = "${destDir.trimEnd('/')}/${entry.name.trimEnd('/')}"
                    if (entry.isDirectory) {
                        fs.mkdir(outPath)
                    } else {
                        val parent = outPath.substringBeforeLast('/', "")
                        if (parent.isNotEmpty()) fs.mkdir(parent)
                        val content = zis.readBytes()
                        fs.writeFile(outPath, content)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
