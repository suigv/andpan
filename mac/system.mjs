import os from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute = promisify(execFile);

export function parseMemory(output) {
  const match = output.match(/System-wide memory free percentage:\s*(\d+)%/);
  return match ? Math.max(0, Math.min(100, 100 - Number(match[1]))) : null;
}

export function parseProcesses(output) {
  return output.split('\n').map(line => {
    const match = line.match(/^\s*([\d.]+)\s+(\d+)\s+(.+?)\s*$/);
    return match ? { name: match[3].slice(0, 80), cpu: Number(match[1]), memoryMB: Math.round(Number(match[2]) / 1024) } : null;
  }).filter(Boolean).sort((a, b) => b.cpu - a.cpu).slice(0, 3);
}

function cpuTimes() {
  return os.cpus().reduce((sum, core) => {
    sum.idle += core.times.idle;
    sum.total += Object.values(core.times).reduce((a, b) => a + b, 0);
    return sum;
  }, { idle: 0, total: 0 });
}

export class SystemMonitor {
  constructor(desktopDriver) {
    this.driver = desktopDriver;
    this.previous = cpuTimes();
    this.stats = { cpu: null, memory: null, processes: [] };
    this.target = { name: '', pid: 0 };
    this.input = '';
    this.accessibility = null;
    this.checkedAt = 0;
    this.pending = null;
  }

  async refresh() {
    if (this.pending) return this.pending;
    if (Date.now() - this.checkedAt < 1800) return;
    this.pending = (async () => {
      const now = cpuTimes();
      const total = now.total - this.previous.total;
      if (total > 0) this.stats.cpu = Math.round(100 * (1 - (now.idle - this.previous.idle) / total));
      this.previous = now;
      const options = { timeout: 2500, maxBuffer: 512000, env: { ...process.env, LC_ALL: 'C' } };
      const [desktop, memory, processes] = await Promise.allSettled([
        this.driver({ action: 'status' }),
        execute('/usr/bin/memory_pressure', ['-Q'], options),
        execute('/bin/ps', ['-Ac', '-o', '%cpu=', '-o', 'rss=', '-o', 'comm=', '-r'], options),
      ]);
      if (desktop.status === 'fulfilled') {
        const data = desktop.value;
        this.input = typeof data.input === 'string' ? data.input.slice(0, 100) : '';
        this.accessibility = typeof data.accessibility === 'boolean' ? data.accessibility : null;
        this.target = Number.isInteger(data.target?.pid) && data.target.pid > 0
          ? { name: String(data.target.name || '').slice(0, 100), pid: data.target.pid }
          : { name: '', pid: 0 };
      } else {
        this.input = '';
        this.accessibility = null;
        this.target = { name: '', pid: 0 };
      }
      this.stats.memory = memory.status === 'fulfilled' ? parseMemory(memory.value.stdout) : null;
      this.stats.processes = processes.status === 'fulfilled' ? parseProcesses(processes.value.stdout) : [];
      this.checkedAt = Date.now();
    })();
    try { await this.pending; } finally { this.pending = null; }
  }

  status() {
    return { ...this.stats, input: this.input, target: this.target, accessibility: this.accessibility };
  }
}
