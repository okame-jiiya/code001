package jp.okame.yano

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var runBtn: Button
    private lateinit var pagesSpinner: Spinner
    private lateinit var progress: TextView
    private lateinit var resultsPanel: LinearLayout
    private lateinit var resultsTitle: TextView
    private lateinit var resultsList: ListView

    private val ui = Handler(Looper.getMainLooper())
    private val extractJs by lazy { assets.open("extract.js").bufferedReader().use { it.readText() } }
    private val results = mutableListOf<YahooItem>()
    private val adapter = ResultsAdapter()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 画面の作りを拡張機能版と揃えるため、PC版のページを表示する
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(extractJs, null)
            }
        }
        web.loadUrl(START_URL)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        runBtn = Button(this).apply {
            text = "自動延長なしを抽出"
            setOnClickListener { startRun() }
        }
        pagesSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                RANGE_OPTIONS.map { it.first }
            )
            setSelection(RANGE_OPTIONS.lastIndex)
        }
        bar.addView(runBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(pagesSpinner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progress = TextView(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 12f
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val frame = FrameLayout(this)
        web = WebView(this)
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        resultsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        resultsTitle = TextView(this).apply { textSize = 16f; setTextColor(Color.BLACK) }
        val close = Button(this).apply {
            text = "閉じる"
            setOnClickListener { resultsPanel.visibility = View.GONE }
        }
        head.addView(resultsTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(close, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        resultsPanel.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        resultsList = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this@MainActivity, ItemActivity::class.java)
                    .putExtra(ItemActivity.EXTRA_URL, results[pos].url))
            }
        }
        resultsPanel.addView(resultsList, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        frame.addView(resultsPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun startRun() {
        runBtn.isEnabled = false
        progress.text = "商品一覧を取得中…"
        web.evaluateJavascript("window.Yano ? Yano.collect(${RANGE_OPTIONS[pagesSpinner.selectedItemPosition].second}) : AndroidBridge.onError('ページの読み込みが終わってから押してください')", null)
    }

    private fun finishRun(message: String) {
        ui.post {
            progress.text = message
            runBtn.isEnabled = true
        }
    }

    /** JavaScriptインターフェースのメソッドはWebView用の別スレッドで呼ばれる。 */
    private inner class Bridge {
        @JavascriptInterface
        fun onProgress(msg: String) {
            ui.post { progress.text = msg }
        }

        @JavascriptInterface
        fun onError(msg: String) = finishRun(msg)

        @JavascriptInterface
        fun onItems(json: String, total: Int) {
            val arr = JSONArray(json)
            val items = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                YahooItem(
                    o.getString("auctionId"), o.getString("url"), o.optString("title"),
                    o.optString("image"), o.optString("price"), o.optString("timeLeft")
                )
            }
            checkAutoExtend(items, total)
        }
    }

    private fun checkAutoExtend(items: List<YahooItem>, total: Int) {
        Thread {
            val checker = AutoExtendChecker(this)
            val done = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(AutoExtendChecker.CONCURRENCY)
            val futures = items.map { item ->
                pool.submit<String> {
                    val status = checker.statusOf(item)
                    val n = done.incrementAndGet()
                    ui.post { progress.text = "自動延長の状態を確認中… $n/${items.size}" }
                    Thread.sleep(AutoExtendChecker.STAGGER_MS)
                    status
                }
            }
            val statuses = futures.map { runCatching { it.get() }.getOrDefault("不明") }
            pool.shutdown()
            checker.save()

            val filtered = items.filterIndexed { i, _ -> statuses[i] == "なし" }
            val unknown = statuses.count { it == "不明" }
            ui.post {
                results.clear()
                results.addAll(filtered)
                adapter.notifyDataSetChanged()
                resultsTitle.text = "自動延長なし ${filtered.size}件"
                resultsPanel.visibility = View.VISIBLE
                progress.text = "完了: ${items.size}件中 ${filtered.size}件が自動延長なし" +
                    (if (total > items.size) "（検索結果は全${total}件）" else "") +
                    (if (unknown > 0) "／判定できなかった商品 ${unknown}件" else "")
                runBtn.isEnabled = true
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            resultsPanel.visibility == View.VISIBLE -> resultsPanel.visibility = View.GONE
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = results[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(img, LinearLayout.LayoutParams(dp(88), dp(88)))
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(context).apply { textSize = 14f; maxLines = 3; setTextColor(Color.BLACK) })
                col.addView(TextView(context).apply { textSize = 12f; setTextColor(Color.DKGRAY) })
                addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            }
            val img = row.getChildAt(0) as ImageView
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = item.title
            (col.getChildAt(1) as TextView).text = listOf(item.price, item.timeLeft).filter { it.isNotEmpty() }.joinToString(" / ")
            ImageLoader.load(item.image, img)
            return row
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 調べる範囲。second は最大件数で、0 は今開いているページだけ。 */
        val RANGE_OPTIONS = listOf(
            "このページのみ" to 0,
            "最大200件" to 200,
            "最大500件" to 500,
            "全ページ(最大1000件)" to 1000,
        )

        const val START_URL = "https://auctions.yahoo.co.jp/"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
