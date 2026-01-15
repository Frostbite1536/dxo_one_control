package com.dxoone.multicam.usb

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * JSON-RPC 2.0 message structures for DXO One camera communication.
 *
 * The DXO One uses JSON-RPC 2.0 over USB for command/response communication.
 *
 * Invariants:
 * - INV-SEC-003: Command validation before transmission
 * - INV-DATA-001: USB messages must be valid byte arrays
 */

/**
 * JSON-RPC 2.0 Request message.
 */
data class JsonRpcRequest(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",

    @SerializedName("id")
    val id: Int,

    @SerializedName("method")
    val method: String,

    @SerializedName("params")
    val params: Map<String, Any>? = null
) {
    fun toJson(): String = Gson().toJson(this)
}

/**
 * JSON-RPC 2.0 Response message.
 */
data class JsonRpcResponse(
    @SerializedName("jsonrpc")
    val jsonrpc: String? = null,

    @SerializedName("id")
    val id: Int? = null,

    @SerializedName("result")
    val result: Map<String, Any>? = null,

    @SerializedName("error")
    val error: JsonRpcError? = null,

    @SerializedName("method")
    val method: String? = null
) {
    val isSuccess: Boolean get() = error == null
    val isNotification: Boolean get() = method != null

    companion object {
        private val gson = Gson()

        fun fromJson(json: String): JsonRpcResponse? {
            return try {
                gson.fromJson(json, JsonRpcResponse::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * JSON-RPC 2.0 Error object.
 */
data class JsonRpcError(
    @SerializedName("code")
    val code: Int,

    @SerializedName("message")
    val message: String,

    @SerializedName("data")
    val data: Any? = null
)

/**
 * Known DXO One JSON-RPC methods (INV-SEC-003: validated command set).
 */
object DxoOneMethods {
    // Camera control
    const val PHOTO_TAKE = "dxo_photo_take"
    const val CAMERA_STATUS_GET = "dxo_camera_status_get"
    const val ALL_SETTINGS_GET = "dxo_all_settings_get"
    const val SETTING_SET = "dxo_setting_set"
    const val CAMERA_MODE_SWITCH = "dxo_camera_mode_switch"
    const val CAMERA_POWEROFF = "dxo_camera_poweroff"
    const val IDLE = "dxo_idle"

    // Focus
    const val TAP_TO_FOCUS = "dxo_tap_to_focus"
    const val DIGITAL_ZOOM_GET = "dxo_digital_zoom_get"

    // File system
    const val FS_LAST_FILE_GET = "dxo_fs_last_file_get"
    const val FS_CANCEL_GET = "dxo_fs_cancel_get"

    // GPS
    const val GPS_DATA_SET = "dxo_gps_data_set"

    // Notifications from camera
    const val USB_FLUSH_FORCED = "dxo_usb_flush_forced"
    const val SETTING_APPLIED = "dxo_setting_applied"

    /**
     * Set of all valid command methods for validation.
     */
    val VALID_COMMANDS = setOf(
        PHOTO_TAKE, CAMERA_STATUS_GET, ALL_SETTINGS_GET, SETTING_SET,
        CAMERA_MODE_SWITCH, CAMERA_POWEROFF, IDLE, TAP_TO_FOCUS,
        DIGITAL_ZOOM_GET, FS_LAST_FILE_GET, FS_CANCEL_GET, GPS_DATA_SET
    )

    /**
     * Validates a command method against the known-safe command set.
     * INV-SEC-003: Command validation before transmission.
     */
    fun isValidCommand(method: String): Boolean {
        return method in VALID_COMMANDS
    }
}
