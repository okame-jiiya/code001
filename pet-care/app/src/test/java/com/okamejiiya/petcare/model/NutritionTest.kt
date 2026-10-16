package com.okamejiiya.petcare.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NutritionTest {
    private val today = LocalDate.of(2026, 10, 13)

    private fun pet(
        species: Species = Species.CAT,
        weight: Double = 4.0,
        ageYears: Long = 3,
        neutered: Boolean = true,
        bcs: Int = 3,
    ) = Pet(
        id = 1, name = "テスト", species = species, breed = "", sex = Sex.FEMALE,
        neutered = neutered, weightKg = weight, birthDate = today.minusYears(ageYears),
        birthEstimated = false, bcs = bcs,
    )

    @Test fun rerOf4kgIsAbout198() {
        assertEquals(197.99, Nutrition.rer(4.0), 0.01)
    }

    @Test fun neuteredAdultCatUses1_2() {
        val e = Nutrition.energy(pet(), today)
        assertEquals(1.2, e.factor, 0.0)
        assertEquals(237.6, e.targetKcal, 0.1)
    }

    @Test fun intactAdultDogUses1_8() {
        val e = Nutrition.energy(pet(species = Species.DOG, weight = 10.0, neutered = false), today)
        assertEquals(1.8, e.factor, 0.0)
    }

    @Test fun seniorDogUses1_4() {
        val e = Nutrition.energy(pet(species = Species.DOG, weight = 10.0, ageYears = 9), today)
        assertEquals(Nutrition.Stage.SENIOR, e.stage)
        assertEquals(1.4, e.factor, 0.0)
    }

    @Test fun kittenUsesGrowthFactor() {
        val p = pet().copy(birthDate = today.minusMonths(6))
        val e = Nutrition.energy(p, today)
        assertEquals(2.5, e.factor, 0.0)
        assertEquals(4.0, e.basisWeightKg, 0.0)
    }

    @Test fun overweightCatDietsOnIdealWeight() {
        val e = Nutrition.energy(pet(weight = 5.75, bcs = 5), today)
        assertEquals(0.8, e.factor, 0.0)
        assertEquals(5.75 / 1.3, e.basisWeightKg, 1e-9)
    }

    @Test fun customKcalOverridesTarget() {
        val e = Nutrition.energy(pet().copy(customKcal = 200), today)
        assertEquals(200.0, e.targetKcal, 0.0)
    }

    private val dry = Food(10, "カリカリ", FoodKind.MEAL, true, "g", 100.0, 380.0)
    private val pouch = Food(11, "パウチ", FoodKind.MEAL, false, "袋", 1.0, 70.0)
    private val treat = Food(12, "ちゅ〜る", FoodKind.TREAT, false, "本", 1.0, 7.0)

    @Test fun autoFoodFillsRemainingCalories() {
        val items = listOf(
            PlanItem(1, 1, pouch.id, auto = false, amount = 1.0, timesPerCycle = 1, cycleDays = 1) to pouch,
            PlanItem(2, 1, dry.id, auto = true, amount = 0.0, timesPerCycle = 2, cycleDays = 1) to dry,
        )
        val r = Nutrition.plan(240.0, items)
        // (240 - 70) / 2回 / 3.8 kcal/g = 22.4g → 22g
        assertEquals(22.0, r.lines[1].amountPerServing, 0.0)
        assertEquals(70.0 + 22 * 3.8 * 2, r.totalKcal, 1e-9)
        assertFalse(r.fixedExceedsTarget)
    }

    @Test fun cycleSpreadsCaloriesOverDays() {
        val items = listOf(PlanItem(1, 1, treat.id, false, 1.0, timesPerCycle = 1, cycleDays = 2) to treat)
        assertEquals(3.5, Nutrition.plan(240.0, items).totalKcal, 1e-9)
    }

    @Test fun treatOverTenPercentIsFlagged() {
        val items = listOf(PlanItem(1, 1, treat.id, false, 4.0, 1, 1) to treat)
        assertTrue(Nutrition.plan(240.0, items).treatOverLimit)
    }

    @Test fun fixedFoodsAboveTargetAreFlagged() {
        val items = listOf(
            PlanItem(1, 1, pouch.id, false, 4.0, 1, 1) to pouch,
            PlanItem(2, 1, dry.id, true, 0.0, 2, 1) to dry,
        )
        val r = Nutrition.plan(240.0, items)
        assertTrue(r.fixedExceedsTarget)
        assertEquals(0.0, r.lines[1].amountPerServing, 0.0)
    }

    @Test fun formatsAmounts() {
        assertEquals("22", formatAmount(22.0))
        assertEquals("0.5", formatAmount(0.5))
    }
}
