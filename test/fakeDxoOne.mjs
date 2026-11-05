// A scripted stand-in for a DXO One on WebUSB, for transport tests.
//
// It implements the subset of USBDevice the code uses, decodes framed
// JSON-RPC requests written to the OUT endpoint, and queues framed replies
// (split into 512-byte packets) on the IN endpoint. Behaviour per method is
// configurable so tests can model late replies, missing ids, notifications,
// and live-view JPEG data interleaved with replies.

import {
    METADATA_INIT_SIGNATURE,
    METADATA_INIT_RESPONSE_SIGNATURE,
    RPC_HEADER,
    RPC_HEADER_TRAILER,
    MAX_PACKETSIZE,
} from '../DxoTransport.js';
import { compareU8A, mergeU8A } from '../u8a.js';

const MAGIC = [0xA3, 0xBA, 0xD1, 0x10];

export function frameMessage(obj) {
    const payload = new TextEncoder().encode(JSON.stringify(obj) + '\x00');
    const details = mergeU8A([payload.length & 0xFF, payload.length >> 8], RPC_HEADER_TRAILER);
    return mergeU8A(mergeU8A(RPC_HEADER, details), payload);
}

export function splitPackets(bytes, size = MAX_PACKETSIZE) {
    const packets = [];
    for (let i = 0; i < bytes.length; i += size) packets.push(bytes.slice(i, i + size));
    return packets;
}

/** A fake JPEG: FFD8FF, deterministic filler without FF bytes, FFD9. */
export function makeJpeg(length, seed = 1) {
    const jpg = new Uint8Array(length);
    jpg.set([0xFF, 0xD8, 0xFF], 0);
    for (let i = 3; i < length - 2; i++) jpg[i] = (i * 7 + seed) % 0xFE;
    jpg.set([0xFF, 0xD9], length - 2);
    return jpg;
}

/** Live-view framing: a 32-byte A3BAD110 header packet prefix followed by the JPEG. */
export function frameJpeg(jpg) {
    const header = new Uint8Array(32);
    header.set(MAGIC, 0);
    header[8] = jpg.length & 0xFF;
    header[9] = (jpg.length >> 8) & 0xFF;
    return mergeU8A(header, jpg);
}

export class FakeDxoOne {
    constructor({ serialNumber = 'FAKE0001', handlers = {}, sendInitSignature = true } = {}) {
        this.serialNumber = serialNumber;
        this.vendorId = 0x2b8f;
        this.productId = 0x0001;
        this.opened = false;
        this.handlers = handlers;
        this.sendInitSignature = sendInitSignature;

        this.requests = [];   // decoded JSON requests, in order
        this.acks = 0;        // METADATA_INIT_RESPONSE_SIGNATURE writes
        this._inQueue = [];
        this._waiters = [];
        this._closedError = null;
        this._timers = new Set();

        this.configuration = {
            interfaces: [
                { interfaceNumber: 0, alternate: { endpoints: [{ endpointNumber: 1 }, { endpointNumber: 2 }] } },
                { interfaceNumber: 1, alternate: { endpoints: [] } },
            ],
        };
    }

    // ---- USBDevice surface -------------------------------------------------

    async open() {
        this.opened = true;
        if (this.sendInitSignature) this.queuePacket(METADATA_INIT_SIGNATURE);
    }
    async selectConfiguration() {}
    async claimInterface() {}
    async selectAlternateInterface() {}
    async clearHalt() {}

    async close() {
        this.opened = false;
        this.unplug();
    }

    async transferOut(endpoint, data) {
        if (this._closedError) throw this._closedError;
        const bytes = new Uint8Array(data);

        if (compareU8A(bytes, METADATA_INIT_RESPONSE_SIGNATURE)) {
            this.acks++;
        } else if (bytes.length > 32 && bytes[0] === 0xA3) {
            const text = new TextDecoder().decode(bytes.subarray(32)).replace(/\x00/g, '');
            const request = JSON.parse(text);
            this.requests.push(request);
            this._handle(request);
        }
        return { status: 'ok', bytesWritten: bytes.length };
    }

    transferIn() {
        if (this._closedError) return Promise.reject(this._closedError);
        if (this._inQueue.length) return Promise.resolve(this._packetResult(this._inQueue.shift()));
        return new Promise((resolve, reject) => this._waiters.push({ resolve, reject }));
    }

    // ---- scripting helpers ----------------------------------------------------

    queuePacket(packet) {
        const waiter = this._waiters.shift();
        if (waiter) waiter.resolve(this._packetResult(packet));
        else this._inQueue.push(packet);
    }

    queueMessage(obj) {
        splitPackets(frameMessage(obj)).forEach(p => this.queuePacket(p));
    }

    queueJpeg(jpg) {
        splitPackets(frameJpeg(jpg)).forEach(p => this.queuePacket(p));
    }

    later(ms, fn) {
        const t = setTimeout(() => { this._timers.delete(t); if (!this._closedError) fn(); }, ms);
        this._timers.add(t);
    }

    /** Simulates the cable being pulled: pending and future reads reject. */
    unplug() {
        if (this._closedError) return;
        this._closedError = Object.assign(new Error('The device was disconnected.'), { name: 'NetworkError' });
        this._waiters.splice(0).forEach(w => w.reject(this._closedError));
        this._timers.forEach(t => clearTimeout(t));
        this._timers.clear();
    }

    _packetResult(packet) {
        const copy = new Uint8Array(packet);
        return { status: 'ok', data: new DataView(copy.buffer) };
    }

    _handle(request) {
        const handler = this.handlers[request.method] || defaultHandler;
        handler(request, this);
    }
}

function defaultHandler(request, cam) {
    cam.queueMessage({ jsonrpc: '2.0', id: request.id, result: { method: request.method } });
}

export function fakeUsbBackend(devices) {
    const queue = [...devices];
    const listeners = new Set();
    return {
        async requestDevice() {
            const device = queue.shift();
            if (!device) throw Object.assign(new Error('No device selected.'), { name: 'NotFoundError' });
            return device;
        },
        addEventListener(type, fn) { if (type === 'disconnect') listeners.add(fn); },
        removeEventListener(type, fn) { listeners.delete(fn); },
        emitDisconnect(device) { listeners.forEach(fn => fn({ device })); },
    };
}
