/*
    DxoTransport.js - Shared USB transport for the DXO One

    One instance per opened camera. It owns the bulk-IN endpoint: a single
    read pump receives every packet and the demultiplexer routes it to

      - the in-flight JSON-RPC request (matched by id),
      - the notification/event handler (messages carrying a `method`), or
      - the live-view JPEG reassembler (everything that is not RPC).

    Requests are serialized (one RPC in flight per camera) and every request
    has a timeout, so a slow or unplugged camera can no longer hang callers or
    shift later replies onto the wrong request.

    Used by dxo1usb.js (single-camera API) and CameraDevice.js (multi-camera).
    The Android app's DxoOneStreamDemux follows the same rules.
*/

import { getU8AFromHexString, compareU8A, mergeU8A, getStringFromU8A } from './u8a.js';

export const METADATA_INIT_SIGNATURE = getU8AFromHexString('A3, BA, D1, 10, AB, CD, AB, CD, 00, 00, 00, 00, 02, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00');
export const METADATA_INIT_RESPONSE_SIGNATURE = getU8AFromHexString('A3, BA, D1, 10, DC, BA, DC, BA, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00');

export const RPC_HEADER = getU8AFromHexString('A3, BA, D1, 10, 17, 08, 00, 0C');
export const RPC_HEADER_TRAILER = getU8AFromHexString('00, 00, 03, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00');

const MESSAGE_MAGIC = getU8AFromHexString('A3, BA, D1, 10');
const MESSAGE_HEADER_SIZE = 32;
const JPG_HEADER = getU8AFromHexString('FF, D8, FF');
const JPG_TRAILER = getU8AFromHexString('FF, D9');
const JSON_OPEN_BRACE = 0x7B; // '{'

export const MAX_PACKETSIZE = 512;
export const MAX_RPC_PAYLOAD = 0xFFFF; // length field is 16 bits
const MAX_JPEG_BUFFER = 4 * 1024 * 1024;

export const DEFAULT_TIMEOUT_MS = 5000;
export const CAPTURE_TIMEOUT_MS = 40000; // long exposures go up to 30 s
const METHOD_TIMEOUTS_MS = {
    dxo_photo_take: CAPTURE_TIMEOUT_MS,
};
const HANDSHAKE_TIMEOUT_MS = 2000;

export class TransportError extends Error {
    /**
     * @param {string} message
     * @param {'timeout'|'closed'|'io'} code
     */
    constructor(message, code) {
        super(message);
        this.name = 'TransportError';
        this.code = code;
    }
}

function startsWith(buf, prefix) {
    if (buf.length < prefix.length) return false;
    for (let i = 0; i < prefix.length; i++) {
        if (buf[i] !== prefix[i]) return false;
    }
    return true;
}

function indexOfPattern(buf, len, pattern, from = 0) {
    const last = len - pattern.length;
    outer:
    for (let i = Math.max(0, from); i <= last; i++) {
        for (let j = 0; j < pattern.length; j++) {
            if (buf[i + j] !== pattern[j]) continue outer;
        }
        return i;
    }
    return -1;
}

/**
 * Builds a framed JSON-RPC request.
 *
 * @param {number} id
 * @param {string} method
 * @param {Object} [params]
 * @returns {Uint8Array}
 */
export function encodeRpcRequest(id, method, params) {
    const payload = new TextEncoder().encode(JSON.stringify({
        "jsonrpc": "2.0",
        "id": id,
        method,
        ...(params ? { params } : {}),
    }) + '\x00');

    // INV-DATA-001: the length field is 16-bit little endian; refuse to wrap.
    if (payload.length > MAX_RPC_PAYLOAD) {
        throw new RangeError(`RPC payload too large (${payload.length} bytes)`);
    }

    const msgDetails = mergeU8A([payload.length & 0xFF, payload.length >> 8], RPC_HEADER_TRAILER);
    return mergeU8A(mergeU8A(RPC_HEADER, msgDetails), payload);
}

/**
 * Pure packet demultiplexer: feed it one bulk-IN packet at a time.
 * No I/O, so it can be unit-tested without a device.
 */
export class DxoStreamDemux {
    /**
     * @param {Object} handlers
     * @param {Function} [handlers.onInitSignature] - camera asked for an ACK
     * @param {Function} [handlers.onMessage] - parsed JSON-RPC message object
     * @param {Function} [handlers.onFrame] - complete JPEG frame (Uint8Array)
     * @param {Function} [handlers.onDrop] - (reason, detail) for diagnostics
     */
    constructor({ onInitSignature, onMessage, onFrame, onDrop } = {}) {
        this.onInitSignature = onInitSignature || (() => {});
        this.onMessage = onMessage || (() => {});
        this.onFrame = onFrame || (() => {});
        this.onDrop = onDrop || (() => {});

        this.videoEnabled = false;

        // RPC message being assembled
        this._rpcBuffer = null;
        this._rpcOffset = 0;
        // Size from a bare 32-byte header whose body arrives in the next packet
        this._pendingHeaderSize = null;

        // JPEG accumulator
        this._jpg = new Uint8Array(64 * 1024);
        this._jpgLen = 0;
        this._jpgStarted = false;
        this._jpgScanFrom = 0;
    }

    setVideoEnabled(enabled) {
        this.videoEnabled = !!enabled;
        if (!this.videoEnabled) this._resetVideo();
    }

    /** @param {Uint8Array} packet */
    push(packet) {
        if (packet.length === 0) return;

        if (compareU8A(packet, METADATA_INIT_SIGNATURE)) {
            this.onInitSignature();
            return;
        }

        if (this._rpcBuffer) {
            this._appendRpc(packet);
            return;
        }

        if (this._pendingHeaderSize !== null) {
            const size = this._pendingHeaderSize;
            this._pendingHeaderSize = null;
            if (packet[0] === JSON_OPEN_BRACE) {
                this._startRpc(size, packet);
                return;
            }
            // Not an RPC body after all: classify this packet normally.
        }

        if (packet.length >= MESSAGE_HEADER_SIZE && startsWith(packet, MESSAGE_MAGIC)) {
            const size = packet[8] | (packet[9] << 8);

            if (packet.length === MESSAGE_HEADER_SIZE) {
                if (size > 0) this._pendingHeaderSize = size;
                return;
            }

            const body = packet.subarray(MESSAGE_HEADER_SIZE);
            if (body[0] === JSON_OPEN_BRACE) {
                if (size > 0) this._startRpc(size, body);
                return;
            }

            // Header followed by non-JSON data: a live-view packet.
            this._pushVideo(body);
            return;
        }

        this._pushVideo(packet);
    }

    _startRpc(size, body) {
        this._rpcBuffer = new Uint8Array(size);
        this._rpcOffset = 0;
        this._appendRpc(body);
    }

    _appendRpc(bytes) {
        const remaining = this._rpcBuffer.length - this._rpcOffset;
        const take = Math.min(remaining, bytes.length);
        this._rpcBuffer.set(bytes.subarray(0, take), this._rpcOffset);
        this._rpcOffset += take;

        if (take < bytes.length) {
            this.onDrop('rpc-overflow', `${bytes.length - take} trailing bytes`);
        }

        if (this._rpcOffset >= this._rpcBuffer.length) {
            const buffer = this._rpcBuffer;
            this._rpcBuffer = null;
            this._rpcOffset = 0;
            this._finishRpc(buffer);
        }
    }

    _finishRpc(buffer) {
        const text = getStringFromU8A(buffer).replace(/\x00/g, '').trim();
        let message;
        try {
            message = JSON.parse(text);
        } catch (e) {
            this.onDrop('rpc-parse', text);
            return;
        }
        if (message && typeof message === 'object') {
            this.onMessage(message);
        } else {
            this.onDrop('rpc-parse', text);
        }
    }

    _resetVideo() {
        this._jpgLen = 0;
        this._jpgStarted = false;
        this._jpgScanFrom = 0;
    }

    _pushVideo(bytes) {
        if (!this.videoEnabled) return;

        if (this._jpgLen + bytes.length > MAX_JPEG_BUFFER) {
            this.onDrop('jpeg-overflow', `${this._jpgLen + bytes.length} bytes without a complete frame`);
            this._resetVideo();
            if (bytes.length > MAX_JPEG_BUFFER) return;
        }

        if (this._jpgLen + bytes.length > this._jpg.length) {
            let size = this._jpg.length;
            while (size < this._jpgLen + bytes.length) size *= 2;
            const grown = new Uint8Array(size);
            grown.set(this._jpg.subarray(0, this._jpgLen));
            this._jpg = grown;
        }
        this._jpg.set(bytes, this._jpgLen);
        this._jpgLen += bytes.length;

        this._extractFrames();
    }

    _extractFrames() {
        for (;;) {
            if (!this._jpgStarted) {
                const start = indexOfPattern(this._jpg, this._jpgLen, JPG_HEADER, this._jpgScanFrom);
                if (start < 0) {
                    // Keep the last bytes in case the start marker straddles packets.
                    const keep = Math.min(this._jpgLen, JPG_HEADER.length - 1);
                    this._jpg.copyWithin(0, this._jpgLen - keep, this._jpgLen);
                    this._jpgLen = keep;
                    this._jpgScanFrom = 0;
                    return;
                }
                // Drop anything before the start marker.
                this._jpg.copyWithin(0, start, this._jpgLen);
                this._jpgLen -= start;
                this._jpgStarted = true;
                this._jpgScanFrom = JPG_HEADER.length;
            }

            const end = indexOfPattern(this._jpg, this._jpgLen, JPG_TRAILER, this._jpgScanFrom);
            if (end < 0) {
                // Resume scanning where a trailer could still begin.
                this._jpgScanFrom = Math.max(JPG_HEADER.length, this._jpgLen - (JPG_TRAILER.length - 1));
                return;
            }

            const frameEnd = end + JPG_TRAILER.length;
            this.onFrame(this._jpg.slice(0, frameEnd));

            // Keep any bytes after the trailer: they belong to the next frame.
            this._jpg.copyWithin(0, frameEnd, this._jpgLen);
            this._jpgLen -= frameEnd;
            this._jpgStarted = false;
            this._jpgScanFrom = 0;
        }
    }
}

/**
 * Owns one camera's endpoints: read pump, serialized requests, timeouts.
 */
export class DxoTransport {
    /**
     * @param {USBDevice} device - an opened device with interfaces claimed
     * @param {number} inEndpoint
     * @param {number} outEndpoint
     * @param {Object} [options]
     * @param {Function} [options.onEvent] - camera notifications (messages with `method`)
     * @param {Function} [options.onFatal] - the transport stopped because of an I/O error
     * @param {string} [options.name] - label for log messages
     */
    constructor(device, inEndpoint, outEndpoint, { onEvent, onFatal, name = 'DXO One' } = {}) {
        this.device = device;
        this.inEndpoint = inEndpoint;
        this.outEndpoint = outEndpoint;
        this.onEvent = onEvent || (() => {});
        this.onFatal = onFatal || (() => {});
        this.name = name;

        this._seq = 0;
        this._inflight = null;
        // Id-less replies (fallback for a camera that doesn't echo ids):
        // - never attributed once the camera has sent any reply with an id;
        // - each timeout means one late id-less reply may still arrive; that many id-less
        //   replies are dropped. A timeout caused by such a drop is not counted again,
        //   so replies that are truly lost cost a bounded number of extra requests and
        //   the transport recovers.
        this._sawReplyId = false;
        this._lateIdlessExpected = 0;
        this._requestChain = Promise.resolve();
        this._writeChain = Promise.resolve();
        this._running = false;
        this._closed = false;
        this._frameHandler = null;
        this._initWaiters = [];

        this._demux = new DxoStreamDemux({
            onInitSignature: () => this._onInitSignature(),
            onMessage: msg => this._onMessage(msg),
            onFrame: frame => this._frameHandler && this._frameHandler(frame),
            onDrop: (reason, detail) => console.debug(`[${this.name}] dropped ${reason}:`, detail),
        });
    }

    get isOpen() {
        return this._running && !this._closed;
    }

    /**
     * Starts the read pump and performs the initial ACK handshake.
     * Waits briefly for the camera's init signature but does not hang if it
     * never arrives (requests will then time out instead).
     */
    async open({ handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS } = {}) {
        if (this._running) return;
        this._running = true;

        const initSeen = new Promise(resolve => this._initWaiters.push(resolve));
        this._pumpPromise = this._pump();

        await this._write(METADATA_INIT_RESPONSE_SIGNATURE);

        let timer;
        const timedOut = new Promise(resolve => { timer = setTimeout(() => resolve(false), handshakeTimeoutMs); });
        const ok = await Promise.race([initSeen.then(() => true), timedOut]);
        clearTimeout(timer);
        if (!ok && !this._closed) {
            console.warn(`[${this.name}] no init signature within ${handshakeTimeoutMs} ms; continuing`);
        }
        if (this._closed) {
            throw new TransportError('Transport closed during handshake', 'closed');
        }
    }

    /**
     * Sends a JSON-RPC request and resolves with the reply whose id matches.
     * JSON-RPC error replies resolve normally (inspect `.error`); transport
     * failures and timeouts reject with TransportError.
     *
     * @param {string} method
     * @param {Object} [params]
     * @param {Object} [options]
     * @param {number} [options.timeoutMs]
     * @returns {Promise<Object>}
     */
    request(method, params, { timeoutMs } = {}) {
        const run = () => this._doRequest(method, params, timeoutMs);
        const result = this._requestChain.then(run, run);
        this._requestChain = result.catch(() => {});
        return result;
    }

    /**
     * Routes live-view frames to `handler(Uint8Array)`; pass null to stop.
     * This only controls the host side; switching the camera into view mode
     * is a separate request (dxo_camera_mode_switch).
     */
    setFrameHandler(handler) {
        this._frameHandler = handler || null;
        this._demux.setVideoEnabled(!!handler);
    }

    /**
     * Stops the pump and fails any in-flight request. Does not close the
     * USBDevice; the owner does that (which also aborts the pending read).
     */
    close(reason = 'Transport closed') {
        if (this._closed) return;
        this._closed = true;
        this._running = false;
        this.setFrameHandler(null);
        this._initWaiters.splice(0).forEach(resolve => resolve());
        if (this._inflight) {
            this._inflight.reject(new TransportError(reason, 'closed'));
        }
    }

    async _doRequest(method, params, timeoutMs) {
        if (this._closed) {
            throw new TransportError(`${this.name} is not connected`, 'closed');
        }

        const id = this._seq++;
        const frame = encodeRpcRequest(id, method, params);
        const timeout = timeoutMs ?? METHOD_TIMEOUTS_MS[method] ?? DEFAULT_TIMEOUT_MS;

        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                if (!entry.droppedIdless) this._lateIdlessExpected++;
                settle(reject, new TransportError(`${method} timed out after ${timeout} ms`, 'timeout'));
            }, timeout);

            const settle = (fn, value) => {
                if (this._inflight !== entry) return;
                clearTimeout(timer);
                this._inflight = null;
                fn(value);
            };

            const entry = {
                id,
                method,
                droppedIdless: false,
                resolve: msg => settle(resolve, msg),
                reject: err => settle(reject, err),
            };
            this._inflight = entry;

            this._write(METADATA_INIT_RESPONSE_SIGNATURE)
                .then(() => this._write(frame))
                .catch(err => entry.reject(err));
        });
    }

    _write(data) {
        const run = async () => {
            if (this._closed) throw new TransportError(`${this.name} is not connected`, 'closed');
            const res = await this.device.transferOut(this.outEndpoint, data);
            if (res && res.status && res.status !== 'ok') {
                throw new TransportError(`transferOut failed: ${res.status}`, 'io');
            }
            if (res && typeof res.bytesWritten === 'number' && res.bytesWritten !== data.length) {
                throw new TransportError(`transferOut wrote ${res.bytesWritten}/${data.length} bytes`, 'io');
            }
        };
        const result = this._writeChain.then(run, run);
        this._writeChain = result.catch(() => {});
        return result;
    }

    async _pump() {
        let emptyReads = 0;
        while (this._running) {
            let res;
            try {
                res = await this.device.transferIn(this.inEndpoint, MAX_PACKETSIZE);
            } catch (error) {
                if (this._running) this._fail(error);
                return;
            }
            if (!this._running) return;

            if (res.status === 'stall') {
                try {
                    await this.device.clearHalt('in', this.inEndpoint);
                } catch (error) {
                    this._fail(error);
                    return;
                }
                continue;
            }

            const data = res.data;
            if (!data || data.byteLength === 0) {
                // Avoid a hot loop on repeated zero-length reads.
                if (++emptyReads > 3) await new Promise(r => setTimeout(r, 10));
                continue;
            }
            emptyReads = 0;

            try {
                this._demux.push(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
            } catch (error) {
                console.error(`[${this.name}] error handling packet:`, error);
            }
        }
    }

    _fail(error) {
        if (this._closed) return;
        const message = `USB transfer failed: ${error?.message || error}`;
        this.close(message);
        this.onFatal(new TransportError(message, 'io'));
    }

    _onInitSignature() {
        this._write(METADATA_INIT_RESPONSE_SIGNATURE).catch(err => {
            console.warn(`[${this.name}] failed to ACK init signature:`, err);
        });
        this._initWaiters.splice(0).forEach(resolve => resolve());
    }

    _onMessage(message) {
        // Notifications (e.g. dxo_setting_applied, dxo_usb_flush_forced) carry a method.
        if (typeof message.method === 'string') {
            if (message.method !== 'dxo_usb_flush_forced') {
                try {
                    this.onEvent(message);
                } catch (error) {
                    console.error(`[${this.name}] event handler threw:`, error);
                }
            }
            return;
        }

        const inflight = this._inflight;

        if (typeof message.id === 'number') {
            this._sawReplyId = true;
            if (inflight && message.id === inflight.id) {
                inflight.resolve(message);
            } else {
                console.debug(`[${this.name}] dropped stale reply id=${message.id}`);
            }
            return;
        }

        // A reply without an id: with one request in flight it can only be that one,
        // unless it may be a late reply to an earlier, timed-out request.
        if (this._sawReplyId) {
            console.debug(`[${this.name}] dropped reply without id (camera uses ids)`, message);
            return;
        }
        if (this._lateIdlessExpected > 0) {
            this._lateIdlessExpected--;
            if (inflight) inflight.droppedIdless = true;
            console.debug(`[${this.name}] dropped reply without id (late reply to a timed-out request)`, message);
            return;
        }
        if (inflight) {
            console.debug(`[${this.name}] reply without id accepted for ${inflight.method}`);
            inflight.resolve(message);
        } else {
            console.debug(`[${this.name}] dropped unsolicited reply`, message);
        }
    }
}

export default DxoTransport;
