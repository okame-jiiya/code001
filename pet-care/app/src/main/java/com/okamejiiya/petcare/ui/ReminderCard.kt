package com.okamejiiya.petcare.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.Reminder
import com.okamejiiya.petcare.model.ReminderKind
import com.okamejiiya.petcare.notify.Reminders
import java.time.LocalDate

/** ごはん・お薬の時刻に毎日通知する設定 */
@Composable
fun ReminderCard(data: AppData, pet: Pet, store: PetStore) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    fun askPermission() {
        if (!notifGranted && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val list = data.reminders.filter { it.petId == pet.id }.sortedBy { it.hour * 60 + it.minute }
    SectionCard(title = "⏰ 通知（ごはん・お薬の時間）") {
        if (list.isEmpty()) EmptyHint("毎日決まった時刻にお知らせします。お薬の飲ませ忘れ防止にも使えます。")
        list.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { editing = r },
                ) {
                    Text("%d:%02d".format(r.hour, r.minute), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${r.kind.emoji} ${r.kind.label}" + (if (r.memo.isNotBlank()) "・${r.memo}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = r.enabled, onCheckedChange = {
                    store.upsertReminder(r.copy(enabled = it))
                    if (it) askPermission()
                })
            }
        }
        if (list.any { it.enabled } && !notifGranted) {
            Text("⚠ 通知が許可されていません。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("通知を許可する") }
        }
        if (list.any { it.enabled } && !Reminders.canScheduleExact(context) && Build.VERSION.SDK_INT >= 31) {
            EmptyHint("時刻ぴったりに鳴らすには「アラームとリマインダー」の許可が必要です（許可しなくても数分以内に届きます）。")
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("許可の設定を開く") }
        }
        FilledTonalButton(onClick = { editing = Reminder(0, pet.id, ReminderKind.MEAL, 8, 0) }) { Text("＋ 通知を追加") }
    }

    editing?.let { r ->
        ReminderDialog(
            initial = r,
            onDismiss = { editing = null },
            onSave = {
                store.upsertReminder(it)
                editing = null
                askPermission()
            },
            onDelete = if (r.id != 0L) ({ store.deleteReminder(r.id); editing = null }) else null,
        )
    }
}

@Composable
private fun ReminderDialog(initial: Reminder, onDismiss: () -> Unit, onSave: (Reminder) -> Unit, onDelete: (() -> Unit)?) {
    var kind by remember { mutableStateOf(initial.kind) }
    var memo by remember { mutableStateOf(initial.memo) }
    var time by remember { mutableLongStateOf(LocalDate.now().atTime(initial.hour, initial.minute).toMillis()) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "通知を追加" else "通知を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (k in ReminderKind.entries) ChoiceChip("${k.emoji} ${k.label}", kind == k) { kind = k }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("毎日", modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickTime = true }) {
                        Text("🕒 ${time.timeText()}", style = MaterialTheme.typography.titleLarge)
                    }
                }
                OutlinedTextField(
                    memo, { memo = it },
                    label = { Text("メモ（例：カリカリ22g、目薬）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("この通知を削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val dt = time.toLocalDateTime()
                onSave(
                    initial.copy(
                        id = if (initial.id == 0L) Ids.next() else initial.id,
                        kind = kind, hour = dt.hour, minute = dt.minute, memo = memo.trim(), enabled = true,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    if (pickTime) {
        TimePickDialog(time, onDismiss = { pickTime = false }) { time = it; pickTime = false }
    }
}
