// Page script for multi-camera.html. Kept out of the HTML so the page can run under a
// strict Content-Security-Policy (script-src 'self', no inline handlers).
import { CameraManager } from './CameraManager.js';

const $ = q => document.querySelector(q);
const $$ = q => document.querySelectorAll(q);

// Initialize the camera manager
const manager = new CameraManager({
    onCameraChange: updateCameraList,
    onCaptureComplete: showCaptureResults,
});

// State
let selectedCameraId = null;
let liveViewCameraId = null;

/**
 * Updates the camera list display
 */
function updateCameraList(cameras) {
    const list = $('#camera-list');
    const count = cameras.length;

    // Update header
    $('#camera-count').textContent = manager.getSummary();

    // Update connect button state (INV-MULTI-002: max 4 cameras)
    $('#btnConnectNew').disabled = count >= 4;

    // Update capture button state
    $('#btnCaptureAll').disabled = count === 0;
    $('#btnDisconnectAll').disabled = count === 0;

    if (count === 0) {
        list.innerHTML = '<div class="no-cameras">No cameras connected. Click "+ Connect Camera" to add one.</div>';
        $('#camera-details').innerHTML = '';
        return;
    }

    // Render camera list. Camera IDs come from the device's USB serial number,
    // so they are only ever placed in escaped attributes; clicks are handled by
    // a delegated listener (see bindDelegatedHandlers) instead of inline onclick.
    list.innerHTML = cameras.map(camera => `
        <div class="camera-item ${camera.id === selectedCameraId ? 'selected' : ''}"
             data-camera-id="${escapeHtml(camera.id)}">
            <div class="camera-header">
                <span class="camera-name">${escapeHtml(camera.displayName)}</span>
                <span class="camera-status ${camera.isConnected ? 'connected' : 'disconnected'}">
                    ${camera.isConnected ? '● Connected' : '○ Disconnected'}
                </span>
            </div>
            <div class="camera-info">
                ${camera.batteryLevel !== null ? `<span class="battery">🔋 ${escapeHtml(camera.batteryLevel)}%</span>` : ''}
                ${camera.isLiveViewActive ? '<span class="live-indicator">📺 Live</span>' : ''}
                ${camera.lastError ? `<span class="error-indicator" title="${escapeHtml(camera.lastError)}">⚠️</span>` : ''}
            </div>
        </div>
    `).join('');

    // Select first camera if none selected
    if (!selectedCameraId && cameras.length > 0) {
        selectCamera(cameras[0].id);
    }
}

/**
 * Shows capture results after multi-camera capture
 */
function showCaptureResults(results, totalTime, triggerSpreadMs = null) {
    const succeeded = results.filter(r => r.status === 'success').length;
    const failed = results.filter(r => r.status === 'error').length;
    // Host-side only: when each command finished writing to USB, not when shutters fired.
    const spread = typeof triggerSpreadMs === 'number'
        ? `<span class="trigger-spread">trigger spread (host-side): ${triggerSpreadMs.toFixed(2)} ms</span>`
        : '';

    let html = `<div class="capture-results">
        <h4>Capture Complete (${totalTime.toFixed(0)}ms)</h4>
        <div class="capture-summary">
            <span class="success-count">${succeeded} succeeded</span>
            ${failed > 0 ? `<span class="fail-count">${failed} failed</span>` : ''}
            ${spread}
        </div>
        <ul class="capture-details">
    `;

    for (const result of results) {
        const icon = result.status === 'success' ? '✅' : '❌';
        html += `<li class="${result.status}">
            ${icon} ${escapeHtml(result.cameraName)}
            ${result.error ? `: ${escapeHtml(result.error)}` : ''}
        </li>`;
    }

    html += '</ul></div>';
    log(html);
}

/**
 * Selects a camera and shows its details
 */
window.selectCamera = (cameraId) => {
    selectedCameraId = cameraId;
    const camera = manager.getCamera(cameraId);

    // Update selection visual
    $$('.camera-item').forEach(el => {
        el.classList.toggle('selected', el.dataset.cameraId === cameraId);
    });

    if (!camera) {
        $('#camera-details').innerHTML = '';
        return;
    }

    const state = camera.getState();
    $('#camera-details').innerHTML = `
        <div class="camera-detail-panel" data-camera-id="${escapeHtml(cameraId)}">
            <h3>${escapeHtml(state.displayName)}</h3>
            <div class="camera-actions">
                <button data-action="takePhoto">📸 Take Photo</button>
                <button data-action="toggleLiveView">
                    ${state.isLiveViewActive ? 'Stop Live View' : 'Start Live View'}
                </button>
                <button data-action="rename">✏️ Rename</button>
                <button data-action="disconnect" class="danger">Disconnect</button>
            </div>
            <div class="camera-meta">
                <p><strong>ID:</strong> ${escapeHtml(state.id)}</p>
                ${state.serialNumber ? `<p><strong>Serial:</strong> ${escapeHtml(state.serialNumber)}</p>` : ''}
                ${state.batteryLevel !== null ? `<p><strong>Battery:</strong> ${escapeHtml(state.batteryLevel)}%</p>` : ''}
            </div>
        </div>
    `;
};

/**
 * Connects a new camera
 */
window.connectCamera = async () => {
    try {
        log('Connecting camera...');
        const camera = await manager.connectCamera();
        log(`Connected: ${escapeHtml(camera.displayName)}`);
        selectCamera(camera.id);
    } catch (error) {
        log(`<div class="error">Connection failed: ${escapeHtml(error.message)}</div>`);
    }
};

/**
 * Disconnects a specific camera
 */
window.disconnectCamera = async (cameraId) => {
    try {
        await manager.disconnectCamera(cameraId);
        selectedCameraId = null;
        log('Camera disconnected');
    } catch (error) {
        log(`<div class="error">Disconnect failed: ${escapeHtml(error.message)}</div>`);
    }
};

/**
 * Disconnects all cameras
 */
window.disconnectAll = async () => {
    if (!confirm('Disconnect all cameras?')) return;
    try {
        await manager.disconnectAll();
        selectedCameraId = null;
        log('All cameras disconnected');
    } catch (error) {
        log(`<div class="error">Failed: ${escapeHtml(error.message)}</div>`);
    }
};

/**
 * Captures on all cameras
 */
window.captureAll = async () => {
    log('Capturing on all cameras...');
    try {
        await manager.captureAll();
        // Results shown via onCaptureComplete callback
    } catch (error) {
        log(`<div class="error">Capture failed: ${escapeHtml(error.message)}</div>`);
    }
};

/**
 * Takes a photo on a single camera
 */
window.takePhotoSingle = async (cameraId) => {
    const camera = manager.getCamera(cameraId);
    if (!camera) return;

    try {
        log(`Taking photo on ${escapeHtml(camera.displayName)}...`);
        await camera.takePhoto();
        log(`Photo taken on ${escapeHtml(camera.displayName)}!`);
    } catch (error) {
        log(`<div class="error">Photo failed: ${escapeHtml(error.message)}</div>`);
    }
};

/**
 * Toggles live view for a camera
 */
window.toggleLiveView = async (cameraId) => {
    const camera = manager.getCamera(cameraId);
    if (!camera) return;

    if (camera.isLiveViewActive) {
        camera.stopLiveView();
        $('#liveview').src = '';
        liveViewCameraId = null;
    } else {
        // Stop any existing live view
        if (liveViewCameraId) {
            const prev = manager.getCamera(liveViewCameraId);
            if (prev) prev.stopLiveView();
        }

        liveViewCameraId = cameraId;
        // Store the last revoke callback to prevent memory leaks
        let lastRevokeCallback = null;
        try {
            await camera.startLiveView((url, revokeCallback) => {
                // Revoke previous URL to free memory
                if (lastRevokeCallback) lastRevokeCallback();
                lastRevokeCallback = revokeCallback;
                if (url) $('#liveview').src = url;
            });
        } catch (error) {
            liveViewCameraId = null;
            log(`<div class="error">Live view failed: ${escapeHtml(error.message)}</div>`);
        }
    }

    // Refresh UI
    updateCameraList(manager.getAllCameraStates());
    if (selectedCameraId === cameraId) {
        selectCamera(cameraId);
    }
};

/**
 * Shows rename dialog for a camera
 */
window.showRenameDialog = (cameraId) => {
    const camera = manager.getCamera(cameraId);
    if (!camera) return;

    const newName = prompt('Enter nickname for camera:', camera.nickname || '');
    if (newName !== null) {
        manager.setNickname(cameraId, newName || null);
    }
};

/**
 * Changes the sync mode
 */
window.setSyncMode = (mode) => {
    manager.setSyncMode(mode);
    log(`Sync mode: ${mode}`);
};

/**
 * Logs a message to the output area
 */
function log(msg) {
    $('#msg').innerHTML = msg;
}

/**
 * Escapes a value for safe use in HTML text AND in quoted attribute values.
 * (textContent -> innerHTML does not escape quotes, so it is not attribute-safe.)
 */
function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/**
 * Click handling for dynamically rendered camera markup. Camera IDs are read
 * from data-* attributes (decoded by the browser), never interpolated into JS.
 */
function bindDelegatedHandlers() {
    $('#btnConnectNew').addEventListener('click', () => window.connectCamera());
    $('#btnCaptureAll').addEventListener('click', () => window.captureAll());
    $('#btnDisconnectAll').addEventListener('click', () => window.disconnectAll());
    $('#syncMode').addEventListener('change', (event) => window.setSyncMode(event.target.value));

    $('#camera-list').addEventListener('click', (event) => {
        const item = event.target.closest('.camera-item');
        if (item) selectCamera(item.dataset.cameraId);
    });

    $('#camera-details').addEventListener('click', (event) => {
        const button = event.target.closest('button[data-action]');
        const panel = event.target.closest('.camera-detail-panel');
        if (!button || !panel) return;

        const cameraId = panel.dataset.cameraId;
        switch (button.dataset.action) {
            case 'takePhoto': return window.takePhotoSingle(cameraId);
            case 'toggleLiveView': return window.toggleLiveView(cameraId);
            case 'rename': return window.showRenameDialog(cameraId);
            case 'disconnect': return window.disconnectCamera(cameraId);
        }
    });
}

// Check WebUSB availability on load
window.addEventListener('DOMContentLoaded', () => {
    if (!manager.isWebUSBAvailable()) {
        $('#main-content').innerHTML = `
            <div class="error-banner">
                WebUSB is not supported in this browser.
                Please use Chrome, Edge, or Opera.
            </div>
        `;
        return;
    }

    // Initialize UI
    bindDelegatedHandlers();
    updateCameraList([]);
});
