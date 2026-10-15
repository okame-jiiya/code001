package com.okamejiiya.petcare.data

import com.okamejiiya.petcare.model.AppData
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
            excretionLogs = listOf(ExcretionLog(10L, 1L, ExcretionType.PEE, 1L, "多い", listOf("血が混じる"), "メモ")),
            moods = listOf(MoodLog(1L, LocalDate.of(2026, 10, 15), Mood.BAD)),
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
}
