package com.dxoone.multicam.usb

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * Demultiplexer for the DXO One bulk-IN stream.
 *
 * The camera sends JSON-RPC replies, notifications, init signatures and live-view JPEG
 * data over a single bulk-IN endpoint. Exactly one reader per camera must own that
 * endpoint and feed every packet it reads into [feed], one packet at a time. This class
 * classifies the packets and routes them:
 *
 * - init signature            -> [Events.onInitSignature] (owner must write the ACK)
 * - RPC notification (method) -> [Events.onNotification] (except dxo_usb_flush_forced)
 * - RPC reply with numeric id -> handler registered via [registerRequest] for that id
 * - RPC reply without an id   -> the single registered (in-flight) request, with a warning
 * - everything else           -> live-view JPEG accumulator -> [Events.onJpegFrame]
 *
 * This file must stay free of android.* imports so it can be unit-tested on the JVM.
 * The JavaScript transport (DxoTransport.js) implements the same state machine.
 *
 * Threading: [feed] must only be called from the single reader thread. [registerRequest],
 * [unregisterRequest] and [liveViewEnabled] may be used from any thread.
 */
class DxoOneStreamDemux(
    private val events: Events,
    private val log: (String) -> Unit = {}
) {
    interface Events {
        /** The camera sent METADATA_INIT_SIGNATURE; the owner must reply with the ACK. */
        fun onInitSignature()

        /** A JSON-RPC notification (has a `method`), other than dxo_usb_flush_forced. */
        fun onNotification(method: String, message: JsonObject)

        /** A complete live-view JPEG frame (FF D8 FF ... FF D9). */
        fun onJpegFrame(frame: ByteArray)
    }

    // ---------------------------------------------------------------------------------
    // Pending requests
    // ---------------------------------------------------------------------------------

    private val pendingLock = Any()
    private val pending = LinkedHashMap<Int, (JsonRpcResponse) -> Unit>()

    /**
     * Register a handler for the reply to request [id]. The handler is invoked at most
     * once, on the reader thread, and is removed before it is invoked.
     */
    fun registerRequest(id: Int, onResponse: (JsonRpcResponse) -> Unit) {
        synchronized(pendingLock) { pending[id] = onResponse }
    }

    fun unregisterRequest(id: Int) {
        synchronized(pendingLock) { pending.remove(id) }
    }

    fun pendingCount(): Int = synchronized(pendingLock) { pending.size }

    // Id-less replies (fallback for a camera that doesn't echo ids). Same rule as JS:
    // - never attributed once the camera has sent any reply with an id;
    // - each timeout means one late id-less reply may still arrive; that many id-less
    //   replies are dropped. A timeout caused by such a drop is not counted again, so
    //   replies that are truly lost cost a bounded number of extra requests.
    private val idlessLock = Any()
    private var sawReplyId = false              // guarded by idlessLock
    private var lateIdlessExpected = 0          // guarded by idlessLock
    private var droppedIdlessForId: Int? = null // guarded by idlessLock

    /** Call when request [id] timed out: its reply may still arrive later. */
    fun markRequestTimedOut(id: Int) {
        synchronized(idlessLock) {
            if (droppedIdlessForId == id) {
                // It timed out because we dropped its (id-less) reply; don't re-arm.
                droppedIdlessForId = null
            } else if (!sawReplyId) {
                lateIdlessExpected++
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Live view
    // ---------------------------------------------------------------------------------

    /** Video bytes are only accumulated while this is true; otherwise they are discarded. */
    @Volatile
    var liveViewEnabled: Boolean = false

    private val jpeg = GrowableByteBuffer(64 * 1024)
    private var jpegHaveStart = false // jpeg[0..3) == FF D8 FF
    private var jpegScanFrom = 0      // next index to scan for the marker we are looking for

    // ---------------------------------------------------------------------------------
    // RPC assembly state
    // ---------------------------------------------------------------------------------

    private var rpcBuffer: ByteArray? = null
    private var rpcFilled = 0
    private var pendingHeaderSize = -1 // size from a bare 32-byte RPC header, -1 if none

    /** True while an RPC message body is being assembled across packets. */
    val isAssemblingRpc: Boolean get() = rpcBuffer != null

    /**
     * Feed one bulk-IN packet (the first [length] bytes of [packet]).
     * The array is not retained, so the caller may reuse it.
     */
    fun feed(packet: ByteArray, length: Int = packet.size) {
        if (length <= 0) return

        // 1. Init signature: always handled, even mid-message.
        if (isInitSignature(packet, length)) {
            pendingHeaderSize = -1
            events.onInitSignature()
            return
        }

        // 2. Continue assembling an RPC message.
        val buf = rpcBuffer
        if (buf != null) {
            appendRpc(packet, 0, length)
            return
        }

        // 3. Body of a previously seen bare 32-byte header.
        if (pendingHeaderSize >= 0) {
            val size = pendingHeaderSize
            pendingHeaderSize = -1
            if (packet[0] == OPEN_BRACE) {
                startRpc(size, packet, 0, length)
                return
            }
            log("Discarding bare RPC header (size=$size): next packet is not a JSON body")
            // fall through: classify this packet normally
        }

        // 4. Packets starting with the A3 BA D1 10 header.
        if (length >= HEADER_SIZE && startsWithHeaderMagic(packet)) {
            val size = (packet[8].toInt() and 0xFF) or ((packet[9].toInt() and 0xFF) shl 8)
            if (length > HEADER_SIZE && packet[HEADER_SIZE] == OPEN_BRACE) {
                if (size == 0) {
                    log("Ignoring RPC header with size 0")
                    return
                }
                startRpc(size, packet, HEADER_SIZE, length - HEADER_SIZE)
            } else if (length == HEADER_SIZE) {
                if (size != 0) pendingHeaderSize = size
            } else {
                // Header followed by non-JSON (e.g. FF D8): video payload.
                appendVideo(packet, HEADER_SIZE, length - HEADER_SIZE)
            }
            return
        }

        // 5. Anything else is video data.
        appendVideo(packet, 0, length)
    }

    /** Drop all partial state (RPC assembly, pending header, JPEG accumulator). */
    fun reset() {
        rpcBuffer = null
        rpcFilled = 0
        pendingHeaderSize = -1
        resetJpeg()
    }

    // ---------------------------------------------------------------------------------
    // RPC
    // ---------------------------------------------------------------------------------

    private fun startRpc(size: Int, src: ByteArray, offset: Int, count: Int) {
        rpcBuffer = ByteArray(size)
        rpcFilled = 0
        appendRpc(src, offset, count)
    }

    private fun appendRpc(src: ByteArray, offset: Int, count: Int) {
        val buf = rpcBuffer ?: return
        val n = minOf(count, buf.size - rpcFilled)
        System.arraycopy(src, offset, buf, rpcFilled, n)
        rpcFilled += n
        if (rpcFilled >= buf.size) {
            rpcBuffer = null
            rpcFilled = 0
            handleRpc(buf)
        }
    }

    private fun handleRpc(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8).replace("\u0000", "").trim()
        val obj: JsonObject = try {
            val element = JsonParser.parseString(text)
            if (!element.isJsonObject) {
                log("Dropping non-object RPC message: $text")
                return
            }
            element.asJsonObject
        } catch (e: Exception) {
            log("Dropping unparseable RPC message (${e.javaClass.simpleName}): $text")
            return
        }

        // Notification
        val methodEl = obj.get("method")
        if (methodEl != null && !methodEl.isJsonNull) {
            val method = if (methodEl.isJsonPrimitive) methodEl.asString else methodEl.toString()
            if (method == DxoOneMethods.USB_FLUSH_FORCED) return
            events.onNotification(method, obj)
            return
        }

        val response = toResponse(obj)
        val id = numericId(obj)
        if (id != null) {
            synchronized(idlessLock) { sawReplyId = true }
            val handler = synchronized(pendingLock) { pending.remove(id) }
            if (handler == null) {
                log("Dropping stale RPC reply id=$id (no pending request): $text")
                return
            }
            handler(response.copy(id = id, matched = true))
            return
        }

        if (!obj.has("result") && !obj.has("error")) {
            log("Dropping RPC message with no method/id/result/error: $text")
            return
        }

        // No id: deliver to the single in-flight request. We have no hardware proof that
        // the camera echoes ids, so this fallback is required -- but only while it is safe.
        val dropReason = synchronized(idlessLock) {
            when {
                sawReplyId -> "camera uses ids"
                lateIdlessExpected > 0 -> {
                    lateIdlessExpected--
                    droppedIdlessForId = synchronized(pendingLock) { pending.keys.singleOrNull() }
                    "late reply to a timed-out request"
                }
                else -> null
            }
        }
        if (dropReason != null) {
            log("Dropping RPC reply without id ($dropReason): $text")
            return
        }
        val handler = synchronized(pendingLock) {
            when (pending.size) {
                1 -> {
                    val key = pending.keys.first()
                    pending.remove(key)
                }
                else -> null
            }
        }
        if (handler == null) {
            log("Dropping RPC reply without id: ${pendingCount()} requests pending: $text")
            return
        }
        log("RPC reply without id; delivering to the in-flight request")
        handler(response.copy(matched = true))
    }

    private fun numericId(obj: JsonObject): Int? {
        val el = obj.get("id") ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) return null
        return try {
            el.asNumber.toInt()
        } catch (e: Exception) {
            null
        }
    }

    private fun toResponse(obj: JsonObject): JsonRpcResponse {
        val resultEl = obj.get("result")
        val result: Map<String, Any>? =
            if (resultEl != null && resultEl.isJsonObject) {
                try {
                    gson.fromJson<Map<String, Any>>(resultEl, mapType)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        return JsonRpcResponse(
            jsonrpc = obj.get("jsonrpc")?.takeIf { it.isJsonPrimitive }?.asString,
            id = null,
            result = result,
            error = toError(obj.get("error")),
            method = null
        )
    }

    private fun toError(el: JsonElement?): JsonRpcError? {
        if (el == null || el.isJsonNull) return null
        if (!el.isJsonObject) return JsonRpcError(code = -1, message = el.toString())
        val o = el.asJsonObject
        val code = o.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asNumber?.toInt() ?: -1
        val message = o.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: o.toString()
        return JsonRpcError(code = code, message = message, data = o.get("data"))
    }

    // ---------------------------------------------------------------------------------
    // JPEG
    // ---------------------------------------------------------------------------------

    private fun appendVideo(src: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        if (!liveViewEnabled) {
            if (jpeg.size > 0 || jpegHaveStart) resetJpeg()
            return
        }
        jpeg.append(src, offset, count)
        extractFrames()
        if (jpeg.size > MAX_JPEG_BUFFER_BYTES) {
            log("JPEG accumulator exceeded $MAX_JPEG_BUFFER_BYTES bytes; resetting")
            resetJpeg()
        }
    }

    private fun extractFrames() {
        while (true) {
            if (!jpegHaveStart) {
                val start = jpeg.indexOf(JPG_SOI, jpegScanFrom)
                if (start < 0) {
                    // Keep only the tail that could be the beginning of FF D8 FF.
                    val keep = minOf(jpeg.size, JPG_SOI.size - 1)
                    jpeg.discardFront(jpeg.size - keep)
                    jpegScanFrom = 0
                    return
                }
                jpeg.discardFront(start)
                jpegHaveStart = true
                jpegScanFrom = JPG_SOI.size
            }
            val end = jpeg.indexOf(JPG_EOI, jpegScanFrom)
            if (end < 0) {
                // Resume next time where an FF D9 could still start.
                jpegScanFrom = maxOf(JPG_SOI.size, jpeg.size - (JPG_EOI.size - 1))
                return
            }
            val frameEnd = end + JPG_EOI.size
            events.onJpegFrame(jpeg.copyRange(0, frameEnd))
            jpeg.discardFront(frameEnd)
            jpegHaveStart = false
            jpegScanFrom = 0
        }
    }

    private fun resetJpeg() {
        jpeg.clear()
        jpegHaveStart = false
        jpegScanFrom = 0
    }

    companion object {
        const val HEADER_SIZE = 32
        const val MAX_JPEG_BUFFER_BYTES = 4 * 1024 * 1024

        private const val OPEN_BRACE = '{'.code.toByte()
        private val HEADER_MAGIC = DxoOneConstants.JPG_METADATA_HEADER // A3 BA D1 10
        private val JPG_SOI = DxoOneConstants.JPG_HEADER // FF D8 FF
        private val JPG_EOI = DxoOneConstants.JPG_TRAILER // FF D9

        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, Any>>() {}.type

        fun isInitSignature(packet: ByteArray, length: Int = packet.size): Boolean {
            val sig = DxoOneConstants.METADATA_INIT_SIGNATURE
            if (length != sig.size) return false
            for (i in sig.indices) if (packet[i] != sig[i]) return false
            return true
        }

        private fun startsWithHeaderMagic(packet: ByteArray): Boolean {
            for (i in HEADER_MAGIC.indices) if (packet[i] != HEADER_MAGIC[i]) return false
            return true
        }

        /** Frame an RPC payload: RPC_HEADER + size (LE16) + RPC_HEADER_TRAILER + payload. */
        fun buildRpcMessage(payload: ByteArray): ByteArray {
            require(payload.size <= 0xFFFF) { "RPC payload too large: ${payload.size} bytes" }
            val size = byteArrayOf(
                (payload.size and 0xFF).toByte(),
                ((payload.size shr 8) and 0xFF).toByte()
            )
            return DxoOneConstants.RPC_HEADER + size + DxoOneConstants.RPC_HEADER_TRAILER + payload
        }
    }
}

/** Minimal growable byte buffer with cheap front-discard (amortised by compaction). */
internal class GrowableByteBuffer(initialCapacity: Int) {
    private var data = ByteArray(initialCapacity)
    var size = 0
        private set

    fun append(src: ByteArray, offset: Int, count: Int) {
        ensureCapacity(size + count)
        System.arraycopy(src, offset, data, size, count)
        size += count
    }

    fun indexOf(pattern: ByteArray, from: Int): Int {
        val last = size - pattern.size
        var i = maxOf(0, from)
        outer@ while (i <= last) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    fun copyRange(from: Int, to: Int): ByteArray = data.copyOfRange(from, to)

    fun discardFront(count: Int) {
        if (count <= 0) return
        if (count >= size) {
            size = 0
            return
        }
        System.arraycopy(data, count, data, 0, size - count)
        size -= count
    }

    fun clear() {
        size = 0
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size) return
        var cap = data.size.coerceAtLeast(1)
        while (cap < needed) cap *= 2
        data = data.copyOf(cap)
    }
}
