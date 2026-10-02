import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const BRIDGE_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const DEFAULTS = {
  PORT: 8787,
  DISCOVERY_PORT: 8788,
  HOST: '0.0.0.0',
  MCP_TRACEMOE_CMD: 'C:\\Users\\Miha2003\\.dsh\\mcp\\trace-moe-mcp\\node_modules\\trace.moe-mcp\\dist\\index.js',
  MCP_IMGFIND_CMD: 'C:\\Users\\Miha2003\\.dsh\\mcp\\imgfind-mcp\\index.js',
  LOG_LEVEL: 'info',
  REQUEST_TIMEOUT_MS: 60000,
};

/** Minimal dotenv reader: KEY=VALUE, optional `export `, optional quotes, `#` comments. */
export function parseEnvFile(text) {
  const out = {};
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const eq = t.indexOf('=');
    if (eq < 1) continue;
    const key = t.slice(0, eq).trim().replace(/^export\s+/, '');
    let value = t.slice(eq + 1).trim();
    if (value.length > 1 && ((value[0] === '"' && value.endsWith('"')) || (value[0] === "'" && value.endsWith("'")))) {
      value = value.slice(1, -1);
    }
    if (key) out[key] = value;
  }
  return out;
}

/** `--key=value` and `--key value`; keys are upper-cased with `-` -> `_`. */
export function parseArgv(argv = process.argv.slice(2)) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const eq = arg.indexOf('=');
    let key;
    let value;
    if (eq > 0) { key = arg.slice(2, eq); value = arg.slice(eq + 1); }
    else {
      key = arg.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith('--')) { value = next; i++; } else value = 'true';
    }
    if (key) out[key.toUpperCase().replace(/-/g, '_')] = value;
  }
  return out;
}

export function loadConfig({ argv, env = process.env, bridgeDir = BRIDGE_DIR } = {}) {
  let fileEnv = {};
  try {
    fileEnv = parseEnvFile(fs.readFileSync(path.join(bridgeDir, '.env'), 'utf8'));
  } catch {
    /* .env is optional */
  }
  const cli = parseArgv(argv);
  const pick = (key) => [cli[key], env[key], fileEnv[key]].find((v) => v !== undefined && v !== '');
  const str = (key) => String(pick(key) ?? DEFAULTS[key] ?? '');
  const int = (key) => {
    const n = Number(pick(key));
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : DEFAULTS[key];
  };
  const uint16 = (key) => {
    const raw = pick(key);
    if (raw === undefined) return DEFAULTS[key];
    const n = Number(raw);
    return Number.isInteger(n) && n >= 0 && n <= 65535 ? n : DEFAULTS[key];
  };

  return Object.freeze({
    port: int('PORT'),
    discoveryPort: uint16('DISCOVERY_PORT'),
    host: str('HOST'),
    traceMoeApiKey: String(pick('TRACE_MOE_API_KEY') ?? ''),
    sauceNaoApiKey: String(pick('SAUCENAO_API_KEY') ?? ''),
    tracemoeCmd: str('MCP_TRACEMOE_CMD'),
    imgfindCmd: str('MCP_IMGFIND_CMD'),
    logLevel: str('LOG_LEVEL'),
    requestTimeoutMs: int('REQUEST_TIMEOUT_MS'),
  });
}

/** Safe-to-print view: booleans only, never key material. */
export function describeConfig(cfg) {
  return {
    port: cfg.port,
    discoveryPort: cfg.discoveryPort,
    host: cfg.host,
    keys: { traceMoe: Boolean(cfg.traceMoeApiKey), sauceNao: Boolean(cfg.sauceNaoApiKey) },
    logLevel: cfg.logLevel,
    requestTimeoutMs: cfg.requestTimeoutMs,
    tracemoeCmd: cfg.tracemoeCmd,
    imgfindCmd: cfg.imgfindCmd,
  };
}

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40, silent: 99 };

export function createLogger(level = 'info') {
  const min = LEVELS[String(level).toLowerCase()] ?? LEVELS.info;
  const emit = (lvl, msg, fields = {}) => {
    if (LEVELS[lvl] < min) return;
    const line = JSON.stringify({ ts: new Date().toISOString(), lvl, msg, ...fields });
    (lvl === 'error' || lvl === 'warn' ? process.stderr : process.stdout).write(`${line}\n`);
  };
  return {
    debug: (m, f) => emit('debug', m, f),
    info: (m, f) => emit('info', m, f),
    warn: (m, f) => emit('warn', m, f),
    error: (m, f) => emit('error', m, f),
  };
}
