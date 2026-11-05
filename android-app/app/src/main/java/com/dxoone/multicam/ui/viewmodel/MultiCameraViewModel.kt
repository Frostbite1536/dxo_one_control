package com.dxoone.multicam.ui.viewmodel

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dxoone.multicam.data.SettingsPreferences
import com.dxoone.multicam.domain.model.CameraEvent
import com.dxoone.multicam.domain.model.CameraUiState
import com.dxoone.multicam.domain.model.MultiCameraUiState
import com.dxoone.multicam.service.CameraSettingsApplier
import com.dxoone.multicam.service.CaptureMode
import com.dxoone.multicam.service.SyncCaptureEngine
import com.dxoone.multicam.usb.CameraConnection
import com.dxoone.multicam.usb.ConnectionState
import com.dxoone.multicam.usb.DxoOneConstants
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val usbDeviceManager: UsbDeviceManager,
    private val settingsPreferences: SettingsPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiCameraUiState())
    val uiState: StateFlow<MultiCameraUiState> = _uiState.asStateFlow()

    private val captureEngine = SyncCaptureEngine()

    // One-shot messages (capture summaries) for the snackbar. A channel, not state: each
    // message is delivered once, buffered while no one is collecting, and never replayed
    // when the screen is recomposed or navigated back to.
    private val _captureMessages = Channel<String>(Channel.BUFFERED)
    val captureMessages: Flow<String> = _captureMessages.receiveAsFlow()

    // Available USB devices (not yet connected)
    private var availableDevices: List<UsbDevice> = emptyList()

    // Cameras selected for capture (by camera ID). A StateFlow so updates are atomic
    // regardless of which thread they come from.
    private val selectedCameraIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Per-camera UI state, derived from each camera's own flows (connection state,
     * battery, live-view frame, nickname) plus the selection. Any change on any camera
     * reaches the UI, not just changes that happen to coincide with a new frame.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cameraStates: Flow<List<CameraUiState>> = usbDeviceManager.connectedCameras
        .flatMapLatest { cameras ->
            if (cameras.isEmpty()) {
                flowOf(emptyList<CameraUiState>())
            } else {
                combine(cameras.values.map(::cameraStateFlow)) { it.toList() }
            }
        }
        .combine(selectedCameraIds) { states, selected ->
            states.map { it.copy(isSelected = it.id in selected) }
        }

    init {
        // Check USB Host support
        _uiState.update {
            it.copy(isUsbHostSupported = usbDeviceManager.isUsbHostSupported())
        }

        // Receivers are registered once per process (DxoOneMultiCamApp); just rescan.
        usbDeviceManager.refreshDevices()

        // Observe available devices
        viewModelScope.launch {
            usbDeviceManager.availableDevices.collect { devices ->
                availableDevices = devices
                _uiState.update {
                    it.copy(availableDeviceCount = devices.size)
                }
            }
        }

        // Drop selections for cameras that went away
        viewModelScope.launch {
            usbDeviceManager.connectedCameras.collect { cameras ->
                selectedCameraIds.update { it intersect cameras.keys }
            }
        }

        // Publish camera state
        viewModelScope.launch {
            cameraStates.collect { states ->
                _uiState.update { it.copy(cameras = states) }
            }
        }
    }

    private fun cameraStateFlow(camera: CameraConnection): Flow<CameraUiState> = combine(
        camera.connectionState,
        camera.batteryLevel,
        camera.liveViewFrame,
        camera.nicknameFlow
    ) { connectionState, battery, frame, nickname ->
        CameraUiState(
            id = camera.id,
            displayName = camera.displayName,
            nickname = nickname,
            connectionState = connectionState,
            batteryLevel = battery,
            liveViewFrame = frame
        )
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
                return@launch
            }

            // Bring the new camera in line with the saved profile so every camera in the
            // rig shoots with the same settings, then start live view if enabled.
            val settings = settingsPreferences.settingsFlow.first()
            try {
                val result = CameraSettingsApplier.apply(connection, settings)
                if (!result.succeeded) {
                    _uiState.update {
                        it.copy(
                            error = "${connection.displayName}: saved settings not confirmed " +
                                "(${result.failedSettings.joinToString()})"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "${connection.displayName}: failed to apply saved settings")
                }
            }

            if (settings.liveViewEnabled && !connection.isLiveViewActive) {
                // Frames reach the UI through connection.liveViewFrame (see cameraStates).
                connection.startLiveView { }
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
        // Observed through CameraConnection.nicknameFlow.
        usbDeviceManager.getCamera(cameraId)?.nickname = newName.ifBlank { null }
    }

    private fun captureAll() {
        val allCameras = usbDeviceManager.connectedCameras.value

        // Filter to selected cameras only, or all if none selected
        val selected = selectedCameraIds.value
        val cameras = if (selected.isNotEmpty()) {
            allCameras.filterKeys { it in selected }.values.toList()
        } else {
            allCameras.values.toList()
        }

        if (cameras.isEmpty()) return

        _uiState.update { it.copy(isCapturing = true) }

        viewModelScope.launch {
            try {
                val results = captureEngine.capture(cameras, _uiState.value.captureMode)

                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        lastCaptureResult = results
                    )
                }
                _captureMessages.trySend(results.summaryText())
            } catch (e: CancellationException) {
                throw e
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
        selectedCameraIds.update { if (cameraId in it) it - cameraId else it + cameraId }
    }

    /**
     * Select all connected cameras.
     */
    private fun selectAllCameras() {
        selectedCameraIds.value = usbDeviceManager.connectedCameras.value.keys
    }

    /**
     * Deselect all cameras (will capture all when none selected).
     */
    private fun deselectAllCameras() {
        selectedCameraIds.value = emptySet()
    }

    // Cameras are not disconnected in onCleared(): they are owned by the process-wide
    // UsbDeviceManager and kept alive by CameraManagerService, so they survive the
    // Activity being recreated or backgrounded.
}
