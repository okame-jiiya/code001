@file:OptIn(ExperimentalLayoutApi::class)

package com.okamejiiya.petcare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okamejiiya.petcare.data.CalendarShow
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.data.UiPrefs
import com.okamejiiya.petcare.data.Screen
import com.okamejiiya.petcare.data.Section
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.Expense
import com.okamejiiya.petcare.model.ExpenseCategory
import com.okamejiiya.petcare.model.FeedLog
import com.okamejiiya.petcare.model.HealthKind
import com.okamejiiya.petcare.model.HealthRecord
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.RecordItem
import com.okamejiiya.petcare.model.mealLabel
import com.okamejiiya.petcare.model.RecordLog
import com.okamejiiya.petcare.model.WeightLog
import com.okamejiiya.petcare.model.formatAmount
import com.okamejiiya.petcare.model.formatKcal
import com.okamejiiya.petcare.model.formatQuantity
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.roundToInt

/** ワンタップ記録で使う時刻 */
/** いまの時刻にいちばん近いごはんの時刻（日をまたいで比べる） */
private fun nearestMeal(times: List<LocalTime>, now: LocalTime = LocalTime.now()): Int {
    if (times.isEmpty()) return -1
    val m = now.toSecondOfDay() / 60
    return times.indices.minBy { i ->
        val d = kotlin.math.abs(times[i].toSecondOfDay() / 60 - m)
        minOf(d, 24 * 60 - d)
    }
}

/** カレンダーの上で選ぶ「何を記録するか」 */
private enum class CalendarMode(val label: String) {
    RECORD("🍚 ごはん・排泄"),
    DIARY("📓 日記"),
    SCHEDULE("🗓 予定"),
    EXPENSE("💴 支出"),
}

/**
 * 予定・記録・日記・支出をまとめたカレンダー。
 * 上で記録するものを選び、日付をタップして、下のボタンから登録する。
 * マスに出す内容は「表示」から選べる（どのモードでも同じものを出す）。
 */
@Composable
fun CalendarScreen(data: AppData, pet: Pet, store: PetStore, prefs: UiPrefs, snackbar: SnackbarHostState, modifier: Modifier) {
    val today = LocalDate.now()
    var mode by rememberSaveable { mutableStateOf(CalendarMode.RECORD) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    val showNames by prefs.calendarShow.collectAsState()
    fun shows(s: CalendarShow) = s.name in showNames

    var feedDialog by remember { mutableStateOf<FeedDialogRequest?>(null) }
    var editTimeOf by remember { mutableStateOf<Any?>(null) }
    var addToilet by remember { mutableStateOf<ExcretionType?>(null) }
    var editToilet by remember { mutableStateOf<ExcretionLog?>(null) }
    var healthDialog by remember { mutableStateOf<HealthRecord?>(null) }
    var recordDialog by remember { mutableStateOf<Pair<RecordLog, Boolean>?>(null) }
    var weightDialog by remember { mutableStateOf<WeightLog?>(null) }
    var expenseDialog by remember { mutableStateOf<Pair<Expense, Boolean>?>(null) }
    val mealTimes = data.settings.mealTimes
    // -1 は「いまの時刻」、それ以外は mealTimes の番号
    var slot by rememberSaveable { mutableIntStateOf(if (data.settings.quickAtMealTime) nearestMeal(mealTimes) else -1) }
    val scope = rememberCoroutineScope()

    fun quickTime(): Long = selected.atTime(mealTimes.getOrNull(slot) ?: LocalTime.now().withSecond(0).withNano(0)).toMillis()
    val undoHistory = LocalUndo.current
    fun recorded(message: String, undo: () -> Unit) =
        recordedWithUndo(scope, snackbar, undoHistory, "${pet.name}：$message", undo)
    val dayLabel = "${selected.monthValue}/${selected.dayOfMonth}"

    val feedsByDay = remember(data.feedLogs, pet.id) { data.feedLogs.filter { it.petId == pet.id }.groupBy { it.time.toLocalDate() } }
    val toiletsByDay = remember(data.excretionLogs, pet.id) { data.excretionLogs.filter { it.petId == pet.id }.groupBy { it.time.toLocalDate() } }
    val moodByDay = remember(data.moods, pet.id) { data.moods.filter { it.petId == pet.id }.associate { it.date to it.mood } }
    val healthByDay = remember(data.health, pet.id) { data.health.filter { it.petId == pet.id }.groupBy { it.date } }
    val recordsByDay = remember(data.records, pet.id) { data.records.filter { it.petId == pet.id }.groupBy { it.time.toLocalDate() } }
    val weightsByDay = remember(data.weights, pet.id) { data.weights.filter { it.petId == pet.id }.groupBy { it.date } }
    val diaryByDay = remember(data.diaries, pet.id) { data.diaries.filter { it.petId == pet.id }.associate { it.date to it.text } }
    // 支出は「この子の分」と「みんなの分」
    val expensesByDay = remember(data.expenses, pet.id) {
        data.expenses.filter { it.petId == null || it.petId == pet.id }.groupBy { it.date }
    }
    val target = Nutrition.energy(pet, today).targetKcal
    val (_, plan) = planFor(data, pet, today)
    val toiletTypes = ExcretionType.entries.filter { data.isOn(if (it == ExcretionType.POOP) RecordItem.POOP else RecordItem.PEE) }

    // マスに出す行が多いときは、マスを少し縦長にする
    val lines = listOf(CalendarShow.KCAL, CalendarShow.TOILET, CalendarShow.RECORDS, CalendarShow.EXPENSE).count { shows(it) }
    val calSize by prefs.calendarSize.collectAsState()
    val baseAspect = when {
        lines >= 4 -> 0.55f
        lines == 3 -> 0.62f
        else -> 0.72f
    }
    val aspect = baseAspect / calSize
    val layout by prefs.layout.collectAsState()
    val hidden by prefs.hidden.collectAsState()
    val sections = layout.getValue(Screen.CALENDAR).filter { it !in hidden }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (m in CalendarMode.entries) ChoiceChip(m.label, mode == m) { mode = m }
            }
        }
        for (section in sections) when (section) {
        Section.CAL_GRID -> item {
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
                    dayBadge = { if (shows(CalendarShow.MOOD)) moodByDay[it]?.emoji else null },
                    aspect = aspect,
                ) { day ->
                    if (shows(CalendarShow.KCAL)) {
                        val kcal = feedsByDay[day]?.sumOf { it.kcal } ?: 0.0
                        if (kcal > 0) {
                            Text(
                                "${kcal.roundToInt()}",
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                color = Pastel.Ink,
                                modifier = Modifier
                                    .background(kcalColor(kcal, target), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 3.dp),
                            )
                        }
                    }
                    if (shows(CalendarShow.TOILET)) {
                        val list = toiletsByDay[day].orEmpty()
                        val poop = list.count { it.type == ExcretionType.POOP }
                        val pee = list.count { it.type == ExcretionType.PEE }
                        val text = (if (poop > 0) "💩$poop" else "") + (if (pee > 0) "💧$pee" else "") + if (list.any { it.abnormal }) "⚠" else ""
                        if (text.isNotEmpty()) Text(text, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                    if (shows(CalendarShow.RECORDS)) {
                        val emojis = (recordsByDay[day].orEmpty().map { it.item.emoji } + if (weightsByDay[day] != null) listOf(RecordItem.WEIGHT.emoji) else emptyList())
                            .distinct()
                        if (emojis.isNotEmpty()) Text(emojis.take(3).joinToString(""), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
                    }
                    if (shows(CalendarShow.EXPENSE)) {
                        val sum = expensesByDay[day]?.sumOf { it.amount } ?: 0L
                        if (sum > 0) Text(shortYen(sum), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, color = Color(0xFF8A5A2E))
                    }
                    val kinds = if (shows(CalendarShow.SCHEDULE)) healthByDay[day].orEmpty().map { it.kind }.distinct() else emptyList()
                    val diary = shows(CalendarShow.DIARY) && diaryByDay[day] != null
                    if (kinds.isNotEmpty() || diary) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.padding(bottom = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (k in kinds.take(3)) KindDot(k, 6)
                            if (diary) Text("📓", fontSize = 8.sp, lineHeight = 9.sp)
                        }
                    }
                }
                MonthSummary(data, pet, month, mode, expensesByDay, diaryByDay)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shows(CalendarShow.KCAL)) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("少なめ", kcalColor(1.0, 2.0))
                            Pill("ちょうど", kcalColor(1.0, 1.0))
                            Pill("多め", kcalColor(2.0, 1.0))
                        }
                    } else Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "閉じる" else "👁 表示") }
                }
                if (showOptions) {
                    Text("カレンダーに出すもの", style = MaterialTheme.typography.labelLarge)
                    Column {
                        for (opt in CalendarShow.entries) CheckLine(opt.label, shows(opt)) { prefs.setCalendarShow(opt, !shows(opt)) }
                    }
                    if (shows(CalendarShow.SCHEDULE)) {
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
            }
        }

        // ---- 選んだ日にすぐ登録する ----
        Section.CAL_QUICK -> item {
            when (mode) {
                CalendarMode.RECORD -> SectionCard(title = "⚡ $dayLabel にワンタップで記録", container = MaterialTheme.colorScheme.secondaryContainer) {
                    UndoBar(undoHistory)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceChip("いまの時刻", slot !in mealTimes.indices) { slot = -1 }
                        mealTimes.forEachIndexed { i, t -> ChoiceChip("${mealLabel(t)} ${t.hmText()}", slot == i) { slot = i } }
                    }
                    if (data.isOn(RecordItem.FOOD)) {
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
                                        recorded("$dayLabel ${log.time.timeText()} に${line.food.name}を記録しました") { store.deleteFeedLog(log.id) }
                                    },
                                ) { Text("＋ ${line.food.name} ${formatQuantity(line.amountPerServing, line.food.unitLabel)}") }
                            }
                        }
                    }
                    if (toiletTypes.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (t in toiletTypes) {
                                val (bg, fg) = excretionColors(t)
                                Button(
                                    onClick = {
                                        val log = ExcretionLog(Ids.next(), pet.id, t, quickTime())
                                        store.addExcretion(log)
                                        recorded("$dayLabel ${log.time.timeText()} に${t.label}を記録しました") { store.deleteExcretion(log.id) }
                                    },
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
                                ) { Text("${t.emoji} ${t.label}", style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                    QuickRecordChips(
                        data, pet,
                        timeFor = { quickTime() },
                        onQuick = { r ->
                            store.upsertRecord(r)
                            recorded("$dayLabel ${r.time.timeText()} に${r.item.label}を記録しました") { store.deleteRecord(r.id) }
                        },
                        onOpen = { recordDialog = it to true },
                        onWeight = { weightDialog = it },
                    )
                    // きげんは、大事なごはん・排泄の後に
                    if (data.isOn(RecordItem.MOOD)) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            MoodPicker(moodByDay[selected]) { store.setMood(pet.id, selected, it) }
                        }
                    }
                    EmptyHint("時刻を選んでからボタンを押すと、その時刻で記録します。押し間違えたら上の「↩ 取り消す」で戻せます。量や状態は下の一覧をタップして直せます。ごはんの時刻は「設定」で変えられます。")
                }
                CalendarMode.DIARY -> {
                    val saved = diaryByDay[selected].orEmpty()
                    var text by remember(selected, pet.id, saved) { mutableStateOf(saved) }
                    SectionCard(title = "📓 $dayLabel の日記", container = Pastel.LavenderLight) {
                        OutlinedTextField(
                            text, { text = it },
                            placeholder = { Text("今日の様子、できるようになったこと、気になったことなど") },
                            minLines = 4,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = text.trim() != saved, onClick = {
                                store.setDiary(pet.id, selected, text)
                                recorded("$dayLabel の日記を保存しました") { store.setDiary(pet.id, selected, saved) }
                            }) { Text("保存") }
                            if (saved.isNotEmpty()) TextButton(onClick = {
                                store.setDiary(pet.id, selected, "")
                                recorded("日記を消しました") { store.setDiary(pet.id, selected, saved) }
                            }) { Text("消す", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                CalendarMode.SCHEDULE -> SectionCard(title = "🗓 $dayLabel に予定を追加", container = Pastel.PeachLight) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (k in HealthKind.entries) {
                            AssistChip(
                                onClick = {
                                    healthDialog = HealthRecord(
                                        0, pet.id, k, selected, "",
                                        notifyDayBeforeHour = if (selected.isBefore(today)) null else 20,
                                    )
                                },
                                label = { Text("${k.emoji} ${k.label}") },
                            )
                        }
                    }
                    EmptyHint("前日や当日に通知できます。通院・ワクチンなどの記録として残すときは、過ぎた日付を選んでください。")
                }
                CalendarMode.EXPENSE -> SectionCard(title = "💴 $dayLabel の支出を記録", container = Pastel.LemonLight) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in ExpenseCategory.entries) {
                            AssistChip(
                                onClick = { expenseDialog = Expense(Ids.next(), pet.id, selected, c, 0) to true },
                                label = { Text("${c.emoji} ${c.label}") },
                            )
                        }
                    }
                }
            }
        }

        // ---- 選んだ日のまとめ（どのモードでも全部出す） ----
        Section.CAL_DAY -> item {
            SectionCard(title = selected.jpText()) {
                val feeds = feedsByDay[selected].orEmpty()
                val toilets = toiletsByDay[selected].orEmpty()
                val records = recordsByDay[selected].orEmpty()
                val weights = weightsByDay[selected].orEmpty()
                if (feeds.isNotEmpty() || data.isOn(RecordItem.FOOD)) {
                    Text(
                        "🍚 合計 ${formatKcal(feeds.sumOf { it.kcal })} ／ 目安 ${formatKcal(target)}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (toilets.isNotEmpty()) {
                    Text(ExcretionType.entries.joinToString("　") { t -> "${t.emoji} ${toilets.count { it.type == t }}回" }, fontWeight = FontWeight.Bold)
                }
                moodByDay[selected]?.let { Text("きげん ${it.emoji} ${it.label}") }
                val entries = (feeds.map { it.time to (it as Any) } + toilets.map { it.time to (it as Any) } + records.map { it.time to (it as Any) })
                    .sortedBy { it.first }
                if (entries.isEmpty() && weights.isEmpty()) EmptyHint("この日の記録はありません。")
                for (w in weights) {
                    Row(Modifier.fillMaxWidth().clickable { weightDialog = w }.padding(vertical = 4.dp)) {
                        Text("⚖ 体重" + (w.time?.let { " ${it.hmText()}" } ?: ""), modifier = Modifier.weight(1f))
                        Text("${formatAmount(w.kg)} kg", fontWeight = FontWeight.Bold)
                    }
                }
                entries.forEachIndexed { i, (_, e) ->
                    if (i > 0 || weights.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    when (e) {
                        is FeedLog -> FeedLogRow(
                            e,
                            onTime = { editTimeOf = e },
                            onEdit = { feedDialog = FeedDialogRequest(e.foodId, e.amount, e) },
                            onDelete = { store.deleteFeedLog(e.id) },
                        )
                        is ExcretionLog -> ExcretionLogRow(
                            e,
                            onTime = { editTimeOf = e },
                            onEdit = { editToilet = e },
                            onDelete = { store.deleteExcretion(e.id) },
                        )
                        is RecordLog -> RecordRow(e, onEdit = { recordDialog = e to false }, onDelete = { store.deleteRecord(e.id) })
                    }
                }
                if (mode == CalendarMode.RECORD) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (data.isOn(RecordItem.FOOD)) {
                            TextButton(onClick = { feedDialog = FeedDialogRequest(null, null) }, enabled = data.foods.isNotEmpty()) {
                                Text("＋ 量やフードを選んで記録")
                            }
                        }
                        for (t in toiletTypes) TextButton(onClick = { addToilet = t }) { Text("＋ ${t.emoji} 状態や写真も入れて記録") }
                    }
                }

                val events = healthByDay[selected].orEmpty().sortedBy { it.startsAt() }
                if (events.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("🗓 予定・通院", style = MaterialTheme.typography.labelLarge)
                    for (r in events) HealthRow(r, today) { healthDialog = r }
                }
                diaryByDay[selected]?.let { text ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("📓 日記", style = MaterialTheme.typography.labelLarge)
                    Text(text, modifier = Modifier.fillMaxWidth().clickable { mode = CalendarMode.DIARY })
                }
                val spent = expensesByDay[selected].orEmpty()
                if (spent.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    val dayAll = data.expenses.filter { it.date == selected }.sumOf { it.amount }
                    Text(
                        "💴 支出 ${pet.name} ${yen(spent.filter { it.petId == pet.id }.sumOf { it.amount })}・全体 ${yen(dayAll)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    for (e in spent) ExpenseRow(data, e) { expenseDialog = e to false }
                }
            }
        }
        else -> Unit
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
    recordDialog?.let { (r, isNew) ->
        RecordDialog(
            initial = r,
            isNew = isNew,
            onDismiss = { recordDialog = null },
            onSave = { store.upsertRecord(it); recordDialog = null },
            onDelete = if (isNew) null else ({ store.deleteRecord(r.id); recordDialog = null }),
        )
    }
    weightDialog?.let { w ->
        WeightDialog(
            w,
            onDismiss = { weightDialog = null },
            onSave = { store.upsertWeight(it); weightDialog = null },
            onDelete = if (w.id != 0L) ({ store.deleteWeight(w); weightDialog = null }) else null,
        )
    }
    expenseDialog?.let { (e, isNew) ->
        ExpenseDialog(
            data, e, isNew,
            onDismiss = { expenseDialog = null },
            onSave = { store.upsertExpense(it); expenseDialog = null },
            onDelete = if (isNew) null else ({ store.deleteExpense(e.id); expenseDialog = null }),
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

/** 月のまとめ（支出の合計・日記の日数など） */
@Composable
private fun MonthSummary(
    data: AppData,
    pet: Pet,
    month: YearMonth,
    mode: CalendarMode,
    expensesByDay: Map<LocalDate, List<Expense>>,
    diaryByDay: Map<LocalDate, String>,
) {
    val inMonth = { d: LocalDate -> YearMonth.from(d) == month }
    when (mode) {
        CalendarMode.EXPENSE -> {
            val all = data.expenses.filter { inMonth(it.date) }
            val list = expensesByDay.filterKeys(inMonth).values.flatten()
            val own = list.filter { it.petId == pet.id }.sumOf { it.amount }
            val common = list.filter { it.petId == null }.sumOf { it.amount }
            Text("今月の支出", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill("${pet.name} ${yen(own)}", Pastel.PinkLight)
                if (common > 0) Pill("みんなの分 ${yen(common)}", Pastel.MintLight)
                Pill("全体 ${yen(all.sumOf { it.amount })}", Pastel.LemonLight)
            }
            if (data.pets.size > 1 || common > 0) {
                EmptyHint("全体は、すべての子の分とみんなの分（どの子のものでもない支出）の合計です。")
            }
            val byCat = list.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }.toList().sortedByDescending { it.second }
            if (byCat.isNotEmpty()) {
                Text(
                    byCat.joinToString("　") { (c, v) -> "${c.emoji}${yen(v)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CalendarMode.DIARY -> Text("今月の日記 ${diaryByDay.keys.count(inMonth)}日", style = MaterialTheme.typography.bodySmall)
        CalendarMode.SCHEDULE -> {
            val n = data.health.count { it.petId == pet.id && inMonth(it.date) }
            Text("今月の予定・記録 ${n}件", style = MaterialTheme.typography.bodySmall)
        }
        CalendarMode.RECORD -> Unit
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
    aspect: Float = 0.72f,
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
                            .aspectRatio(aspect),
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
