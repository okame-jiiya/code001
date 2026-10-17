package jp.okame.yano

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 商品ページを開く画面。ウォッチ追加は、ページ上のボタンを自動で押す方式。 */
class ItemActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        }
        val watch = Button(this).apply {
            text = "☆ ウォッチ追加"
            setOnClickListener { addToWatchlist() }
        }
        status = TextView(this).apply { setPadding(12.dp, 0, 0, 0) }
        bar.addView(watch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        bar.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = MainActivity.DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.webViewClient = WebViewClient()
        web.loadUrl(url)
    }

    private fun addToWatchlist() {
        status.text = "追加中…"
        web.evaluateJavascript(FIND_AND_CLICK_JS) { result ->
            if (result == "\"notfound\"") {
                status.text = "⚠ ウォッチのボタンが見つかりません。ページ内のボタンを直接押してください"
                return@evaluateJavascript
            }
            ui.postDelayed({
                web.evaluateJavascript(CHECK_JS) { checked ->
                    status.text = if (checked == "true") "✓ 追加済み" else "⚠ 未確認（マイオク→ウォッチリストで確認してください）"
                }
            }, 1500)
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "url"

        private const val FIND_BUTTON_JS = """
            (function () {
              var els = document.querySelectorAll('button, a');
              for (var i = 0; i < els.length; i++) {
                var l = (els[i].getAttribute('aria-label') || '') + (els[i].textContent || '');
                if (l.indexOf('ウォッチリスト') >= 0 && l.indexOf('削除') < 0 && l.indexOf('解除') < 0) return els[i];
              }
              return null;
            })()
        """

        private const val FIND_AND_CLICK_JS =
            "(function(){var b=$FIND_BUTTON_JS;if(!b)return 'notfound';window.__yanoWatchBtn=b;b.click();return 'clicked';})()"

        private const val CHECK_JS = """
            (function () {
              var b = window.__yanoWatchBtn;
              if (!b) return false;
              var l = (b.getAttribute('aria-label') || '') + (b.textContent || '') + (b.className || '');
              return /解除|削除|watched|active/i.test(l) || b.getAttribute('aria-pressed') === 'true';
            })()
        """
    }
}
