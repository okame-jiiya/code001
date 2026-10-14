package com.okamejiiya.petcare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.model.HealthKind
import com.okamejiiya.petcare.model.HealthRecord
import com.okamejiiya.petcare.model.Ids
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 予定の種類ごとの色（カレンダーのドットなど） */
fun healthColor(kind: HealthKind): Color = when (kind) {
    HealthKind.VISIT -> Color(0xFFE0708F)
    HealthKind.MEDICINE -> Color(0xFF8E7CCF)
    HealthKind.VACCINE -> Color(0xFF3FA796)
    HealthKind.TRIMMING -> Color(0xFFE8963F)
    HealthKind.OTHER -> Color(0xFF7D90AE)
}

@Composable
fun KindDot(kind: HealthKind, size: Int = 8) {
    Row(
        Modifier
            .size(size.dp)
            .background(healthColor(kind), CircleShape),
    ) {}
}

fun LocalTime.hmText(): String = "$hour:%02d".format(minute)

fun notifyText(r: HealthRecord): String = listOfNotNull(
    r.notifyDayBeforeHour?.let { "前日${it}時" },
    r.notifyHoursBefore?.let { "当日${it}時間前" },
).joinToString("・").let { if (it.isEmpty()) "" else "🔔 $it" }

@Composable
fun HealthRow(r: HealthRecord, today: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KindDot(r.kind, 10)
        Column(Modifier.weight(1f)) {
            Text("${r.kind.emoji} ${r.title}", fontWeight = FontWeight.Bold)
            val days = ChronoUnit.DAYS.between(today, r.date)
            val rel = when {
                days == 0L -> "今日"
                days == 1L -> "明日"
                days > 1 -> "あと${days}日"
                else -> null
            }
            Text(
                listOfNotNull(r.date.jpText() + (r.time?.let { " ${it.hmText()}" } ?: ""), rel, notifyText(r).ifEmpty { null })
                    .joinToString("・"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun titleHint(kind: HealthKind) = when (kind) {
    HealthKind.VISIT -> "例：健康診断、皮膚の再診"
    HealthKind.MEDICINE -> "例：抗生物質 1日2回"
    HealthKind.VACCINE -> "例：3種混合ワクチン"
    HealthKind.TRIMMING -> "例：シャンプー＋カット"
    HealthKind.OTHER -> "例：ノミ・ダニ予防"
}

@Composable
fun HealthDialog(initial: HealthRecord, onDismiss: () -> Unit, onSave: (HealthRecord) -> Unit, onDelete: (() -> Unit)?) {
    var kind by remember { mutableStateOf(initial.kind) }
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(initial.date) }
    var time by remember { mutableStateOf(initial.time) }
    var dayBefore by remember { mutableStateOf(initial.notifyDayBeforeHour) }
    var hoursBefore by remember { mutableStateOf(initial.notifyHoursBefore) }
    var note by remember { mutableStateOf(initial.note) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val future = !date.isBefore(LocalDate.now())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) (if (future) "予定を追加" else "記録を追加") else "予定・記録を編集") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (k in HealthKind.entries) ChoiceChip("${k.emoji} ${k.label}", kind == k) { kind = k }
                }
                OutlinedTextField(
                    title, { title = it },
                    label = { Text("内容") },
                    placeholder = { Text(titleHint(kind)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("日時", style = MaterialTheme.typography.labelLarge)
                DateButton(date, { if (it != null) date = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickTime = true }) { Text("🕒 " + (time?.hmText() ?: "時刻を決める（任意）")) }
                    if (time != null) TextButton(onClick = { time = null }) { Text("時刻なし") }
                }
                if (future) {
                    Text("通知", style = MaterialTheme.typography.labelLarge)
                    CheckLine("前日に通知", dayBefore != null) { dayBefore = if (dayBefore == null) 20 else null }
                    dayBefore?.let { h ->
                        Stepper(
                            "前日 ${h}時",
                            onMinus = { dayBefore = (h - 1).coerceAtLeast(0) },
                            onPlus = { dayBefore = (h + 1).coerceAtMost(23) },
                            modifier = Modifier.padding(start = 36.dp),
                        )
                    }
                    CheckLine("当日、予定の◯時間前に通知", hoursBefore != null) { hoursBefore = if (hoursBefore == null) 2 else null }
                    hoursBefore?.let { h ->
                        Stepper(
                            "${h}時間前",
                            onMinus = { hoursBefore = (h - 1).coerceAtLeast(0) },
                            onPlus = { hoursBefore = (h + 1).coerceAtMost(12) },
                            modifier = Modifier.padding(start = 36.dp),
                        )
                        if (time == null) EmptyHint("時刻が決まっていないときは、当日9時の${h}時間前に通知します。")
                    }
                }
                OutlinedTextField(
                    note, { note = it },
                    label = { Text("メモ（お店・病院名、費用など）") },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(
                    initial.copy(
                        id = if (initial.id == 0L) Ids.next() else initial.id,
                        kind = kind, title = title.trim(), date = date, time = time, note = note.trim(),
                        notifyDayBeforeHour = if (future) dayBefore else null,
                        notifyHoursBefore = if (future) hoursBefore else null,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    if (pickTime) {
        val base = date.atTime(time ?: LocalTime.of(10, 0)).toMillis()
        TimePickDialog(base, onDismiss = { pickTime = false }) {
            time = it.toLocalDateTime().toLocalTime().withSecond(0).withNano(0); pickTime = false
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("「$title」を削除します。", onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete() })
    }
}
