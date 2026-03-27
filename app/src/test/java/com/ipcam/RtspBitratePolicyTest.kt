package com.ipcam

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RtspBitratePolicyTest {

    // Resolutions below 480p pixel count fall to the minimum 1 Mbps bucket.
    @Test
    fun subVgaFallsBackToOneMegabit() {
        assertEquals(1_000_000, RTSPServer.calculateBitrate(320, 240))
    }

    // Standard 480p (640x480) maps to the 1.5 Mbps bucket.
    @Test
    fun sd480p640x480UsesOneAndHalfMegabits() {
        assertEquals(1_500_000, RTSPServer.calculateBitrate(640, 480))
    }

    // HD 720p (1280x720) maps to the 3 Mbps bucket.
    @Test
    fun hd720pUsesThreeMegabits() {
        assertEquals(3_000_000, RTSPServer.calculateBitrate(1280, 720))
    }

    // Full HD 1080p (1920x1080) maps to the 5 Mbps bucket.
    @Test
    fun fullHd1080pUsesFiveMegabits() {
        assertEquals(5_000_000, RTSPServer.calculateBitrate(1920, 1080))
    }

    // QHD 1440p (2560x1440) maps to the 8 Mbps bucket.
    @Test
    fun qhd1440pUsesEightMegabits() {
        assertEquals(8_000_000, RTSPServer.calculateBitrate(2560, 1440))
    }

    // UHD 4K (3840x2160) maps to the maximum 12 Mbps bucket.
    @Test
    fun uhd4kUsesTwelveMegabits() {
        assertEquals(12_000_000, RTSPServer.calculateBitrate(3840, 2160))
    }

    // Portrait 1080x1920 has the same pixel count as landscape 1080p and must map to 5 Mbps.
    @Test
    fun portraitFullHdUsesFiveMegabits() {
        assertEquals(5_000_000, RTSPServer.calculateBitrate(1080, 1920))
    }

    // Non-standard 800x600 has a pixel count between 480p and 720p and must map to 480p bucket.
    @Test
    fun nonStandard800x600FallsInto480pBucket() {
        assertEquals(1_500_000, RTSPServer.calculateBitrate(800, 600))
    }

    // Boundary: 1279x720 is one pixel column short of 720p and must fall into the 480p bucket.
    @Test
    fun justBelow720pPixelCountFallsInto480pBucket() {
        assertEquals(1_500_000, RTSPServer.calculateBitrate(1279, 720))
    }

    // Boundary: 639x480 is one pixel column short of 480p and must fall to the minimum 1 Mbps.
    @Test
    fun justBelow480pPixelCountFallsToOneMegabit() {
        assertEquals(1_000_000, RTSPServer.calculateBitrate(639, 480))
    }
}
