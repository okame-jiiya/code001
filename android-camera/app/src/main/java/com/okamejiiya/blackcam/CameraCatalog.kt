package com.okamejiiya.blackcam

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import java.security.MessageDigest

/** 端末のカメラ 1 台分の性能。設定画面でユーザーに見せる情報の元になる。 */
data class CameraSpec(
    val cameraId: String,
    val facing: Int?,
    val focalMm: Float?,
    /** 35mm 判換算焦点距離 (メインレンズ基準)。 */
    val equivFocalMm: Float?,
    val aperture: Float?,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val megapixels: Float?,
    val hasOis: Boolean,
    val hasEis: Boolean,
    val zoomMin: Float,
    val zoomMax: Float,
    /** true: CONTROL_ZOOM_RATIO が使える。false: 切り抜き (SCALER_CROP_REGION) でズームする。 */
    val usesZoomRatio: Boolean,
    val maxIso: Int?,
    val longestExposureMs: Float?,
    val maxVideo: Size?,
    val max60fps: Boolean,
    val physicalCount: Int,
    val hardwareLevel: Int?,
)

/** 選択肢として表示するカメラ (レンズ)。zoomRatio はそのレンズの基準倍率。 */
data class CameraOption(
    val cameraId: String,
    val zoomRatio: Float,
    val frontFacing: Boolean,
    val label: String,
)

/** 端末で実際に使える値に補正済みの録画設定。 */
data class ResolvedConfig(
    val option: CameraOption,
    val zoom: Float,
    val size: Size,
    val fps: Int,
    val codec: Codec,
    val bitrate: Int,
    val audio: Boolean,
    val stabilization: Boolean,
)

class CameraCatalog(private val context: Context) {

    val cameraManager: CameraManager = context.getSystemService(CameraManager::class.java)

    private val specs = mutableMapOf<String, CameraSpec>()

    val options: List<CameraOption> by lazy { buildOptions() }

    val hevcSupported: Boolean by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(Codec.HEVC.mime, ignoreCase = true) }
        }
    }

    /** この端末のカメラ構成を表す識別子。設定が別の端末から引き継がれたかどうかの判定に使う。 */
    val signature: String by lazy {
        val parts = mutableListOf("${Build.MANUFACTURER}|${Build.MODEL}")
        for (id in runCatching { cameraManager.cameraIdList.sorted() }.getOrDefault(emptyList())) {
            val ch = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val pixels = ch[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
            parts += "$id:${ch[CameraCharacteristics.LENS_FACING]}:${pixels?.width}x${pixels?.height}"
        }
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString(";").toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    fun characteristics(cameraId: String): CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

    fun specOf(cameraId: String): CameraSpec = specs.getOrPut(cameraId) { buildSpec(cameraId) }

    /** 4K / フルHD など代表的な解像度のうち、そのカメラで録画できるもの (大きい順)。 */
    fun videoSizes(cameraId: String): List<Size> {
        val map = characteristics(cameraId)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?: return emptyList()
        val available = map.getOutputSizes(MediaRecorder::class.java)?.toSet() ?: return emptyList()
        val presets = PRESET_SIZES.filter { it in available }
        if (presets.isNotEmpty()) return presets
        // 標準的な解像度が 1 つもない端末では、使える中で大きいものを数個出す
        return available.filter { it.height >= 480 }.sortedByDescending { it.width * it.height }.take(3)
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

    /** ズーム倍率・フレームレート・手ぶれ補正など、録画とテスト撮影に共通のリクエスト設定。 */
    fun applyCommon(builder: CaptureRequest.Builder, cameraId: String, zoom: Float, fps: Int, stabilization: Boolean) {
        val ch = characteristics(cameraId)
        val spec = specOf(cameraId)
        if (spec.usesZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else if (zoom > 1f) {
            ch[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(it, zoom)) }
        }
        fpsRange(cameraId, fps)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val modes = ch[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        val mode = if (stabilization && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in modes) {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        } else {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
    }

    /** エンコーダーが受け付ける最大ビットレート。取得できなければ null。 */
    fun maxBitrate(codec: Codec): Int? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(codec.mime, ignoreCase = true) } }
            .mapNotNull { it.getCapabilitiesForType(codec.mime).videoCapabilities?.bitrateRange?.upper }
            .maxOrNull()
    }.getOrNull()

    /** 保存されている設定を、この端末で実際に使える値に置き換える。 */
    fun resolve(settings: RecordingSettings): ResolvedConfig? {
        val all = options
        if (all.isEmpty()) return null
        val option = all.firstOrNull {
            it.cameraId == settings.cameraId && VideoMath.isSameZoom(it.zoomRatio, settings.lensBase)
        } ?: all.first()
        val spec = specOf(option.cameraId)
        val zoom = if (option.cameraId == settings.cameraId) {
            settings.zoomRatio.coerceIn(spec.zoomMin, spec.zoomMax)
        } else {
            option.zoomRatio
        }

        val sizes = videoSizes(option.cameraId)
        val size = sizes.firstOrNull { it.width == settings.width && it.height == settings.height }
            ?: sizes.firstOrNull { it.width * it.height <= settings.width * settings.height }
            ?: sizes.lastOrNull()
            ?: Size(1280, 720)

        val fpsChoices = frameRates(option.cameraId, size)
        val fps = if (settings.fps in fpsChoices) settings.fps else fpsChoices.first()
        val codec = if (settings.codec == Codec.HEVC && !hevcSupported) Codec.H264 else settings.codec
        val corrected = settings.copy(width = size.width, height = size.height, fps = fps, codec = codec)
        val bitrate = maxBitrate(codec)?.let { corrected.bitrate().coerceAtMost(it) } ?: corrected.bitrate()

        return ResolvedConfig(
            option = option,
            zoom = zoom,
            size = size,
            fps = fps,
            codec = codec,
            bitrate = bitrate,
            audio = settings.audio,
            stabilization = settings.stabilization,
        )
    }

    /** 設定画面に出す、そのレンズの性能の説明 (複数行)。 */
    fun describe(option: CameraOption): String {
        val spec = specOf(option.cameraId)
        val lines = mutableListOf<String>()
        lines += context.getString(R.string.spec_id, option.cameraId)
        if (spec.focalMm != null) {
            val equiv = spec.equivFocalMm?.let { VideoMath.format1(it * option.zoomRatio) } ?: "?"
            lines += context.getString(
                R.string.spec_lens, VideoMath.format1(spec.focalMm * option.zoomRatio), equiv,
                spec.aperture?.let { VideoMath.format1(it) } ?: "?",
            )
        }
        if (spec.sensorWidthMm != null && spec.sensorHeightMm != null) {
            lines += context.getString(
                R.string.spec_sensor, VideoMath.format1(spec.sensorWidthMm), VideoMath.format1(spec.sensorHeightMm),
                spec.megapixels?.let { VideoMath.format1(it) } ?: "?",
            )
        }
        lines += context.getString(R.string.spec_zoom, VideoMath.formatZoom(spec.zoomMin), VideoMath.formatZoom(spec.zoomMax))
        val stabilization = when {
            spec.hasOis && spec.hasEis -> R.string.stab_both
            spec.hasOis -> R.string.stab_ois
            spec.hasEis -> R.string.stab_eis
            else -> R.string.stab_none
        }
        lines += context.getString(R.string.spec_stabilization, context.getString(stabilization))
        if (spec.maxIso != null) {
            lines += context.getString(
                R.string.spec_lowlight, spec.maxIso.toString(),
                spec.longestExposureMs?.let { VideoMath.format1(it) } ?: "?",
            )
        }
        spec.maxVideo?.let {
            lines += context.getString(
                R.string.spec_video, sizeLabel(it), if (spec.max60fps) "60" else "30",
            )
        }
        if (spec.physicalCount > 1) lines += context.getString(R.string.spec_multi, spec.physicalCount)
        spec.hardwareLevel?.let { lines += context.getString(R.string.spec_level, levelName(it)) }
        return lines.joinToString("\n")
    }

    fun sizeLabel(size: Size): String = when {
        size.width >= 7680 -> context.getString(R.string.size_8k, size.width, size.height)
        size.width >= 3840 -> context.getString(R.string.size_4k, size.width, size.height)
        size.width >= 2560 -> context.getString(R.string.size_qhd, size.width, size.height)
        size.width >= 1920 -> context.getString(R.string.size_fhd, size.width, size.height)
        size.width >= 1280 -> context.getString(R.string.size_hd, size.width, size.height)
        else -> context.getString(R.string.size_other, size.width, size.height)
    }

    private fun levelName(level: Int): String = context.getString(
        when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> R.string.level_legacy
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> R.string.level_limited
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> R.string.level_full
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> R.string.level_3
            else -> R.string.level_external
        }
    )

    private fun buildSpec(cameraId: String): CameraSpec {
        val ch = characteristics(cameraId)
        val focal = ch[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()
        val sensor = ch[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val pixels = ch[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
        val oisModes = ch[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf()
        val eisModes = ch[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        val zoomRange = ch[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
        val maxDigital = ch[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f
        val sizes = runCatching { videoSizes(cameraId) }.getOrDefault(emptyList())
        val top = sizes.firstOrNull()
        return CameraSpec(
            cameraId = cameraId,
            facing = ch[CameraCharacteristics.LENS_FACING],
            focalMm = focal,
            equivFocalMm = if (focal != null && sensor != null) {
                VideoMath.equivalentFocalLength(focal, sensor.width, sensor.height)
            } else {
                null
            },
            aperture = ch[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.firstOrNull(),
            sensorWidthMm = sensor?.width,
            sensorHeightMm = sensor?.height,
            megapixels = pixels?.let { it.width.toLong() * it.height / 1_000_000f },
            hasOis = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes,
            hasEis = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes,
            zoomMin = zoomRange?.lower ?: 1f,
            zoomMax = zoomRange?.upper ?: maxDigital,
            usesZoomRatio = zoomRange != null,
            maxIso = ch[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.upper,
            longestExposureMs = ch[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.upper?.let { it / 1_000_000f },
            maxVideo = top,
            max60fps = top != null && 60 in runCatching { frameRates(cameraId, top) }.getOrDefault(emptyList()),
            physicalCount = ch.physicalCameraIds.size,
            hardwareLevel = ch[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL],
        )
    }

    private fun cropRegion(active: Rect, zoom: Float): Rect {
        val w = (active.width() / zoom).toInt()
        val h = (active.height() / zoom).toInt()
        val left = active.left + (active.width() - w) / 2
        val top = active.top + (active.height() - h) / 2
        return Rect(left, top, left + w, top + h)
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
        val backReference = back.firstOrNull()?.let { specOf(it).equivFocalMm }

        back.filter { it == back.first() || it !in memberIds }.forEachIndexed { index, id ->
            val prefix = if (index == 0) context.getString(R.string.camera_back)
            else context.getString(R.string.camera_back_n, index + 1)
            // 別 ID の背面カメラは、主カメラに対する画角の比でレンズの種類を判断する
            val idScale = if (index == 0 || backReference == null) 1f
            else specOf(id).equivFocalMm?.let { it / backReference } ?: 1f
            lensRatios(id).forEach { zoom ->
                val lensRatio = zoom * idScale
                result += CameraOption(
                    cameraId = id,
                    zoomRatio = zoom,
                    frontFacing = false,
                    label = context.getString(
                        R.string.camera_option_label, prefix,
                        context.getString(VideoMath.lensKind(lensRatio).labelRes), VideoMath.formatZoom(lensRatio),
                    ),
                )
            }
        }
        front.filter { it == front.firstOrNull() || it !in memberIds }.forEachIndexed { index, id ->
            val prefix = if (index == 0) context.getString(R.string.camera_front)
            else context.getString(R.string.camera_front_n, index + 1)
            lensRatios(id).forEach { zoom ->
                val label = if (zoom == 1f) prefix else context.getString(R.string.camera_front_zoom, prefix, VideoMath.formatZoom(zoom))
                result += CameraOption(id, zoom, frontFacing = true, label = label)
            }
        }
        external.forEachIndexed { index, id ->
            result += CameraOption(id, 1f, frontFacing = false, label = context.getString(R.string.camera_external, index + 1))
        }
        // 背面・前面・外部のどれにも分類できないカメラしかない端末でも、1 つは選べるようにする
        if (result.isEmpty()) {
            usable.firstOrNull()?.let { result += CameraOption(it, 1f, frontFacing = false, label = context.getString(R.string.camera_other, it)) }
        }
        return result
    }

    /**
     * そのカメラ ID で選べるレンズの基準倍率。
     * Pixel など多くの端末の背面カメラは 1 つの論理カメラにまとめられていて、ズーム倍率に応じて
     * 超広角・メイン・望遠のレンズが切り替わるため、レンズごとの倍率を求めて選択肢にする。
     */
    private fun lensRatios(cameraId: String): List<Float> {
        val ch = characteristics(cameraId)
        val spec = specOf(cameraId)
        val ratios = mutableListOf(1f)
        fun add(ratio: Float) {
            val clamped = VideoMath.roundZoom(ratio.coerceIn(spec.zoomMin, spec.zoomMax))
            if (ratios.none { VideoMath.isSameZoom(it, clamped) }) ratios += clamped
        }

        // 物理レンズが複数ある論理カメラだけ、レンズごとの倍率を選択肢にする
        if (spec.physicalCount > 1 && spec.usesZoomRatio) {
            if (spec.zoomMin < 0.95f) add(spec.zoomMin)
            // 物理レンズが公開されていれば、その画角から倍率を計算する
            val base = spec.equivFocalMm
            if (base != null) {
                for (physicalId in ch.physicalCameraIds) {
                    val physical = runCatching { cameraManager.getCameraCharacteristics(physicalId) }.getOrNull() ?: continue
                    val focal = physical[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: continue
                    val size = physical[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: continue
                    val ratio = VideoMath.equivalentFocalLength(focal, size.width, size.height) / base
                    if (ratio < spec.zoomMin * 0.9f || ratio > spec.zoomMax) continue
                    add(ratio)
                }
            }
        }
        return ratios.sorted()
    }

    private fun facing(ch: CameraCharacteristics): Int? = ch[CameraCharacteristics.LENS_FACING]

    private fun isLogicalMulti(ch: CameraCharacteristics): Boolean {
        val caps = ch[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: return false
        return CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
    }

    companion object {
        val PRESET_SIZES = listOf(
            Size(7680, 4320), Size(3840, 2160), Size(2560, 1440), Size(1920, 1080), Size(1280, 720),
        )
        val PRESET_FPS = listOf(30, 60)
    }
}
