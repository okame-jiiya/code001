package com.okamejiiya.petcare.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.data.PetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 全データを JSON ファイルに書き出す・読み込む（機種変更やもしものときのため） */
@Composable
fun BackupCard(store: PetStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = store.exportJson()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("ファイルを開けません")
                }.isSuccess
            }
            toast(if (ok) "バックアップを保存しました" else "保存できませんでした")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    SectionCard(title = "💾 バックアップ") {
        EmptyHint(
            "記録はスマホの中だけに保存されています。機種変更やもしものときのために、ときどきファイルに書き出して" +
                "Google ドライブなどに保存しておくと安心です。",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { exporter.launch("uchinoko-backup-${LocalDate.now()}.json") }) { Text("書き出す") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("読み込む")
            }
        }
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            "バックアップを読み込むと、いまアプリに入っている記録はすべて置き換わります。よろしいですか？",
            confirmLabel = "読み込む",
            onDismiss = { pendingImport = null },
            onConfirm = {
                pendingImport = null
                scope.launch {
                    val text = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
                    }
                    val ok = text != null && runCatching { store.importJson(text) }.isSuccess
                    toast(if (ok) "バックアップを読み込みました" else "このファイルは読み込めませんでした")
                }
            },
        )
    }
}
