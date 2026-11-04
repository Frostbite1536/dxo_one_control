/*
    dxo1usb.js - Programmatic control of your DXO One camera over USB
    https://github.com/jsyang/dxo1control
    <jsyang@gmail.com>

    This module provides two APIs:
    1. Single-camera API (original): DXOONE.open() - for single camera control
    2. Multi-camera API (new): CameraManager - for controlling up to 4 cameras

    Multi-camera support enables:
    - 360° photography (camera array)
    - Stereoscopic/3D photography
    - Multi-angle product photography
    - Scientific/research capture
*/
import { DxoTransport } from './DxoTransport.js';

// Multi-camera support classes (INV-API-001: backward compatible addition)
export { CameraDevice } from './CameraDevice.js';
export { CameraManager } from './CameraManager.js';

const PARAMS_DEVICE_REQUEST = { filters: [{ vendorId: 0x2b8f }] };

const ERROR_WEBUSB_API_NOT_SUPPORTED = 'Sorry, your browser / JS environment does not support WebUSB!\nTry running this in Chrome.';

// Can use any library that implements the WebUSB API
// e.g. node-usb
async function open(usbBackend = navigator.usb) {
    if (!usbBackend) {
        if (alert) alert(ERROR_WEBUSB_API_NOT_SUPPORTED);
        throw ERROR_WEBUSB_API_NOT_SUPPORTED;
    }

    const device = await usbBackend.requestDevice(PARAMS_DEVICE_REQUEST);

    await device.open();

    let inEndpoint;
    let outEndpoint;

    await device.selectConfiguration(1);
    await device.claimInterface(device.configuration.interfaces[0].interfaceNumber);
    await device.claimInterface(device.configuration.interfaces[1].interfaceNumber);
    await device.selectAlternateInterface(1, 1);

    inEndpoint = device.configuration.interfaces[0].alternate.endpoints[1].endpointNumber;
    outEndpoint = device.configuration.interfaces[0].alternate.endpoints[0].endpointNumber;

    // All bulk transfers go through one transport: a single read pump, one RPC
    // in flight at a time, replies matched by id, per-request timeouts.
    const transport = new DxoTransport(device, inEndpoint, outEndpoint, {
        onFatal: () => { stopLiveViewLocal(); },
    });
    await transport.open();

    const transferOutRPC = (method, params, options) => transport.request(method, params, options);

    const bindApplySetting = baseParams => (async data => {
        const params = { ...baseParams };

        if (params.type === 'copyright' || params.type === 'artist') {
            params.param = data;
        } else if (params.type === 'mf_inv_distance') {
            // 0 - 5, with 6 digits of precision
            const dist = Math.min(Math.max(data, 0), 5).toFixed(6);
            params.param = dist;
        }

        // The camera first sends a dxo_setting_applied notification, then the
        // reply to this request; the transport routes the former to events and
        // resolves with the latter (matched by id).
        const response = await transferOutRPC('dxo_setting_set', params);
        if (!response || response.error) return false;

        const appliedType = response.result?.type;
        return appliedType === undefined || appliedType === params.type;
    });

    let lastFrameURL = null;

    function revokeLastFrame() {
        if (lastFrameURL) {
            URL.revokeObjectURL(lastFrameURL);
            lastFrameURL = null;
        }
    }

    function stopLiveViewLocal() {
        transport.setFrameHandler(null);
        revokeLastFrame();
    }

    /**
     * Starts live view. `callback(url)` receives an object URL per frame; the
     * previous frame's URL is revoked when the next one is delivered and when
     * live view stops. Resolves once the camera has been switched to view mode.
     */
    async function startLiveView(callback) {
        transport.setFrameHandler(frame => {
            const url = URL.createObjectURL(new Blob([frame], { 'type': 'image/jpeg' }));
            callback(url);
            revokeLastFrame();
            lastFrameURL = url;
        });

        try {
            await transferOutRPC('dxo_camera_mode_switch', { "param": 'view' });
        } catch (error) {
            stopLiveViewLocal();
            throw error;
        }
    }

    async function stopLiveView() {
        // TODO: turn this off from the camera side too (no known command yet);
        // the transport discards any further video data.
        stopLiveViewLocal();
    }

    return {
        command: {
            getAllSettings: () => transferOutRPC('dxo_all_settings_get'),
            getStatus: () => transferOutRPC('dxo_camera_status_get'),

            // TODO: figure out the last 3 parts of the param
            // 3rd last one might be heading
            // 2nd last one might be speed
            // last one might be local time
            setGPSData: () => transferOutRPC('dxo_gps_data_set', { "param": "##d##m##.####s,N,##d##m##.####s,W,138,0,05h34m40s" }),

            setSettings: {
                imageFormat: {
                    rawOff: bindApplySetting({ "type": "raw", "param": "off" }),
                    rawOn: bindApplySetting({ "type": "raw", "param": "on" }),

                    // Temporal Noise Reduction (TNR) to combine the four RAW files into one new SuperRAW Plus file
                    tnrOff: bindApplySetting({ "type": "tnr", "param": "off" }),
                    tnrOn: bindApplySetting({ "type": "tnr", "param": "on" }),
                },

                stillFocusingMode: {
                    MF: bindApplySetting({ "type": "still_focusing_mode", "param": "mf" }),
                    AF: bindApplySetting({ "type": "still_focusing_mode", "param": "af" }),
                },

                afMode: {
                    AF_OD: /* On Demand  */ bindApplySetting({ "type": "af_mode", "param": "af-od" }),
                    AF_C: /* Continuous  */ bindApplySetting({ "type": "af_mode", "param": "af-c" }),
                    AF_S: /* Single Shot */ bindApplySetting({ "type": "af_mode", "param": "af-s" }),
                },

                mfInvDistance: bindApplySetting({ "type": "mf_inv_distance", "param": "0.000000" }),

                iso: {
                    auto: bindApplySetting({ "type": "iso", "param": "auto" }),
                    iso100: bindApplySetting({ "type": "iso", "param": "iso100" }),
                    iso200: bindApplySetting({ "type": "iso", "param": "iso200" }),
                    iso400: bindApplySetting({ "type": "iso", "param": "iso400" }),
                    iso800: bindApplySetting({ "type": "iso", "param": "iso800" }),
                    iso1600: bindApplySetting({ "type": "iso", "param": "iso1600" }),
                    iso3200: bindApplySetting({ "type": "iso", "param": "iso3200" }),
                    iso6400: bindApplySetting({ "type": "iso", "param": "iso6400" }),
                    iso12800: bindApplySetting({ "type": "iso", "param": "iso12800" }),
                    iso25600: bindApplySetting({ "type": "iso", "param": "iso25600" }),
                    iso51200: bindApplySetting({ "type": "iso", "param": "iso51200" }),
                },

                evBias: {
                    m3_0: bindApplySetting({ "type": "ev_bias", "param": "-3.0" }),
                    m2_7: bindApplySetting({ "type": "ev_bias", "param": "-2.7" }),
                    m2_3: bindApplySetting({ "type": "ev_bias", "param": "-2.3" }),
                    m2_0: bindApplySetting({ "type": "ev_bias", "param": "-2.0" }),
                    m1_7: bindApplySetting({ "type": "ev_bias", "param": "-1.7" }),
                    m1_3: bindApplySetting({ "type": "ev_bias", "param": "-1.3" }),
                    m1_0: bindApplySetting({ "type": "ev_bias", "param": "-1.0" }),
                    m0_7: bindApplySetting({ "type": "ev_bias", "param": "-0.7" }),
                    m0_3: bindApplySetting({ "type": "ev_bias", "param": "-0.3" }),
                    zero: bindApplySetting({ "type": "ev_bias", "param": "0" }),
                    p0_3: bindApplySetting({ "type": "ev_bias", "param": "+0.3" }),
                    p0_7: bindApplySetting({ "type": "ev_bias", "param": "+0.7" }),
                    p1_0: bindApplySetting({ "type": "ev_bias", "param": "+1.0" }),
                    p1_3: bindApplySetting({ "type": "ev_bias", "param": "+1.3" }),
                    p1_7: bindApplySetting({ "type": "ev_bias", "param": "+1.7" }),
                    p2_0: bindApplySetting({ "type": "ev_bias", "param": "+2.0" }),
                    p2_3: bindApplySetting({ "type": "ev_bias", "param": "+2.3" }),
                    p2_7: bindApplySetting({ "type": "ev_bias", "param": "+2.7" }),
                    p3_0: bindApplySetting({ "type": "ev_bias", "param": "+3.0" }),
                },

                aperture: {
                    f1_8: bindApplySetting({ "type": "aperture", "param": "1.8" }),
                    f2: bindApplySetting({ "type": "aperture", "param": "2" }),
                    f2_2: bindApplySetting({ "type": "aperture", "param": "2.2" }),
                    f2_5: bindApplySetting({ "type": "aperture", "param": "2.5" }),
                    f2_8: bindApplySetting({ "type": "aperture", "param": "2.8" }),
                    f3_2: bindApplySetting({ "type": "aperture", "param": "3.2" }),
                    f3_5: bindApplySetting({ "type": "aperture", "param": "3.5" }),
                    f4: bindApplySetting({ "type": "aperture", "param": "4" }),
                    f4_5: bindApplySetting({ "type": "aperture", "param": "4.5" }),
                    f5: bindApplySetting({ "type": "aperture", "param": "5" }),
                    f5_6: bindApplySetting({ "type": "aperture", "param": "5.6" }),
                    f6_3: bindApplySetting({ "type": "aperture", "param": "6.3" }),
                    f7_1: bindApplySetting({ "type": "aperture", "param": "7.1" }),
                    f8: bindApplySetting({ "type": "aperture", "param": "8" }),
                    f9: bindApplySetting({ "type": "aperture", "param": "9" }),
                    f10: bindApplySetting({ "type": "aperture", "param": "10" }),
                    f11: bindApplySetting({ "type": "aperture", "param": "11" }),
                },

                exposureTime: {
                    t1_20000: bindApplySetting({ "type": "exposure_time", "param": "1/20000" }),
                    t1_16000: bindApplySetting({ "type": "exposure_time", "param": "1/16000" }),
                    t1_8000: bindApplySetting({ "type": "exposure_time", "param": "1/8000" }),
                    t1_4000: bindApplySetting({ "type": "exposure_time", "param": "1/4000" }),
                    t1_2000: bindApplySetting({ "type": "exposure_time", "param": "1/2000" }),
                    t1_1600: bindApplySetting({ "type": "exposure_time", "param": "1/1600" }),
                    t1_1250: bindApplySetting({ "type": "exposure_time", "param": "1/1250" }),
                    t1_1000: bindApplySetting({ "type": "exposure_time", "param": "1/1000" }),
                    t1_800: bindApplySetting({ "type": "exposure_time", "param": "1/800" }),
                    t1_640: bindApplySetting({ "type": "exposure_time", "param": "1/640" }),
                    t1_500: bindApplySetting({ "type": "exposure_time", "param": "1/500" }),
                    t1_400: bindApplySetting({ "type": "exposure_time", "param": "1/400" }),
                    t1_320: bindApplySetting({ "type": "exposure_time", "param": "1/320" }),
                    t1_250: bindApplySetting({ "type": "exposure_time", "param": "1/250" }),
                    t1_200: bindApplySetting({ "type": "exposure_time", "param": "1/200" }),
                    t1_160: bindApplySetting({ "type": "exposure_time", "param": "1/160" }),
                    t1_125: bindApplySetting({ "type": "exposure_time", "param": "1/125" }),
                    t1_100: bindApplySetting({ "type": "exposure_time", "param": "1/100" }),
                    t1_80: bindApplySetting({ "type": "exposure_time", "param": "1/80" }),
                    t1_60: bindApplySetting({ "type": "exposure_time", "param": "1/60" }),
                    t1_50: bindApplySetting({ "type": "exposure_time", "param": "1/50" }),
                    t1_40: bindApplySetting({ "type": "exposure_time", "param": "1/40" }),
                    t1_30: bindApplySetting({ "type": "exposure_time", "param": "1/30" }),
                    t1_25: bindApplySetting({ "type": "exposure_time", "param": "1/25" }),
                    t1_20: bindApplySetting({ "type": "exposure_time", "param": "1/20" }),
                    t1_15: bindApplySetting({ "type": "exposure_time", "param": "1/15" }),
                    t1_13: bindApplySetting({ "type": "exposure_time", "param": "1/13" }),
                    t1_10: bindApplySetting({ "type": "exposure_time", "param": "1/10" }),
                    t1_8: bindApplySetting({ "type": "exposure_time", "param": "1/8" }),
                    t1_6: bindApplySetting({ "type": "exposure_time", "param": "1/6" }),
                    t1_5: bindApplySetting({ "type": "exposure_time", "param": "1/5" }),
                    t1_4: bindApplySetting({ "type": "exposure_time", "param": "1/4" }),
                    t1_3: bindApplySetting({ "type": "exposure_time", "param": "1/3" }),
                    t1_2: bindApplySetting({ "type": "exposure_time", "param": "1/2" }),
                    t1_1: bindApplySetting({ "type": "exposure_time", "param": "1/1" }),
                    t2_1: bindApplySetting({ "type": "exposure_time", "param": "2/1" }),
                    t4_1: bindApplySetting({ "type": "exposure_time", "param": "4/1" }),
                    t8_1: bindApplySetting({ "type": "exposure_time", "param": "8/1" }),
                    t15_1: bindApplySetting({ "type": "exposure_time", "param": "15/1" }),
                    t30_1: bindApplySetting({ "type": "exposure_time", "param": "30/1" }),
                },

                shootingMode: {
                    sport: bindApplySetting({ "type": "shooting_mode", "param": "sport" }),
                    portrait: bindApplySetting({ "type": "shooting_mode", "param": "portrait" }),
                    landscape: bindApplySetting({ "type": "shooting_mode", "param": "landscape" }),
                    night: bindApplySetting({ "type": "shooting_mode", "param": "night" }),

                    // Priority shooting modes
                    program: bindApplySetting({ "type": "shooting_mode", "param": "program" }),
                    aperture: bindApplySetting({ "type": "shooting_mode", "param": "aperture" }),
                    shutter: bindApplySetting({ "type": "shooting_mode", "param": "shutter" }),
                    manual: bindApplySetting({ "type": "shooting_mode", "param": "manual" }),
                },

                shutterMode: {
                    single: bindApplySetting({ "type": "drive", "param": "single" }),
                    timelapse: bindApplySetting({ "type": "drive", "param": "timelapse" }),
                    timer0s: bindApplySetting({ "type": "selftimer", "param": "0" }),
                    timer2s: bindApplySetting({ "type": "selftimer", "param": "2" }),
                    timer10s: bindApplySetting({ "type": "selftimer", "param": "10" }),
                },

                autoWhiteBalance: {
                    off: bindApplySetting({ "type": "lighting_intensity", "param": "off" }),
                    slight: bindApplySetting({ "type": "lighting_intensity", "param": "slight" }),
                    medium: bindApplySetting({ "type": "lighting_intensity", "param": "medium" }),
                    strong: bindApplySetting({ "type": "lighting_intensity", "param": "strong" }),
                },
                imageQuality: {
                    fine: bindApplySetting({ "type": "photo_quality", "param": "100" }),
                    normal: bindApplySetting({ "type": "photo_quality", "param": "95" }),
                    basic: bindApplySetting({ "type": "photo_quality", "param": "70" }),
                },
                maxIso: {
                    auto: bindApplySetting({ "type": "iso_boundaries", "param": "no_limit" }),
                    iso100: bindApplySetting({ "type": "iso_boundaries", "param": "iso100" }),
                    iso200: bindApplySetting({ "type": "iso_boundaries", "param": "iso200" }),
                    iso400: bindApplySetting({ "type": "iso_boundaries", "param": "iso400" }),
                    iso800: bindApplySetting({ "type": "iso_boundaries", "param": "iso800" }),
                    iso1600: bindApplySetting({ "type": "iso_boundaries", "param": "iso1600" }),
                    iso3200: bindApplySetting({ "type": "iso_boundaries", "param": "iso3200" }),
                    iso6400: bindApplySetting({ "type": "iso_boundaries", "param": "iso6400" }),
                    iso12800: bindApplySetting({ "type": "iso_boundaries", "param": "iso12800" }),
                    iso25600: bindApplySetting({ "type": "iso_boundaries", "param": "iso25600" }),
                    iso51200: bindApplySetting({ "type": "iso_boundaries", "param": "iso51200" }),
                },
                maxShutter: {
                    auto: bindApplySetting({ "type": "max_exposure", "param": "0/1" }),
                    t15_1: bindApplySetting({ "type": "max_exposure", "param": "15/1" }),
                    t2_1: bindApplySetting({ "type": "max_exposure", "param": "2/1" }),
                    t1_3: bindApplySetting({ "type": "max_exposure", "param": "1/3" }),
                    t1_6: bindApplySetting({ "type": "max_exposure", "param": "1/6" }),
                    t1_13: bindApplySetting({ "type": "max_exposure", "param": "1/13" }),
                    t1_25: bindApplySetting({ "type": "max_exposure", "param": "1/25" }),
                    t1_50: bindApplySetting({ "type": "max_exposure", "param": "1/50" }),
                    t1_100: bindApplySetting({ "type": "max_exposure", "param": "1/100" }),
                    t1_200: bindApplySetting({ "type": "max_exposure", "param": "1/200" }),
                    t1_400: bindApplySetting({ "type": "max_exposure", "param": "1/400" }),

                },
                metadata: {
                    copyright: bindApplySetting({ "type": "copyright", "param": "COPYRIGHT" }),
                    artist: bindApplySetting({ "type": "artist", "param": "ARTIST" }),
                },
                videoQuality: {
                    standard: bindApplySetting({ "type": "video_quality", "param": "16000000" }),
                    better: bindApplySetting({ "type": "video_quality", "param": "22000000" }),
                    highest: bindApplySetting({ "type": "video_quality", "param": "30000000" }),
                },
            },

            getDigitalZoom: () => transferOutRPC('dxo_digital_zoom_get', { "type": 'current' }),

            // Origin is bottom left corner
            focus: (x, y) => transferOutRPC('dxo_tap_to_focus', { "param": `[${x},${y},256,256]` }),
            // Not 100% sure what this command does- likely flushes focus value?
            flushFocus: () => transferOutRPC('dxo_tap_to_focus', { "param": '[0,0,0,0]' }),

            takePhoto: () => transferOutRPC('dxo_photo_take'),

            sleep: () => transferOutRPC('dxo_idle'),

            fs: {
                // TODO: File retrieval not tested at all!
                fetchFile: (path, offset, fetch_size) => transferOutRPC('dxo_fs_last_file_get', { "param": path, offset, fetch_size }),

                getLastFilePath: () => transferOutRPC('dxo_fs_last_file_get'),

                cancelGet: () => transferOutRPC('dxo_fs_cancel_get'),
            },

            liveView: {
                start: startLiveView,
                stop: stopLiveView,
            },
        },

        // Disconnect
        close: () => {
            stopLiveViewLocal();
            transport.close('Device closed');
            return device.close();
        },
    };
}

export default {
    open,
}
