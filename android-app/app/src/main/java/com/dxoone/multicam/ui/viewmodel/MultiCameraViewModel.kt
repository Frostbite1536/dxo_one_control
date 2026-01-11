package com.dxoone.multicam.ui.viewmodel

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dxoone.multicam.domain.model.CameraEvent
import com.dxoone.multicam.domain.model.CameraUiState
import com.dxoone.multicam.domain.model.MultiCameraUiState
import com.dxoone.multicam.service.CaptureMode
import com.dxoone.multicam.service.MultiCaptureResult
import com.dxoone.multicam.usb.CameraConnection
import com.dxoone.multicam.usb.ConnectionState
import com.dxoone.multicam.usb.DxoOneConstants
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the multi-camera screen.
 *
 * Manages camera connections, capture operations, and UI state.
 *
 * Invariants:
 * - INV-MULTI-002: Maximum 4 cameras enforced
 * - INV-DATA-003: Connection state reflects actual hardware
 */
@HiltViewModel
class MultiCameraViewModel @Inject constructor(
    private val usbDeviceManager: UsbDeviceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiCameraUiState())
    val uiState: StateFlow<MultiCameraUiState> = _uiState.asStateFlow()

    private val _captureResult = MutableStateFlow<MultiCaptureResult?>(null)
    val captureResult: StateFlow<MultiCaptureResult?> = _captureResult.asStateFlow()

    // Available USB devices (not yet connected)
    private var availableDevices: List<UsbDevice> = emptyList()

    // Track which cameras are selected for capture (by camera ID)
    private val selectedCameraIds = mutableSetOf<String>()

    init {
        // Check USB Host support
        _uiState.update {
            it.copy(isUsbHostSupported = usbDeviceManager.isUsbHostSupported())
        }

        // Initialize USB device manager
        usbDeviceManager.initialize()

        // Observe available devices
        viewModelScope.launch {
            usbDeviceManager.availableDevices.collect { devices ->
                availableDevices = devices
                _uiState.update {
                    it.copy(availableDeviceCount = devices.size)
                }
            }
        }

        // Observe connected cameras
        viewModelScope.launch {
            usbDeviceManager.connectedCameras.collect { cameras ->
                updateCameraStates(cameras.values.toList())
            }
        }
    }

    /**
     * Handle UI events.
     */
    fun onEvent(event: CameraEvent) {
        when (event) {
            is CameraEvent.RefreshDevices -> refreshDevices()
            is CameraEvent.ConnectDevice -> connectDevice(event.deviceIndex)
            is CameraEvent.DisconnectCamera -> disconnectCamera(event.cameraId)
            is CameraEvent.DisconnectAll -> disconnectAll()
            is CameraEvent.RenameCamera -> renameCamera(event.cameraId, event.newName)
            is CameraEvent.CaptureAll -> captureAll()
            is CameraEvent.SetCaptureMode -> setCaptureMode(event.mode)
            is CameraEvent.DismissError -> dismissError()
            is CameraEvent.PreFocusAll -> preFocusAll()
            is CameraEvent.ToggleCameraSelection -> toggleCameraSelection(event.cameraId)
            is CameraEvent.SelectAllCameras -> selectAllCameras()
            is CameraEvent.DeselectAllCameras -> deselectAllCameras()
        }
    }

    private fun refreshDevices() {
        usbDeviceManager.refreshDevices()
    }

    private fun connectDevice(deviceIndex: Int) {
        if (deviceIndex >= availableDevices.size) return

        // INV-MULTI-002: Check maximum camera limit
        if (usbDeviceManager.getConnectedCount() >= DxoOneConstants.MAX_CAMERAS) {
            _uiState.update {
                it.copy(error = "Maximum ${DxoOneConstants.MAX_CAMERAS} cameras supported")
            }
            return
        }

        val device = availableDevices[deviceIndex]

        // Request permission if needed
        if (!usbDeviceManager.hasPermission(device)) {
            usbDeviceManager.requestPermission(device) { granted ->
                if (granted) {
                    performConnect(device)
                } else {
                    _uiState.update {
                        it.copy(error = "USB permission denied")
                    }
                }
            }
        } else {
            performConnect(device)
        }
    }

    private fun performConnect(device: UsbDevice) {
        viewModelScope.launch {
            val connection = usbDeviceManager.connectDevice(device)
            if (connection == null) {
                _uiState.update {
                    it.copy(error = "Failed to connect to camera")
                }
            }
        }
    }

    private fun disconnectCamera(cameraId: String) {
        usbDeviceManager.disconnectCamera(cameraId)
    }

    private fun disconnectAll() {
        usbDeviceManager.disconnectAll()
    }

    private fun renameCamera(cameraId: String, newName: String) {
        usbDeviceManager.getCamera(cameraId)?.nickname = newName.ifBlank { null }
        // Force UI update
        updateCameraStates(usbDeviceManager.connectedCameras.value.values.toList())
    }

    private fun captureAll() {
        val allCameras = usbDeviceManager.connectedCameras.value

        // Filter to selected cameras only, or all if none selected
        val cameras = if (selectedCameraIds.isNotEmpty()) {
            allCameras.filterKeys { it in selectedCameraIds }.values.toList()
        } else {
            allCameras.values.toList()
        }

        if (cameras.isEmpty()) return

        _uiState.update { it.copy(isCapturing = true) }

        viewModelScope.launch {
            try {
                val results = when (_uiState.value.captureMode) {
                    CaptureMode.PARALLEL -> captureParallel(cameras)
                    CaptureMode.SEQUENTIAL -> captureSequential(cameras)
                }

                _captureResult.value = results
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        lastCaptureResult = results
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        error = "Capture failed: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun captureParallel(cameras: List<CameraConnection>): MultiCaptureResult =
        coroutineScope {
            val sessionId = java.util.UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()

            // Launch parallel capture jobs
            val deferredResults = cameras.map { camera ->
                async { camera.takePhoto() }
            }

            // Await all results
            val results = deferredResults.awaitAll()

            val endTime = System.currentTimeMillis()

            val successfulTimestamps = results.filter { it.success }.map { it.timestamp }
            val syncVariance = if (successfulTimestamps.size > 1) {
                successfulTimestamps.max() - successfulTimestamps.min()
            } else 0L

            MultiCaptureResult(
                sessionId = sessionId,
                totalCameras = cameras.size,
                results = results,
                allSucceeded = results.all { it.success },
                syncVarianceMs = syncVariance,
                totalTimeMs = endTime - startTime,
                mode = CaptureMode.PARALLEL
            )
        }

    private suspend fun captureSequential(cameras: List<CameraConnection>): MultiCaptureResult {
        val sessionId = java.util.UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val results = cameras.map { camera ->
            camera.takePhoto()
        }

        val endTime = System.currentTimeMillis()

        val successfulTimestamps = results.filter { it.success }.map { it.timestamp }
        val syncVariance = if (successfulTimestamps.size > 1) {
            successfulTimestamps.max() - successfulTimestamps.min()
        } else 0L

        return MultiCaptureResult(
            sessionId = sessionId,
            totalCameras = cameras.size,
            results = results,
            allSucceeded = results.all { it.success },
            syncVarianceMs = syncVariance,
            totalTimeMs = endTime - startTime,
            mode = CaptureMode.SEQUENTIAL
        )
    }

    private fun setCaptureMode(mode: CaptureMode) {
        _uiState.update { it.copy(captureMode = mode) }
    }

    private fun preFocusAll() {
        viewModelScope.launch {
            usbDeviceManager.connectedCameras.value.values.forEach { camera ->
                camera.focus(128, 128)
            }
        }
    }

    private fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Toggle selection state of a specific camera.
     */
    private fun toggleCameraSelection(cameraId: String) {
        if (selectedCameraIds.contains(cameraId)) {
            selectedCameraIds.remove(cameraId)
        } else {
            selectedCameraIds.add(cameraId)
        }
        // Refresh UI to show updated selection
        updateCameraStates(usbDeviceManager.connectedCameras.value.values.toList())
    }

    /**
     * Select all connected cameras.
     */
    private fun selectAllCameras() {
        selectedCameraIds.clear()
        selectedCameraIds.addAll(usbDeviceManager.connectedCameras.value.keys)
        updateCameraStates(usbDeviceManager.connectedCameras.value.values.toList())
    }

    /**
     * Deselect all cameras (will capture all when none selected).
     */
    private fun deselectAllCameras() {
        selectedCameraIds.clear()
        updateCameraStates(usbDeviceManager.connectedCameras.value.values.toList())
    }

    private fun updateCameraStates(cameras: List<CameraConnection>) {
        // Clean up selection state for disconnected cameras
        val connectedIds = cameras.map { it.id }.toSet()
        selectedCameraIds.retainAll(connectedIds)

        val cameraStates = cameras.map { camera ->
            CameraUiState(
                id = camera.id,
                displayName = camera.displayName,
                nickname = camera.nickname,
                connectionState = camera.connectionState.value,
                batteryLevel = camera.batteryLevel.value,
                liveViewFrame = camera.liveViewFrame.value,
                isSelected = camera.id in selectedCameraIds
            )
        }

        _uiState.update { it.copy(cameras = cameraStates) }
    }

    override fun onCleared() {
        super.onCleared()
        usbDeviceManager.cleanup()
    }
}
