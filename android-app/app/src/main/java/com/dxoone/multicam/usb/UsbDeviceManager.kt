package com.dxoone.multicam.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     */
    fun initialize() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        refreshDevices()
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Receiver may not be registered
        }

        // Disconnect all cameras
        _connectedCameras.value.values.forEach { it.disconnect() }
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

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
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
    suspend fun connectDevice(device: UsbDevice): CameraConnection? {
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

        // Get interfaces
        val controlInterface = device.getInterface(DxoOneConstants.INTERFACE_CONTROL)
        val dataInterface = device.getInterface(DxoOneConstants.INTERFACE_DATA)

        // Create camera connection
        val cameraConnection = CameraConnection(
            device = device,
            connection = connection,
            controlInterface = controlInterface,
            dataInterface = dataInterface
        )

        // Initialize
        val success = cameraConnection.initialize()
        if (success) {
            val cameras = _connectedCameras.value.toMutableMap()
            cameras[cameraConnection.id] = cameraConnection
            _connectedCameras.value = cameras
            return cameraConnection
        } else {
            cameraConnection.disconnect()
            return null
        }
    }

    /**
     * Disconnect a camera by ID.
     */
    fun disconnectCamera(cameraId: String) {
        val cameras = _connectedCameras.value.toMutableMap()
        cameras[cameraId]?.disconnect()
        cameras.remove(cameraId)
        _connectedCameras.value = cameras
    }

    /**
     * Disconnect all cameras.
     */
    fun disconnectAll() {
        _connectedCameras.value.values.forEach { it.disconnect() }
        _connectedCameras.value = emptyMap()
    }

    /**
     * Handle device detachment.
     */
    private fun handleDeviceDetached(device: UsbDevice) {
        val deviceSerial = device.serialNumber
        val cameras = _connectedCameras.value.toMutableMap()

        // Find and remove disconnected camera
        val disconnectedId = cameras.entries
            .find { it.value.device.deviceName == device.deviceName }
            ?.key

        disconnectedId?.let {
            cameras[it]?.disconnect()
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
