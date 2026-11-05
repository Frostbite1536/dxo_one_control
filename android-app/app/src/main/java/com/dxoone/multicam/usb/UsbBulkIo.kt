package com.dxoone.multicam.usb

/**
 * The USB operations [DxoOneUsbProtocol] needs: one bulk-IN and one bulk-OUT endpoint plus
 * control transfers on the same device. Production uses [UsbDeviceConnectionBulkIo]; tests
 * use a fake, since android.hardware.usb types can't be constructed in JVM unit tests.
 *
 * Return values follow [android.hardware.usb.UsbDeviceConnection]: the number of bytes
 * transferred, or a negative value on failure (timeout, halted endpoint, gone device).
 */
interface UsbBulkIo {
    /** Address of the bulk-IN endpoint (direction bit set), e.g. 0x81. */
    val inEndpointAddress: Int

    /** Address of the bulk-OUT endpoint, e.g. 0x01. */
    val outEndpointAddress: Int

    /** Read up to [length] bytes from bulk-IN into [buffer]. */
    fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int

    /** Write the first [length] bytes of [data] to bulk-OUT. */
    fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int): Int

    /** A control transfer on endpoint 0, as UsbDeviceConnection.controlTransfer. */
    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int
    ): Int
}
