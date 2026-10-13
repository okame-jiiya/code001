package com.okamejiiya.petcare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.FeedLog
import com.okamejiiya.petcare.model.Food
import com.okamejiiya.petcare.model.FoodKind
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.formatAmount
import com.okamejiiya.petcare.model.formatKcal
import java.time.LocalDate

/** foodId / amount は初期値。editing があればその記録を編集する */
data class FeedDialogRequest(val foodId: Long?, val amount: Double?, val editing: FeedLog? = null)

@Composable
fun FeedDialog(
    data: AppData,
    pet: Pet,
    day: LocalDate,
    request: FeedDialogRequest,
    onDismiss: () -> Unit,
    onSave: (FeedLog) -> Unit,
) {
    val foods = data.foods
    var foodId by remember { mutableStateOf(request.foodId ?: foods.firstOrNull()?.id) }
    val food: Food? = foods.firstOrNull { it.id == foodId }
    var amountText by remember { mutableStateOf(request.amount?.let { formatAmount(it) } ?: "") }
    var time by remember { mutableLongStateOf(request.editing?.time ?: defaultTimeFor(day)) }
    var pickTime by remember { mutableStateOf(false) }

    val amount = amountText.toDoubleLoose() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.editing != null) "記録を直す" else "ごはん・おやつを記録") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.editing != null && food == null) {
                    Text("${request.editing.foodName}（登録から削除済み）")
                }
                Column(
                    Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (f in foods) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (foodId != f.id) {
                                        foodId = f.id
                                        amountText = if (f.divisible) "" else "1"
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = f.id == foodId, onClick = null)
                            Text(
                                (if (f.kind == FoodKind.TREAT) "🍪 " else "🍚 ") + f.name,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (food != null) {
                    if (food.divisible) {
                        NumberField(amountText, { amountText = it }, "あげた量", suffix = food.unitLabel)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("あげた数", modifier = Modifier.weight(1f))
                            Stepper(
                                "${formatAmount(amount)} ${food.unitLabel}",
                                onMinus = { amountText = formatAmount((amount - 0.5).coerceAtLeast(0.5)) },
                                onPlus = { amountText = formatAmount(amount + 0.5) },
                            )
                        }
                    }
                    Text(
                        "= ${formatKcal(amount * food.kcalPerUnit)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${time.toLocalDate().jpText()} ", modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickTime = true }) { Text("🕒 ${time.timeText()}") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = food != null && amount > 0,
                onClick = {
                    val f = food ?: return@TextButton
                    onSave(
                        FeedLog(
                            id = request.editing?.id ?: Ids.next(),
                            petId = pet.id,
                            foodId = f.id,
                            foodName = f.name,
                            kind = f.kind,
                            amount = amount,
                            unitLabel = f.unitLabel,
                            kcal = amount * f.kcalPerUnit,
                            time = time,
                        ),
                    )
                },
            ) { Text("記録する") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    if (pickTime) {
        TimePickDialog(time, onDismiss = { pickTime = false }) { time = it; pickTime = false }
    }
}
