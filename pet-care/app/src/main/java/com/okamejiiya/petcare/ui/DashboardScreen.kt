@file:OptIn(ExperimentalLayoutApi::class)

package com.okamejiiya.petcare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.okamejiiya.petcare.data.Screen
import com.okamejiiya.petcare.data.Section
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.Mood
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.RecordItem
import com.okamejiiya.petcare.model.RecordKind
import com.okamejiiya.petcare.model.formatAmount
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** グラフの線の色（アイコンの背景色を濃くしたもの。同じ子は同じ色） */
private val lineColors = listOf(
    Color(0xFFE8738F), Color(0xFFE89B5C), Color(0xFFC9A800), Color(0xFF4FAF86),
    Color(0xFF4F95C9), Color(0xFF8C6FD1), Color(0xFFA9805A), Color(0xFF7D7D8C),
)
private val totalColor = Color(0xFF5A4A55)

private fun lineColor(pet: Pet) = lineColors[pet.colorIndex.mod(lineColors.size)]

/** グラフの1本の線 */
class ChartSeries(val name: String, val color: Color, val points: List<Pair<LocalDate, Double>>)

/**
 * まとめ（ダッシュボード）。全員の分を並べて、または1頭だけを選んで、
 * 体重・カロリー・トイレ・機嫌・ほかの記録・支出の推移を見る。
 */
@Composable
fun DashboardScreen(data: AppData, modifier: Modifier) {
    val today = LocalDate.now()
    // 0 は「全員」
    var scopeId by rememberSaveable { mutableStateOf(0L) }
    var range by rememberSaveable { mutableStateOf(TrendRange.MONTH) }
    val pets = data.sortedPets.filter { scopeId == 0L || it.id == scopeId }.ifEmpty { data.sortedPets }
    val from = range.days?.let { today.minusDays(it - 1) }
    fun inRange(d: LocalDate) = (from == null || !d.isBefore(from)) && !d.isAfter(today)
    val prefs = LocalPrefs.current
    val layout by prefs.layout.collectAsState()
    val hidden by prefs.hidden.collectAsState()
    val sections = layout.getValue(Screen.DASHBOARD).filter { it !in hidden }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(title = "📊 まとめ") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChoiceChip("全員", scopeId == 0L) { scopeId = 0L }
                    for (p in data.sortedPets) ChoiceChip(p.name, scopeId == p.id) { scopeId = p.id }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in TrendRange.entries) ChoiceChip(r.label, range == r) { range = r }
                }
                EmptyHint("グラフをタップすると、その日の値が出ます。出す項目と順番、下のタブに出すかどうかは「設定」の「画面の表示」で変えられます。")
            }
        }

        for (section in sections) when (section) {
        // ---- 支出 ----
        Section.DASH_EXPENSE -> item {
            val list = data.expenses.filter { inRange(it.date) }
            val total = list.sumOf { it.amount }
            SectionCard(title = "💴 支出") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (p in pets) Pill("${p.name} ${yen(list.filter { it.petId == p.id }.sumOf { it.amount })}", Pastel.PinkLight)
                    val common = list.filter { it.petId == null }.sumOf { it.amount }
                    if (common > 0) Pill("みんなの分 ${yen(common)}", Pastel.MintLight)
                    Pill("全体 ${yen(total)}", Pastel.LemonLight)
                }
                if (list.isEmpty()) {
                    EmptyHint("この期間の支出はありません。カレンダーの「💴 支出」から記録できます。")
                } else {
                    // 1か月なら日ごと、それより長いと月ごとの合計
                    val monthly = range != TrendRange.MONTH
                    fun key(d: LocalDate) = if (monthly) YearMonth.from(d).atDay(1) else d
                    val series = buildList {
                        for (p in pets) {
                            val byKey = list.filter { it.petId == p.id }.groupBy { key(it.date) }.mapValues { e -> e.value.sumOf { it.amount }.toDouble() }
                            if (byKey.isNotEmpty()) add(ChartSeries(p.name, lineColor(p), fillGaps(byKey, monthly)))
                        }
                        if (scopeId == 0L) {
                            val byKey = list.groupBy { key(it.date) }.mapValues { e -> e.value.sumOf { it.amount }.toDouble() }
                            add(ChartSeries("全体", totalColor, fillGaps(byKey, monthly)))
                        }
                    }
                    if (series.isNotEmpty()) MultiLineChart(series, "円", Modifier.fillMaxWidth().height(200.dp))
                    EmptyHint(if (monthly) "月ごとの合計です。" else "日ごとの合計です（記録のない日は 0 円）。")
                    val byCat = list.filter { scopeId == 0L || it.petId == scopeId }
                        .groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }.toList().sortedByDescending { it.second }
                    if (byCat.isNotEmpty()) {
                        Text(
                            byCat.joinToString("　") { (c, v) -> "${c.emoji}${c.label} ${yen(v)}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- 体重 ----
        Section.DASH_WEIGHT -> item {
            val series = pets.mapNotNull { p ->
                val pts = data.weights.filter { it.petId == p.id && inRange(it.date) }.sortedBy { it.date }.map { it.date to it.kg }
                pts.takeIf { it.isNotEmpty() }?.let { ChartSeries(p.name, lineColor(p), it) }
            }
            MetricCard("⚖ 体重", series, "kg") { s -> "${s.name} ${formatAmount(s.points.last().second)} kg（${changeText(s)}）" }
        }

        // ---- カロリー ----
        Section.DASH_KCAL -> if (data.isOn(RecordItem.FOOD)) item {
            val series = pets.mapNotNull { p ->
                val byDay = data.feedLogs.filter { it.petId == p.id }.groupBy { it.time.toLocalDate() }.mapValues { e -> e.value.sumOf { it.kcal } }
                dailySeries(byDay, from, today)?.let { ChartSeries(p.name, lineColor(p), it) }
            }
            MetricCard("🍚 1日のカロリー", series, "kcal") { s ->
                val pet = pets.first { it.name == s.name }
                val avg = s.points.map { it.second }.average()
                val target = Nutrition.energy(pet, today).targetKcal
                "${s.name} 平均 ${formatAmount(avg)} kcal（目安の${if (target > 0) (avg / target * 100).toInt() else 0}%）"
            }
        }

        // ---- トイレ ----
        Section.DASH_POOP, Section.DASH_PEE -> {
            val type = if (section == Section.DASH_POOP) ExcretionType.POOP else ExcretionType.PEE
            if (data.isOn(if (type == ExcretionType.POOP) RecordItem.POOP else RecordItem.PEE)) item(key = "toilet_${type.name}") {
                val series = pets.mapNotNull { p ->
                    val byDay = data.excretionLogs.filter { it.petId == p.id && it.type == type }
                        .groupBy { it.time.toLocalDate() }.mapValues { it.value.size.toDouble() }
                    dailySeries(byDay, from, today)?.let { ChartSeries(p.name, lineColor(p), it) }
                }
                MetricCard("${type.emoji} ${type.label}の回数（1日）", series, "回") { s ->
                    val abnormal = data.excretionLogs.count { l -> l.type == type && l.abnormal && inRange(l.time.toLocalDate()) && pets.any { it.id == l.petId && it.name == s.name } }
                    "${s.name} 平均 ${formatAmount(s.points.map { it.second }.average())} 回" + if (abnormal > 0) "・気になる記録 ${abnormal}件" else ""
                }
            }
        }

        // ---- 機嫌 ----
        Section.DASH_MOOD -> if (data.isOn(RecordItem.MOOD)) item {
            SectionCard(title = "☀️ 機嫌") {
                for (p in pets) {
                    val moods = data.moods.filter { it.petId == p.id && inRange(it.date) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetAvatar(p, 24)
                        HSpace(8)
                        Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            if (moods.isEmpty()) "記録なし" else Mood.entries.joinToString("　") { m -> "${m.emoji}${moods.count { it.mood == m }}" },
                        )
                    }
                }
            }
        }

        // ---- ほかの記録（設定で選んだ項目） ----
        Section.DASH_RECORDS -> for (rec in data.settings.items.filter { !it.special }) {
            item(key = "rec_${rec.name}") {
                if (rec.kind == RecordKind.NUMBER) {
                    val series = pets.mapNotNull { p ->
                        val pts = data.records.filter { it.petId == p.id && it.item == rec && it.value != null && inRange(it.time.toLocalDate()) }
                            .sortedBy { it.time }.map { it.time.toLocalDate() to it.value!! }
                        pts.takeIf { it.isNotEmpty() }?.let { ChartSeries(p.name, lineColor(p), it) }
                    }
                    MetricCard("${rec.emoji} ${rec.title}", series, rec.unit) { s ->
                        "${s.name} 平均 ${formatAmount(s.points.map { it.second }.average())}${rec.unit}（${s.points.size}回）"
                    }
                } else {
                    SectionCard(title = "${rec.emoji} ${rec.label}") {
                        for (p in pets) {
                            val logs = data.records.filter { it.petId == p.id && it.item == rec && inRange(it.time.toLocalDate()) }
                            val last = data.records.filter { it.petId == p.id && it.item == rec }.maxOfOrNull { it.time }?.toLocalDate()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PetAvatar(p, 24)
                                HSpace(8)
                                Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(
                                    "${logs.size}回" + (last?.let { "・最後 ${it.slashText()}" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
        else -> Unit
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MetricCard(title: String, series: List<ChartSeries>, unit: String, summary: (ChartSeries) -> String) {
    SectionCard(title = title) {
        if (series.isEmpty()) {
            EmptyHint("この期間の記録はありません。")
            return@SectionCard
        }
        MultiLineChart(series, unit, Modifier.fillMaxWidth().height(190.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        for (s in series) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(s.color, CircleShape))
                HSpace(8)
                Text(summary(s), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun changeText(s: ChartSeries): String {
    if (s.points.size < 2) return "記録1回"
    val d = s.points.last().second - s.points.first().second
    val sign = if (d > 0) "+" else if (d < 0) "−" else "±"
    return "期間中 $sign${formatAmount(abs(d))}"
}

/** 1日ごとの値（記録のない日は 0）。記録を始める前の日は含めない */
private fun dailySeries(byDay: Map<LocalDate, Double>, from: LocalDate?, today: LocalDate): List<Pair<LocalDate, Double>>? {
    val inRange = byDay.filterKeys { (from == null || !it.isBefore(from)) && !it.isAfter(today) }
    if (inRange.isEmpty()) return null
    val first = maxOf(from ?: byDay.keys.min(), byDay.keys.min())
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { it to (byDay[it] ?: 0.0) }.toList()
}

/** 日ごと・月ごとの合計の、記録のない日（月）を 0 で埋める */
private fun fillGaps(byKey: Map<LocalDate, Double>, monthly: Boolean): List<Pair<LocalDate, Double>> {
    val first = byKey.keys.min()
    val last = byKey.keys.max()
    return generateSequence(first) { if (monthly) it.plusMonths(1) else it.plusDays(1) }
        .takeWhile { !it.isAfter(last) }
        .map { it to (byKey[it] ?: 0.0) }
        .toList()
}

/** 何本かの線を重ねたグラフ。タップした日の値を線ごとに表示する */
@Composable
fun MultiLineChart(series: List<ChartSeries>, unit: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val all = series.flatMap { it.points }
    val dates = all.map { it.first }.distinct().sorted()
    var selected by remember(series.map { it.points.size }, dates.firstOrNull(), dates.lastOrNull()) { mutableStateOf(dates.lastOrNull()) }
    val labelStyle = TextStyle(fontSize = 10.sp, color = Pastel.InkSoft)
    val valueStyle = TextStyle(fontSize = 11.sp, color = Pastel.Ink, fontWeight = FontWeight.Bold)
    if (all.isEmpty()) return

    var lo = all.minOf { it.second }
    var hi = all.maxOf { it.second }
    if (hi - lo < 0.4) { val mid = (hi + lo) / 2; lo = mid - 0.2; hi = mid + 0.2 }
    val dataLo = lo
    val dataHi = hi
    val pad = (hi - lo) * 0.12
    lo -= pad; hi += pad
    val d0 = dates.first().toEpochDay()
    val d1 = dates.last().toEpochDay()
    val dots = series.maxOf { it.points.size } <= 40

    Canvas(
        modifier.pointerInput(dates) {
            detectTapGestures { tap ->
                val left = 44.dp.toPx(); val right = size.width - 12.dp.toPx()
                val days = if (d1 == d0) 1f else (d1 - d0).toFloat()
                val day = d0 + ((tap.x - left) / (right - left) * days).toLong().coerceIn(0L, d1 - d0)
                selected = dates.minByOrNull { abs(it.toEpochDay() - day) }
            }
        },
    ) {
        val left = 44.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 8.dp.toPx() + 14.sp.toPx() * series.size.coerceAtMost(4)
        val bottom = size.height - 20.dp.toPx()
        fun x(d: LocalDate) = if (d1 == d0) (left + right) / 2 else left + (right - left) * (d.toEpochDay() - d0) / (d1 - d0).toFloat()
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()

        for (k in 0..2) {
            val v = dataLo + (dataHi - dataLo) * k / 2
            val yy = y(v)
            drawLine(Pastel.Line, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(shortValue(v), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
        }
        val firstLabel = measurer.measure(dates.first().let { "${it.monthValue}/${it.dayOfMonth}" }, labelStyle)
        drawText(firstLabel, topLeft = Offset(left, bottom + 4.dp.toPx()))
        if (d1 != d0) {
            val lastLabel = measurer.measure(dates.last().let { "${it.monthValue}/${it.dayOfMonth}" }, labelStyle)
            drawText(lastLabel, topLeft = Offset(right - lastLabel.size.width, bottom + 4.dp.toPx()))
        }
        selected?.let { sd ->
            drawLine(Pastel.Line, Offset(x(sd), top), Offset(x(sd), bottom), strokeWidth = 2.dp.toPx())
        }
        for (s in series) {
            if (s.points.size > 1) {
                val path = Path()
                s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
                drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            for (p in s.points) {
                if (!dots && p.first != selected && s.points.size > 1) continue
                val c = Offset(x(p.first), y(p.second))
                val r = if (p.first == selected) 5.dp.toPx() else 3.5.dp.toPx()
                drawCircle(Color.White, r + 1.5.dp.toPx(), c)
                drawCircle(s.color, r, c)
            }
        }
        // 選んだ日の値（線ごと。上に並べる）
        selected?.let { sd ->
            val lines = series.mapNotNull { s ->
                s.points.lastOrNull { it.first == sd }?.let { s to it.second }
            }.take(4)
            val head = measurer.measure("${sd.monthValue}/${sd.dayOfMonth}", valueStyle)
            drawText(head, topLeft = Offset(0f, 0f))
            var yy = 0f
            var xx = head.size.width + 8.dp.toPx()
            for ((s, v) in lines) {
                val t = measurer.measure((if (series.size > 1) "${s.name} " else "") + "${shortValue(v)}$unit", valueStyle.copy(color = s.color))
                if (xx + t.size.width > size.width) { xx = head.size.width + 8.dp.toPx(); yy += t.size.height }
                drawText(t, topLeft = Offset(xx, yy))
                xx += t.size.width + 10.dp.toPx()
            }
        }
    }
}

/** 目盛り用の短い数字（1万以上は「1.2万」） */
private fun shortValue(v: Double): String = if (abs(v) >= 10000) formatAmount(v / 10000) + "万" else formatAmount(v)
