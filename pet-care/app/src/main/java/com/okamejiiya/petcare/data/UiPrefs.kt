package com.okamejiiya.petcare.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** この端末だけの表示の設定（文字の大きさ・カレンダーに出す項目） */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _fontScale = MutableStateFlow(prefs.getFloat(KEY_FONT, 1f))
    val fontScale: StateFlow<Float> = _fontScale

    private val _calendarShow = MutableStateFlow(
        prefs.getStringSet(KEY_CALENDAR, null)?.toSet() ?: CalendarShow.entries.filter { it.default }.map { it.name }.toSet(),
    )
    val calendarShow: StateFlow<Set<String>> = _calendarShow

    fun setFontScale(v: Float) {
        _fontScale.value = v
        prefs.edit().putFloat(KEY_FONT, v).apply()
    }

    fun setCalendarShow(item: CalendarShow, on: Boolean) {
        val next = if (on) _calendarShow.value + item.name else _calendarShow.value - item.name
        _calendarShow.value = next
        prefs.edit().putStringSet(KEY_CALENDAR, next).apply()
    }

    private companion object {
        const val KEY_FONT = "fontScale"
        const val KEY_CALENDAR = "calendarShow"
    }
}

/** カレンダーの日付の下に出せるもの */
enum class CalendarShow(val label: String, val default: Boolean = true) {
    KCAL("カロリー"),
    TOILET("うんち・おしっこの回数"),
    MOOD("機嫌"),
    RECORDS("そのほかの記録（体温・薬など）"),
    SCHEDULE("予定"),
    DIARY("日記"),
    EXPENSE("支出"),
}
