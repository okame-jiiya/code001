package com.okamejiiya.petcare.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.okamejiiya.petcare.data.FamilySync
import java.text.SimpleDateFormat
import java.util.Locale

/** 家族共有：Google ログイン、メンバー、招待 QR コード、参加、共有をやめる */
@Composable
fun FamilyCard(family: FamilySync) {
    val status by family.status.collectAsState()
    val members by family.members.collectAsState()
    val invite by family.invite.collectAsState()
    val account by family.account.collectAsState()
    val context = LocalContext.current
    var askLink by remember { mutableStateOf(false) }
    var joinLink by remember { mutableStateOf<FamilySync.InviteLink?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FamilySync.Member?>(null) }

    SectionCard(title = "👨‍👩‍👧 家族と共有") {
        when (val s = status) {
            FamilySync.Status.NotConfigured -> EmptyHint("家族共有は準備中です。設定が済むと、ここから始められます。")
            FamilySync.Status.Off, is FamilySync.Status.Found -> {
                EmptyHint(
                    "共有を始めると、記録がクラウドに保存され、家族のスマホや iPhone の Web 版と同じ記録を見たり書いたりできます。" +
                        "どちらかが記録すると、すぐに相手にも反映されます。",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { family.create() }) { Text("共有を始める") }
                    OutlinedButton(onClick = { askLink = true }) { Text("招待リンクで参加") }
                }
            }
            is FamilySync.Status.Working -> Working(s.message)
            is FamilySync.Status.On -> {
                Text("✅ 家族と共有中です", style = MaterialTheme.typography.titleSmall)
                MemberList(members, onRemove = { removing = it })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InviteSection(invite, onCreate = { family.createInvite() }, onRevoke = { family.revokeInvites() }, onShare = { url ->
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "うちの子ごはん帳の記録を一緒につけよう 🐾\n$url")
                    context.startActivity(Intent.createChooser(send, "招待リンクを送る"))
                })
                TextButton(onClick = { confirmLeave = true }) { Text("この端末の共有をやめる", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            is FamilySync.Status.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.familyId != null) {
                        TextButton(onClick = { confirmLeave = true }) { Text("共有をやめる") }
                    } else {
                        FilledTonalButton(onClick = { family.create() }) { Text("共有を始める") }
                        OutlinedButton(onClick = { askLink = true }) { Text("招待リンクで参加") }
                    }
                }
            }
        }
        if (status != FamilySync.Status.NotConfigured) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AccountSection(account, onGoogle = { context.findActivity()?.let { family.signInWithGoogle(it) } })
        }
    }

    if (askLink) {
        InviteLinkDialog(onDismiss = { askLink = false }) { askLink = false; joinLink = it }
    }
    joinLink?.let { link ->
        JoinFamilyDialog(onDismiss = { joinLink = null }) { joinLink = null; family.join(link) }
    }
    if (confirmLeave) {
        ConfirmDialog(
            "共有をやめると、この端末は家族の記録と同期しなくなります（今ある記録はこの端末に残ります）。よろしいですか？",
            confirmLabel = "やめる",
            onDismiss = { confirmLeave = false },
            onConfirm = { confirmLeave = false; family.leave() },
        )
    }
    removing?.let { m ->
        ConfirmDialog(
            "「${m.name}」を家族から外しますか？外された端末は、家族の記録を見られなくなります。",
            confirmLabel = "外す",
            onDismiss = { removing = null },
            onConfirm = { removing = null; family.removeMember(m.uid) },
        )
    }
}

@Composable
private fun Working(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(message)
    }
}

private val dayFormat get() = SimpleDateFormat("M/d", Locale.JAPAN)
private val dayTimeFormat get() = SimpleDateFormat("M/d H:mm", Locale.JAPAN)

@Composable
private fun MemberList(members: List<FamilySync.Member>, onRemove: (FamilySync.Member) -> Unit) {
    Text("メンバー（${members.size}人）", style = MaterialTheme.typography.labelLarge)
    for (m in members) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (m.platform == "web") "🌐 " else "📱 ") + m.name + if (m.me) "（この端末）" else "",
                    fontWeight = if (m.me) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    listOfNotNull(
                        m.joinedAt?.let { "${dayFormat.format(it)} から参加" },
                        if (m.google) "Google ログイン" else null,
                    ).joinToString("・"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!m.me) TextButton(onClick = { onRemove(m) }) { Text("外す", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun InviteSection(invite: FamilySync.Invite?, onCreate: () -> Unit, onRevoke: () -> Unit, onShare: (String) -> Unit) {
    Text("家族を招待", style = MaterialTheme.typography.labelLarge)
    if (invite == null) {
        EmptyHint("招待 QR コードは 7 日で使えなくなります。家族を招待するときに作ってください。")
        FilledTonalButton(onClick = onCreate) { Text("招待 QR コードを作る") }
        return
    }
    EmptyHint(
        "家族のスマホのカメラでこの QR コードを読み取ると参加できます。" +
            "アプリが入っていない iPhone などは、そのまま Web 版で使えます（Safari の共有ボタン →「ホーム画面に追加」でアプリのように使えます）。",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(invite.url, 200) }
    Text(
        "${dayTimeFormat.format(invite.expiresAt)} まで有効",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { onShare(invite.url) }) { Text("リンクを送る") }
        OutlinedButton(onClick = onCreate) { Text("作り直す") }
        TextButton(onClick = onRevoke) { Text("取り消す", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    EmptyHint("招待を知っている人は誰でも参加できます。家族以外に渡ってしまったら「取り消す」を押し、知らないメンバーは「外す」で外してください。")
}

@Composable
private fun AccountSection(account: FamilySync.Account?, onGoogle: () -> Unit) {
    if (account?.email != null) {
        Text("🔐 Google アカウントでログイン中", style = MaterialTheme.typography.labelLarge)
        EmptyHint("${account.googleName ?: ""} ${account.email}".trim() + "\n機種変更やアプリの入れ直しのあとも、同じアカウントでログインすれば家族の記録に戻れます。")
    } else if (FamilySync.googleAvailable) {
        Text("🔐 Google アカウントでログイン", style = MaterialTheme.typography.labelLarge)
        EmptyHint("ログインしておくと、機種変更やアプリの入れ直しのあとも、招待なしで家族の記録に戻れます。")
        OutlinedButton(onClick = onGoogle) { Text("Google でログイン") }
    }
}

/** はじめての画面に出す、家族の記録に参加する方法 */
@Composable
fun FirstTimeFamily(family: FamilySync, status: FamilySync.Status, onInviteLink: () -> Unit) {
    val context = LocalContext.current
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        EmptyHint("家族がすでにこのアプリで記録している場合は、招待リンクから参加すると同じ記録を見られます。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onInviteLink) { Text("👨‍👩‍👧 招待リンクで参加") }
            if (FamilySync.googleAvailable) {
                OutlinedButton(onClick = { context.findActivity()?.let { family.signInWithGoogle(it) } }) { Text("Google でログイン") }
            }
        }
        when (status) {
            is FamilySync.Status.Working -> Working(status.message)
            is FamilySync.Status.Error -> Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is FamilySync.Status.On -> Text("家族の記録を待っています…（まだペットが登録されていない場合は、ここで登録できます）", style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
    }
}

/** 招待リンクを貼り付けてもらう */
@Composable
fun InviteLinkDialog(onDismiss: () -> Unit, onJoin: (FamilySync.InviteLink) -> Unit) {
    var text by remember { mutableStateOf("") }
    val link = FamilySync.parseInvite(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("招待リンクで参加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmptyHint("家族から届いた招待リンクを貼り付けてください。")
                OutlinedTextField(text, { text = it }, placeholder = { Text("https://…#join=…") }, singleLine = true)
                if (text.isNotBlank() && link == null) Text("招待リンクの形ではないようです", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { link?.let(onJoin) }, enabled = link != null) { Text("次へ") } },
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

/** Google ログインしたら、そのアカウントの家族が見つかったとき */
@Composable
fun FoundFamilyDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("家族の記録が見つかりました") },
        text = {
            Text(
                "この Google アカウントで共有していた家族の記録に戻りますか？" +
                    "この端末の記録は家族の記録に置き換わります。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("戻る") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("あとで") } },
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
