package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RebootEndpointInstrumentedTest : BaseDeviceCoreTest() {

    /**
     * On a non–Device Owner device, /reboot must respond with 403 and a JSON body describing
     * the missing Device Owner capability. Successful reboot is not exercised.
     *
     * Scenarios that require Device Owner or a locked keyguard are intentionally omitted
     * (see project test plan).
     */
    @Test
    fun rebootEndpointReturnsForbiddenWhenNotDeviceOwner() {
        val rebootDiag = env.jsonGet("/diagnostics/reboot").getJSONObject("diagnostics")
        assumeTrue(
            "Test targets devices where the app is not Device Owner",
            !rebootDiag.getBoolean("isDeviceOwner")
        )

        val response = env.httpGet("/reboot", expectedCode = null)
        assertEquals(403, response.statusCode)
        val j = JSONObject(response.bodyText())
        assertEquals("error", j.getString("status"))
        assertEquals("not_device_owner", j.getString("error"))
        assertTrue(j.getString("message").contains("Device Owner", ignoreCase = true))
    }
}
