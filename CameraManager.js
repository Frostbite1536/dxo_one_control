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
 * @property {number} timestamp - performance.now() when the capture was issued
 * @property {number} [triggeredAt] - performance.now() when the dxo_photo_take frame finished
 *   writing to USB (host-side proxy for the trigger time; not the shutter time)
 * @property {Object} [result] - Camera response on success
 * @property {string} [error] - Error message on failure
 */

/**
 * One-shot start line for a parallel capture (same rules as the Android CaptureBarrier):
 * each camera either arrives (its request is next in line and only the write is left) or
 * leaves (it won't send). When all have done so, every waiting camera is released together.
 * The deadline is shared: at most `timeoutMs` after the first arrival the barrier breaks,
 * releasing everyone waiting; later arrivals don't wait.
 */
export class CaptureBarrier {
    constructor(parties, timeoutMs) {
        this._remaining = parties;
        this._timeoutMs = timeoutMs;
        this._timer = null;
        this._done = false;
        this.broken = false;
        this._released = new Promise(resolve => { this._release = resolve; });
        if (parties <= 0) this._finish();
    }

    get isReleased() {
        return this._done;
    }

    /** @returns {Promise<boolean>} true if every party arrived or left before the deadline */
    arriveAndAwait() {
        this._countDown();
        if (!this._done && this._timer === null) {
            this._timer = setTimeout(() => {
                this.broken = true;
                this._finish();
            }, this._timeoutMs);
        }
        return this._released.then(() => !this.broken);
    }

    leave() {
        this._countDown();
    }

    _countDown() {
        this._remaining--;
        if (this._remaining <= 0) this._finish();
    }

    _finish() {
        if (this._done) return;
        this._done = true;
        if (this._timer !== null) clearTimeout(this._timer);
        this._release();
    }
}

/**
 * Host-side trigger spread of a capture: max - min of `triggeredAt` over successful
 * cameras, in ms, or null with fewer than two. Not a shutter-sync measurement.
 *
 * @param {CaptureResult[]} results
 * @returns {number|null}
 */
export function triggerSpreadMs(results) {
    const times = results
        .filter(r => r.status === 'success' && typeof r.triggeredAt === 'number')
        .map(r => r.triggeredAt);
    if (times.length < 2) return null;
    return Math.max(...times) - Math.min(...times);
}

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

        // Parallel capture: how long cameras wait for each other at the start line
        this.barrierTimeoutMs = options.barrierTimeoutMs ?? 300;

        // Callbacks
        this.onCameraChange = options.onCameraChange || (() => {});
        this.onCaptureComplete = options.onCaptureComplete || (() => {});

        // Bind disconnect handler and store reference for cleanup (Bug fix: memory leak)
        this._boundDisconnectHandler = this._handleDisconnect.bind(this);
        if (this.usbBackend) {
            this.usbBackend.addEventListener('disconnect', this._boundDisconnectHandler);
        }
    }

    /**
     * Cleans up the CameraManager and releases resources
     *
     * Removes event listeners and disconnects all cameras.
     * Call this before discarding the CameraManager instance.
     *
     * @returns {Promise<void>}
     */
    async dispose() {
        // Remove USB disconnect listener (Bug fix: prevent memory leak)
        if (this.usbBackend && this._boundDisconnectHandler) {
            this.usbBackend.removeEventListener('disconnect', this._boundDisconnectHandler);
        }

        // Disconnect all cameras
        await this.disconnectAll();
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

            // Already connected? Same USBDevice object, or same serial number.
            for (const camera of this.cameras.values()) {
                if (camera.device === device ||
                    (device.serialNumber && camera.device.serialNumber === device.serialNumber)) {
                    throw new Error(`Camera ${camera.displayName} is already connected`);
                }
            }

            // Create and initialize the camera device
            const camera = new CameraDevice(device, nickname);

            // INV-MULTI-001: two cameras without a serial number would share the fallback
            // id; keep ids unique (and stable for a given connection order).
            if (this.cameras.has(camera.id)) {
                let n = 2;
                while (this.cameras.has(`${camera.id}#${n}`)) n++;
                camera.id = `${camera.id}#${n}`;
            }

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

        // INV-MULTI-004: log what we can actually measure (host-side trigger spread)
        const spread = triggerSpreadMs(results);
        console.log(`[CameraManager] Capture completed in ${totalTime.toFixed(1)}ms (mode: ${this.syncMode})` +
            (spread !== null ? `, trigger spread (host-side) ${spread.toFixed(2)}ms` : ''));

        this.onCaptureComplete(results, totalTime, spread);
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

        // Hold every camera at a barrier just before its write, so the writes start
        // together even if some cameras' queues were busy (bounded by barrierTimeoutMs).
        const barrier = new CaptureBarrier(cameras.length, this.barrierTimeoutMs);

        // INV-MULTI-003: each camera's failure becomes an error result (never rejects)
        const capturePromises = cameras.map(camera => {
            const timestamp = performance.now();
            let arrived = false;
            let triggeredAt;
            return camera.takePhoto({
                beforeWrite: () => {
                    arrived = true;
                    return barrier.arriveAndAwait();
                },
                onWritten: t => { triggeredAt = t; },
            })
                .then(result => ({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'success',
                    timestamp,
                    triggeredAt,
                    result,
                }))
                .catch(error => ({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'error',
                    timestamp,
                    error: error.message,
                }))
                .finally(() => {
                    // Failed before reaching the barrier: don't hold the others.
                    if (!arrived) barrier.leave();
                });
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
            let triggeredAt;
            try {
                const result = await camera.takePhoto({ onWritten: t => { triggeredAt = t; } });
                results.push({
                    cameraId: camera.id,
                    cameraName: camera.displayName,
                    status: 'success',
                    timestamp,
                    triggeredAt,
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
                // INV-DATA-003: Update connection state; stop live view and
                // fail any in-flight request instead of leaving it pending.
                camera.markDisconnected();
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
