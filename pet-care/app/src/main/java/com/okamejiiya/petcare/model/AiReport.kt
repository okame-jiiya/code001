package com.okamejiiya.petcare.model

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** PDF・AI 相談用の文字に入れられる内容 */
enum class PdfSection(val label: String, val default: Boolean = true) {
    PROFILE("プロフィール"),
    PHOTO("トップ画像"),
    DOCUMENTS("書類の画像（診断書・血統書など）", default = false),
    WEIGHT("体重"),
    FOOD("ごはん"),
    TOILET("うんち・おしっこ"),
    TOILET_PHOTOS("トイレの写真", default = false),
    MOOD("機嫌"),
    RECORDS("そのほかの記録（体温・薬など）"),
    SCHEDULE("予定・通院"),
    DIARY("日記", default = false),
    EXPENSE("支出", default = false),
}

/**
 * AI に読んでもらって健康の相談をするための記録（マークダウン）。
 * 先頭に相談文の見本、続けてプロフィールと、1日1行の表を置く。写真は入れない。
 * Web 版の aiReport() と同じ内容にする。
 */
object AiReport {
    fun build(
        data: AppData,
        pet: Pet,
        from: LocalDate,
        to: LocalDate,
        sections: Set<PdfSection>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        fun Long.dateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)
        fun Long.date(): LocalDate = dateTime().toLocalDate()
        fun Long.hm(): String = dateTime().let { "%d:%02d".format(it.hour, it.minute) }
        fun inRange(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
        fun has(s: PdfSection) = s in sections
        val species = pet.species.label
        val energy = Nutrition.energy(pet, today)
        val sb = StringBuilder()
        fun ln(s: String = "") = sb.append(s).append('\n')

        ln("# ${pet.name}（$species）の健康記録 ${from.slash()}〜${to.slash()}")
        ln()
        ln("> 飼い主がアプリ「うちの子ごはん帳」でつけた記録です（${today.slash()} 作成）。記録のない日は、つけ忘れの可能性もあります。")
        ln()
        ln("## 相談したいこと")
        ln()
        ln("うちの${species}「${pet.name}」の記録です。この記録をもとに、次のことを教えてください。")
        ln()
        ln("1. 記録から読み取れる気になる点（体重の増減、食べた量、うんち・おしっこの回数や状態、機嫌の変化など）と、その根拠になった日付")
        ln("2. 早めに動物病院を受診したほうがよいサイン")
        ln("3. 家でできる工夫や、次の受診で獣医さんに伝えるとよいこと")
        ln()
        ln("診断ではなく参考意見として、記録だけでは判断できないことは「わからない」と答えてください。")

        if (has(PdfSection.PROFILE)) {
            ln()
            ln("## プロフィール")
            ln()
            ln("- 種類：$species${if (pet.breed.isNotBlank()) "（${md(pet.breed)}）" else ""}")
            ln("- 性別：${pet.sex.label}（${if (pet.neutered) "避妊・去勢済み" else "未去勢"}）")
            ln("- 年齢：${ageText(pet, today)}（${if (pet.birthEstimated) "推定 " else ""}${pet.birthDate.slash()} 生まれ）")
            pet.adoptionDate?.let { ln("- お迎えした日：${it.slash()}") }
            ln("- 体重：${formatAmount(pet.weightKg)} kg" + (pet.targetWeightKg?.let { "（目標 ${formatAmount(it)} kg）" } ?: ""))
            ln("- 体形：BCS ${pet.bcs}/5（${BCS_TITLES.getOrElse(pet.bcs - 1) { "" }}、3 が理想）")
            ln("- 1日のカロリーの目安：${formatKcal(energy.targetKcal)}（${energy.stage.label}、${energy.factorLabel}" +
                (if (pet.customKcal != null && pet.customKcal > 0) "、手動で設定" else "") + "）")
            val plans = data.plans.filter { it.petId == pet.id }.mapNotNull { p -> data.food(p.foodId)?.let { p to it } }
            if (plans.isNotEmpty()) {
                ln("- いつものごはん：" + plans.joinToString("、") { (p, f) -> "${md(f.name)}（${cycleLabel(p.timesPerCycle, p.cycleDays)}）" })
            }
            if (pet.personality.isNotBlank()) ln("- 性格：${md(pet.personality)}")
            if (pet.physical.isNotBlank()) ln("- 体の特徴・持病など：${md(pet.physical)}")
            if (pet.notes.isNotBlank()) ln("- そのほか：${md(pet.notes)}")
        }

        if (has(PdfSection.WEIGHT)) {
            val list = data.weights.filter { it.petId == pet.id && inRange(it.date) }.sortedBy { it.date }
            if (list.isNotEmpty()) {
                ln()
                ln("## 体重の推移")
                ln()
                ln("| 日付 | 体重 (kg) | 前回との差 |")
                ln("|---|---|---|")
                list.forEachIndexed { i, w ->
                    val diff = if (i == 0) "" else signed(w.kg - list[i - 1].kg)
                    ln("| ${w.date.slash()} | ${formatAmount(w.kg)} | $diff |")
                }
            }
        }

        // 1日1行の表（ごはん・トイレ・機嫌）
        val food = has(PdfSection.FOOD)
        val toilet = has(PdfSection.TOILET)
        val mood = has(PdfSection.MOOD)
        if (food || toilet || mood) {
            val feeds = data.feedLogs.filter { it.petId == pet.id && inRange(it.time.date()) }.groupBy { it.time.date() }
            val toilets = data.excretionLogs.filter { it.petId == pet.id && inRange(it.time.date()) }.groupBy { it.time.date() }
            val moods = data.moods.filter { it.petId == pet.id && inRange(it.date) }.associateBy { it.date }
            val recorded = buildSet {
                if (food) addAll(feeds.keys)
                if (toilet) addAll(toilets.keys)
                if (mood) addAll(moods.keys)
            }
            ln()
            ln("## 毎日のまとめ")
            ln()
            if (recorded.isEmpty()) {
                ln("この期間の記録はありません。")
            } else {
                val head = buildList {
                    add("日付")
                    if (food) { add("食べた量 (kcal)"); add("目安との比") }
                    if (toilet) { add("うんち"); add("おしっこ") }
                    if (mood) add("機嫌")
                    if (toilet) add("気になること")
                }
                ln("| " + head.joinToString(" | ") + " |")
                ln("|" + head.joinToString("|") { "---" } + "|")
                var d = maxOf(from, recorded.min())
                val last = minOf(to, today).let { if (it.isBefore(recorded.max())) recorded.max() else it }
                while (!d.isAfter(last)) {
                    val cells = mutableListOf("${d.slash()}（${"月火水木金土日"[d.dayOfWeek.value - 1]}）")
                    if (d !in recorded) {
                        repeat(head.size - 1) { cells.add("") }
                    } else {
                        if (food) {
                            val l = feeds[d].orEmpty()
                            val kcal = l.sumOf { it.kcal }
                            cells.add(if (l.isEmpty()) "記録なし" else kcal.toInt().toString())
                            cells.add(if (l.isEmpty() || energy.targetKcal <= 0) "" else "${(kcal / energy.targetKcal * 100).toInt()}%")
                        }
                        val t = toilets[d].orEmpty()
                        if (toilet) {
                            for (type in ExcretionType.entries) {
                                val l = t.filter { it.type == type }
                                val conds = l.mapNotNull { it.condition }.distinct()
                                cells.add("${l.size}回" + if (conds.isNotEmpty()) "（${conds.joinToString("・") { md(it) }}）" else "")
                            }
                        }
                        if (mood) cells.add(moods[d]?.mood?.label ?: "")
                        if (toilet) {
                            cells.add(
                                t.filter { it.flags.isNotEmpty() || it.note.isNotBlank() }.sortedBy { it.time }.joinToString("、") { e ->
                                    "${e.type.label}：" + (e.flags + listOfNotNull(e.note.ifBlank { null })).joinToString("・") { md(it) }
                                },
                            )
                        }
                    }
                    ln("| " + cells.joinToString(" | ") + " |")
                    d = d.plusDays(1)
                }
                ln()
                ln("空欄の日は、記録がありません。" + if (food) "目安との比は、1日のカロリーの目安（${formatKcal(energy.targetKcal)}）に対する割合です。" else "")
            }
        }

        if (food) {
            val treats = data.feedLogs.filter { it.petId == pet.id && inRange(it.time.date()) && it.kind == FoodKind.TREAT }
            if (treats.isNotEmpty()) {
                val days = treats.map { it.time.date() }.distinct().size
                ln()
                ln("おやつ：期間中 ${treats.size}回（${days}日）、合計 ${formatKcal(treats.sumOf { it.kcal })}。1日のおやつの目安は ${formatKcal(energy.targetKcal * Nutrition.TREAT_LIMIT_RATIO)} まで。")
            }
        }

        if (has(PdfSection.RECORDS)) {
            val list = data.records.filter { it.petId == pet.id && inRange(it.time.date()) }.sortedBy { it.time }
            if (list.isNotEmpty()) {
                ln()
                ln("## そのほかの記録")
                ln()
                ln("| 日時 | 項目 | 内容 |")
                ln("|---|---|---|")
                for (r in list) {
                    val main = when (r.item.kind) {
                        RecordKind.NUMBER -> r.value?.let { formatAmount(it) + if (r.item.unit.isNotEmpty()) " ${r.item.unit}" else "" }
                        RecordKind.CHECK -> if (r.done) "済み" else "未"
                        RecordKind.TEXT -> null
                    }
                    ln("| ${r.time.date().slash()} ${r.time.hm()} | ${r.item.label} | ${listOfNotNull(main, r.text.ifBlank { null }?.let { md(it) }).joinToString(" ")} |")
                }
            }
        }

        if (has(PdfSection.SCHEDULE)) {
            val list = data.health.filter { it.petId == pet.id && inRange(it.date) }.sortedBy { it.startsAt() }
            if (list.isNotEmpty()) {
                ln()
                ln("## 通院・お薬・ワクチンなど")
                ln()
                for (h in list) {
                    ln("- ${h.date.slash()}${h.time?.let { " %d:%02d".format(it.hour, it.minute) } ?: ""} ${h.kind.label}：${md(h.title)}" +
                        (if (h.note.isNotBlank()) "（${md(h.note)}）" else "") + if (h.date.isAfter(today)) "【予定】" else "")
                }
            }
        }

        if (has(PdfSection.DIARY)) {
            val list = data.diaries.filter { it.petId == pet.id && inRange(it.date) }.sortedBy { it.date }
            if (list.isNotEmpty()) {
                ln()
                ln("## 日記")
                ln()
                for (dy in list) ln("- ${dy.date.slash()}：${md(dy.text)}")
            }
        }

        if (has(PdfSection.EXPENSE)) {
            val list = data.expenses.filter { (it.petId == null || it.petId == pet.id) && inRange(it.date) }
            if (list.isNotEmpty()) {
                ln()
                ln("## 支出")
                ln()
                ln("- ${pet.name}の分：${yen(list.filter { it.petId == pet.id }.sumOf { it.amount })}")
                val common = list.filter { it.petId == null }.sumOf { it.amount }
                if (common > 0) ln("- みんなの分：${yen(common)}")
                for ((c, l) in list.groupBy { it.category }.toList().sortedByDescending { (_, l) -> l.sumOf { it.amount } }) {
                    ln("- ${c.label}：${yen(l.sumOf { it.amount })}")
                }
            }
        }
        return sb.toString()
    }

    private val BCS_TITLES = listOf("痩せ", "やや痩せ", "理想的", "やや肥満", "肥満")

    /** 表が崩れないよう、縦線と改行を置きかえる */
    private fun md(s: String) = s.replace("|", "｜").replace(Regex("\\s*\\n\\s*"), " ").trim()

    private fun signed(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "±") + formatAmount(kotlin.math.abs(v))

    private fun yen(v: Long) = "¥" + NumberFormat.getNumberInstance(Locale.JAPAN).format(v)

    private fun cycleLabel(times: Int, days: Int) = if (days == 1) "毎日${times}回" else if (days == 7 && times == 1) "週1回" else "${days}日に${times}回"

    private fun LocalDate.slash() = "$year/$monthValue/$dayOfMonth"

    private fun ageText(pet: Pet, today: LocalDate): String {
        val m = Nutrition.ageInMonths(pet.birthDate, today)
        val t = if (m < 12) "${m}か月" else if (m % 12 == 0L) "${m / 12}歳" else "${m / 12}歳${m % 12}か月"
        return if (pet.birthEstimated) "約$t" else t
    }
}
