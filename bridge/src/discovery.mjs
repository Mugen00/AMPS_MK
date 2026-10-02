import dgram from 'node:dgram';
import os from 'node:os';

export const DISCOVERY_MAGIC = 'AMPS_DISCOVER_V1';
export const DISCOVERY_PROTOCOL = 1;
export const DISCOVERY_SERVICE = 'amps-bridge';

// Windows answers a bind to an already owned UDP port with EACCES, POSIX with EADDRINUSE.
const PORT_BUSY = new Set(['EADDRINUSE', 'EACCES']);

/** `::ffff:192.168.0.5` → `192.168.0.5`; anything else passes through. */
export function normalizeAddress(address) {
  const value = String(address ?? '').trim();
  const mapped = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/i.exec(value);
  return mapped ? mapped[1] : value;
}

const IPV4 = /^\d{1,3}(?:\.\d{1,3}){3}$/;

function toInt(ip) {
  return ip.split('.').reduce((acc, octet) => (acc * 256) + Number(octet), 0);
}

function sameSubnet(left, right, netmask) {
  if (!IPV4.test(left) || !IPV4.test(right) || !IPV4.test(netmask)) return false;
  return (toInt(left) & toInt(netmask)) === (toInt(right) & toInt(netmask));
}

/** Every non-internal IPv4 of this machine, as `{ name, address, netmask }`. */
export function localAddresses() {
  const out = [];
  for (const [name, addresses] of Object.entries(os.networkInterfaces())) {
    for (const entry of addresses ?? []) {
      if (entry.family === 'IPv4' && !entry.internal) out.push({ name, address: entry.address, netmask: entry.netmask });
    }
  }
  return out;
}

/**
 * Which of our own addresses the sender can reach. The datagram's source is the
 * phone, never the bridge, so its address is only useful for picking the NIC:
 * on a multi-homed PC the interface in the sender's subnet is the one that
 * answers, the rest are the fallback.
 */
export function pickLocalAddress(remote) {
  const local = localAddresses();
  const target = normalizeAddress(remote);
  const inSubnet = local.find((entry) => sameSubnet(entry.address, target, entry.netmask));
  return (inSubnet ?? local[0])?.address || '127.0.0.1';
}

/**
 * Pure reply builder. `remote` is the sender's address; the advertised URL is
 * always one of this machine's own addresses, never the phone's.
 */
export function buildDiscoveryReply({ remote, httpPort, version, host, keys, mcp } = {}) {
  const local = localAddresses();
  const advertised = pickLocalAddress(remote);
  const reply = {
    service: DISCOVERY_SERVICE,
    protocol: DISCOVERY_PROTOCOL,
    version: String(version ?? ''),
    http: `http://${advertised}:${httpPort}`,
    port: Number(httpPort),
    host: String(host ?? ''),
    ips: local.map((entry) => entry.address),
    keys: { traceMoe: Boolean(keys?.traceMoe), sauceNao: Boolean(keys?.sauceNao) },
  };
  // Only reported once a node has actually been probed: an unknown state is not
  // the same as a failed one and must not be rendered as "MCP not ready".
  if (mcp && typeof mcp === 'object') {
    const known = {};
    if (mcp.tracemoe !== undefined && mcp.tracemoe !== null) known.tracemoe = Boolean(mcp.tracemoe);
    if (mcp.imgfind !== undefined && mcp.imgfind !== null) known.imgfind = Boolean(mcp.imgfind);
    if (Object.keys(known).length > 0) reply.mcp = known;
  }
  return reply;
}

function closeSocket(socket) {
  return new Promise((resolve) => {
    try {
      if (!socket.address()) return resolve();
    } catch {
      // The socket was already closed by the error handler.
      return resolve();
    }
    socket.close(() => resolve());
    return undefined;
  });
}

/**
 * Starts the UDP responder. Never rejects and never throws into the HTTP
 * server: a busy port only means `active: false`.
 *
 * `mcp` may be a plain state object or a getter — it is read once per request
 * and must stay synchronous, the socket callback has no time to wait.
 *
 * @returns {Promise<{port: number|null, active: boolean, close: () => Promise<void>}>}
 */
export function startDiscoveryResponder({ port, httpPort, version, keys, mcp, host = os.hostname(), log } = {}) {
  const resolved = Number(port ?? 0);
  if (!Number.isInteger(resolved) || resolved <= 0 || resolved > 65535) {
    return Promise.resolve({ port: null, active: false, close: async () => {} });
  }

  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

  return new Promise((resolve) => {
    const state = {
      port: resolved,
      active: false,
      close: () => {
        state.active = false;
        return closeSocket(socket);
      },
    };
    let settled = false;
    const settle = (active) => {
      state.active = active;
      if (settled) return;
      settled = true;
      resolve(state);
    };

    socket.on('error', (err) => {
      const busy = PORT_BUSY.has(err?.code);
      log?.warn?.('discovery socket error', { port: resolved, code: err?.code ?? null, error: String(err?.message ?? err) });
      if (busy) {
        process.stderr.write(
          `\n  UDP-порт ${resolved} уже занят — автопоиск отключён, HTTP-мост продолжает работать.\n`
          + `  Запустите с другим портом: node src\\server.mjs --discovery-port ${resolved + 1}\n\n`,
        );
      }
      try {
        socket.close();
      } catch {
        /* already closed */
      }
      settle(false);
    });

    socket.on('message', (msg, rinfo) => {
      if (msg.toString('utf8').trim().toUpperCase() !== DISCOVERY_MAGIC) {
        log?.debug?.('discovery packet ignored', { from: rinfo.address, bytes: msg.length });
        return;
      }
      const reply = buildDiscoveryReply({
        remote: rinfo.address,
        httpPort,
        version,
        host,
        keys,
        mcp: typeof mcp === 'function' ? mcp() : mcp,
      });
      socket.send(Buffer.from(JSON.stringify(reply), 'utf8'), rinfo.port, normalizeAddress(rinfo.address), (err) => {
        if (err) log?.debug?.('discovery reply failed', { to: rinfo.address, error: String(err?.message ?? err) });
      });
    });

    socket.on('listening', () => {
      try {
        socket.setBroadcast(true);
      } catch (err) {
        log?.warn?.('discovery broadcast unavailable', { error: String(err?.message ?? err) });
      }
      log?.info?.('discovery responder ready', { port: resolved, magic: DISCOVERY_MAGIC, httpPort: Number(httpPort) });
      settle(true);
    });

    socket.bind({ port: resolved, address: '0.0.0.0', exclusive: false });
  });
}
