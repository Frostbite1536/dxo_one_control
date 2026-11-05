package com.dxoone.multicam.usb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Trigger timestamps ([DxoOneUsbProtocol.sendCommand]'s beforeWrite / onWritten) and the
 * [CaptureBarrier] start line, against fake cameras. This is the transport half of a
 * PARALLEL capture; CameraConnection.takePhoto only wires the barrier into beforeWrite.
 */
class CaptureSyncProtocolTest {

    private val started = CopyOnWriteArrayList<DxoOneUsbProtocol>()

    private suspend fun connected(vararg fakes: FakeUsbBulkIo): List<DxoOneUsbProtocol> =
        fakes.map { io -> DxoOneUsbProtocol(io).also { started += it; it.initialize() } }

    @After
    fun stopAll() {
        started.forEach { it.stop() }
    }

    @Test
    fun onWrittenIsAfterTheWriteAndBeforeTheReply() = runBlocking {
        val cam = FakeUsbBulkIo(replyDelayMs = { 200 }, writeDurationMs = 2)
        val p = connected(cam).single()
        var written: Long? = null
        var writtenBeforeReply = false
        val r = p.sendCommand(DxoOneMethods.PHOTO_TAKE, onWritten = {
            written = it
            writtenBeforeReply = cam.replyAt[DxoOneMethods.PHOTO_TAKE] == null
        })
        assertTrue(r.isSuccess)
        assertTrue(writtenBeforeReply)
        assertNotNull(written)
        assertTrue(written!! >= cam.writeEnd.getValue(DxoOneMethods.PHOTO_TAKE))
        assertTrue(written!! < cam.replyAt.getValue(DxoOneMethods.PHOTO_TAKE))
    }

    @Test
    fun beforeWriteRunsBeforeTheRequestIsWritten() = runBlocking {
        val cam = FakeUsbBulkIo()
        val p = connected(cam).single()
        var writtenWhenCalled: Boolean? = null
        val r = p.sendCommand(DxoOneMethods.PHOTO_TAKE, beforeWrite = {
            writtenWhenCalled = cam.writeStart.containsKey(DxoOneMethods.PHOTO_TAKE)
        })
        assertTrue(r.isSuccess)
        assertEquals(false, writtenWhenCalled)
    }

    @Test
    fun onWrittenNotCalledWhenWriteFails() = runBlocking {
        val cam = FakeUsbBulkIo()
        val p = connected(cam).single()
        cam.shortWrite = true
        var called = false
        try {
            p.sendCommand(DxoOneMethods.PHOTO_TAKE, onWritten = { called = true })
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(called)
    }

    @Test
    fun barrierReleasesAllWritesTogether() = runBlocking {
        // Camera k is busy with a status command that replies after 60*k ms, so without the
        // barrier their photo_take writes would start ~180 ms apart.
        val fakes = (0 until 4).map { k ->
            FakeUsbBulkIo(replyDelayMs = { m -> if (m == DxoOneMethods.CAMERA_STATUS_GET) 60L * k else 50L })
        }
        val cams = connected(*fakes.toTypedArray())
        cams.forEach { launch(Dispatchers.IO) { it.sendCommand(DxoOneMethods.CAMERA_STATUS_GET) } }
        delay(20)

        val barrier = CaptureBarrier(cams.size, timeoutMs = 1_000)
        val writtenAt = cams.map { p ->
            async(Dispatchers.IO) {
                var at: Long? = null
                val r = p.sendCommand(
                    DxoOneMethods.PHOTO_TAKE,
                    beforeWrite = { assertTrue(barrier.arriveAndAwait()) },
                    onWritten = { at = it }
                )
                assertTrue(r.isSuccess)
                at!!
            }
        }.awaitAll()

        assertFalse(barrier.isBroken)
        val starts = fakes.map { it.writeStart.getValue(DxoOneMethods.PHOTO_TAKE) }
        val startSpreadMs = (starts.max() - starts.min()) / 1e6
        val reportedSpreadMs = (writtenAt.max() - writtenAt.min()) / 1e6
        assertTrue("writes should start together, got ${startSpreadMs}ms", startSpreadMs < 50)
        assertTrue("reported spread ${reportedSpreadMs}ms", reportedSpreadMs < 50)
        writtenAt.zip(fakes).forEach { (at, f) ->
            assertTrue(at >= f.writeEnd.getValue(DxoOneMethods.PHOTO_TAKE))
            assertTrue(at < f.replyAt.getValue(DxoOneMethods.PHOTO_TAKE))
        }
    }

    @Test
    fun barrierTimeoutBoundsTheWaitForABusyCamera() = runBlocking {
        val busy = FakeUsbBulkIo(replyDelayMs = { m -> if (m == DxoOneMethods.CAMERA_STATUS_GET) 1_500L else 10L })
        val quick = FakeUsbBulkIo()
        val (busyP, quickP) = connected(busy, quick)
        launch(Dispatchers.IO) { busyP.sendCommand(DxoOneMethods.CAMERA_STATUS_GET) }
        delay(20)

        val barrier = CaptureBarrier(2, timeoutMs = 300)
        val t0 = System.nanoTime()
        val results = listOf(busyP, quickP).map { p ->
            async(Dispatchers.IO) {
                p.sendCommand(DxoOneMethods.PHOTO_TAKE, beforeWrite = { barrier.arriveAndAwait() })
            }
        }.awaitAll()
        assertTrue(results.all { it.isSuccess })
        assertTrue(barrier.isBroken)
        val quickAt = (quick.writeStart.getValue(DxoOneMethods.PHOTO_TAKE) - t0) / 1e6
        val busyAt = (busy.writeStart.getValue(DxoOneMethods.PHOTO_TAKE) - t0) / 1e6
        assertTrue("quick wrote at ${quickAt}ms", quickAt in 250.0..800.0)
        assertTrue("busy wrote at ${busyAt}ms", busyAt >= 1_400)
    }

    @Test
    fun closedTransportNeverReachesTheBarrier() = runBlocking {
        // CameraConnection.takePhoto leaves the barrier when beforeWrite was never called, so
        // a camera whose transport is closed must fail before beforeWrite.
        val (good, closed) = connected(FakeUsbBulkIo(), FakeUsbBulkIo())
        closed.stop()

        val barrier = CaptureBarrier(2, timeoutMs = 5_000)
        suspend fun takePhoto(p: DxoOneUsbProtocol): Result<JsonRpcResponse> {
            var arrived = false
            return try {
                runCatching {
                    p.sendCommand(DxoOneMethods.PHOTO_TAKE, beforeWrite = {
                        arrived = true
                        barrier.arriveAndAwait()
                    })
                }
            } finally {
                if (!arrived) barrier.leave()
            }
        }

        val t0 = System.nanoTime()
        val (rGood, rClosed) = listOf(good, closed).map { p -> async(Dispatchers.IO) { takePhoto(p) } }.awaitAll()
        assertTrue(rGood.getOrThrow().isSuccess)
        assertTrue(rClosed.exceptionOrNull() is DxoTransportClosedException)
        assertFalse(barrier.isBroken)
        assertTrue("took ${elapsedMs(t0)}ms", elapsedMs(t0) < 1_000)
    }
}
