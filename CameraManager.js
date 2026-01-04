/*
    CameraManager.js - Multi-camera management for DXO One cameras
    https://github.com/jsyang/dxo1control

    Manages multiple DXO One camera connections with synchronized capture capability.
    Supports up to 4 cameras for applications like:
    - 360° photography (camera array)
    - Stereoscopic/3D photography
    - Multi-angle product photography
    - Scientific/research capture
*/

import { CameraDevice } from './CameraDevice.js';

// INV-MULTI-002: Maximum camera limit (prevents resource exhaustion)
const MAX_CAMERAS = 4;

// USB device filter for DXO One cameras
// INV-SEC-001: Only connect to verified DXO One vendor ID
const PARAMS_DEVICE_REQUEST = { filters: [{ vendorId: 0x2b8f }] };

const ERROR_WEBUSB_API_NOT_SUPPORTED = 'Sorry, your browser / JS environment does not support WebUSB!\nTry running this in Chrome.';
const ERROR_MAX_CAMERAS_REACHED = `Maximum ${MAX_CAMERAS} cameras supported. Disconnect a camera to add another.`;

/**
 * Sync mode for multi-camera capture operations
 * @typedef {'parallel' | 'sequential'} SyncMode
 */

/**
 * Result of a multi-camera capture operation
 * @typedef {Object} CaptureResult
 * @property {string} cameraId - ID of the camera
 * @property {string} cameraName - Display name of the camera
 * @property {'success' | 'error'} status - Result status
 * @property {number} timestamp - When the capture completed
 * @property {Object} [result] - Camera response on success
 * @property {string} [error] - Error message on failure
 */

/**
 * CameraManager - Manages multiple DXO One camera connections
 *
 * Provides:
 * - Connection management for up to 4 cameras
 * - Synchronized capture across all cameras
 * - Per-camera state tracking
 * - Event callbacks for state changes
 *
 * @example
 * const manager = new CameraManager();
 * await manager.connectCamera();
 * await manager.connectCamera();
 * const results = await manager.captureAll();
 * console.log(`Captured on ${results.filter(r => r.status === 'success').length} cameras`);
 */
export class CameraManager {
    /**
     * Creates a new CameraManager instance
     *
     * @param {Object} [options] - Configuration options
     * @param {Object} [options.usbBackend=navigator.usb] - WebUSB backend (for testing)
     * @param {Function} [options.onCameraChange] - Callback when camera list changes
     * @param {Function} [options.onCaptureComplete] - Callback when capture completes
     */
    constructor(options = {}) {
        // INV-CONS-001: Check WebUSB availability
        this.usbBackend = options.usbBackend || (typeof navigator !== 'undefined' ? navigator.usb : null);

        // Camera storage - Map of cameraId -> CameraDevice
        this.cameras = new Map();

        // Sync mode for capture operations
        this.syncMode = 'parallel';

        // Callbacks
        this.onCameraChange = options.onCameraChange || (() => {});
        this.onCaptureComplete = options.onCaptureComplete || (() => {});

        // Bind disconnect handler
        if (this.usbBackend) {
            this.usbBackend.addEventListener('disconnect', this._handleDisconnect.bind(this));
        }
    }

    /**
     * Gets the number of connected cameras
     *
     * @returns {number} Count of connected cameras
     */
    get cameraCount() {
        return this.cameras.size;
    }

    /**
     * Gets an array of all connected cameras
     *
     * @returns {CameraDevice[]} Array of connected cameras
     */
    get connectedCameras() {
        return Array.from(this.cameras.values());
    }

    /**
     * Gets state of all cameras for UI display
     *
     * @returns {Object[]} Array of camera state objects
     */
    getAllCameraStates() {
        return this.connectedCameras.map(camera => camera.getState());
    }

    /**
     * Checks if WebUSB is available
     *
     * @returns {boolean} True if WebUSB is supported
     */
    isWebUSBAvailable() {
        return !!this.usbBackend;
    }

    /**
     * Connects a new DXO One camera
     *
     * Opens a WebUSB device picker for the user to select a camera.
     * Each camera requires explicit user permission (INV-SEC-002).
     *
     * @param {string} [nickname] - Optional user-friendly name for the camera
     * @returns {Promise<CameraDevice>} The connected camera device
     * @throws {Error} If WebUSB not available, max cameras reached, or connection fails
     */
    async connectCamera(nickname = null) {
        // INV-CONS-001: Check WebUSB availability
        if (!this.isWebUSBAvailable()) {
            throw new Error(ERROR_WEBUSB_API_NOT_SUPPORTED);
        }

        // INV-MULTI-002: Enforce maximum camera limit
        if (this.cameras.size >= MAX_CAMERAS) {
            throw new Error(ERROR_MAX_CAMERAS_REACHED);
        }

        try {
            // INV-SEC-002: User permission required (handled by WebUSB API)
            const device = await this.usbBackend.requestDevice(PARAMS_DEVICE_REQUEST);

            // Check if this device is already connected
            const existingId = device.serialNumber ||
                              `${device.vendorId}-${device.productId}`;

            for (const [id, camera] of this.cameras) {
                if (id.includes(existingId) || (camera.device === device)) {
                    throw new Error(`Camera ${existingId} is already connected`);
                }
            }

            // Create and initialize the camera device
            const camera = new CameraDevice(device, nickname);
            await camera.initialize();

            // Store the camera
            this.cameras.set(camera.id, camera);

            // Notify listeners
            this._notifyCameraChange();

            return camera;

        } catch (error) {
            // INV-CONS-003: Provide clear error for recovery
            if (error.name === 'NotFoundError') {
                throw new Error('No camera selected. Please select a DXO One camera from the list.');
            }
            throw error;
        }
    }

    /**
     * Disconnects a specific camera by ID
     *
     * @param {string} cameraId - The camera ID to disconnect
     * @returns {Promise<boolean>} True if camera was disconnected
     */
    async disconnectCamera(cameraId) {
        const camera = this.cameras.get(cameraId);
        if (!camera) {
            console.warn(`Camera ${cameraId} not found`);
            return false;
        }

        try {
            await camera.close();
        } catch (error) {
            console.warn(`Error disconnecting camera ${cameraId}:`, error);
        }

        this.cameras.delete(cameraId);
        this._notifyCameraChange();

        return true;
    }

    /**
     * Disconnects all connected cameras
     *
     * @returns {Promise<void>}
     */
    async disconnectAll() {
        const disconnectPromises = Array.from(this.cameras.values()).map(camera =>
            camera.close().catch(err => console.warn(`Error closing camera:`, err))
        );

        await Promise.all(disconnectPromises);
        this.cameras.clear();
        this._notifyCameraChange();
    }

    /**
     * Gets a camera by ID
     *
     * @param {string} cameraId - The camera ID
     * @returns {CameraDevice|undefined} The camera or undefined
     */
    getCamera(cameraId) {
        return this.cameras.get(cameraId);
    }

    /**
     * Sets the nickname for a camera
     *
     * @param {string} cameraId - The camera ID
     * @param {string} nickname - The new nickname
     * @returns {boolean} True if nickname was set
     */
    setNickname(cameraId, nickname) {
        const camera = this.cameras.get(cameraId);
        if (camera) {
            camera.nickname = nickname;
            this._notifyCameraChange();
            return true;
        }
        return false;
    }

    /**
     * Sets the sync mode for capture operations
     *
     * @param {'parallel' | 'sequential'} mode - The sync mode
     */
    setSyncMode(mode) {
        if (mode !== 'parallel' && mode !== 'sequential') {
            throw new Error(`Invalid sync mode: ${mode}. Use 'parallel' or 'sequential'.`);
        }
        this.syncMode = mode;
    }

    /**
     * Captures a photo on all connected cameras
     *
     * Uses the current sync mode to determine capture strategy:
     * - 'parallel': Captures on all cameras simultaneously (best effort ~50ms)
     * - 'sequential': Captures one camera at a time (slower but more reliable)
     *
     * @returns {Promise<CaptureResult[]>} Results for each camera
     */
    async captureAll() {
        if (this.cameras.size === 0) {
            return [];
        }

        const startTime = performance.now();

        const results = this.syncMode === 'parallel'
            ? await this._captureParallel()
            : await this._captureSequential();

        const totalTime = performance.now() - startTime;

        // Log sync timing for debugging (INV-MULTI-004)
        console.log(`[CameraManager] Capture completed in ${totalTime.toFixed(1)}ms (mode: ${this.syncMode})`);

        this.onCaptureComplete(results, totalTime);
        return results;
    }

    /**
     * Captures on all cameras in parallel (best effort synchronization)
     *
     * @private
     * @returns {Promise<CaptureResult[]>}
     */
    async _captureParallel() {
        const cameras = this.connectedCameras;

        // INV-MULTI-003: Use Promise.allSettled for partial failure handling
        const capturePromises = cameras.map(camera => {
            const timestamp = performance.now();
            return camera.takePhoto()
                .then(result => ({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'success',
                    timestamp,
                    result,
                }))
                .catch(error => ({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'error',
                    timestamp,
                    error: error.message,
                }));
        });

        return await Promise.all(capturePromises);
    }

    /**
     * Captures on all cameras sequentially
     *
     * @private
     * @returns {Promise<CaptureResult[]>}
     */
    async _captureSequential() {
        const cameras = this.connectedCameras;
        const results = [];

        for (const camera of cameras) {
            const timestamp = performance.now();
            try {
                const result = await camera.takePhoto();
                results.push({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'success',
                    timestamp,
                    result,
                });
            } catch (error) {
                results.push({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'error',
                    timestamp,
                    error: error.message,
                });
            }
        }

        return results;
    }

    /**
     * Sends a command to all connected cameras
     *
     * @param {string} method - RPC method name
     * @param {Object} [params] - RPC parameters
     * @returns {Promise<Array>} Results from all cameras
     */
    async sendCommandToAll(method, params) {
        const cameras = this.connectedCameras;

        // INV-MULTI-003: Handle partial failures
        const commandPromises = cameras.map(camera => {
            return camera.transferOutRPC(method, params)
                .then(result => ({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'success',
                    result,
                }))
                .catch(error => ({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'error',
                    error: error.message,
                }));
        });

        return await Promise.all(commandPromises);
    }

    /**
     * Gets status from all cameras
     *
     * @returns {Promise<Array>} Status from all cameras
     */
    async getAllStatus() {
        return this.sendCommandToAll('dxo_camera_status_get');
    }

    /**
     * Gets settings from all cameras
     *
     * @returns {Promise<Array>} Settings from all cameras
     */
    async getAllSettings() {
        return this.sendCommandToAll('dxo_all_settings_get');
    }

    /**
     * Handles USB disconnect events
     *
     * @private
     * @param {USBConnectionEvent} event
     */
    _handleDisconnect(event) {
        // Find and remove the disconnected camera
        for (const [id, camera] of this.cameras) {
            if (camera.device === event.device) {
                // INV-DATA-003: Update connection state
                camera.isConnected = false;
                this.cameras.delete(id);
                console.log(`[CameraManager] Camera ${camera.displayName} disconnected`);
                this._notifyCameraChange();
                break;
            }
        }
    }

    /**
     * Notifies listeners of camera list changes
     *
     * @private
     */
    _notifyCameraChange() {
        this.onCameraChange(this.getAllCameraStates());
    }

    /**
     * Checks if capture can be performed
     *
     * @returns {boolean} True if at least one camera is connected
     */
    canCapture() {
        return this.cameras.size > 0 &&
               this.connectedCameras.some(c => c.isConnected);
    }

    /**
     * Gets a summary of connected cameras
     *
     * @returns {string} Human-readable summary
     */
    getSummary() {
        const count = this.cameras.size;
        if (count === 0) return 'No cameras connected';
        if (count === 1) return '1 camera connected';
        return `${count} cameras connected`;
    }
}

export default CameraManager;
