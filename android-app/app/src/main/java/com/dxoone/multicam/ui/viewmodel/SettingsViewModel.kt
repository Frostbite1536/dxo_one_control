package com.dxoone.multicam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dxoone.multicam.data.SettingsPreferences
import com.dxoone.multicam.domain.model.*
import com.dxoone.multicam.service.CameraSettingsApplier
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * Manages camera settings and applies them to connected cameras.
 * Persists settings using DataStore preferences.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val usbDeviceManager: UsbDeviceManager,
    private val settingsPreferences: SettingsPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Load saved settings
        viewModelScope.launch {
            val savedSettings = settingsPreferences.settingsFlow.first()
            val applyToAll = settingsPreferences.applyToAllFlow.first()
            _uiState.update {
                it.copy(
                    currentSettings = savedSettings,
                    applyToAllCameras = applyToAll
                )
            }
        }

        // Observe connected cameras
        viewModelScope.launch {
            usbDeviceManager.connectedCameras.collect { cameras ->
                _uiState.update {
                    it.copy(connectedCameraIds = cameras.keys.toList())
                }
            }
        }
    }

    /**
     * Handle settings events.
     */
    fun onEvent(event: SettingsEvent) {
        when (event) {
            // Image format
            is SettingsEvent.SetImageFormat -> updateSettings { it.copy(imageFormat = event.format) }

            // Shooting mode
            is SettingsEvent.SetShootingMode -> updateSettings { it.copy(shootingMode = event.mode) }

            // Exposure settings
            is SettingsEvent.SetIso -> updateSettings { it.copy(iso = event.iso) }
            is SettingsEvent.SetAperture -> updateSettings { it.copy(aperture = event.aperture) }
            is SettingsEvent.SetExposureTime -> updateSettings { it.copy(exposureTime = event.time) }
            is SettingsEvent.SetEvBias -> updateSettings { it.copy(evBias = event.bias) }

            // Focus settings
            is SettingsEvent.SetFocusMode -> updateSettings { it.copy(focusMode = event.mode) }
            is SettingsEvent.SetAfMode -> updateSettings { it.copy(afMode = event.mode) }

            // Other settings
            is SettingsEvent.SetWhiteBalanceIntensity -> updateSettings {
                it.copy(whiteBalanceIntensity = event.intensity)
            }
            is SettingsEvent.SetJpegQuality -> updateSettings { it.copy(jpegQuality = event.quality) }
            is SettingsEvent.SetSelfTimer -> updateSettings { it.copy(selfTimer = event.timer) }
            is SettingsEvent.SetLiveViewEnabled -> updateSettings { it.copy(liveViewEnabled = event.enabled) }

            // Apply settings
            is SettingsEvent.SetApplyToAllCameras -> {
                _uiState.update { it.copy(applyToAllCameras = event.applyToAll) }
                viewModelScope.launch {
                    settingsPreferences.saveApplyToAll(event.applyToAll)
                }
            }
            is SettingsEvent.SelectCamera -> {
                _uiState.update { it.copy(selectedCameraId = event.cameraId) }
            }
            is SettingsEvent.ApplySettings -> applySettings()
            is SettingsEvent.ResetToDefaults -> resetToDefaults()

            // Dismiss messages
            is SettingsEvent.DismissError -> _uiState.update { it.copy(error = null) }
            is SettingsEvent.DismissSuccess -> _uiState.update { it.copy(successMessage = null) }
        }
    }

    private fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        _uiState.update {
            it.copy(currentSettings = transform(it.currentSettings))
        }
    }

    private fun applySettings() {
        val state = _uiState.value
        val settings = state.currentSettings

        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true, error = null) }

            try {
                val cameras = if (state.applyToAllCameras) {
                    usbDeviceManager.connectedCameras.value.values.toList()
                } else {
                    state.selectedCameraId?.let { id ->
                        listOfNotNull(usbDeviceManager.getCamera(id))
                    } ?: emptyList()
                }

                if (cameras.isEmpty()) {
                    _uiState.update {
                        it.copy(isApplying = false, error = "No cameras selected")
                    }
                    return@launch
                }

                var successCount = 0
                var failCount = 0
                val failedTypes = mutableSetOf<String>()

                for (camera in cameras) {
                    try {
                        val result = CameraSettingsApplier.apply(camera, settings)
                        if (result.succeeded) successCount++ else failCount++
                        failedTypes += result.failedSettings

                        // Start/stop live view based on setting. Frames reach the UI through
                        // camera.liveViewFrame; don't start a second session if one is running.
                        if (settings.liveViewEnabled) {
                            if (!camera.isLiveViewActive) camera.startLiveView { }
                        } else {
                            camera.stopLiveView()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failCount++
                    }
                }

                // Save settings to preferences after successful application
                if (successCount > 0) {
                    settingsPreferences.saveSettings(settings)
                }

                val failedDetail = if (failedTypes.isNotEmpty()) {
                    " (not confirmed: ${failedTypes.sorted().joinToString()})"
                } else ""
                val message = when {
                    failCount == 0 -> "Settings applied to $successCount camera(s)"
                    successCount == 0 -> "Failed to apply settings$failedDetail"
                    else -> "Applied to $successCount, failed on $failCount camera(s)$failedDetail"
                }

                _uiState.update {
                    it.copy(
                        isApplying = false,
                        successMessage = if (successCount > 0) message else null,
                        error = if (successCount == 0) message else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        error = "Failed to apply settings: ${e.message}"
                    )
                }
            }
        }
    }

    private fun resetToDefaults() {
        _uiState.update {
            it.copy(currentSettings = CameraSettings())
        }
        viewModelScope.launch {
            settingsPreferences.resetToDefaults()
            // Resetting also clears the stored "apply to all" flag; keep the UI in sync.
            val applyToAll = settingsPreferences.applyToAllFlow.first()
            _uiState.update { it.copy(applyToAllCameras = applyToAll) }
        }
    }
}
