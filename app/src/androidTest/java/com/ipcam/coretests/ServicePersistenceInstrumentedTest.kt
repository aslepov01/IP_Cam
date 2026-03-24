package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServicePersistenceInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies background-service persistence relative to the UI: closing MainActivity must not
    // stop the running server, and the app must still serve snapshots after the activity is gone.
    @Test
    fun closingMainActivityKeepsServerReachableAndSnapshotWorking() {
        env.closeMainActivityKeepingServiceRunning()

        val status = env.loopbackStatus()
        assertEquals("running", status.getString("status"))
        assertTrue(status.getString("url").startsWith("http://"))

        val snapshot = env.waitForSnapshotReady()
        assertEquals(200, snapshot.statusCode)
        assertTrue(snapshot.body.size > 1_000)

        env.waitForCameraState("IDLE")
        env.waitForNoLongLivedConnections()
    }
}
