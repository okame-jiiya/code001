package com.okamejiiya.petcare.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.okamejiiya.petcare.data.FamilySync

/** 家族共有：招待 QR コード・リンク、参加、共有をやめる */
@Composable
fun FamilyCard(family: FamilySync) {
    val status by family.status.collectAsState()
    val context = LocalContext.current
    var askLink by remember { mutableStateOf(false) }
    var joinId by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    SectionCard(title = "👨‍👩‍👧 家族と共有") {
        when (val s = status) {
            FamilySync.Status.NotConfigured -> EmptyHint("家族共有は準備中です。設定が済むと、ここから始められます。")
            FamilySync.Status.Off -> {
                EmptyHint(
                    "共有を始めると、記録がクラウドに保存され、家族のスマホや iPhone の Web 版と同じ記録を見たり書いたりできます。" +
                        "どちらかが記録すると、すぐに相手にも反映されます。",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { family.create() }) { Text("共有を始める") }
                    OutlinedButton(onClick = { askLink = true }) { Text("招待リンクで参加") }
                }
            }
            is FamilySync.Status.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(s.message)
            }
            is FamilySync.Status.On -> {
                val url = family.inviteUrl(s.familyId)
                Text("✅ 家族と共有中です", style = MaterialTheme.typography.titleSmall)
                EmptyHint(
                    "家族のスマホのカメラでこの QR コードを読み取ると参加できます。" +
                        "アプリが入っていない iPhone などは、そのまま Web 版で使えます（Safari の共有ボタン →「ホーム画面に追加」でアプリのように使えます）。",
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(url, 200) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "うちの子ごはん帳の記録を一緒につけよう 🐾\n$url")
                        context.startActivity(Intent.createChooser(send, "招待リンクを送る"))
                    }) { Text("リンクを送る") }
                    TextButton(onClick = { confirmLeave = true }) { Text("共有をやめる", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                EmptyHint("招待リンクを知っている人は誰でも参加できます。家族以外には送らないでください。")
            }
            is FamilySync.Status.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.familyId != null) {
                        TextButton(onClick = { confirmLeave = true }) { Text("共有をやめる") }
                    } else {
                        FilledTonalButton(onClick = { family.create() }) { Text("もう一度") }
                        OutlinedButton(onClick = { askLink = true }) { Text("招待リンクで参加") }
                    }
                }
            }
        }
    }

    if (askLink) {
        InviteLinkDialog(onDismiss = { askLink = false }) { askLink = false; joinId = it }
    }
    joinId?.let { fid ->
        JoinFamilyDialog(onDismiss = { joinId = null }) { joinId = null; family.join(fid) }
    }
    if (confirmLeave) {
        ConfirmDialog(
            "共有をやめると、この端末は家族の記録と同期しなくなります（今ある記録はこの端末に残ります）。よろしいですか？",
            confirmLabel = "やめる",
            onDismiss = { confirmLeave = false },
            onConfirm = { confirmLeave = false; family.leave() },
        )
    }
}

/** 招待リンクを貼り付けてもらう */
@Composable
fun InviteLinkDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val fid = FamilySync.parseInvite(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("招待リンクで参加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmptyHint("家族から届いた招待リンクを貼り付けてください。")
                OutlinedTextField(text, { text = it }, placeholder = { Text("https://…#join=…") }, singleLine = true)
                if (text.isNotBlank() && fid == null) Text("招待リンクの形ではないようです", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { fid?.let(onJoin) }, enabled = fid != null) { Text("次へ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 参加するとこの端末の記録が置き換わることを確認する */
@Composable
fun JoinFamilyDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("家族の記録に参加") },
        text = {
            Text(
                "参加すると、この端末の記録は家族の記録に置き換わります。" +
                    "この端末だけにある記録を残したいときは、先に「バックアップ」で書き出しておいてください。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("参加する") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun QrCode(text: String, sizeDp: Int) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Box(Modifier.background(Color.White, MaterialTheme.shapes.small).padding(12.dp)) {
        Canvas(Modifier.size(sizeDp.dp)) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color(0xFF4F4247), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
