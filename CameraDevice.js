/*
    CameraDevice.js - Individual camera device wrapper for multi-camera support
    https://github.com/jsyang/dxo1control

    This class wraps a single DXO One camera connection with state tracking
    for use in multi-camera scenarios.
*/

import { DxoTransport } from './DxoTransport.js';

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

        // Shared USB transport (read pump, serialized RPC, timeouts)
        this.transport = null;

        // Live view state
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

            this.transport = new DxoTransport(this.device, this.inEndpoint, this.outEndpoint, {
                name: this.displayName,
                onFatal: error => this._onTransportFailure(error),
            });
            await this.transport.open();

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
            this.transport?.close('Initialization failed');
            this.transport = null;
            // STATE_MACHINES: a failed INITIALIZING releases the device
            try {
                if (this.device.opened) await this.device.close();
            } catch (closeError) {
                console.warn(`Error closing camera ${this.displayName} after failed init:`, closeError);
            }
            throw new Error(`Failed to initialize camera ${this.displayName}: ${error.message}`);
        }
    }

    /**
     * Called by the transport when a USB transfer fails (e.g. unplugged).
     *
     * @private
     */
    _onTransportFailure(error) {
        this.lastError = error;
        this.isConnected = false;
        this.isLiveViewActive = false;
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
     * Sends an RPC command to the camera and returns the matching reply.
     *
     * JSON-RPC error replies resolve normally (check `.error`). Rejects if the
     * camera is not connected, the transfer fails, or no reply arrives in time.
     *
     * @param {string} method - RPC method name
     * @param {Object} [params] - RPC parameters
     * @param {Object} [options] - { timeoutMs }
     * @returns {Promise<Object>} RPC response
     */
    async transferOutRPC(method, params, options) {
        // INV-DATA-003: Check connection state
        if (!this.isConnected || !this.transport) {
            throw new Error(`Camera ${this.displayName} not connected`);
        }
        return this.transport.request(method, params, options);
    }

    /**
     * Takes a photo with this camera
     *
     * @returns {Promise<Object>} Photo capture result
     */
    async takePhoto() {
        const response = await this.transferOutRPC('dxo_photo_take');
        // INV-MULTI-003: a camera that refused the capture must not count as success
        if (response?.error) {
            const detail = response.error.message || JSON.stringify(response.error);
            throw new Error(`Camera ${this.displayName} rejected capture: ${detail}`);
        }
        return response;
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
     * Resolves once the camera has been switched to view mode; frames are then
     * delivered by the transport's read pump. Calling it again while active
     * only replaces the callback.
     *
     * @param {Function} callback - Called with (url, revokeCallback) for each frame.
     *                              Call revokeCallback() after using the URL to free memory.
     * @returns {Promise<void>}
     */
    async startLiveView(callback) {
        if (!this.isConnected || !this.transport) {
            throw new Error(`Camera ${this.displayName} not connected`);
        }

        this.transport.setFrameHandler(frame => {
            const url = URL.createObjectURL(new Blob([frame], { 'type': 'image/jpeg' }));
            callback(url, () => URL.revokeObjectURL(url));
        });

        if (this.isLiveViewActive) return;

        try {
            await this.transferOutRPC('dxo_camera_mode_switch', { "param": 'view' });
            this.isLiveViewActive = true;
        } catch (error) {
            this.transport?.setFrameHandler(null);
            this.isLiveViewActive = false;
            this.lastError = error;
            throw error;
        }
    }

    /**
     * Stops live view
     *
     * TODO: turn this off from the camera side too (no known command yet);
     * until then the transport discards any further video data.
     */
    stopLiveView() {
        this.transport?.setFrameHandler(null);
        this.isLiveViewActive = false;
    }

    /**
     * Marks the camera as gone after a USB disconnect event, failing any
     * in-flight request instead of letting it hang.
     */
    markDisconnected() {
        this.stopLiveView();
        this.transport?.close('Camera disconnected');
        this.isConnected = false;
    }

    /**
     * Closes the connection to this camera
     *
     * @returns {Promise<void>}
     */
    async close() {
        this.stopLiveView();
        this.transport?.close('Camera closed');

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
