package com.okamejiiya.blackcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Range
import android.util.Size

/** 選択肢として表示するカメラ。zoomRatio は CONTROL_ZOOM_RATIO に渡す値。 */
data class CameraOption(
    val cameraId: String,
    val zoomRatio: Float,
    val frontFacing: Boolean,
    val label: String,
)

/** 端末で実際に使える値に補正済みの録画設定。 */
data class ResolvedConfig(
    val option: CameraOption,
    val size: Size,
    val fps: Int,
    val codec: Codec,
    val bitrate: Int,
    val audio: Boolean,
    val stabilization: Boolean,
)

class CameraCatalog(context: Context) {

    val cameraManager: CameraManager = context.getSystemService(CameraManager::class.java)

    val options: List<CameraOption> by lazy { buildOptions() }

    val hevcSupported: Boolean by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(Codec.HEVC.mime, ignoreCase = true) }
        }
    }

    fun characteristics(cameraId: String): CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

    /** 4K / 1080p / 720p のうち、そのカメラで録画できるもの (大きい順)。 */
    fun videoSizes(cameraId: String): List<Size> {
        val map = characteristics(cameraId)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?: return emptyList()
        val available = map.getOutputSizes(MediaRecorder::class.java)?.toSet() ?: return emptyList()
        return PRESET_SIZES.filter { it in available }
    }

    /** 30 / 60 fps のうち、そのカメラ・解像度で使えるもの。 */
    fun frameRates(cameraId: String, size: Size): List<Int> {
        val ch = characteristics(cameraId)
        val ranges = ch[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES] ?: return listOf(30)
        val minFrameNanos = ch[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?.getOutputMinFrameDuration(MediaRecorder::class.java, size) ?: 0L
        val result = PRESET_FPS.filter { fps ->
            ranges.any { it.upper == fps } && (minFrameNanos == 0L || minFrameNanos <= 1_000_000_000L / fps)
        }
        return result.ifEmpty { listOf(30) }
    }

    fun fpsRange(cameraId: String, fps: Int): Range<Int>? {
        val ranges = characteristics(cameraId)[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
            ?: return null
        // 固定フレームレート (例: 30-30) を優先し、なければ上限が一致する中で下限が最も高いもの
        return ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
    }

    /** 保存されている設定を、この端末で実際に使える値に置き換える。 */
    fun resolve(settings: RecordingSettings): ResolvedConfig? {
        val all = options
        if (all.isEmpty()) return null
        val option = all.firstOrNull {
            it.cameraId == settings.cameraId && VideoMath.isSameZoom(it.zoomRatio, settings.zoomRatio)
        } ?: all.first()

        val sizes = videoSizes(option.cameraId)
        val size = sizes.firstOrNull { it.width == settings.width && it.height == settings.height }
            ?: sizes.firstOrNull { it.width * it.height <= settings.width * settings.height }
            ?: sizes.lastOrNull()
            ?: Size(1280, 720)

        val fpsChoices = frameRates(option.cameraId, size)
        val fps = if (settings.fps in fpsChoices) settings.fps else fpsChoices.first()
        val codec = if (settings.codec == Codec.HEVC && !hevcSupported) Codec.H264 else settings.codec
        val corrected = settings.copy(width = size.width, height = size.height, fps = fps, codec = codec)

        return ResolvedConfig(
            option = option,
            size = size,
            fps = fps,
            codec = codec,
            bitrate = corrected.bitrate(),
            audio = settings.audio,
            stabilization = settings.stabilization,
        )
    }

    private fun buildOptions(): List<CameraOption> {
        val ids = cameraManager.cameraIdList.toList()
        val chars = ids.associateWith { cameraManager.getCameraCharacteristics(it) }
        val usable = ids.filter { id ->
            val caps = chars.getValue(id)[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps
        }
        // 論理マルチカメラを構成する物理カメラが別 ID でも公開されている場合は二重に出さない
        val memberIds = usable.flatMap { chars.getValue(it).physicalCameraIds }.toSet()

        val back = usable.filter { facing(chars.getValue(it)) == CameraMetadata.LENS_FACING_BACK }
            .sortedByDescending { isLogicalMulti(chars.getValue(it)) }
        val front = usable.filter { facing(chars.getValue(it)) == CameraMetadata.LENS_FACING_FRONT }
        val external = usable.filter { facing(chars.getValue(it)) == CameraMetadata.LENS_FACING_EXTERNAL }

        val result = mutableListOf<CameraOption>()
        val backReference = back.firstOrNull()?.let { equivalentFocal(chars.getValue(it)) }

        back.filter { it == back.first() || it !in memberIds }.forEachIndexed { index, id ->
            val ch = chars.getValue(id)
            val prefix = if (index == 0) "背面" else "背面${index + 1}"
            // 別 ID の背面カメラは、主カメラに対する画角の比でレンズの種類を判断する
            val idScale = if (index == 0 || backReference == null) 1f
            else equivalentFocal(ch)?.let { it / backReference } ?: 1f
            zoomRatios(ch).forEach { zoom ->
                val lensRatio = zoom * idScale
                result += CameraOption(
                    cameraId = id,
                    zoomRatio = zoom,
                    frontFacing = false,
                    label = "$prefix ${VideoMath.lensKind(lensRatio).label} ${VideoMath.formatZoom(lensRatio)}x",
                )
            }
        }
        front.filter { it == front.firstOrNull() || it !in memberIds }.forEachIndexed { index, id ->
            val prefix = if (index == 0) "前面" else "前面${index + 1}"
            zoomRatios(chars.getValue(id)).forEach { zoom ->
                val suffix = if (zoom == 1f) "" else " ${VideoMath.formatZoom(zoom)}x"
                result += CameraOption(id, zoom, frontFacing = true, label = prefix + suffix)
            }
        }
        external.forEachIndexed { index, id ->
            result += CameraOption(id, 1f, frontFacing = false, label = "外部カメラ${index + 1}")
        }
        return result
    }

    /**
     * そのカメラ ID で選べるレンズの倍率。
     * Pixel の背面カメラは 1 つの論理カメラにまとめられていて、ズーム倍率に応じて
     * 超広角・メイン・望遠のレンズが切り替わるため、レンズごとの倍率を求めて選択肢にする。
     */
    private fun zoomRatios(ch: CameraCharacteristics): List<Float> {
        val range = ch[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] ?: return listOf(1f)
        val ratios = mutableListOf(1f)
        fun add(ratio: Float) {
            val clamped = VideoMath.roundZoom(ratio.coerceIn(range.lower, range.upper))
            if (ratios.none { VideoMath.isSameZoom(it, clamped) }) ratios += clamped
        }

        // 背面は Pixel の標準カメラと同じ 0.5x / 2x / 5x を先に入れる (レンズ切り替えの閾値と揃えるため)
        if (facing(ch) == CameraMetadata.LENS_FACING_BACK) {
            if (range.lower < 0.95f) add(range.lower)
            for (preset in listOf(2f, 5f)) if (preset <= range.upper) add(preset)
        }
        // 物理レンズが公開されていれば、その画角から倍率を計算して足りないものを補う
        val base = equivalentFocal(ch)
        if (base != null) {
            for (physicalId in ch.physicalCameraIds) {
                val physical = runCatching { cameraManager.getCameraCharacteristics(physicalId) }.getOrNull() ?: continue
                val ratio = equivalentFocal(physical)?.div(base) ?: continue
                if (ratio < range.lower * 0.9f || ratio > range.upper) continue
                add(ratio)
            }
        }
        return ratios.sorted()
    }

    private fun equivalentFocal(ch: CameraCharacteristics): Float? {
        val focal = ch[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return null
        val sensor = ch[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
        return VideoMath.equivalentFocalLength(focal, sensor.width, sensor.height)
    }

    private fun facing(ch: CameraCharacteristics): Int? = ch[CameraCharacteristics.LENS_FACING]

    private fun isLogicalMulti(ch: CameraCharacteristics): Boolean {
        val caps = ch[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: return false
        return CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
    }

    companion object {
        val PRESET_SIZES = listOf(Size(3840, 2160), Size(1920, 1080), Size(1280, 720))
        val PRESET_FPS = listOf(30, 60)

        fun sizeLabel(size: Size): String = when {
            size.width >= 3840 -> "4K (${size.width}×${size.height})"
            size.width >= 1920 -> "フルHD (${size.width}×${size.height})"
            else -> "HD (${size.width}×${size.height})"
        }
    }
}
