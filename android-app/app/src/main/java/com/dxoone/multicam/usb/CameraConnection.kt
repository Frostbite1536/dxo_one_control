package com.dxoone.multicam.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents a connection to a single DXO One camera.
 *
 * Wraps the USB protocol and provides high-level camera operations. All bulk-IN traffic
 * (RPC replies and live-view frames) is read by the single reader inside
 * [DxoOneUsbProtocol]; this class never reads the endpoint itself.
 *
 * Invariants:
 * - INV-MULTI-001: Camera identification (unique ID)
 * - INV-DATA-003: Connection state must match actual hardware status
 * - INV-CONS-003: Error state recovery
 */
class CameraConnection(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection
) {
    // Unique camera identification (INV-MULTI-001)
    val id: String = device.serialNumber ?: UUID.randomUUID().toString()
    val serialNumber: String? = device.serialNumber

    // User-assignable nickname (observable so renames reach the UI)
    private val _nickname = MutableStateFlow<String?>(null)
    val nicknameFlow: StateFlow<String?> = _nickname.asStateFlow()
    var nickname: String?
        get() = _nickname.value
        set(value) { _nickname.value = value }

    // Display name for UI
    val displayName: String
        get() = nickname ?: "Camera (${id.takeLast(4)})"

    // Connection state (INV-DATA-003)
    private val _connectionState = MutableStateFlow(ConnectionState.INITIALIZING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Camera status
    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _cameraStatus = MutableStateFlow<CameraStatus?>(null)
    val cameraStatus: StateFlow<CameraStatus?> = _cameraStatus.asStateFlow()

    // Live view
    private val _liveViewFrame = MutableStateFlow<Bitmap?>(null)
    val liveViewFrame: StateFlow<Bitmap?> = _liveViewFrame.asStateFlow()

    /** Owns this connection's coroutines (frame decoder); cancelled in [disconnect]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Raw JPEG frames from the reader thread; conflated so a slow decoder skips frames. */
    private val jpegFrames = Channel<ByteArray>(Channel.CONFLATED)
    private var decoderJob: Job? = null

    private val liveViewMutex = Mutex() // serializes startLiveView calls
    private val liveViewStateLock = Any() // guards session/listeners/enable flag together
    @Volatile private var liveViewSession: Job? = null
    private val frameListeners = CopyOnWriteArrayList<(Bitmap?) -> Unit>()

    // USB Protocol handler
    @Volatile private var protocol: DxoOneUsbProtocol? = null

    // Claimed interfaces
    private var controlInterface: UsbInterface? = null
    private var dataInterface: UsbInterface? = null

    private val released = AtomicBoolean(false)

    /**
     * Initialize the camera connection.
     *
     * Claims interface 0 (bulk endpoints) and interface 1, selects alternate setting 1 on
     * interface 1 (as dxo1usb.js does with selectAlternateInterface(1, 1)), starts the
     * single reader and performs the init handshake. On any failure the USB connection is
     * released and closed before returning false.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.INITIALIZING
        var ok = false
        try {
            val control = findInterface(DxoOneConstants.INTERFACE_CONTROL, 0)
                ?: findInterface(DxoOneConstants.INTERFACE_CONTROL, null)
            if (control == null) {
                Log.e(TAG, "Interface ${DxoOneConstants.INTERFACE_CONTROL} not found")
                return@withContext false
            }
            if (!connection.claimInterface(control, true)) {
                Log.e(TAG, "Failed to claim interface ${control.id}")
                return@withContext false
            }
            controlInterface = control

            claimDataInterface()

            // Find bulk endpoints on the control interface
            var endpointIn: UsbEndpoint? = null
            var endpointOut: UsbEndpoint? = null
            for (i in 0 until control.endpointCount) {
                val endpoint = control.getEndpoint(i)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    if (endpointIn == null) endpointIn = endpoint
                } else {
                    if (endpointOut == null) endpointOut = endpoint
                }
            }
            if (endpointIn == null || endpointOut == null) {
                Log.e(TAG, "Bulk endpoints not found on interface ${control.id}")
                return@withContext false
            }

            val proto = DxoOneUsbProtocol(
                connection = connection,
                endpointIn = endpointIn,
                endpointOut = endpointOut,
                name = id.takeLast(4),
                onJpegFrame = { frame -> jpegFrames.trySend(frame) },
                onFatalError = { error -> onTransportFailed(error) }
            )
            protocol = proto
            startDecoder()
            proto.initialize()

            _connectionState.value = ConnectionState.CONNECTED
            ok = true
            refreshStatus()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            false
        } finally {
            if (!ok) {
                releaseHardware()
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    /**
     * Find a UsbInterface descriptor by interface number and (optionally) alternate
     * setting. device.getInterface(i) is a descriptor index, not an interface number, and
     * each alternate setting is a separate descriptor, so search them all.
     */
    private fun findInterface(interfaceId: Int, alternateSetting: Int?): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.id == interfaceId &&
                (alternateSetting == null || intf.alternateSetting == alternateSetting)
            ) {
                return intf
            }
        }
        return null
    }

    private fun claimDataInterface() {
        val alt = findInterface(DxoOneConstants.INTERFACE_DATA, DxoOneConstants.ALTERNATE_SETTING)
        val intf = alt ?: findInterface(DxoOneConstants.INTERFACE_DATA, null)
        if (intf == null) {
            Log.w(TAG, "Interface ${DxoOneConstants.INTERFACE_DATA} not found; continuing")
            return
        }
        if (!connection.claimInterface(intf, true)) {
            Log.w(TAG, "Failed to claim interface ${intf.id}; continuing")
            return
        }
        dataInterface = intf
        if (alt == null) {
            Log.w(
                TAG,
                "Interface ${DxoOneConstants.INTERFACE_DATA} has no alternate setting " +
                    "${DxoOneConstants.ALTERNATE_SETTING}; continuing with the default"
            )
            return
        }
        // UsbDeviceConnection.setInterface (API 21+) selects the alternate setting.
        if (!connection.setInterface(alt)) {
            Log.w(TAG, "setInterface(${alt.id}, alt ${alt.alternateSetting}) failed; continuing")
        }
    }

    private fun onTransportFailed(error: Throwable) {
        // Called on the reader thread: only update state, never block here.
        Log.e(TAG, "Camera $displayName transport failed: ${error.message}")
        protocol?.setLiveViewEnabled(false)
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Refresh camera status including battery level.
     */
    suspend fun refreshStatus(): CameraStatus? = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) return@withContext null

        try {
            val response = protocol?.sendCommand(DxoOneMethods.CAMERA_STATUS_GET)
            if (response?.isSuccess == true) {
                val result = response.result ?: return@withContext null

                val status = CameraStatus(
                    batteryLevel = (result["battery_level"] as? Double)?.toInt(),
                    sdCardPresent = result["sd_card_present"] as? Boolean ?: false,
                    sdCardSpace = (result["sd_card_free_space"] as? Double)?.toLong(),
                    isRecording = result["is_recording"] as? Boolean ?: false,
                    currentMode = result["current_mode"] as? String
                )

                _cameraStatus.value = status
                _batteryLevel.value = status.batteryLevel
                status
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refreshStatus failed: ${e.message}")
            null
        }
    }

    /**
     * Take a photo. Waits up to [DxoOneConstants.CAPTURE_TIMEOUT_MS] for the reply.
     *
     * @return CaptureResult with timing and status information
     */
    suspend fun takePhoto(): CaptureResult = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return@withContext CaptureResult(
                cameraId = id,
                success = false,
                error = "Camera not connected"
            )
        }

        val startTime = System.currentTimeMillis()

        try {
            val response = protocol?.sendCommand(
                DxoOneMethods.PHOTO_TAKE,
                timeoutMs = DxoOneConstants.CAPTURE_TIMEOUT_MS.toLong()
            )
            val endTime = System.currentTimeMillis()

            if (response?.isSuccess == true) {
                CaptureResult(
                    cameraId = id,
                    success = true,
                    timestamp = startTime,
                    captureTimeMs = endTime - startTime
                )
            } else {
                CaptureResult(
                    cameraId = id,
                    success = false,
                    error = response?.error?.message ?: "Capture failed",
                    timestamp = startTime
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DxoCommandTimeoutException) {
            CaptureResult(
                cameraId = id,
                success = false,
                error = "Capture timed out after ${e.timeoutMs}ms",
                timestamp = startTime
            )
        } catch (e: Exception) {
            CaptureResult(
                cameraId = id,
                success = false,
                error = e.message ?: "Unknown error",
                timestamp = startTime
            )
        }
    }

    /**
     * Get all camera settings.
     */
    suspend fun getAllSettings(): Map<String, Any>? = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) return@withContext null

        try {
            val response = protocol?.sendCommand(DxoOneMethods.ALL_SETTINGS_GET)
            if (response?.isSuccess == true) response.result else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getAllSettings failed: ${e.message}")
            null
        }
    }

    /**
     * Set a camera setting.
     *
     * The camera first sends a dxo_setting_applied notification, then the real reply;
     * the transport routes the notification away, so the response here is the reply.
     * Mirrors dxo1usb.js bindApplySetting: if the reply carries result.type it must match.
     */
    suspend fun setSetting(settingType: String, value: String): Boolean =
        withContext(Dispatchers.IO) {
            if (_connectionState.value != ConnectionState.CONNECTED) return@withContext false

            try {
                val params = mapOf("type" to settingType, "param" to value)
                val response = protocol?.sendCommand(DxoOneMethods.SETTING_SET, params)
                    ?: return@withContext false
                if (!response.isSuccess) return@withContext false
                val resultType = response.result?.get("type")
                resultType == null || resultType == settingType
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setSetting($settingType) failed: ${e.message}")
                false
            }
        }

    /**
     * Trigger tap-to-focus at specified coordinates.
     * Origin is bottom-left corner. Coordinates are in 256x256 space.
     */
    suspend fun focus(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) return@withContext false

        try {
            val params = mapOf("param" to "[$x,$y,256,256]")
            val response = protocol?.sendCommand(DxoOneMethods.TAP_TO_FOCUS, params)
            response?.isSuccess == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "focus failed: ${e.message}")
            false
        }
    }

    /**
     * Start live view streaming.
     *
     * Idempotent: while a live-view session is active, further calls register [onFrame]
     * as an additional listener and return the existing session Job; no second loop is
     * started. Frames are produced by the transport's single reader, decoded on this
     * connection's scope and published to [liveViewFrame] and to every listener.
     *
     * @return the live-view session Job (completes on [stopLiveView] / [disconnect]);
     *         an already-cancelled Job if live view could not be started.
     */
    suspend fun startLiveView(onFrame: (Bitmap?) -> Unit): Job = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return@withContext Job().apply { cancel() }
        }
        val proto = protocol ?: return@withContext Job().apply { cancel() }

        liveViewMutex.withLock {
            liveViewSession?.let { session ->
                if (session.isActive) {
                    frameListeners.addIfAbsent(onFrame)
                    return@withLock session
                }
            }

            // Open the session and enable accumulation BEFORE switching modes: the camera
            // may start streaming right after its reply, and those frames must not be lost.
            val session = Job(scope.coroutineContext[Job])
            synchronized(liveViewStateLock) {
                frameListeners.clear()
                frameListeners.add(onFrame)
                liveViewSession = session
                proto.setLiveViewEnabled(true)
            }

            val switched = try {
                // Switch to view mode
                val response = proto.sendCommand(
                    DxoOneMethods.CAMERA_MODE_SWITCH,
                    mapOf("param" to "view")
                )
                if (!response.isSuccess) {
                    Log.w(TAG, "dxo_camera_mode_switch(view) reply not successful: ${response.error?.message}")
                }
                true
            } catch (e: CancellationException) {
                abortLiveView(session)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to switch to view mode: ${e.message}")
                false
            }

            if (!switched) abortLiveView(session)
            // If stopLiveView() ran meanwhile, the session is already cancelled.
            session
        }
    }

    private fun abortLiveView(session: Job) {
        synchronized(liveViewStateLock) {
            session.cancel()
            if (liveViewSession === session) {
                protocol?.setLiveViewEnabled(false)
                liveViewSession = null
                frameListeners.clear()
            }
        }
    }

    /** True while a live-view session is running. */
    val isLiveViewActive: Boolean
        get() = liveViewSession?.isActive == true

    /**
     * Stop live view streaming.
     *
     * Disables frame accumulation; video bytes that still arrive are discarded by the
     * transport. TODO: also leave view mode on the camera side once the command for it is
     * known (dxo1usb.js has the same TODO); none is known, so none is sent.
     */
    fun stopLiveView() {
        synchronized(liveViewStateLock) {
            protocol?.setLiveViewEnabled(false)
            liveViewSession?.cancel()
            liveViewSession = null
            frameListeners.clear()
        }
        _liveViewFrame.value = null
    }

    private fun startDecoder() {
        if (decoderJob != null) return
        decoderJob = scope.launch {
            for (jpeg in jpegFrames) {
                if (liveViewSession?.isActive != true) continue
                val bitmap = try {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (liveViewSession?.isActive != true) continue
                _liveViewFrame.value = bitmap
                for (listener in frameListeners) {
                    try {
                        listener(bitmap)
                    } catch (e: Exception) {
                        Log.w(TAG, "Live view listener threw", e)
                    }
                }
            }
        }
    }

    /**
     * Get the last captured file path.
     */
    suspend fun getLastFilePath(): String? = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) return@withContext null

        try {
            val response = protocol?.sendCommand(DxoOneMethods.FS_LAST_FILE_GET)
            if (response?.isSuccess == true) response.result?.get("path") as? String else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Put camera to sleep/idle mode.
     */
    suspend fun sleep(): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) return@withContext false

        try {
            val response = protocol?.sendCommand(DxoOneMethods.IDLE)
            response?.isSuccess == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Power off the camera.
     */
    suspend fun powerOff(): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) return@withContext false

        try {
            val response = protocol?.sendCommand(DxoOneMethods.CAMERA_POWEROFF)
            _connectionState.value = ConnectionState.DISCONNECTED
            response?.isSuccess == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if camera is ready for capture.
     */
    fun isReady(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /**
     * Disconnect and clean up resources. Safe to call more than once.
     *
     * Releasing joins the reader thread (up to READER_JOIN_TIMEOUT_MS), so when called on
     * the main thread the release runs on a short-lived background thread instead of
     * blocking the UI (e.g. "Disconnect all" with four cameras).
     */
    fun disconnect() {
        stopLiveView()
        _connectionState.value = ConnectionState.DISCONNECTED
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread({ releaseHardware() }, "dxo-usb-release-$id").apply {
                isDaemon = true
                start()
            }
        } else {
            releaseHardware()
        }
    }

    /**
     * Stop the reader (joined, bounded) BEFORE releasing interfaces and closing the
     * connection. Idempotent.
     */
    private fun releaseHardware() {
        if (!released.compareAndSet(false, true)) return
        protocol?.setLiveViewEnabled(false)
        try {
            protocol?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping transport", e)
        }
        try {
            dataInterface?.let { connection.releaseInterface(it) }
            controlInterface?.let { connection.releaseInterface(it) }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        try {
            connection.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        jpegFrames.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "CameraConnection"
    }
}

/**
 * Camera connection states.
 */
enum class ConnectionState {
    DISCONNECTED,
    INITIALIZING,
    CONNECTED,
    ERROR
}

/**
 * Camera status information.
 */
data class CameraStatus(
    val batteryLevel: Int?,
    val sdCardPresent: Boolean,
    val sdCardSpace: Long?,
    val isRecording: Boolean,
    val currentMode: String?
)

/**
 * Result of a capture operation.
 */
data class CaptureResult(
    val cameraId: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val captureTimeMs: Long = 0,
    val filePath: String? = null,
    val error: String? = null
)
