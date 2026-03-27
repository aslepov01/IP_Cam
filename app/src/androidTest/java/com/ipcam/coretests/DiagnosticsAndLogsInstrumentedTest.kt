package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsAndLogsInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies /diagnostics/camera JSON at idle: required fields, types, and plausible values.
    @Test
    fun cameraDiagnosticsCoherentAtIdle() {
        env.waitForCameraState("IDLE")

        val body = env.httpGet("/diagnostics/camera")
        assertEquals(200, body.statusCode)
        val ct = body.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull().orEmpty()
        assertTrue("Expected JSON content type, got $ct", ct.contains("json", ignoreCase = true))

        val j = body.jsonObject()
        assertEquals("ok", j.getString("status"))
        assertTrue(j.getString("cameraState").isNotEmpty())
        assertEquals(0, j.getInt("consumerCount"))
        assertFalse(j.getBoolean("hasLastFrame"))
        assertEquals(0, j.getInt("lastFrameSizeBytes"))
        assertTrue(j.getDouble("currentFps") >= 0.0)
        assertTrue(j.getBoolean("permissionGranted"))
        assertEquals(0, j.getInt("mjpegClients"))
        assertEquals(0, j.getInt("rtspClients"))
        assertNotNull(j.get("rtspEnabled"))
        assertTrue(j.getString("serverUrl").startsWith("http://"))
        assertNotNull(j.getString("deviceName"))
    }

    // Verifies /diagnostics/camera reflects an active MJPEG consumer and stays read-only (GET).
    @Test
    fun cameraDiagnosticsCoherentWithActiveMjpegStream() {
        env.waitForCameraState("IDLE")
        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()
        env.waitForCameraState("ACTIVE")

        val j = env.jsonGet("/diagnostics/camera")
        assertEquals("ok", j.getString("status"))
        assertEquals("ACTIVE", j.getString("cameraState"))
        assertTrue(j.getInt("consumerCount") >= 1)
        assertTrue(j.getInt("mjpegClients") >= 1)
        assertTrue(j.getBoolean("hasLastFrame"))
        assertTrue(j.getInt("lastFrameSizeBytes") > 0)

        mjpeg.close()
        env.waitForCameraState("IDLE")
        env.waitForStreamingTelemetryQuiescent()
    }

    // Verifies /diagnostics/reboot exposes capability fields without attempting reboot.
    @Test
    fun rebootDiagnosticsAreNonDestructiveAndStructured() {
        val body = env.httpGet("/diagnostics/reboot")
        assertEquals(200, body.statusCode)
        val j = body.jsonObject()
        assertEquals("ok", j.getString("status"))
        val d = j.getJSONObject("diagnostics")

        d.getBoolean("isDeviceOwner")
        d.getBoolean("isDeviceAdmin")
        d.getBoolean("isDeviceLocked")
        assertTrue(d.getString("deviceManufacturer").isNotEmpty())
        assertTrue(d.getString("deviceModel").isNotEmpty())
        assertTrue(d.getInt("androidVersion") > 0)
        assertTrue(d.getString("androidVersionName").isNotEmpty())
        assertTrue(d.getString("selinuxStatus").isNotEmpty())
        if (!d.isNull("knoxVersion")) {
            assertTrue(d.getString("knoxVersion").isNotEmpty())
        }
        val rebootPossible = d.getBoolean("rebootPossible")
        if (rebootPossible) {
            assertTrue("blockingReason should be null when reboot is possible", d.isNull("blockingReason"))
        } else {
            assertFalse("blockingReason should be present when reboot is blocked", d.isNull("blockingReason"))
            val reason = d.getString("blockingReason")
            assertTrue(
                "Blocking reason should explain why: $reason",
                reason.contains("Device Owner", ignoreCase = true) ||
                    reason.contains("locked", ignoreCase = true) ||
                    reason.contains("Knox", ignoreCase = true)
            )
        }
    }

    // Verifies /logs returns plain text with RTSP entries and correct line format.
    // RTSP auto-starts ~2s after service creation, so we wait for it rather than
    // calling /enableRTSP which would race with the auto-start coroutine.
    @Test
    fun logsEndpointReturnsPlainTextWithRecentLinesAfterRtspEnable() {
        val initialResponse = env.httpGet("/logs")
        assertEquals(200, initialResponse.statusCode)
        val ct = initialResponse.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull().orEmpty()
        assertTrue("Expected text/plain, got $ct", ct.contains("text/plain", ignoreCase = true))

        // Wait for RTSP auto-start to complete and log entries to appear
        var text = ""
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            text = env.httpGet("/logs").bodyText()
            if (text.contains("RTSP", ignoreCase = true)) break
            Thread.sleep(500)
        }

        assertFalse("Log body should not be blank after RTSP startup", text.isBlank())
        assertTrue("Logs should mention RTSP after server auto-start", text.contains("RTSP", ignoreCase = true))

        val linePattern = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} [IWED]/[^:]+: .+""")
        val formatted = text.lines().filter { it.isNotBlank() && !it.startsWith("No log") }
        assertTrue("Expected at least one formatted log line", formatted.isNotEmpty())
        val firstMismatch = formatted.firstOrNull { !linePattern.matches(it) }
        assertTrue(
            "All log lines should match buffer format, first mismatch: $firstMismatch",
            formatted.all { linePattern.matches(it) }
        )
    }
}
