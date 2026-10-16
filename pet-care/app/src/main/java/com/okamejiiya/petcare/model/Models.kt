package com.okamejiiya.petcare.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class Species(val label: String, val emoji: String, val icons: List<String>) {
    CAT("猫", "🐱", listOf("🐱", "🐈", "🐈‍⬛", "😺", "😸")),
    DOG("犬", "🐶", listOf("🐶", "🐕", "🐩", "🦮", "🐕‍🦺")),
}

enum class Sex(val label: String) {
    MALE("オス"),
    FEMALE("メス"),
}

enum class FoodKind(val label: String) {
    MEAL("ごはん"),
    TREAT("おやつ"),
}

enum class ExcretionType(val label: String, val emoji: String) {
    POOP("うんち", "💩"),
    PEE("おしっこ", "💧"),
}

data class Pet(
    val id: Long,
    val name: String,
    val species: Species,
    val breed: String,
    val sex: Sex,
    val neutered: Boolean,
    val weightKg: Double,
    val birthDate: LocalDate,
    /** 年齢から逆算した生年月日のときは true（表示を「◯歳◯か月」にする） */
    val birthEstimated: Boolean,
    /** ボディコンディションスコア（5段階、3が理想） */
    val bcs: Int,
    /** 獣医さんに言われた目標体重など。null なら BCS から推定 */
    val targetWeightKg: Double? = null,
    /** 1日のカロリーを手動で決めたいとき。null なら自動計算 */
    val customKcal: Int? = null,
    /** アイコンの背景色（PetColors の番号）。多頭飼いで見分けやすくする */
    val colorIndex: Int = 0,
    /** アイコンの絵柄（Species.icons の番号） */
    val iconIndex: Int = 0,
    /** お迎えした日 */
    val adoptionDate: LocalDate? = null,
    /** 性格 */
    val personality: String = "",
    /** 体の特徴（模様・持病・アレルギーなど） */
    val physical: String = "",
    /** そのほかのメモ */
    val notes: String = "",
    /** 表示の順番（小さいほど先） */
    val order: Int = 0,
    /** トップ画像（正方形に切り抜いた写真の ID） */
    val photoId: String? = null,
    /** アイコンもトップ画像にする */
    /** 写真があるときに写真をアイコンにする（標準）。false なら絵文字アイコン */
    val photoAsIcon: Boolean = true,
    /** 診断書・血統書などの画像 */
    val documents: List<PetDocument> = emptyList(),
) {
    val icon: String get() = species.icons.getOrElse(iconIndex) { species.emoji }
}

/** プロフィールに載せる書類の画像（診断書・血統書など） */
data class PetDocument(val photoId: String, val title: String)

/**
 * 買ってきたフード・おやつ。
 * divisible = true は g 単位で量を調整できるもの（カリカリなど）、false はパウチ・缶など個数単位のもの。
 * 「baseAmount [unitLabel] あたり kcalPerBase kcal」で登録する（例: 100 g あたり 380 kcal、1 袋あたり 70 kcal）。
 */
data class Food(
    val id: Long,
    val name: String,
    val kind: FoodKind,
    val divisible: Boolean,
    val unitLabel: String,
    val baseAmount: Double,
    val kcalPerBase: Double,
) {
    val kcalPerUnit: Double get() = if (baseAmount > 0) kcalPerBase / baseAmount else 0.0
}

/**
 * ペットごとのごはんプラン。cycleDays 日に timesPerCycle 回あげる。
 * auto = true のときは 1 回量をアプリが目標カロリーから計算する（g 単位のフードのみ）。
 */
data class PlanItem(
    val id: Long,
    val petId: Long,
    val foodId: Long,
    val auto: Boolean,
    val amount: Double,
    val timesPerCycle: Int,
    val cycleDays: Int,
)

/** あげた記録。後でフードを編集・削除しても履歴が変わらないよう、名前とカロリーを控えておく */
data class FeedLog(
    val id: Long,
    val petId: Long,
    val foodId: Long,
    val foodName: String,
    val kind: FoodKind,
    val amount: Double,
    val unitLabel: String,
    val kcal: Double,
    val time: Long,
)

data class ExcretionLog(
    val id: Long,
    val petId: Long,
    val type: ExcretionType,
    val time: Long,
    /** うんちは硬さ、おしっこは量。未入力なら null */
    val condition: String? = null,
    /** 血が混じる などの気になる症状 */
    val flags: List<String> = emptyList(),
    val note: String = "",
    /** 獣医さんに見せる用の写真（圧縮した画像の ID） */
    val photoIds: List<String> = emptyList(),
) {
    val abnormal: Boolean get() = flags.isNotEmpty() || condition in ExcretionOptions.abnormalConditions
}

/**
 * 状態と「気になること」。動物病院でよく聞かれる項目（便の硬さのスコア、色・血・粘液・寄生虫、
 * 尿の色・にごり・回数・痛がる様子など）を、飼い主が見て分かる言葉にしたもの。
 */
object ExcretionOptions {
    val poopConditions = listOf("コロコロでかたい", "ふつう", "やわらかめ", "形がない（泥状）", "水っぽい（下痢）")
    val peeConditions = listOf("出ない", "少ない", "ふつう", "多い")
    val poopFlags = listOf(
        "赤い血が混じる",
        "黒っぽい（タールのよう）",
        "色がいつもと違う（白・灰・黄・緑など）",
        "粘液・ゼリー状のものがつく",
        "虫や米粒のようなものが混じる",
        "異物（毛・ひも・おもちゃなど）が混じる",
        "いきむのに出ない・出にくい",
        "においがいつもより強い",
        "トイレ以外でした",
    )
    val peeFlags = listOf(
        "赤い・ピンク色（血が混じる）",
        "色がいつもと違う（濃い・茶色・オレンジなど）",
        "にごっている・キラキラしたものがある",
        "何度も行くのに少ししか出ない",
        "出すときに痛がる・鳴く",
        "量がとても多い・水をよく飲む",
        "においがいつもと違う（甘い・強い）",
        "トイレ以外でした",
    )
    /** 以前の記録の言葉（「下痢」など）も気になる状態として扱う */
    val abnormalConditions = setOf("下痢", "形がない（泥状）", "水っぽい（下痢）", "出ない")

    fun conditions(type: ExcretionType) = if (type == ExcretionType.POOP) poopConditions else peeConditions
    fun flags(type: ExcretionType) = if (type == ExcretionType.POOP) poopFlags else peeFlags
}

enum class Mood(val label: String, val emoji: String) {
    GOOD("ごきげん", "☀️"),
    SO_SO("ふつう", "☁️"),
    BAD("いまいち", "☔"),
}

/** その日の機嫌（1日1つ） */
data class MoodLog(
    val petId: Long,
    val date: LocalDate,
    val mood: Mood,
)

data class WeightLog(
    val id: Long,
    val petId: Long,
    val date: LocalDate,
    val kg: Double,
    /** はかった時刻（入れなくてもよい） */
    val time: LocalTime? = null,
)

enum class ReminderKind(val label: String, val emoji: String) {
    MEAL("ごはん", "🍚"),
    TREAT("おやつ", "🍪"),
    MEDICINE("薬", "💊"),
    VISIT("通院", "🏥"),
    WALK("散歩", "🦮"),
    WEIGHT("体重", "⚖"),
    OTHER("その他", "🔔"),
}

enum class RepeatType(val label: String) {
    HOURS("○時間ごと"),
    DAILY("毎日"),
    DAYS("○日ごと"),
    WEEKLY("毎週"),
}

/**
 * 繰り返しの通知。1 つの項目（ごはん・薬など）に、繰り返しをいくつでも組み合わせて登録できる。
 * HOURS は startDate の hour:minute から interval 時間ごと、DAYS は interval 日ごと、
 * WEEKLY は weekdays（1=月 … 7=日）の hour:minute に通知する。
 */
data class Reminder(
    val id: Long,
    /** null なら全員分 */
    val petId: Long?,
    val kind: ReminderKind,
    val hour: Int,
    val minute: Int,
    val memo: String = "",
    val enabled: Boolean = true,
    val repeat: RepeatType = RepeatType.DAILY,
    val interval: Int = 1,
    val weekdays: Set<Int> = emptySet(),
    val startDate: LocalDate? = null,
) {
    /** now より後の次の通知時刻 */
    fun next(now: LocalDateTime): LocalDateTime {
        val start = (startDate ?: now.toLocalDate()).atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        if (start.isAfter(now) && repeat != RepeatType.WEEKLY) return start
        return when (repeat) {
            RepeatType.HOURS -> {
                val step = interval.coerceAtLeast(1).toLong() * 60
                val passed = java.time.Duration.between(start, now).toMinutes()
                start.plusMinutes((passed / step + 1) * step)
            }
            RepeatType.DAILY, RepeatType.DAYS -> {
                val step = if (repeat == RepeatType.DAILY) 1L else interval.coerceAtLeast(1).toLong()
                val days = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate())
                var t = start.plusDays((days / step) * step)
                while (!t.isAfter(now)) t = t.plusDays(step)
                t
            }
            RepeatType.WEEKLY -> {
                val days = weekdays.ifEmpty { setOf(start.dayOfWeek.value) }
                var t = maxOf(start.toLocalDate(), now.toLocalDate()).atTime(start.toLocalTime())
                while (!t.isAfter(now) || t.isBefore(start) || t.dayOfWeek.value !in days) t = t.plusDays(1)
                t
            }
        }
    }

    fun repeatText(): String {
        val at = "%d:%02d".format(hour, minute)
        return when (repeat) {
            RepeatType.HOURS -> "${at}から${interval}時間ごと"
            RepeatType.DAILY -> "毎日 $at"
            RepeatType.DAYS -> "${interval}日ごと $at"
            RepeatType.WEEKLY -> "毎週" + weekdays.sorted().joinToString("・") { "月火水木金土日"[it - 1].toString() } + " $at"
        }
    }
}

enum class HealthKind(val label: String, val emoji: String) {
    VISIT("通院", "🏥"),
    MEDICINE("お薬", "💊"),
    VACCINE("ワクチン", "💉"),
    TRIMMING("トリミング", "✂"),
    OTHER("その他", "📝"),
}

/**
 * 通院・お薬・ワクチン・トリミングなど。今日以降の日付なら「予定」、過去なら「記録」として表示する。
 * 予定は時刻と通知のタイミング（前日の◯時／当日の◯時間前）を選べる。
 */
data class HealthRecord(
    val id: Long,
    val petId: Long,
    val kind: HealthKind,
    val date: LocalDate,
    val title: String,
    val note: String = "",
    val time: LocalTime? = null,
    /** 前日のこの時刻（時）に通知。null なら通知しない */
    val notifyDayBeforeHour: Int? = null,
    /** 当日、予定時刻のこの時間前に通知。null なら通知しない */
    val notifyHoursBefore: Int? = null,
) {
    /** 時刻が決まっていない予定は、当日の通知を朝 9 時基準で計算する */
    fun startsAt(): LocalDateTime = date.atTime(time ?: LocalTime.of(9, 0))

    fun notifyTimes(): List<Pair<String, LocalDateTime>> = listOfNotNull(
        notifyDayBeforeHour?.let { "前日" to date.minusDays(1).atTime(it.coerceIn(0, 23), 0) },
        notifyHoursBefore?.let { "当日" to startsAt().minusHours(it.toLong()) },
    )
}

/** 記録の入力のしかた */
enum class RecordKind { NUMBER, TEXT, CHECK }

/**
 * 記録できる項目。ごはん・うんち・おしっこ・機嫌・体重は専用の記録（FeedLog など）を使い、
 * それ以外は RecordLog に「項目のキー＋数値・文字・チェック」で残す。
 */
enum class RecordItem(val label: String, val emoji: String, val kind: RecordKind, val unit: String = "", val special: Boolean = false) {
    WEIGHT("体重", "⚖", RecordKind.NUMBER, "kg", special = true),
    TEMPERATURE("体温", "🌡", RecordKind.NUMBER, "℃"),
    FOOD("ごはん", "🍚", RecordKind.NUMBER, special = true),
    WATER("水", "🚰", RecordKind.NUMBER, "ml"),
    POOP("うんち", "💩", RecordKind.TEXT, special = true),
    PEE("おしっこ", "💧", RecordKind.TEXT, special = true),
    MOOD("機嫌", "☀️", RecordKind.CHECK, special = true),
    WALK("散歩", "🦮", RecordKind.NUMBER, "分"),
    HEARTWORM("フィラリア予防", "🦟", RecordKind.CHECK),
    COMBO_VACCINE("混合ワクチン", "💉", RecordKind.TEXT),
    RABIES("狂犬病ワクチン", "🩹", RecordKind.CHECK),
    VISIT("通院記録", "🏥", RecordKind.TEXT),
    MEDICINE_DONE("薬（飲ませた）", "💊", RecordKind.CHECK),
    MEDICINE_NOTE("薬（内容）", "📋", RecordKind.TEXT),
    SICK("体調不良", "🤒", RecordKind.TEXT),
    BRUSHING("ブラッシング", "🪮", RecordKind.TEXT),
    TRIMMING("トリミング", "✂", RecordKind.TEXT),
    SHAMPOO("シャンプー", "🛁", RecordKind.TEXT),
    NAIL("爪切り", "💅", RecordKind.TEXT),
    ;

    val title: String get() = if (unit.isNotEmpty()) "$label（$unit）" else label

    companion object {
        val defaults: List<RecordItem> = listOf(WEIGHT, FOOD, POOP, PEE, MOOD, MEDICINE_DONE, VISIT)
        fun of(key: String): RecordItem? = entries.firstOrNull { it.name == key }
    }
}

/** ごはん・トイレ・機嫌・体重以外の記録（体温・散歩・ワクチンなど） */
data class RecordLog(
    val id: Long,
    val petId: Long,
    val item: RecordItem,
    val time: Long,
    val value: Double? = null,
    val text: String = "",
    val done: Boolean = true,
)

/** 日記（ペット × 日付で 1 つ） */
data class Diary(
    val petId: Long,
    val date: LocalDate,
    val text: String,
)

enum class ExpenseCategory(val label: String, val emoji: String) {
    FOOD("フード", "🍚"),
    TREAT("おやつ", "🍪"),
    MEDICAL("医療", "🏥"),
    TRIMMING("トリミング", "✂"),
    GOODS("用品", "🧸"),
    INSURANCE("保険", "🛡"),
    OTHER("その他", "💴"),
}

/** 支出。petId が null なら「みんなの分」 */
data class Expense(
    val id: Long,
    val petId: Long?,
    val date: LocalDate,
    val category: ExpenseCategory,
    val amount: Long,
    val memo: String = "",
)

/** 家族で共通の設定 */
data class SharedSettings(
    /** 記録する項目（表示の順番） */
    val items: List<RecordItem> = RecordItem.defaults,
    /** ごはんの時刻（カレンダーのワンタップ記録で選べる時刻） */
    val mealTimes: List<LocalTime> = DEFAULT_MEAL_TIMES,
    /** カレンダーのワンタップ記録を、最初から「いまに近いごはんの時刻」にする（false なら いまの時刻） */
    val quickAtMealTime: Boolean = false,
) {
    companion object {
        val DEFAULT_MEAL_TIMES: List<LocalTime> = listOf(LocalTime.of(7, 0), LocalTime.of(12, 0), LocalTime.of(18, 0), LocalTime.of(22, 0))
    }
}

/** 時刻から「朝・昼・夕・夜」 */
fun mealLabel(t: LocalTime): String = when (t.hour) {
    in 4..9 -> "朝"
    in 10..14 -> "昼"
    in 15..19 -> "夕"
    else -> "夜"
}

data class AppData(
    val pets: List<Pet> = emptyList(),
    val foods: List<Food> = emptyList(),
    val plans: List<PlanItem> = emptyList(),
    val feedLogs: List<FeedLog> = emptyList(),
    val excretionLogs: List<ExcretionLog> = emptyList(),
    val weights: List<WeightLog> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val health: List<HealthRecord> = emptyList(),
    val moods: List<MoodLog> = emptyList(),
    val records: List<RecordLog> = emptyList(),
    val diaries: List<Diary> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val settings: SharedSettings = SharedSettings(),
    val selectedPetId: Long? = null,
) {
    /** 並べ替えた順のペット */
    val sortedPets: List<Pet> get() = pets.sortedWith(compareBy<Pet> { it.order }.thenBy { it.id })
    val selectedPet: Pet? get() = pets.firstOrNull { it.id == selectedPetId } ?: sortedPets.firstOrNull()
    fun isOn(item: RecordItem): Boolean = item in settings.items
    fun food(id: Long): Food? = foods.firstOrNull { it.id == id }
}

/**
 * 記録の ID。「ミリ秒 × 1000 + 乱数」から始めて 1 ずつ増やす。
 * 家族の端末どうしで ID がぶつからないよう乱数を混ぜる（Web 版の JavaScript でも扱える 2^53 未満に収まる）。
 */
object Ids {
    private val counter = AtomicLong(System.currentTimeMillis() * 1000 + Random.nextInt(1000))
    fun next(): Long = counter.addAndGet(1L + Random.nextInt(8))
}
