package com.dxoone.multicam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dxoone.multicam.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-based preferences for persisting camera settings.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_settings")

@Singleton
class SettingsPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Settings keys
        private val KEY_IMAGE_FORMAT = stringPreferencesKey("image_format")
        private val KEY_SHOOTING_MODE = stringPreferencesKey("shooting_mode")
        private val KEY_ISO = stringPreferencesKey("iso")
        private val KEY_APERTURE = stringPreferencesKey("aperture")
        private val KEY_EXPOSURE_TIME = stringPreferencesKey("exposure_time")
        private val KEY_EV_BIAS = stringPreferencesKey("ev_bias")
        private val KEY_FOCUS_MODE = stringPreferencesKey("focus_mode")
        private val KEY_AF_MODE = stringPreferencesKey("af_mode")
        private val KEY_WHITE_BALANCE = stringPreferencesKey("white_balance_intensity")
        private val KEY_JPEG_QUALITY = stringPreferencesKey("jpeg_quality")
        private val KEY_SELF_TIMER = stringPreferencesKey("self_timer")
        private val KEY_LIVE_VIEW_ENABLED = booleanPreferencesKey("live_view_enabled")
        private val KEY_APPLY_TO_ALL = booleanPreferencesKey("apply_to_all_cameras")
    }

    /**
     * Flow of camera settings from DataStore.
     */
    val settingsFlow: Flow<CameraSettings> = context.dataStore.data.map { preferences ->
        CameraSettings(
            imageFormat = preferences[KEY_IMAGE_FORMAT]?.let { enumValueOf<ImageFormat>(it) }
                ?: ImageFormat.JPEG_ONLY,
            shootingMode = preferences[KEY_SHOOTING_MODE]?.let { enumValueOf<ShootingMode>(it) }
                ?: ShootingMode.PROGRAM,
            iso = preferences[KEY_ISO]?.let { enumValueOf<IsoSetting>(it) }
                ?: IsoSetting.AUTO,
            aperture = preferences[KEY_APERTURE]?.let { enumValueOf<ApertureSetting>(it) }
                ?: ApertureSetting.F1_8,
            exposureTime = preferences[KEY_EXPOSURE_TIME]?.let { enumValueOf<ExposureTimeSetting>(it) }
                ?: ExposureTimeSetting.AUTO,
            evBias = preferences[KEY_EV_BIAS]?.let { enumValueOf<EvBiasSetting>(it) }
                ?: EvBiasSetting.ZERO,
            focusMode = preferences[KEY_FOCUS_MODE]?.let { enumValueOf<FocusMode>(it) }
                ?: FocusMode.AUTO_FOCUS,
            afMode = preferences[KEY_AF_MODE]?.let { enumValueOf<AfMode>(it) }
                ?: AfMode.AF_SINGLE,
            whiteBalanceIntensity = preferences[KEY_WHITE_BALANCE]?.let { enumValueOf<WhiteBalanceIntensity>(it) }
                ?: WhiteBalanceIntensity.MEDIUM,
            jpegQuality = preferences[KEY_JPEG_QUALITY]?.let { enumValueOf<JpegQuality>(it) }
                ?: JpegQuality.FINE,
            selfTimer = preferences[KEY_SELF_TIMER]?.let { enumValueOf<SelfTimerSetting>(it) }
                ?: SelfTimerSetting.OFF,
            liveViewEnabled = preferences[KEY_LIVE_VIEW_ENABLED] ?: true
        )
    }

    /**
     * Flow for apply-to-all-cameras preference.
     */
    val applyToAllFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_APPLY_TO_ALL] ?: true
    }

    /**
     * Save camera settings to DataStore.
     */
    suspend fun saveSettings(settings: CameraSettings) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IMAGE_FORMAT] = settings.imageFormat.name
            preferences[KEY_SHOOTING_MODE] = settings.shootingMode.name
            preferences[KEY_ISO] = settings.iso.name
            preferences[KEY_APERTURE] = settings.aperture.name
            preferences[KEY_EXPOSURE_TIME] = settings.exposureTime.name
            preferences[KEY_EV_BIAS] = settings.evBias.name
            preferences[KEY_FOCUS_MODE] = settings.focusMode.name
            preferences[KEY_AF_MODE] = settings.afMode.name
            preferences[KEY_WHITE_BALANCE] = settings.whiteBalanceIntensity.name
            preferences[KEY_JPEG_QUALITY] = settings.jpegQuality.name
            preferences[KEY_SELF_TIMER] = settings.selfTimer.name
            preferences[KEY_LIVE_VIEW_ENABLED] = settings.liveViewEnabled
        }
    }

    /**
     * Save apply-to-all preference.
     */
    suspend fun saveApplyToAll(applyToAll: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_APPLY_TO_ALL] = applyToAll
        }
    }

    /**
     * Reset settings to defaults.
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
