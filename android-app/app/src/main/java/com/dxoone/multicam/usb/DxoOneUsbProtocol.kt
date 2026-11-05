package com.dxoone.multicam.usb

import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

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
 * All USB access goes through [io] (a [UsbDeviceConnectionBulkIo] in production), so the
 * transport can be unit-tested against a fake device.
 *
 * Ported from dxo1usb.js; the JS transport (DxoTransport.js) follows the same rules.
 *
 * Invariants:
 * - INV-DATA-001: USB messages must be valid byte arrays with correct length
 * - INV-SEC-003: Command validation before transmission
 * - INV-CONS-003: Error state recovery
 */
class DxoOneUsbProtocol(
    private val io: UsbBulkIo,
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

    // ACKs requested by the camera are written off the reader thread, so a command holding
    // the write lock never stops bulk-IN from being drained.
    private val ackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dxo-usb-ack-$name").apply { isDaemon = true }
    }

    private val demux = DxoOneStreamDemux(
        events = object : DxoOneStreamDemux.Events {
            override fun onInitSignature() {
                try {
                    ackExecutor.execute {
                        try {
                            writeAck()
                        } catch (e: IOException) {
                            Log.w(TAG, "[$name] failed to ACK init signature", e)
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    // Transport is stopping.
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
     * The timeout covers the wait for the reply (not the wait for the mutex, nor
     * [beforeWrite]).
     *
     * @param beforeWrite called with this camera's request lock held, after the frame has
     *   been built and encoded and just before it is written. A multi-camera capture uses it
     *   to hold every camera at a [CaptureBarrier] so their writes start together. It must be
     *   bounded: the request lock stays held while it runs.
     * @param onWritten called with a [System.nanoTime] reading taken right after the request
     *   frame was fully written to bulk-OUT, before the reply arrives. Not called if the write
     *   fails. This is the closest host-side proxy for when the camera received the command;
     *   there is no feedback on when it actually acted on it.
     * @return the matched reply (check [JsonRpcResponse.isSuccess])
     * @throws IllegalArgumentException for an unknown command
     * @throws DxoCommandTimeoutException if no reply arrives within [timeoutMs]
     * @throws DxoTransportClosedException if the transport is stopped or the device died
     * @throws IOException if a write fails
     */
    suspend fun sendCommand(
        method: String,
        params: Map<String, Any>? = null,
        timeoutMs: Long = defaultTimeoutFor(method),
        beforeWrite: (suspend () -> Unit)? = null,
        onWritten: ((writtenAtNanos: Long) -> Unit)? = null
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

            // Wait (if asked) before registering the request, so no reply can be attributed
            // to it before it is on the wire.
            beforeWrite?.invoke()

            val deferred = CompletableDeferred<JsonRpcResponse>()
            demux.registerRequest(id) { deferred.complete(it) }
            inFlight = deferred
            try {
                // The device may have died between ensureOpen() and registration (or while
                // waiting in beforeWrite).
                ensureOpen()
                val writtenAt = withContext(Dispatchers.IO) {
                    synchronized(writeLock) {
                        writeFully(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)
                        writeFully(message)
                        System.nanoTime()
                    }
                }
                onWritten?.invoke(writtenAt)
                withTimeoutOrNull(timeoutMs) { deferred.await() }
                    ?: run {
                        demux.markRequestTimedOut(id)
                        throw DxoCommandTimeoutException(method, timeoutMs)
                    }
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
        ackExecutor.shutdown()
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
        // Let a queued/running ACK finish before the caller closes the connection, so no
        // bulk-OUT races the native close.
        try {
            if (!ackExecutor.awaitTermination(DxoOneConstants.ACK_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "[$name] ACK writer did not finish within ${DxoOneConstants.ACK_DRAIN_TIMEOUT_MS}ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
        var haltClears = 0 // since the last successful read
        try {
            while (running) {
                val started = System.nanoTime()
                val n = io.bulkIn(buffer, buffer.size, DxoOneConstants.READ_POLL_TIMEOUT_MS)
                if (!running) break
                when {
                    n > 0 -> {
                        fastFailures = 0
                        haltClears = 0
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
                            // bulkTransfer returns -1 immediately both for a halted (STALLed)
                            // endpoint and for a gone device. Try clearing the halt first; a
                            // gone device fails the control transfer and keeps counting.
                            if (fastFailures == DxoOneConstants.STALL_CLEAR_AFTER_FAILURES &&
                                haltClears < DxoOneConstants.MAX_HALT_CLEARS &&
                                clearHalt(io.inEndpointAddress)
                            ) {
                                haltClears++
                                Log.w(TAG, "[$name] bulk-IN halted; cleared (attempt $haltClears)")
                                fastFailures = 0
                                continue
                            }
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

    /**
     * Must be called with [writeLock] held. A failed transfer (-1, e.g. a halted endpoint)
     * gets one CLEAR_FEATURE(ENDPOINT_HALT) and one retry.
     */
    private fun writeFully(data: ByteArray) {
        val started = System.nanoTime()
        var n = io.bulkOut(data, data.size, DxoOneConstants.USB_TIMEOUT_MS)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // Only an immediate failure looks like a halt. After a timeout part of a multi-packet
        // frame may already have gone out, and clearing an endpoint that isn't halted resets
        // its data toggle, so don't clear-and-resend then.
        if (n < 0 && elapsedMs < DxoOneConstants.DEAD_DEVICE_FAST_FAIL_MS && clearHalt(io.outEndpointAddress)) {
            Log.w(TAG, "[$name] bulk-OUT halted; cleared, retrying")
            n = io.bulkOut(data, data.size, DxoOneConstants.USB_TIMEOUT_MS)
        }
        if (n != data.size) {
            throw IOException("bulk-OUT wrote $n of ${data.size} bytes")
        }
    }

    /**
     * Standard CLEAR_FEATURE(ENDPOINT_HALT) request to [endpointAddress] (USB 2.0 §9.4.1). Android
     * has no public clearHalt(); the kernel's usbfs resets the host-side endpoint state when
     * this request succeeds.
     *
     * @return true if the device accepted the request
     */
    private fun clearHalt(endpointAddress: Int): Boolean {
        val result = io.controlTransfer(
            USB_RECIP_ENDPOINT_OUT, // host-to-device | standard | endpoint
            USB_REQ_CLEAR_FEATURE,
            USB_FEATURE_ENDPOINT_HALT,
            endpointAddress,
            null,
            0,
            DxoOneConstants.USB_TIMEOUT_MS
        )
        return result >= 0
    }

    companion object {
        private const val TAG = "DxoOneUsbProtocol"

        private const val USB_RECIP_ENDPOINT_OUT = 0x02
        private const val USB_REQ_CLEAR_FEATURE = 0x01
        private const val USB_FEATURE_ENDPOINT_HALT = 0x00

        fun defaultTimeoutFor(method: String): Long =
            if (method == DxoOneMethods.PHOTO_TAKE) {
                DxoOneConstants.CAPTURE_TIMEOUT_MS.toLong()
            } else {
                DxoOneConstants.COMMAND_TIMEOUT_MS.toLong()
            }
    }
}
