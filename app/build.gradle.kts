plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions側でSecretsを環境変数として渡す想定。
//  KEYSTORE_PATH               : base64デコード済みのkeystoreファイルパス(workflow側で用意)
//  KEYSTORE_PASSWORD_20260801  : keystore/キーのパスワード(共通)
//  KEY_ALIAS_20260801          : 鍵のエイリアス
// いずれか欠けている場合は署名なし(releaseもデバッグ用途扱い)でビルドされる。
val ciKeystorePath = System.getenv("KEYSTORE_PATH")
val ciKeystorePassword = System.getenv("KEYSTORE_PASSWORD_20260801")
val ciKeyAlias = System.getenv("KEY_ALIAS_20260801")
val hasCiSigningConfig = !ciKeystorePath.isNullOrBlank() &&
    !ciKeystorePassword.isNullOrBlank() &&
    !ciKeyAlias.isNullOrBlank() &&
    file(ciKeystorePath).exists()

android {
    namespace = "com.privfm.explorer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.privfm.explorer"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "5.3.0"
    }

    signingConfigs {
        if (hasCiSigningConfig) {
            create("release") {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasCiSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // ★このアプリ自体もdebuggable=trueでビルドされる
            // (run-as経由で自身のデータ領域を検証する際の動作確認に利用可能)
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku: ADB/root権限で特権シェル・APIを呼び出すための公式API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // アーカイブ対応: いずれも純Java実装でNDK/ネイティブ.soビルド不要
    //  - Apache Commons Compress (Apache License 2.0): tar / tar.gz / tar.bz2 / 7z(読取)
    //  - junrar (UnRARライセンス): RAR書庫の「読み取り専用」展開のみ。
    //    RAR互換の圧縮アーカイバを作る用途への使用は禁止(ライセンス条項どおり、本アプリも展開専用として扱う)
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.github.junrar:junrar:7.5.5")
    // commons-compressのXZ(.tar.xz)対応に必要
    implementation("org.tukaani:xz:1.9")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
