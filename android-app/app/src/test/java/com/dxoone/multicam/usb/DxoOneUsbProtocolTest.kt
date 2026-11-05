package com.dxoone.multicam.usb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** [DxoOneUsbProtocol] against a fake camera behind [UsbBulkIo]. */
class DxoOneUsbProtocolTest {

    private val started = CopyOnWriteArrayList<DxoOneUsbProtocol>()

    private fun protocol(
        io: UsbBulkIo,
        onNotification: (String, com.google.gson.JsonObject) -> Unit = { _, _ -> },
        onFatalError: (Throwable) -> Unit = {}
    ) = DxoOneUsbProtocol(io, onNotification = onNotification, onFatalError = onFatalError)
        .also { started += it }

    @After
    fun stopAll() {
        started.forEach { it.stop() }
    }

    @Test
    fun sendCommandMatchesReplyAfterNotification() = runBlocking {
        val cam = FakeUsbBulkIo(reply = { id, _ ->
            listOf(
                rpcPacket("""{"method":"dxo_setting_applied","params":{"type":"iso"}}"""),
                rpcPacket("""{"id":$id,"result":{"type":"iso"}}""")
            )
        })
        val notes = CopyOnWriteArrayList<String>()
        val p = protocol(cam, onNotification = { m, _ -> notes += m })
        p.initialize()

        val r1 = p.sendCommand(DxoOneMethods.SETTING_SET, mapOf("type" to "iso", "param" to "iso100"))
        assertTrue(r1.isSuccess)
        assertEquals(0, r1.id)
        val r2 = p.sendCommand(DxoOneMethods.SETTING_SET, mapOf("type" to "iso", "param" to "iso200"))
        assertTrue(r2.isSuccess)
        assertEquals(1, r2.id)

        waitUntil { notes.size >= 2 }
        assertEquals(listOf("dxo_setting_applied", "dxo_setting_applied"), notes.toList())
        // init ACK + init-signature ACK + one ACK before each framed request
        assertTrue(cam.countAcks() >= 4)
        p.stop()
        assertFalse(p.isRunning)
    }

    @Test
    fun concurrentCommandsAreSerializedWithASingleReader() = runBlocking {
        val cam = FakeUsbBulkIo()
        val p = protocol(cam)
        p.initialize()
        val results = (1..20).map {
            async(Dispatchers.IO) { p.sendCommand(DxoOneMethods.CAMERA_STATUS_GET) }
        }.awaitAll()
        assertTrue(results.all { it.isSuccess })
        assertEquals((0 until 20).toSet(), results.map { it.id }.toSet())
        assertEquals(1, cam.maxConcurrentReaders)
    }

    @Test
    fun timeoutThrowsTypedExceptionAndLateReplyIsDropped() = runBlocking {
        val cam = FakeUsbBulkIo(reply = { _, _ -> emptyList() })
        val p = protocol(cam)
        p.initialize()
        val t0 = System.nanoTime()
        try {
            p.sendCommand(DxoOneMethods.CAMERA_STATUS_GET, timeoutMs = 300)
            fail("expected a timeout")
        } catch (e: DxoCommandTimeoutException) {
            assertEquals(300L, e.timeoutMs)
        }
        assertTrue(elapsedMs(t0) < 2_000)

        // The late reply to id 0 arrives now; the next request (id 1) must not take it.
        cam.inQueue.add(rpcPacket("""{"id":0,"result":{"late":true}}"""))
        delay(100)
        val next = async(Dispatchers.IO) {
            p.sendCommand(DxoOneMethods.CAMERA_STATUS_GET, timeoutMs = 2_000)
        }
        delay(100)
        cam.inQueue.add(rpcPacket("""{"id":1,"result":{"late":false}}"""))
        val r = next.await()
        assertEquals(1, r.id)
        assertEquals(false, r.result?.get("late"))
    }

    @Test
    fun photoTakeDefaultsToCaptureTimeout() {
        assertEquals(
            DxoOneConstants.CAPTURE_TIMEOUT_MS.toLong(),
            DxoOneUsbProtocol.defaultTimeoutFor(DxoOneMethods.PHOTO_TAKE)
        )
        assertEquals(
            DxoOneConstants.COMMAND_TIMEOUT_MS.toLong(),
            DxoOneUsbProtocol.defaultTimeoutFor(DxoOneMethods.SETTING_SET)
        )
    }

    @Test
    fun unknownCommandIsRejectedBeforeAnyWrite() = runBlocking {
        val cam = FakeUsbBulkIo()
        val p = protocol(cam)
        p.initialize()
        // The ACK answering the init signature is written asynchronously.
        waitUntil { cam.countAcks() >= 2 }
        val writes = cam.writes.size
        try {
            p.sendCommand("dxo_not_a_command")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(writes, cam.writes.size)
    }

    @Test
    fun deadDeviceStopsReaderAndFailsInFlight() = runBlocking {
        val fatal = CopyOnWriteArrayList<Throwable>()
        val cam = FakeUsbBulkIo(reply = { _, _ -> emptyList() })
        val p = protocol(cam, onFatalError = { fatal += it })
        p.initialize()
        val inFlight = async(Dispatchers.IO) {
            runCatching { p.sendCommand(DxoOneMethods.CAMERA_STATUS_GET, timeoutMs = 10_000) }
        }
        delay(100)
        cam.dead.set(true)
        val t0 = System.nanoTime()
        val res = inFlight.await()
        assertTrue(elapsedMs(t0) < 2_000)
        assertTrue(res.exceptionOrNull() is DxoTransportClosedException)
        waitUntil { fatal.isNotEmpty() }
        assertEquals(1, fatal.size)
        assertFalse(p.isRunning)

        val callsAfter = cam.inCalls.get()
        Thread.sleep(200)
        assertEquals("reader must not spin after death", callsAfter, cam.inCalls.get())
        try {
            p.sendCommand(DxoOneMethods.CAMERA_STATUS_GET)
            fail("expected DxoTransportClosedException")
        } catch (e: DxoTransportClosedException) {
            // expected
        }
    }

    @Test
    fun shortWriteThrowsIOException() = runBlocking {
        val cam = FakeUsbBulkIo()
        val p = protocol(cam)
        p.initialize()
        cam.shortWrite = true
        try {
            p.sendCommand(DxoOneMethods.CAMERA_STATUS_GET)
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(e is DxoCommandTimeoutException)
        }
    }

    @Test
    fun initWithoutSignatureProceedsAfterBoundedWait() = runBlocking {
        val cam = FakeUsbBulkIo(sendInitSignature = false)
        val p = protocol(cam)
        val t0 = System.nanoTime()
        p.initialize()
        val elapsed = elapsedMs(t0)
        assertTrue("waited ${elapsed}ms", elapsed in 1_900..3_500)
        assertTrue(p.isRunning)
    }

    @Test
    fun stopJoinsReaderPromptly() = runBlocking {
        val cam = FakeUsbBulkIo()
        val p = protocol(cam)
        p.initialize()
        val t0 = System.nanoTime()
        p.stop()
        assertTrue(elapsedMs(t0) < 600)
        val calls = cam.inCalls.get()
        Thread.sleep(400)
        assertEquals(calls, cam.inCalls.get())
    }

    @Test
    fun haltedInEndpointIsClearedAndTransportKeepsWorking() = runBlocking {
        val fatal = CopyOnWriteArrayList<Throwable>()
        val cam = FakeUsbBulkIo()
        val p = protocol(cam, onFatalError = { fatal += it })
        p.initialize()
        cam.halted.set(true)
        // The reader may be inside a 250 ms poll when the halt starts.
        waitUntil { cam.haltClears.isNotEmpty() }
        assertEquals(listOf(cam.inEndpointAddress), cam.haltClears.toList())
        assertTrue(p.isRunning)
        assertTrue(fatal.isEmpty())
        val r = p.sendCommand(DxoOneMethods.CAMERA_STATUS_GET, timeoutMs = 2_000)
        assertTrue(r.isSuccess)
    }

    @Test
    fun deadDeviceStillDetectedWhenHaltClearFails() = runBlocking {
        val fatal = CopyOnWriteArrayList<Throwable>()
        val cam = FakeUsbBulkIo()
        val p = protocol(cam, onFatalError = { fatal += it })
        p.initialize()
        cam.dead.set(true) // control transfers fail too
        waitUntil { fatal.isNotEmpty() }
        assertEquals(1, fatal.size)
        assertTrue(fatal.single() is DxoTransportClosedException)
        assertTrue(cam.haltClears.isEmpty())
        assertFalse(p.isRunning)
    }
}

internal fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

/** Poll [condition] until it holds; fail after [timeoutMs]. */
internal fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("condition not met within ${timeoutMs}ms")
        Thread.sleep(10)
    }
}
