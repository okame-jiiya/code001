@file:OptIn(ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.okamejiiya.petcare.model.Reminder
import com.okamejiiya.petcare.model.ReminderKind
import com.okamejiiya.petcare.model.RepeatType
import com.okamejiiya.petcare.notify.Reminders
import java.time.LocalDate

/** 項目を入れたときの、最初の繰り返し */
private fun defaultReminder(kind: ReminderKind): Reminder = when (kind) {
    ReminderKind.MEAL -> Reminder(0, null, kind, 8, 0)
    ReminderKind.TREAT -> Reminder(0, null, kind, 15, 0)
    ReminderKind.MEDICINE -> Reminder(0, null, kind, 9, 0)
    ReminderKind.VISIT -> Reminder(0, null, kind, 9, 0, repeat = RepeatType.DAYS, interval = 30, startDate = LocalDate.now().plusDays(1))
    ReminderKind.WALK -> Reminder(0, null, kind, 7, 0)
    ReminderKind.WEIGHT -> Reminder(0, null, kind, 9, 0, repeat = RepeatType.WEEKLY, weekdays = setOf(7))
    ReminderKind.OTHER -> Reminder(0, null, kind, 12, 0)
}

/**
 * 定期リマインダーの設定。通知したい項目にまとめてチェックを入れ、
 * 項目ごとに「○時間ごと・毎日・○日ごと・毎週」をいくつでも組み合わせられる。
 */
@Composable
fun ReminderSettings(data: AppData, store: PetStore) {
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

    SectionCard(title = "⏰ 定期リマインダー") {
        EmptyHint("通知したい項目をオンにして、繰り返しを決めます。1つの項目に「8時間ごと」と「毎週日曜」のように、いくつでも組み合わせられます。")
        ReminderKind.entries.forEachIndexed { i, kind ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val list = data.reminders.filter { it.kind == kind }
            val on = list.any { it.enabled }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${kind.emoji} ${kind.label}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { checked ->
                    if (checked) {
                        if (list.isEmpty()) store.upsertReminder(defaultReminder(kind).copy(id = Ids.next()))
                        else list.forEach { store.upsertReminder(it.copy(enabled = true)) }
                        askPermission()
                    } else {
                        list.forEach { store.upsertReminder(it.copy(enabled = false)) }
                    }
                })
            }
            if (on) {
                for (r in list.sortedBy { it.hour * 60 + it.minute }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = r }
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.repeatText(), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (if (r.petId == null) "みんな" else data.pets.firstOrNull { it.id == r.petId }?.name ?: "？") +
                                    (if (r.memo.isNotBlank()) "・${r.memo}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!r.enabled) Pill("休止中", Pastel.LemonLight)
                    }
                }
                TextButton(onClick = { editing = defaultReminder(kind) }) { Text("＋ 繰り返しを追加") }
            }
        }
        if (data.reminders.any { it.enabled } && !notifGranted) {
            Text("⚠ 通知が許可されていません。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("通知を許可する") }
        }
        if (data.reminders.any { it.enabled } && !Reminders.canScheduleExact(context) && Build.VERSION.SDK_INT >= 31) {
            EmptyHint("時刻ぴったりに鳴らすには「アラームとリマインダー」の許可が必要です（許可しなくても数分以内に届きます）。")
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("許可の設定を開く") }
        }
    }

    editing?.let { r ->
        ReminderDialog(
            data = data,
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
private fun ReminderDialog(data: AppData, initial: Reminder, onDismiss: () -> Unit, onSave: (Reminder) -> Unit, onDelete: (() -> Unit)?) {
    var petId by remember { mutableStateOf(initial.petId) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var interval by remember { mutableIntStateOf(initial.interval.coerceAtLeast(1)) }
    var weekdays by remember { mutableStateOf(initial.weekdays.ifEmpty { setOf(LocalDate.now().dayOfWeek.value) }) }
    var startDate by remember { mutableStateOf(initial.startDate ?: LocalDate.now()) }
    var memo by remember { mutableStateOf(initial.memo) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var time by remember { mutableLongStateOf(LocalDate.now().atTime(initial.hour, initial.minute).toMillis()) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${initial.kind.emoji} ${initial.kind.label}の通知") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("だれの分", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("みんな", petId == null) { petId = null }
                    for (p in data.sortedPets) ChoiceChip(p.name, petId == p.id) { petId = p.id }
                }
                Text("繰り返し", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in RepeatType.entries) ChoiceChip(t.label, repeat == t) {
                        if (repeat != t) {
                            repeat = t
                            interval = when (t) {
                                RepeatType.HOURS -> 8
                                RepeatType.DAYS -> 2
                                else -> 1
                            }
                        }
                    }
                }
                when (repeat) {
                    RepeatType.HOURS -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Stepper("${interval}時間", onMinus = { interval = (interval - 1).coerceAtLeast(1) }, onPlus = { interval = (interval + 1).coerceAtMost(24) })
                        Text("ごと")
                    }
                    RepeatType.DAYS -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Stepper("${interval}日", onMinus = { interval = (interval - 1).coerceAtLeast(2) }, onPlus = { interval = (interval + 1).coerceAtMost(365) })
                        Text("ごと")
                    }
                    RepeatType.WEEKLY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (d in 1..7) {
                            ChoiceChip("月火水木金土日"[d - 1].toString(), d in weekdays) {
                                weekdays = if (d in weekdays && weekdays.size > 1) weekdays - d else weekdays + d
                            }
                        }
                    }
                    RepeatType.DAILY -> Unit
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (repeat == RepeatType.HOURS) "最初の時刻" else "時刻", modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickTime = true }) {
                        Text("🕒 ${time.timeText()}", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (repeat == RepeatType.HOURS || repeat == RepeatType.DAYS) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("はじめる日", modifier = Modifier.weight(1f))
                        DateButton(startDate, { if (it != null) startDate = it })
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
                    CheckLine("この繰り返しを休む", !enabled) { enabled = !enabled }
                    TextButton(onClick = onDelete) { Text("この繰り返しを削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val dt = time.toLocalDateTime()
                onSave(
                    initial.copy(
                        id = if (initial.id == 0L) Ids.next() else initial.id,
                        petId = petId,
                        hour = dt.hour, minute = dt.minute,
                        repeat = repeat,
                        interval = interval,
                        weekdays = if (repeat == RepeatType.WEEKLY) weekdays else emptySet(),
                        startDate = if (repeat == RepeatType.HOURS || repeat == RepeatType.DAYS) startDate else null,
                        memo = memo.trim(),
                        enabled = enabled,
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
