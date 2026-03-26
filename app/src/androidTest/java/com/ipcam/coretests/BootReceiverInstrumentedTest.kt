package com.ipcam.coretests

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ipcam.BootReceiver
import com.ipcam.CameraService
import com.ipcam.testsupport.DeviceTestEnvironment
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Exercises `BootReceiver` by calling `onReceive` directly (no full device reboot): autostart
// preference in CE and device-protected storage vs whether the HTTP server comes up, matching the
// receiver's boot/QuickBoot intent handling.
@RunWith(AndroidJUnit4::class)
class BootReceiverInstrumentedTest {

    private lateinit var env: DeviceTestEnvironment
    private val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        env = DeviceTestEnvironment()
        env.shutdownAndReset()
        repeat(3) {
            runCatching {
                appContext.stopService(Intent(appContext, CameraService::class.java))
            }
            Thread.sleep(750)
        }
    }

    @After
    fun tearDown() {
        env.shutdownAndReset()
    }

    // Verifies `autoStartServer=false` in prefs: after simulating `BOOT_COMPLETED`, loopback HTTP
    // must stay down; prefs read back false in the same storage context the receiver uses.
    @Test
    fun bootReceiverDoesNotStartServiceWhenAutoStartDisabled() {
        env.grantRuntimePermissionsWithoutActivity()
        writeAutoStartPreference(enabled = false)

        val dp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.createDeviceProtectedStorageContext()
        } else {
            appContext
        }
        assertFalse(
            "autoStartServer must be false in prefs BootReceiver reads",
            dp.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_START, true)
        )

        assertFalse(
            "Precondition: CameraService HTTP must be stopped before boot simulation",
            runCatching { env.httpGet("/status", readTimeoutMs = 2_000) }.isSuccess
        )

        BootReceiver().onReceive(appContext, Intent(Intent.ACTION_BOOT_COMPLETED))

        Thread.sleep(4_000)
        val httpStillUp = runCatching {
            env.httpGet("/status", readTimeoutMs = 3_000)
        }.isSuccess
        assertFalse(
            "HTTP server must not be reachable when autostart is disabled",
            httpStillUp
        )
    }

    // Verifies `autoStartServer=true`: after `BOOT_COMPLETED`, the app must listen on HTTP and
    // `/status` reports a running server (foreground service autostart path).
    @Test
    fun bootReceiverStartsForegroundServiceWhenAutoStartEnabled() {
        env.grantRuntimePermissionsWithoutActivity()
        writeAutoStartPreference(enabled = true)

        BootReceiver().onReceive(appContext, Intent(Intent.ACTION_BOOT_COMPLETED))

        waitForLoopbackHttp(timeoutMs = 60_000L)
        val status = env.jsonGet("/status")
        assertTrue(status.getString("status") == "running")
    }

    // Verifies on API 24+ that device-protected `autoStartServer` wins over credential-encrypted
    // prefs: CE true but DP false must not start the HTTP server after boot intent.
    @Test
    fun bootReceiverUsesDeviceProtectedAutoStartPreferenceOnDirectBootCapableDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            org.junit.Assume.assumeTrue("Device-protected storage split requires API 24+", false)
        }

        env.grantRuntimePermissionsWithoutActivity()

        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START, true)
            .commit()

        val deviceProtected = appContext.createDeviceProtectedStorageContext()
        deviceProtected.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START, false)
            .commit()

        BootReceiver().onReceive(appContext, Intent(Intent.ACTION_BOOT_COMPLETED))

        Thread.sleep(4_000)
        assertFalse(env.isLoopbackHttpServerReachable())
    }

    private fun writeAutoStartPreference(enabled: Boolean) {
        val ce = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .commit()
        check(ce) { "Failed to commit autoStart to CE prefs" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dp = appContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_START, enabled)
                .commit()
            check(dp) { "Failed to commit autoStart to device-protected prefs" }
        }
    }

    private fun waitForLoopbackHttp(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (env.isLoopbackHttpServerReachable()) {
                return
            }
            Thread.sleep(500)
        }
        throw AssertionError("CameraService did not open HTTP port after BootReceiver autostart")
    }

    companion object {
        private const val PREFS = "IPCamSettings"
        private const val KEY_AUTO_START = "autoStartServer"
    }
}
