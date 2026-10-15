@file:OptIn(ExperimentalMaterial3Api::class)

package com.okamejiiya.petcare.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import com.okamejiiya.petcare.data.Screen
import com.okamejiiya.petcare.data.Section
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.okamejiiya.petcare.model.PetDocument
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

/**
 * 「うちの子」：まず全員を写真と基本情報で一覧にし、タップでその子の画面へ。
 * 1頭ずつの画面の並び・表示は「設定」の「画面の表示」で変えられる。
 */
@Composable
fun PetScreen(data: AppData, pet: Pet, store: PetStore, modifier: Modifier, onEdit: (Long) -> Unit) {
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val detail = detailId?.let { id -> data.pets.firstOrNull { it.id == id } }
    if (detail == null) {
        PetList(data, modifier) { p -> store.selectPet(p.id); detailId = p.id }
    } else {
        BackHandler { detailId = null }
        PetDetail(data, detail, store, modifier, onBack = { detailId = null }, onEdit = { onEdit(detail.id) }, onSwitch = { store.selectPet(it); detailId = it })
    }
}

@Composable
private fun PetList(data: AppData, modifier: Modifier, onOpen: (Pet) -> Unit) {
    val prefs = LocalPrefs.current
    val photoSize by prefs.petPhotoSize.collectAsState()
    val today = LocalDate.now()
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("うちの子たち（${data.pets.size}）", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        items(data.sortedPets, key = { it.id }) { p ->
            SectionCard(modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onOpen(p) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val photo = p.photoId
                    if (photo != null) {
                        PhotoImage(photo, Modifier.size(photoSize.dp).clip(RoundedCornerShape((photoSize / 5).dp)), maxSide = 400)
                    } else {
                        PetAvatar(p, photoSize)
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${p.species.emoji} ${p.breed.ifBlank { p.species.label }}・${p.sex.label}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${ageText(p, today)}・${formatAmount(p.weightKg)} kg" +
                                (p.adoptionDate?.let { "・お迎えから${durationText(it, today)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            todaySummary(data, p),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { EmptyHint("タップすると、その子のプロフィールと記録の推移が見られます。新しい子の登録や並べ替えは「設定」からできます。") }
    }
}

@Composable
private fun PetDetail(data: AppData, pet: Pet, store: PetStore, modifier: Modifier, onBack: () -> Unit, onEdit: () -> Unit, onSwitch: (Long) -> Unit) {
    val prefs = LocalPrefs.current
    val layout by prefs.layout.collectAsState()
    val hidden by prefs.hidden.collectAsState()
    val sections = layout.getValue(Screen.PET).filter { it !in hidden }
    var viewing by remember { mutableStateOf<PetDocument?>(null) }
    var viewTop by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 一覧へ") }
                Spacer(Modifier.weight(1f))
                // ほかの子へすぐ移れるように
                // 表示中の子も枠付きで残し、順番に見ていけるようにする
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (p in data.sortedPets) {
                        val current = p.id == pet.id
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .border(if (current) 3.dp else 0.dp, if (current) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .clickable(enabled = !current) { onSwitch(p.id) }
                                .padding(4.dp),
                        ) { PetAvatar(p, if (current) 38 else 32) }
                    }
                }
            }
        }
        for (section in sections) when (section) {
            Section.PET_PROFILE -> item(key = section.name) {
                SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val photo = pet.photoId
                        if (photo != null) {
                            PhotoImage(
                                photo,
                                Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { viewTop = true },
                                maxSide = 640,
                            )
                        } else {
                            BodyShapeImage(pet.species, pet.bcs)
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(pet.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PetAvatar(pet, 26)
                                Text(pet.breed.ifBlank { pet.species.label })
                            }
                            Text("${pet.sex.label}・${if (pet.neutered) "避妊・去勢済み" else "未去勢"}")
                            Text("${ageText(pet)}・${formatAmount(pet.weightKg)} kg・BCS ${pet.bcs}（${bcsTitles[pet.bcs - 1]}）")
                        }
                    }
                    Text(
                        "🎂 " + (if (pet.birthEstimated) "推定 " else "") + pet.birthDate.slashText() +
                            (pet.adoptionDate?.let { "　🏠 お迎え ${it.slashText()}（${durationText(it, today)}）" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onEdit) { Text("プロフィールを編集") }
                }
            }
            Section.PET_ABOUT -> if (pet.personality.isNotBlank() || pet.physical.isNotBlank() || pet.notes.isNotBlank()) {
                item(key = section.name) {
                    SectionCard(title = "📝 この子のこと") {
                        ProfileText("性格", pet.personality)
                        ProfileText("体の特徴", pet.physical)
                        ProfileText("そのほか", pet.notes)
                    }
                }
            }
            Section.PET_TRENDS -> item(key = section.name) { TrendsCard(data, pet, store) }
            Section.PET_DOCS -> if (pet.documents.isNotEmpty()) {
                item(key = section.name) {
                    SectionCard(title = "📄 書類（診断書・血統書など）") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (doc in pet.documents) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    PhotoImage(
                                        doc.photoId,
                                        Modifier
                                            .size(96.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { viewing = doc },
                                        maxSide = 400,
                                    )
                                    Text(doc.title.ifBlank { "書類" }, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            Section.PET_KCAL -> item(key = section.name) { KcalGuideCard(pet, today) }
            else -> Unit
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    viewing?.let { doc -> PhotoViewer(doc.photoId, doc.title, onDismiss = { viewing = null }, onDelete = null) }
    if (viewTop) pet.photoId?.let { PhotoViewer(it, pet.name, onDismiss = { viewTop = false }, onDelete = null) }
}

@Composable
private fun KcalGuideCard(pet: Pet, today: LocalDate) {
    val energy = Nutrition.energy(pet, today)
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

@Composable
private fun ProfileText(label: String, text: String) {
    if (text.isBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 「2年3か月」のような期間 */
fun durationText(from: LocalDate, today: LocalDate): String {
    val m = Nutrition.ageInMonths(from, today)
    return when {
        m < 1 -> "${java.time.temporal.ChronoUnit.DAYS.between(from, today)}日"
        m < 12 -> "${m}か月"
        m % 12 == 0L -> "${m / 12}年"
        else -> "${m / 12}年${m % 12}か月"
    }
}

/** 多頭飼いのときに、それぞれの子の今日の様子をひと目で */
private fun todaySummary(data: AppData, pet: Pet): String {
    val today = LocalDate.now()
    val kcal = data.feedLogs.filter { it.petId == pet.id && it.time.toLocalDate() == today }.sumOf { it.kcal }
    val toilets = data.excretionLogs.filter { it.petId == pet.id && it.time.toLocalDate() == today }
    val target = Nutrition.energy(pet, today).targetKcal
    return "今日 ${kcal.roundToInt()}/${formatKcal(target)}・" +
        ExcretionType.entries.joinToString("・") { t -> "${t.emoji}${toilets.count { it.type == t }}" } +
        (data.moods.firstOrNull { it.petId == pet.id && it.date == today }?.let { "・${it.mood.emoji}" } ?: "")
}

@Composable
fun PetEditor(
    initial: Pet?,
    firstTime: Boolean,
    onSave: (Pet) -> Unit,
    onCancel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    defaultColor: Int = 0,
    familyCard: (@Composable () -> Unit)? = null,
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
    var colorIndex by rememberSaveable { mutableIntStateOf(initial?.colorIndex ?: defaultColor) }
    var iconIndex by rememberSaveable { mutableIntStateOf(initial?.iconIndex ?: 0) }
    var targetText by rememberSaveable { mutableStateOf(initial?.targetWeightKg?.let { formatAmount(it) } ?: "") }
    var customKcalText by rememberSaveable { mutableStateOf(initial?.customKcal?.toString() ?: "") }
    var adoptionText by rememberSaveable { mutableStateOf(initial?.adoptionDate?.toString()) }
    var personality by rememberSaveable { mutableStateOf(initial?.personality ?: "") }
    var physical by rememberSaveable { mutableStateOf(initial?.physical ?: "") }
    var notes by rememberSaveable { mutableStateOf(initial?.notes ?: "") }
    var photoId by rememberSaveable { mutableStateOf(initial?.photoId) }
    var photoAsIcon by rememberSaveable { mutableStateOf(initial?.photoAsIcon ?: true) }
    var documents by remember { mutableStateOf(initial?.documents ?: emptyList()) }
    var newDocTitle by rememberSaveable { mutableStateOf("") }
    var viewingDoc by remember { mutableStateOf<PetDocument?>(null) }
    val photos = LocalPhotos.current
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
        familyCard?.invoke()
        SectionCard {
            OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Species.entries) ChoiceChip("${s.emoji} ${s.label}", species == s) {
                    if (species != s) { species = s; breed = ""; iconIndex = 0 }
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
        SectionCard(title = "📷 トップ画像") {
            val current = photoId
            if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhotoImage(current, Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)), maxSide = 400)
                    Column(Modifier.weight(1f)) {
                        CheckLine("アイコンもこの写真にする", photoAsIcon) { photoAsIcon = !photoAsIcon }
                        TextButton(onClick = { photoId = null }) {
                            Text("写真を外す", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                EmptyHint("正方形に切り抜いて保存します。家族共有でも送れるよう、小さめのサイズにします。")
            }
            PhotoButtons(PhotoPurpose.TOP, onPhoto = { photoId = it; photoAsIcon = true })
        }
        SectionCard(title = "🎨 アイコン（多頭飼いで見分けやすく）") {
            if (photoAsIcon && photoId != null) EmptyHint("いまはトップ画像をアイコンにしています。下の色は、写真が無いときの背景に使います。")
            AvatarPicker(species, iconIndex, colorIndex, onIcon = { iconIndex = it; photoAsIcon = false }, onColor = { colorIndex = it })
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
            Text("お迎えした日", style = MaterialTheme.typography.labelLarge)
            DateButton(
                adoptionText?.let { LocalDate.parse(it) },
                { adoptionText = it?.toString() },
                placeholder = "選ぶ（なくてもOK）",
                allowClear = true,
            )
        }
        SectionCard(title = "📝 この子のこと（なくてもOK）") {
            OutlinedTextField(personality, { personality = it }, label = { Text("性格（人なつこい・怖がり など）") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            OutlinedTextField(physical, { physical = it }, label = { Text("体の特徴（模様・持病・アレルギー など）") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            OutlinedTextField(notes, { notes = it }, label = { Text("そのほか（かかりつけ病院・苦手なこと など）") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
        }
        SectionCard(title = "📄 書類（診断書・血統書など）") {
            for (doc in documents) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhotoImage(doc.photoId, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).clickable { viewingDoc = doc }, maxSide = 300)
                    Text(doc.title.ifBlank { "書類" }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { documents = documents - doc }) { Text("外す", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            OutlinedTextField(
                newDocTitle, { newDocTitle = it },
                label = { Text("書類の名前（例：血統書、ワクチン証明書）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            PhotoButtons(PhotoPurpose.DOCUMENT, onPhoto = {
                documents = documents + PetDocument(it, newDocTitle.trim())
                newDocTitle = ""
            })
            EmptyHint("文字がなんとか読めるくらいに軽くして保存します。")
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
                            colorIndex = colorIndex,
                            iconIndex = iconIndex,
                            adoptionDate = adoptionText?.let { LocalDate.parse(it) },
                            personality = personality.trim(),
                            physical = physical.trim(),
                            notes = notes.trim(),
                            order = initial?.order ?: 0,
                            photoId = photoId,
                            photoAsIcon = photoAsIcon,
                            documents = documents,
                        ),
                    )
                    // 外した写真は消す（家族の端末からも）
                    val kept = setOfNotNull(photoId) + documents.map { it.photoId }
                    (listOfNotNull(initial?.photoId) + initial?.documents.orEmpty().map { it.photoId })
                        .filter { it !in kept }
                        .forEach { photos.delete(it) }
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
    viewingDoc?.let { doc -> PhotoViewer(doc.photoId, doc.title, onDismiss = { viewingDoc = null }, onDelete = null) }
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
