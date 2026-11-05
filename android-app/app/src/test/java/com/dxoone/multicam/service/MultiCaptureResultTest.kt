package com.dxoone.multicam.service

import com.dxoone.multicam.usb.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCaptureResultTest {

    private fun result(
        vararg cameras: CaptureResult,
        error: String? = null,
        mode: CaptureMode = CaptureMode.PARALLEL
    ) = MultiCaptureResult(
        sessionId = "s",
        totalCameras = cameras.size,
        results = cameras.toList(),
        allSucceeded = cameras.isNotEmpty() && cameras.all { it.success },
        totalTimeMs = 900,
        mode = mode,
        error = error
    )

    private fun ok(id: String, writtenAt: Long?) =
        CaptureResult(cameraId = id, success = true, triggerWrittenAtNanos = writtenAt)

    private fun failed(id: String, writtenAt: Long?) =
        CaptureResult(cameraId = id, success = false, error = "x", triggerWrittenAtNanos = writtenAt)

    @Test
    fun spreadIsMaxMinusMinOfSuccessfulWriteTimes() {
        val r = result(ok("a", 1_000_000), ok("b", 1_420_000), ok("c", 1_100_000))
        assertEquals(420_000L, r.triggerSpreadNanos)
        assertEquals("0.42 ms", r.triggerSpreadText)
        assertEquals("Captured 3 photos (trigger spread, host-side: 0.42 ms)", r.summaryText())
    }

    @Test
    fun failedCamerasAndMissingTimestampsAreIgnored() {
        val r = result(ok("a", 5_000_000), failed("b", 900_000_000), ok("c", null), ok("d", 7_500_000))
        assertEquals(2_500_000L, r.triggerSpreadNanos)
        assertFalse(r.allSucceeded)
        assertEquals("Captured 3/4 photos (trigger spread, host-side: 2.50 ms)", r.summaryText())
    }

    @Test
    fun noSpreadWithFewerThanTwoTimestamps() {
        val single = result(ok("a", 1_000))
        assertNull(single.triggerSpreadNanos)
        assertNull(single.triggerSpreadText)
        assertEquals("Captured 1 photo", single.summaryText())

        val oneFailed = result(ok("a", 1_000), failed("b", 2_000))
        assertNull(oneFailed.triggerSpreadNanos)
        assertEquals("Captured 1/2 photos", oneFailed.summaryText())
    }

    @Test
    fun errorSummaryNeverMentionsTiming() {
        val r = result(failed("a", null), error = "No cameras ready for capture")
        assertEquals("Capture failed: No cameras ready for capture", r.summaryText())
        assertTrue(r.results.none { it.success })
    }

    @Test
    fun sequentialModeReportsNoSpread() {
        val r = result(ok("a", 1_000_000), ok("b", 200_000_000), mode = CaptureMode.SEQUENTIAL)
        assertNull(r.triggerSpreadNanos)
        assertNull(r.triggerSpreadText)
    }
}
