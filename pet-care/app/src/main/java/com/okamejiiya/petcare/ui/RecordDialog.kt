package com.okamejiiya.petcare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.WeightLog
import com.okamejiiya.petcare.model.RecordItem
import com.okamejiiya.petcare.model.RecordKind
import com.okamejiiya.petcare.model.RecordLog
import com.okamejiiya.petcare.model.formatAmount

/** 体温・散歩・ワクチンなどの記録を入れる・直す */
@Composable
fun RecordDialog(initial: RecordLog, isNew: Boolean, onDismiss: () -> Unit, onSave: (RecordLog) -> Unit, onDelete: (() -> Unit)?) {
    val item = initial.item
    var time by remember { mutableLongStateOf(initial.time) }
    var valueText by remember { mutableStateOf(initial.value?.let { formatAmount(it) } ?: "") }
    var text by remember { mutableStateOf(initial.text) }
    var done by remember { mutableStateOf(initial.done) }
    val value = valueText.toDoubleLoose()
    val valid = when (item.kind) {
        RecordKind.NUMBER -> value != null
        RecordKind.TEXT -> true
        RecordKind.CHECK -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.emoji} ${item.label}" + if (isNew) "を記録" else "") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DateTimeRow(time) { time = it }
                when (item.kind) {
                    RecordKind.NUMBER -> NumberField(valueText, { valueText = it }, item.label, Modifier.fillMaxWidth(), suffix = item.unit.ifEmpty { null })
                    RecordKind.CHECK -> CheckLine(if (item == RecordItem.MEDICINE_DONE) "飲ませた" else "済み", done) { done = !done }
                    RecordKind.TEXT -> Unit
                }
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(if (item.kind == RecordKind.TEXT) textHint(item) else "メモ（なくてもOK）") },
                    minLines = if (item.kind == RecordKind.TEXT) 3 else 1,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("この記録を削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(initial.copy(time = time, value = if (item.kind == RecordKind.NUMBER) value else null, text = text.trim(), done = done))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private fun textHint(item: RecordItem): String = when (item) {
    RecordItem.COMBO_VACCINE -> "ワクチンの種類・病院など"
    RecordItem.VISIT -> "病院・診断・処置・費用など"
    RecordItem.MEDICINE_NOTE -> "薬の名前・量・回数など"
    RecordItem.SICK -> "様子（吐いた・元気がない など）"
    RecordItem.TRIMMING -> "お店・カットの内容など"
    else -> "メモ"
}

/** 記録の中身を短い文字で */
fun recordValueText(r: RecordLog): String {
    val main = when (r.item.kind) {
        RecordKind.NUMBER -> r.value?.let { formatAmount(it) + if (r.item.unit.isNotEmpty()) " ${r.item.unit}" else "" }
        RecordKind.CHECK -> if (r.done) "✓ 済み" else "未"
        RecordKind.TEXT -> null
    }
    return listOfNotNull(main, r.text.ifBlank { null }).joinToString("　")
}

@Composable
fun RecordRow(r: RecordLog, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onEdit) { Text(r.time.timeText(), fontWeight = FontWeight.Bold) }
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text("${r.item.emoji} ${r.item.label}")
            val detail = recordValueText(r)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onDelete) { Text("削除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * 「ほかの記録」のボタン（設定で選んだ項目だけ）。
 * チェックの項目はワンタップで記録し、数値や文字の項目は入力画面を開く。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickRecordChips(
    data: AppData,
    pet: Pet,
    timeFor: () -> Long,
    onQuick: (RecordLog) -> Unit,
    onOpen: (RecordLog) -> Unit,
    onWeight: (WeightLog) -> Unit,
) {
    val items = data.settings.items.filter { !it.special || it == RecordItem.WEIGHT }
    if (items.isEmpty()) {
        EmptyHint("「設定」の「記録する項目」で、体温・散歩・薬などを追加できます。")
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) {
            AssistChip(
                onClick = {
                    val t = timeFor()
                    when {
                        item == RecordItem.WEIGHT -> {
                            val dt = t.toLocalDateTime()
                            onWeight(newWeight(pet, dt.toLocalDate(), dt.toLocalTime().withSecond(0).withNano(0)))
                        }
                        item.kind == RecordKind.CHECK -> onQuick(RecordLog(Ids.next(), pet.id, item, t, done = true))
                        else -> onOpen(RecordLog(Ids.next(), pet.id, item, t))
                    }
                },
                label = { Text("${item.emoji} ${item.label}" + if (item.kind == RecordKind.CHECK) " ✓" else "") },
            )
        }
    }
}
