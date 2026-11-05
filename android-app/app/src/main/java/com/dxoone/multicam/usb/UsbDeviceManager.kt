package com.dxoone.multicam.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages USB device discovery and connection for DXO One cameras.
 *
 * Handles:
 * - Device enumeration and filtering by vendor ID
 * - Permission requests
 * - Connection/disconnection events
 *
 * Invariants:
 * - INV-SEC-001: Only connect to verified DXO One vendor ID
 * - INV-SEC-002: User permission required for USB access
 * - INV-MULTI-002: Maximum 4 cameras supported
 */
@Singleton
class UsbDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _availableDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val availableDevices: StateFlow<List<UsbDevice>> = _availableDevices.asStateFlow()

    private val _connectedCameras = MutableStateFlow<Map<String, CameraConnection>>(emptyMap())
    val connectedCameras: StateFlow<Map<String, CameraConnection>> = _connectedCameras.asStateFlow()

    private val pendingPermissionCallbacks = mutableMapOf<String, (Boolean) -> Unit>()

    /** Guards receiver registration so [initialize] is idempotent. */
    private val initialized = AtomicBoolean(false)

    /** Serializes connects so the camera limit and duplicate checks can't race. */
    private val connectMutex = Mutex()

    /**
     * Connections disconnected recently, by device node. Their release may still be running
     * on a background thread; a reconnect to the same device waits for it.
     */
    private val releasing = java.util.concurrent.ConcurrentHashMap<String, CameraConnection>()

    private fun disconnectTracked(camera: CameraConnection) {
        releasing[camera.device.deviceName] = camera
        camera.disconnect()
    }

    companion object {
        private const val TAG = "UsbDeviceManager"
        private const val ACTION_USB_PERMISSION = "com.dxoone.multicam.USB_PERMISSION"
    }

    // USB permission broadcast receiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false
                        )

                        device?.let {
                            val deviceName = it.deviceName
                            pendingPermissionCallbacks[deviceName]?.invoke(granted)
                            pendingPermissionCallbacks.remove(deviceName)
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { handleDeviceDetached(it) }
                    refreshDevices()
                }
            }
        }
    }

    /**
     * Initialize the USB device manager and register receivers.
     *
     * Idempotent: called once per process from [com.dxoone.multicam.DxoOneMultiCamApp];
     * later calls only refresh the device list.
     */
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) {
            refreshDevices()
            return
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // Not exported on every API level: the only senders are the system (attach /
        // detach) and our own permission PendingIntent. ContextCompat applies the flag on
        // API 33+ and an equivalent signature-permission guard below that.
        ContextCompat.registerReceiver(
            context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        refreshDevices()
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        if (initialized.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                // Receiver may not be registered
            }
        }

        // Disconnect all cameras
        _connectedCameras.value.values.forEach { disconnectTracked(it) }
        _connectedCameras.value = emptyMap()
    }

    /**
     * Refresh the list of available DXO One devices.
     * INV-SEC-001: Only includes devices with DXO One vendor ID.
     */
    fun refreshDevices() {
        val devices = usbManager.deviceList.values
            .filter { it.vendorId == DxoOneConstants.VENDOR_ID }
            .toList()
        _availableDevices.value = devices
    }

    /**
     * Check if we have permission to access a device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Request permission to access a USB device.
     * INV-SEC-002: Requires explicit user permission.
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (hasPermission(device)) {
            callback(true)
            return
        }

        pendingPermissionCallbacks[device.deviceName] = callback

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        // The intent must be explicit: Android 14+ (targetSdk 34) rejects a
        // FLAG_MUTABLE PendingIntent wrapping an implicit intent. It must stay
        // mutable because UsbManager fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED.
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )

        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Connect to a DXO One device.
     *
     * @param device The USB device to connect to
     * @return CameraConnection if successful, null otherwise
     *
     * INV-MULTI-002: Enforces maximum camera limit
     */
    suspend fun connectDevice(device: UsbDevice): CameraConnection? = connectMutex.withLock {
        connectDeviceLocked(device)
    }

    private suspend fun connectDeviceLocked(device: UsbDevice): CameraConnection? {
        // A previous connection to this device may still be releasing its interfaces.
        releasing.remove(device.deviceName)?.let { previous ->
            val done = withContext(Dispatchers.IO) {
                previous.awaitReleased(DxoOneConstants.RELEASE_WAIT_TIMEOUT_MS)
            }
            if (!done) Log.w(TAG, "Previous connection to ${device.deviceName} still releasing")
        }

        // Already connected (same USB device node): reuse instead of opening it twice.
        _connectedCameras.value.values.firstOrNull { it.device.deviceName == device.deviceName }
            ?.let { return it }

        // INV-MULTI-002: Check maximum camera limit
        if (_connectedCameras.value.size >= DxoOneConstants.MAX_CAMERAS) {
            return null
        }

        // INV-SEC-001: Verify vendor ID
        if (device.vendorId != DxoOneConstants.VENDOR_ID) {
            return null
        }

        // Check permission
        if (!hasPermission(device)) {
            return null
        }

        // Open connection
        val connection = usbManager.openDevice(device) ?: return null

        // Create camera connection. It locates and claims its interfaces itself
        // (device.getInterface(i) is a descriptor index, not an interface number) and
        // closes the connection if initialization fails.
        val cameraConnection = CameraConnection(
            device = device,
            connection = connection
        )

        // Initialize
        val success = cameraConnection.initialize()
        if (success) {
            _connectedCameras.update { it + (cameraConnection.id to cameraConnection) }
            return cameraConnection
        } else {
            disconnectTracked(cameraConnection)
            return null
        }
    }

    /**
     * Disconnect a camera by ID.
     */
    fun disconnectCamera(cameraId: String) {
        val cameras = _connectedCameras.value.toMutableMap()
        cameras[cameraId]?.let { disconnectTracked(it) }
        cameras.remove(cameraId)
        _connectedCameras.value = cameras
    }

    /**
     * Disconnect all cameras.
     */
    fun disconnectAll() {
        _connectedCameras.value.values.forEach { disconnectTracked(it) }
        _connectedCameras.value = emptyMap()
    }

    /**
     * Handle device detachment.
     */
    private fun handleDeviceDetached(device: UsbDevice) {
        // Match by device node only: reading device.serialNumber here can throw
        // SecurityException once the system has revoked access to a detached device.
        val cameras = _connectedCameras.value.toMutableMap()

        // Find and remove disconnected camera
        val disconnectedId = cameras.entries
            .find { it.value.device.deviceName == device.deviceName }
            ?.key

        disconnectedId?.let {
            cameras[it]?.let { c -> disconnectTracked(c) }
            cameras.remove(it)
            _connectedCameras.value = cameras
        }
    }

    /**
     * Get a connected camera by ID.
     */
    fun getCamera(cameraId: String): CameraConnection? {
        return _connectedCameras.value[cameraId]
    }

    /**
     * Get the number of connected cameras.
     */
    fun getConnectedCount(): Int = _connectedCameras.value.size

    /**
     * Check if USB Host is supported on this device.
     * INV-CONS-001: Check for feature availability.
     */
    fun isUsbHostSupported(): Boolean {
        return context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_USB_HOST
        )
    }
}
