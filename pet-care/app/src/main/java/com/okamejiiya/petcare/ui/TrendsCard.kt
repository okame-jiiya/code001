@file:OptIn(ExperimentalLayoutApi::class)

package com.okamejiiya.petcare.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.Mood
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.RecordItem
import com.okamejiiya.petcare.model.RecordKind
import com.okamejiiya.petcare.model.formatAmount
import com.okamejiiya.petcare.model.visitEntries
import java.time.LocalDate

/** 推移を見られるもの。体重・ごはん・トイレ・機嫌は専用の記録から、それ以外は「ほかの記録」から */
private sealed class Metric(val key: String, val label: String) {
    data object Weight : Metric("WEIGHT", "⚖ 体重")
    data object Kcal : Metric("KCAL", "🍚 カロリー")
    data class Toilet(val type: ExcretionType) : Metric(type.name, "${type.emoji} ${type.label}の回数")
    data object MoodTrend : Metric("MOOD", "☀️ 機嫌")
    data class Record(val item: RecordItem) : Metric("R_" + item.name, "${item.emoji} ${item.label}")
}

private fun metricsFor(data: AppData): List<Metric> = buildList {
    add(Metric.Weight)
    if (data.isOn(RecordItem.FOOD)) add(Metric.Kcal)
    if (data.isOn(RecordItem.POOP)) add(Metric.Toilet(ExcretionType.POOP))
    if (data.isOn(RecordItem.PEE)) add(Metric.Toilet(ExcretionType.PEE))
    if (data.isOn(RecordItem.MOOD)) add(Metric.MoodTrend)
    data.settings.items.filter { !it.special }.forEach { add(Metric.Record(it)) }
}

/** 記録の推移（体重・カロリー・トイレ・機嫌、設定で選んだ記録項目） */
@Composable
fun TrendsCard(data: AppData, pet: Pet, store: PetStore) {
    val metrics = metricsFor(data)
    var key by rememberSaveable { mutableStateOf(Metric.Weight.key) }
    val metric = metrics.firstOrNull { it.key == key } ?: Metric.Weight
    var range by rememberSaveable { mutableStateOf(TrendRange.MONTH) }
    val today = LocalDate.now()

    SectionCard(title = "📈 記録の推移") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in metrics) ChoiceChip(m.label, m == metric) { key = m.key }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (metric == Metric.Weight) {
            WeightContent(data, pet, store)
            return@SectionCard
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in TrendRange.entries) ChoiceChip(r.label, range == r) { range = r }
        }
        val from = range.days?.let { today.minusDays(it - 1) }
        when (metric) {
            Metric.Kcal -> {
                val byDay = data.feedLogs.filter { it.petId == pet.id }.groupBy { it.time.toLocalDate() }.mapValues { e -> e.value.sumOf { it.kcal } }
                val target = Nutrition.energy(pet, today).targetKcal
                DailyTrend(byDay, from, today, "kcal", goal = target, note = "点線は1日の目安 ${formatAmount(target)} kcal です。")
            }
            is Metric.Toilet -> {
                val byDay = data.excretionLogs.filter { it.petId == pet.id && it.type == metric.type }
                    .groupBy { it.time.toLocalDate() }.mapValues { it.value.size.toDouble() }
                DailyTrend(byDay, from, today, "回", goal = null, note = null)
            }
            Metric.MoodTrend -> {
                val moods = data.moods.filter { it.petId == pet.id && (from == null || !it.date.isBefore(from)) }.sortedBy { it.date }
                if (moods.isEmpty()) {
                    EmptyHint("この期間の機嫌の記録はありません。")
                } else {
                    // ごきげん 3・ふつう 2・いまいち 1 として線にする
                    LineChart(moods.map { it.date to (Mood.entries.size - it.mood.ordinal).toDouble() }, null, "", Modifier.fillMaxWidth().height(170.dp))
                    EmptyHint(Mood.entries.joinToString("・") { "${it.emoji}${it.label} ${Mood.entries.size - it.ordinal}" } + " として表示しています。")
                    Text(
                        Mood.entries.joinToString("　") { m -> "${m.emoji} ${moods.count { it.mood == m }}日" },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            is Metric.Record -> RecordTrend(data, pet, metric.item, from)
            Metric.Weight -> Unit
        }
    }
}

/** 1日ごとの合計（記録の無い日は 0）の推移 */
@Composable
private fun DailyTrend(byDay: Map<LocalDate, Double>, from: LocalDate?, today: LocalDate, unit: String, goal: Double?, note: String?) {
    val start = from ?: byDay.keys.minOrNull()
    if (start == null || byDay.none { !it.key.isBefore(start) }) {
        EmptyHint("この期間の記録はありません。")
        return
    }
    // 記録を始める前の日は 0 にしない
    val first = maxOf(start, byDay.keys.min())
    val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
    val points = days.map { it to (byDay[it] ?: 0.0) }
    LineChart(points, goal, unit, Modifier.fillMaxWidth().height(190.dp))
    val values = points.map { it.second }
    Text(
        "1日平均 ${formatAmount(values.average())} $unit　最大 ${formatAmount(values.max())} $unit（${days.size}日間）",
        fontWeight = FontWeight.Bold,
    )
    note?.let { EmptyHint(it) }
}

/** 「ほかの記録」の推移。数値の項目はグラフ、それ以外は履歴の一覧 */
@Composable
private fun RecordTrend(data: AppData, pet: Pet, item: RecordItem, from: LocalDate?) {
    if (item == RecordItem.VISIT) return VisitTrend(data, pet, from)
    val logs = data.records
        .filter { it.petId == pet.id && it.item == item && (from == null || !it.time.toLocalDate().isBefore(from)) }
        .sortedBy { it.time }
    var showAll by remember(item) { mutableStateOf(false) }
    if (logs.isEmpty()) {
        EmptyHint("この期間の「${item.label}」の記録はありません。")
        return
    }
    if (item.kind == RecordKind.NUMBER) {
        val nums = logs.mapNotNull { r -> r.value?.let { r.time.toLocalDate() to it } }
        if (nums.isNotEmpty()) {
            LineChart(nums, null, item.unit, Modifier.fillMaxWidth().height(190.dp))
            val vs = nums.map { it.second }
            Text(
                "平均 ${formatAmount(vs.average())}${item.unit}　最小 ${formatAmount(vs.min())}　最大 ${formatAmount(vs.max())}",
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        val last = logs.last().time.toLocalDate()
        val today = LocalDate.now()
        Text(
            "${logs.size}回　最後は ${last.slashText()}（${if (last == today) "今日" else durationText(last, today) + "前"}）",
            fontWeight = FontWeight.Bold,
        )
    }
    val list = logs.reversed()
    list.take(if (showAll) list.size else 8).forEach { r ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(r.time.toLocalDate().slashText() + " " + r.time.timeText(), modifier = Modifier.weight(1f))
            Text(recordValueText(r).ifBlank { "✓" }, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (list.size > 8 && !showAll) TextButton(onClick = { showAll = true }) { Text("すべて表示（${list.size}件）") }
}

/** 通院の推移：通院記録と、カレンダーの「通院」の予定のうち今日までの分 */
@Composable
private fun VisitTrend(data: AppData, pet: Pet, from: LocalDate?) {
    val today = LocalDate.now()
    val list = visitEntries(data, pet.id, today).filter { from == null || !it.date.isBefore(from) }
    var showAll by remember { mutableStateOf(false) }
    if (list.isEmpty()) {
        EmptyHint("この期間の通院の記録はありません。カレンダーの「🗓 予定」で「🏥 通院」を入れると、ここに数えられます。")
        return
    }
    val last = list.first().date
    Text(
        "${list.size}回　最後は ${last.slashText()}（${if (last == today) "今日" else durationText(last, today) + "前"}）",
        fontWeight = FontWeight.Bold,
    )
    list.take(if (showAll) list.size else 8).forEach { v ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(v.date.slashText() + (v.time?.let { " %d:%02d".format(it.hour, it.minute) } ?: ""), modifier = Modifier.weight(1f))
            Text((if (v.fromSchedule) "🗓 " else "") + v.text.ifBlank { "通院" }, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (list.size > 8 && !showAll) TextButton(onClick = { showAll = true }) { Text("すべて表示（${list.size}件）") }
    EmptyHint("🗓 はカレンダーの予定から数えたものです（同じ日は1回）。")
}
