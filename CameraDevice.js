/*
    CameraDevice.js - Individual camera device wrapper for multi-camera support
    https://github.com/jsyang/dxo1control

    This class wraps a single DXO One camera connection with state tracking
    for use in multi-camera scenarios.
*/

import { getU8AFromHexString, compareU8A, mergeU8A, getStringFromU8A } from './u8a.js';

const METADATA_INIT_SIGNATURE = getU8AFromHexString('A3, BA, D1, 10, AB, CD, AB, CD, 00, 00, 00, 00, 02, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00');
const METADATA_INIT_RESPONSE_SIGNATURE = getU8AFromHexString('A3, BA, D1, 10, DC, BA, DC, BA, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00');

const RPC_HEADER = getU8AFromHexString('A3, BA, D1, 10, 17, 08, 00, 0C');
const RPC_HEADER_TRAILER = getU8AFromHexString('00, 00, 03, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00');

const JPG_METADATA_HEADER = getU8AFromHexString('A3, BA, D1, 10');
const JPG_HEADER = getU8AFromHexString('FF, D8, FF');
const JPG_TRAILER = getU8AFromHexString('FF, D9');

const MAX_PACKETSIZE = 512;

/**
 * CameraDevice - Manages a single DXO One camera connection
 *
 * Provides per-camera state tracking, command execution, and error handling.
 * Designed for use with CameraManager for multi-camera scenarios.
 *
 * @example
 * const camera = new CameraDevice(usbDevice);
 * await camera.initialize();
 * await camera.takePhoto();
 */
export class CameraDevice {
    /**
     * Creates a new CameraDevice instance
     *
     * @param {USBDevice} usbDevice - The WebUSB device handle
     * @param {string} [nickname] - User-assigned name for the camera
     */
    constructor(usbDevice, nickname = null) {
        // INV-DATA-003: Connection state must accurately reflect hardware status
        this.device = usbDevice;
        this.isConnected = false;
        this.isInitialized = false;
        this.lastError = null;
        this.nickname = nickname;

        // Unique identifier - prefer serial number, fallback to device info
        this.id = usbDevice.serialNumber ||
                  `${usbDevice.vendorId}-${usbDevice.productId}-${Date.now()}`;

        // USB endpoints
        this.inEndpoint = null;
        this.outEndpoint = null;

        // RPC sequence tracking
        this.seq = 0;

        // Live view state
        this.lastJPEGFrame = new Uint8Array(0);
        this.shouldStopLiveView = false;
        this.isLiveViewActive = false;

        // Camera metadata cache
        this.settings = null;
        this.status = null;
        this.batteryLevel = null;
    }

    /**
     * Gets the display name for this camera
     *
     * @returns {string} Nickname if set, otherwise a generated name from ID
     */
    get displayName() {
        if (this.nickname) return this.nickname;
        if (this.device.serialNumber) {
            return `Camera (${this.device.serialNumber.slice(-4)})`;
        }
        return `Camera ${this.id.slice(-8)}`;
    }

    /**
     * Initializes the USB connection to the camera
     *
     * Opens the device, claims interfaces, and establishes communication.
     *
     * @returns {Promise<void>}
     * @throws {Error} If initialization fails
     */
    async initialize() {
        try {
            await this.device.open();

            await this.device.selectConfiguration(1);
            await this.device.claimInterface(this.device.configuration.interfaces[0].interfaceNumber);
            await this.device.claimInterface(this.device.configuration.interfaces[1].interfaceNumber);
            await this.device.selectAlternateInterface(1, 1);

            this.inEndpoint = this.device.configuration.interfaces[0].alternate.endpoints[1].endpointNumber;
            this.outEndpoint = this.device.configuration.interfaces[0].alternate.endpoints[0].endpointNumber;

            await this.device.transferOut(this.outEndpoint, METADATA_INIT_RESPONSE_SIGNATURE);

            // Drain any pre-existing data from the device's outbound buffer
            let initDrainRXBuffer = [];
            do {
                initDrainRXBuffer = await this._getRX(MAX_PACKETSIZE);

                if (compareU8A(initDrainRXBuffer, METADATA_INIT_SIGNATURE)) {
                    await this.device.transferOut(this.outEndpoint, METADATA_INIT_RESPONSE_SIGNATURE);
                    break;
                }
            } while (initDrainRXBuffer.length > 0);

            // INV-DATA-003: Update connection state accurately
            this.isConnected = true;
            this.isInitialized = true;
            this.lastError = null;

            // Fetch initial status
            await this._refreshStatus();

        } catch (error) {
            // INV-CONS-003: Provide clear error recovery
            this.lastError = error;
            this.isConnected = false;
            throw new Error(`Failed to initialize camera ${this.displayName}: ${error.message}`);
        }
    }

    /**
     * Refreshes the camera status and settings
     *
     * @private
     */
    async _refreshStatus() {
        try {
            this.status = await this.transferOutRPC('dxo_camera_status_get');
            this.settings = await this.transferOutRPC('dxo_all_settings_get');

            // Extract battery level if available
            if (this.status?.result?.battery) {
                this.batteryLevel = this.status.result.battery;
            }
        } catch (error) {
            console.warn(`Failed to refresh status for ${this.displayName}:`, error);
        }
    }

    /**
     * Internal method to receive data from the camera
     *
     * @param {number} byteLength - Maximum bytes to receive
     * @returns {Promise<Uint8Array>} Received data
     * @private
     */
    async _getRX(byteLength = 32) {
        // INV-DATA-003: Check connection before operation
        // Bug fix: Simplified condition - if not connected, throw error regardless of initialization state
        if (!this.isConnected) {
            throw new Error('Camera disconnected');
        }
        return this.device.transferIn(this.inEndpoint, byteLength)
            .then(res => new Uint8Array(res.data.buffer));
    }

    /**
     * Transfers JPEG data from the camera (for live view)
     *
     * @returns {Promise<Uint8Array>} JPEG image data
     */
    async transferInJPEG() {
        let metadata = await this._getRX(MAX_PACKETSIZE);

        if (compareU8A(metadata, METADATA_INIT_SIGNATURE)) {
            await this.device.transferOut(this.outEndpoint, METADATA_INIT_RESPONSE_SIGNATURE);
            metadata = await this._getRX(MAX_PACKETSIZE);
        }

        let jpgResponse = new Uint8Array(metadata.length);

        let offset = 0;
        // Bug fix: Check metadata instead of empty jpgResponse array
        if (metadata.indexOfMulti(JPG_METADATA_HEADER) >= 0) {
            offset = metadata.length - 32;
            jpgResponse.set(metadata.slice(32));
        } else {
            offset = 0;
            jpgResponse.set(metadata);
        }

        let payload;
        do {
            payload = await this._getRX(MAX_PACKETSIZE);

            const diffLength = jpgResponse.length - (offset + payload.length);
            if (diffLength < 0) {
                let newJpgResponse = new Uint8Array(offset + payload.length);
                newJpgResponse.set(jpgResponse);
                jpgResponse = newJpgResponse;
            }

            jpgResponse.set(payload, offset);
            offset += payload.length;
        } while (payload.indexOfMulti(JPG_TRAILER) < 0);

        return jpgResponse;
    }

    /**
     * Receives an RPC response from the camera
     *
     * @returns {Promise<Object|null>} Parsed JSON-RPC response
     */
    async transferInRPC() {
        let metadata = await this._getRX(MAX_PACKETSIZE);

        if (compareU8A(metadata, METADATA_INIT_SIGNATURE)) {
            await this.device.transferOut(this.outEndpoint, METADATA_INIT_RESPONSE_SIGNATURE);
            metadata = await this._getRX(MAX_PACKETSIZE);
        }

        const rpcResponseSize = metadata[8] + (metadata[9] << 8);

        // INV-API-002: Consistent return type (null for no response)
        if (rpcResponseSize === 0) return null;

        let rpcResponse = new Uint8Array(rpcResponseSize);
        rpcResponse.set(metadata.slice(32));
        let offset = metadata.length - 32;

        let payload;
        do {
            if (offset === rpcResponseSize) break;

            payload = await this._getRX(MAX_PACKETSIZE);
            if (compareU8A(payload, METADATA_INIT_SIGNATURE)) {
                await this.device.transferOut(this.outEndpoint, METADATA_INIT_RESPONSE_SIGNATURE);
                break;
            }

            try {
                rpcResponse.set(payload, offset);
            } catch (e) {
                return null;
            }

            offset += payload.length;
        } while (offset < rpcResponseSize);

        const decodedString = getStringFromU8A(rpcResponse).replace(/\x00/g, '').trim();

        try {
            const res = JSON.parse(decodedString);
            if (res.method === 'dxo_usb_flush_forced') {
                return await this.transferInRPC();
            } else {
                return res;
            }
        } catch (e) {
            console.log(`[${this.displayName}] Failed to parse:`, decodedString);
            return null;
        }
    }

    /**
     * Sends an RPC command to the camera and returns the response
     *
     * @param {string} method - RPC method name
     * @param {Object} [params] - RPC parameters
     * @returns {Promise<Object>} RPC response
     */
    async transferOutRPC(method, params) {
        // INV-DATA-003: Check connection state
        if (!this.isConnected) {
            throw new Error(`Camera ${this.displayName} not connected`);
        }

        await this.device.transferOut(this.outEndpoint, METADATA_INIT_RESPONSE_SIGNATURE);

        // INV-DATA-001: Use Uint8Array for all messages
        const payload = new TextEncoder().encode(JSON.stringify({
            "jsonrpc": "2.0",
            "id": this.seq,
            method,
            ...(params ? { params } : {}),
        }) + '\x00');

        this.seq++;

        const msgDetails = mergeU8A([
            payload.length % (1 << 8),
            Math.floor(payload.length / (1 << 8))
        ], RPC_HEADER_TRAILER);

        const msgHeader = mergeU8A(RPC_HEADER, msgDetails);
        const msgWhole = mergeU8A(msgHeader, payload);

        await this.device.transferOut(this.outEndpoint, msgWhole);

        return await this.transferInRPC();
    }

    /**
     * Takes a photo with this camera
     *
     * @returns {Promise<Object>} Photo capture result
     */
    async takePhoto() {
        return await this.transferOutRPC('dxo_photo_take');
    }

    /**
     * Gets all camera settings
     *
     * @returns {Promise<Object>} Camera settings
     */
    async getAllSettings() {
        this.settings = await this.transferOutRPC('dxo_all_settings_get');
        return this.settings;
    }

    /**
     * Gets camera status
     *
     * @returns {Promise<Object>} Camera status
     */
    async getStatus() {
        this.status = await this.transferOutRPC('dxo_camera_status_get');
        return this.status;
    }

    /**
     * Starts live view with a callback for each frame
     *
     * @param {Function} callback - Called with (url, revokeCallback) for each frame.
     *                              Call revokeCallback() after using the URL to free memory.
     */
    async startLiveView(callback) {
        this.shouldStopLiveView = false;
        this.isLiveViewActive = true;

        await this.transferOutRPC('dxo_camera_mode_switch', { "param": 'view' });

        do {
            if (this.shouldStopLiveView) break;

            let frame = await this.transferInJPEG() || new Uint8Array(0);
            if (!frame || frame.length === 0) continue;

            let foundHeaderIndex = this.lastJPEGFrame.indexOfMulti(JPG_HEADER);
            // Only search for trailer if header was found (Bug fix: invalid offset)
            let foundTrailerIndex = foundHeaderIndex >= 0
                ? this.lastJPEGFrame.indexOfMulti(JPG_TRAILER, foundHeaderIndex + 1)
                : -1;

            // Bug fix: >= 0 instead of > 0 (header at index 0 is valid)
            if (foundHeaderIndex >= 0 && foundTrailerIndex >= 0) {
                this.lastJPEGFrame = this.lastJPEGFrame.slice(foundHeaderIndex, foundTrailerIndex + 2);
                let blob = new Blob([this.lastJPEGFrame], { 'type': 'image/jpeg' });
                let url = URL.createObjectURL(blob);
                this.lastJPEGFrame = new Uint8Array(0);
                // Bug fix: Pass URL revocation callback to prevent memory leak
                callback(url, () => URL.revokeObjectURL(url));
            } else {
                let accumulatedJPEGFrame = this.lastJPEGFrame.slice();
                const lastLength = this.lastJPEGFrame.length;

                if (foundHeaderIndex > 0) {
                    this.lastJPEGFrame = new Uint8Array(lastLength - foundHeaderIndex + frame.length);
                    this.lastJPEGFrame.set(accumulatedJPEGFrame);
                    this.lastJPEGFrame.set(frame, lastLength - foundHeaderIndex);
                } else {
                    this.lastJPEGFrame = new Uint8Array(lastLength + frame.length);
                    this.lastJPEGFrame.set(accumulatedJPEGFrame);
                    this.lastJPEGFrame.set(frame, lastLength);
                }

                foundHeaderIndex = this.lastJPEGFrame.indexOfMulti(JPG_HEADER);
                // Only search for trailer if header was found (Bug fix: invalid offset)
                foundTrailerIndex = foundHeaderIndex >= 0
                    ? this.lastJPEGFrame.indexOfMulti(JPG_TRAILER, foundHeaderIndex + 1)
                    : -1;

                // Bug fix: >= 0 instead of > 0 (header at index 0 is valid)
                if (foundHeaderIndex >= 0 && foundTrailerIndex >= 0) {
                    this.lastJPEGFrame = this.lastJPEGFrame.slice(foundHeaderIndex, foundTrailerIndex + 2);
                    let blob = new Blob([this.lastJPEGFrame], { 'type': 'image/jpeg' });
                    let url = URL.createObjectURL(blob);
                    this.lastJPEGFrame = new Uint8Array(0);
                    // Bug fix: Pass URL revocation callback to prevent memory leak
                    callback(url, () => URL.revokeObjectURL(url));
                }
            }
        } while (1);

        this.isLiveViewActive = false;
    }

    /**
     * Stops live view
     */
    stopLiveView() {
        this.shouldStopLiveView = true;
    }

    /**
     * Closes the connection to this camera
     *
     * @returns {Promise<void>}
     */
    async close() {
        this.shouldStopLiveView = true;
        this.isLiveViewActive = false;

        try {
            await this.device.close();
        } catch (error) {
            console.warn(`Error closing camera ${this.displayName}:`, error);
        }

        // INV-DATA-003: Update state on disconnect
        this.isConnected = false;
    }

    /**
     * Returns a serializable state object for this camera
     *
     * @returns {Object} Camera state for UI display
     */
    getState() {
        return {
            id: this.id,
            displayName: this.displayName,
            nickname: this.nickname,
            isConnected: this.isConnected,
            isLiveViewActive: this.isLiveViewActive,
            batteryLevel: this.batteryLevel,
            lastError: this.lastError?.message || null,
            serialNumber: this.device.serialNumber || null,
        };
    }
}

export default CameraDevice;
