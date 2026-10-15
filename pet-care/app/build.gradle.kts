import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 家族共有で使う Firebase の設定（Web 版と共通）。ファイルが無ければ共有機能は「準備中」になる
val firebaseConfig: Map<*, *> = rootProject.file("firebase-config.json").let { f ->
    if (f.exists()) JsonSlurper().parse(f) as Map<*, *> else emptyMap<String, Any>()
}

fun configString(vararg keys: String): String =
    "\"" + (keys.firstNotNullOfOrNull { firebaseConfig[it]?.toString()?.takeIf(String::isNotBlank) } ?: "") + "\""

android {
    namespace = "com.okamejiiya.petcare"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.okamejiiya.petcare"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "FIREBASE_API_KEY", configString("apiKey"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", configString("projectId"))
        buildConfigField("String", "FIREBASE_APP_ID", configString("androidAppId", "appId"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", configString("googleWebClientId"))
        buildConfigField("String", "WEB_URL", configString("webUrl").takeIf { it != "\"\"" } ?: "\"https://okame-jiiya.github.io/code001/\"")
    }

    // CI で毎回ちがう鍵になると上書きインストールできず、Google ログイン用の SHA-1 も変わってしまうので、
    // デバッグ用の鍵をリポジトリに置いて固定する（Google Play に出すときは別の鍵を使う）
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android の org.json は単体テストでは動かないので、本物を使う
    testImplementation("org.json:json:20240303")
}
