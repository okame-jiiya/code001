package com.okamejiiya.petcare.data

import com.okamejiiya.petcare.model.AppData
import org.json.JSONObject

/**
 * 家族共有でクラウドに置くデータの形。
 * 記録 1 件をドキュメント 1 つ（中身は JSON 文字列）にするので、
 * 2 人が同時に別の記録を書き込んでも、お互いの記録が消えない。
 */
internal class SyncCollection<T>(
    val name: String,
    val items: (AppData) -> List<T>,
    val replace: (AppData, List<T>) -> AppData,
    val key: (T) -> String,
    val encode: (T) -> JSONObject,
    val decode: (JSONObject) -> List<T>,
) {
    fun docs(d: AppData): Map<String, String> = items(d).associate { key(it) to encode(it).toString() }

    /** クラウドの中身（ドキュメント ID → JSON）で、このコレクションをまるごと置き換える */
    fun applyDocs(d: AppData, docs: Map<String, String>): AppData {
        val list = docs.toSortedMap().values.flatMap { json -> runCatching { decode(JSONObject(json)) }.getOrDefault(emptyList()) }
        return replace(d, list)
    }
}

/** 送る変更 1 件。json が null なら削除 */
internal data class DocChange(val collection: String, val id: String, val json: String?)

internal object SyncData {
    val collections: List<SyncCollection<*>> = listOf(
        SyncCollection("pets", { it.pets }, { d, l -> d.copy(pets = l) }, { it.id.toString() }, { Codec.pet(it) }, { listOf(Codec.pet(it)) }),
        SyncCollection("foods", { it.foods }, { d, l -> d.copy(foods = l) }, { it.id.toString() }, { Codec.food(it) }, { listOf(Codec.food(it)) }),
        SyncCollection("plans", { it.plans }, { d, l -> d.copy(plans = l) }, { it.id.toString() }, { Codec.plan(it) }, { listOf(Codec.plan(it)) }),
        SyncCollection("feedLogs", { it.feedLogs }, { d, l -> d.copy(feedLogs = l) }, { it.id.toString() }, { Codec.feed(it) }, { listOf(Codec.feed(it)) }),
        SyncCollection(
            "excretionLogs", { it.excretionLogs }, { d, l -> d.copy(excretionLogs = l) }, { it.id.toString() },
            { Codec.excretion(it) }, { listOf(Codec.excretion(it)) },
        ),
        SyncCollection("weights", { it.weights }, { d, l -> d.copy(weights = l) }, { it.id.toString() }, { Codec.weight(it) }, { listOf(Codec.weight(it)) }),
        SyncCollection("reminders", { it.reminders }, { d, l -> d.copy(reminders = l) }, { it.id.toString() }, { Codec.reminder(it) }, { listOf(Codec.reminder(it)) }),
        SyncCollection("health", { it.health }, { d, l -> d.copy(health = l) }, { it.id.toString() }, { Codec.health(it) }, { Codec.health(it) }),
        // 機嫌は「ペット × 日付」で 1 つ
        SyncCollection("moods", { it.moods }, { d, l -> d.copy(moods = l) }, { "${it.petId}_${it.date}" }, { Codec.mood(it) }, { listOf(Codec.mood(it)) }),
    )

    fun collection(name: String): SyncCollection<*>? = collections.firstOrNull { it.name == name }

    /** prev から next への変更を、ドキュメント単位の書き込み・削除にする */
    fun diff(prev: AppData, next: AppData): List<DocChange> = collections.flatMap { c ->
        if (c.items(prev) === c.items(next)) return@flatMap emptyList()
        val before = c.docs(prev)
        val after = c.docs(next)
        after.filter { (id, json) -> before[id] != json }.map { (id, json) -> DocChange(c.name, id, json) } +
            (before.keys - after.keys).map { DocChange(c.name, it, null) }
    }

    /** 共有に参加したとき、この端末の記録をいったん空にする（ペットの選択だけは残す） */
    fun clearShared(d: AppData): AppData = collections.fold(d) { acc, c -> c.replace(acc, emptyList()) }
}
