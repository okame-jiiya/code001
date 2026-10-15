package com.okamejiiya.petcare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Expense
import com.okamejiiya.petcare.model.ExpenseCategory
import java.text.NumberFormat
import java.util.Locale

fun yen(amount: Long): String = "¥" + NumberFormat.getNumberInstance(Locale.JAPAN).format(amount)

/** カレンダーのマスに入るくらい短い金額（1.2万 など） */
fun shortYen(amount: Long): String = when {
    amount >= 100_000 -> "${amount / 10_000}万"
    amount >= 10_000 -> "%.1f万".format(amount / 10_000.0)
    else -> "¥$amount"
}

@Composable
fun ExpenseDialog(data: AppData, initial: Expense, isNew: Boolean, onDismiss: () -> Unit, onSave: (Expense) -> Unit, onDelete: (() -> Unit)?) {
    var category by remember { mutableStateOf(initial.category) }
    var amountText by remember { mutableStateOf(if (initial.amount > 0) initial.amount.toString() else "") }
    var date by remember { mutableStateOf(initial.date) }
    var petId by remember { mutableStateOf(initial.petId) }
    var memo by remember { mutableStateOf(initial.memo) }
    val amount = amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "支出を記録" else "支出を直す") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in ExpenseCategory.entries) ChoiceChip("${c.emoji} ${c.label}", category == c) { category = c }
                }
                NumberField(amountText, { amountText = it }, "金額", Modifier.fillMaxWidth(), suffix = "円", decimal = false)
                DateButton(date, { if (it != null) date = it })
                Text("だれの分", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("みんな", petId == null) { petId = null }
                    for (p in data.sortedPets) ChoiceChip(p.name, petId == p.id) { petId = p.id }
                }
                OutlinedTextField(
                    memo, { memo = it },
                    label = { Text("メモ（お店・品名など）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) TextButton(onClick = onDelete) { Text("この支出を削除", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = amount > 0, onClick = {
                onSave(initial.copy(category = category, amount = amount, date = date, petId = petId, memo = memo.trim()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun ExpenseRow(data: AppData, e: Expense, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${e.category.emoji} ${e.category.label}" + if (e.memo.isNotBlank()) "・${e.memo}" else "")
            Text(
                e.petId?.let { id -> data.pets.firstOrNull { it.id == id }?.name } ?: "みんな",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(yen(e.amount), fontWeight = FontWeight.Bold)
    }
}
