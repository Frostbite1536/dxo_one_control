import test from 'node:test';
import assert from 'node:assert/strict';

import { DxoStreamDemux, DxoTransport, TransportError, encodeRpcRequest, METADATA_INIT_SIGNATURE } from '../DxoTransport.js';
import DXOONE from '../dxo1usb.js';
import { CameraManager, CaptureBarrier, triggerSpreadMs } from '../CameraManager.js';
import { FakeDxoOne, fakeUsbBackend, frameMessage, frameJpeg, makeJpeg, splitPackets } from './fakeDxoOne.mjs';

// Silence the transport's diagnostic logging during tests.
console.debug = () => {};

const sleep = ms => new Promise(r => setTimeout(r, ms));

function collectDemux() {
    const out = { messages: [], frames: [], inits: 0, drops: [] };
    const demux = new DxoStreamDemux({
        onMessage: m => out.messages.push(m),
        onFrame: f => out.frames.push(f),
        onInitSignature: () => out.inits++,
        onDrop: (reason) => out.drops.push(reason),
    });
    return { demux, out };
}

// ---------------------------------------------------------------------------
// Demultiplexer (pure)
// ---------------------------------------------------------------------------

test('demux: RPC reply in a single packet', () => {
    const { demux, out } = collectDemux();
    demux.push(frameMessage({ id: 3, result: { ok: true } }));
    assert.deepEqual(out.messages, [{ id: 3, result: { ok: true } }]);
});

test('demux: RPC reply split across packets', () => {
    const { demux, out } = collectDemux();
    const big = { id: 1, result: { blob: 'x'.repeat(1500) } };
    splitPackets(frameMessage(big)).forEach(p => demux.push(p));
    assert.deepEqual(out.messages, [big]);
});

test('demux: bare 32-byte header then body packet', () => {
    const { demux, out } = collectDemux();
    const framed = frameMessage({ id: 9, result: 1 });
    demux.push(framed.slice(0, 32));
    demux.push(framed.slice(32));
    assert.deepEqual(out.messages, [{ id: 9, result: 1 }]);
});

test('demux: init signature is reported, not parsed', () => {
    const { demux, out } = collectDemux();
    demux.push(METADATA_INIT_SIGNATURE);
    assert.equal(out.inits, 1);
    assert.equal(out.messages.length, 0);
});

test('demux: unparseable JSON is dropped', () => {
    const { demux, out } = collectDemux();
    const framed = frameMessage({ id: 1 });
    framed[33] = 0x00; framed[34] = 0x7D; // corrupt the body after the brace
    demux.push(framed);
    assert.equal(out.messages.length, 0);
    assert.deepEqual(out.drops, ['rpc-parse']);
});

test('demux: JPEG split across packets keeps bytes of the next frame', () => {
    const { demux, out } = collectDemux();
    demux.setVideoEnabled(true);
    const a = makeJpeg(1300, 1);
    const b = makeJpeg(900, 2);
    // Frame A's tail and frame B's head share a packet.
    const stream = new Uint8Array([...frameJpeg(a), ...b]);
    splitPackets(stream, 512).forEach(p => demux.push(p));
    assert.equal(out.frames.length, 2);
    assert.deepEqual(out.frames[0], a);
    assert.deepEqual(out.frames[1], b);
});

test('demux: JPEG start marker straddling a packet boundary', () => {
    const { demux, out } = collectDemux();
    demux.setVideoEnabled(true);
    const jpg = makeJpeg(700, 3);
    const stream = new Uint8Array([0x01, 0x02, ...jpg]); // 2 junk bytes, then FFD8FF
    demux.push(stream.slice(0, 3));      // ends with FF D8 split
    demux.push(stream.slice(3));
    assert.equal(out.frames.length, 1);
    assert.deepEqual(out.frames[0], jpg);
});

test('demux: video bytes between two RPC replies do not corrupt either', () => {
    const { demux, out } = collectDemux();
    demux.setVideoEnabled(true);
    const jpg = makeJpeg(1100, 4);
    demux.push(frameMessage({ id: 1, result: 'a' }));
    splitPackets(frameJpeg(jpg)).forEach(p => demux.push(p));
    demux.push(frameMessage({ id: 2, result: 'b' }));
    assert.deepEqual(out.messages, [{ id: 1, result: 'a' }, { id: 2, result: 'b' }]);
    assert.deepEqual(out.frames, [jpg]);
});

test('demux: video is discarded while live view is off', () => {
    const { demux, out } = collectDemux();
    splitPackets(frameJpeg(makeJpeg(800))).forEach(p => demux.push(p));
    assert.equal(out.frames.length, 0);
});

test('encodeRpcRequest refuses payloads the 16-bit length cannot describe', () => {
    assert.throws(() => encodeRpcRequest(1, 'dxo_x', { p: 'x'.repeat(70000) }), RangeError);
    const framed = encodeRpcRequest(1, 'dxo_x');
    const len = framed[8] | (framed[9] << 8);
    assert.equal(len, framed.length - 32);
});

// ---------------------------------------------------------------------------
// Transport against the fake camera
// ---------------------------------------------------------------------------

async function openTransport(cam, opts) {
    await cam.open();
    const t = new DxoTransport(cam, 1, 2, opts);
    await t.open({ handshakeTimeoutMs: 200 });
    return t;
}

test('transport: handshake ACKs the camera init signature', async () => {
    const cam = new FakeDxoOne();
    const t = await openTransport(cam);
    await sleep(5);
    assert.equal(cam.acks, 2); // initial ACK + reply to the init signature
    t.close(); cam.unplug();
});

test('transport: handshake does not hang when the camera sends no init signature', async () => {
    const cam = new FakeDxoOne({ sendInitSignature: false });
    const started = Date.now();
    const t = await openTransport(cam);
    assert.ok(Date.now() - started < 1000);
    t.close(); cam.unplug();
});

test('transport: concurrent requests are serialized and matched by id', async () => {
    const cam = new FakeDxoOne({
        handlers: {
            // Replies arrive in reverse speed order; serialization must keep them straight.
            slow: (req, c) => c.later(30, () => c.queueMessage({ id: req.id, result: 'slow' })),
            fast: (req, c) => c.queueMessage({ id: req.id, result: 'fast' }),
        },
    });
    const t = await openTransport(cam);
    const [a, b] = await Promise.all([t.request('slow'), t.request('fast')]);
    assert.equal(a.result, 'slow');
    assert.equal(b.result, 'fast');
    t.close(); cam.unplug();
});

test('transport: notification before the reply is routed to events', async () => {
    const events = [];
    const cam = new FakeDxoOne({
        handlers: {
            dxo_setting_set: (req, c) => {
                c.queueMessage({ jsonrpc: '2.0', method: 'dxo_setting_applied', params: req.params });
                c.queueMessage({ jsonrpc: '2.0', id: req.id, result: { type: req.params.type } });
            },
        },
    });
    const t = await openTransport(cam, { onEvent: e => events.push(e) });
    const res = await t.request('dxo_setting_set', { type: 'iso', param: 'iso800' });
    assert.deepEqual(res.result, { type: 'iso' });
    assert.equal(events.length, 1);
    assert.equal(events[0].method, 'dxo_setting_applied');
    t.close(); cam.unplug();
});

test('transport: a reply without an id is accepted for the in-flight request', async () => {
    const cam = new FakeDxoOne({ handlers: { noid: (req, c) => c.queueMessage({ result: 'ok' }) } });
    const t = await openTransport(cam);
    assert.equal((await t.request('noid')).result, 'ok');
    t.close(); cam.unplug();
});

test('transport: a timed-out request does not shift later replies', async () => {
    const cam = new FakeDxoOne({
        handlers: {
            late: (req, c) => c.later(80, () => c.queueMessage({ id: req.id, result: 'late' })),
        },
    });
    const t = await openTransport(cam);

    await assert.rejects(t.request('late', undefined, { timeoutMs: 20 }),
        e => e instanceof TransportError && e.code === 'timeout');

    // The late reply (stale id) arrives while this request is in flight.
    cam.handlers.next = (req, c) => c.later(100, () => c.queueMessage({ id: req.id, result: 'next' }));
    const res = await t.request('next');
    assert.equal(res.result, 'next');
    t.close(); cam.unplug();
});

test('transport: a late id-less reply after a timeout is not given to the next request', async () => {
    const cam = new FakeDxoOne({
        handlers: {
            slow: (req, c) => c.later(40, () => c.queueMessage({ result: 'reply-to-slow' })),
            next: (req, c) => c.later(80, () => c.queueMessage({ id: req.id, result: 'reply-to-next' })),
        },
    });
    const t = await openTransport(cam);
    await assert.rejects(t.request('slow', undefined, { timeoutMs: 20 }), e => e.code === 'timeout');
    const res = await t.request('next');
    assert.equal(res.result, 'reply-to-next');
    t.close(); cam.unplug();
});

test('transport: a camera without ids recovers after a reply is lost entirely', async () => {
    let lose = true;
    const cam = new FakeDxoOne({
        handlers: {
            cmd: (req, c) => {
                if (lose) { lose = false; return; }          // first reply never arrives
                c.queueMessage({ result: `reply-${req.params.n}` });
            },
        },
    });
    const t = await openTransport(cam);
    await assert.rejects(t.request('cmd', { n: 0 }, { timeoutMs: 20 }), e => e.code === 'timeout');
    // At most one more request is sacrificed; after that every reply is delivered.
    await t.request('cmd', { n: 1 }, { timeoutMs: 20 }).catch(() => {});
    for (const n of [2, 3, 4]) {
        assert.equal((await t.request('cmd', { n }, { timeoutMs: 200 })).result, `reply-${n}`);
    }
    t.close(); cam.unplug();
});

test('transport: several timed-out requests on a backlogged camera without ids', async () => {
    const backlog = [];
    const cam = new FakeDxoOne({
        handlers: {
            slow: (req, c) => backlog.push(req.params.n),          // answered later, in order
            go: (req, c) => {
                backlog.splice(0).forEach(n => c.queueMessage({ result: `reply-${n}` }));
                c.queueMessage({ result: 'reply-go' });
            },
        },
    });
    const t = await openTransport(cam);
    for (const n of [0, 1, 2, 3]) {
        await assert.rejects(t.request('slow', { n }, { timeoutMs: 10 }), e => e.code === 'timeout');
    }
    assert.equal((await t.request('go', undefined, { timeoutMs: 200 })).result, 'reply-go');
    t.close(); cam.unplug();
});

test('transport: once the camera has sent ids, id-less replies are not attributed', async () => {
    const cam = new FakeDxoOne({
        handlers: {
            withid: (req, c) => c.queueMessage({ id: req.id, result: 'ok' }),
            noid: (req, c) => c.queueMessage({ result: 'stray' }),
        },
    });
    const t = await openTransport(cam);
    assert.equal((await t.request('withid')).result, 'ok');
    await assert.rejects(t.request('noid', undefined, { timeoutMs: 30 }), e => e.code === 'timeout');
    t.close(); cam.unplug();
});

test('transport: unplugging fails the in-flight request instead of hanging', async () => {
    let fatal = null;
    const cam = new FakeDxoOne({ handlers: { never: () => {} } });
    const t = await openTransport(cam, { onFatal: e => { fatal = e; } });
    const pending = t.request('never', undefined, { timeoutMs: 5000 });
    cam.unplug();
    await assert.rejects(pending, e => e instanceof TransportError && e.code === 'closed');
    assert.ok(fatal instanceof TransportError);
    await assert.rejects(t.request('anything'), e => e.code === 'closed');
});

test('transport: commands during live view get their own replies and frames stay intact', async () => {
    const frames = [];
    const sent = [];
    const cam = new FakeDxoOne({
        handlers: {
            dxo_camera_mode_switch: (req, c) => {
                c.queueMessage({ id: req.id, result: 'view' });
                // Stream frames; each frame is interleaved with whatever replies are queued.
                let n = 0;
                const tick = () => {
                    if (n >= 6) return;
                    const jpg = makeJpeg(1000 + n * 37, n);
                    sent.push(jpg);
                    c.queueJpeg(jpg);
                    n++;
                    c.later(5, tick);
                };
                tick();
            },
            dxo_photo_take: (req, c) => c.later(12, () => c.queueMessage({ id: req.id, result: 'photo' })),
        },
    });
    const t = await openTransport(cam);
    t.setFrameHandler(f => frames.push(f));
    await t.request('dxo_camera_mode_switch', { param: 'view' });
    const photo = await t.request('dxo_photo_take');
    const status = await t.request('dxo_camera_status_get');
    await sleep(60);

    assert.equal(photo.result, 'photo');
    assert.equal(status.result.method, 'dxo_camera_status_get');
    assert.deepEqual(frames, sent);
    t.close(); cam.unplug();
});

// ---------------------------------------------------------------------------
// Public APIs on top of the transport
// ---------------------------------------------------------------------------

test('dxo1usb: open() sends no RPC by itself and getDigitalZoom is callable', async () => {
    const cam = new FakeDxoOne();
    const dxo = await DXOONE.open(fakeUsbBackend([cam]));
    await sleep(10);
    assert.equal(cam.requests.length, 0);
    const zoom = await dxo.command.getDigitalZoom();
    assert.equal(zoom.result.method, 'dxo_digital_zoom_get');
    await dxo.close();
});

test('dxo1usb: setSettings resolves true on the confirmed reply and sends correct params', async () => {
    const cam = new FakeDxoOne({
        handlers: {
            dxo_setting_set: (req, c) => {
                c.queueMessage({ method: 'dxo_setting_applied', params: req.params });
                c.queueMessage({ id: req.id, result: { type: req.params.type } });
            },
        },
    });
    const dxo = await DXOONE.open(fakeUsbBackend([cam]));
    assert.equal(await dxo.command.setSettings.imageFormat.tnrOff(), true);
    assert.equal(await dxo.command.setSettings.shootingMode.night(), true);
    assert.deepEqual(cam.requests.map(r => r.params), [
        { type: 'tnr', param: 'off' },
        { type: 'shooting_mode', param: 'night' },
    ]);
    await dxo.close();
});

test('dxo1usb: value-taking setters send the value, or their default when called without one', async () => {
    const cam = new FakeDxoOne({
        handlers: { dxo_setting_set: (req, c) => c.queueMessage({ id: req.id, result: { type: req.params.type } }) },
    });
    const dxo = await DXOONE.open(fakeUsbBackend([cam]));
    await dxo.command.setSettings.metadata.artist('Jane');
    await dxo.command.setSettings.metadata.copyright();
    await dxo.command.setSettings.mfInvDistance(7);
    await dxo.command.setSettings.mfInvDistance();
    assert.deepEqual(cam.requests.map(r => r.params), [
        { type: 'artist', param: 'Jane' },
        { type: 'copyright', param: 'COPYRIGHT' },
        { type: 'mf_inv_distance', param: '5.000000' },
        { type: 'mf_inv_distance', param: '0.000000' },
    ]);
    await dxo.close();
});

test('dxo1usb: setSettings resolves false on a JSON-RPC error', async () => {
    const cam = new FakeDxoOne({
        handlers: { dxo_setting_set: (req, c) => c.queueMessage({ id: req.id, error: { code: -1, message: 'nope' } }) },
    });
    const dxo = await DXOONE.open(fakeUsbBackend([cam]));
    assert.equal(await dxo.command.setSettings.iso.iso800(), false);
    await dxo.close();
});

test('CameraManager: captureAll reports a refused capture as an error', async () => {
    const ok = new FakeDxoOne({ serialNumber: 'OK01' });
    const bad = new FakeDxoOne({
        serialNumber: 'BAD1',
        handlers: { dxo_photo_take: (req, c) => c.queueMessage({ id: req.id, error: { message: 'card full' } }) },
    });
    const manager = new CameraManager({ usbBackend: fakeUsbBackend([ok, bad]) });
    await manager.connectCamera();
    await manager.connectCamera();

    const results = await manager.captureAll();
    const byId = Object.fromEntries(results.map(r => [r.cameraId, r]));
    assert.equal(byId.OK01.status, 'success');
    assert.equal(byId.BAD1.status, 'error');
    assert.match(byId.BAD1.error, /card full/);
    await manager.dispose();
});

test('CameraManager: unplug during capture fails that camera without hanging the others', async () => {
    const ok = new FakeDxoOne({ serialNumber: 'OK02' });
    const gone = new FakeDxoOne({ serialNumber: 'GONE', handlers: { dxo_photo_take: () => {} } });
    const backend = fakeUsbBackend([ok, gone]);
    const manager = new CameraManager({ usbBackend: backend });
    await manager.connectCamera();
    await manager.connectCamera();

    const capture = manager.captureAll();
    await sleep(10);
    gone.unplug();
    backend.emitDisconnect(gone);

    const results = await capture;
    const byId = Object.fromEntries(results.map(r => [r.cameraId, r]));
    assert.equal(byId.OK02.status, 'success');
    assert.equal(byId.GONE.status, 'error');
    assert.equal(manager.cameraCount, 1);
    await manager.dispose();
});

test('CameraDevice: stopping live view while it is starting leaves it stopped', async () => {
    const cam = new FakeDxoOne({
        serialNumber: 'LV02',
        handlers: {
            dxo_camera_mode_switch: (req, c) => c.later(30, () => c.queueMessage({ id: req.id, result: 'view' })),
        },
    });
    const manager = new CameraManager({ usbBackend: fakeUsbBackend([cam]) });
    const camera = await manager.connectCamera();

    const urls = [];
    const starting = camera.startLiveView(url => urls.push(url));
    camera.stopLiveView();
    await starting;
    assert.equal(camera.isLiveViewActive, false);

    cam.queueJpeg(makeJpeg(600));
    await sleep(10);
    assert.equal(urls.length, 0);
    await manager.dispose();
});

test('CameraDevice: live view start is idempotent and stop discards further frames', async () => {
    const cam = new FakeDxoOne({ serialNumber: 'LV01' });
    const manager = new CameraManager({ usbBackend: fakeUsbBackend([cam]) });
    const camera = await manager.connectCamera();

    const urls = [];
    await camera.startLiveView(url => urls.push(url));
    await camera.startLiveView(url => urls.push(url));
    const modeSwitches = cam.requests.filter(r => r.method === 'dxo_camera_mode_switch').length;
    assert.equal(modeSwitches, 1);
    assert.equal(camera.isLiveViewActive, true);

    cam.queueJpeg(makeJpeg(600));
    await sleep(10);
    assert.equal(urls.length, 1);

    camera.stopLiveView();
    cam.queueJpeg(makeJpeg(600));
    await sleep(10);
    assert.equal(urls.length, 1);
    assert.equal(camera.isLiveViewActive, false);
    await manager.dispose();
});

test('CameraDevice: unknown commands are rejected before anything is sent', async () => {
    const cam = new FakeDxoOne({ serialNumber: 'AL01' });
    const manager = new CameraManager({ usbBackend: fakeUsbBackend([cam]) });
    const camera = await manager.connectCamera();
    const sent = cam.requests.length;
    await assert.rejects(camera.transferOutRPC('dxo_format_sdcard'), /Unknown camera command/);
    assert.equal(cam.requests.length, sent);
    await manager.dispose();
});

test('CameraManager: cameras without a serial number get stable, unique ids', async () => {
    const a = new FakeDxoOne({ serialNumber: '' });
    const b = new FakeDxoOne({ serialNumber: '' });
    const manager = new CameraManager({ usbBackend: fakeUsbBackend([a, b]) });
    const first = await manager.connectCamera();
    const second = await manager.connectCamera();
    assert.notEqual(first.id, second.id);
    assert.ok(!/\d{10,}/.test(first.id), 'id must not contain a timestamp');
    assert.equal(manager.cameraCount, 2);
    await manager.dispose();
});

test('CaptureBarrier: releases together; a timeout releases everyone still waiting at once', async () => {
    const b = new CaptureBarrier(3, 1000);
    const t0 = performance.now();
    const all = Promise.all([b.arriveAndAwait(), sleep(30).then(() => b.arriveAndAwait()), sleep(60).then(() => b.leave())]);
    const [x, y] = await all;
    assert.equal(x, true); assert.equal(y, true);
    assert.ok(performance.now() - t0 < 200);

    const broken = new CaptureBarrier(3, 80);           // third party never shows up
    const start = performance.now();
    const times = await Promise.all([0, 40].map(d => sleep(d).then(() => broken.arriveAndAwait()).then(ok => [ok, performance.now() - start])));
    assert.deepEqual(times.map(([ok]) => ok), [false, false]);
    assert.ok(Math.abs(times[0][1] - times[1][1]) < 30, `released apart: ${times.map(t => t[1])}`);
    assert.equal(await broken.arriveAndAwait(), false);  // late arrival doesn't wait
});

test('CameraManager: parallel capture holds busy and idle cameras at one start line', async () => {
    const writtenAt = {};
    const slow = new Set();                              // serials whose next status reply is slow
    const make = (serial) => {
        return new FakeDxoOne({
            serialNumber: serial,
            handlers: {
                dxo_camera_status_get: (req, c) => {
                    if (slow.delete(serial)) c.later(80, () => c.queueMessage({ id: req.id, result: {} }));
                    else c.queueMessage({ id: req.id, result: {} });
                },
                dxo_photo_take: (req, c) => { writtenAt[serial] = performance.now(); c.queueMessage({ id: req.id, result: 'ok' }); },
            },
        });
    };
    const cams = [make('P1'), make('P2'), make('P3')];
    const manager = new CameraManager({ usbBackend: fakeUsbBackend(cams), barrierTimeoutMs: 1000 });
    for (let i = 0; i < 3; i++) await manager.connectCamera();

    // Camera P2 is busy with a slow command when the capture starts.
    slow.add('P2');
    const busy = manager.getCamera('P2').transferOutRPC('dxo_camera_status_get');
    let reported;
    manager.onCaptureComplete = (results, total, spread) => { reported = spread; };
    const results = await manager.captureAll();
    await busy;

    assert.ok(results.every(r => r.status === 'success'));
    const times = Object.values(writtenAt);
    assert.ok(Math.max(...times) - Math.min(...times) < 30, `writes spread ${Math.max(...times) - Math.min(...times)} ms`);
    assert.equal(typeof reported, 'number');
    assert.ok(reported < 30);
    assert.equal(triggerSpreadMs([{ status: 'success', triggeredAt: 5 }]), null);
    await manager.dispose();
});

test('CameraManager: a camera that fails before the start line does not hold the others', async () => {
    const ok = new FakeDxoOne({ serialNumber: 'F1' });
    const gone = new FakeDxoOne({ serialNumber: 'F2' });
    const manager = new CameraManager({ usbBackend: fakeUsbBackend([ok, gone]), barrierTimeoutMs: 5000 });
    await manager.connectCamera();
    await manager.connectCamera();
    manager.getCamera('F2').markDisconnected();          // fails immediately on takePhoto
    const t0 = performance.now();
    const results = await manager.captureAll();
    assert.ok(performance.now() - t0 < 1000, 'must not wait for the barrier timeout');
    assert.deepEqual(results.map(r => r.status), ['success', 'error']);
    await manager.dispose();
});

test('transport: onWritten fires after the frame is written and before the reply', async () => {
    const cam = new FakeDxoOne({
        handlers: { slowreply: (req, c) => c.later(40, () => c.queueMessage({ id: req.id, result: 'r' })) },
    });
    const t = await openTransport(cam);
    let seenAtWrite = null;
    let resolved = false;
    const pending = t.request('slowreply', undefined, {
        onWritten: () => { seenAtWrite = { frameSent: cam.requests.length === 1, resolved }; },
    }).then(r => { resolved = true; return r; });
    await pending;
    assert.deepEqual(seenAtWrite, { frameSent: true, resolved: false });
    t.close(); cam.unplug();
});

test('CameraManager: sequential capture reports no trigger spread', async () => {
    const manager = new CameraManager({ usbBackend: fakeUsbBackend([new FakeDxoOne({ serialNumber: 'S1' }), new FakeDxoOne({ serialNumber: 'S2' })]) });
    await manager.connectCamera();
    await manager.connectCamera();
    manager.setSyncMode('sequential');
    let reported = 'unset';
    manager.onCaptureComplete = (results, total, spread) => { reported = spread; };
    await manager.captureAll();
    assert.equal(reported, null);
    await manager.dispose();
});

