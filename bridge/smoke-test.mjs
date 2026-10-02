#!/usr/bin/env node
/**
 * Live check of the UDP discovery responder plus both MCP stdio nodes:
 * initialize handshake + tools/list. Optional tool calls run afterwards;
 * upstream network failures are reported as WARN (the sandbox has no outbound
 * network) and do not fail the run.
 */
import dgram from 'node:dgram';
import { loadConfig, createLogger } from './src/config.mjs';
import { McpStdioClient, extractToolPayload } from './src/mcp-client.mjs';
import { startDiscoveryResponder, DISCOVERY_MAGIC } from './src/discovery.mjs';
import { VERSION } from './src/routes.mjs';

const config = loadConfig();
const log = createLogger(config.logLevel === 'silent' ? 'silent' : 'info');
let failures = 0;

function check(ok, label, detail = '') {
  if (!ok) failures++;
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? `  — ${detail}` : ''}\n`);
}

function warn(label, detail) {
  process.stdout.write(`WARN  ${label}${detail ? `  — ${detail}` : ''}\n`);
}

function node(name, command, env) {
  const client = new McpStdioClient({ command, name, env, requestTimeoutMs: config.requestTimeoutMs, log });
  return { name, client };
}

async function listTools({ name, client }) {
  process.stdout.write(`\n=== ${name} :: ${commandLabel(name)} ===\n`);
  const startedAt = Date.now();
  let tools;
  try {
    tools = await client.listTools();
  } catch (err) {
    check(false, `${name}: initialize + tools/list`, err.message);
    if (/EPERM|EACCES/.test(err.message)) {
      process.stdout.write('      note: the OS refused to create a child process — this is a sandbox/permission\n            restriction of the current shell, not a bridge protocol error. Run it in a normal terminal.\n');
    }
    const tail = client.stderrTail(10);
    if (tail) process.stdout.write(`      stderr tail:\n${tail.split('\n').map((l) => `      | ${l}`).join('\n')}\n`);
    return null;
  }
  check(true, `${name}: initialize + tools/list`, `${tools.length} tool(s) in ${Date.now() - startedAt}ms`);
  for (const tool of tools) {
    const keys = Object.keys(tool?.inputSchema?.properties ?? {});
    process.stdout.write(`   - ${tool.name}(${keys.join(', ')})\n`);
  }
  return tools;
}

const commandLabel = (name) => (name === 'tracemoe' ? config.tracemoeCmd : config.imgfindCmd);

/** Ask the OS for a free UDP port so a running bridge cannot collide with the check. */
function freeUdpPort() {
  return new Promise((resolve, reject) => {
    const probe = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    probe.once('error', reject);
    probe.bind({ port: 0, address: '127.0.0.1', exclusive: false }, () => {
      const { port } = probe.address();
      probe.close(() => resolve(port));
    });
  });
}

async function checkDiscovery() {
  process.stdout.write(`\n=== discovery :: UDP ${DISCOVERY_MAGIC} ===\n`);
  const port = await freeUdpPort();
  const responder = await startDiscoveryResponder({
    port,
    httpPort: config.port,
    version: VERSION,
    keys: { traceMoe: Boolean(config.traceMoeApiKey), sauceNao: Boolean(config.sauceNaoApiKey) },
    mcp: { tracemoe: true, imgfind: true },
    log,
  });
  const client = dgram.createSocket({ type: 'udp4' });
  try {
    check(responder.active, 'discovery: responder bound', `udp 0.0.0.0:${port}`);
    const reply = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no reply within 3000ms from 127.0.0.1:${port}`)), 3000);
      client.once('message', (msg) => {
        clearTimeout(timer);
        resolve(msg.toString('utf8'));
      });
      client.send(Buffer.from(DISCOVERY_MAGIC, 'utf8'), port, '127.0.0.1', (err) => {
        if (err) {
          clearTimeout(timer);
          reject(err);
        }
      });
    });
    process.stdout.write(`   reply: ${reply}\n`);
    const parsed = JSON.parse(reply);
    check(parsed?.service === 'amps-bridge', 'discovery: reply service', String(parsed?.service));
    check(parsed?.protocol === 1 && parsed?.port === config.port, 'discovery: reply protocol/port', `protocol=${parsed?.protocol} port=${parsed?.port}`);
    const url = new URL(String(parsed?.http));
    const httpPort = Number(url.port || 80);
    check(url.protocol === 'http:' && httpPort === config.port, 'discovery: reply http url', String(parsed?.http));
    check(Array.isArray(parsed?.ips) && parsed.ips.includes(url.hostname), 'discovery: advertised host is one of our own ips', `ips=${JSON.stringify(parsed?.ips)}`);
  } catch (err) {
    check(false, 'discovery: AMPS_DISCOVER_V1 round trip', err.message);
  } finally {
    await responder.close();
    await new Promise((resolve) => client.close(() => resolve()));
  }
}

async function call(client, toolName, args = {}) {
  const result = await client.callTool(toolName, args);
  const { data, raw } = extractToolPayload(result);
  return { result, data, raw };
}

async function main() {
  process.stdout.write(`amps-bridge smoke test (node ${process.version})\n`);
  process.stdout.write(`trace.moe key: ${config.traceMoeApiKey ? 'configured' : 'not configured'}\n`);
  process.stdout.write(`SauceNAO  key: ${config.sauceNaoApiKey ? 'configured' : 'not configured'}\n`);
  process.stdout.write(`discovery port: ${config.discoveryPort === 0 ? 'disabled' : config.discoveryPort}\n`);

  await checkDiscovery();

  const tracemoe = node('tracemoe', config.tracemoeCmd, config.traceMoeApiKey ? { TRACE_MOE_API_KEY: config.traceMoeApiKey } : {});
  const imgfind = node('imgfind', config.imgfindCmd, {
    ...(config.traceMoeApiKey ? { TRACE_MOE_API_KEY: config.traceMoeApiKey } : {}),
    ...(config.sauceNaoApiKey ? { SAUCENAO_API_KEY: config.sauceNaoApiKey } : {}),
  });

  try {
    const traceTools = await listTools(tracemoe);
    const sauceTools = await listTools(imgfind);

    if (traceTools?.some((t) => t.name === 'search_anime_by_image_file')) {
      if (!config.traceMoeApiKey) {
        warn('tracemoe get_account_quota skipped', 'TRACE_MOE_API_KEY not configured');
      } else {
        try {
          const { raw } = await call(tracemoe.client, 'get_account_quota');
          warn('tracemoe get_account_quota', raw.split('\n')[0] || 'no text');
        } catch (err) {
          warn('tracemoe get_account_quota failed (upstream/network)', err.message);
        }
      }
    }

    if (sauceTools?.some((t) => t.name === 'config_status')) {
      try {
        const { raw } = await call(imgfind.client, 'config_status');
        process.stdout.write(`\nimgfind config_status:\n${raw.split('\n').map((l) => `   ${l}`).join('\n')}\n`);
        check(true, 'imgfind: config_status');
      } catch (err) {
        check(false, 'imgfind: config_status', err.message);
      }
    }
  } finally {
    await Promise.allSettled([tracemoe.client.close(), imgfind.client.close()]);
  }

  process.stdout.write(`\n${failures === 0 ? 'SMOKE OK' : `SMOKE FAILED (${failures} check(s))`}\n`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  process.stderr.write(`\nSMOKE FAILED: ${err?.stack ?? err}\n`);
  process.exit(1);
});
