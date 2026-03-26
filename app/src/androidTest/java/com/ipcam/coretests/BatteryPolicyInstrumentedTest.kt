package com.ipcam.coretests

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryPolicyInstrumentedTest : BaseDeviceCoreTest() {

    // Restores real battery reporting after each test so the device is not left in a mocked state.
    @After
    fun restoreBatteryReporting() {
        runCatching {
            env.executeShellCommand("cmd battery reset")
        }
        runCatching {
            env.executeShellCommand("dumpsys battery reset")
        }
    }

    // Verifies critical-battery streaming policy using shell battery simulation: service reports
    // CRITICAL_BATTERY and streaming disallowed; `/stream` serves HTML instead of MJPEG;
    // `/overrideBatteryLimit` returns error while level is at or below the critical threshold, then
    // succeeds after level is raised; `/status` stays coherent; MJPEG works again after recovery.
    // Skips via Assume if the device never enters CRITICAL_BATTERY (shell commands ineffective).
    @Test
    fun criticalBatteryBlocksMjpegStreamAndOverrideRespectsThreshold() {
        assumeTrue("Battery shell control requires API 23+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)

        runCatching { env.executeShellCommand("cmd battery unplug") }
        runCatching { env.executeShellCommand("cmd battery set level 5") }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            runCatching {
                env.executeShellCommand("dumpsys battery unplug")
                env.executeShellCommand("dumpsys battery set level 5")
            }
        }

        val criticalStatus = waitForBatteryMode("CRITICAL_BATTERY", timeoutMs = 20_000L)
        assumeNotNull(
            "Battery simulation did not drive CRITICAL_BATTERY (device may block cmd/dumpsys battery)",
            criticalStatus
        )

        assertEquals("CRITICAL_BATTERY", criticalStatus!!.getString("batteryMode"))
        assertFalse(criticalStatus.getBoolean("streamingAllowed"))

        val stream = env.httpGet("/stream", expectedCode = null, readTimeoutMs = 10_000)
        assertEquals(200, stream.statusCode)
        val ct = stream.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull().orEmpty()
        assertTrue(ct.contains("text/html", ignoreCase = true))
        assertTrue(stream.bodyText().contains("Streaming Paused", ignoreCase = true))

        val overrideLow = env.httpGet("/overrideBatteryLimit", expectedCode = 400).jsonObject()
        assertEquals("error", overrideLow.getString("status"))
        assertFalse(overrideLow.getBoolean("streamingAllowed"))

        runCatching { env.executeShellCommand("cmd battery set level 15") }
        runCatching { env.executeShellCommand("dumpsys battery set level 15") }
        Thread.sleep(800)

        val overrideOk = env.jsonGet("/overrideBatteryLimit")
        assertEquals("ok", overrideOk.getString("status"))
        assertTrue(overrideOk.getBoolean("streamingAllowed"))

        val recovered = env.loopbackStatus()
        assertTrue(recovered.getBoolean("streamingAllowed"))
        assertEquals("LOW_BATTERY", recovered.getString("batteryMode"))

        val mjpeg = env.openMjpegStream()
        assertTrue(mjpeg.awaitFirstJpegFrame().size > 1_000)
        mjpeg.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun waitForBatteryMode(mode: String, timeoutMs: Long): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val s = env.loopbackStatus()
                if (s.getString("batteryMode") == mode) {
                    return s
                }
            } catch (_: Throwable) {
                // Server may still be starting in edge cases; keep polling.
            }
            Thread.sleep(400)
        }
        return null
    }
}
