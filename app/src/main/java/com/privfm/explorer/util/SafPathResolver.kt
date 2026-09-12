// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * 外部SAF連携。
 *
 * 本アプリはファイル操作をすべて特権シェル(Shizuku/Root/`run-as`/通常)経由の
 * 文字列パスで行っており、SAFの`content://`/`DocumentFile`ベースのAPIとは
 * アーキテクチャが異なる。そのため、他アプリのSAFツリー(
 * `ACTION_OPEN_DOCUMENT_TREE`で選んだフォルダ)をそのまま`content://`経由で
 * 読み書きする経路は実装せず、代わりに「選ばれたツリーが指す実際の絶対パスを
 * 推定し、それ以降は通常の(特権)ファイル操作に委ねる」という、多くのroot対応
 * ファイルマネージャーで採用されている実用的な方式を採る。
 *
 * これは特にSDカード・USB OTGなど、スコープドストレージ制限や一部端末のOEM
 * カスタマイズにより`MANAGE_EXTERNAL_STORAGE`だけでは書き込みアクセスできない
 * リムーバブルストレージに対して、SAFの許可ダイアログを経由することで
 * ユーザーの明示的な同意を得た上でパスを特定する目的で使う。
 * (Root/Shizukuがあれば実パスへの読み書きはSAFの許可自体を経由せず直接行える
 * ため、この機能は主に無権限環境や、リムーバブルストレージのパス把握のために使う)
 */
object SafPathResolver {

    /**
     * `ACTION_OPEN_DOCUMENT_TREE`で得たツリーURIから、対応する実際の絶対パスを
     * ベストエフォートで推定する。DocumentsContractのツリーIDは通常
     * "<ボリュームID>:<相対パス>" の形式(例: "primary:Download" や
     * "1234-5678:Pictures")になっており、既知のボリュームIDとマウントポイントの
     * 対応から実パスを組み立てる。
     *
     * @return 推定できた場合は絶対パス、推定できなかった場合はnull
     *   (SAF Uriのみで、実パスに対応するボリュームが特定できないケース。
     *   その場合はSAF経由の直接アクセスに対応していない旨を利用者に伝える)
     */
    fun resolveTreeUriToPath(context: Context, treeUri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        val split = docId.split(":", limit = 2)
        if (split.size != 2) return null
        val volumeId = split[0]
        val relativePath = split[1]

        val volumeRoot = resolveVolumeRoot(context, volumeId) ?: return null
        return if (relativePath.isEmpty()) volumeRoot else "$volumeRoot/$relativePath"
    }

    /** ボリュームID("primary"や"1234-5678"等)から、実際のマウントポイントを特定する */
    private fun resolveVolumeRoot(context: Context, volumeId: String): String? {
        if (volumeId == "primary") {
            return Environment.getExternalStorageDirectory().absolutePath
        }
        // リムーバブルストレージ(SDカード等)は "/storage/<volumeId>" に
        // マウントされるのが一般的(AOSP標準のvold命名規則)。
        // StorageManagerのgetStorageVolumesがAPI24+で使えるので、それで裏付けを取る。
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val volumes = storageManager?.storageVolumes ?: emptyList()
            for (volume in volumes) {
                val uuid = volume.uuid ?: continue
                if (uuid.equals(volumeId, ignoreCase = true)) {
                    val directory = try {
                        // getDirectory()はAPI30+のみpublic API。リフレクション無しで
                        // 呼べないAPI29以下は、後段の "/storage/<id>" 推測にフォールバックする。
                        val method = volume.javaClass.getMethod("getDirectory")
                        method.invoke(volume) as? File
                    } catch (e: Exception) {
                        null
                    }
                    if (directory != null) return directory.absolutePath
                }
            }
        } catch (e: Exception) {
            // StorageManager側で失敗しても、下の推測ロジックにフォールバックする
        }
        val guess = File("/storage/$volumeId")
        return if (guess.exists()) guess.absolutePath else "/storage/$volumeId"
    }
}
