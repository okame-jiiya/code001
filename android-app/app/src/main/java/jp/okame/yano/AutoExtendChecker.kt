package jp.okame.yano

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
        val status = try {
            parse(fetchHtml(item.url)) ?: "不明"
        } catch (e: Exception) {
            "不明"
        }
        // 取得失敗（不明）はキャッシュしない
        if (status != "不明") {
            synchronized(cache) {
                cache.put(item.auctionId, JSONObject().put("status", status).put("ts", System.currentTimeMillis()))
            }
        }
        return status
    }

    fun save() {
        synchronized(cache) { prefs.edit().putString(KEY, cache.toString()).apply() }
    }

    private fun fetchHtml(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", MainActivity.DESKTOP_UA)
            conn.setRequestProperty("Accept-Language", "ja")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(html: String): String? {
        val text = html.replace(Regex("<[^>]+>"), " ")
        return Regex("自動延長[\\s\\S]{0,30}?(あり|なし)").find(text)?.groupValues?.get(1)
    }

    companion object {
        private const val KEY = "cache_v1"
        private const val TTL_MS = 12L * 60 * 60 * 1000
        const val CONCURRENCY = 3
        const val STAGGER_MS = 350L
    }
}
