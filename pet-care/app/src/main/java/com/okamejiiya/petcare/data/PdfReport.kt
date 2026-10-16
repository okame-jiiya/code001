package com.okamejiiya.petcare.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.Nutrition
import com.okamejiiya.petcare.model.PdfSection
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.RecordKind
import com.okamejiiya.petcare.model.formatAmount
import com.okamejiiya.petcare.model.formatKcal
import com.okamejiiya.petcare.model.formatQuantity
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * 記録を PDF（A4）に出力する（獣医さん・ペットシッターさんへの提出など）。
 * 絵文字は PDF だと表示できないことがあるので、見出しは【】で書く。
 */
class PdfReport(private val data: AppData, private val pet: Pet, private val photos: PhotoStore) {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNo = 0
    private var y = 0f
    private val canvas: Canvas get() = requireNotNull(page).canvas

    fun build(from: LocalDate, to: LocalDate, sections: Set<PdfSection>, out: File) {
        newPage()
        text("${pet.name}の記録", 20f, bold = true, color = PINK)
        text("期間 ${from.slash()} 〜 ${to.slash()}　作成 ${LocalDate.now().slash()}", 9f, color = GRAY)
        gap(8f)

        if (PdfSection.PHOTO in sections) pet.photoId?.let { image(it, 150f, 150f) }
        if (PdfSection.PROFILE in sections) profile()
        if (PdfSection.WEIGHT in sections) weights(from, to)
        days(from, to, sections)
        if (PdfSection.EXPENSE in sections) expenseSummary(from, to)
        if (PdfSection.DOCUMENTS in sections) documents()

        finishPage()
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    // ---------------- 中身 ----------------

    private fun profile() {
        heading("プロフィール")
        val today = LocalDate.now()
        val m = Nutrition.ageInMonths(pet.birthDate, today)
        val age = if (m < 12) "${m}か月" else "${m / 12}歳${if (m % 12 > 0) "${m % 12}か月" else ""}"
        line("種類", "${pet.species.label}・${pet.breed.ifBlank { "品種未登録" }}")
        line("性別", "${pet.sex.label}（${if (pet.neutered) "避妊・去勢済み" else "未去勢"}）")
        line("生年月日", "${if (pet.birthEstimated) "推定 " else ""}${pet.birthDate.slash()}（$age）")
        pet.adoptionDate?.let { line("お迎えした日", it.slash()) }
        line("体重", "${formatAmount(pet.weightKg)} kg（体形 BCS ${pet.bcs}/5）")
        line("1日のカロリーの目安", formatKcal(Nutrition.energy(pet, today).targetKcal))
        val plans = data.plans.filter { it.petId == pet.id }.mapNotNull { p -> data.food(p.foodId)?.let { p to it } }
        if (plans.isNotEmpty()) {
            line("いつものごはん", plans.joinToString("、") { (p, f) -> "${f.name}（${p.timesPerCycle}回/${p.cycleDays}日）" })
        }
        if (pet.personality.isNotBlank()) line("性格", pet.personality)
        if (pet.physical.isNotBlank()) line("体の特徴", pet.physical)
        if (pet.notes.isNotBlank()) line("そのほか", pet.notes)
        gap(6f)
    }

    private fun weights(from: LocalDate, to: LocalDate) {
        val list = data.weights.filter { it.petId == pet.id && !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }
        if (list.isEmpty()) return
        heading("体重の記録")
        text(list.joinToString("　") { "${it.date.monthValue}/${it.date.dayOfMonth} ${formatAmount(it.kg)}kg" }, 10f)
        gap(6f)
    }

    private fun days(from: LocalDate, to: LocalDate, sections: Set<PdfSection>) {
        fun inRange(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
        val feeds = if (PdfSection.FOOD in sections) data.feedLogs.filter { it.petId == pet.id && inRange(it.time.date()) }.groupBy { it.time.date() } else emptyMap()
        val toilets = if (PdfSection.TOILET in sections) data.excretionLogs.filter { it.petId == pet.id && inRange(it.time.date()) }.groupBy { it.time.date() } else emptyMap()
        val moods = if (PdfSection.MOOD in sections) data.moods.filter { it.petId == pet.id && inRange(it.date) }.associateBy { it.date } else emptyMap()
        val records = if (PdfSection.RECORDS in sections) data.records.filter { it.petId == pet.id && inRange(it.time.date()) }.groupBy { it.time.date() } else emptyMap()
        val health = if (PdfSection.SCHEDULE in sections) data.health.filter { it.petId == pet.id && inRange(it.date) }.groupBy { it.date } else emptyMap()
        val diaries = if (PdfSection.DIARY in sections) data.diaries.filter { it.petId == pet.id && inRange(it.date) }.associateBy { it.date } else emptyMap()
        val expenses = if (PdfSection.EXPENSE in sections) data.expenses.filter { (it.petId == null || it.petId == pet.id) && inRange(it.date) }.groupBy { it.date } else emptyMap()
        val dates = (feeds.keys + toilets.keys + moods.keys + records.keys + health.keys + diaries.keys + expenses.keys).sorted()
        if (dates.isEmpty()) return
        heading("毎日の記録")
        for (d in dates) {
            ensure(40f)
            text("${d.slash()}（${"月火水木金土日"[d.dayOfWeek.value - 1]}）", 11f, bold = true, color = INK)
            moods[d]?.let { item("きげん", it.mood.label) }
            feeds[d]?.sortedBy { it.time }?.let { list ->
                item("ごはん", "合計 ${formatKcal(list.sumOf { it.kcal })}：" +
                    list.joinToString("、") { "${it.time.hm()} ${it.foodName} ${formatQuantity(it.amount, it.unitLabel)}" })
            }
            toilets[d]?.sortedBy { it.time }?.let { list ->
                for (t in ExcretionType.entries) {
                    val l = list.filter { it.type == t }
                    if (l.isEmpty()) continue
                    item(t.label, "${l.size}回：" + l.joinToString("、") { e ->
                        e.time.hm() + listOfNotNull(e.condition, e.flags.takeIf { it.isNotEmpty() }?.joinToString("・") { "※$it" }, e.note.ifBlank { null })
                            .joinToString(" ").let { if (it.isEmpty()) "" else "（$it）" }
                    })
                }
                if (PdfSection.TOILET_PHOTOS in sections) {
                    val ids = list.flatMap { it.photoIds }
                    if (ids.isNotEmpty()) thumbnails(ids)
                }
            }
            records[d]?.sortedBy { it.time }?.forEach { r ->
                val value = when (r.item.kind) {
                    RecordKind.NUMBER -> r.value?.let { formatAmount(it) + r.item.unit } ?: ""
                    RecordKind.CHECK -> if (r.done) "済み" else "未"
                    RecordKind.TEXT -> ""
                }
                item(r.item.label, listOf(r.time.hm(), value, r.text).filter { it.isNotBlank() }.joinToString(" "))
            }
            health[d]?.sortedBy { it.startsAt() }?.forEach { h ->
                item(h.kind.label, listOfNotNull(h.time?.let { "%d:%02d".format(it.hour, it.minute) }, h.title, h.note.ifBlank { null }).joinToString(" "))
            }
            diaries[d]?.let { item("日記", it.text) }
            expenses[d]?.forEach { e -> item("支出", "${e.category.label} ${yen(e.amount)}" + if (e.memo.isNotBlank()) "（${e.memo}）" else "") }
            gap(4f)
        }
    }

    private fun expenseSummary(from: LocalDate, to: LocalDate) {
        val list = data.expenses.filter { (it.petId == null || it.petId == pet.id) && !it.date.isBefore(from) && !it.date.isAfter(to) }
        if (list.isEmpty()) return
        heading("支出のまとめ")
        line("合計", yen(list.sumOf { it.amount }) + "（${pet.name}の分と、みんなの分）")
        for ((c, l) in list.groupBy { it.category }.toList().sortedByDescending { (_, l) -> l.sumOf { it.amount } }) {
            line(c.label, yen(l.sumOf { it.amount }))
        }
        gap(6f)
    }

    private fun documents() {
        for (d in pet.documents) {
            val bmp = bitmap(d.photoId) ?: continue
            finishPage()
            newPage()
            text(d.title.ifBlank { "書類" }, 14f, bold = true, color = INK)
            gap(4f)
            drawFit(bmp, CONTENT_W, PAGE_H - MARGIN - y)
        }
    }

    // ---------------- 描画の部品 ----------------

    private fun heading(t: String) {
        ensure(48f)
        gap(6f)
        text(t, 14f, bold = true, color = PINK)
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, Paint().apply { color = LINE; strokeWidth = 1f })
        gap(6f)
    }

    private fun line(label: String, value: String) = text("$label：$value", 10.5f)

    private fun item(label: String, value: String) = text("【$label】$value", 10f, indent = 10f)

    private fun text(t: String, size: Float, bold: Boolean = false, color: Int = INK, indent: Float = 0f) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = (CONTENT_W - indent).toInt()
        val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
        // 長い文はページをまたいで 1 行ずつ描く
        for (i in 0 until layout.lineCount) {
            val h = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
            ensure(h)
            canvas.save()
            canvas.translate(MARGIN + indent, y - layout.getLineTop(i))
            canvas.clipRect(0f, layout.getLineTop(i).toFloat(), width.toFloat(), layout.getLineBottom(i).toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += h
        }
    }

    private fun image(id: String, maxW: Float, maxH: Float) {
        val bmp = bitmap(id) ?: return
        ensure(maxH)
        drawFit(bmp, maxW, maxH)
        gap(6f)
    }

    private fun thumbnails(ids: List<String>) {
        val size = 90f
        ensure(size + 4f)
        var x = MARGIN + 10f
        for (id in ids) {
            val bmp = bitmap(id) ?: continue
            if (x + size > PAGE_W - MARGIN) {
                y += size + 4f
                x = MARGIN + 10f
                ensure(size + 4f)
            }
            val s = minOf(size / bmp.width, size / bmp.height)
            canvas.drawBitmap(bmp, null, RectF(x, y, x + bmp.width * s, y + bmp.height * s), Paint(Paint.FILTER_BITMAP_FLAG))
            x += size + 6f
        }
        y += size + 4f
    }

    private fun drawFit(bmp: Bitmap, maxW: Float, maxH: Float) {
        val s = minOf(maxW / bmp.width, maxH / bmp.height, 1.5f)
        val w = bmp.width * s
        val h = bmp.height * s
        canvas.drawBitmap(bmp, null, RectF(MARGIN, y, MARGIN + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        y += h
    }

    private fun bitmap(id: String): Bitmap? = photos.bytes(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    private fun gap(h: Float) {
        y += h
    }

    /** 残りが h より少なければ次のページへ */
    private fun ensure(h: Float) {
        if (y + h > PAGE_H - MARGIN) {
            finishPage()
            newPage()
        }
    }

    private fun newPage() {
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create())
        y = MARGIN
    }

    private fun finishPage() {
        val p = page ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = GRAY }
        p.canvas.drawText("うちの子ごはん帳　${pet.name}　$pageNo", MARGIN, PAGE_H - 20f, paint)
        doc.finishPage(p)
        page = null
    }

    private fun LocalDate.slash() = "$year/$monthValue/$dayOfMonth"
    private fun Long.dateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
    private fun Long.date(): LocalDate = dateTime().toLocalDate()
    private fun Long.hm(): String = dateTime().let { "%d:%02d".format(it.hour, it.minute) }
    private fun yen(v: Long) = "¥" + NumberFormat.getNumberInstance(Locale.JAPAN).format(v)

    private companion object {
        const val PAGE_W = 595f
        const val PAGE_H = 842f
        const val MARGIN = 40f
        const val CONTENT_W = PAGE_W - MARGIN * 2
        val INK = Color.rgb(0x4F, 0x42, 0x47)
        val GRAY = Color.rgb(0x8A, 0x7A, 0x80)
        val PINK = Color.rgb(0xC9, 0x5F, 0x80)
        val LINE = Color.rgb(0xEB, 0xDC, 0xE0)
    }
}
