package com.okamejiiya.petcare.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderTest {
    private val now = LocalDateTime.of(2026, 10, 15, 10, 30) // 木曜

    private fun r(repeat: RepeatType, hour: Int, interval: Int = 1, weekdays: Set<Int> = emptySet(), start: LocalDate? = null) =
        Reminder(1, null, ReminderKind.MEAL, hour, 0, repeat = repeat, interval = interval, weekdays = weekdays, startDate = start)

    @Test fun dailyRollsToTomorrow() {
        assertEquals(LocalDateTime.of(2026, 10, 15, 18, 0), r(RepeatType.DAILY, 18).next(now))
        assertEquals(LocalDateTime.of(2026, 10, 16, 8, 0), r(RepeatType.DAILY, 8).next(now))
    }

    @Test fun everyEightHoursFromStart() {
        val x = r(RepeatType.HOURS, 8, interval = 8, start = LocalDate.of(2026, 10, 14))
        assertEquals(LocalDateTime.of(2026, 10, 15, 16, 0), x.next(now))
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), x.next(LocalDateTime.of(2026, 10, 15, 16, 0)))
    }

    @Test fun everyThreeDays() {
        val x = r(RepeatType.DAYS, 9, interval = 3, start = LocalDate.of(2026, 10, 10))
        // 10, 13, 16 …
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 0), x.next(now))
    }

    @Test fun futureStartWaits() {
        val x = r(RepeatType.DAYS, 9, interval = 30, start = LocalDate.of(2026, 11, 1))
        assertEquals(LocalDateTime.of(2026, 11, 1, 9, 0), x.next(now))
    }

    @Test fun weeklyPicksNextWeekday() {
        // 月(1)・日(7)
        assertEquals(LocalDateTime.of(2026, 10, 18, 9, 0), r(RepeatType.WEEKLY, 9, weekdays = setOf(1, 7)).next(now))
        // 木(4) の 9 時はもう過ぎたので来週
        assertEquals(LocalDateTime.of(2026, 10, 22, 9, 0), r(RepeatType.WEEKLY, 9, weekdays = setOf(4)).next(now))
        assertEquals(LocalDateTime.of(2026, 10, 15, 11, 0), r(RepeatType.WEEKLY, 11, weekdays = setOf(4)).next(now))
    }
}
