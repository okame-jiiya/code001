package com.okamejiiya.petcare.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 通院の1回分。fromSchedule はカレンダーの予定から数えたもの */
data class VisitEntry(val date: LocalDate, val time: LocalTime?, val text: String, val fromSchedule: Boolean)

/**
 * 通院の記録：「ほかの記録」の通院記録と、カレンダーの「通院」の予定のうち今日までのもの。
 * 同じ日に両方あれば 1 回と数える（予定のほうを残す）。新しい順。
 */
fun visitEntries(data: AppData, petId: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<VisitEntry> {
    val scheduled = data.health
        .filter { it.petId == petId && it.kind == HealthKind.VISIT && !it.date.isAfter(today) }
        .map { VisitEntry(it.date, it.time, listOf(it.title, it.note).filter { s -> s.isNotBlank() }.joinToString("・"), true) }
    val days = scheduled.map { it.date }.toSet()
    val recorded = data.records
        .filter { it.petId == petId && it.item == RecordItem.VISIT }
        .map { r ->
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.time), zone)
            VisitEntry(t.toLocalDate(), t.toLocalTime().withSecond(0).withNano(0), r.text, false)
        }
        .filter { it.date !in days }
        .distinctBy { it.date }
    return (scheduled.distinctBy { it.date } + recorded).sortedWith(compareByDescending<VisitEntry> { it.date }.thenByDescending { it.time })
}
