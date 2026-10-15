package com.okamejiiya.petcare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.WeightLog
import com.okamejiiya.petcare.model.formatAmount
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

private enum class WeightRange(val label: String, val days: Long?) {
    MONTH("1か月", 31), QUARTER("3か月", 92), YEAR("1年", 366), ALL("すべて", null)
}

/** 体重の推移（グラフと記録の一覧） */
@Composable
fun WeightCard(data: AppData, pet: Pet, store: PetStore) {
    val today = LocalDate.now()
    var range by rememberSaveable { mutableStateOf(WeightRange.QUARTER) }
    var weightDialog by remember { mutableStateOf<WeightLog?>(null) }
    var showAllWeights by remember { mutableStateOf(false) }

    val weights = data.weights.filter { it.petId == pet.id }.sortedBy { it.date }
    val shown = range.days?.let { d -> weights.filter { !it.date.isBefore(today.minusDays(d)) } } ?: weights
    val goal = pet.targetWeightKg ?: Nutrition.estimatedIdealWeight(pet.weightKg, pet.bcs).takeIf { pet.bcs != 3 }

    SectionCard(title = "⚖ 体重") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${formatAmount(pet.weightKg)} kg",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            weightChangeText(weights)?.let {
                Text("  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (r in WeightRange.entries) ChoiceChip(r.label, range == r) { range = r }
        }
        if (shown.isEmpty()) {
            EmptyHint("この期間の記録はありません。体重をはかったら記録しましょう。")
        } else {
            WeightChart(shown, goal, Modifier.fillMaxWidth().height(190.dp))
            if (goal != null) {
                EmptyHint(
                    (if (pet.targetWeightKg != null) "点線は目標体重" else "点線は体形（BCS）から推定した理想体重") +
                        " ${formatAmount(goal)} kg です。点をタップすると値が出ます。",
                )
            }
        }
        FilledTonalButton(onClick = { weightDialog = newWeight(pet, today) }) {
            Text("＋ 体重を記録")
        }
        val list = weights.reversed()
        list.take(if (showAllWeights) list.size else 5).forEach { w ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { weightDialog = w }
                    .padding(vertical = 4.dp),
            ) {
                Text(w.date.slashText() + (w.time?.let { " ${it.hmText()}" } ?: ""), modifier = Modifier.weight(1f))
                Text("${formatAmount(w.kg)} kg", fontWeight = FontWeight.Bold)
            }
        }
        if (list.size > 5 && !showAllWeights) {
            TextButton(onClick = { showAllWeights = true }) { Text("すべて表示（${list.size}件）") }
        }
    }

    weightDialog?.let { w ->
        WeightDialog(
            initial = w,
            onDismiss = { weightDialog = null },
            onSave = { store.upsertWeight(it); weightDialog = null },
            onDelete = if (w.id != 0L) ({ store.deleteWeight(w); weightDialog = null }) else null,
        )
    }
}

/** 新しい体重の記録（id 0 は「まだ保存していない」） */
fun newWeight(pet: Pet, day: LocalDate, time: LocalTime? = LocalTime.now().withSecond(0).withNano(0)) =
    WeightLog(0, pet.id, day, pet.weightKg, time)

private fun weightChangeText(weights: List<WeightLog>): String? {
    if (weights.size < 2) return null
    val last = weights.last()
    val prev = weights[weights.size - 2]
    val diff = last.kg - prev.kg
    val sign = if (diff > 0) "+" else if (diff < 0) "−" else "±"
    return "前回（${prev.date.slashText()}）から $sign${formatAmount(abs(diff))} kg"
}

/** 体重の折れ線グラフ。1系列なので凡例は付けず、点をタップすると値を表示する */
@Composable
private fun WeightChart(points: List<WeightLog>, goal: Double?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf(points.lastIndex) }
    val line = Pastel.Pink
    val grid = Pastel.Line
    val ink = Pastel.InkSoft
    val labelStyle = TextStyle(fontSize = 10.sp, color = ink)
    val valueStyle = TextStyle(fontSize = 12.sp, color = Pastel.Ink, fontWeight = FontWeight.Bold)

    val values = points.map { it.kg } + listOfNotNull(goal)
    var lo = values.min()
    var hi = values.max()
    if (hi - lo < 0.4) { val mid = (hi + lo) / 2; lo = mid - 0.2; hi = mid + 0.2 }
    val dataLo = lo
    val dataHi = hi
    val pad = (hi - lo) * 0.12
    lo -= pad; hi += pad
    val d0 = points.first().date.toEpochDay()
    val d1 = points.last().date.toEpochDay()

    Canvas(
        modifier.pointerInput(points) {
            detectTapGestures { tap ->
                val left = 40.dp.toPx(); val right = size.width - 12.dp.toPx()
                val xs = points.map { p ->
                    if (d1 == d0) (left + right) / 2 else left + (right - left) * (p.date.toEpochDay() - d0) / (d1 - d0).toFloat()
                }
                selected = xs.indices.minByOrNull { abs(xs[it] - tap.x) } ?: selected
            }
        },
    ) {
        val left = 40.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 22.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        fun x(date: LocalDate) =
            if (d1 == d0) (left + right) / 2 else left + (right - left) * (date.toEpochDay() - d0) / (d1 - d0).toFloat()
        fun y(kg: Double) = (bottom - (bottom - top) * ((kg - lo) / (hi - lo))).toFloat()

        // 目盛り（控えめな横線3本）
        for (k in 0..2) {
            val v = dataLo + (dataHi - dataLo) * k / 2
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(formatAmount(v), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
        }
        // 日付（最初と最後）
        val first = measurer.measure(points.first().date.let { "${it.monthValue}/${it.dayOfMonth}" }, labelStyle)
        drawText(first, topLeft = Offset(left, bottom + 4.dp.toPx()))
        if (points.size > 1) {
            val last = measurer.measure(points.last().date.let { "${it.monthValue}/${it.dayOfMonth}" }, labelStyle)
            drawText(last, topLeft = Offset(right - last.size.width, bottom + 4.dp.toPx()))
        }
        if (goal != null) {
            val gy = y(goal)
            drawLine(
                Pastel.Mint, Offset(left, gy), Offset(right, gy), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
        }
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(x(p.date), y(p.kg)) else path.lineTo(x(p.date), y(p.kg))
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.forEachIndexed { i, p ->
            val c = Offset(x(p.date), y(p.kg))
            val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
            drawCircle(Color.White, r + 2.dp.toPx(), c)
            drawCircle(line, r, c)
        }
        points.getOrNull(selected)?.let { p ->
            val t = measurer.measure("${p.date.monthValue}/${p.date.dayOfMonth}  ${formatAmount(p.kg)}kg", valueStyle)
            val cx = x(p.date)
            val tx = (cx - t.size.width / 2).coerceIn(0f, maxOf(0f, size.width - t.size.width))
            val ty = (y(p.kg) - t.size.height - 10.dp.toPx()).coerceAtLeast(0f)
            drawText(t, topLeft = Offset(tx, ty))
        }
    }
}

@Composable
fun WeightDialog(initial: WeightLog, onDismiss: () -> Unit, onSave: (WeightLog) -> Unit, onDelete: (() -> Unit)?) {
    var date by remember { mutableStateOf(initial.date) }
    var time by remember { mutableStateOf(initial.time) }
    var pickTime by remember { mutableStateOf(false) }
    var kgText by remember { mutableStateOf(if (initial.kg > 0) formatAmount(initial.kg) else "") }
    val kg = kgText.toDoubleLoose() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "体重を記録" else "体重の記録を直す") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DateButton(date, { if (it != null) date = it })
                    TextButton(onClick = { pickTime = true }) { Text("🕒 " + (time?.hmText() ?: "時刻")) }
                }
                NumberField(kgText, { kgText = it }, "体重", Modifier.fillMaxWidth(), suffix = "kg")
                EmptyHint("いちばん新しい記録が、プロフィールの体重とカロリー計算に使われます。")
                if (onDelete != null) TextButton(onClick = onDelete) { Text("この記録を削除", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = kg > 0 && !date.isAfter(LocalDate.now()), onClick = {
                onSave(initial.copy(id = if (initial.id == 0L) Ids.next() else initial.id, date = date, kg = kg, time = time))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    if (pickTime) {
        TimePickDialog(date.atTime(time ?: LocalTime.now()).toMillis(), onDismiss = { pickTime = false }) {
            time = it.toLocalDateTime().toLocalTime()
            pickTime = false
        }
    }
}
