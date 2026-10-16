package jp.okame.yano

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/** 一覧のサムネイル用の簡易ローダー（メモリキャッシュのみ）。 */
object ImageLoader {
    private val cache = object : android.util.LruCache<String, Bitmap>(64) {}
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    fun load(url: String, view: ImageView) {
        view.tag = url
        view.setImageBitmap(null)
        if (url.isEmpty()) return
        cache.get(url)?.let { view.setImageBitmap(it); return }
        pool.execute {
            val bmp = try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            } ?: return@execute
            cache.put(url, bmp)
            // 行が再利用されて別の商品になっていないときだけ反映する
            ui.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }
}
