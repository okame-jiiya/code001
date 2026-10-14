@file:OptIn(ExperimentalMaterial3Api::class)

package com.okamejiiya.petcare.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Breeds
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.Sex
import com.okamejiiya.petcare.model.Species
import com.okamejiiya.petcare.model.formatAmount
import com.okamejiiya.petcare.model.formatKcal
import com.okamejiiya.petcare.model.ExcretionType
import java.time.Instant
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.ZoneOffset

fun ageText(pet: Pet, today: LocalDate = LocalDate.now()): String {
    val m = Nutrition.ageInMonths(pet.birthDate, today)
    val text = when {
        m < 12 -> "${m}か月"
        m % 12 == 0L -> "${m / 12}歳"
        else -> "${m / 12}歳${m % 12}か月"
    }
    return if (pet.birthEstimated) "約$text" else text
}

@Composable
fun PetScreen(data: AppData, pet: Pet, store: PetStore, modifier: Modifier, onAdd: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) {
        BackHandler { editing = false }
        PetEditor(
            initial = pet,
            firstTime = false,
            onSave = { store.upsertPet(it); editing = false },
            onCancel = { editing = false },
            onDelete = { store.deletePet(pet.id); editing = false },
            modifier = modifier,
        )
        return
    }
    val energy = Nutrition.energy(pet, LocalDate.now())
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BodyShapeImage(pet.species, pet.bcs)
                    Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(pet.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${pet.species.emoji} ${pet.breed.ifBlank { pet.species.label }}")
                        Text("${pet.sex.label}・${if (pet.neutered) "避妊・去勢済み" else "未去勢"}")
                        Text("${ageText(pet)}・${formatAmount(pet.weightKg)} kg・BCS ${pet.bcs}（${bcsTitles[pet.bcs - 1]}）")
                    }
                }
                Button(onClick = { editing = true }) { Text("プロフィールを編集") }
            }
        }
        item {
            SectionCard(title = "🔢 1日のカロリーの目安") {
                Text(
                    formatKcal(energy.targetKcal),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (pet.customKcal != null) {
                    Text("手動で設定した値です（自動計算では ${formatKcal(energy.calculatedKcal)}）", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val w = formatAmount((energy.basisWeightKg * 100).toLong() / 100.0)
                Text("計算の内訳", style = MaterialTheme.typography.labelLarge)
                Text("安静時に必要なカロリー（RER）\n= 70 × ${w}kg^0.75 = ${formatKcal(energy.rer)}", style = MaterialTheme.typography.bodyMedium)
                Text("係数 ${energy.factor}（${energy.factorLabel}）", style = MaterialTheme.typography.bodyMedium)
                Text("1日の目安（MER）= ${formatKcal(energy.rer)} × ${energy.factor} = ${formatKcal(energy.calculatedKcal)}", style = MaterialTheme.typography.bodyMedium)
                val basisNote = when {
                    energy.stage == Nutrition.Stage.BABY_EARLY || energy.stage == Nutrition.Stage.BABY -> "成長期のため今の体重で計算しています。"
                    pet.targetWeightKg != null -> "目標体重 ${formatAmount(pet.targetWeightKg)}kg で計算しています。"
                    pet.bcs != 3 -> "BCS ${pet.bcs} から推定した理想体重 ${w}kg で計算しています。"
                    else -> "今の体重で計算しています。"
                }
                EmptyHint("$basisNote 一般的な目安なので、体重の変化や獣医さんの指示に合わせて調整してください。")
            }
        }
        item {
            SectionCard(title = "🐾 うちの子たち") {
                data.pets.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { store.selectPet(p.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.species.emoji} ${p.name}", fontWeight = if (p.id == pet.id) FontWeight.Bold else FontWeight.Normal)
                            Text(
                                todaySummary(data, p),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (p.id == pet.id) Pill("表示中", Pastel.PinkLight)
                    }
                }
                FilledTonalButton(onClick = onAdd) { Text("＋ 新しい子を登録") }
            }
        }
        item { BackupCard(store) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 多頭飼いのときに、それぞれの子の今日の様子をひと目で */
private fun todaySummary(data: AppData, pet: Pet): String {
    val today = LocalDate.now()
    val kcal = data.feedLogs.filter { it.petId == pet.id && it.time.toLocalDate() == today }.sumOf { it.kcal }
    val toilets = data.excretionLogs.filter { it.petId == pet.id && it.time.toLocalDate() == today }
    val target = Nutrition.energy(pet, today).targetKcal
    return "今日 ${kcal.roundToInt()}/${formatKcal(target)}・" +
        ExcretionType.entries.joinToString("・") { t -> "${t.emoji}${toilets.count { it.type == t }}" }
}

@Composable
fun PetEditor(
    initial: Pet?,
    firstTime: Boolean,
    onSave: (Pet) -> Unit,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var species by rememberSaveable { mutableStateOf(initial?.species ?: Species.CAT) }
    var breed by rememberSaveable { mutableStateOf(initial?.breed ?: "") }
    var sex by rememberSaveable { mutableStateOf(initial?.sex ?: Sex.FEMALE) }
    var neutered by rememberSaveable { mutableStateOf(initial?.neutered ?: true) }
    var weightText by rememberSaveable { mutableStateOf(initial?.let { formatAmount(it.weightKg) } ?: "") }
    var useAge by rememberSaveable { mutableStateOf(initial?.birthEstimated ?: false) }
    var birthText by rememberSaveable { mutableStateOf((initial?.birthDate ?: today.minusYears(1)).toString()) }
    val initialMonths = initial?.let { Nutrition.ageInMonths(it.birthDate, today) } ?: 12L
    var ageYearsText by rememberSaveable { mutableStateOf((initialMonths / 12).toString()) }
    var ageMonthsText by rememberSaveable { mutableStateOf((initialMonths % 12).toString()) }
    var bcs by rememberSaveable { mutableIntStateOf(initial?.bcs ?: 3) }
    var targetText by rememberSaveable { mutableStateOf(initial?.targetWeightKg?.let { formatAmount(it) } ?: "") }
    var customKcalText by rememberSaveable { mutableStateOf(initial?.customKcal?.toString() ?: "") }
    var showBreeds by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val weight = weightText.toDoubleLoose() ?: 0.0
    val birth: LocalDate = if (useAge) {
        val months = (ageYearsText.toLongOrNull() ?: 0) * 12 + (ageMonthsText.toLongOrNull() ?: 0)
        today.minusMonths(months)
    } else LocalDate.parse(birthText)
    val valid = name.isNotBlank() && weight > 0 && !birth.isAfter(today)

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            when {
                firstTime -> "ようこそ 🐾\nまずはうちの子を登録しましょう"
                initial == null -> "新しい子を登録"
                else -> "${initial.name}のプロフィール"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        SectionCard {
            OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Species.entries) ChoiceChip("${s.emoji} ${s.label}", species == s) {
                    if (species != s) { species = s; breed = "" }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(breed, { breed = it }, label = { Text("品種") }, singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small)
                OutlinedButton(onClick = { showBreeds = true }) { Text("一覧") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Sex.entries) ChoiceChip(s.label, sex == s) { sex = s }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("避妊・去勢済み", modifier = Modifier.weight(1f))
                Switch(checked = neutered, onCheckedChange = { neutered = it })
            }
            NumberField(weightText, { weightText = it }, "体重", Modifier.fillMaxWidth(), suffix = "kg")
        }
        SectionCard(title = "🎂 年齢") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("生年月日で入力", !useAge) { useAge = false }
                ChoiceChip("年齢で入力", useAge) {
                    if (!useAge) {
                        val m = Nutrition.ageInMonths(LocalDate.parse(birthText), today)
                        ageYearsText = (m / 12).toString(); ageMonthsText = (m % 12).toString()
                    }
                    useAge = true
                }
            }
            if (useAge) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(ageYearsText, { ageYearsText = it }, "年齢", Modifier.weight(1f), suffix = "歳", decimal = false)
                    NumberField(ageMonthsText, { ageMonthsText = it }, "", Modifier.weight(1f), suffix = "か月", decimal = false)
                }
            } else {
                OutlinedButton(onClick = { showDate = true }) {
                    Text("📅 ${birth.year}年${birth.monthValue}月${birth.dayOfMonth}日")
                }
            }
            val stage = Nutrition.stage(species, Nutrition.ageInMonths(birth, today))
            EmptyHint("ライフステージ：${stage.label}")
        }
        SectionCard(title = "🫶 いまの体形（上から見た形）") {
            BcsSelector(species, bcs) { bcs = it }
        }
        SectionCard(title = "⚙ 細かい設定（なくてもOK）") {
            NumberField(targetText, { targetText = it }, "目標体重（獣医さんの指示など）", Modifier.fillMaxWidth(), suffix = "kg")
            NumberField(customKcalText, { customKcalText = it }, "1日のカロリーを自分で決める", Modifier.fillMaxWidth(), suffix = "kcal", decimal = false)
            EmptyHint("空欄なら、体重・年齢・去勢の有無・体形から自動で計算します。")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCancel != null) OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("キャンセル") }
            Button(
                enabled = valid,
                modifier = Modifier.weight(1f),
                onClick = {
                    onSave(
                        Pet(
                            id = initial?.id ?: Ids.next(),
                            name = name.trim(),
                            species = species,
                            breed = breed.trim(),
                            sex = sex,
                            neutered = neutered,
                            weightKg = weight,
                            birthDate = birth,
                            birthEstimated = useAge,
                            bcs = bcs,
                            targetWeightKg = targetText.toDoubleLoose()?.takeIf { it > 0 },
                            customKcal = customKcalText.trim().toIntOrNull()?.takeIf { it > 0 },
                        ),
                    )
                },
            ) { Text("保存") }
        }
        if (onDelete != null) {
            TextButton(onClick = { confirmDelete = true }) {
                Text("この子の登録を削除", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showBreeds) {
        BreedDialog(species, onDismiss = { showBreeds = false }) { breed = it; showBreeds = false }
    }
    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = birth.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        birthText = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDate = false
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("キャンセル") } },
        ) { DatePicker(state = state) }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            "${initial?.name ?: "この子"}の登録と、これまでのごはん・トイレの記録をすべて削除します。",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; onDelete() },
        )
    }
}

/** 品種の一覧（アイウエオ順）。上の欄に入力すると絞り込める */
@Composable
private fun BreedDialog(species: Species, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = Breeds.of(species)
    val list = if (query.isBlank()) all else all.filter { it.contains(query.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("品種を選ぶ（${all.size - 1}種）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query, { query = it },
                    label = { Text("しぼりこみ（例：テリア）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    for (b in list) {
                        Text(
                            b,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(b) }
                                .padding(vertical = 10.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (list.isEmpty() && query.isNotBlank()) {
                        TextButton(onClick = { onPick(query.trim()) }) { Text("「${query.trim()}」をそのまま使う") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
