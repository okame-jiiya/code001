package com.okamejiiya.petcare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ワンタップで付けた記録の取り消し。押し間違いに後から気づいても戻せるよう、
 * スナックバーが消えたあとも、アプリを開いている間は新しい順に取り消せる。
 */
class UndoHistory {
    class Entry(val label: String, val undo: () -> Unit)

    private val entries = mutableStateListOf<Entry>()

    val last: Entry? get() = entries.lastOrNull()
    val size: Int get() = entries.size

    fun push(label: String, undo: () -> Unit): Entry {
        val e = Entry(label, undo)
        entries.add(e)
        if (entries.size > 30) entries.removeAt(0)
        return e
    }

    fun undo(e: Entry): Boolean {
        if (!entries.remove(e)) return false
        e.undo()
        return true
    }

    fun clear() = entries.clear()
}

/** 記録したことをスナックバーで知らせ、取り消しの履歴にも残す */
fun recordedWithUndo(scope: CoroutineScope, snackbar: SnackbarHostState, history: UndoHistory, message: String, undo: () -> Unit) {
    val entry = history.push(message.removeSuffix("を記録しました").removeSuffix("しました"), undo)
    scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar(message, actionLabel = "取り消す", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) history.undo(entry)
    }
}

/** 「直前の記録を取り消す」ボタン（履歴が無いときは何も出さない） */
@Composable
fun UndoBar(history: UndoHistory) {
    val last = history.last ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { history.undo(last) }) { Text("↩ 取り消す", fontWeight = FontWeight.Bold) }
            Text(
                "直前：${last.label}" + if (history.size > 1) "（ほか${history.size - 1}件も順に戻せます）" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { history.clear() }) { Text("閉じる", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
