package com.okamejiiya.petcare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Food
import com.okamejiiya.petcare.model.FoodKind
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.PlanItem
import com.okamejiiya.petcare.model.formatAmount
import com.okamejiiya.petcare.model.formatKcal
import com.okamejiiya.petcare.model.formatQuantity

@Composable
fun FoodScreen(data: AppData, pet: Pet, store: PetStore, modifier: Modifier) {
    val (energy, plan) = planFor(data, pet)
    var newFood by remember { mutableStateOf(false) }
    var editPlan by remember { mutableStateOf<PlanItem?>(null) }
    var newPlan by remember { mutableStateOf(false) }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(title = "📋 ${pet.name}のごはんプラン") {
                Text(
                    "1日の目安 ${formatKcal(energy.targetKcal)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                if (plan.lines.isEmpty()) {
                    EmptyHint(
                        if (data.foods.isEmpty()) "まず下の「＋ フード・おやつを登録」で、買ってきたごはんを登録してください。"
                        else "あげているごはんをプランに追加すると、目安カロリーから1回の量を計算します。",
                    )
                }
                plan.lines.forEachIndexed { index, line ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editPlan = line.item },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(line.food.name, fontWeight = FontWeight.Bold)
                                if (line.item.auto && line.food.divisible) Pill("自動計算", Pastel.MintLight)
                                if (line.food.kind == FoodKind.TREAT) Pill("おやつ", Pastel.LavenderLight)
                            }
                            Text(
                                "${cycleText(line.item.timesPerCycle, line.item.cycleDays)}・1回 ${formatQuantity(line.amountPerServing, line.food.unitLabel)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "1日 ${formatKcal(line.dailyKcal)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (plan.lines.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("合計 ${formatKcal(plan.totalKcal)} ／ 目安 ${formatKcal(energy.targetKcal)}", fontWeight = FontWeight.Bold)
                    if (plan.fixedExceedsTarget) {
                        Warning("量が決まっているごはんだけで目安を超えています。パウチやおやつの回数を見直してください。")
                    } else if (plan.lines.none { it.item.auto && it.food.divisible } && plan.totalKcal > energy.targetKcal * 1.1) {
                        Warning("目安より ${formatKcal(plan.totalKcal - energy.targetKcal)} 多めのプランです。")
                    }
                    if (plan.treatOverLimit) {
                        Warning("おやつが1日の目安の10%（${formatKcal(energy.targetKcal * Nutrition.TREAT_LIMIT_RATIO)}）を超えています。")
                    }
                }
                FilledTonalButton(onClick = { newPlan = true }, enabled = data.foods.isNotEmpty()) { Text("＋ プランに追加") }
            }
        }
        item {
            SectionCard(title = "🛒 フード・おやつ") {
                EmptyHint(
                    if (data.foods.isEmpty()) "買ってきたごはんを、袋に書いてあるカロリーで登録します。登録したものの一覧・修正は「設定」の「フード・おやつの登録」にまとめています。"
                    else "${data.foods.size}種類を登録済みです。一覧・修正は「設定」の「フード・おやつの登録」からできます。",
                )
                FilledTonalButton(onClick = { newFood = true }) { Text("＋ フード・おやつを登録") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (newFood) {
        FoodDialog(
            initial = null,
            onDismiss = { newFood = false },
            onSave = { store.upsertFood(it); newFood = false },
            onDelete = null,
        )
    }
    if (newPlan || editPlan != null) {
        PlanDialog(
            data = data,
            pet = pet,
            initial = editPlan,
            onDismiss = { newPlan = false; editPlan = null },
            onSave = { store.upsertPlan(it); newPlan = false; editPlan = null },
            onDelete = editPlan?.let { p -> { store.deletePlan(p.id); editPlan = null } },
        )
    }
}

/** 登録したフード・おやつの一覧（設定画面にまとめている） */
@Composable
fun FoodMasterCard(data: AppData, store: PetStore) {
    var editFood by remember { mutableStateOf<Food?>(null) }
    var newFood by remember { mutableStateOf(false) }
    SectionCard(title = "🛒 フード・おやつの登録") {
        if (data.foods.isEmpty()) EmptyHint("カリカリ、パウチ、缶詰、おやつなどを、袋に書いてあるカロリーで登録します。")
        data.foods.forEachIndexed { index, food ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editFood = food }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (food.kind == FoodKind.TREAT) "🍪" else "🍚")
                Column(Modifier.weight(1f)) {
                    Text(food.name, fontWeight = FontWeight.Bold)
                    Text(
                        "${formatQuantity(food.baseAmount, food.unitLabel)} あたり ${formatKcal(food.kcalPerBase)}" +
                            if (food.divisible) "（g で調整）" else "（${food.unitLabel}単位）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FilledTonalButton(onClick = { newFood = true }) { Text("＋ フード・おやつを登録") }
        EmptyHint("家族と共通です。タップで修正・削除できます。")
    }
    if (newFood || editFood != null) {
        FoodDialog(
            initial = editFood,
            onDismiss = { newFood = false; editFood = null },
            onSave = { store.upsertFood(it); newFood = false; editFood = null },
            onDelete = editFood?.let { f -> { store.deleteFood(f.id); editFood = null } },
        )
    }
}

@Composable
private fun Warning(text: String) {
    Text("⚠ $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

private val packUnits = listOf("袋", "パウチ", "缶", "本", "個", "枚", "カップ", "トレイ")

@Composable
private fun FoodDialog(initial: Food?, onDismiss: () -> Unit, onSave: (Food) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: FoodKind.MEAL) }
    var divisible by remember { mutableStateOf(initial?.divisible ?: true) }
    var unit by remember { mutableStateOf(if (initial != null && !initial.divisible) initial.unitLabel else "袋") }
    var baseText by remember { mutableStateOf(initial?.takeIf { it.divisible }?.let { formatAmount(it.baseAmount) } ?: "100") }
    var kcalText by remember { mutableStateOf(initial?.let { formatAmount(it.kcalPerBase) } ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    val base = if (divisible) baseText.toDoubleLoose() ?: 0.0 else 1.0
    val kcal = kcalText.toDoubleLoose() ?: 0.0
    val valid = name.isNotBlank() && base > 0 && kcal > 0 && unit.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "フード・おやつを登録" else "フード・おやつを編集") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("商品名（例：○○ 室内猫用）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in FoodKind.entries) ChoiceChip(k.label, kind == k) { kind = k }
                }
                Text("量のあげ方", style = MaterialTheme.typography.labelLarge)
                Column {
                    RadioLine("g 単位で量を調整できる（カリカリなど）", divisible) { divisible = true }
                    RadioLine("1袋・1缶など個数単位（パウチ・缶・ちゅ〜るなど）", !divisible) { divisible = false }
                }
                if (divisible) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("1g あたり", baseText == "1") { baseText = "1" }
                        ChoiceChip("100g あたり", baseText == "100") { baseText = "100" }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumberField(baseText, { baseText = it }, "量", Modifier.weight(1f), suffix = "g あたり")
                        NumberField(kcalText, { kcalText = it }, "カロリー", Modifier.weight(1f), suffix = "kcal")
                    }
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (u in packUnits) ChoiceChip(u, unit == u) { unit = u }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            unit, { unit = it.take(6) },
                            label = { Text("単位") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                        )
                        NumberField(kcalText, { kcalText = it }, "1${unit}あたり", Modifier.weight(1.4f), suffix = "kcal")
                    }
                }
                EmptyHint("パッケージの「エネルギー」「代謝エネルギー」欄の数字を入れてください。")
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("このフードを削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    Food(
                        id = initial?.id ?: Ids.next(),
                        name = name.trim(),
                        kind = kind,
                        divisible = divisible,
                        unitLabel = if (divisible) "g" else unit.trim(),
                        baseAmount = base,
                        kcalPerBase = kcal,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            "「$name」を削除します。ごはんプランからも外れます（これまでの記録は残ります）。",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; onDelete() },
        )
    }
}

@Composable
fun RadioLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp))
    }
}

private data class CyclePreset(val label: String, val times: Int, val days: Int)

private val cyclePresets = listOf(
    CyclePreset("毎日1回", 1, 1),
    CyclePreset("毎日2回", 2, 1),
    CyclePreset("毎日3回", 3, 1),
    CyclePreset("2日に1回", 1, 2),
    CyclePreset("3日に1回", 1, 3),
    CyclePreset("週1回", 1, 7),
)

@Composable
private fun PlanDialog(
    data: AppData,
    pet: Pet,
    initial: PlanItem?,
    onDismiss: () -> Unit,
    onSave: (PlanItem) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var foodId by remember { mutableStateOf(initial?.foodId ?: data.foods.firstOrNull()?.id) }
    val food = data.foods.firstOrNull { it.id == foodId }
    var times by remember { mutableIntStateOf(initial?.timesPerCycle ?: 2) }
    var days by remember { mutableIntStateOf(initial?.cycleDays ?: 1) }
    var auto by remember { mutableStateOf(initial?.auto ?: true) }
    var amountText by remember { mutableStateOf(initial?.takeIf { it.amount > 0 }?.let { formatAmount(it.amount) } ?: "1") }
    val amount = amountText.toDoubleLoose() ?: 0.0
    val isAuto = auto && food?.divisible == true
    val valid = food != null && times > 0 && days > 0 && (isAuto || amount > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "プランに追加" else "プランを編集") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("ごはん", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    for (f in data.foods) {
                        RadioLine((if (f.kind == FoodKind.TREAT) "🍪 " else "🍚 ") + f.name, f.id == foodId) {
                            if (foodId != f.id) {
                                foodId = f.id
                                if (!f.divisible) amountText = "1"
                                if (f.kind == FoodKind.TREAT && initial == null) { times = 1; days = 1 }
                            }
                        }
                    }
                }
                Text("あげるサイクル", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (p in cyclePresets) ChoiceChip(p.label, times == p.times && days == p.days) { times = p.times; days = p.days }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stepper("$days 日", onMinus = { days = (days - 1).coerceAtLeast(1) }, onPlus = { days = (days + 1).coerceAtMost(31) })
                    Text(" に ")
                    Stepper("$times 回", onMinus = { times = (times - 1).coerceAtLeast(1) }, onPlus = { times = (times + 1).coerceAtMost(10) })
                }
                if (food != null) {
                    Text("1回の量", style = MaterialTheme.typography.labelLarge)
                    if (food.divisible) {
                        Column {
                            RadioLine("目安カロリーから自動で計算する（おすすめ）", auto) { auto = true }
                            RadioLine("自分で決める", !auto) { auto = false; if (amountText == "1") amountText = "" }
                        }
                        if (!auto) NumberField(amountText, { amountText = it }, "1回の量", suffix = "g")
                        else EmptyHint("パウチやおやつなど量が決まっている分を差し引いた残りを、この g 数で調整します。")
                    } else {
                        Stepper(
                            "${formatAmount(amount)} ${food.unitLabel}",
                            onMinus = { amountText = formatAmount((amount - 0.5).coerceAtLeast(0.5)) },
                            onPlus = { amountText = formatAmount(amount + 0.5) },
                        )
                    }
                    if (!isAuto && amount > 0) {
                        Text(
                            "1日あたり ${formatKcal(amount * food.kcalPerUnit * times / days)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("プランから外す", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val f = food ?: return@TextButton
                onSave(
                    PlanItem(
                        id = initial?.id ?: Ids.next(),
                        petId = pet.id,
                        foodId = f.id,
                        auto = isAuto,
                        amount = if (isAuto) 0.0 else amount,
                        timesPerCycle = times,
                        cycleDays = days,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
