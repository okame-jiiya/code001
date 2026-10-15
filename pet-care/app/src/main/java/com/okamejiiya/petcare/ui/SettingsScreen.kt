@file:OptIn(ExperimentalLayoutApi::class)

package com.okamejiiya.petcare.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.okamejiiya.petcare.data.FamilySync
import com.okamejiiya.petcare.data.PdfReport
import com.okamejiiya.petcare.data.PdfSection
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.data.UiPrefs
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.RecordItem
import com.okamejiiya.petcare.model.Species
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** 設定（文字の大きさ・記録する項目・リマインダー・並べ替え・PDF・家族共有・バックアップ） */
@Composable
fun SettingsScreen(data: AppData, pet: Pet, store: PetStore, family: FamilySync, prefs: UiPrefs, modifier: Modifier) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { FontCard(prefs) }
        item { RecordItemsCard(data, store) }
        item { ReminderSettings(data, store) }
        item { PetOrderCard(data, store) }
        item { PdfExportCard(data, pet) }
        item { FamilyCard(family) }
        item { BackupCard(store) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private val fontScales = listOf("小さめ" to 0.9f, "ふつう" to 1f, "大きめ" to 1.15f, "とても大きく" to 1.3f)

@Composable
private fun FontCard(prefs: UiPrefs) {
    val scale by prefs.fontScale.collectAsState()
    SectionCard(title = "🔤 文字の大きさ") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, v) in fontScales) ChoiceChip(label, scale == v) { prefs.setFontScale(v) }
        }
        EmptyHint("この端末だけの設定です。スマホ本体の文字の大きさに合わせて、さらに大きく・小さくします。")
    }
}

@Composable
private fun RecordItemsCard(data: AppData, store: PetStore) {
    SectionCard(title = "📝 記録する項目") {
        EmptyHint("チェックした項目が「きょう」と「カレンダー」に出ます。あとからいつでも足したり外したりできます（記録は消えません）。家族と共通の設定です。")
        Column {
            for (item in RecordItem.entries) {
                CheckLine(item.emoji + " " + item.title, data.isOn(item)) {
                    store.updateSettings { s ->
                        val next = if (item in s.items) s.items - item else s.items + item
                        s.copy(items = RecordItem.entries.filter { it in next })
                    }
                }
            }
        }
    }
}

/** 自動で並べ替えるときの順番 */
private enum class AutoSort(val label: String) {
    DOG_FIRST("犬→猫・年上から"),
    CAT_FIRST("猫→犬・年上から"),
    OLDER("年上から"),
    YOUNGER("年下から"),
    ADOPTED("お迎え順"),
    NAME("名前順"),
}

private fun sortPets(pets: List<Pet>, by: AutoSort): List<Pet> = when (by) {
    AutoSort.DOG_FIRST -> pets.sortedWith(compareBy<Pet> { if (it.species == Species.DOG) 0 else 1 }.thenBy { it.birthDate })
    AutoSort.CAT_FIRST -> pets.sortedWith(compareBy<Pet> { if (it.species == Species.CAT) 0 else 1 }.thenBy { it.birthDate })
    AutoSort.OLDER -> pets.sortedBy { it.birthDate }
    AutoSort.YOUNGER -> pets.sortedByDescending { it.birthDate }
    AutoSort.ADOPTED -> pets.sortedWith(compareBy<Pet> { it.adoptionDate ?: LocalDate.MAX }.thenBy { it.id })
    AutoSort.NAME -> pets.sortedBy { it.name }
}

@Composable
private fun PetOrderCard(data: AppData, store: PetStore) {
    val pets = data.sortedPets
    if (pets.size < 2) return
    SectionCard(title = "🐾 うちの子の並べ替え") {
        pets.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PetAvatar(p, 30)
                HSpace(10)
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Text("${p.species.label}・${ageText(p)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = i > 0, onClick = {
                    val ids = pets.map { it.id }.toMutableList()
                    ids.add(i - 1, ids.removeAt(i))
                    store.reorderPets(ids)
                }) { Text("▲") }
                TextButton(enabled = i < pets.lastIndex, onClick = {
                    val ids = pets.map { it.id }.toMutableList()
                    ids.add(i + 1, ids.removeAt(i))
                    store.reorderPets(ids)
                }) { Text("▼") }
            }
        }
        Text("自動で並べる", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in AutoSort.entries) {
                OutlinedButton(onClick = { store.reorderPets(sortPets(pets, s).map { it.id }) }) { Text(s.label) }
            }
        }
    }
}

private enum class PdfRange(val label: String, val months: Long?) {
    MONTH("1か月", 1), QUARTER("3か月", 3), HALF("半年", 6), YEAR("1年", 12), ALL("すべて", null), CUSTOM("日付を選ぶ", null)
}

@Composable
private fun PdfExportCard(data: AppData, current: Pet) {
    val context = LocalContext.current
    val photos = LocalPhotos.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var petId by rememberSaveable { mutableStateOf(current.id) }
    var range by rememberSaveable { mutableStateOf(PdfRange.MONTH) }
    var fromText by rememberSaveable { mutableStateOf(today.minusMonths(1).toString()) }
    var toText by rememberSaveable { mutableStateOf(today.toString()) }
    var sections by remember { mutableStateOf(PdfSection.entries.filter { it.default }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<File?>(null) }
    val pet = data.pets.firstOrNull { it.id == petId } ?: current

    val earliest = listOfNotNull(
        pet.birthDate, pet.adoptionDate,
        data.feedLogs.filter { it.petId == pet.id }.minOfOrNull { it.time }?.toLocalDate(),
    ).max().coerceAtMost(today)
    val from = when (range) {
        PdfRange.CUSTOM -> LocalDate.parse(fromText)
        PdfRange.ALL -> listOfNotNull(
            data.feedLogs.filter { it.petId == pet.id }.minOfOrNull { it.time }?.toLocalDate(),
            data.excretionLogs.filter { it.petId == pet.id }.minOfOrNull { it.time }?.toLocalDate(),
            data.weights.filter { it.petId == pet.id }.minOfOrNull { it.date },
            data.health.filter { it.petId == pet.id }.minOfOrNull { it.date },
            earliest,
        ).min()
        else -> today.minusMonths(range.months ?: 1L)
    }
    val to = if (range == PdfRange.CUSTOM) LocalDate.parse(toText) else today

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = made ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            }.onSuccess { Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "保存できませんでした", Toast.LENGTH_LONG).show() }
        }
    }

    SectionCard(title = "📄 PDF で渡す（獣医さん・シッターさん・譲渡先へ）") {
        if (data.pets.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in data.sortedPets) ChoiceChip(p.name, p.id == pet.id) { petId = p.id; made = null }
            }
        }
        Text("期間", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in PdfRange.entries) ChoiceChip(r.label, range == r) { range = r; made = null }
        }
        if (range == PdfRange.CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateButton(LocalDate.parse(fromText), { if (it != null) { fromText = it.toString(); made = null } })
                Text(" 〜 ")
                DateButton(LocalDate.parse(toText), { if (it != null) { toText = it.toString(); made = null } })
            }
        } else {
            EmptyHint("${from.slashText()} 〜 ${to.slashText()}")
        }
        Text("入れる内容", style = MaterialTheme.typography.labelLarge)
        Column {
            for (s in PdfSection.entries) CheckLine(s.label, s in sections) {
                sections = if (s in sections) sections - s else sections + s
                made = null
            }
        }
        val file = made
        if (file == null) {
            Button(enabled = !busy && sections.isNotEmpty() && !from.isAfter(to), onClick = {
                busy = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            // 家族の端末で撮った写真は、先にクラウドから取ってくる
                            val ids = buildList {
                                if (PdfSection.PHOTO in sections) pet.photoId?.let { add(it) }
                                if (PdfSection.DOCUMENTS in sections) addAll(pet.documents.map { it.photoId })
                                if (PdfSection.TOILET_PHOTOS in sections) {
                                    addAll(
                                        data.excretionLogs
                                            .filter { it.petId == pet.id && !it.time.toLocalDate().isBefore(from) && !it.time.toLocalDate().isAfter(to) }
                                            .flatMap { it.photoIds },
                                    )
                                }
                            }
                            for (id in ids) if (!photos.has(id)) photos.load(id, 64)
                            val name = "${pet.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}_記録_${from}_$to.pdf"
                            val out = File(context.cacheDir, "shared/$name")
                            PdfReport(data, pet, photos).build(from, to, sections, out)
                            out
                        }
                    }
                    busy = false
                    result.onSuccess { made = it }.onFailure {
                        Toast.makeText(context, "PDF を作れませんでした", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(if (busy) "作っています…" else "PDF を作る") }
        } else {
            Text("✅ ${file.name}（${file.length() / 1024} KB）", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "${pet.name}の記録")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "PDF を送る"))
                }) { Text("送る（LINE・メールなど）") }
                FilledTonalButton(onClick = { saver.launch(file.name) }) { Text("ファイルに保存") }
            }
            TextButton(onClick = { made = null }) { Text("作り直す") }
        }
        EmptyHint("作った PDF は、この端末の中で作るだけで、どこにも送られません。「送る」で相手を選んで渡してください。")
    }
}
