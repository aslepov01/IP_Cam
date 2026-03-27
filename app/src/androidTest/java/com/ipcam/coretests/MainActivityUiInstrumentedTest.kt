package com.ipcam.coretests

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.ipcam.MainActivity
import com.ipcam.R
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.view.View
import android.widget.Button

@RunWith(AndroidJUnit4::class)
class MainActivityUiInstrumentedTest : BaseDeviceCoreTest() {

    private fun waitUntilStartStopShowsEnabledLabel(
        scenario: ActivityScenario<MainActivity>,
        expectedLabel: String,
        timeoutMs: Long = 25_000L
    ) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            scenario.onActivity { activity ->
                val btn = activity.findViewById<Button>(R.id.startStopButton)
                ok = btn != null && btn.isEnabled && btn.text.toString() == expectedLabel
            }
            if (ok) {
                return
            }
            inst.waitForIdleSync()
            Thread.sleep(100)
        }
        throw AssertionError(
            "startStopButton did not become enabled with \"$expectedLabel\" within ${timeoutMs}ms"
        )
    }

    private fun scrollPreviewHeaderIntoViewAndTap() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val pkg = inst.targetContext.packageName
        val device = UiDevice.getInstance(inst)
        val cx = device.displayWidth / 2
        val y1 = (device.displayHeight * 0.72).toInt().coerceAtLeast(1)
        val y2 = (device.displayHeight * 0.32).toInt().coerceAtLeast(1)

        repeat(20) {
            val header = device.findObject(By.res(pkg, "previewSectionHeader"))
                ?: device.findObject(By.textContains("Camera Preview"))
            if (header != null) {
                header.click()
                inst.waitForIdleSync()
                return
            }
            device.swipe(cx, y1, cx, y2, 18)
            Thread.sleep(120)
        }
        error("previewSectionHeader not reachable after scrolling")
    }

    // Verifies the start/stop button lifecycle: the server URL must be shown, tapping stop must
    // shut down the HTTP server, and tapping start must bring it back reachable on loopback.
    @Test
    fun startStopButtonTogglesServerAndServerUrlIsShown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario: ActivityScenario<MainActivity> = env.mainActivityScenario()

        scenario.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.serverUrlText).isShown)
        }

        onView(withId(R.id.serverUrlText)).check(matches(isDisplayed()))
        onView(withId(R.id.serverUrlText)).check(matches(withText(containsString("http"))))

        // `toggleServer()` uses `CameraService.isServerRunning()`, not the button caption. After a long
        // suite the label can lag even though loopback is already up from `startFreshApp`.
        assertTrue(
            "Expected HTTP server up before toggle",
            env.isLoopbackHttpServerReachable()
        )

        onView(withId(R.id.startStopButton)).perform(click())
        env.waitUntilLoopbackHttpServerStopped()

        waitUntilStartStopShowsEnabledLabel(
            scenario,
            ctx.getString(R.string.start_server)
        )

        onView(withId(R.id.startStopButton)).perform(click())
        env.waitUntilLoopbackHttpServerReady()

        assertTrue(
            "Expected HTTP server up again after second toggle",
            env.isLoopbackHttpServerReachable()
        )
    }

    // Expanding the camera preview section must activate the camera; collapsing it must release
    // the camera back to IDLE.
    @Test
    fun previewSectionExpandCollapseMovesCameraBetweenActiveAndIdle() {
        env.mainActivityScenario()
        env.waitForCameraState("IDLE")

        scrollPreviewHeaderIntoViewAndTap()
        env.waitForCameraState("ACTIVE")

        scrollPreviewHeaderIntoViewAndTap()
        env.waitForCameraState("IDLE")
    }
}
