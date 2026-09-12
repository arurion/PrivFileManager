// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.shell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.privfm.explorer.util.AppPreferences
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * ShizukuアプリをインストールせずにADB(shell)権限を得るための実行エンジン。
 *
 * App Manager(MuntashirAkon, GPL-3.0-or-later)が使っている
 * [libadb-android](https://github.com/MuntashirAkon/libadb-android)
 * (同じくMuntashirAkon作、GPL-3.0-or-later / Apache-2.0のデュアルライセンス、
 * 本プロジェクトはGPL-3.0-or-laterとして利用する)を採用した。
 *
 * dadb(mobile-dev-inc)も検討したが、Android 11+のワイヤレスデバッグにおける
 * 「ペアリングコード」でのTLSペアリングをdadbは実装していない
 * (dadb本体のIssue #25で議論中・未解決)。libadb-androidは実際に
 * `AbsAdbConnectionManager#pair(host, port, pairingCode)` でこのペアリングを
 * 実装しており、App Manager自体もこれを使って「PC不要でのワイヤレスデバッグ
 * 有効化」を実現している。本実装もApp Managerの`AdbConnectionManager`の
 * 構成(自己署名証明書つきRSA鍵ペアを1つ生成し使い回す)に倣った。
 *
 * 証明書生成には、App Manager自身が使っている`sun.security.x509`ベースの
 * 自作ユーティリティ(Android非標準API)や、BouncyCastleではなく、
 * Android標準の`AndroidKeyStore`プロバイダを使っている。
 * `KeyGenParameterSpec`に証明書のsubject等を指定するだけで、鍵ペア生成と
 * 同時に自己署名証明書が自動的に発行されるため、追加のライブラリが不要になる。
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    companion object {
        private const val KEYSTORE_ALIAS = "privfm_adb_key"

        @Volatile
        private var instance: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also {
                    instance = it
                    it.setApi(android.os.Build.VERSION.SDK_INT)
                }
            }
        }
    }

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateKeyPair()
        }
    }

    private fun generateKeyPair() {
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 365L * 24 * 60 * 60 * 1000) // 1年
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=PrivFileManager"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    override fun getPrivateKey(): PrivateKey {
        return keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
    }

    override fun getCertificate(): Certificate {
        return keyStore.getCertificate(KEYSTORE_ALIAS)
    }

    override fun getDeviceName(): String = "PrivFileManager"
}

object AdbShell : ShellExecutor {

    /** shell:サービスは終了コードを返さないため、出力末尾にこの目印を仕込んで判定する */
    private const val EXIT_MARKER = "___PRIVFM_ADB_EXIT___"

    override fun label(): String = "ADB (Wireless debugging)"

    override fun isAvailable(): Boolean {
        val host = AppPreferences.adbHost
        val port = AppPreferences.adbPort
        return host.isNotBlank() && port > 0
    }

    /**
     * ワイヤレスデバッグの「ペアリングコード」でペアリングする。
     * (端末の「開発者向けオプション > ワイヤレスデバッグ > ペア設定コードによるデバイスの
     * ペア設定」画面に表示されるIP・ポート・6桁のコードを入力してもらう)
     * 呼び出し元は必ずバックグラウンドスレッドから呼ぶこと。
     */
    fun pair(context: Context, host: String, port: Int, pairingCode: String): Result<Unit> {
        return try {
            val manager = AdbConnectionManager.getInstance(context)
            val paired = manager.pair(host, port, pairingCode)
            if (paired) Result.success(Unit) else Result.failure(IllegalStateException("ペアリングに失敗しました(コードやポートをご確認ください)"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 接続を試みる。ペアリング済みの状態で、「ワイヤレスデバッグ」画面の主接続用
     * IPアドレス・ポート(ペアリング用とは別のポート番号)を指定する。
     * 呼び出し元は必ずバックグラウンドスレッドから呼ぶこと。
     */
    fun connect(context: Context, host: String, port: Int): Result<Unit> {
        return try {
            val manager = AdbConnectionManager.getInstance(context)
            val connected = manager.connect(host, port)
            if (connected) Result.success(Unit) else Result.failure(IllegalStateException("接続できませんでした"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 保存済みのホスト/ポート設定を使って接続する(設定画面の「接続テスト」用) */
    fun connect(context: Context): Result<Unit> {
        val host = AppPreferences.adbHost
        val port = AppPreferences.adbPort
        if (host.isBlank() || port <= 0) {
            return Result.failure(IllegalStateException("接続先(ホスト/ポート)が設定されていません"))
        }
        return connect(context, host, port)
    }

    fun disconnect(context: Context) {
        try {
            AdbConnectionManager.getInstance(context).disconnect()
        } catch (e: Exception) {
            // 切断失敗は無視してよい
        }
    }

    /**
     * [context]は`ShellExecutor`インターフェースの共通シグネチャには含められないため、
     * 事前に[connect]済みの接続を[com.privfm.explorer.shell.ShellManager]経由で
     * 使い回す設計にしている。接続していない状態でexecが呼ばれた場合はエラーを返す。
     */
    override fun exec(command: String, stdinBase64: String?): ShellResult {
        val appContext = ShellManager.applicationContext
            ?: return ShellResult(-1, "", "ADB接続の初期化に失敗しました(Context未設定)")
        val manager = AdbConnectionManager.getInstance(appContext)
        if (!manager.isConnected) {
            return ShellResult(-1, "", "ADB未接続です。設定画面から接続してください")
        }
        return try {
            val fullCommand = if (stdinBase64 != null) {
                "echo '$stdinBase64' | base64 -d | ($command); echo \"$EXIT_MARKER\$?\""
            } else {
                "($command); echo \"$EXIT_MARKER\$?\""
            }
            val stream = manager.openStream("shell:$fullCommand")
            val output = ByteArrayOutputStream()
            val input = stream.openInputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            stream.close()
            val rawOutput = output.toString("UTF-8")
            val markerIndex = rawOutput.lastIndexOf(EXIT_MARKER)
            if (markerIndex < 0) {
                ShellResult(-1, rawOutput, "終了コードを取得できませんでした")
            } else {
                val stdout = rawOutput.substring(0, markerIndex)
                val exitCode = rawOutput.substring(markerIndex + EXIT_MARKER.length).trim().toIntOrNull() ?: -1
                ShellResult(exitCode, stdout, "")
            }
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "ADB通信エラー")
        }
    }
}
