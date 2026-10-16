package com.okamejiiya.petcare.ui

import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.ExcretionLog
import com.okamejiiya.petcare.model.ExcretionType
import com.okamejiiya.petcare.model.Pet
import com.okamejiiya.petcare.model.Sex
import com.okamejiiya.petcare.model.Species
import com.okamejiiya.petcare.notify.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HealthAlertsTest {
    private val hour = 3_600_000L
    private val now = 1_000_000_000_000L
    private val cat = Pet(1, "みけ", Species.CAT, "", Sex.FEMALE, true, 4.0, LocalDate.of(2020, 1, 1), false, 3)

    private fun pee(hoursAgo: Long, flags: List<String> = emptyList()) =
        ExcretionLog(hoursAgo, 1, ExcretionType.PEE, now - hoursAgo * hour, flags = flags)

    @Test fun warnsWhenNoPeeFor24HoursAfterRegularLogging() {
        val data = AppData(pets = listOf(cat), excretionLogs = listOf(pee(30), pee(40), pee(50)))
        assertTrue(healthAlerts(data, cat, now).single().contains("30時間"))
    }

    @Test fun noWarningForSparseLogging() {
        val data = AppData(pets = listOf(cat), excretionLogs = listOf(pee(30)))
        assertTrue(healthAlerts(data, cat, now).isEmpty())
    }

    @Test fun warnsAboutBloodInUrine() {
        val data = AppData(pets = listOf(cat), excretionLogs = listOf(pee(2, listOf("血が混じる")), pee(8)))
        assertTrue(healthAlerts(data, cat, now).single().contains("血が混じる"))
    }

    @Test fun nextTriggerRollsToTomorrow() {
        val now = LocalDateTime.of(2026, 10, 13, 9, 0)
        assertEquals(LocalDateTime.of(2026, 10, 13, 18, 0), Reminders.nextTrigger(18, 0, now))
        assertEquals(LocalDateTime.of(2026, 10, 14, 8, 0), Reminders.nextTrigger(8, 0, now))
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 0), Reminders.nextTrigger(9, 0, now))
    }
}
