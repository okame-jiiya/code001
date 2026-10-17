package jp.okame.yano

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 商品ページを1件ずつ取得して「自動延長あり/なし」を判定する。
 * 取得はアプリ側（WebViewの外）で行うので、ページ間のCORS制限を受けない。
 * ログイン済みのCookieはWebViewのものをそのまま使う。
 */
class AutoExtendChecker(context: Context) {

    private val prefs = context.getSharedPreferences("yano_cache", Context.MODE_PRIVATE)
    private val cache = JSONObject(prefs.getString(KEY, "{}") ?: "{}")

    fun statusOf(item: YahooItem): String {
        synchronized(cache) {
            val c = cache.optJSONObject(item.auctionId)
            if (c != null && System.currentTimeMillis() - c.optLong("ts") < TTL_MS) {
                return c.optString("status", "不明")
            }
        }
        var status = "不明"
        var attempt = 0
        while (true) {
            waitIfPaused()
            val reason = try {
                val s = fetchStatus(item.url)
                if (s != null) {
                    status = s
                    null
                } else {
                    NOT_FOUND
                }
            } catch (e: HttpStatusException) {
                "HTTP ${e.code}"
            } catch (e: Exception) {
                "通信エラー"
            }
            if (reason == null) break
            // 記載が見つからないページは1回だけ、通信の失敗は2回までやり直す
            val limit = if (reason == NOT_FOUND) 1 else RETRIES
            if (attempt >= limit) {
                failures.getOrPut(reason) { AtomicInteger() }.incrementAndGet()
                break
            }
            attempt++
            if (reason == NOT_FOUND) {
                Thread.sleep(1000)
            } else {
                // 通信に失敗したら全体で少し待つ（アクセス制限を受けている場合に備える）
                pauseAll(RETRY_WAIT_MS * attempt)
            }
        }
        // 取得失敗（不明）はキャッシュしない
        if (status != "不明") {
            synchronized(cache) {
                cache.put(item.auctionId, JSONObject().put("status", status).put("ts", System.currentTimeMillis()))
            }
        }
        return status
    }

    /** 判定できなかった理由ごとの件数（例: "HTTP 403" → 120）。 */
    val failures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var pauseUntil = 0L

    private fun pauseAll(ms: Long) {
        synchronized(this) { pauseUntil = maxOf(pauseUntil, System.currentTimeMillis() + ms) }
    }

    private fun waitIfPaused() {
        while (true) {
            val wait = pauseUntil - System.currentTimeMillis()
            if (wait <= 0) return
            Thread.sleep(wait)
        }
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    fun save() {
        synchronized(cache) {
            // 期限切れのものは保存のたびに捨て、記録が増え続けないようにする
            val now = System.currentTimeMillis()
            cache.keys().asSequence().toList()
                .filter { now - (cache.optJSONObject(it)?.optLong("ts") ?: 0L) >= TTL_MS }
                .forEach { cache.remove(it) }
            prefs.edit().putString(KEY, cache.toString()).apply()
        }
    }

    /**
     * 商品ページを先頭から少しずつ読み、「自動延長」の判定が見つかった時点で通信を打ち切る。
     * HTMLだけを取得するので、画像や広告は読み込まない。
     */
    private fun fetchStatus(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", MainActivity.DESKTOP_UA)
            conn.setRequestProperty("Accept-Language", "ja")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) throw HttpStatusException(conn.responseCode)

            val html = StringBuilder()
            var searchFrom = 0
            val buf = CharArray(16 * 1024)
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val n = reader.read(buf)
                    val eof = n < 0
                    if (!eof) html.appendRange(buf, 0, n)
                    // 「自動延長」の後ろが十分に読めたものから順に判定する
                    while (true) {
                        val idx = html.indexOf(LABEL, searchFrom)
                        if (idx < 0 || (!eof && html.length < idx + WINDOW)) break
                        parse(html.substring(idx, minOf(html.length, idx + WINDOW)))?.let { return it }
                        searchFrom = idx + LABEL.length
                    }
                    if (eof) return null
                }
            }
            @Suppress("UNREACHABLE_CODE")
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(chunk: String): String? {
        val text = chunk.replace(Regex("<[^>]+>"), " ")
        return Regex("^自動延長[\\s\\S]{0,30}?(あり|なし)").find(text)?.groupValues?.get(1)
    }

    companion object {
        private const val KEY = "cache_v1"
        // ヤフオクの出品期間は最長7日間なので、余裕を見て8日間覚えておく
        private const val TTL_MS = 8L * 24 * 60 * 60 * 1000
        private const val LABEL = "自動延長"
        private const val WINDOW = 2000
        private const val RETRIES = 2
        private const val NOT_FOUND = "ページに自動延長の記載なし"
        private const val RETRY_WAIT_MS = 3000L
        const val CONCURRENCY = 4
        const val STAGGER_MS = 150L
    }
}
