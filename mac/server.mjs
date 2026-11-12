import http from 'node:http';
import https from 'node:https';
import dgram from 'node:dgram';
import fs from 'node:fs';
import { createHash, timingSafeEqual } from 'node:crypto';
import { execFile, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { SystemMonitor } from './system.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const defaultAudioPort = Number(process.env.ANDPAN_AUDIO_PORT || 8554);

function problem(message, status = 409, code = '') {
  return Object.assign(new Error(message), { status, code });
}

export function runDriver(binary, request) {
  return new Promise((resolve, reject) => {
    const child = spawn(binary, [], { stdio: ['pipe', 'pipe', 'ignore'] });
    let output = '';
    let finished = false;
    const finish = (error, result) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      error ? reject(error) : resolve(result);
    };
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      finish(problem('电脑操作超时，请先检查电脑上的结果', 503));
    }, 15000);
    child.on('error', () => finish(problem('无法启动电脑控制程序', 503)));
    child.stdin.on('error', () => {});
    child.stdout.on('data', chunk => {
      output += chunk;
      if (output.length > 100000) {
        child.kill('SIGKILL');
        finish(problem('电脑控制响应异常', 503));
      }
    });
    child.on('close', code => {
      try {
        const data = JSON.parse(output);
        if (code !== 0) finish(problem(data.error || '电脑操作失败', 409, data.code || ''));
        else finish(null, data);
      } catch {
        finish(problem('电脑控制程序响应异常', 503));
      }
    });
    child.stdin.end(JSON.stringify(request));
  });
}

export class SendController {
  constructor(driver) {
    this.driver = driver;
    this.busy = false;
    this.receipts = new Map();
  }

  async send(pid, eventId) {
    if (!Number.isInteger(pid) || pid <= 0 || pid > 2147483647 ||
        typeof eventId !== 'string' || !/^[a-zA-Z0-9-]{8,80}$/.test(eventId)) {
      throw problem('操作无效', 400);
    }
    const existing = this.receipts.get(eventId);
    if (existing) {
      if (existing.pid !== pid) throw problem('操作编号已被使用', 400);
      if (existing.error) throw problem(existing.error);
      return existing.result;
    }
    if (this.busy) throw problem('上一条操作尚未完成');
    this.busy = true;
    const receipt = { pid, error: '操作结果未知，请先检查电脑，避免重复发送' };
    this.receipts.set(eventId, receipt);
    while (this.receipts.size > 128) this.receipts.delete(this.receipts.keys().next().value);
    try {
      const result = await this.driver({ action: 'send', pid });
      if (result.dispatched !== true) throw problem('操作结果未知，请先检查电脑，避免重复发送');
      receipt.result = { dispatched: true };
      delete receipt.error;
      return receipt.result;
    } catch (error) {
      receipt.error = error.message;
      throw error;
    } finally {
      this.busy = false;
    }
  }
}

export class AudioMeter {
  constructor(binary) {
    this.binary = binary;
    this.child = null;
    this.level = null;
    this.updatedAt = 0;
    this.startedAt = 0;
    this.failed = false;
  }

  start() {
    this.stop();
    this.level = null;
    this.updatedAt = 0;
    this.startedAt = Date.now();
    this.failed = false;
    const child = spawn(this.binary, [], { stdio: ['ignore', 'pipe', 'ignore'] });
    this.child = child;
    let pending = '';
    child.stdout.on('data', data => {
      pending = (pending + data.toString()).slice(-4096);
      const lines = pending.split('\n');
      pending = lines.pop();
      for (const line of lines) {
        const level = Number(line);
        if (line.trim() && Number.isFinite(level) && level >= 0 && level <= 1) {
          this.level = level;
          this.updatedAt = Date.now();
        }
      }
    });
    child.on('error', () => { if (this.child === child) this.failed = true; });
    child.on('close', () => { if (this.child === child) this.failed = true; });
  }

  stop() {
    const child = this.child;
    this.child = null;
    if (child) child.kill();
    this.level = null;
    this.updatedAt = 0;
  }

  status() {
    if (!this.child) return { state: 'off', level: null };
    if (this.failed || (this.updatedAt && Date.now() - this.updatedAt > 2500) ||
        (!this.updatedAt && Date.now() - this.startedAt > 5000)) {
      return { state: 'unavailable', level: null };
    }
    return this.updatedAt ? { state: 'live', level: this.level } : { state: 'starting', level: null };
  }
}

export class Microphone {
  constructor(driver, fnDriver, leaseMs = 6000, meter = null) {
    this.driver = driver;
    this.fnDriver = fnDriver;
    this.leaseMs = leaseMs;
    this.queue = Promise.resolve();
    this.timer = null;
    this.muted = null;
    this.error = '';
    this.inputError = '';
    this.fnDown = false;
    this.lastCheck = 0;
    this.checking = null;
    this.meter = meter;
  }

  perform(operation) {
    const task = this.queue.then(operation);
    this.queue = task.catch(() => {});
    return task;
  }

  async set(muted) {
    try {
      const result = await this.driver({ action: 'set', muted });
      this.muted = result.muted;
      this.inputError = '';
      return result;
    } catch (error) {
      this.muted = null;
      throw error;
    }
  }

  async refresh() {
    if (this.checking) return this.checking;
    if (Date.now() - this.lastCheck < 2500) return;
    this.checking = this.perform(async () => {
      try {
        const result = await this.driver({ action: 'get' });
        if (typeof result.muted !== 'boolean') throw problem('无法读取 Mac 麦克风状态');
        this.muted = result.muted;
        this.inputError = '';
      } catch (error) {
        this.muted = null;
        this.inputError = error.message;
      }
      this.lastCheck = Date.now();
    });
    try { await this.checking; } finally { this.checking = null; }
  }

  arm() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => { void this.stop().catch(() => {}); }, this.leaseMs);
    this.timer.unref();
  }

  async start({ fn = true } = {}) {
    const wantFn = fn !== false;
    return this.perform(async () => {
      if (this.muted === false && this.fnDown === wantFn) {
        this.arm();
        return { muted: false, meter: this.meter?.status() };
      }
      try {
        if (this.muted !== false) await this.set(false);
        if (wantFn && !this.fnDown) {
          // Mark the key before calling the driver: even a failed or timed-out
          // request may have posted the key-down event.
          this.fnDown = true;
          await this.fnDriver({ action: 'down' });
        } else if (!wantFn && this.fnDown) {
          await this.fnDriver({ action: 'up' });
          this.fnDown = false;
        }
        this.error = '';
        this.arm();
        this.meter?.start();
        return { muted: false, meter: this.meter?.status() };
      } catch (error) {
        if (this.fnDown) {
          try { await this.fnDriver({ action: 'up' }); this.fnDown = false; } catch {}
        }
        try { await this.set(true); } catch {}
        this.error = error.message;
        throw error;
      }
    });
  }

  hold() {
    if (this.muted !== false || !this.timer) throw problem('麦克风已静音，请重新按住');
    this.arm();
    return { muted: false, meter: this.meter?.status() };
  }

  async stop() {
    clearTimeout(this.timer);
    this.timer = null;
    this.meter?.stop();
    return this.perform(async () => {
      // A start may have finished after stop was queued.
      clearTimeout(this.timer);
      this.timer = null;
      this.meter?.stop();
      let failure;
      if (this.fnDown) {
        try {
          await this.fnDriver({ action: 'up' });
          this.fnDown = false;
        } catch (error) { failure = error; }
      }
      try { await this.set(true); }
      catch (error) { failure ||= error; }
      this.error = failure?.message || '';
      if (failure) throw failure;
      return { muted: true };
    });
  }

  status() {
    return { available: this.muted !== null, muted: this.muted, error: this.error || this.inputError };
  }
}

function authorized(header, token) {
  const expected = Buffer.from(`Bearer ${token}`);
  const received = Buffer.from(header || '');
  return expected.length === received.length && timingSafeEqual(expected, received);
}

export function parseLsofSockets(output) {
  const sockets = [];
  let command = '', name = '', state = '';
  const push = () => {
    if (name || state) sockets.push({ command, name, state });
    name = '';
    state = '';
  };
  for (const line of output.split('\n')) {
    if (line.startsWith('p')) { push(); command = ''; }
    else if (line.startsWith('c')) command = line.slice(1);
    else if (line.startsWith('f')) { push(); }
    else if (line.startsWith('n')) name = line.slice(1);
    else if (line.startsWith('TST=')) state = line.slice(4);
  }
  push();
  return sockets;
}

export function micYouState(output, peer) {
  let listening = false, connected = false;
  for (const socket of parseLsofSockets(output)) {
    if (socket.state === 'LISTEN') listening = true;
    if (socket.state === 'ESTABLISHED' && peer && socket.name.includes(`->${peer}:`)) connected = true;
  }
  if (!listening || !peer) return 'unknown';
  return connected ? 'connected' : 'disconnected';
}

export class MicYouService {
  constructor({ binary, port = 8554, device = 'BlackHole 2ch', dataDir = '' } = {}) {
    this.binary = binary || '';
    this.port = port;
    this.device = device;
    this.dataDir = dataDir;
    this.child = null;
    this.managed = false;
    this.state = 'stopped';
    this.error = '';
  }

  inspectPort() {
    return new Promise(resolve => {
      execFile('/usr/sbin/lsof', ['-nP', `-iTCP:${this.port}`, '-sTCP:LISTEN', '-FcT'],
        { timeout: 2000, maxBuffer: 50000 }, (error, stdout) => {
          if (error) return resolve([]);
          resolve(parseLsofSockets(stdout).filter(item => item.state === 'LISTEN'));
        });
    });
  }

  async start() {
    const existing = await this.inspectPort();
    if (existing.length > 0) {
      const isMicYou = existing.some(item => item.command.toLowerCase().startsWith('micyou'));
      this.managed = false;
      if (isMicYou) {
        this.state = 'external';
        this.error = '';
      } else {
        this.state = 'unavailable';
        this.error = `语音端口 ${this.port} 已被 ${existing[0].command || '其他程序'} 占用`;
      }
      return this.status();
    }
    if (!this.binary || !fs.existsSync(this.binary)) {
      this.state = 'unavailable';
      this.error = '缺少 MicYou 语音接收程序';
      return this.status();
    }
    if (this.dataDir) fs.mkdirSync(this.dataDir, { recursive: true, mode: 0o700 });
    this.state = 'starting';
    this.error = '';
    this.managed = true;
    const args = [
      'serve', '--port', String(this.port), '--mode', 'wifi',
      '--device', this.device, '--bind', '0.0.0.0', '-q',
    ];
    const env = this.dataDir ? { ...process.env, XDG_DATA_HOME: this.dataDir } : process.env;
    const child = spawn(this.binary, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    this.child = child;
    let lastError = '';
    const capture = chunk => {
      const lines = chunk.toString().split(/\r?\n/).map(line => line.trim()).filter(Boolean);
      if (lines.length > 0) lastError = lines.at(-1).slice(0, 160);
    };
    child.stdout.on('data', capture);
    child.stderr.on('data', capture);
    child.on('error', error => {
      if (this.child !== child) return;
      this.child = null;
      this.managed = false;
      this.state = 'unavailable';
      this.error = `无法启动内置语音服务：${error.message}`;
    });
    child.on('close', code => {
      if (this.child !== child) return;
      this.child = null;
      this.managed = false;
      if (this.state !== 'stopped') {
        this.state = 'unavailable';
        this.error = lastError || `内置语音服务已退出 (${code ?? 'signal'})`;
      }
    });
    const deadline = Date.now() + 3500;
    while (this.child === child && Date.now() < deadline) {
      const listeners = await this.inspectPort();
      if (listeners.length > 0) {
        this.state = 'running';
        this.error = '';
        return this.status();
      }
      await new Promise(resolve => setTimeout(resolve, 150));
    }
    if (this.child === child) this.state = 'running';
    return this.status();
  }

  async stop() {
    this.state = 'stopped';
    const child = this.child;
    this.child = null;
    this.managed = false;
    if (!child) return;
    await new Promise(resolve => {
      const timer = setTimeout(() => {
        try { child.kill('SIGKILL'); } catch {}
        resolve();
      }, 2500);
      child.once('close', () => { clearTimeout(timer); resolve(); });
      try { child.kill('SIGTERM'); } catch { clearTimeout(timer); resolve(); }
    });
  }

  status() {
    return {
      port: this.port,
      service: this.state === 'external' ? 'running' : this.state,
      managed: this.managed,
      error: this.error,
    };
  }
}

function micYouMonitor(audioPort = 8554, micYouService = null) {
  let cached = '', checkedAt = 0, pending;
  return async peer => {
    if (Date.now() - checkedAt >= 2000) {
      pending ||= new Promise(resolve => {
        execFile('/usr/sbin/lsof', ['-nP', '-a', '-c', 'micyou', `-iTCP:${audioPort}`, '-FnT'],
          { timeout: 2000, maxBuffer: 100000 }, (error, stdout) => {
            cached = error ? '' : stdout;
            checkedAt = Date.now();
            pending = null;
            resolve();
          });
      });
      await pending;
    }
    const base = micYouService ? micYouService.status() : { port: audioPort, service: 'unknown', managed: false, error: '' };
    return { ...base, state: micYouState(cached, peer) };
  };
}

export function createDiscoveryServer(wifiPort) {
  const socket = dgram.createSocket('udp4');
  socket.on('message', (message, peer) => {
    if (message.toString() === 'ANDPAN_DISCOVER_V1') {
      socket.send(`ANDPAN_V1:${wifiPort}`, peer.port, peer.address);
    }
  });
  return socket;
}

async function readBody(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (Buffer.byteLength(body) > 4096) throw problem('请求过大', 413);
  }
  try { return JSON.parse(body || '{}'); }
  catch { throw problem('请求格式无效', 400); }
}

function createApkInspector(apkPath) {
  let cached = { mtimeMs: -1, size: -1, info: { available: false, size: 0, sha256: '' } };
  return () => {
    if (!apkPath) return { available: false, size: 0, sha256: '' };
    try {
      const stat = fs.statSync(apkPath);
      if (!stat.isFile()) return { available: false, size: 0, sha256: '' };
      if (stat.mtimeMs === cached.mtimeMs && stat.size === cached.size) return cached.info;
      const sha256 = createHash('sha256').update(fs.readFileSync(apkPath)).digest('hex');
      const info = { available: true, size: stat.size, sha256 };
      cached = { mtimeMs: stat.mtimeMs, size: stat.size, info };
      return info;
    } catch {
      cached = { mtimeMs: -1, size: -1, info: { available: false, size: 0, sha256: '' } };
      return cached.info;
    }
  };
}

export function createDeckServer({ token, desktopDriver, microphoneDriver, fnDriver, leaseMs, tls, micYouDriver, micYouService, audioPort = 8554, meter, systemMonitor, apkPath = path.join(root, '.build', 'android', 'andpan.apk') }) {
  if (typeof token !== 'string' || token.length < 32) throw new Error('缺少连接密钥');
  const controller = new SendController(desktopDriver);
  const microphone = new Microphone(microphoneDriver, fnDriver, leaseMs, meter);
  const system = systemMonitor || new SystemMonitor(desktopDriver);
  const micYou = micYouDriver || micYouMonitor(audioPort, micYouService);
  const inspectApk = createApkInspector(apkPath);
  let lastPhone = '', lastPhoneAt = 0;
  const handler = async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    const send = (status, body) => { res.writeHead(status); res.end(JSON.stringify(body)); };
    try {
      const url = new URL(req.url, 'http://localhost');
      if (!authorized(req.headers.authorization, token)) return send(401, { error: '连接已失效，请在 Mac 重新启动面板' });
      if (req.socket.encrypted) {
        lastPhone = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
        lastPhoneAt = Date.now();
      }
      if (req.method === 'GET' && url.pathname === '/apk/andpan.apk') {
        const apk = inspectApk();
        if (!apk.available) return send(404, { error: 'Mac 上暂无可安装的手机端安装包' });
        res.writeHead(200, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Length': String(apk.size),
          'X-Apk-Sha256': apk.sha256,
        });
        fs.createReadStream(apkPath).pipe(res);
        return;
      }
      if (req.method === 'GET' && (url.pathname === '/status' || url.pathname === '/health')) {
        await Promise.all([system.refresh(), microphone.refresh()]);
        const peer = req.socket.encrypted ? lastPhone : Date.now() - lastPhoneAt < 30000 ? lastPhone :
          (url.pathname === '/status' ? '127.0.0.1' : '');
        let link;
        try { link = await micYou(peer); } catch { link = { state: 'unknown', port: audioPort, service: 'unknown', managed: false, error: '' }; }
        return send(200, { system: system.status(), microphone: microphone.status(), micYou: link,
          apk: inspectApk(),
          phoneConnected: Date.now() - lastPhoneAt < 10000 });
      }
      if (req.method !== 'POST') return send(404, { error: '操作不存在' });
      const body = await readBody(req);
      let result;
      switch (url.pathname) {
        case '/command':
          if (body.action !== 'send') throw problem('操作无效', 400);
          result = await controller.send(body.pid, body.eventId);
          break;
        case '/mic/start': result = await microphone.start({ fn: body.fn !== false }); break;
        case '/mic/hold': result = microphone.hold(); break;
        case '/mic/stop': result = await microphone.stop(); break;
        default: return send(404, { error: '操作不存在' });
      }
      send(200, result);
    } catch (error) {
      send(error.status || 500, { error: error.message || '电脑操作失败' });
    }
  };
  const server = http.createServer(handler);
  const wifiServer = tls ? https.createServer({ ...tls, minVersion: 'TLSv1.2' }, handler) : null;
  return { server, wifiServer, controller, microphone, system, micYouService };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const token = process.env.ANDPAN_TOKEN || '';
  const port = Number(process.env.ANDPAN_PORT || 43125);
  if (!Number.isInteger(port) || port < 1024 || port > 65533) throw new Error('端口无效');
  const audioPort = Number.isInteger(defaultAudioPort) && defaultAudioPort > 0 && defaultAudioPort < 65535 ? defaultAudioPort : 8554;
  const desktopBinary = path.join(root, '.runtime', 'system');
  const microphoneBinary = path.join(root, '.runtime', 'microphone');
  const fnBinary = path.join(root, '.runtime', 'fn-key');
  const meterBinary = path.join(root, '.runtime', 'meter');
  const runtimeMicYou = path.join(root, '.runtime', 'micyou-cli');
  const micYouBinary = process.env.ANDPAN_MICYOU_BIN ||
    (fs.existsSync(runtimeMicYou) ? runtimeMicYou : '/Applications/MicYou.app/Contents/MacOS/micyou-cli');
  const micYouService = new MicYouService({
    binary: micYouBinary,
    port: audioPort,
    device: 'BlackHole 2ch',
    dataDir: path.join(root, '.runtime', 'micyou-data'),
  });
  const audioResult = await micYouService.start();
  if (audioResult.error) console.error(`语音服务提示：${audioResult.error}`);
  const keyPath = path.join(root, '.runtime', 'wifi-key.pem');
  const certPath = path.join(root, '.runtime', 'wifi-cert.pem');
  const tls = fs.existsSync(keyPath) && fs.existsSync(certPath)
    ? { key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath) } : undefined;
  const deck = createDeckServer({
    token, tls, audioPort, micYouService,
    desktopDriver: request => runDriver(desktopBinary, request),
    microphoneDriver: request => runDriver(microphoneBinary, request),
    fnDriver: request => runDriver(fnBinary, request),
    meter: new AudioMeter(meterBinary),
  });
  try { await deck.microphone.stop(); }
  catch (error) { console.error(`麦克风暂不可用：${error.message}`); }
  deck.server.listen(port, '127.0.0.1', () => console.log(`AndPan 正在运行，端口 ${port}`));
  if (deck.wifiServer) {
    deck.wifiServer.listen(port + 1, '0.0.0.0', () => console.log(`AndPan 无线端口 ${port + 1}`));
  }
  const discovery = createDiscoveryServer(port + 1);
  discovery.on('error', error => console.error(`自动查找不可用，可手动设置地址：${error.message}`));
  discovery.bind(port + 2, '0.0.0.0');
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, async () => {
      try { await deck.microphone.stop(); } catch {}
      try { await micYouService.stop(); } catch {}
      try { discovery.close(); } catch {}
      await Promise.all([deck.server, deck.wifiServer].filter(Boolean).map(server =>
        new Promise(resolve => server.close(resolve))));
      process.exit(0);
    });
  }
}
