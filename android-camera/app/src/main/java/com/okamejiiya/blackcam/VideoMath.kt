package com.okamejiiya.blackcam

import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 端末やカメラ API に依存しない計算。単体テストの対象。 */
object VideoMath {

    /** フルサイズ (36x24mm) 対角線の長さ。35mm 換算焦点距離の計算に使う。 */
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f

    const val AUDIO_BITRATE = 192_000

    fun autoBitrate(width: Int, height: Int, fps: Int, codec: Codec): Int {
        // 1 画素・1 フレームあたりのビット数。HEVC は H.264 の約 6 割で同等画質になる
        val bitsPerPixel = if (codec == Codec.HEVC) 0.09 else 0.15
        val bps = width.toLong() * height * fps * bitsPerPixel
        return bps.toLong().coerceIn(2_000_000L, 200_000_000L).toInt()
    }

    /** 設定のビットレートから求める、10 分間撮影したときのファイルサイズ (バイト)。 */
    fun bytesPerTenMinutes(videoBitrate: Int, audio: Boolean): Long =
        (videoBitrate.toLong() + if (audio) AUDIO_BITRATE else 0) / 8 * 600

    /** 試し撮りの実測値 (バイト数と録画時間) から 10 分間のサイズを推定する。 */
    fun extrapolateTenMinutes(bytes: Long, durationMs: Long): Long =
        if (durationMs <= 0) 0 else (bytes.toDouble() * 600_000.0 / durationMs).toLong()

    fun formatBytes(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1000) String.format(Locale.US, "%.2f GB", mb / 1000) else String.format(Locale.US, "%.0f MB", mb)
    }

    fun format1(value: Float): String = String.format(Locale.US, "%.1f", value)

    /** 端末の向き (OrientationEventListener の値、-1 は不明) を 0/90/180/270 に丸める。 */
    fun roundToRightAngle(deviceOrientation: Int): Int =
        if (deviceOrientation < 0) 0 else ((deviceOrientation + 45) / 90 * 90) % 360

    /** MediaRecorder.setOrientationHint に渡す角度。 */
    fun orientationHint(sensorOrientation: Int, deviceOrientation: Int, frontFacing: Boolean): Int {
        val device = roundToRightAngle(deviceOrientation)
        val signed = if (frontFacing) -device else device
        return (sensorOrientation + signed + 360) % 360
    }

    fun equivalentFocalLength(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float {
        val diagonal = hypot(sensorWidthMm, sensorHeightMm)
        return if (diagonal <= 0f) focalMm else focalMm * FULL_FRAME_DIAGONAL_MM / diagonal
    }

    fun roundZoom(ratio: Float): Float = (ratio * 10f).roundToInt() / 10f

    /** 2 つの倍率がほぼ同じレンズを指すか (10% 以内)。 */
    fun isSameZoom(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) <= 0.1f * maxOf(a, b)

    /** ズームバー (0.1 倍刻み) の位置と倍率の変換。 */
    fun progressToZoom(min: Float, progress: Int): Float = roundZoom(min + progress / 10f)

    fun zoomToProgress(min: Float, zoom: Float): Int = ((zoom - min) * 10f).roundToInt().coerceAtLeast(0)

    fun zoomSteps(min: Float, max: Float): Int = ((max - min) * 10f).roundToInt().coerceAtLeast(0)

    fun lensKind(ratio: Float): LensKind = when {
        ratio < 0.85f -> LensKind.ULTRA_WIDE
        ratio < 1.5f -> LensKind.MAIN
        ratio < 4f -> LensKind.ZOOM
        else -> LensKind.TELE
    }

    /** 1.0 → "1", 0.5 → "0.5" のように倍率を表示用に整形する。 */
    fun formatZoom(ratio: Float): String {
        val rounded = roundZoom(ratio)
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    fun formatElapsed(millis: Long): String {
        val total = millis / 1000
        return "%02d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    }
}

enum class LensKind(val labelRes: Int) {
    ULTRA_WIDE(R.string.lens_ultra_wide),
    MAIN(R.string.lens_main),
    ZOOM(R.string.lens_zoom),
    TELE(R.string.lens_tele),
}
