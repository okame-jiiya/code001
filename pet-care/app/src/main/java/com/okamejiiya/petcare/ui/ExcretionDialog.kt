@file:OptIn(ExperimentalLayoutApi::class)

package com.okamejiiya.petcare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionOptions
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.Species
import java.time.Duration

/** うんち・おしっこの記録を直す（時刻・状態・気になる症状・メモ） */
@Composable
fun ExcretionDialog(
    initial: ExcretionLog,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ExcretionLog) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var type by remember { mutableStateOf(initial.type) }
    var time by remember { mutableLongStateOf(initial.time) }
    var condition by remember { mutableStateOf(initial.condition) }
    var flags by remember { mutableStateOf(initial.flags.toSet()) }
    var note by remember { mutableStateOf(initial.note) }
    var photoIds by remember { mutableStateOf(initial.photoIds) }
    var removed by remember { mutableStateOf(emptyList<String>()) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val photos = LocalPhotos.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "トイレを記録" else "トイレの記録") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in ExcretionType.entries) ChoiceChip("${t.emoji} ${t.label}", type == t) {
                        if (type != t) { type = t; condition = null; flags = emptySet() }
                    }
                }
                DateTimeRow(time) { time = it }
                Text(if (type == ExcretionType.POOP) "かたさ" else "量", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 以前の言葉で記録したものも、そのまま選べるように残す
                    val options = listOfNotNull(initial.condition?.takeIf { it !in ExcretionOptions.conditions(initial.type) && initial.type == type }) +
                        ExcretionOptions.conditions(type)
                    for (c in options) ChoiceChip(c, condition == c) {
                        condition = if (condition == c) null else c
                    }
                }
                Text("気になること", style = MaterialTheme.typography.labelLarge)
                Column {
                    for (f in ExcretionOptions.flags(type)) {
                        CheckLine(f, f in flags) { flags = if (f in flags) flags - f else flags + f }
                    }
                    // 以前の言葉で付けた印も外せるように
                    for (f in flags.filter { it !in ExcretionOptions.flags(type) }) {
                        CheckLine(f, true) { flags = flags - f }
                    }
                }
                EmptyHint(
                    if (type == ExcretionType.POOP) "黒いタールのような便、血便や下痢が続く、元気・食欲がないときは、早めに動物病院に相談してください。"
                    else "おしっこが出ない・ほとんど出ない（特にオス猫）ときは急ぎのことがあります。すぐに動物病院に相談してください。",
                )
                OutlinedTextField(
                    note, { note = it },
                    label = { Text("メモ（色・におい・様子など）") },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("写真（獣医さんに見せる用）", style = MaterialTheme.typography.labelLarge)
                PhotoStrip(photoIds) { viewing = it }
                if (photoIds.size < 4) PhotoButtons(PhotoPurpose.TOILET, onPhoto = { photoIds = photoIds + it }, pickLabel = "🖼 選ぶ")
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("この記録を削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                removed.forEach { photos.delete(it) }
                onSave(initial.copy(type = type, time = time, condition = condition, flags = flags.toList(), note = note.trim(), photoIds = photoIds))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    viewing?.let { id ->
        PhotoViewer(id, "トイレの写真", onDismiss = { viewing = null }, onDelete = {
            photoIds = photoIds - id
            removed = removed + id
            viewing = null
        })
    }
}

@Composable
fun CheckLine(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp))
    }
}

fun excretionDetail(log: ExcretionLog): String =
    listOfNotNull(log.condition, log.flags.takeIf { it.isNotEmpty() }?.joinToString("・") { "⚠$it" }, log.note.ifBlank { null })
        .joinToString("　")

/**
 * 体調の注意。おしっこが長時間出ていないとき（猫の尿路閉塞は命に関わる）や、
 * 血尿・下痢などの記録があるときに知らせる。記録を続けていない子に誤って出さないよう、
 * 直近 3 日におしっこの記録がある場合だけ時間の注意を出す。
 */
fun healthAlerts(data: AppData, pet: Pet, now: Long = System.currentTimeMillis()): List<String> {
    val alerts = mutableListOf<String>()
    val logs = data.excretionLogs.filter { it.petId == pet.id }
    val pees = logs.filter { it.type == ExcretionType.PEE }
    val lastPee = pees.maxOfOrNull { it.time }
    if (lastPee != null) {
        val hours = Duration.ofMillis(now - lastPee).toHours()
        val recent = pees.count { now - it.time < Duration.ofDays(3).toMillis() } >= 2
        if (hours >= 24 && recent) {
            alerts += "最後のおしっこから${hours}時間たっています。" +
                if (pet.species == Species.CAT) "猫は24時間以上出ないと危険なことがあるので、早めに動物病院へ相談してください。"
                else "記録漏れでなければ、動物病院へ相談してください。"
        }
    }
    val dayAgo = now - Duration.ofHours(24).toMillis()
    val recentAbnormal = logs.filter { it.time >= dayAgo && it.abnormal }
    for (type in ExcretionType.entries) {
        val list = recentAbnormal.filter { it.type == type }
        if (list.isNotEmpty()) {
            val what = list.flatMap { it.flags + listOfNotNull(it.condition?.takeIf { c -> c in ExcretionOptions.abnormalConditions }) }.distinct()
            alerts += "24時間以内の${type.label}に「${what.joinToString("・")}」の記録があります。続くようなら受診を検討してください。"
        }
    }
    return alerts
}
