package com.okamejiiya.petcare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.model.Species

val bcsTitles = listOf("やせ", "ややせ", "理想的", "ややぽっちゃり", "ぽっちゃり")

fun bcsDescription(species: Species, bcs: Int): String = when (bcs) {
    1 -> "あばら骨・腰の骨・背骨が外から見える。上から見るとくびれが極端で、脂肪がほとんど触れない。"
    2 -> "あばら骨に簡単に触れる。上から見てはっきりくびれていて、お腹も引き締まっている。"
    3 -> "うすい脂肪ごしにあばら骨に触れる（見えない）。上から見て適度なくびれがある。"
    4 -> "脂肪が多く、あばら骨に触れにくい。上から見てくびれがほとんどない。" +
        if (species == Species.CAT) "お腹に脂肪がつき始めている。" else "腰まわりに脂肪がついている。"
    else -> "厚い脂肪であばら骨に触れない。上から見てくびれがなく、背中が平らに広がる。" +
        if (species == Species.CAT) "お腹が垂れ下がって揺れる。" else "お腹が垂れ下がっている。"
}

/** 上から見たシルエット。BCS によって胴のくびれが変わる */
@Composable
fun BodyShapeImage(species: Species, bcs: Int, modifier: Modifier = Modifier) {
    val i = bcs.coerceIn(1, 5) - 1
    val fill = if (species == Species.CAT) Color(0xFFF8DCC0) else Color(0xFFEFD2B2)
    val accent = if (species == Species.CAT) Color(0xFFE9B98F) else Color(0xFFD9A77A)
    val ink = Pastel.Ink.copy(alpha = 0.55f)
    Canvas(modifier) {
        // 100 x 124 の設計座標を実寸に合わせる
        val s = minOf(size.width / 100f, size.height / 124f)
        val ox = (size.width - 100f * s) / 2f
        val oy = (size.height - 124f * s) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)

        val shoulder = 15f + (i - 2) * 1.5f
        val rib = floatArrayOf(14f, 16f, 18f, 22f, 27f)[i]
        val waist = floatArrayOf(8f, 11f, 14f, 20f, 27f)[i]
        val hip = floatArrayOf(13f, 14f, 15f, 19f, 24f)[i]
        val side = listOf(6f to 32f, shoulder to 42f, rib to 58f, waist to 76f, hip to 92f, 5f to 106f)

        val body = Path().apply {
            val (x0, y0) = side.first()
            moveTo(p(50f + x0, y0).x, p(50f + x0, y0).y)
            for (k in 1 until side.size) {
                val (x1, y1) = side[k - 1]
                val (x2, y2) = side[k]
                val c1 = p(50f + x1, (y1 + y2) / 2f)
                val c2 = p(50f + x2, (y1 + y2) / 2f)
                val e = p(50f + x2, y2)
                cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
            }
            val bottom = side.last()
            val l0 = p(50f - bottom.first, bottom.second)
            quadraticTo(p(50f, 110f).x, p(50f, 110f).y, l0.x, l0.y)
            for (k in side.size - 1 downTo 1) {
                val (x1, y1) = side[k]
                val (x2, y2) = side[k - 1]
                val c1 = p(50f - x1, (y1 + y2) / 2f)
                val c2 = p(50f - x2, (y1 + y2) / 2f)
                val e = p(50f - x2, y2)
                cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
            }
            close()
        }

        // しっぽ
        val tail = Path().apply {
            val a = p(50f, 104f)
            moveTo(a.x, a.y)
            if (species == Species.CAT) {
                val c1 = p(52f, 118f)
                val c2 = p(76f, 124f)
                val e = p(80f, 106f)
                cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
            } else {
                val c = p(56f, 114f)
                val e = p(62f, 118f)
                quadraticTo(c.x, c.y, e.x, e.y)
            }
        }
        val tailWidth = if (species == Species.CAT) 6f * s else 7f * s
        drawPath(tail, ink, style = Stroke(width = tailWidth + 2.4f * s, cap = StrokeCap.Round))
        drawPath(tail, fill, style = Stroke(width = tailWidth, cap = StrokeCap.Round))

        drawPath(body, fill)
        drawPath(body, ink, style = Stroke(width = 1.4f * s))

        // やせているときは、あばら骨と腰の骨を描く
        if (i <= 1) {
            val ribAlpha = if (i == 0) 0.6f else 0.3f
            for (y in listOf(50f, 56f, 62f)) {
                val w = rib - 3f
                drawLine(ink.copy(alpha = ribAlpha), p(50f - w, y), p(50f - w + 6f, y + 1.5f), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
                drawLine(ink.copy(alpha = ribAlpha), p(50f + w, y), p(50f + w - 6f, y + 1.5f), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
            }
            if (i == 0) {
                drawCircle(ink.copy(alpha = 0.5f), 1.6f * s, p(50f - hip + 4f, 90f))
                drawCircle(ink.copy(alpha = 0.5f), 1.6f * s, p(50f + hip - 4f, 90f))
                drawLine(ink.copy(alpha = 0.35f), p(50f, 40f), p(50f, 98f), strokeWidth = 1f * s)
            }
        }
        // 背中の模様
        drawOval(accent, topLeft = p(44f, 46f), size = Size(12f * s, 30f * s))

        // 耳と頭
        if (species == Species.CAT) {
            for (dir in listOf(-1f, 1f)) {
                val ear = Path().apply {
                    val a = p(50f + dir * 5f, 10f)
                    val b = p(50f + dir * 15f, 0f)
                    val c = p(50f + dir * 14f, 17f)
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
                }
                drawPath(ear, fill)
                drawPath(ear, ink, style = Stroke(width = 1.4f * s))
            }
        }
        drawCircle(fill, 13f * s, p(50f, 21f))
        drawCircle(ink, 13f * s, p(50f, 21f), style = Stroke(width = 1.4f * s))
        if (species == Species.DOG) {
            for (dir in listOf(-1f, 1f)) {
                val tl = p(50f + dir * 13.5f - 4.5f, 14f)
                drawOval(accent, topLeft = tl, size = Size(9f * s, 17f * s))
                drawOval(ink, topLeft = tl, size = Size(9f * s, 17f * s), style = Stroke(width = 1.4f * s))
            }
        }
        drawCircle(Pastel.Ink, 1.6f * s, p(45f, 18f))
        drawCircle(Pastel.Ink, 1.6f * s, p(55f, 18f))
        drawCircle(Pastel.Pink, 1.8f * s, p(50f, 24f))
    }
}

/** 体形のイラストを並べて選ぶ */
@Composable
fun BcsSelector(species: Species, bcs: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (v in 1..5) {
                val selected = v == bcs
                OutlinedCard(
                    onClick = { onSelect(v) },
                    border = BorderStroke(
                        if (selected) 2.5.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.width(92.dp),
                ) {
                    Column(
                        Modifier.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BodyShapeImage(species, v, Modifier.size(width = 76.dp, height = 94.dp))
                        Text("BCS $v", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(
                            bcsTitles[v - 1],
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Text(
            bcsDescription(species, bcs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
