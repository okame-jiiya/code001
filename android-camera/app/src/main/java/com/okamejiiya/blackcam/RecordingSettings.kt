package com.okamejiiya.blackcam

import android.content.Context
import android.media.MediaRecorder

enum class Codec(val label: String, val encoder: Int, val mime: String) {
    H264("H.264 (互換性重視)", MediaRecorder.VideoEncoder.H264, "video/avc"),
    HEVC("H.265 / HEVC (省容量)", MediaRecorder.VideoEncoder.HEVC, "video/hevc"),
}

/**
 * 保存される設定。cameraId が null のときは背面メインカメラを使う。
 * bitrateMbps が 0 のときは解像度とフレームレートから自動で決める。
 */
data class RecordingSettings(
    val cameraId: String? = null,
    val zoomRatio: Float = 1f,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val codec: Codec = Codec.H264,
    val bitrateMbps: Int = 0,
    val audio: Boolean = true,
    val stabilization: Boolean = true,
) {
    fun bitrate(): Int =
        if (bitrateMbps > 0) bitrateMbps * 1_000_000 else VideoMath.autoBitrate(width, height, fps, codec)

    companion object {
        val BITRATE_CHOICES_MBPS = listOf(0, 4, 8, 12, 16, 24, 40, 60, 100)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("recording_settings", Context.MODE_PRIVATE)

    fun load(): RecordingSettings {
        val d = RecordingSettings()
        return RecordingSettings(
            cameraId = prefs.getString(KEY_CAMERA, null),
            zoomRatio = prefs.getFloat(KEY_ZOOM, d.zoomRatio),
            width = prefs.getInt(KEY_WIDTH, d.width),
            height = prefs.getInt(KEY_HEIGHT, d.height),
            fps = prefs.getInt(KEY_FPS, d.fps),
            codec = prefs.getString(KEY_CODEC, null)
                ?.let { name -> Codec.entries.firstOrNull { it.name == name } } ?: d.codec,
            bitrateMbps = prefs.getInt(KEY_BITRATE, d.bitrateMbps),
            audio = prefs.getBoolean(KEY_AUDIO, d.audio),
            stabilization = prefs.getBoolean(KEY_STABILIZATION, d.stabilization),
        )
    }

    fun save(s: RecordingSettings) {
        prefs.edit()
            .putString(KEY_CAMERA, s.cameraId)
            .putFloat(KEY_ZOOM, s.zoomRatio)
            .putInt(KEY_WIDTH, s.width)
            .putInt(KEY_HEIGHT, s.height)
            .putInt(KEY_FPS, s.fps)
            .putString(KEY_CODEC, s.codec.name)
            .putInt(KEY_BITRATE, s.bitrateMbps)
            .putBoolean(KEY_AUDIO, s.audio)
            .putBoolean(KEY_STABILIZATION, s.stabilization)
            .apply()
    }

    private companion object {
        const val KEY_CAMERA = "camera_id"
        const val KEY_ZOOM = "zoom_ratio"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_FPS = "fps"
        const val KEY_CODEC = "codec"
        const val KEY_BITRATE = "bitrate_mbps"
        const val KEY_AUDIO = "audio"
        const val KEY_STABILIZATION = "stabilization"
    }
}
