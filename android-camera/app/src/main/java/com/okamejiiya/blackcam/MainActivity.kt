package com.okamejiiya.blackcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * 起動すると真っ黒な画面のまま録画を始め、ホームに戻る (onStop) と録画を止めて保存する。
 * 画面をタップすると設定パネルが開き、次回起動時の設定を変更できる。
 */
class MainActivity : Activity(), VideoRecorder.Listener {

    private lateinit var store: SettingsStore
    private lateinit var catalog: CameraCatalog
    private lateinit var recorder: VideoRecorder
    private lateinit var orientationListener: OrientationEventListener

    private lateinit var recIndicator: TextView
    private lateinit var message: TextView
    private lateinit var tapHint: TextView
    private lateinit var recordButton: Button
    private lateinit var storageInfo: TextView
    private lateinit var permissionButton: Button
    private lateinit var settingsPanel: View
    private lateinit var status: TextView
    private lateinit var cameraSummary: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var bitrateSpinner: Spinner
    private lateinit var saveSpinner: Spinner
    private lateinit var folderLabel: TextView
    private lateinit var lastResult: TextView
    private lateinit var audioSwitch: Switch
    private lateinit var stabilizationSwitch: Switch
    private lateinit var indicatorSwitch: Switch

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var deviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN
    private var started = false
    private var permissionRequested = false
    private var recordingSince = 0L

    /** 録画の状態。設定パネルの開始・停止ボタンの表示に使う。 */
    private enum class RecState { IDLE, STARTING, RECORDING, STOPPING }

    private var recState = RecState.IDLE

    /** 今の設定で 10 分あたりに使う容量。空き容量から録画できる時間を出すのに使う。 */
    private var bytesPerTenMinutes = 0L

    private var currentOption: CameraOption? = null
    private var sizeChoices: List<Size> = emptyList()
    private var fpsChoices: List<Int> = emptyList()
    private var codecChoices: List<Codec> = emptyList()
    private var backCallback: Any? = null
    private var folderUri: String? = null

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            updateLastResult()
            updateStorageInfo()
            if (settingsPanel.visibility == View.VISIBLE) mainHandler.postDelayed(this, 1000)
        }
    }

    /** 設定パネルを開いたまま放置すると画面が明るいままで電池を使うので、しばらく触らなければ閉じる。 */
    private val panelTimeout = Runnable { closeSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SettingsStore(this)
        catalog = CameraCatalog(this)
        recorder = VideoRecorder(this, this)

        recIndicator = findViewById(R.id.recIndicator)
        message = findViewById(R.id.message)
        tapHint = findViewById(R.id.tapHint)
        recordButton = findViewById(R.id.recordButton)
        storageInfo = findViewById(R.id.storageInfo)
        permissionButton = findViewById(R.id.permissionButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        status = findViewById(R.id.status)
        cameraSummary = findViewById(R.id.cameraSummary)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        codecSpinner = findViewById(R.id.codecSpinner)
        bitrateSpinner = findViewById(R.id.bitrateSpinner)
        saveSpinner = findViewById(R.id.saveSpinner)
        folderLabel = findViewById(R.id.folderLabel)
        lastResult = findViewById(R.id.lastResult)
        audioSwitch = findViewById(R.id.audioSwitch)
        stabilizationSwitch = findViewById(R.id.stabilizationSwitch)
        indicatorSwitch = findViewById(R.id.indicatorSwitch)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setDimmed(true)

        findViewById<View>(R.id.root).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.pickFolderButton).setOnClickListener { pickFolder() }
        recordButton.setOnClickListener { toggleRecording() }
        permissionButton.setOnClickListener { openAppPermissions() }
        findViewById<Button>(R.id.chooseCameraButton).setOnClickListener { chooseCamera() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { closeSettings() }

        resolutionSpinner.onItemSelectedListener = onSelected { refreshFps(selectedFps()) }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) deviceOrientation = orientation
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        recState = RecState.IDLE
        orientationListener.enable()
        checkDeviceChange()
        startIfPermitted()
    }

    override fun onStop() {
        started = false
        permissionRequested = false
        orientationListener.disable()
        // 保存は RecordingService (前面サービス) がプロセスを生かしている間に最後まで行われる
        recorder.stop()
        closeSettings()
        super.onStop()
    }

    override fun onDestroy() {
        recorder.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (settingsPanel.visibility == View.VISIBLE) restartPanelTimeout()
        return super.dispatchTouchEvent(ev)
    }

    // ---- 録画 ----

    private fun requiredPermissions(settings: RecordingSettings): Array<String> =
        if (settings.audio) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        else arrayOf(Manifest.permission.CAMERA)

    private fun startIfPermitted() {
        val settings = store.load()
        val missing = requiredPermissions(settings).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            message.visibility = View.GONE
            recState = RecState.STARTING
            updateRecordButton()
            recorder.start(settings) { deviceOrientation }
        } else if (!permissionRequested) {
            permissionRequested = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            showMessage(getString(R.string.permission_needed))
            updateRecordButton()
        }
    }

    /** 設定パネルの録画ボタン。録画中なら止めて保存し、止まっていれば今の設定で録画を始める。 */
    private fun toggleRecording() {
        when (recState) {
            RecState.RECORDING -> {
                recState = RecState.STOPPING
                updateRecordButton()
                recorder.stop()
            }
            RecState.IDLE -> {
                permissionRequested = false
                startIfPermitted()
            }
            RecState.STARTING, RecState.STOPPING -> Unit
        }
    }

    private fun updateRecordButton() {
        val (text, enabled) = when (recState) {
            RecState.IDLE -> R.string.btn_record_start to true
            RecState.STARTING -> R.string.btn_record_starting to false
            RecState.RECORDING -> R.string.btn_record_stop to true
            RecState.STOPPING -> R.string.btn_record_stopping to false
        }
        recordButton.setText(text)
        recordButton.isEnabled = enabled
        val missing = requiredPermissions(store.load()).any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        permissionButton.visibility = if (missing) View.VISIBLE else View.GONE
    }

    private fun openAppPermissions() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS || !started) return
        startIfPermitted()
    }

    override fun onRecordingStarted(startedAtElapsed: Long) {
        recordingSince = startedAtElapsed
        recState = RecState.RECORDING
        updateRecordButton()
        message.visibility = View.GONE
        showTapHintIfNeeded()
        // 録画中であることを小さな赤い点で知らせる (設定で消すと、開始直後に数秒だけ表示する)
        recIndicator.animate().cancel()
        recIndicator.alpha = 0f
        if (store.load().showIndicator) {
            recIndicator.animate().alpha(INDICATOR_ALPHA).setDuration(300)
        } else {
            recIndicator.animate().alpha(1f).setDuration(300).withEndAction {
                recIndicator.animate().alpha(0f).setStartDelay(2000).setDuration(800)
            }
        }
        updateStatus()
    }

    override fun onRecordingStopped(savedFiles: Int) {
        val stoppedByUser = recState == RecState.STOPPING
        recordingSince = 0L
        // ホームから戻った直後は、前の録画の保存完了の知らせが新しい録画の開始より後に届くことがある
        if (recState != RecState.STARTING) recState = RecState.IDLE
        updateRecordButton()
        // 設定パネルから止めたときは、黒い画面に戻っても止まっていることが分かるようにする
        if (stoppedByUser && started) showMessage(getString(R.string.stopped_message))
        recIndicator.animate().cancel()
        recIndicator.alpha = 0f
        updateStatus()
        if (savedFiles > 0) {
            Toast.makeText(applicationContext, getString(R.string.saved_video, savedFiles), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onError(message: String) {
        recordingSince = 0L
        recState = RecState.IDLE
        updateRecordButton()
        showMessage(getString(R.string.error_prefix, message))
        updateStatus()
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    /** 黒い画面をタップすると設定が開くことを、使い始めのうちだけ控えめに知らせる。 */
    private fun showTapHintIfNeeded() {
        if (store.tapHintCount >= TAP_HINT_LAUNCHES) return
        store.tapHintCount = store.tapHintCount + 1
        tapHint.animate().cancel()
        tapHint.alpha = 0f
        tapHint.animate().alpha(1f).setDuration(300).withEndAction {
            tapHint.animate().alpha(0f).setStartDelay(3000).setDuration(800)
        }
    }

    private fun restartPanelTimeout() {
        mainHandler.removeCallbacks(panelTimeout)
        mainHandler.postDelayed(panelTimeout, PANEL_TIMEOUT_MS)
    }

    /** 空き容量と、今の設定であと何分録画できるかの目安。 */
    private fun updateStorageInfo() {
        val path = getExternalFilesDir(null)?.path ?: filesDir.path
        val free = runCatching { StatFs(path).availableBytes }.getOrNull() ?: return
        storageInfo.text = if (bytesPerTenMinutes > 0) {
            val minutes = free * 10 / bytesPerTenMinutes
            getString(R.string.storage_info, VideoMath.formatBytes(free), getString(R.string.duration_hm, minutes / 60, minutes % 60))
        } else {
            getString(R.string.storage_free, VideoMath.formatBytes(free))
        }
    }

    // ---- 設定パネル ----

    private fun openSettings() {
        if (settingsPanel.visibility == View.VISIBLE) return
        val saved = store.load()
        val resolved = runCatching { catalog.resolve(saved) }.getOrNull()

        currentOption = resolved?.option ?: runCatching { catalog.options.firstOrNull() }.getOrNull()
        cameraSummary.text = currentOption?.let {
            getString(R.string.camera_summary, it.label, VideoMath.formatZoom(resolved?.zoom ?: it.zoomRatio))
        } ?: getString(R.string.err_no_camera)
        refreshSizes(resolved?.size ?: Size(saved.width, saved.height), resolved?.fps ?: saved.fps)
        bytesPerTenMinutes = resolved?.let { VideoMath.bytesPerTenMinutes(it.bitrate, it.audio) } ?: 0L

        codecChoices = if (catalog.hevcSupported) Codec.entries.toList() else listOf(Codec.H264)
        codecSpinner.adapter = adapter(codecChoices.map { getString(it.labelRes) })
        codecSpinner.setSelection(codecChoices.indexOf(resolved?.codec ?: saved.codec).coerceAtLeast(0), false)

        val bitrateLabels = RecordingSettings.BITRATE_CHOICES_MBPS.map {
            if (it == 0) getString(R.string.bitrate_auto) else getString(R.string.bitrate_mbps, it)
        }
        bitrateSpinner.adapter = adapter(bitrateLabels)
        bitrateSpinner.setSelection(
            RecordingSettings.BITRATE_CHOICES_MBPS.indexOf(saved.bitrateMbps).coerceAtLeast(0), false
        )

        saveSpinner.adapter = adapter(SaveTarget.entries.map { getString(it.labelRes) })
        saveSpinner.setSelection(saved.saveTarget.ordinal, false)
        folderUri = saved.folderUri
        updateFolderLabel()
        updateLastResult()

        audioSwitch.isChecked = saved.audio
        stabilizationSwitch.isChecked = saved.stabilization
        indicatorSwitch.isChecked = saved.showIndicator

        settingsPanel.visibility = View.VISIBLE
        tapHint.animate().cancel()
        tapHint.alpha = 0f
        updateRecordButton()
        setDimmed(false)
        registerBack()
        restartPanelTimeout()
        mainHandler.removeCallbacks(statusTicker)
        statusTicker.run()
    }

    private fun closeSettings() {
        if (settingsPanel.visibility != View.VISIBLE) return
        settingsPanel.visibility = View.GONE
        mainHandler.removeCallbacks(statusTicker)
        mainHandler.removeCallbacks(panelTimeout)
        unregisterBack()
        setDimmed(true)
        hideSystemBars()
    }

    /** カメラ・ズームは専用の画面で保存されるので、保存済みの値を引き継ぎ、パネルの他の項目を反映する。 */
    private fun collectSettings(): RecordingSettings {
        val size = selectedSize() ?: Size(1920, 1080)
        return store.load().copy(
            width = size.width,
            height = size.height,
            fps = selectedFps() ?: 30,
            codec = codecChoices.getOrNull(codecSpinner.selectedItemPosition) ?: Codec.H264,
            bitrateMbps = RecordingSettings.BITRATE_CHOICES_MBPS.getOrNull(bitrateSpinner.selectedItemPosition) ?: 0,
            audio = audioSwitch.isChecked,
            stabilization = stabilizationSwitch.isChecked,
            saveTarget = SaveTarget.entries.getOrElse(saveSpinner.selectedItemPosition) { SaveTarget.DCIM },
            folderUri = folderUri,
            showIndicator = indicatorSwitch.isChecked,
        )
    }

    /** カメラ・ズームを選ぶ画面を開く。開いている間は録画が止まるので、いまの設定を先に保存しておく。 */
    private fun chooseCamera() {
        store.save(collectSettings())
        startActivity(Intent(this, CameraSettingsActivity::class.java))
    }

    /**
     * 端末のカメラ構成が前回の保存時と違う (データ移行などで別の端末の設定が引き継がれた) ときは、
     * 存在しないカメラを指したまま動かなくならないよう、カメラ関連の設定を初期値に戻す。
     */
    private fun checkDeviceChange() {
        val current = runCatching { catalog.signature }.getOrNull() ?: return
        val saved = store.deviceSignature
        if (saved == null) {
            store.deviceSignature = current
        } else if (saved != current) {
            store.resetCameraSettings()
            store.deviceSignature = current
            Toast.makeText(this, R.string.auto_reset_notice, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSettings() {
        var settings = collectSettings()
        // フォルダ保存を選んでもフォルダが未選択なら、動画を失わないようアルバム (DCIM) に保存する
        if (settings.saveTarget == SaveTarget.FOLDER && settings.folderUri == null) {
            settings = settings.copy(saveTarget = SaveTarget.DCIM)
        }
        store.save(settings)
        Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        closeSettings()
    }

    /** フォルダ選択画面を開く。開いている間は録画が止まるので、いまの設定を先に保存しておく。 */
    private fun pickFolder() {
        store.save(collectSettings())
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        store.save(store.load().copy(saveTarget = SaveTarget.FOLDER, folderUri = uri.toString()))
        Toast.makeText(this, R.string.folder_saved_toast, Toast.LENGTH_SHORT).show()
    }

    private fun updateFolderLabel() {
        folderLabel.text = folderUri?.let { getString(R.string.folder_selected, folderName(it)) }
            ?: getString(R.string.folder_none)
    }

    private fun updateLastResult() {
        lastResult.text = store.lastResult?.let { getString(R.string.last_result, it) }
            ?: getString(R.string.last_result_none)
    }

    private fun folderName(uri: String): String = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifEmpty { getString(R.string.folder_root) }
    }.getOrDefault(uri)

    private fun refreshSizes(preferred: Size?, preferredFps: Int? = selectedFps()) {
        val option = currentOption
        sizeChoices = option?.let { runCatching { catalog.videoSizes(it.cameraId) }.getOrNull() }.orEmpty()
        resolutionSpinner.adapter = adapter(sizeChoices.map { catalog.sizeLabel(it) })
        val index = preferred?.let { p ->
            sizeChoices.indexOf(p).takeIf { it >= 0 }
                ?: sizeChoices.indexOfFirst { it.width * it.height <= p.width * p.height }
        } ?: 0
        if (sizeChoices.isNotEmpty()) resolutionSpinner.setSelection(index.coerceAtLeast(0), false)
        refreshFps(preferredFps)
    }

    private fun refreshFps(preferred: Int?) {
        val option = currentOption
        val size = selectedSize()
        fpsChoices = if (option != null && size != null) {
            runCatching { catalog.frameRates(option.cameraId, size) }.getOrDefault(listOf(30))
        } else {
            listOf(30)
        }
        fpsSpinner.adapter = adapter(fpsChoices.map { getString(R.string.fps_value, it) })
        fpsSpinner.setSelection(fpsChoices.indexOf(preferred).coerceAtLeast(0), false)
    }

    private fun selectedSize(): Size? = sizeChoices.getOrNull(resolutionSpinner.selectedItemPosition)

    private fun selectedFps(): Int? = fpsChoices.getOrNull(fpsSpinner.selectedItemPosition)

    private fun updateStatus() {
        status.text = if (recordingSince > 0L) {
            getString(R.string.status_recording, VideoMath.formatElapsed(SystemClock.elapsedRealtime() - recordingSince))
        } else {
            getString(R.string.status_idle)
        }
    }

    private fun adapter(labels: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun onSelected(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backCallback != null) return
        val callback = OnBackInvokedCallback { closeSettings() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = backCallback as? OnBackInvokedCallback ?: return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        backCallback = null
    }

    // ---- 画面 ----

    /** 録画中は画面の明るさを最低にする (黒画面なので有機 EL ではほぼ消灯と同じ)。 */
    private fun setDimmed(dimmed: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val REQUEST_FOLDER = 2
        const val INDICATOR_ALPHA = 0.5f
        const val PANEL_TIMEOUT_MS = 90_000L
        const val TAP_HINT_LAUNCHES = 5
    }
}
