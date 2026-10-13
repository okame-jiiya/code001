package com.okamejiiya.petcare.data

import android.util.AtomicFile
import android.util.Log
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.FeedLog
import com.okamejiiya.petcare.model.Food
import com.okamejiiya.petcare.model.FoodKind
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.PlanItem
import com.okamejiiya.petcare.model.Sex
import com.okamejiiya.petcare.model.Species
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * 端末内の JSON ファイル 1 つに全データを保存する。アカウント登録もサーバーも不要。
 * 変更はメモリ上の状態にすぐ反映し、ファイルへの書き込みはバックグラウンドで順番に行う。
 */
class PetStore(file: File) {
    private val atomicFile = AtomicFile(file)
    private val writer = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppData> = _state

    fun update(transform: (AppData) -> AppData) {
        val next = synchronized(this) {
            val n = transform(_state.value)
            _state.value = n
            n
        }
        writer.execute { save(next) }
    }

    // ---- よく使う操作 ----

    fun upsertPet(pet: Pet) = update { d ->
        val exists = d.pets.any { it.id == pet.id }
        d.copy(
            pets = if (exists) d.pets.map { if (it.id == pet.id) pet else it } else d.pets + pet,
            selectedPetId = if (exists) d.selectedPetId else pet.id,
        )
    }

    fun deletePet(id: Long) = update { d ->
        d.copy(
            pets = d.pets.filterNot { it.id == id },
            plans = d.plans.filterNot { it.petId == id },
            feedLogs = d.feedLogs.filterNot { it.petId == id },
            excretionLogs = d.excretionLogs.filterNot { it.petId == id },
            selectedPetId = if (d.selectedPetId == id) null else d.selectedPetId,
        )
    }

    fun selectPet(id: Long) = update { it.copy(selectedPetId = id) }

    fun upsertFood(food: Food) = update { d ->
        val exists = d.foods.any { it.id == food.id }
        d.copy(foods = if (exists) d.foods.map { if (it.id == food.id) food else it } else d.foods + food)
    }

    fun deleteFood(id: Long) = update { d ->
        d.copy(foods = d.foods.filterNot { it.id == id }, plans = d.plans.filterNot { it.foodId == id })
    }

    fun upsertPlan(item: PlanItem) = update { d ->
        val exists = d.plans.any { it.id == item.id }
        d.copy(plans = if (exists) d.plans.map { if (it.id == item.id) item else it } else d.plans + item)
    }

    fun deletePlan(id: Long) = update { d -> d.copy(plans = d.plans.filterNot { it.id == id }) }

    fun addFeedLog(log: FeedLog) = update { d -> d.copy(feedLogs = d.feedLogs + log) }

    fun updateFeedLog(log: FeedLog) = update { d ->
        d.copy(feedLogs = d.feedLogs.map { if (it.id == log.id) log else it })
    }

    fun deleteFeedLog(id: Long) = update { d -> d.copy(feedLogs = d.feedLogs.filterNot { it.id == id }) }

    fun addExcretion(log: ExcretionLog) = update { d -> d.copy(excretionLogs = d.excretionLogs + log) }

    fun updateExcretion(log: ExcretionLog) = update { d ->
        d.copy(excretionLogs = d.excretionLogs.map { if (it.id == log.id) log else it })
    }

    fun deleteExcretion(id: Long) = update { d ->
        d.copy(excretionLogs = d.excretionLogs.filterNot { it.id == id })
    }

    // ---- 保存と読み込み ----

    private fun load(): AppData = try {
        if (!atomicFile.baseFile.exists()) AppData()
        else Codec.decode(JSONObject(String(atomicFile.readFully(), Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.e(TAG, "データの読み込みに失敗しました", e)
        AppData()
    }

    private fun save(data: AppData) {
        val out = try {
            atomicFile.startWrite()
        } catch (e: Exception) {
            Log.e(TAG, "保存先を開けませんでした", e)
            return
        }
        try {
            out.write(Codec.encode(data).toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(out)
        } catch (e: Exception) {
            atomicFile.failWrite(out)
            Log.e(TAG, "データの保存に失敗しました", e)
        }
    }

    private companion object {
        const val TAG = "PetStore"
    }
}

private object Codec {
    private const val VERSION = 1

    fun encode(d: AppData): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("selectedPetId", d.selectedPetId ?: JSONObject.NULL)
        .put("pets", JSONArray(d.pets.map { pet(it) }))
        .put("foods", JSONArray(d.foods.map { food(it) }))
        .put("plans", JSONArray(d.plans.map { plan(it) }))
        .put("feedLogs", JSONArray(d.feedLogs.map { feed(it) }))
        .put("excretionLogs", JSONArray(d.excretionLogs.map { excretion(it) }))

    fun decode(o: JSONObject) = AppData(
        pets = o.list("pets") { pet(it) },
        foods = o.list("foods") { food(it) },
        plans = o.list("plans") { plan(it) },
        feedLogs = o.list("feedLogs") { feed(it) },
        excretionLogs = o.list("excretionLogs") { excretion(it) },
        selectedPetId = if (o.isNull("selectedPetId")) null else o.optLong("selectedPetId"),
    )

    private fun <T> JSONObject.list(key: String, read: (JSONObject) -> T): List<T> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { read(arr.getJSONObject(it)) }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)
    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

    private fun pet(p: Pet) = JSONObject()
        .put("id", p.id).put("name", p.name).put("species", p.species.name).put("breed", p.breed)
        .put("sex", p.sex.name).put("neutered", p.neutered).put("weightKg", p.weightKg)
        .put("birthDate", p.birthDate.toString()).put("birthEstimated", p.birthEstimated).put("bcs", p.bcs)
        .put("targetWeightKg", p.targetWeightKg ?: JSONObject.NULL)
        .put("customKcal", p.customKcal ?: JSONObject.NULL)

    private fun pet(o: JSONObject) = Pet(
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
    )

    private fun food(f: Food) = JSONObject()
        .put("id", f.id).put("name", f.name).put("kind", f.kind.name).put("divisible", f.divisible)
        .put("unitLabel", f.unitLabel).put("baseAmount", f.baseAmount).put("kcalPerBase", f.kcalPerBase)

    private fun food(o: JSONObject) = Food(
        id = o.getLong("id"),
        name = o.optString("name"),
        kind = FoodKind.valueOf(o.optString("kind", FoodKind.MEAL.name)),
        divisible = o.optBoolean("divisible"),
        unitLabel = o.optString("unitLabel"),
        baseAmount = o.optDouble("baseAmount", 1.0),
        kcalPerBase = o.optDouble("kcalPerBase", 0.0),
    )

    private fun plan(p: PlanItem) = JSONObject()
        .put("id", p.id).put("petId", p.petId).put("foodId", p.foodId).put("auto", p.auto)
        .put("amount", p.amount).put("timesPerCycle", p.timesPerCycle).put("cycleDays", p.cycleDays)

    private fun plan(o: JSONObject) = PlanItem(
        id = o.getLong("id"),
        petId = o.getLong("petId"),
        foodId = o.getLong("foodId"),
        auto = o.optBoolean("auto"),
        amount = o.optDouble("amount", 0.0),
        timesPerCycle = o.optInt("timesPerCycle", 1),
        cycleDays = o.optInt("cycleDays", 1),
    )

    private fun feed(l: FeedLog) = JSONObject()
        .put("id", l.id).put("petId", l.petId).put("foodId", l.foodId).put("foodName", l.foodName)
        .put("kind", l.kind.name).put("amount", l.amount).put("unitLabel", l.unitLabel)
        .put("kcal", l.kcal).put("time", l.time)

    private fun feed(o: JSONObject) = FeedLog(
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

    private fun excretion(l: ExcretionLog) = JSONObject()
        .put("id", l.id).put("petId", l.petId).put("type", l.type.name).put("time", l.time)

    private fun excretion(o: JSONObject) = ExcretionLog(
        id = o.getLong("id"),
        petId = o.getLong("petId"),
        type = ExcretionType.valueOf(o.getString("type")),
        time = o.getLong("time"),
    )
}
