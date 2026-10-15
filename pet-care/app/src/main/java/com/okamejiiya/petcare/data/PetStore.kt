package com.okamejiiya.petcare.data

import android.util.AtomicFile
import android.util.Log
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.FeedLog
import com.okamejiiya.petcare.model.Food
import com.okamejiiya.petcare.model.HealthRecord
import com.okamejiiya.petcare.model.Ids
import com.okamejiiya.petcare.model.Mood
import com.okamejiiya.petcare.model.MoodLog
import com.okamejiiya.petcare.model.Reminder
import com.okamejiiya.petcare.model.WeightLog
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.PlanItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** 通知の予定が変わったときに呼ばれる（アラームの再登録用） */
    @Volatile
    var onRemindersChanged: ((AppData) -> Unit)? = null

    /**
     * この端末で記録が変わったときに呼ばれる（家族共有でクラウドへ送る用）。
     * 送る順番が入れ替わらないよう、ロックの中で呼ぶ。
     */
    @Volatile
    var onLocalChange: ((prev: AppData, next: AppData) -> Unit)? = null

    fun update(transform: (AppData) -> AppData) = change(transform, fromRemote = false)

    /** 家族の端末から届いた変更を反映する（クラウドへは送り返さない） */
    fun applyRemote(transform: (AppData) -> AppData) = change(transform, fromRemote = true)

    private fun change(transform: (AppData) -> AppData, fromRemote: Boolean) {
        val (prev, next) = synchronized(this) {
            val p = _state.value
            val n = transform(p)
            if (n == p) return
            _state.value = n
            if (!fromRemote) onLocalChange?.invoke(p, n)
            p to n
        }
        writer.execute { save(next) }
        if (prev.reminders != next.reminders || prev.health != next.health || prev.pets != next.pets) onRemindersChanged?.invoke(next)
    }

    // ---- バックアップ ----

    fun exportJson(): String = Codec.encode(_state.value).toString(2)

    /** 読み込めない内容なら例外を投げ、今のデータは変えない */
    fun importJson(json: String) {
        val data = Codec.decode(JSONObject(json))
        update { data }
    }

    // ---- よく使う操作 ----

    /** プロフィールの体重が変わったら、今日の体重記録も残す */
    fun upsertPet(pet: Pet) = update { d ->
        val old = d.pets.firstOrNull { it.id == pet.id }
        val today = LocalDate.now()
        val weights = if (old == null || old.weightKg != pet.weightKg) {
            d.weights.filterNot { it.petId == pet.id && it.date == today } + WeightLog(Ids.next(), pet.id, today, pet.weightKg)
        } else d.weights
        d.copy(
            pets = if (old != null) d.pets.map { if (it.id == pet.id) pet else it } else d.pets + pet,
            weights = weights,
            selectedPetId = if (old != null) d.selectedPetId else pet.id,
        )
    }

    /** 体重を記録する。いちばん新しい記録ならプロフィールの体重も更新する */
    fun upsertWeight(log: WeightLog) = update { d ->
        val weights = d.weights.filterNot { it.id == log.id || (it.petId == log.petId && it.date == log.date) } + log
        d.copy(weights = weights, pets = syncWeight(d.pets, weights, log.petId))
    }

    fun deleteWeight(log: WeightLog) = update { d ->
        val weights = d.weights.filterNot { it.id == log.id }
        d.copy(weights = weights, pets = syncWeight(d.pets, weights, log.petId))
    }

    private fun syncWeight(pets: List<Pet>, weights: List<WeightLog>, petId: Long): List<Pet> {
        val latest = weights.filter { it.petId == petId }.maxByOrNull { it.date } ?: return pets
        return pets.map { if (it.id == petId) it.copy(weightKg = latest.kg) else it }
    }

    fun upsertReminder(r: Reminder) = update { d ->
        val exists = d.reminders.any { it.id == r.id }
        d.copy(reminders = if (exists) d.reminders.map { if (it.id == r.id) r else it } else d.reminders + r)
    }

    fun deleteReminder(id: Long) = update { d -> d.copy(reminders = d.reminders.filterNot { it.id == id }) }

    /** その日の機嫌を設定する。null なら消す */
    fun setMood(petId: Long, date: LocalDate, mood: Mood?) = update { d ->
        val rest = d.moods.filterNot { it.petId == petId && it.date == date }
        d.copy(moods = if (mood == null) rest else rest + MoodLog(petId, date, mood))
    }

    fun upsertHealth(r: HealthRecord) = update { d ->
        val exists = d.health.any { it.id == r.id }
        d.copy(health = if (exists) d.health.map { if (it.id == r.id) r else it } else d.health + r)
    }

    fun deleteHealth(id: Long) = update { d -> d.copy(health = d.health.filterNot { it.id == id }) }

    fun deletePet(id: Long) = update { d ->
        d.copy(
            pets = d.pets.filterNot { it.id == id },
            plans = d.plans.filterNot { it.petId == id },
            feedLogs = d.feedLogs.filterNot { it.petId == id },
            excretionLogs = d.excretionLogs.filterNot { it.petId == id },
            weights = d.weights.filterNot { it.petId == id },
            reminders = d.reminders.filterNot { it.petId == id },
            health = d.health.filterNot { it.petId == id },
            moods = d.moods.filterNot { it.petId == id },
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
