package com.dxoone.multicam.usb

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A framed RPC packet as the camera sends it. */
fun rpcPacket(json: String): ByteArray =
    DxoOneStreamDemux.buildRpcMessage((json + "\u0000").toByteArray(Charsets.UTF_8))

/**
 * A fake DXO One behind [UsbBulkIo].
 *
 * - The first ACK written gets the camera's init signature back (if [sendInitSignature]).
 * - Each RPC request written gets [reply] (default: an empty successful result), queued on
 *   bulk-IN after [replyDelayMs] (immediately when 0).
 * - [dead]: every transfer fails immediately, control transfers too.
 * - [halted]: bulk-IN fails immediately until a CLEAR_FEATURE(ENDPOINT_HALT) succeeds.
 * - [shortWrite]: bulk-OUT reports one byte less than asked.
 */
class FakeUsbBulkIo(
    private val reply: (id: Int, method: String) -> List<ByteArray> = { id, _ ->
        listOf(rpcPacket("""{"jsonrpc":"2.0","id":$id,"result":{}}"""))
    },
    private val replyDelayMs: (method: String) -> Long = { 0L },
    private val sendInitSignature: Boolean = true,
    /** How long each RPC write takes. */
    private val writeDurationMs: Long = 0L
) : UsbBulkIo {
    override val inEndpointAddress = 0x81
    override val outEndpointAddress = 0x01

    val inQueue = LinkedBlockingQueue<ByteArray>()
    val writes = CopyOnWriteArrayList<ByteArray>()
    val dead = AtomicBoolean(false)
    val halted = AtomicBoolean(false)
    @Volatile var shortWrite = false

    val inCalls = AtomicInteger()
    private val concurrentReaders = AtomicInteger()
    @Volatile var maxConcurrentReaders = 0
        private set

    /** wIndex (endpoint address) of each successful CLEAR_FEATURE(ENDPOINT_HALT). */
    val haltClears = CopyOnWriteArrayList<Int>()

    /** Per method, [System.nanoTime] at the start / end of its last RPC write and its reply. */
    val writeStart = ConcurrentHashMap<String, Long>()
    val writeEnd = ConcurrentHashMap<String, Long>()
    val replyAt = ConcurrentHashMap<String, Long>()

    private val acks = AtomicInteger()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fake-usb-reply").apply { isDaemon = true }
    }

    override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        inCalls.incrementAndGet()
        val c = concurrentReaders.incrementAndGet()
        synchronized(this) { maxConcurrentReaders = maxOf(maxConcurrentReaders, c) }
        try {
            if (dead.get() || halted.get()) return -1
            val packet = inQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -1
            packet.copyInto(buffer)
            return packet.size
        } finally {
            concurrentReaders.decrementAndGet()
        }
    }

    override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int): Int {
        if (dead.get()) return -1
        val started = System.nanoTime()
        val bytes = data.copyOf(length)
        writes += bytes
        if (shortWrite) return length - 1
        if (bytes.contentEquals(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)) {
            if (acks.getAndIncrement() == 0 && sendInitSignature) {
                inQueue.add(DxoOneConstants.METADATA_INIT_SIGNATURE)
            }
        } else if (bytes.size > HEADER && bytes[HEADER] == '{'.code.toByte()) {
            val json = String(bytes, HEADER, bytes.size - HEADER, Charsets.UTF_8).trimEnd('\u0000')
            val id = Regex("\"id\":(\\d+)").find(json)!!.groupValues[1].toInt()
            val method = Regex("\"method\":\"([a-z_]+)\"").find(json)!!.groupValues[1]
            writeStart[method] = started
            if (writeDurationMs > 0) Thread.sleep(writeDurationMs)
            writeEnd[method] = System.nanoTime()
            val packets = reply(id, method)
            val deliver = Runnable {
                replyAt[method] = System.nanoTime()
                packets.forEach { inQueue.add(it) }
            }
            val delay = replyDelayMs(method)
            if (delay <= 0) deliver.run() else scheduler.schedule(deliver, delay, TimeUnit.MILLISECONDS)
        }
        return length
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int
    ): Int {
        if (dead.get()) return -1
        // CLEAR_FEATURE(ENDPOINT_HALT): host-to-device | standard | endpoint
        if (requestType == 0x02 && request == 0x01 && value == 0) {
            haltClears += index
            halted.set(false)
            return 0
        }
        return -1
    }

    fun countAcks(): Int =
        writes.count { it.contentEquals(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE) }

    private companion object {
        /** Length of the RPC frame header that precedes the JSON payload. */
        val HEADER = DxoOneStreamDemux.buildRpcMessage(ByteArray(0)).size
    }
}
