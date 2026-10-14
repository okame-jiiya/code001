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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

private class ShapeColors(species: Species) {
    val fill = if (species == Species.CAT) Color(0xFFF6D3B3) else Color(0xFFEBCBA6)
    val far = if (species == Species.CAT) Color(0xFFE8BC95) else Color(0xFFD9B086)
    val ink = Pastel.Ink.copy(alpha = 0.55f)
    val bone = Pastel.Ink.copy(alpha = 0.45f)
}

/** BCS 表と同じく「横から見た図」と「上から見た図」を並べる */
@Composable
fun BodyShapeImage(species: Species, bcs: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BcsSideView(species, bcs, Modifier.size(width = 104.dp, height = 66.dp))
        BcsTopView(species, bcs, Modifier.size(width = 52.dp, height = 80.dp))
    }
}

/** 横から見たシルエット（左向き）。BCS でお腹の引き締まり・垂れ下がりが変わる */
@Composable
fun BcsSideView(species: Species, bcs: Int, modifier: Modifier = Modifier) {
    val i = bcs.coerceIn(1, 5) - 1
    val c = ShapeColors(species)
    val dog = species == Species.DOG
    Canvas(modifier) {
        // 150 x 96 の設計座標
        val s = minOf(size.width / 150f, size.height / 96f)
        val ox = (size.width - 150f * s) / 2f
        val oy = (size.height - 96f * s) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        fun Path.m(x: Float, y: Float) = p(x, y).let { moveTo(it.x, it.y) }
        fun Path.l(x: Float, y: Float) = p(x, y).let { lineTo(it.x, it.y) }
        fun Path.cu(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val a = p(x1, y1); val b = p(x2, y2); val e = p(x3, y3)
            cubicTo(a.x, a.y, b.x, b.y, e.x, e.y)
        }

        val backY = floatArrayOf(32f, 31f, 30f, 28f, 26f)[i]
        val rumpY = floatArrayOf(31f, 30f, 30f, 29f, 28f)[i]
        val chestY = floatArrayOf(62f, 63f, 64f, 66f, 69f)[i]
        val bellyY = floatArrayOf(50f, 55f, 60f, 66f, 74f)[i]
        val legTop = 66f

        // しっぽ
        val tail = Path().apply {
            m(118f, rumpY + 3f)
            if (dog) cu(130f, rumpY - 2f, 136f, 22f, 132f, 12f) else cu(136f, rumpY, 146f, 30f, 140f, 6f)
        }
        val tw = (if (dog) 5.5f else 6f) * s
        drawPath(tail, c.ink, style = Stroke(width = tw + 2.4f * s, cap = StrokeCap.Round))
        drawPath(tail, c.fill, style = Stroke(width = tw, cap = StrokeCap.Round))

        // 奥側の脚
        for (x in listOf(52f, 116f)) {
            val tl = p(x, legTop - 6f)
            drawRoundRect(c.far, tl, Size(10f * s, (88f - legTop + 6f) * s), CornerRadius(4f * s))
        }

        val body = Path().apply {
            m(36f, 25f)
            cu(52f, backY, 98f, backY, 118f, rumpY + 2f)
            cu(128f, rumpY + 4f, 129f, 50f, 122f, 62f)
            l(121f, 86f); cu(121f, 89f, 108f, 89f, 108f, 86f)
            l(108f, legTop)
            cu(100f, bellyY + 1f, 72f, maxOf(bellyY, chestY) + 1f, 56f, chestY)
            l(54f, 86f); cu(54f, 89f, 42f, 89f, 42f, 86f)
            l(42f, 66f)
            cu(37f, 60f, 30f, 52f, 30f, 42f)
            cu(30f, 34f, 32f, 28f, 36f, 25f)
            close()
        }
        drawPath(body, c.fill)
        drawPath(body, c.ink, style = Stroke(width = 1.4f * s, join = StrokeJoin.Round))

        // やせ: あばら骨・背骨・腰骨が見える
        if (i <= 1) {
            val a = if (i == 0) 1f else 0.55f
            for (x in listOf(60f, 67f, 74f, 81f)) {
                val rib = Path().apply { m(x, backY + 9f); cu(x - 2f, backY + 18f, x - 2f, chestY - 10f, x + 1f, chestY - 5f) }
                drawPath(rib, c.bone.copy(alpha = 0.45f * a), style = Stroke(width = 1.3f * s, cap = StrokeCap.Round))
            }
            if (i == 0) {
                for (x in listOf(50f, 62f, 74f, 86f, 98f)) drawCircle(c.bone, 1.4f * s, p(x, backY + 1.5f))
                drawCircle(c.bone, 1.8f * s, p(112f, rumpY + 7f))
            }
        }

        // 頭
        val head = p(28f, 25f)
        if (dog) {
            drawRoundRect(c.fill, p(5f, 23f), Size(20f * s, 12f * s), CornerRadius(5f * s))
            drawRoundRect(c.ink, p(5f, 23f), Size(20f * s, 12f * s), CornerRadius(5f * s), style = Stroke(width = 1.4f * s))
            drawCircle(c.fill, 13f * s, head)
            drawCircle(c.ink, 13f * s, head, style = Stroke(width = 1.4f * s))
            drawRect(c.fill, p(15f, 24f), Size(7f * s, 10f * s))
            drawOval(c.far, p(29f, 15f), Size(10f * s, 21f * s))
            drawOval(c.ink, p(29f, 15f), Size(10f * s, 21f * s), style = Stroke(width = 1.2f * s))
            drawCircle(c.ink, 2.3f * s, p(6.5f, 26f))
        } else {
            for (ear in listOf(listOf(17f, 19f, 20f, 3f, 30f, 13f), listOf(27f, 13f, 35f, 2f, 39f, 18f))) {
                val e = Path().apply { m(ear[0], ear[1]); l(ear[2], ear[3]); l(ear[4], ear[5]); close() }
                drawPath(e, c.fill)
                drawPath(e, c.ink, style = Stroke(width = 1.4f * s, join = StrokeJoin.Round))
            }
            drawCircle(c.fill, 13f * s, head)
            drawCircle(c.ink, 13f * s, head, style = Stroke(width = 1.4f * s))
            drawCircle(c.fill, 6f * s, p(17f, 30f))
            drawCircle(c.ink, 1.7f * s, p(11.5f, 28.5f))
        }
        // 首のつなぎ目の線を消す
        drawCircle(c.fill, 7f * s, p(38f, 32f))
    }
}

/** 上から見たシルエット（頭が上）。BCS で腰のくびれが変わる */
@Composable
fun BcsTopView(species: Species, bcs: Int, modifier: Modifier = Modifier) {
    val i = bcs.coerceIn(1, 5) - 1
    val c = ShapeColors(species)
    val dog = species == Species.DOG
    Canvas(modifier) {
        // 70 x 110 の設計座標
        val s = minOf(size.width / 70f, size.height / 110f)
        val ox = (size.width - 70f * s) / 2f
        val oy = (size.height - 110f * s) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        val cx = 35f

        val shoulder = floatArrayOf(13f, 13.5f, 14f, 15.5f, 17f)[i]
        val rib = floatArrayOf(13f, 14.5f, 16f, 19f, 22f)[i]
        val waist = floatArrayOf(7.5f, 10f, 12.5f, 17f, 22.5f)[i]
        val hip = floatArrayOf(12f, 12.5f, 13f, 15.5f, 18f)[i]
        val side = listOf(6f to 26f, shoulder to 36f, rib to 52f, waist to 70f, hip to 86f, 5f to 98f)

        val tail = Path().apply {
            val a = p(cx, 96f); moveTo(a.x, a.y)
            val e = if (dog) p(cx, 106f) else p(cx + 3f, 110f)
            val m = if (dog) p(cx, 102f) else p(cx - 2f, 104f)
            quadraticTo(m.x, m.y, e.x, e.y)
        }
        val tw = (if (dog) 5.5f else 5f) * s
        drawPath(tail, c.ink, style = Stroke(width = tw + 2.4f * s, cap = StrokeCap.Round))
        drawPath(tail, c.fill, style = Stroke(width = tw, cap = StrokeCap.Round))

        val body = Path().apply {
            val (x0, y0) = side.first()
            p(cx + x0, y0).let { moveTo(it.x, it.y) }
            for (k in 1 until side.size) {
                val (x1, y1) = side[k - 1]; val (x2, y2) = side[k]
                val a = p(cx + x1, (y1 + y2) / 2f); val b = p(cx + x2, (y1 + y2) / 2f); val e = p(cx + x2, y2)
                cubicTo(a.x, a.y, b.x, b.y, e.x, e.y)
            }
            val bottom = side.last()
            val mid = p(cx, 102f); val l0 = p(cx - bottom.first, bottom.second)
            quadraticTo(mid.x, mid.y, l0.x, l0.y)
            for (k in side.size - 1 downTo 1) {
                val (x1, y1) = side[k]; val (x2, y2) = side[k - 1]
                val a = p(cx - x1, (y1 + y2) / 2f); val b = p(cx - x2, (y1 + y2) / 2f); val e = p(cx - x2, y2)
                cubicTo(a.x, a.y, b.x, b.y, e.x, e.y)
            }
            close()
        }
        drawPath(body, c.fill)
        drawPath(body, c.ink, style = Stroke(width = 1.4f * s, join = StrokeJoin.Round))
        if (i == 0) {
            drawLine(c.bone, p(cx, 34f), p(cx, 92f), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
            drawCircle(c.bone, 1.6f * s, p(cx - hip + 4f, 86f))
            drawCircle(c.bone, 1.6f * s, p(cx + hip - 4f, 86f))
        }
        if (i <= 1) {
            for (y in listOf(44f, 50f, 56f)) {
                drawLine(c.bone.copy(alpha = if (i == 0) 0.5f else 0.25f), p(cx - rib + 2.5f, y), p(cx - rib + 7f, y + 1.5f), strokeWidth = 1.1f * s, cap = StrokeCap.Round)
                drawLine(c.bone.copy(alpha = if (i == 0) 0.5f else 0.25f), p(cx + rib - 2.5f, y), p(cx + rib - 7f, y + 1.5f), strokeWidth = 1.1f * s, cap = StrokeCap.Round)
            }
        }

        // 頭（真上から。顔は描かない）
        if (dog) {
            for (dir in listOf(-1f, 1f)) {
                drawOval(c.far, p(cx + dir * 10f - 4f, 12f), Size(8f * s, 16f * s))
                drawOval(c.ink, p(cx + dir * 10f - 4f, 12f), Size(8f * s, 16f * s), style = Stroke(width = 1.2f * s))
            }
            drawOval(c.fill, p(cx - 4.5f, 0f), Size(9f * s, 14f * s))
            drawOval(c.ink, p(cx - 4.5f, 0f), Size(9f * s, 14f * s), style = Stroke(width = 1.3f * s))
        } else {
            for (dir in listOf(-1f, 1f)) {
                val e = Path().apply {
                    val a = p(cx + dir * 3f, 9f); val b = p(cx + dir * 11f, 2f); val d = p(cx + dir * 10.5f, 15f)
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); close()
                }
                drawPath(e, c.fill)
                drawPath(e, c.ink, style = Stroke(width = 1.3f * s, join = StrokeJoin.Round))
            }
        }
        drawOval(c.fill, p(cx - 10.5f, 8f), Size(21f * s, 20f * s))
        drawOval(c.ink, p(cx - 10.5f, 8f), Size(21f * s, 20f * s), style = Stroke(width = 1.4f * s))
        drawOval(c.fill, p(cx - 5.5f, 22f), Size(11f * s, 8f * s))
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
                    modifier = Modifier.width(118.dp),
                ) {
                    Column(
                        Modifier.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BodyShapeImage(species, v)
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
