import { spawn } from 'node:child_process';

const PROTOCOL_VERSION = '2024-11-05';
const STDERR_KEEP = 50;
const STDERR_LINE_MAX = 500;
const MAX_STDOUT_LINE = 8 * 1024 * 1024;

/** Find and parse the first JSON object/array embedded in a text block. */
export function parseJsonLoose(text) {
  if (typeof text !== 'string') return null;
  const start = text.search(/[[{]/);
  if (start < 0) return null;
  const end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
  if (end <= start) return null;
  try {
    const value = JSON.parse(text.slice(start, end + 1));
    return value && typeof value === 'object' ? value : null;
  } catch {
    return null;
  }
}

/**
 * MCP tools/call results are `{content:[{type:'text',text}], isError?}`.
 * Servers here emit markdown first and the raw JSON payload second, so split
 * them: `data` = first parseable object, `raw` = remaining human-readable text.
 */
export function extractToolPayload(result) {
  const texts = Array.isArray(result?.content)
    ? result.content.filter((c) => c?.type === 'text' && typeof c.text === 'string').map((c) => c.text)
    : [];
  if (result?.structuredContent && typeof result.structuredContent === 'object') {
    return { data: result.structuredContent, raw: texts.join('\n\n') };
  }
  let data = null;
  const humans = [];
  for (const text of texts) {
    const parsed = parseJsonLoose(text);
    if (parsed && !data) data = parsed;
    else humans.push(text);
  }
  return { data, raw: humans.join('\n\n') || (data ? JSON.stringify(data) : '') };
}

export class McpStdioClient {
  #child = null;
  #startPromise = null;
  #ready = false;
  #closing = false;
  #nextId = 1;
  #pending = new Map();
  #stderr = [];

  constructor({ command, args = [], name = command, env = {}, requestTimeoutMs = 60000, handshakeTimeoutMs = 20000, log, spawnFn } = {}) {
    this.command = command;
    this.args = args;
    this.name = name;
    this.env = env;
    this.requestTimeoutMs = requestTimeoutMs;
    this.handshakeTimeoutMs = handshakeTimeoutMs;
    this.log = log ?? { debug() {}, info() {}, warn() {}, error() {} };
    // Injectable so the client can be exercised against an in-process server.
    this.spawnFn = spawnFn ?? ((cmd, argv, options) => spawn(process.execPath, [cmd, ...argv], options));
  }

  get ready() {
    return this.#ready;
  }

  stderrTail(n = STDERR_KEEP) {
    return this.#stderr.slice(-Math.max(0, n)).join('\n').slice(-2000);
  }

  async ensureStarted() {
    if (this.#ready) return;
    if (!this.#startPromise) {
      const start = this.#start().finally(() => {
        // Identity check: a stale in-flight start must not clear a newer one.
        if (this.#startPromise === start) this.#startPromise = null;
      });
      this.#startPromise = start;
    }
    return this.#startPromise;
  }

  async #start() {
    this.#closing = false;
    const child = this.spawnFn(this.command, this.args, {
      stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...this.env }, windowsHide: true,
    });
    this.#child = child;

    child.stdout.setEncoding('utf8');
    let buffer = '';
    child.stdout.on('data', (chunk) => {
      buffer += chunk;
      let nl;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl).trim();
        buffer = buffer.slice(nl + 1);
        if (line) this.#onMessage(line);
      }
      if (buffer.length > MAX_STDOUT_LINE) buffer = '';
    });

    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => {
      for (const line of String(chunk).split(/\r?\n/)) {
        if (!line.trim()) continue;
        this.#stderr.push(line.slice(0, STDERR_LINE_MAX));
        if (this.#stderr.length > STDERR_KEEP) this.#stderr.shift();
        this.log.debug('mcp stderr', { node: this.name, line: line.slice(0, STDERR_LINE_MAX) });
      }
    });
    child.on('error', (err) => this.#onExit(err));
    child.on('exit', (code, signal) => this.#onExit(new Error(`mcp process exited (code=${code}, signal=${signal})`)));
    await this.#request('initialize', {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: 'kagami-bridge', version: '1.0.0' },
    }, this.handshakeTimeoutMs);
    this.#write({ jsonrpc: '2.0', method: 'notifications/initialized' });
    this.#ready = true;
    this.log.info('mcp node ready', { node: this.name });
  }

  #onMessage(line) {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      this.log.warn('mcp stdout line is not JSON', { node: this.name, line: line.slice(0, 200) });
      return;
    }
    if (msg && msg.id !== undefined && msg.id !== null && this.#pending.has(msg.id)) {
      const entry = this.#pending.get(msg.id);
      this.#pending.delete(msg.id);
      clearTimeout(entry.timer);
      if (msg.error) entry.reject(Object.assign(new Error(msg.error.message ?? 'mcp error'), { code: 'EMCP', rpcError: msg.error }));
      else entry.resolve(msg.result);
      return;
    }
    if (msg && msg.id !== undefined && msg.id !== null) {
      this.log.warn('unmatched mcp response', { node: this.name, id: msg.id });
      return;
    }
    this.log.debug('mcp notification', { node: this.name, method: msg?.method });
  }

  #write(payload) {
    if (!this.#child || !this.#child.stdin.writable) {
      throw Object.assign(new Error('mcp transport is not writable'), { code: 'EPROC' });
    }
    this.#child.stdin.write(`${JSON.stringify(payload)}\n`);
  }

  #request(method, params, timeoutMs) {
    return new Promise((resolve, reject) => {
      const id = this.#nextId++;
      const timer = setTimeout(() => {
        this.#pending.delete(id);
        reject(Object.assign(new Error(`mcp request timeout after ${timeoutMs}ms: ${method}`), { code: 'ETIMEDOUT' }));
      }, timeoutMs);
      this.#pending.set(id, { resolve, reject, timer });
      try {
        this.#write({ jsonrpc: '2.0', id, method, params });
      } catch (err) {
        clearTimeout(timer);
        this.#pending.delete(id);
        reject(err);
      }
    });
  }

  #onExit(err) {
    this.#ready = false;
    this.#child = null;
    if (this.#closing) return;
    err.code = err.code ?? 'EPROC';
    this.log.warn('mcp node stopped', { node: this.name, error: err.message });
    this.#rejectPending(err);
  }

  #rejectPending(err) {
    for (const [, entry] of this.#pending) {
      clearTimeout(entry.timer);
      entry.reject(err);
    }
    this.#pending.clear();
  }

  async #withRetry(fn) {
    try {
      return await fn();
    } catch (err) {
      if (err?.code !== 'EPROC') throw err;
      this.log.warn('restarting mcp node once', { node: this.name, error: err.message });
      await this.restart();
      return fn();
    }
  }

  async restart() {
    this.#ready = false;
    this.#startPromise = null;
    const child = this.#child;
    this.#child = null;
    if (child && child.exitCode === null) {
      try {
        child.kill();
      } catch {
        /* already gone */
      }
    }
    this.#rejectPending(Object.assign(new Error('mcp node restarted'), { code: 'ECLOSED' }));
  }

  async listTools() {
    return this.#withRetry(async () => {
      await this.ensureStarted();
      const result = await this.#request('tools/list', {}, this.requestTimeoutMs);
      return Array.isArray(result?.tools) ? result.tools : [];
    });
  }

  async callTool(name, args = {}, { timeoutMs = this.requestTimeoutMs } = {}) {
    return this.#withRetry(async () => {
      await this.ensureStarted();
      return this.#request('tools/call', { name, arguments: args ?? {} }, timeoutMs);
    });
  }

  async close() {
    this.#closing = true;
    this.#ready = false;
    this.#startPromise = null;
    this.#rejectPending(Object.assign(new Error('mcp client closed'), { code: 'ECLOSED' }));
    const child = this.#child;
    this.#child = null;
    if (!child || child.exitCode !== null) return;
    await new Promise((resolve) => {
      let done = false;
      const finish = () => { if (!done) { done = true; resolve(); } };
      child.once('exit', finish);
      try { child.kill(); } catch { return finish(); }
      setTimeout(() => {
        try { child.kill('SIGKILL'); } catch { /* already gone */ }
        finish();
      }, 2000).unref?.();
    });
  }
}
