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

    // ---- 画面ごとの表示順・表示する／しない ----

    private val _layout = MutableStateFlow(readLayout())

    /** 画面ごとの並び（表示しないものは含まない） */
    val layout: StateFlow<Map<Screen, List<Section>>> = _layout

    private val _hidden = MutableStateFlow(readHidden())
    val hidden: StateFlow<Set<Section>> = _hidden

    private fun readLayout(): Map<Screen, List<Section>> = Screen.entries.associateWith { screen ->
        val all = Section.entries.filter { it.screen == screen }
        val saved = prefs.getString("order_${screen.name}", null)?.split(',')?.mapNotNull { n -> all.firstOrNull { it.name == n } }.orEmpty()
        saved + (all - saved.toSet())
    }

    private fun readHidden(): Set<Section> =
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().mapNotNull { n -> Section.entries.firstOrNull { it.name == n } }.toSet()

    /** 画面に出す順（表示しないものを除く） */
    fun visible(screen: Screen): List<Section> = _layout.value.getValue(screen).filter { it !in _hidden.value }

    fun move(section: Section, by: Int) {
        val list = _layout.value.getValue(section.screen).toMutableList()
        val i = list.indexOf(section)
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i == j) return
        list.add(j, list.removeAt(i))
        _layout.value = _layout.value + (section.screen to list)
        prefs.edit().putString("order_${section.screen.name}", list.joinToString(",") { it.name }).apply()
    }

    fun setShown(section: Section, shown: Boolean) {
        val next = if (shown) _hidden.value - section else _hidden.value + section
        _hidden.value = next
        prefs.edit().putStringSet(KEY_HIDDEN, next.map { it.name }.toSet()).apply()
    }

    /** カレンダーのマスの高さ（1 がふつう） */
    private val _calendarSize = MutableStateFlow(prefs.getFloat(KEY_CAL_SIZE, 1f))
    val calendarSize: StateFlow<Float> = _calendarSize

    fun setCalendarSize(v: Float) {
        _calendarSize.value = v
        prefs.edit().putFloat(KEY_CAL_SIZE, v).apply()
    }

    /** 「うちの子」一覧の写真の大きさ（dp） */
    private val _petPhotoSize = MutableStateFlow(prefs.getInt(KEY_PET_PHOTO, 72))
    val petPhotoSize: StateFlow<Int> = _petPhotoSize

    fun setPetPhotoSize(v: Int) {
        _petPhotoSize.value = v
        prefs.edit().putInt(KEY_PET_PHOTO, v).apply()
    }

    fun resetLayout() {
        prefs.edit().apply { Screen.entries.forEach { remove("order_${it.name}") } }.remove(KEY_HIDDEN).apply()
        _layout.value = readLayout()
        _hidden.value = emptySet()
    }

    private companion object {
        const val KEY_FONT = "fontScale"
        const val KEY_CALENDAR = "calendarShow"
        const val KEY_HIDDEN = "hiddenSections"
        const val KEY_CAL_SIZE = "calendarSize"
        const val KEY_PET_PHOTO = "petPhotoSize"
    }
}

/** 表示をカスタマイズできる画面（TABS は下のタブそのもの） */
enum class Screen(val label: String) {
    TABS("下のタブ"),
    HOME("きょう"),
    CALENDAR("カレンダー"),
    PET("うちの子（1頭ずつの画面）"),
}

/** 画面の中の並べ替えられる部分。最初の順番がふつうの並び */
enum class Section(val screen: Screen, val label: String, val canHide: Boolean = true) {
    TAB_HOME(Screen.TABS, "🏠 きょう"),
    TAB_CALENDAR(Screen.TABS, "📅 カレンダー"),
    TAB_FOOD(Screen.TABS, "🍚 ごはん"),
    TAB_PET(Screen.TABS, "🐾 うちの子"),
    TAB_SETTINGS(Screen.TABS, "⚙ 設定", canHide = false),

    HOME_ALERTS(Screen.HOME, "注意・近い予定"),
    HOME_KCAL(Screen.HOME, "今日のカロリー"),
    HOME_FOOD(Screen.HOME, "ごはんをあげた"),
    HOME_TOILET(Screen.HOME, "トイレ・きげん"),
    HOME_OTHER(Screen.HOME, "ほかの記録"),
    HOME_LOG(Screen.HOME, "今日の記録"),

    CAL_GRID(Screen.CALENDAR, "カレンダー", canHide = false),
    CAL_QUICK(Screen.CALENDAR, "選んだ日にすぐ登録"),
    CAL_DAY(Screen.CALENDAR, "選んだ日の記録"),

    PET_PROFILE(Screen.PET, "プロフィール", canHide = false),
    PET_ABOUT(Screen.PET, "この子のこと（性格・特徴）"),
    PET_TRENDS(Screen.PET, "記録の推移"),
    PET_DOCS(Screen.PET, "書類"),
    PET_KCAL(Screen.PET, "1日のカロリーの目安"),
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
