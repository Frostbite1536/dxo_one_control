package com.dxoone.multicam.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Represents a connection to a single DXO One camera.
 *
 * Wraps the USB protocol and provides high-level camera operations.
 *
 * Invariants:
 * - INV-MULTI-001: Camera identification (unique ID)
 * - INV-DATA-003: Connection state must match actual hardware status
 * - INV-CONS-003: Error state recovery
 */
class CameraConnection(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val controlInterface: UsbInterface,
    private val dataInterface: UsbInterface
) {
    // Unique camera identification (INV-MULTI-001)
    val id: String = device.serialNumber ?: UUID.randomUUID().toString()
    val serialNumber: String? = device.serialNumber

    // User-assignable nickname
    var nickname: String? = null

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

    private var liveViewJob: Job? = null

    // USB Protocol handler
    private var protocol: DxoOneUsbProtocol? = null

    // Endpoints
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    /**
     * Initialize the camera connection.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.INITIALIZING

            // Claim interfaces
            if (!connection.claimInterface(controlInterface, true)) {
                _connectionState.value = ConnectionState.ERROR
                return@withContext false
            }
            if (!connection.claimInterface(dataInterface, true)) {
                _connectionState.value = ConnectionState.ERROR
                return@withContext false
            }

            // Set alternate interface for data
            // Note: setInterface not directly available, handled in protocol init

            // Find endpoints
            for (i in 0 until controlInterface.endpointCount) {
                val endpoint = controlInterface.getEndpoint(i)
                if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                    endpointIn = endpoint
                } else {
                    endpointOut = endpoint
                }
            }

            if (endpointIn == null || endpointOut == null) {
                _connectionState.value = ConnectionState.ERROR
                return@withContext false
            }

            // Initialize protocol
            protocol = DxoOneUsbProtocol(connection, endpointIn!!, endpointOut!!)
            val initialized = protocol!!.initialize()

            if (initialized) {
                _connectionState.value = ConnectionState.CONNECTED
                refreshStatus()
                true
            } else {
                _connectionState.value = ConnectionState.ERROR
                false
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            false
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
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Take a photo.
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
            val response = protocol?.sendCommand(DxoOneMethods.PHOTO_TAKE)
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
            response?.result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Set a camera setting.
     */
    suspend fun setSetting(settingType: String, value: String): Boolean =
        withContext(Dispatchers.IO) {
            if (_connectionState.value != ConnectionState.CONNECTED) return@withContext false

            try {
                val params = mapOf("type" to settingType, "param" to value)
                val response = protocol?.sendCommand(DxoOneMethods.SETTING_SET, params)
                response?.isSuccess == true
            } catch (e: Exception) {
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
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Start live view streaming.
     */
    suspend fun startLiveView(onFrame: (Bitmap?) -> Unit): Job = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return@withContext Job().apply { cancel() }
        }

        // Switch to view mode
        protocol?.sendCommand(
            DxoOneMethods.CAMERA_MODE_SWITCH,
            mapOf("param" to "view")
        )

        // Start frame loop using coroutine scope
        val scope = CoroutineScope(Dispatchers.IO)
        liveViewJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                try {
                    val jpegData = protocol?.receiveJpegFrame()
                    if (jpegData != null && jpegData.isNotEmpty()) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            jpegData, 0, jpegData.size
                        )
                        _liveViewFrame.value = bitmap
                        onFrame(bitmap)
                    }
                } catch (e: Exception) {
                    // Continue trying
                }
            }
        }

        liveViewJob!!
    }

    /**
     * Stop live view streaming.
     */
    fun stopLiveView() {
        liveViewJob?.cancel()
        liveViewJob = null
        _liveViewFrame.value = null
    }

    /**
     * Get the last captured file path.
     */
    suspend fun getLastFilePath(): String? = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) return@withContext null

        try {
            val response = protocol?.sendCommand(DxoOneMethods.FS_LAST_FILE_GET)
            response?.result?.get("path") as? String
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
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if camera is ready for capture.
     */
    fun isReady(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /**
     * Disconnect and clean up resources.
     */
    fun disconnect() {
        stopLiveView()
        try {
            connection.releaseInterface(controlInterface)
            connection.releaseInterface(dataInterface)
            connection.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        _connectionState.value = ConnectionState.DISCONNECTED
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
