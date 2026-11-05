// Page script for usb.html (single-camera UI). Kept out of the HTML so the page can
// run under a strict Content-Security-Policy (script-src 'self', no inline handlers).
import DXOONE from './dxo1usb.js';

const $ = q => document.querySelector(q);

// textContent, not innerHTML: messages include camera-supplied JSON.
const log = msg => { $('#msg').textContent = msg; };
const logJSON = obj => log(JSON.stringify(obj, null, 2));
const logError = (what, error) => log(`${what} failed: ${error?.message || error}`);

let camera = null;
let isLiveViewRunning = false;
let busy = false;

function render() {
    const connected = camera !== null;
    $('#btnConnect').textContent = connected ? 'Disconnect' : 'Connect';
    $('#btnConnect').disabled = busy;
    $('#btnLiveView').disabled = busy || !connected;
    $('#btnSnap').disabled = busy || !connected;
    $('#btnLiveView').textContent = isLiveViewRunning ? 'Stop live view' : 'Start live view';
}

/** Runs an action with the controls disabled, always restoring a consistent UI. */
async function run(what, action) {
    if (busy) return;
    busy = true;
    render();
    try {
        await action();
    } catch (error) {
        logError(what, error);
    } finally {
        busy = false;
        render();
    }
}

async function disconnect() {
    const device = camera;
    camera = null;
    isLiveViewRunning = false;
    $('#liveview').removeAttribute('src');
    if (device) await device.close();
}

function toggleConnection() {
    return run(camera ? 'Disconnect' : 'Connect', async () => {
        if (camera) {
            await disconnect();
            log('Disconnected');
            return;
        }

        camera = await DXOONE.open();
        try {
            const settings = await camera.command.getAllSettings();
            const status = await camera.command.getStatus();
            logJSON({ status, settings });
        } catch (error) {
            // Connected but the camera isn't answering: don't leave a half-open session.
            await disconnect().catch(() => {});
            throw error;
        }
    });
}

function toggleLiveView() {
    return run('Live view', async () => {
        if (isLiveViewRunning) {
            await camera.command.liveView.stop();
            isLiveViewRunning = false;
            return;
        }
        // dxo1usb.js revokes each frame's object URL when the next one arrives.
        await camera.command.liveView.start(url => {
            if (url) $('#liveview').src = url;
        });
        isLiveViewRunning = true;
    });
}

function takePhoto() {
    return run('Photo', async () => {
        const response = await camera.command.takePhoto();
        if (response?.error) {
            throw new Error(response.error.message || JSON.stringify(response.error));
        }
        log('Photo taken!');
    });
}

$('#btnConnect').addEventListener('click', toggleConnection);
$('#btnLiveView').addEventListener('click', toggleLiveView);
$('#btnSnap').addEventListener('click', takePhoto);

if (!navigator.usb) {
    log('WebUSB is not available in this browser. Use Chrome, Edge or Opera over https:// or http://localhost.');
    $('#btnConnect').disabled = true;
} else {
    // An unplugged camera rejects its pending command; reflect that in the UI. Ignore other
    // devices: the origin may have more than one DXO One paired (multi-camera page).
    navigator.usb.addEventListener('disconnect', (event) => {
        if (!camera || event.device !== camera.device) return;
        disconnect().catch(() => {}).finally(() => {
            log('Camera disconnected');
            render();
        });
    });
    render();
}
