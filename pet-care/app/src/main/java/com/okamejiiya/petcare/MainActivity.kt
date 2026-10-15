package com.okamejiiya.petcare

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.okamejiiya.petcare.data.FamilySync
import com.okamejiiya.petcare.ui.PetCareRoot

class MainActivity : ComponentActivity() {
    /** 招待リンク（petcare://join?f=…&k=…）から開かれたときの招待 */
    private val invite = mutableStateOf<FamilySync.InviteLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // パステルの明るい画面に合わせて、端末がダークモードでもステータスバーの文字は濃い色にする
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (savedInstanceState == null) readInvite(intent)
        val app = application as PetCareApp
        setContent {
            PetCareRoot(app.store, app.family, app.photos, app.prefs, invite.value, onInviteHandled = { invite.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readInvite(intent)
    }

    private fun readInvite(intent: Intent?) {
        val data = intent?.dataString ?: return
        FamilySync.parseInvite(data)?.let { invite.value = it }
    }
}
