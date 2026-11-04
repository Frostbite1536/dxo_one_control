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
    /** Timeout for a single bulk-OUT write. */
    const val USB_TIMEOUT_MS = 5000
    /** Time to wait for the reply to an ordinary RPC command. */
    const val COMMAND_TIMEOUT_MS = 10000
    /** Time to wait for the reply to dxo_photo_take (covers long exposures). */
    const val CAPTURE_TIMEOUT_MS = 30000
    /** Poll timeout of each bulk-IN read in the reader thread (keeps stop() responsive). */
    const val READ_POLL_TIMEOUT_MS = 250
    /** How long initialization waits for the camera's init signature before proceeding. */
    const val INIT_SIGNATURE_WAIT_MS = 2000L
    /** Bounded wait for the reader thread to exit in stop(). */
    const val READER_JOIN_TIMEOUT_MS = 1000L

    // Dead-device detection: bulkTransfer returns -1 for both timeout and error, so a
    // run of -1 results that each return almost immediately means the device is gone.
    const val DEAD_DEVICE_FAST_FAIL_MS = 5L
    const val DEAD_DEVICE_FAST_FAIL_COUNT = 50

    // Multi-camera limits (INV-MULTI-002)
    const val MAX_CAMERAS = 4
}
