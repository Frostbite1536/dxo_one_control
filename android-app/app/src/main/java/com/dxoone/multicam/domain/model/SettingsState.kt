package com.dxoone.multicam.domain.model

/**
 * UI state for the Settings screen.
 */
data class SettingsUiState(
    val currentSettings: CameraSettings = CameraSettings(),
    val isApplying: Boolean = false,
    val applyToAllCameras: Boolean = true,
    val selectedCameraId: String? = null,
    val connectedCameraIds: List<String> = emptyList(),
    val error: String? = null,
    val successMessage: String? = null
)

/**
 * Events from the Settings UI.
 */
sealed class SettingsEvent {
    // Image format
    data class SetImageFormat(val format: ImageFormat) : SettingsEvent()

    // Shooting mode
    data class SetShootingMode(val mode: ShootingMode) : SettingsEvent()

    // Exposure settings
    data class SetIso(val iso: IsoSetting) : SettingsEvent()
    data class SetAperture(val aperture: ApertureSetting) : SettingsEvent()
    data class SetExposureTime(val time: ExposureTimeSetting) : SettingsEvent()
    data class SetEvBias(val bias: EvBiasSetting) : SettingsEvent()

    // Focus settings
    data class SetFocusMode(val mode: FocusMode) : SettingsEvent()
    data class SetAfMode(val mode: AfMode) : SettingsEvent()

    // Other settings
    data class SetWhiteBalanceIntensity(val intensity: WhiteBalanceIntensity) : SettingsEvent()
    data class SetJpegQuality(val quality: JpegQuality) : SettingsEvent()
    data class SetSelfTimer(val timer: SelfTimerSetting) : SettingsEvent()
    data class SetLiveViewEnabled(val enabled: Boolean) : SettingsEvent()

    // Apply settings
    data class SetApplyToAllCameras(val applyToAll: Boolean) : SettingsEvent()
    data class SelectCamera(val cameraId: String?) : SettingsEvent()
    data object ApplySettings : SettingsEvent()
    data object ResetToDefaults : SettingsEvent()

    // Dismiss messages
    data object DismissError : SettingsEvent()
    data object DismissSuccess : SettingsEvent()
}
