// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ファイルの圧縮・展開。
 *
 * Shizuku/Root/run-as配下のファイルはネイティブの `zip`/`tar`/`unrar` バイナリが常に
 * 存在するとは限らないため、PrivilegedFileSystemのread/write経由でバイト列を
 * やり取りし、すべて純Javaのライブラリでアーカイブを組み立てる/読み取る方式を採る。
 * (大容量ファイルは一度メモリに載る点に留意。一般的な用途での利用を想定)
 *
 * 対応フォーマットと組み込みライブラリ:
 *  - ZIP           : java.util.zip (JDK標準)
 *  - TAR / TAR.GZ / TAR.BZ2 / TAR.XZ : Apache Commons Compress (Apache License 2.0)
 *  - 7Z            : Apache Commons Compress (Apache License 2.0) ※展開のみ
 *  - RAR           : junrar (UnRARライセンス) ※展開のみ。
 *    UnRARライセンス条項により、RAR互換の圧縮アーカイバを作る目的での利用は禁止されている。
 *    本アプリはその条項に従い、RARは「読み取り(展開)専用」として扱い、RAR形式での圧縮は行わない。
 *    詳細は NOTICE.md を参照。
 *
 * いずれも純Java実装のため、Android NDKによるネイティブ(.so)ビルドは不要。
 */
object ArchiveUtil {

    enum class Format { ZIP, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, SEVEN_Z, RAR }

    /** 拡張子からフォーマットを推定する(圧縮先の選択・展開元の判定に使用) */
    fun detectFormat(fileName: String): Format? {
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> Format.TAR_GZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> Format.TAR_BZ2
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> Format.TAR_XZ
            lower.endsWith(".tar") -> Format.TAR
            lower.endsWith(".zip") -> Format.ZIP
            lower.endsWith(".7z") -> Format.SEVEN_Z
            lower.endsWith(".rar") -> Format.RAR
            else -> null
        }
    }

    /**
     * 複数のファイル/フォルダをまとめて1つのアーカイブへ圧縮する。
     * RAR/7Zは圧縮先として選べない(ライセンス上/未対応のため展開専用)。
     */
    fun compress(fs: PrivilegedFileSystem, entries: List<FileEntry>, destPath: String, format: Format): Result<Unit> {
        if (format == Format.RAR) {
            return Result.failure(UnsupportedOperationException("RAR形式での圧縮はライセンス上サポートしていません(展開専用)"))
        }
        if (format == Format.SEVEN_Z) {
            return Result.failure(UnsupportedOperationException("7Z形式での圧縮は未対応です(展開のみ対応)"))
        }
        return try {
            val buffer = ByteArrayOutputStream()
            when (format) {
                Format.ZIP -> ZipOutputStream(buffer).use { zos ->
                    for (entry in entries) addToZip(fs, zos, entry, entry.name)
                }
                Format.TAR -> TarArchiveOutputStream(buffer).use { tos ->
                    for (entry in entries) addToTar(fs, tos, entry, entry.name)
                }
                Format.TAR_GZ -> TarArchiveOutputStream(GzipCompressorOutputStream(buffer)).use { tos ->
                    for (entry in entries) addToTar(fs, tos, entry, entry.name)
                }
                Format.TAR_BZ2 -> TarArchiveOutputStream(BZip2CompressorOutputStream(buffer)).use { tos ->
                    for (entry in entries) addToTar(fs, tos, entry, entry.name)
                }
                Format.TAR_XZ -> TarArchiveOutputStream(XZCompressorOutputStream(buffer)).use { tos ->
                    for (entry in entries) addToTar(fs, tos, entry, entry.name)
                }
                else -> Unit
            }
            fs.writeFile(destPath, buffer.toByteArray())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun addToZip(fs: PrivilegedFileSystem, zos: ZipOutputStream, entry: FileEntry, relativePath: String) {
        if (entry.isDirectory) {
            zos.putNextEntry(ZipEntry("$relativePath/"))
            zos.closeEntry()
            val children = fs.listDirectory(entry.path).getOrDefault(emptyList())
            for (child in children) addToZip(fs, zos, child, "$relativePath/${child.name}")
        } else {
            val bytes = fs.readFile(entry.path).getOrNull() ?: return
            zos.putNextEntry(ZipEntry(relativePath))
            zos.write(bytes)
            zos.closeEntry()
        }
    }

    private fun addToTar(fs: PrivilegedFileSystem, tos: TarArchiveOutputStream, entry: FileEntry, relativePath: String) {
        if (entry.isDirectory) {
            val tarEntry = TarArchiveEntry("$relativePath/")
            tos.putArchiveEntry(tarEntry)
            tos.closeArchiveEntry()
            val children = fs.listDirectory(entry.path).getOrDefault(emptyList())
            for (child in children) addToTar(fs, tos, child, "$relativePath/${child.name}")
        } else {
            val bytes = fs.readFile(entry.path).getOrNull() ?: return
            val tarEntry = TarArchiveEntry(relativePath)
            tarEntry.size = bytes.size.toLong()
            tos.putArchiveEntry(tarEntry)
            tos.write(bytes)
            tos.closeArchiveEntry()
        }
    }

    /**
     * アーカイブファイルを指定ディレクトリへ展開する。
     * @param archivePath 展開元のアーカイブファイルパス
     * @param destDir 展開先のディレクトリ(存在しなければ作成)
     * @param format 明示指定しない場合は archivePath の拡張子から自動判定する
     */
    fun extract(fs: PrivilegedFileSystem, archivePath: String, destDir: String, format: Format? = null): Result<Unit> {
        return try {
            val resolvedFormat = format ?: detectFormat(archivePath)
                ?: return Result.failure(IllegalArgumentException("未対応の拡張子です: $archivePath"))
            val bytes = fs.readFile(archivePath).getOrElse { return Result.failure(it) }
            fs.mkdir(destDir)
            when (resolvedFormat) {
                Format.ZIP -> extractZip(fs, bytes, destDir)
                Format.TAR -> extractTar(fs, TarArchiveInputStream(bytes.inputStream()), destDir)
                Format.TAR_GZ -> extractTar(fs, TarArchiveInputStream(GzipCompressorInputStream(bytes.inputStream())), destDir)
                Format.TAR_BZ2 -> extractTar(fs, TarArchiveInputStream(BZip2CompressorInputStream(bytes.inputStream())), destDir)
                Format.TAR_XZ -> extractTar(fs, TarArchiveInputStream(XZCompressorInputStream(bytes.inputStream())), destDir)
                Format.SEVEN_Z -> extractSevenZ(fs, bytes, destDir)
                Format.RAR -> extractRar(fs, bytes, destDir)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZip(fs: PrivilegedFileSystem, bytes: ByteArray, destDir: String) {
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                writeExtractedEntry(fs, destDir, entry.name, entry.isDirectory) { zis.readBytes() }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractTar(fs: PrivilegedFileSystem, tis: TarArchiveInputStream, destDir: String) {
        tis.use { stream ->
            var entry: TarArchiveEntry? = stream.nextTarEntry
            while (entry != null) {
                writeExtractedEntry(fs, destDir, entry.name, entry.isDirectory) { stream.readBytes() }
                entry = stream.nextTarEntry
            }
        }
    }

    /** 7Zはランダムアクセスが必要なためSeekableByteChannel経由で読む(ストリーミング展開不可な形式) */
    private fun extractSevenZ(fs: PrivilegedFileSystem, bytes: ByteArray, destDir: String) {
        SevenZFile(SeekableInMemoryByteChannel(bytes)).use { sevenZFile ->
            var entry: ArchiveEntry? = sevenZFile.nextEntry
            while (entry != null) {
                val e = entry as SevenZArchiveEntry
                writeExtractedEntry(fs, destDir, e.name, e.isDirectory) { sevenZFile.getInputStream(e).readBytes() }
                entry = sevenZFile.nextEntry
            }
        }
    }

    /**
     * RAR展開(junrar)。UnRARライセンスに基づき「読み取り専用」。
     * ここで生成したデータをRAR形式へ再圧縮する処理は本アプリに実装しない。
     */
    private fun extractRar(fs: PrivilegedFileSystem, bytes: ByteArray, destDir: String) {
        Archive(ByteArrayInputStream(bytes)).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                val name = header.fileNameString.replace('\\', '/')
                if (header.isDirectory) {
                    writeExtractedEntry(fs, destDir, name, true) { ByteArray(0) }
                } else {
                    val out = ByteArrayOutputStream()
                    archive.extractFile(header, out)
                    writeExtractedEntry(fs, destDir, name, false) { out.toByteArray() }
                }
                header = archive.nextFileHeader()
            }
        }
    }

    private inline fun writeExtractedEntry(
        fs: PrivilegedFileSystem,
        destDir: String,
        rawName: String,
        isDirectory: Boolean,
        readBytes: () -> ByteArray,
    ) {
        // Zip Slip対策: ".."を含むパスや絶対パスへの書き込みは拒否する
        val normalized = rawName.trim('/').replace("\\", "/")
        if (normalized.isEmpty() || normalized.split("/").any { it == ".." }) return
        val outPath = "${destDir.trimEnd('/')}/${normalized.trimEnd('/')}"
        if (isDirectory) {
            fs.mkdir(outPath)
        } else {
            val parent = outPath.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) fs.mkdir(parent)
            fs.writeFile(outPath, readBytes())
        }
    }
}
