import http from 'node:http';
import os from 'node:os';
import { loadConfig, createLogger, describeConfig } from './config.mjs';
import { McpStdioClient } from './mcp-client.mjs';
import { createRouter, VERSION } from './routes.mjs';

const MAX_BODY_BYTES = 25 * 1024 * 1024;

const CORS_HEADERS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'content-type, x-filename, x-cut-borders, x-anilist-id',
  'access-control-max-age': '86400',
};

const config = loadConfig();
const log = createLogger(config.logLevel);

const tooLarge = () => Object.assign(new Error('payload_too_large'), { status: 413 });

function readBody(req) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length'] ?? 0);
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) return reject(tooLarge());
    const chunks = [];
    let size = 0;
    let settled = false;
    const fail = (err) => {
      if (settled) return;
      settled = true;
      req.destroy();
      reject(err);
    };
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) return fail(tooLarge());
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (settled) return;
      settled = true;
      resolve(Buffer.concat(chunks));
    });
    req.on('error', fail);
    req.on('aborted', () => fail(Object.assign(new Error('client_aborted'), { status: 400 })));
  });
}

function sendJson(res, status, json) {
  const payload = JSON.stringify(json);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
    'cache-control': 'no-store',
    ...CORS_HEADERS,
  });
  res.end(payload);
}

function lanAddresses() {
  const out = [];
  for (const [name, addresses] of Object.entries(os.networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family === 'IPv4' && !address.internal) out.push({ name, address: address.address });
    }
  }
  return out;
}

function printBanner(base) {
  const view = describeConfig(config);
  const lan = lanAddresses();
  const target = lan[0] ? `http://${lan[0].address}:${config.port}` : base;
  const lines = [
    '',
    `  Kagami Bridge v${VERSION}`,
    `  listening on   http://${config.host}:${config.port}`,
    `  keys           trace.moe: ${view.keys.traceMoe ? 'configured' : 'NOT configured'} | SauceNAO: ${view.keys.sauceNao ? 'configured' : 'NOT configured'}`,
    ...(lan.length === 0 ? ['  LAN            no external IPv4 interface found (is Wi-Fi on?)'] : []),
    ...lan.map(({ name, address }) => `  LAN            http://${address}:${config.port}   (${name})`),
    '',
    '  copy-paste checks:',
    `    curl ${target}/api/health`,
    `    curl "${target}/api/anime/search?q=One+Piece"`,
    `    curl -X POST --data-binary @frame.jpg -H "x-filename: frame.jpg" ${target}/api/frame/lookup`,
    `    curl -X POST --data-binary @frame.jpg ${target}/api/frame/identify`,
    '',
    '  On Android: same Wi-Fi, then enter that http://<LAN-IP>:<port> URL. Ctrl+C to stop.',
    '',
  ];
  process.stdout.write(`${lines.join('\n')}\n`);
}

const tracemoe = new McpStdioClient({
  command: config.tracemoeCmd,
  name: 'tracemoe',
  env: config.traceMoeApiKey ? { TRACE_MOE_API_KEY: config.traceMoeApiKey } : {},
  requestTimeoutMs: config.requestTimeoutMs,
  log,
});

const imgfind = new McpStdioClient({
  command: config.imgfindCmd,
  name: 'imgfind',
  env: {
    ...(config.traceMoeApiKey ? { TRACE_MOE_API_KEY: config.traceMoeApiKey } : {}),
    ...(config.sauceNaoApiKey ? { SAUCENAO_API_KEY: config.sauceNaoApiKey } : {}),
  },
  requestTimeoutMs: config.requestTimeoutMs,
  log,
});

const dispatch = createRouter({
  tracemoe,
  imgfind,
  log,
  traceMoeKey: config.traceMoeApiKey,
  sauceNaoKey: config.sauceNaoApiKey,
  requestTimeoutMs: config.requestTimeoutMs,
});

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  if (req.method === 'OPTIONS') {
    res.writeHead(204, CORS_HEADERS);
    res.end();
    return;
  }
  try {
    const body = req.method === 'POST' ? await readBody(req) : Buffer.alloc(0);
    const { status, json } = await dispatch({
      method: req.method ?? 'GET',
      pathname: url.pathname.replace(/\/+$/, '') || '/',
      query: url.searchParams,
      headers: req.headers,
      body,
    });
    sendJson(res, status, json);
  } catch (err) {
    const tooLarge = Number(err?.status) === 413;
    log.error('request aborted', { path: url.pathname, error: String(err?.message ?? err) });
    const json = tooLarge
      ? { error: 'bad_request', message: 'payload_too_large: request body exceeds 25 MB' }
      : { error: 'internal_error', message: String(err?.message ?? 'internal error').slice(0, 200) };
    if (!res.headersSent) sendJson(res, tooLarge ? 400 : 500, json);
    else res.end();
  }
});

server.on('error', (err) => {
  log.error('http server error', { error: err.message, code: err.code });
  if (err.code === 'EADDRINUSE') {
    process.stderr.write(`\n  port ${config.port} is already in use — start with: node src/server.mjs --port ${config.port + 1}\n\n`);
  }
  process.exit(1);
});

server.listen(config.port, config.host, () => {
  printBanner(`http://${config.host}:${config.port}`);
  log.info('bridge started', { version: VERSION, port: config.port, host: config.host, ...describeConfig(config) });
});

let shuttingDown = false;
async function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  log.info('shutting down', { signal });
  server.close();
  const force = setTimeout(() => process.exit(0), 3000);
  force.unref?.();
  await Promise.allSettled([tracemoe.close(), imgfind.close()]);
  process.exit(0);
}

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => void shutdown(signal));
process.on('unhandledRejection', (reason) => log.error('unhandled rejection', { error: String(reason?.message ?? reason) }));
