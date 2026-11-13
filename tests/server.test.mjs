import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import dgram from 'node:dgram';
import { execFileSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { AudioMeter, MicYouService, createDeckServer, createDiscoveryServer, SendController, Microphone, micYouState, parseLsofSockets } from '../mac/server.mjs';
import { parseMemory, parseProcesses } from '../mac/system.mjs';

function fixture() {
  const state = { target: { name: '测试应用', pid: 42 }, sends: 0 };
  const driver = async request => {
    if (request.action === 'status') return { target: state.target, input: 'BlackHole 2ch', accessibility: true };
    if (request.pid !== state.target.pid) throw new Error('当前应用已切换，请确认后再发送');
    state.sends++;
    return { dispatched: true };
  };
  return { state, driver };
}

test('sends one return key at most once and checks the current application', async () => {
  const { state, driver } = fixture();
  const controller = new SendController(driver);
  await controller.send(42, 'send-event-123');
  await controller.send(42, 'send-event-123');
  assert.equal(state.sends, 1);
  state.target = { name: '另一个应用', pid: 43 };
  await assert.rejects(controller.send(42, 'send-event-456'), /切换/);
  assert.equal(state.sends, 1);
});

test('does not retry an uncertain send or reuse an event for another app', async () => {
  let sends = 0;
  const controller = new SendController(async () => { sends++; throw new Error('结果不明确'); });
  await assert.rejects(controller.send(42, 'send-event-123'), /不明确/);
  await assert.rejects(controller.send(42, 'send-event-123'), /不明确/);
  await assert.rejects(controller.send(43, 'send-event-123'), /已被使用/);
  assert.equal(sends, 1);
});

test('parses real system samples and leaves unknown memory unknown', () => {
  assert.equal(parseMemory('System-wide memory free percentage: 46%'), 54);
  assert.equal(parseMemory('unexpected output'), null);
  assert.deepEqual(parseProcesses(' 3.2  2048 First App\n 12.4 4096 Second App\ninvalid\n'), [
    { name: 'Second App', cpu: 12.4, memoryMB: 4 },
    { name: 'First App', cpu: 3.2, memoryMB: 2 },
  ]);
});

test('only samples audio during an active hold and stops after release', async () => {
  const events = [];
  const meter = {
    start: () => events.push('meter-start'),
    stop: () => events.push('meter-stop'),
    status: () => ({ state: 'live', level: 0.4 }),
  };
  const fnEvents = [];
  const mic = new Microphone(async ({ muted }) => ({ muted }), async req => fnEvents.push(req.action), 6000, meter);
  await mic.start();
  assert.deepEqual(mic.hold().meter, { state: 'live', level: 0.4 });
  await mic.stop();
  assert.equal(events[0], 'meter-start');
  assert.equal(events.at(-1), 'meter-stop');
  assert.deepEqual(fnEvents, ['down', 'up']);
});

test('continuous mode opens microphone without holding Fn key down', async () => {
  const fnEvents = [];
  const mic = new Microphone(async ({ muted }) => ({ muted }), async req => fnEvents.push(req.action), 6000);
  const res = await mic.start({ fn: false, continuous: true });
  assert.equal(res.muted, false);
  assert.equal(mic.fnDown, false);
  assert.equal(mic.continuous, true);
  assert.deepEqual(fnEvents, []);
  assert.equal(mic.timer, null);
  assert.equal(mic.hold().muted, false);
  await mic.stop();
  assert.deepEqual(fnEvents, []);
  assert.equal(mic.continuous, false);
});

test('serves compiled apk over authenticated endpoint with sha256 checksum', async () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'andpan-apk-'));
  const fakeApk = path.join(dir, 'test.apk');
  writeFileSync(fakeApk, 'APK_BINARY_PAYLOAD_TEST');
  const deck = createDeckServer({
    token: 'test-token-abcdefghijklmnopqrstuvwxyz012345',
    apkPath: fakeApk,
    desktopDriver: async () => ({ target: { name: 'App', pid: 1 }, input: 'BlackHole 2ch', accessibility: true }),
    microphoneDriver: async () => ({ muted: true }),
    fnDriver: async () => ({}),
    micYouDriver: async () => ({ state: 'connected', port: 8554 }),
  });
  await new Promise(r => deck.server.listen(0, '127.0.0.1', r));
  const port = deck.server.address().port;
  try {
    const status = await new Promise((resolve, reject) => {
      const req = http.request(`http://127.0.0.1:${port}/status`, {
        headers: { Authorization: 'Bearer test-token-abcdefghijklmnopqrstuvwxyz012345' }
      }, res => {
        let buf = '';
        res.on('data', d => buf += d);
        res.on('end', () => resolve(JSON.parse(buf)));
      });
      req.on('error', reject);
      req.end();
    });
    assert.equal(status.apk.available, true);
    assert.equal(status.apk.size, 23);
    assert.match(status.apk.sha256, /^[a-f0-9]{64}$/);

    const download = await new Promise((resolve, reject) => {
      const req = http.request(`http://127.0.0.1:${port}/apk/andpan.apk`, {
        headers: { Authorization: 'Bearer test-token-abcdefghijklmnopqrstuvwxyz012345' }
      }, res => {
        let buf = '';
        res.on('data', d => buf += d);
        res.on('end', () => resolve({ status: res.statusCode, data: buf, sha: res.headers['x-apk-sha256'] }));
      });
      req.on('error', reject);
      req.end();
    });
    assert.equal(download.status, 200);
    assert.equal(download.data, 'APK_BINARY_PAYLOAD_TEST');
    assert.equal(download.sha, status.apk.sha256);
  } finally {
    await new Promise(r => deck.server.close(r));
    rmSync(dir, { recursive: true, force: true });
  }
});

test('meter displays received levels and treats stale samples as unavailable', async t => {
  const dir = mkdtempSync(path.join(tmpdir(), 'andpan-meter-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const fixture = path.join(dir, 'meter');
  writeFileSync(fixture, '#!/usr/bin/env node\nconsole.log("0.42"); setInterval(() => {}, 1000);\n');
  chmodSync(fixture, 0o700);
  const meter = new AudioMeter(fixture);
  t.after(() => meter.stop());
  meter.start();
  for (let i = 0; i < 60 && meter.status().state === 'starting'; i++) await delay(50);
  assert.deepEqual(meter.status(), { state: 'live', level: 0.42 });
  meter.updatedAt -= 3000;
  assert.deepEqual(meter.status(), { state: 'unavailable', level: null });
  meter.stop();
  assert.deepEqual(meter.status(), { state: 'off', level: null });
});

test('USB service requires its secret and releases Fn and mutes after a lost hold', async t => {
  const { driver } = fixture('');
  const updates = [];
  const token = 'a'.repeat(48);
  const deck = createDeckServer({
    token, desktopDriver: driver, leaseMs: 160,
    microphoneDriver: async request => {
      if (request.action === 'set') updates.push(request.muted ? 'mute' : 'unmute');
      return { muted: request.muted };
    },
    fnDriver: async request => { updates.push(`fn-${request.action}`); return { ok: true }; },
  });
  await new Promise(resolve => deck.server.listen(0, '127.0.0.1', resolve));
  t.after(() => deck.server.close());
  const base = `http://127.0.0.1:${deck.server.address().port}`;
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const unauthorized = await fetch(`${base}/status`);
  assert.equal(unauthorized.status, 401);
  const start = await fetch(`${base}/mic/start`, { method: 'POST', headers, body: '{}' });
  assert.equal(start.status, 200);
  assert.deepEqual(updates, ['unmute', 'fn-down']);
  await delay(70);
  const hold = await fetch(`${base}/mic/hold`, { method: 'POST', headers, body: '{}' });
  assert.equal(hold.status, 200);
  await delay(80);
  assert.deepEqual(updates, ['unmute', 'fn-down']);
  await delay(120);
  assert.deepEqual(updates, ['unmute', 'fn-down', 'fn-up', 'mute']);
});

test('Wi-Fi service uses TLS and the same authenticated controller', async t => {
  const dir = mkdtempSync(path.join(tmpdir(), 'andpan-tls-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const keyPath = path.join(dir, 'key.pem');
  const certPath = path.join(dir, 'cert.pem');
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes',
    '-days', '1', '-subj', '/CN=AndPan', '-keyout', keyPath, '-out', certPath], { stdio: 'ignore' });
  const cert = readFileSync(certPath);
  const { driver } = fixture('草稿');
  const token = 'b'.repeat(48);
  const deck = createDeckServer({
    token, desktopDriver: driver, microphoneDriver: async ({ action, muted }) => ({ muted: action === 'get' ? true : muted }),
    fnDriver: async () => ({}), tls: { key: readFileSync(keyPath), cert },
    micYouDriver: async peer => ({ state: peer === '127.0.0.1' ? 'connected' : 'unknown' }),
  });
  await new Promise(resolve => deck.wifiServer.listen(0, '127.0.0.1', resolve));
  t.after(() => deck.wifiServer.close());
  const port = deck.wifiServer.address().port;
  const request = (ca, authorization, requestPath = '/status', body) => new Promise((resolve, reject) => {
    const req = https.request({ host: '127.0.0.1', port, path: requestPath, servername: 'AndPan', ca,
      method: body ? 'POST' : 'GET',
      headers: authorization ? { Authorization: authorization } : {} }, res => {
      let body = '';
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(body) }));
    });
    req.on('error', reject);
    req.end(body ? JSON.stringify(body) : undefined);
  });
  await assert.rejects(request(undefined, `Bearer ${token}`), /self-signed|certificate/i);
  assert.equal((await request(cert, '')).status, 401);
  const response = await request(cert, `Bearer ${token}`);
  assert.equal(response.status, 200);
  assert.equal(response.body.system.target.name, '测试应用');
  assert.equal(response.body.system.input, 'BlackHole 2ch');
  assert.equal(response.body.system.accessibility, true);
  assert.equal(response.body.micYou.state, 'connected');
  assert.equal(response.body.microphone.available, true);
});

test('local discovery reports the Wi-Fi port without exposing connection credentials', async t => {
  const server = createDiscoveryServer(43126);
  await new Promise(resolve => server.bind(0, '127.0.0.1', resolve));
  const client = dgram.createSocket('udp4');
  t.after(() => { server.close(); client.close(); });
  const response = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('discovery timed out')), 1000);
    client.once('message', data => { clearTimeout(timer); resolve(data.toString()); });
  });
  client.send('ANDPAN_DISCOVER_V1', server.address().port, '127.0.0.1');
  assert.equal(await response, 'ANDPAN_V1:43126');
});

test('MicYou status identifies the phone and does not guess when its listener cannot be checked', () => {
  const sockets = 'p42\nf3\nn*:8554\nTST=LISTEN\nf4\nn192.168.0.98:8554->192.168.0.90:47127\nTST=ESTABLISHED\n';
  assert.equal(micYouState(sockets, '192.168.0.90'), 'connected');
  assert.equal(micYouState(sockets, '192.168.0.91'), 'disconnected');
  assert.equal(micYouState(sockets, ''), 'unknown');
  assert.equal(micYouState('', '192.168.0.90'), 'unknown');
});

test('parses lsof sockets and manages the bundled audio service lifecycle', async () => {
  const parsed = parseLsofSockets('p91\ncmicyou-cli\nf18\nn*:8554\nTST=LISTEN\n');
  assert.deepEqual(parsed, [{ command: 'micyou-cli', name: '*:8554', state: 'LISTEN' }]);
  const missing = new MicYouService({ binary: '/nonexistent/micyou-cli', port: 48554 });
  missing.inspectPort = async () => [];
  assert.equal((await missing.start()).service, 'unavailable');
  const occupied = new MicYouService({ binary: '/nonexistent/micyou-cli', port: 48554 });
  occupied.inspectPort = async () => [{ command: 'python3', name: '*:48554', state: 'LISTEN' }];
  const conflict = await occupied.start();
  assert.equal(conflict.service, 'unavailable');
  assert.match(conflict.error, /python3/);
  const existing = new MicYouService({ binary: '/nonexistent/micyou-cli', port: 48554 });
  existing.inspectPort = async () => [{ command: 'micyou', name: '*:48554', state: 'LISTEN' }];
  const reused = await existing.start();
  assert.equal(reused.service, 'running');
  assert.equal(reused.managed, false);
});

test('Mac input status can recover after the user fixes the selected input', async () => {
  let ready = false;
  const mic = new Microphone(async () => {
    if (!ready) throw new Error('请先将 Mac 的默认输入设备设为 BlackHole 2ch');
    return { muted: true };
  }, async () => ({}));
  await mic.refresh();
  assert.equal(mic.status().available, false);
  assert.match(mic.status().error, /BlackHole/);
  ready = true;
  mic.lastCheck = 0;
  await mic.refresh();
  assert.equal(mic.status().available, true);
  assert.equal(mic.status().muted, true);
  assert.equal(mic.status().error, '');
});

test('releasing a held button releases Fn before muting and does not press twice', async () => {
  const events = [];
  const mic = new Microphone(
    async ({ muted }) => { events.push(muted ? 'mute' : 'unmute'); return { muted }; },
    async ({ action }) => { events.push(`fn-${action}`); return { ok: true }; },
  );
  await mic.start();
  await mic.start();
  await mic.stop();
  assert.deepEqual(events, ['unmute', 'fn-down', 'fn-up', 'mute']);
});

test('a failed Fn press releases the key and mutes before reporting the error', async () => {
  const events = [];
  const mic = new Microphone(
    async ({ muted }) => { events.push(muted ? 'mute' : 'unmute'); return { muted }; },
    async ({ action }) => {
      events.push(`fn-${action}`);
      if (action === 'down') throw new Error('Fn 按下失败');
    },
  );
  await assert.rejects(mic.start(), /Fn 按下失败/);
  assert.deepEqual(events, ['unmute', 'fn-down', 'fn-up', 'mute']);
  assert.equal(mic.status().muted, true);
});

test('a failed Fn release still mutes and can be retried', async () => {
  const events = [];
  let releaseFails = true;
  const mic = new Microphone(
    async ({ muted }) => { events.push(muted ? 'mute' : 'unmute'); return { muted }; },
    async ({ action }) => {
      events.push(`fn-${action}`);
      if (action === 'up' && releaseFails) throw new Error('Fn 释放失败');
    },
  );
  await mic.start();
  await assert.rejects(mic.stop(), /Fn 释放失败/);
  assert.equal(mic.status().muted, true);
  releaseFails = false;
  await mic.stop();
  assert.deepEqual(events, ['unmute', 'fn-down', 'fn-up', 'mute', 'fn-up', 'mute']);
});

test('release queued while a press is starting cancels its watchdog too', async () => {
  const events = [];
  let finishUnmute;
  const mic = new Microphone(
    async ({ muted }) => {
      events.push(muted ? 'mute' : 'unmute');
      if (!muted) await new Promise(resolve => { finishUnmute = resolve; });
      return { muted };
    },
    async ({ action }) => { events.push(`fn-${action}`); },
    30,
  );
  const press = mic.start();
  await delay(0);
  const release = mic.stop();
  finishUnmute();
  await Promise.all([press, release]);
  await delay(60);
  assert.deepEqual(events, ['unmute', 'fn-down', 'fn-up', 'mute']);
});
