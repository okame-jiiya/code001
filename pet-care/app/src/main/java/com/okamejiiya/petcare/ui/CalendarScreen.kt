@file:OptIn(ExperimentalLayoutApi::class)

package com.okamejiiya.petcare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.FeedLog
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.formatKcal
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import com.okamejiiya.petcare.model.HealthKind
import com.okamejiiya.petcare.model.HealthRecord
import com.okamejiiya.petcare.model.formatQuantity
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/** ワンタップ記録で使う時刻 */
private enum class QuickSlot(val label: String, val time: LocalTime?) {
    NOW("いまの時刻", null),
    MORNING("朝 7:00", LocalTime.of(7, 0)),
    NOON("昼 12:00", LocalTime.of(12, 0)),
    EVENING("夕 18:00", LocalTime.of(18, 0)),
    NIGHT("夜 22:00", LocalTime.of(22, 0)),
}

private enum class CalendarMode(val label: String) { FOOD("🍚 ごはん"), TOILET("🚽 トイレ") }

@Composable
fun CalendarScreen(data: AppData, pet: Pet, store: PetStore, snackbar: SnackbarHostState, modifier: Modifier) {
    val today = LocalDate.now()
    var mode by rememberSaveable { mutableStateOf(CalendarMode.FOOD) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)

    var feedDialog by remember { mutableStateOf<FeedDialogRequest?>(null) }
    var editTimeOf by remember { mutableStateOf<Any?>(null) }
    var addToilet by remember { mutableStateOf<ExcretionType?>(null) }
    var editToilet by remember { mutableStateOf<ExcretionLog?>(null) }
    var healthDialog by remember { mutableStateOf<HealthRecord?>(null) }
    var slot by rememberSaveable { mutableStateOf(QuickSlot.NOW) }
    val scope = rememberCoroutineScope()

    fun quickTime(): Long = selected.atTime(slot.time ?: LocalTime.now().withSecond(0).withNano(0)).toMillis()
    fun recorded(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, actionLabel = "取り消す", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
    }

    val feedsByDay = remember(data.feedLogs, pet.id) {
        data.feedLogs.filter { it.petId == pet.id }.groupBy { it.time.toLocalDate() }
    }
    val toiletsByDay = remember(data.excretionLogs, pet.id) {
        data.excretionLogs.filter { it.petId == pet.id }.groupBy { it.time.toLocalDate() }
    }
    val moodByDay = remember(data.moods, pet.id) {
        data.moods.filter { it.petId == pet.id }.associate { it.date to it.mood }
    }
    val healthByDay = remember(data.health, pet.id) {
        data.health.filter { it.petId == pet.id }.groupBy { it.date }
    }
    val target = Nutrition.energy(pet, today).targetKcal
    val (_, plan) = planFor(data, pet, today)

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in CalendarMode.entries) ChoiceChip(m.label, mode == m) { mode = m }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { monthText = month.minusMonths(1).toString() }) { Text("◀") }
                    Text(
                        "${month.year}年${month.monthValue}月",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { monthText = month.plusMonths(1).toString() }) { Text("▶") }
                }
                MonthGrid(
                    month = month,
                    today = today,
                    selected = selected,
                    onSelect = { selectedText = it.toString() },
                    dayBadge = { moodByDay[it]?.emoji },
                ) { day ->
                    when (mode) {
                        CalendarMode.FOOD -> {
                            val kcal = feedsByDay[day]?.sumOf { it.kcal } ?: 0.0
                            if (kcal > 0) {
                                Text(
                                    "${kcal.roundToInt()}",
                                    fontSize = 10.sp,
                                    color = Pastel.Ink,
                                    modifier = Modifier
                                        .background(kcalColor(kcal, target), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 3.dp),
                                )
                            }
                        }
                        CalendarMode.TOILET -> {
                            val list = toiletsByDay[day].orEmpty()
                            val poop = list.count { it.type == ExcretionType.POOP }
                            val pee = list.count { it.type == ExcretionType.PEE }
                            if (poop > 0) Text("💩$poop", fontSize = 10.sp, lineHeight = 11.sp)
                            if (pee > 0) Text("💧$pee", fontSize = 10.sp, lineHeight = 11.sp)
                            if (list.any { it.abnormal }) Text("⚠", fontSize = 10.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    val kinds = healthByDay[day].orEmpty().map { it.kind }.distinct()
                    if (kinds.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (k in kinds.take(4)) KindDot(k, 6)
                        }
                    }
                }
                if (mode == CalendarMode.FOOD) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("少なめ", kcalColor(1.0, 2.0))
                        Pill("ちょうど", kcalColor(1.0, 1.0))
                        Pill("多め", kcalColor(2.0, 1.0))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in HealthKind.entries) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            KindDot(k, 8)
                            Text(k.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "⚡ ${selected.monthValue}/${selected.dayOfMonth} にワンタップで記録", container = MaterialTheme.colorScheme.secondaryContainer) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (q in QuickSlot.entries) ChoiceChip(q.label, slot == q) { slot = q }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MoodPicker(moodByDay[selected]) { store.setMood(pet.id, selected, it) }
                }
                when (mode) {
                    CalendarMode.FOOD -> {
                        if (plan.lines.isEmpty()) EmptyHint("「ごはん」タブでプランを作ると、ここからワンタップで記録できます。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (line in plan.lines) {
                                Button(
                                    enabled = line.amountPerServing > 0,
                                    onClick = {
                                        val log = FeedLog(
                                            Ids.next(), pet.id, line.food.id, line.food.name, line.food.kind,
                                            line.amountPerServing, line.food.unitLabel,
                                            line.amountPerServing * line.food.kcalPerUnit, quickTime(),
                                        )
                                        store.addFeedLog(log)
                                        recorded("${selected.monthValue}/${selected.dayOfMonth} ${log.time.timeText()} に${line.food.name}を記録しました") {
                                            store.deleteFeedLog(log.id)
                                        }
                                    },
                                ) { Text("＋ ${line.food.name} ${formatQuantity(line.amountPerServing, line.food.unitLabel)}") }
                            }
                        }
                    }
                    CalendarMode.TOILET -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (t in ExcretionType.entries) {
                                val (bg, fg) = excretionColors(t)
                                Button(
                                    onClick = {
                                        val log = ExcretionLog(Ids.next(), pet.id, t, quickTime())
                                        store.addExcretion(log)
                                        recorded("${selected.monthValue}/${selected.dayOfMonth} ${log.time.timeText()} に${t.label}を記録しました") {
                                            store.deleteExcretion(log.id)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
                                ) { Text("${t.emoji} ${t.label}", style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
                EmptyHint("時刻を選んでからボタンを押すと、その時刻で記録します。量や状態は下の一覧をタップして直せます。")
            }
        }
        item {
            SectionCard(title = selected.jpText()) {
                when (mode) {
                    CalendarMode.FOOD -> {
                        val list = feedsByDay[selected].orEmpty().sortedBy { it.time }
                        Text(
                            "合計 ${formatKcal(list.sumOf { it.kcal })} ／ 目安 ${formatKcal(target)}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (list.isEmpty()) EmptyHint("この日の記録はありません。")
                        list.forEachIndexed { i, log ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            FeedLogRow(
                                log,
                                onTime = { editTimeOf = log },
                                onEdit = { feedDialog = FeedDialogRequest(log.foodId, log.amount, log) },
                                onDelete = { store.deleteFeedLog(log.id) },
                            )
                        }
                        TextButton(
                            onClick = { feedDialog = FeedDialogRequest(null, null) },
                            enabled = data.foods.isNotEmpty(),
                        ) { Text("＋ 量やフードを選んで記録") }
                    }
                    CalendarMode.TOILET -> {
                        val list = toiletsByDay[selected].orEmpty().sortedBy { it.time }
                        Text(
                            ExcretionType.entries.joinToString("　") { t -> "${t.emoji} ${list.count { it.type == t }}回" },
                            fontWeight = FontWeight.Bold,
                        )
                        if (list.isEmpty()) EmptyHint("この日の記録はありません。")
                        list.forEachIndexed { i, log ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ExcretionLogRow(
                                log,
                                onTime = { editTimeOf = log },
                                onEdit = { editToilet = log },
                                onDelete = { store.deleteExcretion(log.id) },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in ExcretionType.entries) {
                                TextButton(onClick = { addToilet = t }) { Text("＋ ${t.emoji} 状態も入れて記録") }
                            }
                        }
                    }
                }
            }
        }
        item {
            val events = healthByDay[selected].orEmpty().sortedBy { it.startsAt() }
            SectionCard(title = "🗓 この日の予定・通院") {
                if (events.isEmpty()) EmptyHint("予定はありません。")
                events.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HealthRow(r, today) { healthDialog = r }
                }
                TextButton(onClick = {
                    healthDialog = HealthRecord(
                        0, pet.id, HealthKind.TRIMMING, selected, "",
                        notifyDayBeforeHour = if (selected.isBefore(today)) null else 20,
                    )
                }) { Text("＋ この日に予定を追加") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    healthDialog?.let { r ->
        HealthDialog(
            initial = r,
            onDismiss = { healthDialog = null },
            onSave = { store.upsertHealth(it); healthDialog = null },
            onDelete = if (r.id != 0L) ({ store.deleteHealth(r.id); healthDialog = null }) else null,
        )
    }
    feedDialog?.let { req ->
        FeedDialog(
            data = data,
            pet = pet,
            day = selected,
            request = req,
            onDismiss = { feedDialog = null },
            onSave = { log ->
                if (req.editing != null) store.updateFeedLog(log) else store.addFeedLog(log)
                feedDialog = null
            },
        )
    }
    addToilet?.let { type ->
        ExcretionDialog(
            initial = ExcretionLog(Ids.next(), pet.id, type, defaultTimeFor(selected)),
            isNew = true,
            onDismiss = { addToilet = null },
            onSave = { store.addExcretion(it); addToilet = null },
            onDelete = null,
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
    when (val t = editTimeOf) {
        is FeedLog -> TimePickDialog(t.time, onDismiss = { editTimeOf = null }) {
            store.updateFeedLog(t.copy(time = it)); editTimeOf = null
        }
        is ExcretionLog -> TimePickDialog(t.time, onDismiss = { editTimeOf = null }) {
            store.updateExcretion(t.copy(time = it)); editTimeOf = null
        }
    }
}

private fun kcalColor(kcal: Double, target: Double): Color {
    if (target <= 0) return Pastel.PinkLight
    val r = kcal / target
    return when {
        r < 0.8 -> Pastel.LemonLight
        r <= 1.1 -> Pastel.MintLight
        else -> Pastel.PeachLight
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    dayBadge: (LocalDate) -> String?,
    cell: @Composable ColumnScope.(LocalDate) -> Unit,
) {
    val offset = month.atDay(1).dayOfWeek.value % 7 // 日曜はじまり
    val cells = offset + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            "日月火水木金土".forEachIndexed { i, c ->
                Text(
                    c.toString(),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (i) {
                        0 -> Color(0xFFD9667A)
                        6 -> Color(0xFF6C8FD0)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.72f),
                    ) {
                        if (dayNum in 1..month.lengthOfMonth()) {
                            val day = month.atDay(dayNum)
                            val isSel = day == selected
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .then(
                                        if (day == today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                        else Modifier,
                                    )
                                    .clickable { onSelect(day) }
                                    .padding(top = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "$dayNum",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    // 機嫌は日付の右肩に小さく出して、カレンダーの高さを変えない
                                    dayBadge(day)?.let { Text(it, fontSize = 9.sp, lineHeight = 10.sp) }
                                }
                                cell(day)
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.fillMaxWidth())
}
