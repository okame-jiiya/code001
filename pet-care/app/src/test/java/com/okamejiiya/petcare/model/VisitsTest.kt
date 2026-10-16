package com.okamejiiya.petcare.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class VisitsTest {
    private val today = LocalDate.of(2026, 10, 16)
    private fun at(d: LocalDate) = d.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun countsPastScheduledVisitsAndRecordsOncePerDay() {
        val data = AppData(
            health = listOf(
                HealthRecord(1, 1, HealthKind.VISIT, today, "再診"),
                HealthRecord(2, 1, HealthKind.VISIT, today.minusDays(5), "健康診断"),
                HealthRecord(3, 1, HealthKind.VISIT, today.plusDays(1), "先の予定"),
                HealthRecord(4, 1, HealthKind.VACCINE, today.minusDays(3), "ワクチン"),
                HealthRecord(5, 2, HealthKind.VISIT, today.minusDays(1), "ほかの子"),
            ),
            records = listOf(
                RecordLog(6, 1, RecordItem.VISIT, at(today.minusDays(5)), text = "同じ日"),
                RecordLog(7, 1, RecordItem.VISIT, at(today.minusDays(9)), text = "皮膚科"),
            ),
        )
        val list = visitEntries(data, 1, today, ZoneOffset.UTC)
        assertEquals(listOf(today, today.minusDays(5), today.minusDays(9)), list.map { it.date })
        assertEquals(listOf(true, true, false), list.map { it.fromSchedule })
    }
}
