package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection

@RunWith(AndroidJUnit4::class)
class StreamRateAndRtspConfigInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies `/setMjpegFps` and `/setRtspFps` validation: out-of-range values (0, 61 for MJPEG)
    // return HTTP 400, and in-range values apply and appear in the JSON success body.
    @Test
    fun setMjpegFpsAndSetRtspFpsRejectOutOfRangeAndAcceptValidRange() {
        val low = env.httpGet("/setMjpegFps?value=0", expectedCode = HttpURLConnection.HTTP_BAD_REQUEST).jsonObject()
        assertEquals("error", low.getString("status"))

        val high = env.httpGet("/setMjpegFps?value=61", expectedCode = HttpURLConnection.HTTP_BAD_REQUEST).jsonObject()
        assertEquals("error", high.getString("status"))

        val okMjpeg = env.jsonGet("/setMjpegFps?value=12")
        assertEquals("ok", okMjpeg.getString("status"))
        assertEquals(12, okMjpeg.getInt("targetMjpegFps"))

        val lowR = env.httpGet("/setRtspFps?value=0", expectedCode = HttpURLConnection.HTTP_BAD_REQUEST).jsonObject()
        assertEquals("error", lowR.getString("status"))

        val okRtsp = env.jsonGet("/setRtspFps?value=25")
        assertEquals("ok", okRtsp.getString("status"))
        assertEquals(25, okRtsp.getInt("targetRtspFps"))
    }

    // Verifies `/setRTSPBitrate` rejects non-positive values and `/setRTSPBitrateMode` rejects
    // unknown modes; a valid mode update succeeds and returns the applied mode in the response.
    @Test
    fun setRtspBitrateAndModeRejectInvalidValues() {
        val badBitrate = env.httpGet("/setRTSPBitrate?value=0", expectedCode = HttpURLConnection.HTTP_BAD_REQUEST).jsonObject()
        assertEquals("error", badBitrate.getString("status"))

        val badMode = env.httpGet("/setRTSPBitrateMode?value=ABR", expectedCode = HttpURLConnection.HTTP_BAD_REQUEST).jsonObject()
        assertEquals("error", badMode.getString("status"))

        val okMode = env.jsonGet("/setRTSPBitrateMode?value=VBR")
        assertEquals("ok", okMode.getString("status"))
        assertEquals("VBR", okMode.getString("bitrateMode"))
    }

    // Verifies RTSP is enabled, `/rtspStatus` reflects configured bitrate, mode, and target FPS,
    // then a playing TCP RTSP session keeps receiving RTP after live changes to bitrate, mode,
    // RTSP FPS, and MJPEG FPS; playing session count stays at one until teardown. Cleans up to
    // IDLE with no long-lived connections.
    @Test
    fun rtspEnableStatusReflectsConfigAndPlayingSessionSurvivesLiveBitrateChanges() {
        env.ensureRtspEnabled()

        env.jsonGet("/setRTSPBitrate?value=2.5")
        env.jsonGet("/setRTSPBitrateMode?value=CQ")
        env.jsonGet("/setRtspFps?value=18")

        var status = env.jsonGet("/rtspStatus")
        assertTrue(status.getBoolean("rtspEnabled"))
        assertEquals(2.5, status.getDouble("bitrateMbps"), 0.05)
        assertEquals("CQ", status.getString("bitrateMode"))
        assertEquals(18, status.getInt("targetFps"))

        val rtsp = env.openRtspTcp()
        rtsp.describe()
        rtsp.setupTcp()
        rtsp.play()
        rtsp.awaitInterleavedRtpPacket()

        env.waitForRtspPlayingSessions(1)

        env.jsonGet("/setRTSPBitrate?value=3.0")
        env.jsonGet("/setRTSPBitrateMode?value=CBR")
        env.jsonGet("/setRtspFps?value=22")
        env.jsonGet("/setMjpegFps?value=14")

        rtsp.awaitInterleavedRtpPacket(timeoutMs = 25_000L)

        status = env.jsonGet("/rtspStatus")
        assertEquals(1, status.getInt("playingSessions"))
        assertEquals(3.0, status.getDouble("bitrateMbps"), 0.05)
        assertEquals("CBR", status.getString("bitrateMode"))

        val sse = env.openSse()
        val stateJson = sse.awaitInitialEventPayloads()["state"]
        assertTrue(stateJson != null)
        val state = JSONObject(stateJson!!)
        assertEquals(14, state.getInt("targetMjpegFps"))
        assertEquals(22, state.getInt("targetRtspFps"))

        rtsp.teardown()
        rtsp.close()
        sse.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }
}
