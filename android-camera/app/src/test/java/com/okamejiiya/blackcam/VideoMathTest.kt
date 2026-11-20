package com.okamejiiya.blackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMathTest {

    @Test
    fun orientationHint_backCamera_followsDeviceRotation() {
        assertEquals(90, VideoMath.orientationHint(90, 0, frontFacing = false))
        assertEquals(180, VideoMath.orientationHint(90, 90, frontFacing = false))
        assertEquals(0, VideoMath.orientationHint(90, 270, frontFacing = false))
    }

    @Test
    fun orientationHint_frontCamera_isMirrored() {
        assertEquals(270, VideoMath.orientationHint(270, 0, frontFacing = true))
        assertEquals(180, VideoMath.orientationHint(270, 90, frontFacing = true))
    }

    @Test
    fun orientationHint_unknownOrientation_treatedAsPortrait() {
        assertEquals(90, VideoMath.orientationHint(90, -1, frontFacing = false))
    }

    @Test
    fun roundToRightAngle_roundsToNearest() {
        assertEquals(0, VideoMath.roundToRightAngle(30))
        assertEquals(90, VideoMath.roundToRightAngle(80))
        assertEquals(0, VideoMath.roundToRightAngle(350))
        assertEquals(270, VideoMath.roundToRightAngle(260))
    }

    @Test
    fun autoBitrate_hevcIsSmallerThanH264() {
        val h264 = VideoMath.autoBitrate(3840, 2160, 30, Codec.H264)
        val hevc = VideoMath.autoBitrate(3840, 2160, 30, Codec.HEVC)
        assertTrue(hevc < h264)
        assertEquals(37_324_800, h264)
    }

    @Test
    fun autoBitrate_isClamped() {
        assertEquals(2_000_000, VideoMath.autoBitrate(320, 240, 15, Codec.HEVC))
    }

    @Test
    fun equivalentFocalLength_fullFrameIsUnchanged() {
        assertEquals(50f, VideoMath.equivalentFocalLength(50f, 36f, 24f), 0.1f)
    }

    @Test
    fun lensKindAndFormat() {
        assertEquals(LensKind.ULTRA_WIDE, VideoMath.lensKind(0.5f))
        assertEquals(LensKind.MAIN, VideoMath.lensKind(1f))
        assertEquals(LensKind.ZOOM, VideoMath.lensKind(2f))
        assertEquals(LensKind.TELE, VideoMath.lensKind(5f))
        assertEquals("1", VideoMath.formatZoom(1f))
        assertEquals("0.5", VideoMath.formatZoom(0.5f))
        assertEquals("4.7", VideoMath.formatZoom(4.68f))
    }

    @Test
    fun isSameZoom_toleratesSmallDifferences() {
        assertTrue(VideoMath.isSameZoom(4.7f, 5f))
        assertFalse(VideoMath.isSameZoom(2f, 5f))
        assertFalse(VideoMath.isSameZoom(0.5f, 0.7f))
    }

    @Test
    fun formatElapsed() {
        assertEquals("01:02:03", VideoMath.formatElapsed(3_723_000L))
    }

    @Test
    fun settingsBitrate_usesExplicitValueOrAuto() {
        assertEquals(8_000_000, RecordingSettings(bitrateMbps = 8).bitrate())
        assertEquals(VideoMath.autoBitrate(1920, 1080, 30, Codec.H264), RecordingSettings().bitrate())
    }

    @Test
    fun saveTarget_defaultsToAlbumFolderAndFolderHasNoRelativePath() {
        assertEquals(SaveTarget.DCIM, RecordingSettings().saveTarget)
        assertEquals("DCIM/BlackCam", SaveTarget.DCIM.relativePath)
        assertEquals("Movies/BlackCam", SaveTarget.MOVIES.relativePath)
        assertEquals(null, SaveTarget.FOLDER.relativePath)
    }

    @Test
    fun tenMinuteSize_fromBitrate() {
        // 8 Mbps の映像 + 192 kbps の音声 = 約 8.192 Mbps → 600 秒で約 614 MB
        assertEquals(614_400_000L, VideoMath.bytesPerTenMinutes(8_000_000, audio = true))
        assertEquals(600_000_000L, VideoMath.bytesPerTenMinutes(8_000_000, audio = false))
    }

    @Test
    fun tenMinuteSize_extrapolatedFromTestClip() {
        // 5 秒で 5MB なら 10 分 (600 秒) で 600MB
        assertEquals(600_000_000L, VideoMath.extrapolateTenMinutes(5_000_000L, 5_000L))
        assertEquals(0L, VideoMath.extrapolateTenMinutes(5_000_000L, 0L))
    }

    @Test
    fun formatBytes_usesMbAndGb() {
        assertEquals("614 MB", VideoMath.formatBytes(614_400_000L))
        assertEquals("1.50 GB", VideoMath.formatBytes(1_500_000_000L))
    }

    @Test
    fun zoomBar_roundTrips() {
        assertEquals(1.0f, VideoMath.progressToZoom(0.5f, 5), 0.001f)
        assertEquals(5, VideoMath.zoomToProgress(0.5f, 1.0f))
        assertEquals(295, VideoMath.zoomSteps(0.5f, 30f))
        assertEquals(0, VideoMath.zoomSteps(1f, 1f))
    }
}
