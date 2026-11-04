package com.dxoone.multicam.service

import com.dxoone.multicam.domain.model.CameraSettings
import com.dxoone.multicam.domain.model.FocusMode
import com.dxoone.multicam.domain.model.ShootingMode
import com.dxoone.multicam.usb.CameraConnection

/**
 * Sends a [CameraSettings] profile to a camera, checking every command's result.
 *
 * Shared by the Settings screen (explicit "Apply") and by the connect path, which applies
 * the saved profile to every newly connected camera so all cameras shoot with the same
 * settings. Live view is not touched here; callers decide whether to stream.
 */
object CameraSettingsApplier {

    /** Outcome for one camera: the setting types the camera did not confirm. */
    data class Result(val failedSettings: List<String>) {
        val succeeded: Boolean get() = failedSettings.isEmpty()
    }

    suspend fun apply(camera: CameraConnection, settings: CameraSettings): Result {
        val commands = buildList {
            // Image format (RAW + TNR)
            add("raw" to settings.imageFormat.rawParam)
            add("tnr" to settings.imageFormat.tnrParam)

            add("shooting_mode" to settings.shootingMode.param)

            // Always sent, including "auto", so switching back from a manual value
            // actually reaches the camera (dxo1usb.js sends { type: "iso", param: "auto" }).
            add("iso" to settings.iso.param)

            // Aperture / shutter only exist in the modes that use them.
            if (settings.shootingMode == ShootingMode.APERTURE_PRIORITY ||
                settings.shootingMode == ShootingMode.MANUAL
            ) {
                add("aperture" to settings.aperture.param)
            }
            if (settings.shootingMode == ShootingMode.SHUTTER_PRIORITY ||
                settings.shootingMode == ShootingMode.MANUAL
            ) {
                add("exposure_time" to settings.exposureTime.param)
            }

            add("ev_bias" to settings.evBias.param)
            add("still_focusing_mode" to settings.focusMode.param)
            if (settings.focusMode == FocusMode.AUTO_FOCUS) {
                add("af_mode" to settings.afMode.param)
            }
            add("lighting_intensity" to settings.whiteBalanceIntensity.param)
            add("photo_quality" to settings.jpegQuality.param)
            add("selftimer" to settings.selfTimer.param)
        }

        val failed = commands
            .filterNot { (type, value) -> camera.setSetting(type, value) }
            .map { (type, _) -> type }

        return Result(failed)
    }
}
