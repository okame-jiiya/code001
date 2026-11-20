package com.okamejiiya.blackcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executor

/** 試し撮りの結果。 */
data class TestResult(
    val file: File,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val durationMs: Long,
    /** 実測値から推定した、10 分間撮影したときのファイルサイズ (音声を含む)。 */
    val tenMinuteBytes: Long,
    val videoBitrateMbps: Float,
    /** 録画した映像の平均の明るさ (0〜255)。取り出せなければ null。 */
    val averageLuma: Int?,
    /** 撮影時にカメラが使った露出 (Exif に相当する情報)。 */
    val iso: Int?,
    val exposureNs: Long?,
    val aperture: Float?,
    val focalMm: Float?,
)

/**
 * 保存済みの画質設定で数秒だけ録画して、ファイルサイズ・明るさ・露出を測る。
 * 動画ファイルには Exif がないので、露出は撮影中のカメラの撮影結果 (CaptureResult) から読み取る。
 */
class CameraTester(context: Context) {

    private val appContext = context.applicationContext
    private val catalog = CameraCatalog(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 呼び出しごとに専用のスレッドで実行し、終わったら callback (メインスレッド) を呼ぶ。 */
    @SuppressLint("MissingPermission") // 呼び出し前に画面側で権限を確認している
    fun run(settings: RecordingSettings, durationMs: Long, callback: (TestResult?, String?) -> Unit) {
        val thread = HandlerThread("CameraTester").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val out = File(appContext.cacheDir, TEST_FILE)
        var finished = false
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null
        var iso: Int? = null
        var exposure: Long? = null
        var aperture: Float? = null
        var focal: Float? = null

        fun cleanup() {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { recorder?.release() }
            thread.quitSafely()
        }

        fun finishWith(result: TestResult?, error: String?) {
            if (finished) return
            finished = true
            cleanup()
            mainHandler.post { callback(result, error) }
        }

        handler.post {
            val config = runCatching { catalog.resolve(settings) }.getOrNull()
            if (config == null) {
                finishWith(null, appContext.getString(R.string.err_no_camera))
                return@post
            }
            try {
                catalog.cameraManager.openCamera(config.option.cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        try {
                            recorder = prepare(config, out)
                            val surface = recorder!!.surface
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                catalog.applyCommon(this, config.option.cameraId, config.zoom, config.fps, config.stabilization)
                            }.build()
                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(OutputConfiguration(surface)),
                                executor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
                                        session = configured
                                        try {
                                            configured.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult,
                                                ) {
                                                    // 最後の値 (自動露出が落ち着いた後) を使う
                                                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
                                                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposure = it }
                                                    result.get(CaptureResult.LENS_APERTURE)?.let { aperture = it }
                                                    result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { focal = it }
                                                }
                                            }, handler)
                                            recorder!!.start()
                                            handler.postDelayed({ complete(config, out, durationMs, recorder!!, ::finishWith, { iso }, { exposure }, { aperture }, { focal }) }, durationMs)
                                        } catch (e: Exception) {
                                            finishWith(null, e.message ?: e.javaClass.simpleName)
                                        }
                                    }

                                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                                        finishWith(null, appContext.getString(R.string.err_config))
                                    }
                                },
                            )
                            sessionConfig.sessionParameters = request
                            device.createCaptureSession(sessionConfig)
                        } catch (e: Exception) {
                            finishWith(null, e.message ?: e.javaClass.simpleName)
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        finishWith(null, appContext.getString(R.string.err_camera, -1))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        finishWith(null, appContext.getString(R.string.err_camera, error))
                    }
                })
            } catch (e: Exception) {
                finishWith(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun complete(
        config: ResolvedConfig,
        file: File,
        durationMs: Long,
        recorder: MediaRecorder,
        finish: (TestResult?, String?) -> Unit,
        iso: () -> Int?,
        exposure: () -> Long?,
        aperture: () -> Float?,
        focal: () -> Float?,
    ) {
        try {
            recorder.stop()
        } catch (e: RuntimeException) {
            finish(null, appContext.getString(R.string.err_test_short))
            return
        }
        // 後片づけ (カメラを閉じる) は finish 側で行う。ここでは先に動画を解析する
        val analysis = analyze(file)
        val bytes = file.length()
        val actualMs = analysis.durationMs.takeIf { it > 0 } ?: durationMs
        val videoPart = VideoMath.extrapolateTenMinutes(bytes, actualMs)
        val audioPart = if (config.audio) VideoMath.AUDIO_BITRATE.toLong() / 8 * 600 else 0L
        finish(
            TestResult(
                file = file,
                width = config.size.width,
                height = config.size.height,
                bytes = bytes,
                durationMs = actualMs,
                tenMinuteBytes = videoPart + audioPart,
                videoBitrateMbps = if (actualMs > 0) bytes * 8f / (actualMs / 1000f) / 1_000_000f else 0f,
                averageLuma = analysis.luma,
                iso = iso(),
                exposureNs = exposure(),
                aperture = aperture(),
                focalMm = focal(),
            ),
            null,
        )
    }

    private class Analysis(val durationMs: Long, val luma: Int?)

    private fun analyze(file: File): Analysis {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = retriever.getScaledFrameAtTime(
                duration * 500, MediaMetadataRetriever.OPTION_CLOSEST, 160, 160,
            )
            Analysis(duration, frame?.let { averageLuma(it) })
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
            Analysis(0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun averageLuma(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return if (pixels.isEmpty()) 0 else (sum / pixels.size).toInt()
    }

    private fun prepare(config: ResolvedConfig, file: File): MediaRecorder {
        val ch = catalog.characteristics(config.option.cameraId)
        val sensorOrientation = ch[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
        try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file.absolutePath)
                setVideoEncoder(config.codec.encoder)
                setVideoSize(config.size.width, config.size.height)
                setVideoFrameRate(config.fps)
                setVideoEncodingBitRate(config.bitrate)
                setOrientationHint(VideoMath.orientationHint(sensorOrientation, 0, config.option.frontFacing))
                prepare()
            }
        } catch (e: Exception) {
            runCatching { recorder.release() }
            throw e
        }
        return recorder
    }

    companion object {
        private const val TAG = "CameraTester"
        private const val TEST_FILE = "camera_test.mp4"
    }
}
