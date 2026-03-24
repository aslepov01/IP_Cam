package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class FlashlightAndPersistenceInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies the flashlight happy path: selecting a flash-capable camera must allow turning the
    // torch on and off through HTTP endpoints, with /status reflecting the resulting state.
    @Test
    fun flashlightEndpointsUpdateStatusOnFlashCapableCamera() {
        val camerasResponse = env.jsonGet("/cameras")
        val cameras = camerasResponse.getJSONArray("cameras")
        val flashCameraId = findCameraIdByFlashSupport(cameras, hasFlash = true)
        assumeTrue("Device does not expose a flash-capable camera", flashCameraId != null)

        env.jsonGet("/selectCamera?cameraId=${URLEncoder.encode(flashCameraId!!, "UTF-8")}")
        env.waitForSelectedCamera(flashCameraId)

        val offResponse = env.jsonGet("/flashOff")
        assertEquals("ok", offResponse.getString("status"))
        env.waitForFlashlightState(false)

        val onResponse = env.jsonGet("/flashOn")
        assertEquals("ok", onResponse.getString("status"))
        val enabledStatus = env.waitForFlashlightState(true)
        assertTrue(enabledStatus.getBoolean("flashlightAvailable"))
        assertTrue(enabledStatus.getBoolean("flashlightOn"))

        val toggleResponse = env.jsonGet("/toggleFlashlight")
        assertEquals("ok", toggleResponse.getString("status"))
        val disabledStatus = env.waitForFlashlightState(false)
        assertFalse(disabledStatus.getBoolean("flashlightOn"))
    }

    // Verifies graceful flashlight rejection: selecting a camera without flash support must cause
    // the flashlight control endpoints to fail with a clear client error instead of succeeding.
    @Test
    fun flashlightEndpointsRejectCameraWithoutFlashWhenAvailable() {
        val camerasResponse = env.jsonGet("/cameras")
        val cameras = camerasResponse.getJSONArray("cameras")
        val nonFlashCameraId = findCameraIdByFlashSupport(cameras, hasFlash = false)
        assumeTrue("Device does not expose a non-flash camera for rejection testing", nonFlashCameraId != null)

        env.jsonGet("/selectCamera?cameraId=${URLEncoder.encode(nonFlashCameraId!!, "UTF-8")}")
        val status = env.waitForSelectedCamera(nonFlashCameraId)
        assertFalse(status.getBoolean("flashlightAvailable"))

        val onResponse = env.httpGet("/flashOn", expectedCode = HttpURLConnection.HTTP_BAD_REQUEST).jsonObject()
        assertEquals("error", onResponse.getString("status"))

        val toggleResponse = env.httpGet("/toggleFlashlight", expectedCode = HttpURLConnection.HTTP_BAD_REQUEST).jsonObject()
        assertEquals("error", toggleResponse.getString("status"))
    }

    // Verifies settings persistence across a full app restart: selected camera, explicit
    // resolution, and rotation must survive service/activity teardown plus relaunch, and the
    // restored state must be visible from both /status and the initial SSE state snapshot.
    @Test
    fun cameraSelectionFormatAndRotationPersistAcrossAppRestart() {
        val camerasResponse = env.jsonGet("/cameras")
        val cameras = camerasResponse.getJSONArray("cameras")
        val initialCameraId = camerasResponse.getString("selectedCameraId")
        val expectedCameraId = findAlternateCameraId(cameras, initialCameraId) ?: initialCameraId

        val selectResponse = env.jsonGet("/selectCamera?cameraId=${URLEncoder.encode(expectedCameraId, "UTF-8")}")
        assertEquals("ok", selectResponse.getString("status"))
        env.waitForSelectedCamera(expectedCameraId)

        val formatsResponse = env.jsonGet("/formats")
        val expectedFormat = findDifferentFormatValue(
            formats = formatsResponse.getJSONArray("formats"),
            current = formatsResponse.optString("selected").takeUnless { it == "null" }
        ) ?: formatsResponse.getJSONArray("formats").getJSONObject(0).getString("value")

        val formatResponse = env.jsonGet("/setFormat?value=${URLEncoder.encode(expectedFormat, "UTF-8")}")
        assertEquals("ok", formatResponse.getString("status"))
        env.waitForSelectedResolution(expectedFormat)

        val rotationResponse = env.jsonGet("/setRotation?value=90")
        assertEquals("ok", rotationResponse.getString("status"))
        assertEquals("90", rotationResponse.getString("rotation"))

        env.restartAppPreservingState()

        val restoredStatus = env.waitForSelectedCamera(expectedCameraId)
        assertEquals(expectedFormat, restoredStatus.getString("resolution"))

        val sse = env.openSse()
        val initialPayloads = sse.awaitInitialEventPayloads()
        val statePayload = initialPayloads["state"] ?: throw AssertionError("Missing initial SSE state payload")
        val state = JSONObject(statePayload)

        assertEquals(expectedCameraId, state.getString("cameraId"))
        assertEquals(expectedFormat, state.getString("resolution"))
        assertEquals(90, state.getInt("rotation"))

        sse.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun findCameraIdByFlashSupport(cameras: JSONArray, hasFlash: Boolean): String? {
        for (index in 0 until cameras.length()) {
            val camera = cameras.getJSONObject(index)
            if (camera.getBoolean("hasFlash") == hasFlash) {
                return camera.getString("id")
            }
        }
        return null
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
}
