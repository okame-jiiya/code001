package com.okamejiiya.blackcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Camera2 + MediaRecorder でプレビューなしの録画を行う。
 *
 * すべての処理は専用スレッドで順番に実行するので、start() と stop() を短い間隔で
 * 呼んでも前の録画の保存が終わってから次の録画が始まる。
 * 動画は録画中は MediaStore に「保留中」として書き込み、終了時に公開してアルバムに出す。
 */
class VideoRecorder(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onRecordingStarted(startedAtElapsed: Long)
        fun onRecordingStopped(savedFiles: Int)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val catalog = CameraCatalog(appContext)
    private val thread = HandlerThread("VideoRecorder").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var active: Session? = null

    /** 1 回分の録画。保存先は大きなファイルを分割するため複数になることがある。 */
    private class Segment(val uri: Uri, val pfd: ParcelFileDescriptor)

    private inner class Session(val config: ResolvedConfig, val deviceOrientation: () -> Int) {
        val baseName: String = "BlackCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var segmentCount = 0
        var camera: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null
        var current: Segment? = null
        var next: Segment? = null
        var recording = false
        var finished = false
        var published = 0
    }

    fun start(settings: RecordingSettings, deviceOrientation: () -> Int) {
        handler.post {
            if (active != null) return@post
            val config = try {
                catalog.resolve(settings)
            } catch (e: Exception) {
                Log.e(TAG, "resolve failed", e)
                null
            }
            if (config == null) {
                notifyError("使用できるカメラが見つかりません")
                return@post
            }
            val session = Session(config, deviceOrientation)
            active = session
            openCamera(session)
        }
    }

    fun stop() {
        handler.post {
            val session = active ?: return@post
            active = null
            finish(session)
        }
    }

    /** 実行中の録画を保存してからスレッドを終了する。 */
    fun release() {
        stop()
        handler.post { thread.quitSafely() }
    }

    @SuppressLint("MissingPermission") // 呼び出し前に MainActivity で権限を確認している
    private fun openCamera(s: Session) {
        try {
            catalog.cameraManager.openCamera(s.config.option.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (s.finished) {
                        camera.close()
                        return
                    }
                    s.camera = camera
                    try {
                        prepareRecorder(s)
                        createSession(s, camera)
                    } catch (e: Exception) {
                        fail(s, e)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    // 他のアプリがカメラを使い始めた場合など。ここまでの録画は保存する
                    if (active === s) {
                        active = null
                        finish(s)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    fail(s, IllegalStateException("カメラエラー ($error)"))
                }
            })
        } catch (e: Exception) {
            fail(s, e)
        }
    }

    private fun prepareRecorder(s: Session) {
        val c = s.config
        val ch = catalog.characteristics(c.option.cameraId)
        val sensorOrientation = ch[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val segment = newSegment(s)
        s.current = segment

        val recorder = MediaRecorder(appContext)
        s.recorder = recorder
        recorder.apply {
            if (c.audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(segment.pfd.fileDescriptor)
            setVideoEncoder(c.codec.encoder)
            setVideoSize(c.size.width, c.size.height)
            setVideoFrameRate(c.fps)
            setVideoEncodingBitRate(c.bitrate)
            if (c.audio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioChannels(2)
                setAudioEncodingBitRate(192_000)
            }
            setOrientationHint(
                VideoMath.orientationHint(sensorOrientation, s.deviceOrientation(), c.option.frontFacing)
            )
            // MP4 は 4GB を超えると壊れやすいので、手前でファイルを切り替える
            setMaxFileSize(MAX_SEGMENT_BYTES)
            setOnInfoListener { _, what, _ -> handler.post { onRecorderInfo(s, what) } }
            setOnErrorListener { _, what, extra ->
                handler.post { fail(s, IllegalStateException("録画エラー ($what/$extra)")) }
            }
            prepare()
        }
    }

    private fun createSession(s: Session, camera: CameraDevice) {
        val surface = s.recorder!!.surface
        val c = s.config
        val ch = catalog.characteristics(c.option.cameraId)

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            if (ch[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] != null) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, c.option.zoomRatio)
            }
            catalog.fpsRange(c.option.cameraId, c.fps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val stabilizationModes =
                ch[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
            val mode = if (c.stabilization && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in stabilizationModes) {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            } else {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
        }.build()

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (s.finished) {
                        session.close()
                        return
                    }
                    s.captureSession = session
                    try {
                        session.setRepeatingRequest(request, null, handler)
                        s.recorder!!.start()
                        s.recording = true
                        val startedAt = SystemClock.elapsedRealtime()
                        mainHandler.post { listener.onRecordingStarted(startedAt) }
                    } catch (e: Exception) {
                        fail(s, e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    fail(s, IllegalStateException("この設定の組み合わせでは録画できません"))
                }
            },
        )
        // フレームレートなどをセッション作成時に HAL へ伝える
        sessionConfig.sessionParameters = request
        camera.createCaptureSession(sessionConfig)
    }

    private fun onRecorderInfo(s: Session, what: Int) {
        if (s.finished) return
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (s.next != null) return
                try {
                    val next = newSegment(s)
                    s.next = next
                    s.recorder?.setNextOutputFile(next.pfd.fileDescriptor)
                } catch (e: Exception) {
                    Log.e(TAG, "next segment failed", e)
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                s.current?.let { if (publish(it)) s.published++ }
                s.current = s.next
                s.next = null
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                // 次のファイルに切り替えられなかった場合。ここまでを保存して終了する
                if (active === s) active = null
                finish(s)
                notifyError("ファイルサイズの上限に達したため録画を終了しました")
            }
        }
    }

    private fun fail(s: Session, e: Exception) {
        Log.e(TAG, "recording failed", e)
        if (s.finished) return
        if (active === s) active = null
        finish(s)
        notifyError(e.message ?: e.javaClass.simpleName)
    }

    private fun finish(s: Session) {
        if (s.finished) return
        s.finished = true

        runCatching { s.captureSession?.stopRepeating() }
        var lastSegmentValid = false
        if (s.recording) {
            lastSegmentValid = try {
                s.recorder?.stop()
                true
            } catch (e: RuntimeException) {
                // 録画開始直後に止めた場合などはデータがなく例外になる
                Log.w(TAG, "stop failed", e)
                false
            }
        }
        runCatching { s.captureSession?.close() }
        runCatching { s.camera?.close() }
        runCatching { s.recorder?.release() }

        s.current?.let { segment ->
            if (lastSegmentValid && publish(segment)) s.published++ else discard(segment)
        }
        s.next?.let { discard(it) }
        s.current = null
        s.next = null

        val saved = s.published
        mainHandler.post { listener.onRecordingStopped(saved) }
    }

    private fun newSegment(s: Session): Segment {
        s.segmentCount++
        val name = if (s.segmentCount == 1) "${s.baseName}.mp4" else "${s.baseName}_${s.segmentCount}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BlackCam")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("保存先を作成できません")
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw") ?: error("保存先を開けません")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Segment(uri, pfd)
    }

    private fun publish(segment: Segment): Boolean = try {
        segment.pfd.close()
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        appContext.contentResolver.update(segment.uri, values, null, null)
        true
    } catch (e: Exception) {
        Log.e(TAG, "publish failed", e)
        false
    }

    private fun discard(segment: Segment) {
        runCatching { segment.pfd.close() }
        runCatching { appContext.contentResolver.delete(segment.uri, null, null) }
    }

    private fun notifyError(message: String) {
        mainHandler.post { listener.onError(message) }
    }

    private companion object {
        const val TAG = "VideoRecorder"
        const val MAX_SEGMENT_BYTES = 3_900_000_000L
    }
}
