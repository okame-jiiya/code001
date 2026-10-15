package com.okamejiiya.petcare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.Species

/** 多頭飼いで見分けるためのアイコン背景色（パステル） */
val petColors = listOf(
    Color(0xFFFFC9D6), // ピンク
    Color(0xFFFFD8B5), // ピーチ
    Color(0xFFFFF0A8), // レモン
    Color(0xFFC6EBD9), // ミント
    Color(0xFFC4E1F6), // そら
    Color(0xFFDCCFF5), // ラベンダー
    Color(0xFFE6D3C0), // ミルクティー
    Color(0xFFDCDCE3), // グレー
)

fun petColor(index: Int): Color = petColors[index.mod(petColors.size)]

/** 選ばれていない色のうち最初のもの（新しい子の初期値） */
fun nextFreeColor(pets: List<Pet>): Int {
    val used = pets.map { it.colorIndex.mod(petColors.size) }.toSet()
    return petColors.indices.firstOrNull { it !in used } ?: (pets.size % petColors.size)
}

@Composable
fun PetAvatar(icon: String, colorIndex: Int, size: Int = 28) {
    Box(
        Modifier
            .size(size.dp)
            .background(petColor(colorIndex), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(icon, fontSize = (size * 0.55f).sp)
    }
}

@Composable
fun PetAvatar(pet: Pet, size: Int = 28) = PetAvatar(pet.icon, pet.colorIndex, size)

/** アイコンの絵柄と色を選ぶ */
@Composable
fun AvatarPicker(species: Species, iconIndex: Int, colorIndex: Int, onIcon: (Int) -> Unit, onColor: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            species.icons.forEachIndexed { i, icon ->
                Box(
                    Modifier
                        .size(44.dp)
                        .border(
                            BorderStroke(if (i == iconIndex) 2.5.dp else 0.dp, if (i == iconIndex) MaterialTheme.colorScheme.primary else Color.Transparent),
                            CircleShape,
                        )
                        .clickable { onIcon(i) },
                    contentAlignment = Alignment.Center,
                ) { PetAvatar(icon, colorIndex, 38) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            petColors.forEachIndexed { i, c ->
                Box(
                    Modifier
                        .size(32.dp)
                        .background(c, CircleShape)
                        .border(
                            if (i == colorIndex) 2.5.dp else 1.dp,
                            if (i == colorIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                        .clickable { onColor(i) },
                )
            }
        }
    }
}
