package com.dxoone.multicam.usb

import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class DxoOneStreamDemuxTest {

    private class Recorder : DxoOneStreamDemux.Events {
        var initSignatures = 0
        val notifications = mutableListOf<Pair<String, JsonObject>>()
        val frames = mutableListOf<ByteArray>()

        override fun onInitSignature() {
            initSignatures++
        }

        override fun onNotification(method: String, message: JsonObject) {
            notifications += method to message
        }

        override fun onJpegFrame(frame: ByteArray) {
            frames += frame
        }
    }

    private lateinit var events: Recorder
    private lateinit var logs: MutableList<String>
    private lateinit var demux: DxoOneStreamDemux

    @Before
    fun setUp() {
        events = Recorder()
        logs = mutableListOf()
        demux = DxoOneStreamDemux(events) { logs += it }
    }

    // ---------------------------------------------------------------- helpers

    /** A framed RPC message as the camera sends it (header + size + trailer + JSON + NUL). */
    private fun rpcMessage(json: String): ByteArray =
        DxoOneStreamDemux.buildRpcMessage((json + "\u0000").toByteArray(Charsets.UTF_8))

    /** Split a byte stream into USB packets of at most [size] bytes. */
    private fun packets(data: ByteArray, size: Int = 512): List<ByteArray> =
        (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)) }

    private fun feedAll(list: List<ByteArray>) = list.forEach { demux.feed(it) }

    private fun register(id: Int): MutableList<JsonRpcResponse> {
        val got = mutableListOf<JsonRpcResponse>()
        demux.registerRequest(id) { got += it }
        return got
    }

    private fun jpeg(bodySize: Int, seed: Int): ByteArray {
        // Body bytes never contain 0xFF, so FF D9 only appears as the real EOI marker.
        val rnd = Random(seed)
        val body = ByteArray(bodySize) { rnd.nextInt(0, 0xFF).toByte() }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            body + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** A video packet with the 32-byte A3 BA D1 10 header in front of [payload]. */
    private fun videoWithHeader(payload: ByteArray): ByteArray {
        val header = ByteArray(32)
        DxoOneConstants.JPG_METADATA_HEADER.copyInto(header)
        header[8] = (payload.size and 0xFF).toByte()
        header[9] = ((payload.size shr 8) and 0xFF).toByte()
        return header + payload
    }

    // ---------------------------------------------------------------- RPC

    @Test
    fun rpcInOnePacket() {
        val got = register(3)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","id":3,"result":{"type":"iso","param":"iso100"}}"""))

        assertEquals(1, got.size)
        val r = got[0]
        assertEquals(3, r.id)
        assertTrue(r.matched)
        assertTrue(r.isSuccess)
        assertEquals("iso", r.result?.get("type"))
        assertEquals(0, demux.pendingCount())
        assertFalse(demux.isAssemblingRpc)
    }

    @Test
    fun rpcSplitAcrossPackets() {
        val longValue = "x".repeat(1500)
        val got = register(9)
        val msg = rpcMessage("""{"jsonrpc":"2.0","id":9,"result":{"blob":"$longValue"}}""")
        val parts = packets(msg)
        assertTrue(parts.size >= 3)

        parts.dropLast(1).forEach { demux.feed(it) }
        assertTrue(got.isEmpty())
        assertTrue(demux.isAssemblingRpc)
        demux.feed(parts.last())

        assertEquals(1, got.size)
        assertEquals(longValue, got[0].result?.get("blob"))
        assertFalse(demux.isAssemblingRpc)
    }

    @Test
    fun rpcBytesBeyondSizeAreNotAppended() {
        val got = register(1)
        val msg = rpcMessage("""{"id":1,"result":{"ok":true}}""")
        // Garbage after the declared size in the same packet must be ignored.
        demux.feed(msg + "GARBAGE".toByteArray())
        assertEquals(1, got.size)
        assertEquals(true, got[0].result?.get("ok"))
    }

    @Test
    fun bareHeaderThenBody() {
        val got = register(4)
        val msg = rpcMessage("""{"jsonrpc":"2.0","id":4,"result":{"battery_level":87}}""")
        demux.feed(msg.copyOfRange(0, 32))
        assertTrue(got.isEmpty())
        demux.feed(msg.copyOfRange(32, msg.size))

        assertEquals(1, got.size)
        assertEquals(87.0, got[0].result?.get("battery_level"))
    }

    @Test
    fun bareHeaderThenBodySplitAcrossPackets() {
        val got = register(4)
        val msg = rpcMessage("""{"id":4,"result":{"v":"${"y".repeat(700)}"}}""")
        demux.feed(msg.copyOfRange(0, 32))
        feedAll(packets(msg.copyOfRange(32, msg.size)))
        assertEquals(1, got.size)
        assertEquals("y".repeat(700), got[0].result?.get("v"))
    }

    @Test
    fun bareHeaderFollowedByNonJsonIsDiscardedAndPacketClassifiedNormally() {
        demux.liveViewEnabled = true
        val got = register(4)
        val header = rpcMessage("""{"id":4,"result":{}}""").copyOfRange(0, 32)
        demux.feed(header)
        val frame = jpeg(100, 1)
        demux.feed(frame)

        assertTrue(got.isEmpty())
        assertEquals(1, events.frames.size)
        assertArrayEquals(frame, events.frames[0])
        assertFalse(demux.isAssemblingRpc)
    }

    @Test
    fun notificationThenMatchingIdReply() {
        val got = register(5)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","method":"dxo_setting_applied","params":{"type":"iso"}}"""))

        // The notification must not be taken as the reply.
        assertTrue(got.isEmpty())
        assertEquals(1, events.notifications.size)
        assertEquals(DxoOneMethods.SETTING_APPLIED, events.notifications[0].first)

        demux.feed(rpcMessage("""{"jsonrpc":"2.0","id":5,"result":{"type":"iso"}}"""))
        assertEquals(1, got.size)
        assertEquals(5, got[0].id)
        assertTrue(got[0].isSuccess)
    }

    @Test
    fun staleReplyWithUnknownIdIsDropped() {
        val got = register(6)
        demux.feed(rpcMessage("""{"id":5,"result":{"late":true}}"""))
        assertTrue(got.isEmpty())
        assertEquals(1, demux.pendingCount())
        assertTrue(logs.any { it.contains("stale") })

        demux.feed(rpcMessage("""{"id":6,"result":{}}"""))
        assertEquals(1, got.size)
        assertEquals(6, got[0].id)
    }

    @Test
    fun replyIsDeliveredOnlyOnce() {
        val got = register(2)
        val msg = rpcMessage("""{"id":2,"result":{}}""")
        demux.feed(msg)
        demux.feed(msg)
        assertEquals(1, got.size)
    }

    @Test
    fun replyWithNoIdGoesToTheInFlightRequest() {
        val got = register(7)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","result":{"type":"raw"}}"""))

        assertEquals(1, got.size)
        assertTrue(got[0].matched)
        assertTrue(got[0].isSuccess)
        assertNull(got[0].id)
        assertEquals("raw", got[0].result?.get("type"))
        assertTrue(logs.any { it.contains("without id") })
    }

    @Test
    fun errorReplyWithNoIdIsMatchedButNotSuccess() {
        val got = register(8)
        demux.feed(rpcMessage("""{"error":{"code":-32601,"message":"nope"}}"""))
        assertEquals(1, got.size)
        assertTrue(got[0].matched)
        assertFalse(got[0].isSuccess)
        assertEquals(-32601, got[0].error?.code)
        assertEquals("nope", got[0].error?.message)
    }

    @Test
    fun replyWithNoIdIsDroppedOnceTheCameraHasSentIds() {
        val first = register(1)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","id":1,"result":"ok"}"""))
        assertEquals(1, first.size)

        val second = register(2)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","result":"stray"}"""))
        assertEquals(0, second.size)
    }

    @Test
    fun lateReplyWithNoIdAfterTimeoutIsNotGivenToTheNextRequest() {
        val timedOut = register(1)
        demux.unregisterRequest(1)
        demux.markRequestTimedOut(1)

        val next = register(2)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","result":"late"}"""))
        assertEquals(0, timedOut.size)
        assertEquals(0, next.size)
    }

    @Test
    fun cameraWithoutIdsRecoversAfterAReplyIsLostEntirely() {
        // Request 1 times out and its reply never arrives.
        register(1)
        demux.unregisterRequest(1)
        demux.markRequestTimedOut(1)

        // Request 2's reply is taken for the late reply and dropped; 2 then times out.
        val second = register(2)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","result":"two"}"""))
        assertEquals(0, second.size)
        demux.unregisterRequest(2)
        demux.markRequestTimedOut(2)

        // From now on id-less replies are delivered again.
        for (n in 3..5) {
            val got = register(n)
            demux.feed(rpcMessage("""{"jsonrpc":"2.0","result":"r$n"}"""))
            assertEquals(1, got.size)
        }
    }

    @Test
    fun severalTimedOutRequestsOnABackloggedCameraWithoutIds() {
        for (n in 0..3) {
            register(n)
            demux.unregisterRequest(n)
            demux.markRequestTimedOut(n)
        }
        val go = register(4)
        for (n in 0..3) demux.feed(rpcMessage("""{"jsonrpc":"2.0","result":"reply-$n"}"""))
        assertEquals(0, go.size)
        demux.feed(rpcMessage("""{"jsonrpc":"2.0","result":"reply-go"}"""))
        assertEquals(1, go.size)
    }

    @Test
    fun replyWithNoIdAndNothingPendingIsDropped() {
        demux.feed(rpcMessage("""{"result":{}}"""))
        assertTrue(logs.any { it.contains("without id") })
    }

    @Test
    fun usbFlushForcedIsIgnored() {
        val got = register(1)
        demux.feed(rpcMessage("""{"method":"dxo_usb_flush_forced"}"""))
        assertTrue(events.notifications.isEmpty())
        assertTrue(got.isEmpty())
        demux.feed(rpcMessage("""{"id":1,"result":{}}"""))
        assertEquals(1, got.size)
    }

    @Test
    fun garbageJsonIsDropped() {
        val got = register(1)
        demux.feed(rpcMessage("""{"id":1,"result":{"unterminated"""))
        assertTrue(got.isEmpty())
        assertTrue(events.notifications.isEmpty())
        assertTrue(logs.any { it.contains("unparseable") })
        assertFalse(demux.isAssemblingRpc)

        // The stream recovers: the next valid reply is delivered.
        demux.feed(rpcMessage("""{"id":1,"result":{}}"""))
        assertEquals(1, got.size)
    }

    @Test
    fun unmatchedResponseFromJsonIsNotSuccess() {
        val r = JsonRpcResponse.fromJson("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        assertNotNull(r)
        assertFalse(r!!.matched)
        assertFalse(r.isSuccess)
    }

    // ---------------------------------------------------------------- init signature

    @Test
    fun initSignatureDetected() {
        demux.feed(DxoOneConstants.METADATA_INIT_SIGNATURE.copyOf())
        assertEquals(1, events.initSignatures)

        // A 32-byte packet that only differs slightly is not the init signature.
        val other = DxoOneConstants.METADATA_INIT_SIGNATURE.copyOf()
        other[12] = 0x03
        demux.feed(other)
        assertEquals(1, events.initSignatures)

        // Length must match exactly (feed with explicit length into a larger buffer).
        val big = ByteArray(512)
        DxoOneConstants.METADATA_INIT_SIGNATURE.copyInto(big)
        demux.feed(big, 32)
        assertEquals(2, events.initSignatures)
        demux.feed(big, 33)
        assertEquals(2, events.initSignatures)
    }

    @Test
    fun initSignatureMidRpcDoesNotCorruptAssembly() {
        val got = register(11)
        val msg = rpcMessage("""{"id":11,"result":{"v":"${"z".repeat(800)}"}}""")
        val parts = packets(msg)
        demux.feed(parts[0])
        demux.feed(DxoOneConstants.METADATA_INIT_SIGNATURE.copyOf())
        parts.drop(1).forEach { demux.feed(it) }

        assertEquals(1, events.initSignatures)
        assertEquals(1, got.size)
        assertEquals("z".repeat(800), got[0].result?.get("v"))
    }

    // ---------------------------------------------------------------- JPEG

    @Test
    fun jpegFrameSplitAcrossPacketsWithTrailingBytesOfNextFrame() {
        demux.liveViewEnabled = true
        val f1 = jpeg(1300, 1)
        val f2 = jpeg(900, 2)
        val f3 = jpeg(50, 3)
        val junk = byteArrayOf(0x01, 0x02, 0x03)
        val stream = junk + f1 + f2 + f3

        // Uneven packet boundaries, including one between FF and D9 of f1's EOI.
        val eoiSplit = junk.size + f1.size - 1
        val cuts = listOf(0, 200, 700, eoiSplit, eoiSplit + 5, eoiSplit + 600, stream.size)
        for (i in 0 until cuts.size - 1) {
            demux.feed(stream.copyOfRange(cuts[i], cuts[i + 1]))
        }

        assertEquals(3, events.frames.size)
        assertArrayEquals(f1, events.frames[0])
        assertArrayEquals(f2, events.frames[1])
        assertArrayEquals(f3, events.frames[2])
    }

    @Test
    fun jpegWithHeaderPrefixedPackets() {
        demux.liveViewEnabled = true
        val f1 = jpeg(1000, 4)
        val chunks = packets(f1, 480)
        // First chunk carries the A3 BA D1 10 header, the rest are raw.
        demux.feed(videoWithHeader(chunks[0]))
        chunks.drop(1).forEach { demux.feed(it) }
        assertEquals(1, events.frames.size)
        assertArrayEquals(f1, events.frames[0])
    }

    @Test
    fun soiSplitAcrossPackets() {
        demux.liveViewEnabled = true
        val f1 = jpeg(100, 5)
        demux.feed(byteArrayOf(0x00, 0xFF.toByte()))
        demux.feed(byteArrayOf(0xD8.toByte()))
        demux.feed(f1.copyOfRange(2, f1.size))
        assertEquals(1, events.frames.size)
        assertArrayEquals(f1, events.frames[0])
    }

    @Test
    fun videoInterleavedBetweenTwoRpcMessages() {
        demux.liveViewEnabled = true
        val got1 = register(20)
        demux.feed(rpcMessage("""{"id":20,"result":{"n":1}}"""))
        assertEquals(1, got1.size)

        val frame = jpeg(1500, 6)
        val chunks = packets(frame, 480)
        demux.feed(videoWithHeader(chunks[0]))
        demux.feed(chunks[1])

        val got2 = register(21)
        demux.feed(rpcMessage("""{"id":21,"result":{"n":2}}"""))
        chunks.drop(2).forEach { demux.feed(it) }

        assertEquals(1, got2.size)
        assertEquals(2.0, got2[0].result?.get("n"))
        assertEquals(1, events.frames.size)
        assertArrayEquals(frame, events.frames[0])
    }

    @Test
    fun videoDiscardedWhileLiveViewDisabled() {
        val f1 = jpeg(200, 7)
        demux.feed(f1.copyOfRange(0, 100))
        demux.liveViewEnabled = true
        demux.feed(f1.copyOfRange(100, f1.size)) // tail of a frame we never saw start
        assertTrue(events.frames.isEmpty())

        val f2 = jpeg(200, 8)
        demux.feed(f2)
        assertEquals(1, events.frames.size)
        assertArrayEquals(f2, events.frames[0])

        demux.liveViewEnabled = false
        demux.feed(jpeg(10, 9))
        assertEquals(1, events.frames.size)
    }

    @Test
    fun oversizedJpegAccumulatorIsReset() {
        demux.liveViewEnabled = true
        demux.feed(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        val filler = ByteArray(512) { 0x11 }
        repeat(DxoOneStreamDemux.MAX_JPEG_BUFFER_BYTES / 512 + 2) { demux.feed(filler) }
        assertTrue(logs.any { it.contains("exceeded") })
        assertTrue(events.frames.isEmpty())

        val f = jpeg(300, 10)
        demux.feed(f)
        assertEquals(1, events.frames.size)
        assertArrayEquals(f, events.frames[0])
    }
}
