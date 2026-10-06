package com.okamejiiya.blackcam

import android.content.Context
import android.media.MediaRecorder

enum class Codec(val label: String, val encoder: Int, val mime: String) {
    H264("H.264 (互換性重視)", MediaRecorder.VideoEncoder.H264, "video/avc"),
    HEVC("H.265 / HEVC (省容量)", MediaRecorder.VideoEncoder.HEVC, "video/hevc"),
}

/** 動画の保存先。FOLDER は SettingsStore に保存したフォルダ (Storage Access Framework) を使う。 */
enum class SaveTarget(val label: String, val relativePath: String?) {
    DCIM("アルバム（DCIM/BlackCam）", "DCIM/BlackCam"),
    MOVIES("アルバム（ムービー/BlackCam）", "Movies/BlackCam"),
    FOLDER("自分で選んだフォルダ", null),
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
    val saveTarget: SaveTarget = SaveTarget.DCIM,
    /** saveTarget が FOLDER のときの保存先フォルダ (ツリー URI)。 */
    val folderUri: String? = null,
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
            saveTarget = prefs.getString(KEY_SAVE_TARGET, null)
                ?.let { name -> SaveTarget.entries.firstOrNull { it.name == name } } ?: d.saveTarget,
            folderUri = prefs.getString(KEY_FOLDER_URI, null),
        )
    }

    /** 直近の録画結果 (保存先やエラー)。設定画面に表示して、動画の場所を確認できるようにする。 */
    var lastResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

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
            .putString(KEY_SAVE_TARGET, s.saveTarget.name)
            .putString(KEY_FOLDER_URI, s.folderUri)
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
        const val KEY_SAVE_TARGET = "save_target"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_LAST_RESULT = "last_result"
    }
}
