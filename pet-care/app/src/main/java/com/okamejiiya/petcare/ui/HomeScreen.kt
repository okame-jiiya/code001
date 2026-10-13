package com.okamejiiya.petcare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.FeedLog
import com.okamejiiya.petcare.model.FoodKind
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.formatKcal
import com.okamejiiya.petcare.model.formatQuantity
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** ペットの目安カロリーとプランの計算結果 */
fun planFor(data: AppData, pet: Pet, today: LocalDate = LocalDate.now()): Pair<Nutrition.Energy, Nutrition.PlanResult> {
    val energy = Nutrition.energy(pet, today)
    val items = data.plans.filter { it.petId == pet.id }.mapNotNull { item -> data.food(item.foodId)?.let { item to it } }
    return energy to Nutrition.plan(energy.targetKcal, items)
}

@Composable
fun HomeScreen(
    data: AppData,
    pet: Pet,
    store: PetStore,
    snackbar: SnackbarHostState,
    modifier: Modifier,
    onGoFood: () -> Unit,
) {
    val today = LocalDate.now()
    val (energy, plan) = planFor(data, pet, today)
    val feeds = data.feedLogs.filter { it.petId == pet.id && it.time.toLocalDate() == today }
    val toilets = data.excretionLogs.filter { it.petId == pet.id && it.time.toLocalDate() == today }
    val scope = rememberCoroutineScope()
    var feedDialog by remember { mutableStateOf<FeedDialogRequest?>(null) }
    var editTimeOf by remember { mutableStateOf<Any?>(null) }
    var editToilet by remember { mutableStateOf<ExcretionLog?>(null) }

    fun recorded(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, actionLabel = "取り消す", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
    }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val alerts = healthAlerts(data, pet)
        val upcoming = data.health
            .filter { it.petId == pet.id && it.nextDate != null && !it.nextDate.isBefore(today) && !it.nextDate.isAfter(today.plusDays(14)) }
            .sortedBy { it.nextDate }
        if (alerts.isNotEmpty() || upcoming.isNotEmpty()) {
            item {
                SectionCard(container = if (alerts.isNotEmpty()) Pastel.PeachLight else Pastel.LavenderLight) {
                    for (a in alerts) Text("⚠ $a", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF8A3B2E))
                    for (r in upcoming) {
                        val next = r.nextDate ?: continue
                        val days = java.time.temporal.ChronoUnit.DAYS.between(today, next)
                        Text(
                            "${r.kind.emoji} ${r.title}：" + if (days == 0L) "今日が予定日です" else "あと${days}日（${next.jpText()}）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            val eaten = feeds.sumOf { it.kcal }
            val treats = feeds.filter { it.kind == FoodKind.TREAT }.sumOf { it.kcal }
            val target = energy.targetKcal
            SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Text("${today.jpText()} の ${pet.name}", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${eaten.roundToInt()}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        " / ${formatKcal(target)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                val ratio = if (target > 0) (eaten / target).toFloat() else 0f
                LinearProgressIndicator(
                    progress = { ratio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = if (ratio > 1.1f) Pastel.Peach else MaterialTheme.colorScheme.primary,
                    trackColor = Color.White,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                val diff = target - eaten
                Text(
                    when {
                        diff > 0.5 -> "あと ${formatKcal(diff)} あげられます"
                        diff < -target * 0.1 -> "目安より ${formatKcal(-diff)} 多めです"
                        else -> "今日の目安に届きました 🎉"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (treats > 0) {
                    val limit = target * Nutrition.TREAT_LIMIT_RATIO
                    Text(
                        "おやつ ${formatKcal(treats)}（目安の10% = ${formatKcal(limit)} まで）" +
                            if (treats > limit) " ⚠ あげすぎ" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (treats > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        item {
            SectionCard(title = "🍚 ごはんをあげた") {
                if (plan.lines.isEmpty()) {
                    EmptyHint("「ごはん」タブでフードを登録して、ごはんプランを作るとワンタップで記録できます。")
                    TextButton(onClick = onGoFood) { Text("ごはんプランを作る") }
                }
                for (line in plan.lines) {
                    val given = feeds.count { it.foodId == line.food.id }
                    val perDay = if (line.item.cycleDays == 1) "／${line.item.timesPerCycle}" else ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { feedDialog = FeedDialogRequest(line.food.id, line.amountPerServing) },
                        ) {
                            Text(line.food.name, fontWeight = FontWeight.Bold)
                            Text(
                                "1回 ${formatQuantity(line.amountPerServing, line.food.unitLabel)}・${cycleText(line.item.timesPerCycle, line.item.cycleDays)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "今日 $given$perDay 回" + lastGivenText(data, pet, line.food.id, today),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Button(
                            onClick = {
                                val log = FeedLog(
                                    Ids.next(), pet.id, line.food.id, line.food.name, line.food.kind,
                                    line.amountPerServing, line.food.unitLabel,
                                    line.amountPerServing * line.food.kcalPerUnit, System.currentTimeMillis(),
                                )
                                store.addFeedLog(log)
                                recorded("${line.food.name}を記録しました") { store.deleteFeedLog(log.id) }
                            },
                            enabled = line.amountPerServing > 0,
                        ) { Text("あげた") }
                    }
                }
                if (data.foods.isNotEmpty()) {
                    TextButton(onClick = { feedDialog = FeedDialogRequest(null, null) }) {
                        Text("＋ 量を変えて・プラン外のごはんやおやつを記録")
                    }
                }
            }
        }

        item {
            SectionCard(title = "🚽 トイレ") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (type in ExcretionType.entries) {
                        val (bg, fg) = excretionColors(type)
                        Button(
                            onClick = {
                                val log = ExcretionLog(Ids.next(), pet.id, type, System.currentTimeMillis())
                                store.addExcretion(log)
                                recorded("${type.label}を記録しました") { store.deleteExcretion(log.id) }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
                        ) {
                            Text("${type.emoji} ${type.label}", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                for (type in ExcretionType.entries) {
                    val list = toilets.filter { it.type == type }
                    val last = list.maxOfOrNull { it.time }
                    Text(
                        "${type.emoji} 今日 ${list.size} 回" + (last?.let { "（最後 ${it.timeText()}）" } ?: "") +
                            lastExcretionText(data, pet, type, today, list.isEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "📝 今日の記録") {
                val entries = (feeds.map { it.time to (it as Any) } + toilets.map { it.time to (it as Any) })
                    .sortedByDescending { it.first }
                if (entries.isEmpty()) EmptyHint("まだ記録がありません。時刻をタップすると修正、トイレの記録をタップすると状態（かたさ・血が混じる など）を残せます。")
                entries.forEachIndexed { index, (_, entry) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    when (entry) {
                        is FeedLog -> FeedLogRow(
                            entry,
                            onTime = { editTimeOf = entry },
                            onEdit = { feedDialog = FeedDialogRequest(entry.foodId, entry.amount, entry) },
                            onDelete = { store.deleteFeedLog(entry.id) },
                        )
                        is ExcretionLog -> ExcretionLogRow(
                            entry,
                            onTime = { editTimeOf = entry },
                            onEdit = { editToilet = entry },
                            onDelete = { store.deleteExcretion(entry.id) },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    feedDialog?.let { req ->
        FeedDialog(
            data = data,
            pet = pet,
            day = today,
            request = req,
            onDismiss = { feedDialog = null },
            onSave = { log ->
                if (req.editing != null) store.updateFeedLog(log) else store.addFeedLog(log)
                feedDialog = null
            },
        )
    }
    editToilet?.let { log ->
        ExcretionDialog(
            initial = log,
            isNew = false,
            onDismiss = { editToilet = null },
            onSave = { store.updateExcretion(it); editToilet = null },
            onDelete = { store.deleteExcretion(log.id); editToilet = null },
        )
    }
    when (val target = editTimeOf) {
        is FeedLog -> TimePickDialog(target.time, onDismiss = { editTimeOf = null }) {
            store.updateFeedLog(target.copy(time = it)); editTimeOf = null
        }
        is ExcretionLog -> TimePickDialog(target.time, onDismiss = { editTimeOf = null }) {
            store.updateExcretion(target.copy(time = it)); editTimeOf = null
        }
    }
}

fun cycleText(times: Int, days: Int): String = when {
    days == 1 -> "毎日${times}回"
    days == 7 && times == 1 -> "週1回"
    else -> "${days}日に${times}回"
}

private fun lastGivenText(data: AppData, pet: Pet, foodId: Long, today: LocalDate): String {
    val last = data.feedLogs.filter { it.petId == pet.id && it.foodId == foodId }.maxOfOrNull { it.time } ?: return ""
    val days = java.time.temporal.ChronoUnit.DAYS.between(last.toLocalDate(), today)
    return if (days >= 1) "・前回 ${days}日前" else ""
}

private fun lastExcretionText(data: AppData, pet: Pet, type: ExcretionType, today: LocalDate, noneToday: Boolean): String {
    if (!noneToday) return ""
    val last = data.excretionLogs.filter { it.petId == pet.id && it.type == type }.maxOfOrNull { it.time } ?: return ""
    val days = java.time.temporal.ChronoUnit.DAYS.between(last.toLocalDate(), today)
    return " 前回は${days}日前 ${last.timeText()}"
}

fun excretionColors(type: ExcretionType): Pair<Color, Color> = when (type) {
    ExcretionType.POOP -> Pastel.LatteLight to Color(0xFF6B4A2E)
    ExcretionType.PEE -> Pastel.LemonLight to Color(0xFF6B5A16)
}

@Composable
fun FeedLogRow(log: FeedLog, onTime: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onTime) { Text(log.time.timeText(), fontWeight = FontWeight.Bold) }
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text((if (log.kind == FoodKind.TREAT) "🍪 " else "🍚 ") + log.foodName)
            Text(
                "${formatQuantity(log.amount, log.unitLabel)}・${formatKcal(log.kcal)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) { Text("削除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun ExcretionLogRow(log: ExcretionLog, onTime: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onTime) { Text(log.time.timeText(), fontWeight = FontWeight.Bold) }
        val (bg, fg) = excretionColors(log.type)
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Row { Pill("${log.type.emoji} ${log.type.label}", bg, fg) }
            val detail = excretionDetail(log)
            Text(
                detail.ifBlank { "タップで状態を記録" },
                style = MaterialTheme.typography.bodySmall,
                color = if (log.abnormal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) { Text("削除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
