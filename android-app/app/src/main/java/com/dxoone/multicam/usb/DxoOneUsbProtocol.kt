package com.dxoone.multicam.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** No reply to an RPC request arrived within its timeout. */
class DxoCommandTimeoutException(val method: String, val timeoutMs: Long) :
    IOException("No reply to $method within ${timeoutMs}ms")

/** The transport was stopped or the device stopped responding. */
class DxoTransportClosedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * USB transport for one DXO One camera.
 *
 * Owns the bulk endpoints of a single camera:
 * - ONE reader, on a dedicated thread, is the only code that reads bulk-IN. Every packet
 *   goes through [DxoOneStreamDemux], which routes RPC replies to the waiting request,
 *   notifications to [onNotification] and live-view frames to [onJpegFrame].
 * - All writes go through one write lock and are checked for a full-length transfer.
 * - [sendCommand] allows only one RPC in flight per camera (request mutex), so replies
 *   without an id can still be attributed safely.
 *
 * Callbacks are invoked on the reader thread and must not block.
 *
 * Ported from dxo1usb.js; the JS transport (DxoTransport.js) follows the same rules.
 *
 * Invariants:
 * - INV-DATA-001: USB messages must be valid byte arrays with correct length
 * - INV-SEC-003: Command validation before transmission
 * - INV-CONS-003: Error state recovery
 */
class DxoOneUsbProtocol(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val name: String = "camera",
    private val onJpegFrame: (ByteArray) -> Unit = {},
    private val onNotification: (method: String, message: JsonObject) -> Unit = { _, _ -> },
    private val onFatalError: (Throwable) -> Unit = {}
) {
    private val writeLock = Any()
    private val requestMutex = Mutex()
    private var nextId = 0 // guarded by requestMutex

    @Volatile private var running = false
    @Volatile private var fatalError: Throwable? = null
    @Volatile private var inFlight: CompletableDeferred<JsonRpcResponse>? = null
    private var readerThread: Thread? = null
    private val initSignatureSeen = CompletableDeferred<Unit>()

    private val demux = DxoOneStreamDemux(
        events = object : DxoOneStreamDemux.Events {
            override fun onInitSignature() {
                try {
                    writeAck()
                } catch (e: IOException) {
                    Log.w(TAG, "[$name] failed to ACK init signature", e)
                }
                initSignatureSeen.complete(Unit)
            }

            override fun onNotification(method: String, message: JsonObject) {
                Log.d(TAG, "[$name] notification: $method")
                this@DxoOneUsbProtocol.onNotification(method, message)
            }

            override fun onJpegFrame(frame: ByteArray) {
                this@DxoOneUsbProtocol.onJpegFrame(frame)
            }
        },
        log = { Log.w(TAG, "[$name] $it") }
    )

    val isRunning: Boolean get() = running

    /**
     * Start the reader thread, send the initial ACK and wait (bounded) for the camera's
     * init signature. A missing init signature only produces a warning.
     *
     * @throws IOException if the initial ACK cannot be written
     */
    suspend fun initialize() {
        startReader()
        withContext(Dispatchers.IO) { writeAck() }
        val seen = withTimeoutOrNull(DxoOneConstants.INIT_SIGNATURE_WAIT_MS) {
            initSignatureSeen.await()
        }
        if (seen == null) {
            Log.w(
                TAG,
                "[$name] no init signature within ${DxoOneConstants.INIT_SIGNATURE_WAIT_MS}ms; proceeding"
            )
        }
    }

    /** Enable/disable live-view accumulation. Video bytes are discarded while disabled. */
    fun setLiveViewEnabled(enabled: Boolean) {
        demux.liveViewEnabled = enabled
    }

    /**
     * Send a JSON-RPC command and wait for its reply.
     *
     * Only one request is in flight per camera; concurrent callers queue on a mutex.
     * The timeout covers the wait for the reply (not the wait for the mutex).
     *
     * @return the matched reply (check [JsonRpcResponse.isSuccess])
     * @throws IllegalArgumentException for an unknown command
     * @throws DxoCommandTimeoutException if no reply arrives within [timeoutMs]
     * @throws DxoTransportClosedException if the transport is stopped or the device died
     * @throws IOException if a write fails
     */
    suspend fun sendCommand(
        method: String,
        params: Map<String, Any>? = null,
        timeoutMs: Long = defaultTimeoutFor(method)
    ): JsonRpcResponse {
        // INV-SEC-003: Validate command before transmission
        if (!DxoOneMethods.isValidCommand(method)) {
            throw IllegalArgumentException("Unknown command: $method")
        }

        return requestMutex.withLock {
            ensureOpen()
            val id = nextId++
            val payload = (JsonRpcRequest(id = id, method = method, params = params).toJson() +
                "\u0000").toByteArray(Charsets.UTF_8)
            // INV-DATA-001: proper message format
            val message = DxoOneStreamDemux.buildRpcMessage(payload)

            val deferred = CompletableDeferred<JsonRpcResponse>()
            demux.registerRequest(id) { deferred.complete(it) }
            inFlight = deferred
            try {
                // The device may have died between ensureOpen() and registration.
                ensureOpen()
                withContext(Dispatchers.IO) {
                    synchronized(writeLock) {
                        writeFully(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)
                        writeFully(message)
                    }
                }
                withTimeoutOrNull(timeoutMs) { deferred.await() }
                    ?: throw DxoCommandTimeoutException(method, timeoutMs)
            } finally {
                inFlight = null
                demux.unregisterRequest(id)
            }
        }
    }

    /**
     * Stop the reader thread (bounded join) and fail the in-flight request.
     * Must be called BEFORE releasing interfaces / closing the connection.
     */
    fun stop() {
        running = false
        failInFlight(DxoTransportClosedException("Transport stopped"))
        val thread = readerThread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(DxoOneConstants.READER_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                Log.w(TAG, "[$name] reader thread did not exit within ${DxoOneConstants.READER_JOIN_TIMEOUT_MS}ms")
            }
        }
        readerThread = null
    }

    // ---------------------------------------------------------------------------------
    // Reader
    // ---------------------------------------------------------------------------------

    private fun startReader() {
        if (running) return
        running = true
        readerThread = Thread({ readLoop() }, "dxo-usb-reader-$name").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(DxoOneConstants.MAX_PACKET_SIZE)
        var fastFailures = 0
        try {
            while (running) {
                val started = System.nanoTime()
                val n = connection.bulkTransfer(
                    endpointIn, buffer, buffer.size, DxoOneConstants.READ_POLL_TIMEOUT_MS
                )
                if (!running) break
                when {
                    n > 0 -> {
                        fastFailures = 0
                        try {
                            demux.feed(buffer, n)
                        } catch (e: Exception) {
                            Log.e(TAG, "[$name] demux failed on a $n-byte packet", e)
                        }
                    }
                    n < 0 -> {
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000
                        if (elapsedMs < DxoOneConstants.DEAD_DEVICE_FAST_FAIL_MS) {
                            fastFailures++
                            if (fastFailures >= DxoOneConstants.DEAD_DEVICE_FAST_FAIL_COUNT) {
                                fail(
                                    DxoTransportClosedException(
                                        "Device not responding ($fastFailures immediate bulk-IN failures)"
                                    )
                                )
                                break
                            }
                        } else {
                            fastFailures = 0 // an ordinary poll timeout
                        }
                    }
                    // n == 0: zero-length packet, nothing to do
                }
            }
        } catch (t: Throwable) {
            if (running) fail(t)
        }
    }

    private fun fail(error: Throwable) {
        if (fatalError != null) return
        fatalError = error
        running = false
        Log.e(TAG, "[$name] transport failed", error)
        failInFlight(
            error as? DxoTransportClosedException
                ?: DxoTransportClosedException(error.message ?: "Transport failed", error)
        )
        try {
            onFatalError(error)
        } catch (e: Exception) {
            Log.e(TAG, "[$name] onFatalError callback threw", e)
        }
    }

    private fun failInFlight(error: Throwable) {
        inFlight?.completeExceptionally(error)
    }

    private fun ensureOpen() {
        fatalError?.let { throw DxoTransportClosedException("Transport failed: ${it.message}", it) }
        if (!running) throw DxoTransportClosedException("Transport not running")
    }

    // ---------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------

    private fun writeAck() {
        synchronized(writeLock) { writeFully(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE) }
    }

    /** Must be called with [writeLock] held. */
    private fun writeFully(data: ByteArray) {
        val n = connection.bulkTransfer(endpointOut, data, data.size, DxoOneConstants.USB_TIMEOUT_MS)
        if (n != data.size) {
            throw IOException("bulk-OUT wrote $n of ${data.size} bytes")
        }
    }

    companion object {
        private const val TAG = "DxoOneUsbProtocol"

        fun defaultTimeoutFor(method: String): Long =
            if (method == DxoOneMethods.PHOTO_TAKE) {
                DxoOneConstants.CAPTURE_TIMEOUT_MS.toLong()
            } else {
                DxoOneConstants.COMMAND_TIMEOUT_MS.toLong()
            }
    }
}
