package com.okamejiiya.petcare.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AiReportTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 15)
    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).toInstant(zone).toEpochMilli()

    private val pet = Pet(
        id = 1, name = "もち", species = Species.CAT, breed = "ミックス", sex = Sex.FEMALE,
        neutered = true, weightKg = 4.2, birthDate = LocalDate.of(2020, 5, 1), birthEstimated = false, bcs = 3,
        physical = "腎臓の数値が\nやや高め",
    )
    private val data = AppData(
        pets = listOf(pet),
        weights = listOf(WeightLog(1, 1, today.minusDays(10), 4.4), WeightLog(2, 1, today, 4.2)),
        feedLogs = listOf(FeedLog(3, 1, 9, "カリカリ", FoodKind.MEAL, 50.0, "g", 190.0, at(today.minusDays(2), 7))),
        excretionLogs = listOf(
            ExcretionLog(4, 1, ExcretionType.PEE, at(today.minusDays(1), 9), condition = "少ない", flags = listOf("赤い・ピンク色（血が混じる）")),
        ),
        moods = listOf(MoodLog(1, today.minusDays(1), Mood.BAD)),
    )

    @Test
    fun containsPromptProfileAndDailyTable() {
        val text = AiReport.build(data, pet, today.minusDays(30), today, PdfSection.entries.filter { it.default }.toSet(), today, zone)
        assertTrue(text.startsWith("# もち（猫）の健康記録 2026/9/15〜2026/10/15"))
        assertTrue(text.contains("## 相談したいこと"))
        assertTrue(text.contains("- 体の特徴・持病など：腎臓の数値が やや高め"))
        assertTrue(text.contains("| 2026/10/15 | 4.2 | −0.2 |"))
        // 最初の記録の日から今日まで、1日1行
        assertTrue(text.contains("| 2026/10/13（火） | 190 |"))
        assertTrue(text.contains("| 2026/10/14（水） | 記録なし |  | 0回 | 1回（少ない） | いまいち | おしっこ：赤い・ピンク色（血が混じる） |"))
        assertTrue(text.contains("| 2026/10/15（木） |  |"))
        assertFalse(text.contains("2026/10/12（"))
    }

    @Test
    fun leavesOutSectionsNotChosen() {
        val text = AiReport.build(data, pet, today.minusDays(30), today, setOf(PdfSection.WEIGHT), today, zone)
        assertFalse(text.contains("## プロフィール"))
        assertFalse(text.contains("## 毎日のまとめ"))
        assertTrue(text.contains("## 体重の推移"))
    }
}
