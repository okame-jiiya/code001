plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.okamejiiya.blackcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.okamejiiya.blackcam"
        minSdk = 30
        targetSdk = 36
        // Play ストアは versionCode が毎回増えている必要があるので、CI では実行番号を使う
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.1.0"
    }

    // 署名用の鍵は環境変数で渡す (リポジトリには置かない)。未設定なら署名なしでビルドされる
    val keystorePath = System.getenv("BLACKCAM_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BLACKCAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BLACKCAM_KEY_ALIAS")
                keyPassword = System.getenv("BLACKCAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
