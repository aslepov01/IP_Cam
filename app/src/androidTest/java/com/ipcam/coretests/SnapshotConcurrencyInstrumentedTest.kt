package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ipcam.testsupport.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SnapshotConcurrencyInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies concurrent `/snapshot` callers: five worker threads (staggered start) each retry
    // until a 200 JPEG response, so all succeed without wedging the server; afterward the camera
    // is IDLE and telemetry shows no lingering camera clients.
    @Test
    fun parallelSnapshotRequestsReturnJpegAndCameraReturnsToIdleAfterLastCompletes() {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (1..5).map { index ->
                pool.submit(
                    Callable {
                        Thread.sleep((index - 1) * 150L)
                        pollSnapshotUntilJpegReady()
                    }
                )
            }

            val responses = futures.map { it.get(120, TimeUnit.SECONDS) }
            for (response in responses) {
                assertEquals(200, response.statusCode)
                assertJpegPayload(response)
            }
        } finally {
            pool.shutdownNow()
        }

        env.waitForCameraState("IDLE")
        env.waitForMetrics(description = "snapshot concurrency cleanup") {
            it.optInt("totalCameraClients", -1) == 0
        }
    }

    private fun pollSnapshotUntilJpegReady(): HttpResponse {
        val deadline = System.currentTimeMillis() + 90_000L
        var last: HttpResponse? = null
        while (System.currentTimeMillis() < deadline) {
            last = env.httpGet("/snapshot", expectedCode = null, readTimeoutMs = 45_000)
            if (last.statusCode == 200 && last.body.size > 1_000) {
                return last
            }
            Thread.sleep(400)
        }
        throw AssertionError(
            "Snapshot did not return JPEG in time; last status=${last?.statusCode} body=${last?.bodyText()?.take(120)}"
        )
    }

    // Verifies `/snapshot` while an MJPEG `/stream` is active: snapshot returns a valid JPEG, the
    // multipart stream keeps delivering frames afterward, the camera stays ACTIVE until the MJPEG
    // client disconnects, then returns to IDLE.
    @Test
    fun snapshotReturnsJpegWhileMjpegStreamRemainsActive() {
        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()
        env.waitForCameraState("ACTIVE")

        val snap = env.waitForSnapshotReady()
        assertJpegPayload(snap)

        val afterSnap = mjpeg.awaitFirstJpegFrame()
        assertTrue(afterSnap.size > 1_000)

        env.waitForCameraState("ACTIVE")

        mjpeg.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies `/snapshot` while RTSP is in PLAYING: snapshot returns JPEG, RTP keeps flowing,
    // playing session count and ACTIVE state persist until RTSP teardown, then full cleanup to IDLE.
    @Test
    fun snapshotReturnsJpegWhileRtspSessionRemainsPlaying() {
        env.ensureRtspEnabled()
        val rtsp = env.openRtspTcp()
        rtsp.describe()
        rtsp.setupTcp()
        rtsp.play()
        rtsp.awaitInterleavedRtpPacket()
        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")

        val snap = env.waitForSnapshotReady()
        assertJpegPayload(snap)

        rtsp.awaitInterleavedRtpPacket(timeoutMs = 20_000L)
        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")

        rtsp.teardown()
        rtsp.close()
        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun assertJpegPayload(response: HttpResponse) {
        assertTrue(response.body.size > 1_000)
        assertEquals(0xFF.toByte(), response.body[0])
        assertEquals(0xD8.toByte(), response.body[1])
    }
}
