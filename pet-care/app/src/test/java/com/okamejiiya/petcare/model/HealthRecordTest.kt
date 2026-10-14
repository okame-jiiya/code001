package com.okamejiiya.petcare.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class HealthRecordTest {
    private val base = HealthRecord(1, 1, HealthKind.TRIMMING, LocalDate.of(2026, 10, 20), "カット")

    @Test fun notifiesDayBeforeAndHoursBefore() {
        val r = base.copy(time = LocalTime.of(14, 30), notifyDayBeforeHour = 20, notifyHoursBefore = 2)
        assertEquals(
            listOf("前日" to LocalDateTime.of(2026, 10, 19, 20, 0), "当日" to LocalDateTime.of(2026, 10, 20, 12, 30)),
            r.notifyTimes(),
        )
    }

    @Test fun untimedEventUsesNineOClock() {
        assertEquals(LocalDateTime.of(2026, 10, 20, 8, 0), base.copy(notifyHoursBefore = 1).notifyTimes().single().second)
    }

    @Test fun noNotificationByDefault() {
        assertTrue(base.notifyTimes().isEmpty())
    }

    @Test fun breedsStartWithMixAndHaveNoDuplicates() {
        for (s in Species.entries) {
            val list = Breeds.of(s)
            assertEquals(Breeds.MIX, list.first())
            assertEquals(list.size, list.toSet().size)
        }
        assertTrue(Breeds.dogs.size > 100)
        assertTrue(Breeds.cats.size > 50)
    }
}
