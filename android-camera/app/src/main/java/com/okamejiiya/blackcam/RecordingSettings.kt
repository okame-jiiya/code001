package com.okamejiiya.blackcam

import android.content.Context
import android.media.MediaRecorder

enum class Codec(val labelRes: Int, val encoder: Int, val mime: String) {
    H264(R.string.codec_h264, MediaRecorder.VideoEncoder.H264, "video/avc"),
    HEVC(R.string.codec_hevc, MediaRecorder.VideoEncoder.HEVC, "video/hevc"),
}

/** 動画の保存先。FOLDER は SettingsStore に保存したフォルダ (Storage Access Framework) を使う。 */
enum class SaveTarget(val labelRes: Int, val relativePath: String?) {
    DCIM(R.string.save_dcim, "DCIM/BlackCam"),
    MOVIES(R.string.save_movies, "Movies/BlackCam"),
    FOLDER(R.string.save_folder, null),
}

/**
 * 保存される設定。cameraId が null のときは背面メインカメラを使う。
 * bitrateMbps が 0 のときは解像度とフレームレートから自動で決める。
 */
data class RecordingSettings(
    val cameraId: String? = null,
    /** 実際に使うズーム倍率。 */
    val zoomRatio: Float = 1f,
    /** 選んだレンズの基準倍率 (超広角 0.5 など)。同じカメラ ID の中でレンズを見分けるのに使う。 */
    val lensBase: Float = 1f,
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
    /** 録画中に小さな赤い点を出し続けるか。 */
    val showIndicator: Boolean = true,
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
            // 旧バージョンの設定には基準倍率がないので、ズーム倍率をそのまま使う
            lensBase = prefs.getFloat(KEY_LENS_BASE, prefs.getFloat(KEY_ZOOM, d.lensBase)),
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
            showIndicator = prefs.getBoolean(KEY_INDICATOR, d.showIndicator),
        )
    }

    /** この設定を保存した端末のカメラ構成の識別子。別の端末に設定が引き継がれたことの検出に使う。 */
    var deviceSignature: String?
        get() = prefs.getString(KEY_SIGNATURE, null)
        set(value) = prefs.edit().putString(KEY_SIGNATURE, value).apply()

    /** カメラに依存する設定 (カメラ・ズーム・解像度・フレームレート) だけを初期値に戻す。 */
    fun resetCameraSettings() {
        val d = RecordingSettings()
        save(
            load().copy(
                cameraId = null, zoomRatio = d.zoomRatio, lensBase = d.lensBase,
                width = d.width, height = d.height, fps = d.fps, bitrateMbps = d.bitrateMbps,
            )
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
            .putFloat(KEY_LENS_BASE, s.lensBase)
            .putInt(KEY_WIDTH, s.width)
            .putInt(KEY_HEIGHT, s.height)
            .putInt(KEY_FPS, s.fps)
            .putString(KEY_CODEC, s.codec.name)
            .putInt(KEY_BITRATE, s.bitrateMbps)
            .putBoolean(KEY_AUDIO, s.audio)
            .putBoolean(KEY_STABILIZATION, s.stabilization)
            .putString(KEY_SAVE_TARGET, s.saveTarget.name)
            .putString(KEY_FOLDER_URI, s.folderUri)
            .putBoolean(KEY_INDICATOR, s.showIndicator)
            .apply()
    }

    private companion object {
        const val KEY_CAMERA = "camera_id"
        const val KEY_ZOOM = "zoom_ratio"
        const val KEY_LENS_BASE = "lens_base"
        const val KEY_INDICATOR = "show_indicator"
        const val KEY_SIGNATURE = "device_signature"
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
