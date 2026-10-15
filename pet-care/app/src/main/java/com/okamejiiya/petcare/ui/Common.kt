@file:OptIn(ExperimentalMaterial3Api::class)

package com.okamejiiya.petcare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.ZoneOffset
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val zone: ZoneId get() = ZoneId.systemDefault()

fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)
fun Long.toLocalDate(): LocalDate = toLocalDateTime().toLocalDate()
fun LocalDateTime.toMillis(): Long = atZone(zone).toInstant().toEpochMilli()
fun Long.timeText(): String = toLocalDateTime().format(DateTimeFormatter.ofPattern("H:mm"))
fun LocalDate.jpText(): String = "${monthValue}月${dayOfMonth}日（${weekdayJp(this)}）"
fun weekdayJp(d: LocalDate): String = "月火水木金土日"[d.dayOfWeek.value - 1].toString()

/** その日の指定時刻（今日なら現在時刻）をミリ秒で */
fun defaultTimeFor(day: LocalDate): Long {
    val now = LocalDateTime.now()
    return if (day == now.toLocalDate()) now.toMillis() else day.atTime(12, 0).toMillis()
}

fun String.toDoubleLoose(): Double? = replace(',', '.').trim().toDoubleOrNull()

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
    )
}

/** − 値 ＋ のステッパー */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(36.dp)) { Text("−") }
        Text(
            value,
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(36.dp)) { Text("＋") }
    }
}

@Composable
fun Pill(text: String, color: Color, textColor: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        modifier = Modifier
            .background(color, CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
    )
}

@Composable
fun TimePickDialog(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val dt = initial.toLocalDateTime()
    val state = rememberTimePickerState(initialHour = dt.hour, initialMinute = dt.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("時刻を選ぶ") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(dt.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)).toMillis())
            }) { Text("決定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun ConfirmDialog(message: String, confirmLabel: String = "削除", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

fun LocalDate.slashText(): String = "${year}/${monthValue}/${dayOfMonth}"

/** 日付ボタン。押すとカレンダーで選べる。allowClear なら「未定」に戻せる */
@Composable
fun DateButton(
    date: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "日付を選ぶ",
    allowClear: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }) { Text("📅 " + (date?.slashText() ?: placeholder)) }
        if (allowClear && date != null) TextButton(onClick = { onChange(null) }) { Text("未定にする") }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("キャンセル") } },
        ) { DatePicker(state = state) }
    }
}

/** 機嫌（晴れ・曇り・雨）をワンタップで選ぶ。同じものをもう一度押すと取り消し */
@Composable
fun MoodPicker(current: com.okamejiiya.petcare.model.Mood?, onPick: (com.okamejiiya.petcare.model.Mood?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("きげん", style = MaterialTheme.typography.labelLarge)
        for (m in com.okamejiiya.petcare.model.Mood.entries) {
            ChoiceChip("${m.emoji} ${m.label}", current == m) { onPick(if (current == m) null else m) }
        }
    }
}
