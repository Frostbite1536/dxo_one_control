package com.dxoone.multicam.usb

/**
 * Constants for DXO One USB communication protocol.
 *
 * These values are derived from reverse-engineering the DXO One camera
 * and must match exactly for proper communication.
 *
 * Invariants:
 * - INV-SEC-001: Only connect to verified DXO One vendor/product IDs
 * - INV-DATA-001: USB messages must be valid byte arrays with correct length
 */
object DxoOneConstants {

    // USB Device Identification (INV-SEC-001)
    const val VENDOR_ID = 0x2B8F  // DXO Labs

    // USB Configuration
    const val CONFIGURATION_ID = 1
    const val INTERFACE_CONTROL = 0
    const val INTERFACE_DATA = 1
    const val ALTERNATE_SETTING = 1

    // Endpoint configuration
    const val ENDPOINT_IN = 0x81   // Bulk IN
    const val ENDPOINT_OUT = 0x02  // Bulk OUT
    const val MAX_PACKET_SIZE = 512

    // Protocol signatures
    val METADATA_INIT_SIGNATURE = byteArrayOf(
        0xA3.toByte(), 0xBA.toByte(), 0xD1.toByte(), 0x10.toByte(),
        0xAB.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0xCD.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    val METADATA_INIT_RESPONSE_SIGNATURE = byteArrayOf(
        0xA3.toByte(), 0xBA.toByte(), 0xD1.toByte(), 0x10.toByte(),
        0xDC.toByte(), 0xBA.toByte(), 0xDC.toByte(), 0xBA.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    // RPC Header for JSON-RPC messages
    val RPC_HEADER = byteArrayOf(
        0xA3.toByte(), 0xBA.toByte(), 0xD1.toByte(), 0x10.toByte(),
        0x17, 0x08, 0x00, 0x0C
    )

    val RPC_HEADER_TRAILER = byteArrayOf(
        0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    // JPEG markers for live view parsing
    val JPG_METADATA_HEADER = byteArrayOf(
        0xA3.toByte(), 0xBA.toByte(), 0xD1.toByte(), 0x10.toByte()
    )

    val JPG_HEADER = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()
    )

    val JPG_TRAILER = byteArrayOf(
        0xFF.toByte(), 0xD9.toByte()
    )

    // Timeouts (milliseconds)
    const val USB_TIMEOUT_MS = 5000
    const val COMMAND_TIMEOUT_MS = 10000
    const val CAPTURE_TIMEOUT_MS = 30000

    // Multi-camera limits (INV-MULTI-002)
    const val MAX_CAMERAS = 4
}
