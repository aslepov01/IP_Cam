package com.ipcam

import android.app.ActivityManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerformanceMetricsTest {

    private lateinit var metrics: PerformanceMetrics

    @Before
    fun setup() {
        metrics = PerformanceMetrics(RuntimeEnvironment.getApplication())
        metrics.reset()
    }

    // CPU usage above the HIGH threshold (70%) but below CRITICAL (85%) must yield HIGH pressure.
    @Test
    fun cpuUsageAboveHighThresholdYieldsHighCpuPressure() {
        val p = metrics.isUnderPressure(metrics.createCpuStats(71.0))
        assertEquals(PressureLevel.HIGH, p.cpuPressure)
    }

    // CPU usage above the CRITICAL threshold (85%) must yield CRITICAL pressure.
    @Test
    fun cpuUsageAboveCriticalThresholdYieldsCriticalCpuPressure() {
        val p = metrics.isUnderPressure(metrics.createCpuStats(86.0))
        assertEquals(PressureLevel.CRITICAL, p.cpuPressure)
    }

    // Frame drop rate above 10% but below 20% triggers HIGH frame pressure.
    @Test
    fun frameDropRateAboveTenPercentYieldsHighFramePressure() {
        metrics.reset()
        repeat(100) { metrics.recordFrameProcessingTime(1L) }
        repeat(13) { metrics.recordFrameDropped() }
        val p = metrics.isUnderPressure(metrics.createCpuStats(0.0))
        assertEquals(PressureLevel.HIGH, p.framePressure)
    }

    // Frame drop rate above 20% triggers CRITICAL frame pressure.
    @Test
    fun frameDropRateAboveTwentyPercentYieldsCriticalFramePressure() {
        metrics.reset()
        repeat(70) { metrics.recordFrameProcessingTime(1L) }
        repeat(25) { metrics.recordFrameDropped() }
        val p = metrics.isUnderPressure(metrics.createCpuStats(0.0))
        assertEquals(PressureLevel.CRITICAL, p.framePressure)
    }

    // The overall pressure level must equal the worst (maximum) of all individual components.
    @Test
    fun overallPressureIsMaximumOfComponents() {
        metrics.reset()
        repeat(50) { metrics.recordFrameProcessingTime(1L) }
        repeat(5) { metrics.recordFrameDropped() }
        val p = metrics.isUnderPressure(metrics.createCpuStats(86.0))
        assertEquals(PressureLevel.CRITICAL, p.overall)
    }

    // Boundary: CPU at exactly the HIGH threshold (70%) must still be NORMAL (threshold is exclusive).
    @Test
    fun cpuUsageAtExactlySeventyPercentIsNormal() {
        val p = metrics.isUnderPressure(metrics.createCpuStats(70.0))
        assertEquals(PressureLevel.NORMAL, p.cpuPressure)
    }

    // Boundary: CPU at exactly 85% must be HIGH, not yet CRITICAL (threshold is exclusive).
    @Test
    fun cpuUsageAtExactlyEightyFivePercentIsHighNotCritical() {
        val p = metrics.isUnderPressure(metrics.createCpuStats(85.0))
        assertEquals(PressureLevel.HIGH, p.cpuPressure)
    }

    // Boundary: frame drop rate at exactly 10% must be NORMAL (threshold is exclusive).
    @Test
    fun frameDropRateAtExactlyTenPercentIsNormal() {
        metrics.reset()
        repeat(90) { metrics.recordFrameProcessingTime(1L) }
        repeat(10) { metrics.recordFrameDropped() }
        val p = metrics.isUnderPressure(metrics.createCpuStats(0.0))
        assertEquals(PressureLevel.NORMAL, p.framePressure)
    }

    // Boundary: frame drop rate at exactly 20% must be HIGH, not CRITICAL (threshold is exclusive).
    @Test
    fun frameDropRateAtExactlyTwentyPercentIsHighNotCritical() {
        metrics.reset()
        repeat(80) { metrics.recordFrameProcessingTime(1L) }
        repeat(20) { metrics.recordFrameDropped() }
        val p = metrics.isUnderPressure(metrics.createCpuStats(0.0))
        assertEquals(PressureLevel.HIGH, p.framePressure)
    }

    // Heap usage above 75% must trigger HIGH or CRITICAL memory pressure. Uses assumeTrue
    // because Robolectric JVM may not allow reaching the threshold.
    @Test
    fun memoryPressureHighWhenHeapUsageRatioCrossesSeventyFivePercent() {
        val hold = ArrayList<ByteArray>(96)
        try {
            while (metrics.getMemoryStats().heapUsageRatio <= PerformanceMetrics.MEMORY_HIGH_THRESHOLD &&
                hold.size < 96
            ) {
                hold.add(ByteArray(512 * 1024))
            }
            val ratio = metrics.getMemoryStats().heapUsageRatio
            assumeTrue("Heap did not reach HIGH threshold in this JVM (ratio=$ratio)", ratio > PerformanceMetrics.MEMORY_HIGH_THRESHOLD)
            val p = metrics.isUnderPressure(metrics.createCpuStats(0.0))
            assertTrue(
                p.memoryPressure == PressureLevel.HIGH || p.memoryPressure == PressureLevel.CRITICAL
            )
        } finally {
            hold.clear()
            System.gc()
        }
    }

    // Heap usage above 90% must trigger CRITICAL memory pressure. Uses assumeTrue
    // because Robolectric JVM may not allow reaching the threshold.
    @Test
    fun memoryPressureCriticalWhenHeapUsageRatioCrossesNinetyPercent() {
        val hold = ArrayList<ByteArray>(160)
        try {
            while (metrics.getMemoryStats().heapUsageRatio <= PerformanceMetrics.MEMORY_CRITICAL_THRESHOLD &&
                hold.size < 160
            ) {
                hold.add(ByteArray(512 * 1024))
            }
            val ratio = metrics.getMemoryStats().heapUsageRatio
            assumeTrue(
                "Heap did not reach CRITICAL threshold in this JVM (ratio=$ratio)",
                ratio > PerformanceMetrics.MEMORY_CRITICAL_THRESHOLD
            )
            val p = metrics.isUnderPressure(metrics.createCpuStats(0.0))
            assertEquals(PressureLevel.CRITICAL, p.memoryPressure)
        } finally {
            hold.clear()
            System.gc()
        }
    }

    // System-level low-memory flag from ActivityManager must trigger HIGH memory pressure
    // even if heap usage itself is below the threshold.
    @Test
    fun systemLowMemoryYieldsHighMemoryPressure() {
        val context = RuntimeEnvironment.getApplication()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().apply {
            availMem = 500_000_000L
            totalMem = 2_000_000_000L
            lowMemory = true
        }
        shadowOf(am).setMemoryInfo(info)

        metrics.reset()
        val p = metrics.isUnderPressure(metrics.createCpuStats(0.0))
        assertEquals(PressureLevel.HIGH, p.memoryPressure)
    }

    // Detailed stats text must include the exact frame counters and CPU percentage in a
    // format-anchored string, preventing loose substring matches from masking regressions.
    @Test
    fun frameCountersSurfaceInDetailedStats() {
        metrics.reset()
        metrics.recordFrameProcessingTime(5L)
        metrics.recordFrameProcessingTime(7L)
        metrics.recordFrameDropped()
        metrics.recordFrameSkipped()
        metrics.recordFrameSkipped()

        val text = metrics.getDetailedStats(metrics.createCpuStats(33.0))
        assertTrue(text.contains("Performance Metrics", ignoreCase = true))
        assertTrue(text.contains("Frames: 2 processed, 1 dropped, 2 skipped"))
        assertTrue(text.contains("33.0%"))
    }
}
