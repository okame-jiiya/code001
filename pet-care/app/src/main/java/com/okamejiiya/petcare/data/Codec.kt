package com.okamejiiya.petcare.data

import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Diary
import com.okamejiiya.petcare.model.Expense
import com.okamejiiya.petcare.model.ExpenseCategory
import com.okamejiiya.petcare.model.PetDocument
import com.okamejiiya.petcare.model.RecordItem
import com.okamejiiya.petcare.model.RecordLog
import com.okamejiiya.petcare.model.RepeatType
import com.okamejiiya.petcare.model.SharedSettings
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.FeedLog
import com.okamejiiya.petcare.model.Food
import com.okamejiiya.petcare.model.FoodKind
import com.okamejiiya.petcare.model.HealthKind
import com.okamejiiya.petcare.model.HealthRecord
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Mood
import com.okamejiiya.petcare.model.MoodLog
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.PlanItem
import com.okamejiiya.petcare.model.Reminder
import com.okamejiiya.petcare.model.ReminderKind
import com.okamejiiya.petcare.model.Sex
import com.okamejiiya.petcare.model.Species
import com.okamejiiya.petcare.model.WeightLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** アプリのデータと JSON の変換（端末内の保存・バックアップ・家族共有で共通） */
internal object Codec {
    private const val VERSION = 1

    fun encode(d: AppData): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("selectedPetId", d.selectedPetId ?: JSONObject.NULL)
        .put("pets", JSONArray(d.pets.map { pet(it) }))
        .put("foods", JSONArray(d.foods.map { food(it) }))
        .put("plans", JSONArray(d.plans.map { plan(it) }))
        .put("feedLogs", JSONArray(d.feedLogs.map { feed(it) }))
        .put("excretionLogs", JSONArray(d.excretionLogs.map { excretion(it) }))
        .put("weights", JSONArray(d.weights.map { weight(it) }))
        .put("reminders", JSONArray(d.reminders.map { reminder(it) }))
        .put("health", JSONArray(d.health.map { health(it) }))
        .put("moods", JSONArray(d.moods.map { mood(it) }))
        .put("records", JSONArray(d.records.map { record(it) }))
        .put("diaries", JSONArray(d.diaries.map { diary(it) }))
        .put("expenses", JSONArray(d.expenses.map { expense(it) }))
        .put("settings", settings(d.settings))

    fun decode(o: JSONObject) = AppData(
        pets = o.list("pets") { pet(it) },
        foods = o.list("foods") { food(it) },
        plans = o.list("plans") { plan(it) },
        feedLogs = o.list("feedLogs") { feed(it) },
        excretionLogs = o.list("excretionLogs") { excretion(it) },
        weights = o.list("weights") { weight(it) },
        reminders = o.list("reminders") { reminder(it) },
        health = o.list("health") { health(it) }.flatten(),
        moods = o.list("moods") { mood(it) },
        records = o.list("records") { record(it) }.filterNotNull(),
        diaries = o.list("diaries") { diary(it) },
        expenses = o.list("expenses") { expense(it) },
        settings = o.optJSONObject("settings")?.let { settings(it) } ?: SharedSettings(),
        selectedPetId = if (o.isNull("selectedPetId")) null else o.optLong("selectedPetId"),
    )

    private fun <T> JSONObject.list(key: String, read: (JSONObject) -> T): List<T> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { read(arr.getJSONObject(it)) }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)
    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
    private fun JSONObject.optDate(key: String): LocalDate? = optStringOrNull(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun JSONObject.strings(key: String): List<String> =
        optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    fun pet(p: Pet) = JSONObject()
        .put("id", p.id).put("name", p.name).put("species", p.species.name).put("breed", p.breed)
        .put("sex", p.sex.name).put("neutered", p.neutered).put("weightKg", p.weightKg)
        .put("birthDate", p.birthDate.toString()).put("birthEstimated", p.birthEstimated).put("bcs", p.bcs)
        .put("targetWeightKg", p.targetWeightKg ?: JSONObject.NULL)
        .put("customKcal", p.customKcal ?: JSONObject.NULL)
        .put("colorIndex", p.colorIndex).put("iconIndex", p.iconIndex)
        .put("adoptionDate", p.adoptionDate?.toString() ?: JSONObject.NULL)
        .put("personality", p.personality).put("physical", p.physical).put("notes", p.notes)
        .put("order", p.order).put("photoId", p.photoId ?: JSONObject.NULL).put("photoAsIcon", p.photoAsIcon)
        .put("documents", JSONArray(p.documents.map { JSONObject().put("photoId", it.photoId).put("title", it.title) }))

    fun pet(o: JSONObject) = Pet(
        id = o.getLong("id"),
        name = o.optString("name"),
        species = Species.valueOf(o.optString("species", Species.CAT.name)),
        breed = o.optString("breed"),
        sex = Sex.valueOf(o.optString("sex", Sex.FEMALE.name)),
        neutered = o.optBoolean("neutered"),
        weightKg = o.optDouble("weightKg", 0.0),
        birthDate = LocalDate.parse(o.getString("birthDate")),
        birthEstimated = o.optBoolean("birthEstimated"),
        bcs = o.optInt("bcs", 3),
        targetWeightKg = o.optDoubleOrNull("targetWeightKg"),
        customKcal = o.optIntOrNull("customKcal"),
        colorIndex = o.optInt("colorIndex", 0),
        iconIndex = o.optInt("iconIndex", 0),
        adoptionDate = o.optDate("adoptionDate"),
        personality = o.optString("personality"),
        physical = o.optString("physical"),
        notes = o.optString("notes"),
        order = o.optInt("order", 0),
        photoId = o.optStringOrNull("photoId"),
        photoAsIcon = o.optBoolean("photoAsIcon"),
        documents = o.optJSONArray("documents")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { PetDocument(it.getString("photoId"), it.optString("title")) }
        } ?: emptyList(),
    )

    fun food(f: Food) = JSONObject()
        .put("id", f.id).put("name", f.name).put("kind", f.kind.name).put("divisible", f.divisible)
        .put("unitLabel", f.unitLabel).put("baseAmount", f.baseAmount).put("kcalPerBase", f.kcalPerBase)

    fun food(o: JSONObject) = Food(
        id = o.getLong("id"),
        name = o.optString("name"),
        kind = FoodKind.valueOf(o.optString("kind", FoodKind.MEAL.name)),
        divisible = o.optBoolean("divisible"),
        unitLabel = o.optString("unitLabel"),
        baseAmount = o.optDouble("baseAmount", 1.0),
        kcalPerBase = o.optDouble("kcalPerBase", 0.0),
    )

    fun plan(p: PlanItem) = JSONObject()
        .put("id", p.id).put("petId", p.petId).put("foodId", p.foodId).put("auto", p.auto)
        .put("amount", p.amount).put("timesPerCycle", p.timesPerCycle).put("cycleDays", p.cycleDays)

    fun plan(o: JSONObject) = PlanItem(
        id = o.getLong("id"),
        petId = o.getLong("petId"),
        foodId = o.getLong("foodId"),
        auto = o.optBoolean("auto"),
        amount = o.optDouble("amount", 0.0),
        timesPerCycle = o.optInt("timesPerCycle", 1),
        cycleDays = o.optInt("cycleDays", 1),
    )

    fun feed(l: FeedLog) = JSONObject()
        .put("id", l.id).put("petId", l.petId).put("foodId", l.foodId).put("foodName", l.foodName)
        .put("kind", l.kind.name).put("amount", l.amount).put("unitLabel", l.unitLabel)
        .put("kcal", l.kcal).put("time", l.time)

    fun feed(o: JSONObject) = FeedLog(
        id = o.getLong("id"),
        petId = o.getLong("petId"),
        foodId = o.optLong("foodId"),
        foodName = o.optString("foodName"),
        kind = FoodKind.valueOf(o.optString("kind", FoodKind.MEAL.name)),
        amount = o.optDouble("amount", 0.0),
        unitLabel = o.optString("unitLabel"),
        kcal = o.optDouble("kcal", 0.0),
        time = o.getLong("time"),
    )

    fun excretion(l: ExcretionLog) = JSONObject()
        .put("id", l.id).put("petId", l.petId).put("type", l.type.name).put("time", l.time)
        .put("condition", l.condition ?: JSONObject.NULL).put("flags", JSONArray(l.flags)).put("note", l.note)
        .put("photoIds", JSONArray(l.photoIds))

    fun excretion(o: JSONObject) = ExcretionLog(
        id = o.getLong("id"),
        petId = o.getLong("petId"),
        type = ExcretionType.valueOf(o.getString("type")),
        time = o.getLong("time"),
        condition = if (o.isNull("condition")) null else o.optString("condition"),
        flags = o.strings("flags"),
        note = o.optString("note"),
        photoIds = o.strings("photoIds"),
    )

    fun mood(m: MoodLog) = JSONObject()
        .put("petId", m.petId).put("date", m.date.toString()).put("mood", m.mood.name)

    fun mood(o: JSONObject) = MoodLog(
        petId = o.getLong("petId"),
        date = LocalDate.parse(o.getString("date")),
        mood = Mood.valueOf(o.getString("mood")),
    )

    fun weight(w: WeightLog) = JSONObject()
        .put("id", w.id).put("petId", w.petId).put("date", w.date.toString()).put("kg", w.kg)
        .put("time", w.time?.toString() ?: JSONObject.NULL)

    fun weight(o: JSONObject) = WeightLog(
        id = o.getLong("id"),
        petId = o.getLong("petId"),
        date = LocalDate.parse(o.getString("date")),
        kg = o.getDouble("kg"),
        time = o.optStringOrNull("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    )

    fun reminder(r: Reminder) = JSONObject()
        .put("id", r.id).put("petId", r.petId ?: JSONObject.NULL).put("kind", r.kind.name).put("hour", r.hour)
        .put("minute", r.minute).put("memo", r.memo).put("enabled", r.enabled)
        .put("repeat", r.repeat.name).put("interval", r.interval).put("weekdays", JSONArray(r.weekdays.sorted()))
        .put("startDate", r.startDate?.toString() ?: JSONObject.NULL)

    fun reminder(o: JSONObject) = Reminder(
        id = o.getLong("id"),
        petId = o.optLongOrNull("petId"),
        kind = enumOr(o.optString("kind"), ReminderKind.OTHER),
        hour = o.optInt("hour", 8),
        minute = o.optInt("minute", 0),
        memo = o.optString("memo"),
        enabled = o.optBoolean("enabled", true),
        repeat = enumOr(o.optString("repeat"), RepeatType.DAILY),
        interval = o.optInt("interval", 1).coerceAtLeast(1),
        weekdays = o.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { a.getInt(it) }.filter { it in 1..7 }.toSet() } ?: emptySet(),
        startDate = o.optDate("startDate"),
    )

    /** 知らない項目（新しいバージョンで増えたもの）は読み飛ばす */
    fun record(r: RecordLog) = JSONObject()
        .put("id", r.id).put("petId", r.petId).put("item", r.item.name).put("time", r.time)
        .put("value", r.value ?: JSONObject.NULL).put("text", r.text).put("done", r.done)

    fun record(o: JSONObject): RecordLog? {
        val item = RecordItem.of(o.optString("item")) ?: return null
        return RecordLog(
            id = o.getLong("id"),
            petId = o.getLong("petId"),
            item = item,
            time = o.getLong("time"),
            value = o.optDoubleOrNull("value"),
            text = o.optString("text"),
            done = o.optBoolean("done", true),
        )
    }

    fun diary(d: Diary) = JSONObject().put("petId", d.petId).put("date", d.date.toString()).put("text", d.text)

    fun diary(o: JSONObject) = Diary(o.getLong("petId"), LocalDate.parse(o.getString("date")), o.optString("text"))

    fun expense(e: Expense) = JSONObject()
        .put("id", e.id).put("petId", e.petId ?: JSONObject.NULL).put("date", e.date.toString())
        .put("category", e.category.name).put("amount", e.amount).put("memo", e.memo)

    fun expense(o: JSONObject) = Expense(
        id = o.getLong("id"),
        petId = o.optLongOrNull("petId"),
        date = LocalDate.parse(o.getString("date")),
        category = enumOr(o.optString("category"), ExpenseCategory.OTHER),
        amount = o.optLong("amount", 0),
        memo = o.optString("memo"),
    )

    fun settings(s: SharedSettings) = JSONObject().put("items", JSONArray(s.items.map { it.name }))

    fun settings(o: JSONObject) = SharedSettings(
        items = if (o.has("items")) o.strings("items").mapNotNull { RecordItem.of(it) }.distinct() else RecordItem.defaults,
    )

    fun health(r: HealthRecord) = JSONObject()
        .put("id", r.id).put("petId", r.petId).put("kind", r.kind.name).put("date", r.date.toString())
        .put("title", r.title).put("note", r.note).put("time", r.time?.toString() ?: JSONObject.NULL)
        .put("notifyDayBeforeHour", r.notifyDayBeforeHour ?: JSONObject.NULL)
        .put("notifyHoursBefore", r.notifyHoursBefore ?: JSONObject.NULL)

    /** 旧形式の「次回の予定日」は、別の予定として取り出す */
    fun health(o: JSONObject): List<HealthRecord> {
        val r = HealthRecord(
            id = o.getLong("id"),
            petId = o.getLong("petId"),
            kind = enumOr(o.optString("kind"), HealthKind.OTHER),
            date = LocalDate.parse(o.getString("date")),
            title = o.optString("title"),
            note = o.optString("note"),
            time = if (o.isNull("time")) null else LocalTime.parse(o.getString("time")),
            notifyDayBeforeHour = o.optIntOrNull("notifyDayBeforeHour"),
            notifyHoursBefore = o.optIntOrNull("notifyHoursBefore"),
        )
        val next = if (o.isNull("nextDate")) null else LocalDate.parse(o.getString("nextDate"))
        return listOfNotNull(r, next?.let { r.copy(id = Ids.next(), date = it, note = "") })
    }
}
