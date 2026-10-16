package com.okamejiiya.petcare.data

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * デバッグ版の App Check。Google Play を通さない APK は Play Integrity で確認できないため、デバッグ用の確認方法を使う。
 * Firebase で App Check を「適用」にするときは、logcat に出るデバッグトークンを Firebase に登録する。
 */
internal object AppCheckSetup {
    fun install(app: FirebaseApp) {
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
