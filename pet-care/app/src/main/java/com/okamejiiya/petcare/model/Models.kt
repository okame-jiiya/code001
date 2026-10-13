package com.okamejiiya.petcare.model

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

enum class Species(val label: String, val emoji: String) {
    CAT("猫", "🐱"),
    DOG("犬", "🐶"),
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
)

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
)

data class AppData(
    val pets: List<Pet> = emptyList(),
    val foods: List<Food> = emptyList(),
    val plans: List<PlanItem> = emptyList(),
    val feedLogs: List<FeedLog> = emptyList(),
    val excretionLogs: List<ExcretionLog> = emptyList(),
    val selectedPetId: Long? = null,
) {
    val selectedPet: Pet? get() = pets.firstOrNull { it.id == selectedPetId } ?: pets.firstOrNull()
    fun food(id: Long): Food? = foods.firstOrNull { it.id == id }
}

object Ids {
    private val counter = AtomicLong(System.currentTimeMillis() * 1000)
    fun next(): Long = counter.incrementAndGet()
}
