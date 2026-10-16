package com.okamejiiya.petcare.data

import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Diary
import com.okamejiiya.petcare.model.Expense
import com.okamejiiya.petcare.model.ExpenseCategory
import com.okamejiiya.petcare.model.PetDocument
import com.okamejiiya.petcare.model.RecordItem
import com.okamejiiya.petcare.model.RecordLog
import com.okamejiiya.petcare.model.Reminder
import com.okamejiiya.petcare.model.ReminderKind
import com.okamejiiya.petcare.model.RepeatType
import com.okamejiiya.petcare.model.SharedSettings
import com.okamejiiya.petcare.model.WeightLog
import org.json.JSONObject
import java.time.LocalTime
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.Mood
import com.okamejiiya.petcare.model.MoodLog
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.Sex
import com.okamejiiya.petcare.model.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SyncDataTest {
    private val pet = Pet(1L, "もち", Species.CAT, "ミックス（雑種）", Sex.FEMALE, true, 4.2, LocalDate.of(2020, 5, 1), false, 3)
    private val base = AppData(pets = listOf(pet), selectedPetId = 1L)

    @Test
    fun diffSendsOnlyChangedDocs() {
        val log = ExcretionLog(10L, 1L, ExcretionType.POOP, 1_700_000_000_000)
        val next = base.copy(excretionLogs = listOf(log))
        val changes = SyncData.diff(base, next)
        assertEquals(1, changes.size)
        assertEquals("excretionLogs", changes[0].collection)
        assertEquals("10", changes[0].id)
    }

    @Test
    fun diffDeletesRemovedDocs() {
        val withMood = base.copy(moods = listOf(MoodLog(1L, LocalDate.of(2026, 10, 15), Mood.GOOD)))
        val changes = SyncData.diff(withMood, base)
        assertEquals(listOf(DocChange("moods", "1_2026-10-15", null)), changes)
    }

    @Test
    fun selectingPetIsNotShared() {
        assertTrue(SyncData.diff(base, base.copy(selectedPetId = 2L)).isEmpty())
    }

    @Test
    fun remoteDocsRoundTrip() {
        val full = base.copy(
            pets = listOf(
                pet.copy(
                    adoptionDate = LocalDate.of(2020, 8, 1), personality = "甘えん坊", physical = "白黒", notes = "注射が苦手",
                    order = 2, photoId = "123", photoAsIcon = true, documents = listOf(PetDocument("456", "血統書")),
                ),
            ),
            excretionLogs = listOf(ExcretionLog(10L, 1L, ExcretionType.PEE, 1L, "多い", listOf("血が混じる"), "メモ", listOf("789"))),
            moods = listOf(MoodLog(1L, LocalDate.of(2026, 10, 15), Mood.BAD)),
            weights = listOf(WeightLog(11L, 1L, LocalDate.of(2026, 10, 15), 4.3, LocalTime.of(8, 30))),
            reminders = listOf(
                Reminder(12L, null, ReminderKind.WALK, 7, 0, repeat = RepeatType.WEEKLY, weekdays = setOf(1, 3)),
                Reminder(13L, 1L, ReminderKind.MEDICINE, 8, 0, repeat = RepeatType.HOURS, interval = 8, startDate = LocalDate.of(2026, 10, 1)),
            ),
            records = listOf(RecordLog(14L, 1L, RecordItem.TEMPERATURE, 5L, value = 38.6), RecordLog(15L, 1L, RecordItem.NAIL, 6L, text = "前足だけ")),
            diaries = listOf(Diary(1L, LocalDate.of(2026, 10, 15), "はじめてのお散歩")),
            expenses = listOf(Expense(16L, null, LocalDate.of(2026, 10, 15), ExpenseCategory.MEDICAL, 3300, "健康診断")),
            settings = SharedSettings(listOf(RecordItem.WEIGHT, RecordItem.WALK)),
        )
        // 全部送ったものを、空の端末で受け取ると同じになる
        var received = SyncData.clearShared(AppData(selectedPetId = 1L))
        for (c in SyncData.collections) {
            val docs = SyncData.diff(AppData(), full).filter { it.collection == c.name }.associate { it.id to it.json!! }
            received = c.applyDocs(received, docs)
        }
        assertEquals(full, received)
    }

    @Test
    fun brokenDocIsSkipped() {
        val c = SyncData.collection("pets")!!
        val d = c.applyDocs(AppData(), mapOf("1" to "{broken", "2" to Codec.pet(pet.copy(id = 2L)).toString()))
        assertEquals(listOf(2L), d.pets.map { it.id })
    }

    @Test
    fun oldReminderIsDaily() {
        val r = Codec.reminder(JSONObject("""{"id":1,"petId":1,"kind":"MEAL","hour":8,"minute":30,"memo":"","enabled":true}"""))
        assertEquals(RepeatType.DAILY, r.repeat)
        assertEquals(1L, r.petId)
    }

    @Test
    fun unknownRecordItemIsSkipped() {
        val c = SyncData.collection("records")!!
        val d = c.applyDocs(AppData(), mapOf("1" to """{"id":1,"petId":1,"item":"SOMETHING_NEW","time":1}"""))
        assertTrue(d.records.isEmpty())
    }

    @Test
    fun settingsAreOneDoc() {
        val next = base.copy(settings = SharedSettings(listOf(RecordItem.WALK)))
        assertEquals(listOf("settings" to "app"), SyncData.diff(base, next).map { it.collection to it.id })
        assertTrue(SyncData.diff(base, base.copy(settings = SharedSettings())).isEmpty())
    }

    @Test
    fun mealTimesRoundTrip() {
        val s = SharedSettings(mealTimes = listOf(LocalTime.of(6, 30), LocalTime.of(19, 0)), quickAtMealTime = true)
        assertEquals(s, Codec.settings(JSONObject(Codec.settings(s).toString())))
        // 以前の設定（時刻なし）はふつうの時刻になる
        assertEquals(SharedSettings.DEFAULT_MEAL_TIMES, Codec.settings(JSONObject("""{"items":["WEIGHT"]}""")).mealTimes)
    }

    @Test
    fun photoIconIsDefault() {
        val old = Codec.pet(pet.copy(photoId = "9", photoAsIcon = false)).apply { remove("iconMode") }
        assertTrue(Codec.pet(old).photoAsIcon)
        val emoji = pet.copy(photoId = "9", photoAsIcon = false)
        assertEquals(false, Codec.pet(JSONObject(Codec.pet(emoji).toString())).photoAsIcon)
    }
}
