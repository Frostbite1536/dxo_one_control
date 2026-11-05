package com.dxoone.multicam.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/** [UsbBulkIo] over a real [UsbDeviceConnection] and its two bulk endpoints. */
class UsbDeviceConnectionBulkIo(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) : UsbBulkIo {
    override val inEndpointAddress: Int = endpointIn.address
    override val outEndpointAddress: Int = endpointOut.address

    override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(endpointIn, buffer, length, timeoutMs)

    override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(endpointOut, data, length, timeoutMs)

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int
    ): Int = connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
}
