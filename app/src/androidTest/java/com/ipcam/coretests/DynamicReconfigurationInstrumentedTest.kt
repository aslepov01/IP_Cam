package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class DynamicReconfigurationInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies MJPEG camera switching under load: an already streaming client must keep receiving
    // JPEG frames after selecting a different camera, and cleanup must still release the runtime.
    @Test
    fun cameraSwitchWhileMjpegStreamingKeepsFramesFlowingWhenMultipleCamerasExist() {
        val camerasResponse = env.jsonGet("/cameras")
        val cameras = camerasResponse.getJSONArray("cameras")
        assumeTrue("Device exposes only one camera, live camera switching cannot be tested", cameras.length() > 1)

        val selectedCameraId = camerasResponse.getString("selectedCameraId")
        val alternateCameraId = findAlternateCameraId(cameras, selectedCameraId)
        assumeTrue("Could not find an alternate camera id", alternateCameraId != null)

        val stream = env.openMjpegStream()
        assertTrue(stream.awaitFirstJpegFrame().size > 1_000)
        env.waitForCameraState("ACTIVE")

        val response = env.jsonGet("/selectCamera?cameraId=${URLEncoder.encode(alternateCameraId!!, "UTF-8")}")
        assertEquals("ok", response.getString("status"))
        assertEquals(alternateCameraId, response.getString("cameraId"))

        env.waitForSelectedCamera(alternateCameraId)
        assertTrue(stream.awaitFirstJpegFrame(timeoutMs = 30_000L).size > 1_000)

        closeMjpegStreamAndWaitForIdle(stream)
    }

    // Verifies MJPEG format reconfiguration under load: changing the selected resolution while a
    // stream is open must converge to the new format without stalling the multipart stream.
    @Test
    fun formatChangeWhileMjpegStreamingKeepsFramesFlowing() {
        val formatsResponse = env.jsonGet("/formats")
        val targetFormat = findDifferentFormatValue(
            formats = formatsResponse.getJSONArray("formats"),
            current = formatsResponse.optString("selected").takeUnless { it == "null" }
        )
        assumeTrue("Device did not expose a second format to switch to", targetFormat != null)

        val stream = env.openMjpegStream()
        assertTrue(stream.awaitFirstJpegFrame().size > 1_000)
        env.waitForCameraState("ACTIVE")

        val response = env.jsonGet("/setFormat?value=${URLEncoder.encode(targetFormat!!, "UTF-8")}")
        assertEquals("ok", response.getString("status"))

        env.waitForSelectedResolution(targetFormat)
        assertTrue(stream.awaitFirstJpegFrame(timeoutMs = 30_000L).size > 1_000)

        closeMjpegStreamAndWaitForIdle(stream)
    }

    // Verifies RTSP camera switching under load: a playing RTSP TCP session must continue
    // receiving interleaved RTP packets after selecting a different camera.
    @Test
    fun cameraSwitchWhileRtspStreamingKeepsPacketsFlowingWhenMultipleCamerasExist() {
        val camerasResponse = env.jsonGet("/cameras")
        val cameras = camerasResponse.getJSONArray("cameras")
        assumeTrue("Device exposes only one camera, live camera switching cannot be tested", cameras.length() > 1)

        val selectedCameraId = camerasResponse.getString("selectedCameraId")
        val alternateCameraId = findAlternateCameraId(cameras, selectedCameraId)
        assumeTrue("Could not find an alternate camera id", alternateCameraId != null)

        env.ensureRtspEnabled()

        val client = env.openRtspTcp()
        assertEquals(200, client.describe().statusCode)
        assertEquals(200, client.setupTcp().statusCode)
        assertEquals(200, client.play().statusCode)
        assertTrue(client.awaitInterleavedRtpPacket().isNotEmpty())

        val response = env.jsonGet("/selectCamera?cameraId=${URLEncoder.encode(alternateCameraId!!, "UTF-8")}")
        assertEquals("ok", response.getString("status"))
        env.waitForSelectedCamera(alternateCameraId)
        env.waitForRtspPlayingSessions(1)

        assertTrue(client.awaitInterleavedRtpPacket(timeoutMs = 30_000L).isNotEmpty())

        assertEquals(200, client.teardown().statusCode)
        client.close()
        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies RTSP format reconfiguration under load: changing the selected resolution while a
    // playing RTSP session is active must converge to the new format and continue RTP delivery.
    @Test
    fun formatChangeWhileRtspStreamingKeepsPacketsFlowing() {
        val formatsResponse = env.jsonGet("/formats")
        val targetFormat = findDifferentFormatValue(
            formats = formatsResponse.getJSONArray("formats"),
            current = formatsResponse.optString("selected").takeUnless { it == "null" }
        )
        assumeTrue("Device did not expose a second format to switch to", targetFormat != null)

        env.ensureRtspEnabled()

        val client = env.openRtspTcp()
        assertEquals(200, client.describe().statusCode)
        assertEquals(200, client.setupTcp().statusCode)
        assertEquals(200, client.play().statusCode)
        assertTrue(client.awaitInterleavedRtpPacket().isNotEmpty())

        val response = env.jsonGet("/setFormat?value=${URLEncoder.encode(targetFormat!!, "UTF-8")}")
        assertEquals("ok", response.getString("status"))
        env.waitForSelectedResolution(targetFormat)
        env.waitForRtspPlayingSessions(1)

        assertTrue(client.awaitInterleavedRtpPacket(timeoutMs = 30_000L).isNotEmpty())

        assertEquals(200, client.teardown().statusCode)
        client.close()
        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun findAlternateCameraId(cameras: JSONArray, selectedCameraId: String): String? {
        for (index in 0 until cameras.length()) {
            val camera = cameras.getJSONObject(index)
            if (camera.getString("id") != selectedCameraId) {
                return camera.getString("id")
            }
        }
        return null
    }

    private fun findDifferentFormatValue(formats: JSONArray, current: String?): String? {
        var fallback: String? = null
        for (index in 0 until formats.length()) {
            val value = formats.getJSONObject(index).getString("value")
            if (fallback == null) {
                fallback = value
            }
            if (value != current) {
                return value
            }
        }

        return if (fallback != null && fallback != current) fallback else null
    }

    private fun closeMjpegStreamAndWaitForIdle(stream: com.ipcam.testsupport.MjpegStreamClient) {
        stream.close()
        env.waitForNoLongLivedConnections()
        env.waitForStreamingTelemetryQuiescent()
        env.waitForCameraState("IDLE", timeoutMs = 30_000L)
    }
}
