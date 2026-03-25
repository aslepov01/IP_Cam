package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayConfigurationInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies that all four OSD overlay endpoints accept truthy and falsy values, reject invalid
    // inputs with 400, and return the applied flag in the response body.
    @Test
    fun overlayEndpointsToggleFlagsAndRejectInvalidValues() {
        val overlays = listOf(
            OverlaySpec("/setDateTimeOverlay", "showDateTimeOverlay"),
            OverlaySpec("/setBatteryOverlay", "showBatteryOverlay"),
            OverlaySpec("/setResolutionOverlay", "showResolutionOverlay"),
            OverlaySpec("/setFpsOverlay", "showFpsOverlay")
        )

        for (spec in overlays) {
            // Truthy values
            for (value in listOf("true", "1", "yes")) {
                val response = env.jsonGet("${spec.endpoint}?value=$value")
                assertEquals("ok", response.getString("status"))
                assertTrue("${spec.statusField} should be true in response after value=$value",
                    response.getBoolean(spec.statusField))
            }

            // Falsy values
            for (value in listOf("false", "0", "no")) {
                val response = env.jsonGet("${spec.endpoint}?value=$value")
                assertEquals("ok", response.getString("status"))
                assertFalse("${spec.statusField} should be false in response after value=$value",
                    response.getBoolean(spec.statusField))
            }

            // Null / missing value defaults to false
            val nullResponse = env.jsonGet(spec.endpoint)
            assertEquals("ok", nullResponse.getString("status"))
            assertFalse(nullResponse.getBoolean(spec.statusField))

            // Invalid value
            val badResponse = env.httpGet("${spec.endpoint}?value=maybe", expectedCode = 400)
            assertEquals("error", badResponse.jsonObject().getString("status"))
        }
    }

    // Verifies that OSD overlay flags appear in the initial SSE state event with correct values.
    @Test
    fun overlayFlagsExposedInSseInitialState() {
        // Set a known state: all overlays on
        env.jsonGet("/setDateTimeOverlay?value=true")
        env.jsonGet("/setBatteryOverlay?value=true")
        env.jsonGet("/setResolutionOverlay?value=true")
        env.jsonGet("/setFpsOverlay?value=true")

        val sse = env.openSse()
        val payloads = sse.awaitInitialEventPayloads()

        assertNotNull("Expected SSE state event", payloads["state"])
        val state = JSONObject(payloads["state"]!!)

        assertTrue(state.getBoolean("showDateTimeOverlay"))
        assertTrue(state.getBoolean("showBatteryOverlay"))
        assertTrue(state.getBoolean("showResolutionOverlay"))
        assertTrue(state.getBoolean("showFpsOverlay"))

        sse.close()
        env.waitForNoLongLivedConnections()

        // Restore defaults
        env.jsonGet("/setDateTimeOverlay?value=false")
        env.jsonGet("/setBatteryOverlay?value=false")
        env.jsonGet("/setResolutionOverlay?value=false")
        env.jsonGet("/setFpsOverlay?value=false")
    }

    private data class OverlaySpec(val endpoint: String, val statusField: String)
}
