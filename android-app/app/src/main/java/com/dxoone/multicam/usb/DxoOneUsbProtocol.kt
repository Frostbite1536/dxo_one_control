package com.dxoone.multicam.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB Protocol implementation for DXO One camera.
 *
 * Handles low-level USB communication including:
 * - JSON-RPC message encoding/decoding
 * - Binary framing with headers
 * - Bulk transfer operations
 *
 * Ported from dxo1usb.js WebUSB implementation.
 *
 * Invariants:
 * - INV-DATA-001: USB messages must be valid byte arrays with correct length
 * - INV-SEC-003: Command validation before transmission
 * - INV-CONS-003: Error state recovery
 */
class DxoOneUsbProtocol(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    private var sequenceId = 0

    /**
     * Initialize the connection by draining any existing data and sending ACK.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Send initial ACK
            transferOut(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)

            // Drain any pre-existing data from device buffer
            var buffer: ByteArray
            do {
                buffer = transferIn(DxoOneConstants.MAX_PACKET_SIZE)
                if (buffer.contentEquals(DxoOneConstants.METADATA_INIT_SIGNATURE)) {
                    transferOut(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)
                    break
                }
            } while (buffer.isNotEmpty())

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Send a JSON-RPC command and receive the response.
     *
     * @param method The JSON-RPC method name (validated against safe commands)
     * @param params Optional parameters for the method
     * @return JsonRpcResponse or null on failure
     */
    suspend fun sendCommand(
        method: String,
        params: Map<String, Any>? = null
    ): JsonRpcResponse? = withContext(Dispatchers.IO) {
        // INV-SEC-003: Validate command before transmission
        if (!DxoOneMethods.isValidCommand(method)) {
            throw IllegalArgumentException("Unknown command: $method")
        }

        try {
            // Send ACK first
            transferOut(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)

            // Build JSON-RPC request
            val request = JsonRpcRequest(
                id = sequenceId++,
                method = method,
                params = params
            )
            val payload = (request.toJson() + "\u0000").toByteArray(Charsets.UTF_8)

            // Build message with headers (INV-DATA-001: proper message format)
            val message = buildRpcMessage(payload)
            transferOut(message)

            // Receive response
            receiveRpcResponse()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build a complete RPC message with proper headers.
     * Format: RPC_HEADER + message_size(little-endian) + TRAILER + payload
     */
    private fun buildRpcMessage(payload: ByteArray): ByteArray {
        // Message size in little-endian (2 bytes)
        val sizeBytes = byteArrayOf(
            (payload.size and 0xFF).toByte(),
            ((payload.size shr 8) and 0xFF).toByte()
        )

        // Build message details: size + trailer
        val msgDetails = sizeBytes + DxoOneConstants.RPC_HEADER_TRAILER

        // Complete message: header + details + payload
        return DxoOneConstants.RPC_HEADER + msgDetails + payload
    }

    /**
     * Receive and parse a JSON-RPC response.
     */
    private suspend fun receiveRpcResponse(): JsonRpcResponse? {
        var metadata = transferIn(DxoOneConstants.MAX_PACKET_SIZE)

        // Handle metadata init signature if present
        if (metadata.contentEquals(DxoOneConstants.METADATA_INIT_SIGNATURE)) {
            transferOut(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)
            metadata = transferIn(DxoOneConstants.MAX_PACKET_SIZE)
        }

        if (metadata.size < 32) return null

        // Extract response size from little-endian bytes at offset 8-9
        val responseSize = (metadata[8].toInt() and 0xFF) or
            ((metadata[9].toInt() and 0xFF) shl 8)

        if (responseSize == 0) return null

        // Collect response data
        val responseBuffer = ByteArray(responseSize)
        val initialData = metadata.copyOfRange(32, metadata.size)
        System.arraycopy(initialData, 0, responseBuffer, 0,
            minOf(initialData.size, responseSize))
        var offset = initialData.size

        // Read remaining data if needed
        while (offset < responseSize) {
            val payload = transferIn(DxoOneConstants.MAX_PACKET_SIZE)

            if (payload.contentEquals(DxoOneConstants.METADATA_INIT_SIGNATURE)) {
                transferOut(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)
                break
            }

            val copySize = minOf(payload.size, responseSize - offset)
            System.arraycopy(payload, 0, responseBuffer, offset, copySize)
            offset += payload.size
        }

        // Parse JSON response
        val jsonString = responseBuffer.toString(Charsets.UTF_8)
            .replace("\u0000", "")
            .trim()

        val response = JsonRpcResponse.fromJson(jsonString)

        // Handle USB flush notification by retrying
        if (response?.method == DxoOneMethods.USB_FLUSH_FORCED) {
            return receiveRpcResponse()
        }

        return response
    }

    /**
     * Receive a JPEG frame from live view stream.
     */
    suspend fun receiveJpegFrame(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            var metadata = transferIn(DxoOneConstants.MAX_PACKET_SIZE)

            // Handle metadata init signature
            if (metadata.contentEquals(DxoOneConstants.METADATA_INIT_SIGNATURE)) {
                transferOut(DxoOneConstants.METADATA_INIT_RESPONSE_SIGNATURE)
                metadata = transferIn(DxoOneConstants.MAX_PACKET_SIZE)
            }

            // Check for JPEG metadata header and extract initial data
            val jpegBuffer = mutableListOf<Byte>()
            val headerIndex = findSubarray(metadata, DxoOneConstants.JPG_METADATA_HEADER)
            if (headerIndex >= 0) {
                // Skip the 32-byte header
                metadata.drop(32).forEach { jpegBuffer.add(it) }
            } else {
                metadata.forEach { jpegBuffer.add(it) }
            }

            // Continue reading until we find JPEG trailer (FFD9)
            while (true) {
                val payload = transferIn(DxoOneConstants.MAX_PACKET_SIZE)
                payload.forEach { jpegBuffer.add(it) }

                val data = jpegBuffer.toByteArray()
                if (findSubarray(data, DxoOneConstants.JPG_TRAILER) >= 0) {
                    break
                }
            }

            // Extract valid JPEG data between header and trailer
            val data = jpegBuffer.toByteArray()
            val headerIdx = findSubarray(data, DxoOneConstants.JPG_HEADER)
            val trailerIdx = findSubarray(data, DxoOneConstants.JPG_TRAILER, headerIdx + 1)

            if (headerIdx >= 0 && trailerIdx > headerIdx) {
                data.copyOfRange(headerIdx, trailerIdx + 2)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Low-level USB bulk transfer OUT.
     */
    private fun transferOut(data: ByteArray): Int {
        return connection.bulkTransfer(
            endpointOut,
            data,
            data.size,
            DxoOneConstants.USB_TIMEOUT_MS
        )
    }

    /**
     * Low-level USB bulk transfer IN.
     */
    private fun transferIn(maxSize: Int): ByteArray {
        val buffer = ByteArray(maxSize)
        val bytesRead = connection.bulkTransfer(
            endpointIn,
            buffer,
            maxSize,
            DxoOneConstants.USB_TIMEOUT_MS
        )
        return if (bytesRead > 0) buffer.copyOf(bytesRead) else ByteArray(0)
    }

    /**
     * Find the index of a subarray within an array.
     */
    private fun findSubarray(array: ByteArray, subarray: ByteArray, startIndex: Int = 0): Int {
        if (subarray.isEmpty()) return -1
        outer@ for (i in startIndex..array.size - subarray.size) {
            for (j in subarray.indices) {
                if (array[i + j] != subarray[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        private const val TAG = "DxoOneUsbProtocol"
    }
}
