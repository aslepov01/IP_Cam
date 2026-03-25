package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class CameraConfigurationInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies camera catalog integrity and selection validation: /cameras must report a real
    // selected camera, and selecting an unknown camera id must be rejected with an error.
    @Test
    fun camerasEndpointReportsSelectedCameraAndRejectsUnknownSelection() {
        val camerasResponse = env.jsonGet("/cameras")
        val cameras = camerasResponse.getJSONArray("cameras")
        val selectedCameraId = camerasResponse.optString("selectedCameraId")

        assertTrue("Expected at least one camera in /cameras response", cameras.length() > 0)
        assertTrue("Expected selectedCameraId to match one of the cameras", containsCameraId(cameras, selectedCameraId))

        val invalidId = "non-existent-camera-id"
        val invalidSelection = env.httpGet(
            "/selectCamera?cameraId=${URLEncoder.encode(invalidId, "UTF-8")}",
            expectedCode = HttpURLConnection.HTTP_BAD_REQUEST
        ).jsonObject()

        assertEquals("error", invalidSelection.getString("status"))
    }

    // Verifies camera switching on devices with multiple cameras: selecting a different camera
    // should update runtime state and keep snapshot capture working after the switch.
    @Test
    fun selectingAlternateCameraKeepsSnapshotWorkingWhenMultipleCamerasExist() {
        val camerasResponse = env.jsonGet("/cameras")
        val cameras = camerasResponse.getJSONArray("cameras")
        assumeTrue("Device exposes only one camera, alternate camera selection cannot be tested", cameras.length() > 1)

        val selectedCameraId = camerasResponse.getString("selectedCameraId")
        val alternateCameraId = findAlternateCameraId(cameras, selectedCameraId)
        assumeTrue("Could not find an alternate camera id", alternateCameraId != null)

        val selectResponse = env.jsonGet("/selectCamera?cameraId=${URLEncoder.encode(alternateCameraId!!, "UTF-8")}")
        assertEquals("ok", selectResponse.getString("status"))
        assertEquals(alternateCameraId, selectResponse.getString("cameraId"))

        val statusAfterSwitch = env.loopbackStatus()
        assertEquals(alternateCameraId, statusAfterSwitch.getString("cameraId"))

        val snapshot = env.waitForSnapshotReady()
        assertEquals(200, snapshot.statusCode)
        assertTrue("Snapshot should still return JPEG bytes after camera switch", snapshot.body.size > 1_000)

        env.waitForCameraState("IDLE")
    }

    // Verifies format selection and connection limit normalization: supported formats can be
    // applied, invalid numeric limits are clamped, and the camera still serves snapshots after it.
    @Test
    fun formatsAndConnectionLimitEndpointsApplyAndNormalizeValues() {
        val formats = env.jsonGet("/formats")
        val availableFormats = formats.getJSONArray("formats")

        assertTrue("Expected /formats to expose at least one supported format", availableFormats.length() > 0)

        val firstFormatValue = availableFormats.getJSONObject(0).getString("value")
        val setFormatResponse = env.jsonGet("/setFormat?value=${URLEncoder.encode(firstFormatValue, "UTF-8")}")
        assertEquals("ok", setFormatResponse.getString("status"))

        val normalizedLimits = env.setConnectionLimits(
            mjpegStreams = 0,
            sseClients = 101,
            rtspSessions = -4
        ).getJSONObject("connectionLimits")

        assertEquals(1, normalizedLimits.getInt("maxMjpegStreams"))
        assertEquals(100, normalizedLimits.getInt("maxSseClients"))
        assertEquals(1, normalizedLimits.getInt("maxRtspSessions"))

        val status = env.loopbackStatus().getJSONObject("connectionLimits")
        assertEquals(1, status.getInt("maxMjpegStreams"))
        assertEquals(100, status.getInt("maxSseClients"))
        assertEquals(1, status.getInt("maxRtspSessions"))

        val snapshot = env.waitForSnapshotReady()
        assertEquals(200, snapshot.statusCode)
        assertTrue(snapshot.body.size > 1_000)

        env.waitForCameraState("IDLE")
    }

    // Verifies /setCameraOrientation and /setRotation endpoints accept valid values, reject
    // invalid ones with 400, and reflect changes in the SSE state event.
    @Test
    fun orientationAndRotationEndpointsValidateAndApply() {
        // Valid orientation values — verified through the response body
        val portrait = env.jsonGet("/setCameraOrientation?value=portrait")
        assertEquals("ok", portrait.getString("status"))
        assertEquals("portrait", portrait.getString("cameraOrientation"))

        val landscape = env.jsonGet("/setCameraOrientation?value=landscape")
        assertEquals("ok", landscape.getString("status"))
        assertEquals("landscape", landscape.getString("cameraOrientation"))

        // Invalid orientation returns 400
        val badOrientation = env.httpGet("/setCameraOrientation?value=diagonal", expectedCode = 400)
        assertEquals("error", badOrientation.jsonObject().getString("status"))

        // Valid rotation values — each returns the new value in the response
        for (angle in listOf(0, 90, 180, 270)) {
            val response = env.jsonGet("/setRotation?value=$angle")
            assertEquals("ok", response.getString("status"))
            assertEquals(angle.toString(), response.getString("rotation"))
        }

        // Verify SSE state delta captures rotation change
        val sse = env.openSse()
        sse.awaitInitialEventPayloads()
        env.jsonGet("/setRotation?value=90")
        val delta = sse.awaitEventOfType("state", timeoutMs = 10_000L)
        assertNotNull("Expected SSE delta after /setRotation", delta)
        val deltaJson = JSONObject(delta!!)
        assertEquals(90, deltaJson.getInt("rotation"))
        sse.close()
        env.waitForNoLongLivedConnections()

        // Invalid rotation values return 400
        val badRotation = env.httpGet("/setRotation?value=45", expectedCode = 400)
        assertEquals("error", badRotation.jsonObject().getString("status"))

        val badRotation2 = env.httpGet("/setRotation?value=abc", expectedCode = 400)
        assertEquals("error", badRotation2.jsonObject().getString("status"))

        // Restore defaults
        env.jsonGet("/setRotation?value=0")
        env.jsonGet("/setCameraOrientation?value=landscape")
    }

    // Verifies /setFormat edge cases: auto-reset on blank/missing value, overlong value rejection,
    // and unsupported resolution rejection with alternatives in the error message.
    @Test
    fun setFormatAutoResetAndEdgeCases() {
        val formats = env.jsonGet("/formats")
        val availableFormats = formats.getJSONArray("formats")
        assumeTrue("Expected at least one format", availableFormats.length() > 0)

        // Set a specific format first
        val firstFormat = availableFormats.getJSONObject(0).getString("value")
        env.jsonGet("/setFormat?value=${URLEncoder.encode(firstFormat, "UTF-8")}")
        assertEquals(firstFormat, env.loopbackStatus().getString("resolution"))

        // Auto-reset with no value parameter
        val autoReset = env.jsonGet("/setFormat")
        assertEquals("ok", autoReset.getString("status"))
        assertEquals("auto", env.loopbackStatus().getString("resolution"))

        // Set a format again, then reset with empty value
        env.jsonGet("/setFormat?value=${URLEncoder.encode(firstFormat, "UTF-8")}")
        val emptyReset = env.jsonGet("/setFormat?value=")
        assertEquals("ok", emptyReset.getString("status"))
        assertEquals("auto", env.loopbackStatus().getString("resolution"))

        // Overlong value
        val overlong = "a".repeat(33)
        val overlongResponse = env.httpGet("/setFormat?value=$overlong", expectedCode = 400)
        assertTrue(overlongResponse.jsonObject().getString("message").contains("too long", ignoreCase = true))

        // Unsupported resolution
        val unsupported = env.httpGet("/setFormat?value=9999x9999", expectedCode = 400)
        val unsupportedJson = unsupported.jsonObject()
        assertEquals("error", unsupportedJson.getString("status"))
        assertTrue("Error should mention available formats",
            unsupportedJson.getString("message").contains("Available", ignoreCase = true))

        // Invalid format string
        val invalid = env.httpGet("/setFormat?value=notaresolution", expectedCode = 400)
        assertEquals("error", invalid.jsonObject().getString("status"))
    }

    private fun containsCameraId(cameras: JSONArray, selectedCameraId: String): Boolean {
        for (index in 0 until cameras.length()) {
            if (cameras.getJSONObject(index).getString("id") == selectedCameraId) {
                return true
            }
        }
        return false
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
}
