package com.okamejiiya.petcare.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 1日に必要なカロリーの計算。
 *
 * RER（安静時エネルギー要求量） = 70 × 体重(kg)^0.75
 * MER（1日のエネルギー要求量）  = RER × 係数
 *
 * 係数（一般的な獣医栄養学の目安）
 *   犬: 生後4か月未満 3.0 / 4〜12か月 2.0 / 避妊・去勢済み成犬 1.6 / 未去勢の成犬 1.8 / 7歳以上 1.4 / 減量 1.0
 *   猫: 生後4か月未満 3.0 / 4〜12か月 2.5 / 避妊・去勢済み成猫 1.2 / 未去勢の成猫 1.4 / 減量 0.8
 *
 * BCS（5段階）が 4 以上の成年・シニアは「減量」係数を理想体重に掛ける。
 * BCS が 3 以外のときの理想体重は、BCS が 1 段階ずれるごとに体重が約15%ずれるとみなして推定する。
 */
object Nutrition {

    const val TREAT_LIMIT_RATIO = 0.10

    enum class Stage(val label: String) {
        BABY_EARLY("子ども（生後4か月未満）"),
        BABY("子ども（生後4〜12か月）"),
        ADULT("成年"),
        SENIOR("シニア"),
    }

    data class Energy(
        val stage: Stage,
        val basisWeightKg: Double,
        val rer: Double,
        val factor: Double,
        val factorLabel: String,
        val calculatedKcal: Double,
        /** 手動設定があればそちら、なければ calculatedKcal */
        val targetKcal: Double,
    )

    fun rer(weightKg: Double): Double = if (weightKg <= 0) 0.0 else 70.0 * weightKg.pow(0.75)

    fun ageInMonths(birthDate: LocalDate, today: LocalDate): Long =
        ChronoUnit.MONTHS.between(birthDate, today).coerceAtLeast(0)

    fun stage(species: Species, months: Long): Stage = when {
        months < 4 -> Stage.BABY_EARLY
        months < 12 -> Stage.BABY
        species == Species.DOG && months >= 7 * 12 -> Stage.SENIOR
        species == Species.CAT && months >= 11 * 12 -> Stage.SENIOR
        else -> Stage.ADULT
    }

    fun estimatedIdealWeight(weightKg: Double, bcs: Int): Double =
        weightKg / (1 + 0.15 * (bcs.coerceIn(1, 5) - 3))

    fun energy(pet: Pet, today: LocalDate): Energy {
        val stage = stage(pet.species, ageInMonths(pet.birthDate, today))
        val growing = stage == Stage.BABY_EARLY || stage == Stage.BABY
        val basis = when {
            growing -> pet.weightKg
            pet.targetWeightKg != null && pet.targetWeightKg > 0 -> pet.targetWeightKg
            else -> estimatedIdealWeight(pet.weightKg, pet.bcs)
        }
        val (factor, label) = factor(pet, stage)
        val calculated = rer(basis) * factor
        val target = pet.customKcal?.takeIf { it > 0 }?.toDouble() ?: calculated
        return Energy(stage, basis, rer(basis), factor, label, calculated, target)
    }

    private fun factor(pet: Pet, stage: Stage): Pair<Double, String> {
        val dog = pet.species == Species.DOG
        return when {
            stage == Stage.BABY_EARLY -> 3.0 to "成長期（生後4か月未満）"
            stage == Stage.BABY -> (if (dog) 2.0 else 2.5) to "成長期（生後4〜12か月）"
            pet.bcs >= 4 -> (if (dog) 1.0 else 0.8) to "減量中（BCS ${pet.bcs}）"
            dog && stage == Stage.SENIOR -> 1.4 to "シニア犬（7歳以上）"
            pet.neutered -> (if (dog) 1.6 else 1.2) to "避妊・去勢済みの成${pet.species.label}"
            else -> (if (dog) 1.8 else 1.4) to "未去勢の成${pet.species.label}"
        }
    }

    // ---- ごはんプラン ----

    data class PlanLine(
        val item: PlanItem,
        val food: Food,
        /** 1回あたりの量（auto のときは計算結果） */
        val amountPerServing: Double,
        val dailyKcal: Double,
    )

    data class PlanResult(
        val lines: List<PlanLine>,
        val targetKcal: Double,
        val totalKcal: Double,
        val treatKcal: Double,
        /** 量が決まっているフードだけで目標を超えている */
        val fixedExceedsTarget: Boolean,
    ) {
        val treatOverLimit: Boolean get() = targetKcal > 0 && treatKcal > targetKcal * TREAT_LIMIT_RATIO
    }

    fun servingsPerDay(item: PlanItem): Double =
        if (item.cycleDays <= 0) 0.0 else item.timesPerCycle.toDouble() / item.cycleDays

    /**
     * 量が決まっているもの（パウチなどの個数単位、または手入力した g 数）を先に足し、
     * 目標カロリーの残りを「自動」のフードで均等に分けて 1 回あたりの g 数を出す。
     */
    fun plan(targetKcal: Double, items: List<Pair<PlanItem, Food>>): PlanResult {
        val isAuto = { p: Pair<PlanItem, Food> -> p.first.auto && p.second.divisible }
        val fixed = items.filterNot(isAuto)
        val autos = items.filter(isAuto)

        val fixedLines = fixed.map { (item, food) ->
            PlanLine(item, food, item.amount, item.amount * food.kcalPerUnit * servingsPerDay(item))
        }
        val fixedKcal = fixedLines.sumOf { it.dailyKcal }
        val remaining = (targetKcal - fixedKcal).coerceAtLeast(0.0)
        val share = if (autos.isEmpty()) 0.0 else remaining / autos.size

        val autoLines = autos.map { (item, food) ->
            val perDay = servingsPerDay(item)
            val raw = if (perDay > 0 && food.kcalPerUnit > 0) share / perDay / food.kcalPerUnit else 0.0
            val amount = roundAmount(raw)
            PlanLine(item, food, amount, amount * food.kcalPerUnit * perDay)
        }
        val byId = (fixedLines + autoLines).associateBy { it.item.id }
        val lines = items.mapNotNull { byId[it.first.id] }
        return PlanResult(
            lines = lines,
            targetKcal = targetKcal,
            totalKcal = lines.sumOf { it.dailyKcal },
            treatKcal = lines.filter { it.food.kind == FoodKind.TREAT }.sumOf { it.dailyKcal },
            fixedExceedsTarget = autos.isNotEmpty() && fixedKcal >= targetKcal,
        )
    }

    /** 10g 未満は 0.5g 刻み、それ以上は 1g 刻み */
    fun roundAmount(amount: Double): Double =
        if (amount < 10) (amount * 2).roundToInt() / 2.0 else amount.roundToInt().toDouble()
}

fun formatAmount(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

fun formatKcal(value: Double): String = "${value.roundToInt()} kcal"

/** 「35 g」「1 袋」 */
fun formatQuantity(amount: Double, unitLabel: String): String = "${formatAmount(amount)} $unitLabel"
