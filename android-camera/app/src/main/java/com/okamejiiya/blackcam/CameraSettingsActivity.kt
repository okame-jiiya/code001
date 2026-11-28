package com.okamejiiya.blackcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 録画に使うカメラとズーム倍率を選ぶ画面。
 * カメラごとの性能 (焦点距離・センサー・暗所性能・手ぶれ補正・最大画質) を表示し、
 * 数秒の試し撮りで実際のファイルサイズ・明るさ・露出を測って、撮影した映像を見て選べる。
 */
class CameraSettingsActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var catalog: CameraCatalog
    private lateinit var tester: CameraTester

    private lateinit var cameraSpinner: Spinner
    private lateinit var specText: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var testHint: TextView
    private lateinit var testButton: Button
    private lateinit var testResult: TextView
    private lateinit var previewView: VideoView

    private var options: List<CameraOption> = emptyList()
    private var spec: CameraSpec? = null
    private var pendingZoom: Float? = null
    private var shownOption: CameraOption? = null
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        store = SettingsStore(this)
        catalog = CameraCatalog(this)
        tester = CameraTester(this)

        cameraSpinner = findViewById(R.id.cameraSpinner)
        specText = findViewById(R.id.specText)
        zoomLabel = findViewById(R.id.zoomLabel)
        zoomBar = findViewById(R.id.zoomBar)
        testHint = findViewById(R.id.testHint)
        testButton = findViewById(R.id.testButton)
        testResult = findViewById(R.id.testResult)
        previewView = findViewById(R.id.previewView)

        options = runCatching { catalog.options }.getOrDefault(emptyList())
        val resolved = runCatching { catalog.resolve(store.load()) }.getOrNull()
        refreshCameraLabels(resolved?.option)
        if (resolved != null) {
            pendingZoom = resolved.zoom
            cameraSpinner.setSelection(options.indexOf(resolved.option).coerceAtLeast(0), false)
        }
        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val option = options.getOrNull(position) ?: return
                val zoom = pendingZoom ?: option.zoomRatio
                pendingZoom = null
                showOption(option, zoom)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = updateZoomLabel()
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        findViewById<Button>(R.id.useButton).setOnClickListener { useCamera() }
        testButton.setOnClickListener { startTest() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { confirmReset() }
        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }

        if (options.isEmpty()) {
            specText.setText(R.string.err_no_camera)
            testButton.isEnabled = false
            findViewById<Button>(R.id.useButton).isEnabled = false
        }
    }

    override fun onDestroy() {
        previewView.stopPlayback()
        File(cacheDir, "camera_test.mp4").delete()
        super.onDestroy()
    }

    /** 一覧の中で、いま録画に使われているカメラに「使用中」と付ける。 */
    private fun refreshCameraLabels(inUse: CameraOption?) {
        val position = cameraSpinner.selectedItemPosition
        val labels = options.map { if (it == inUse) getString(R.string.camera_in_use, it.label) else it.label }
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (position >= 0) cameraSpinner.setSelection(position, false)
    }

    private fun selectedOption(): CameraOption? = options.getOrNull(cameraSpinner.selectedItemPosition)

    private fun currentZoom(): Float {
        val s = spec ?: return 1f
        return VideoMath.progressToZoom(s.zoomMin, zoomBar.progress).coerceIn(s.zoomMin, s.zoomMax)
    }

    private fun showOption(option: CameraOption, zoom: Float) {
        // 「使用中」の表示を付け直したときも選択の通知が来るので、同じカメラなら試し撮りの結果を残す
        val sameOption = option == shownOption
        shownOption = option
        val s = catalog.specOf(option.cameraId)
        spec = s
        specText.text = catalog.describe(option)
        val steps = VideoMath.zoomSteps(s.zoomMin, s.zoomMax)
        zoomBar.max = steps
        zoomBar.isEnabled = steps > 0
        zoomBar.progress = VideoMath.zoomToProgress(s.zoomMin, zoom.coerceIn(s.zoomMin, s.zoomMax))
        updateZoomLabel()
        updateTestHint(option)
        if (!sameOption) clearTestResult()
    }

    private fun updateZoomLabel() {
        zoomLabel.text = getString(R.string.label_zoom, VideoMath.formatZoom(currentZoom()))
    }

    private fun updateTestHint(option: CameraOption) {
        val config = runCatching { catalog.resolve(store.load().copy(cameraId = option.cameraId, lensBase = option.zoomRatio)) }.getOrNull()
        val quality = config?.let {
            getString(
                R.string.quality_summary, catalog.sizeLabel(it.size), it.fps,
                getString(it.codec.labelRes), VideoMath.formatBytes(VideoMath.bytesPerTenMinutes(it.bitrate, it.audio)),
            )
        } ?: ""
        testHint.text = getString(R.string.test_hint, quality)
    }

    private fun clearTestResult() {
        testResult.text = ""
        previewView.stopPlayback()
        previewView.visibility = View.GONE
    }

    private fun useCamera() {
        val option = selectedOption() ?: return
        store.save(store.load().copy(cameraId = option.cameraId, lensBase = option.zoomRatio, zoomRatio = currentZoom()))
        store.deviceSignature = catalog.signature
        // 一覧を作り直すと選択の通知が来るので、いまのズームを引き継がせる
        pendingZoom = currentZoom()
        refreshCameraLabels(option)
        Toast.makeText(this, R.string.toast_camera_saved, Toast.LENGTH_SHORT).show()
    }

    // ---- 試し撮り ----

    private fun startTest() {
        if (testing) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        val option = selectedOption() ?: return
        val settings = store.load().copy(cameraId = option.cameraId, lensBase = option.zoomRatio, zoomRatio = currentZoom())
        testing = true
        testButton.isEnabled = false
        clearTestResult()
        testResult.setText(R.string.test_running)
        tester.run(settings, TEST_DURATION_MS) { result, error ->
            testing = false
            if (isDestroyed) return@run
            testButton.isEnabled = true
            if (result == null) {
                testResult.text = getString(R.string.test_failed, error ?: "")
            } else {
                showResult(result, settings)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startTest()
    }

    private fun showResult(r: TestResult, settings: RecordingSettings) {
        val lines = mutableListOf<String>()
        lines += getString(R.string.result_clip, "${r.width}×${r.height}", VideoMath.formatBytes(r.bytes), VideoMath.format1(r.durationMs / 1000f))
        lines += getString(R.string.result_bitrate, VideoMath.format1(r.videoBitrateMbps))
        lines += getString(R.string.result_ten, VideoMath.formatBytes(r.tenMinuteBytes), VideoMath.formatBytes(r.tenMinuteBytes * 6))
        r.averageLuma?.let { luma ->
            val level = when {
                luma < 40 -> R.string.bright_dark
                luma < 90 -> R.string.bright_dim
                luma < 170 -> R.string.bright_normal
                else -> R.string.bright_bright
            }
            lines += getString(R.string.result_brightness, luma, getString(level))
        }
        if (r.iso != null || r.exposureNs != null) {
            lines += getString(
                R.string.result_exposure,
                r.iso?.toString() ?: "?",
                r.exposureNs?.let { shutter(it) } ?: "?",
                r.aperture?.let { VideoMath.format1(it) } ?: "?",
                r.focalMm?.let { VideoMath.format1(it) } ?: "?",
            )
        }
        lines += getString(R.string.result_play_hint)
        testResult.text = lines.joinToString("\n")

        previewView.visibility = View.VISIBLE
        previewView.setVideoPath(r.file.absolutePath)
        previewView.setOnPreparedListener { it.isLooping = true; previewView.start() }
        previewView.setOnErrorListener { _, _, _ -> previewView.visibility = View.GONE; true }
        previewView.setOnClickListener { showFullscreen(r.file) }
    }

    /** 試し撮りの映像を全画面で再生する。タップで閉じる。 */
    private fun showFullscreen(file: File) {
        previewView.pause()
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val video = VideoView(this)
        val hint = TextView(this).apply {
            setText(R.string.fullscreen_hint)
            setTextColor(Color.argb(0xB0, 0xFF, 0xFF, 0xFF))
            setPadding(0, 0, 0, (32 * resources.displayMetrics.density).toInt())
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setOnClickListener { dialog.dismiss() }
        }
        video.setOnClickListener { dialog.dismiss() }
        video.setVideoPath(file.absolutePath)
        video.setOnPreparedListener { it.isLooping = true; video.start() }
        dialog.setContentView(frame)
        dialog.setOnDismissListener {
            video.stopPlayback()
            if (previewView.visibility == View.VISIBLE) previewView.start()
        }
        dialog.show()
    }

    private fun shutter(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 0.25) String.format(Locale.US, "%.1f s", seconds)
        else "1/${(1.0 / seconds).roundToInt()} s"
    }

    // ---- 初期化 ----

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.btn_reset_yes) { _, _ ->
                store.resetCameraSettings()
                store.deviceSignature = catalog.signature
                Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val REQUEST_CAMERA = 3
        const val TEST_DURATION_MS = 3000L
    }
}
