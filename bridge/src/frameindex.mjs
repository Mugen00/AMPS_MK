/**
 * Perceptual-hash frame index — trace.moe only knows the anime it has indexed,
 * so every known frame is also stored here as a 64-bit dHash and matched on
 * device. Same perceptual hash as the phone (`src/phash.mjs`), Hamming
 * distance <= 10 counts as a match.
 *
 * Storage: `bridge/.cache/frame-index.json`, one object
 * `{ version, builtAt, entries }`, entries sorted by hash so a lookup can stop
 * as soon as the remaining candidates cannot be close enough.
 *
 * Everything that cannot be decoded is rejected rather than hashed: a wrong
 * hash in this file is a wrong identification in the app.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { hashFrame, bitsToHex, hexToBits, DEFAULT_MAX_DISTANCE, ImageDecodeError } from './phash.mjs';

export { hashFrame };

const BRIDGE_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Where the index lives; `loadIndex`/`saveIndex` accept an override for tests. */
export const INDEX_PATH = path.join(BRIDGE_DIR, '.cache', 'frame-index.json');

/** Storage schema version, bumped when the entry shape changes. */
export const INDEX_VERSION = 1;

/** Match tolerance handed back in `indexStats` for the app to display. */
export const MAX_DISTANCE = DEFAULT_MAX_DISTANCE;

const HASH_PATTERN = /^[0-9a-fA-F]{16}$/;

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const log = (deps, level, message, fields) => deps?.log?.[level]?.(message, fields);

/** In-process cache so a request does not re-read the file every time. */
const cache = new Map();

/** Hamming distance between two 64-bit hashes given as 16 hex characters. */
export function hammingDistance(a, b) {
  let x = hexToBits(a) ^ hexToBits(b);
  let distance = 0;
  while (x > 0n) {
    x &= x - 1n;
    distance += 1;
  }
  return distance;
}

const emptyIndex = () => ({ version: INDEX_VERSION, builtAt: new Date().toISOString(), entries: [] });

function normaliseEntry(input = {}) {
  const hash = String(input.hash ?? '').trim().toLowerCase();
  if (!HASH_PATTERN.test(hash)) throw new Error(`malformed hash: ${JSON.stringify(String(input.hash ?? '')).slice(0, 40)}`);
  const anilistId = Number(input.anilistId);
  if (!Number.isInteger(anilistId) || anilistId <= 0) throw new Error(`anilistId must be a positive integer, got ${JSON.stringify(input.anilistId)}`);
  const seriesTitle = String(input.seriesTitle ?? '').trim();
  if (!seriesTitle) throw new Error('seriesTitle is required');
  const source = String(input.source ?? '').trim();
  if (!source) throw new Error('source is required — provenance is never lost');
  const episodeRaw = input.episode === null || input.episode === undefined || input.episode === '' ? null : Number(input.episode);
  if (episodeRaw !== null && (!Number.isInteger(episodeRaw) || episodeRaw < 0)) throw new Error(`episode must be a non-negative integer or null, got ${JSON.stringify(input.episode)}`);
  const timestampRaw = input.timestampSec === null || input.timestampSec === undefined || input.timestampSec === '' ? null : Number(input.timestampSec);
  if (timestampRaw !== null && (!Number.isFinite(timestampRaw) || timestampRaw < 0)) throw new Error(`timestampSec must be a number >= 0 or null, got ${JSON.stringify(input.timestampSec)}`);
  const imageUrl = input.imageUrl === null || input.imageUrl === undefined ? null : String(input.imageUrl);
  if (imageUrl && !/^https?:\/\//i.test(imageUrl)) throw new Error(`imageUrl must be an http(s) URL, got ${JSON.stringify(input.imageUrl).slice(0, 80)}`);
  const width = Number(input.width);
  const height = Number(input.height);

  return {
    hash,
    anilistId,
    seriesTitle,
    episode: episodeRaw,
    timestampSec: timestampRaw === null ? null : Math.round(timestampRaw * 1000) / 1000,
    source,
    imageUrl: imageUrl || null,
    width: Number.isInteger(width) && width > 0 ? width : null,
    height: Number.isInteger(height) && height > 0 ? height : null,
    addedAt: input.addedAt ? new Date(input.addedAt).toISOString() : new Date().toISOString(),
  };
}

function compareHash(a, b) {
  const x = hexToBits(a);
  const y = hexToBits(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

/**
 * Load the index (lazily, from disk, then from the in-process cache).
 * @param {{file?: string, force?: boolean}} [options]
 */
export function loadIndex(options = {}) {
  const file = options.file ? path.resolve(options.file) : INDEX_PATH;
  if (!options.force && cache.has(file)) return cache.get(file);
  let index;
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    const rawEntries = Array.isArray(parsed?.entries) ? parsed.entries : [];
    // One broken entry must not throw the whole index away.
    let dropped = 0;
    const entries = [];
    for (const row of rawEntries) {
      try {
        entries.push(normaliseEntry(row));
      } catch {
        dropped += 1;
      }
    }
    if (dropped > 0) {
      process.stderr.write(`${JSON.stringify({ ts: new Date().toISOString(), lvl: 'warn', msg: 'frame index entries skipped', file, dropped })}\n`);
    }
    entries.sort((a, b) => compareHash(a.hash, b.hash));
    index = { version: Number(parsed?.version) || INDEX_VERSION, builtAt: parsed?.builtAt ?? new Date().toISOString(), entries };
  } catch (err) {
    if (err?.code !== 'ENOENT') {
      // A corrupt index must not take the bridge down: start over, loudly.
      process.stderr.write(`${JSON.stringify({ ts: new Date().toISOString(), lvl: 'warn', msg: 'frame index unreadable, starting empty', file, error: String(err?.message ?? err) })}\n`);
    }
    index = emptyIndex();
  }
  cache.set(file, index);
  return index;
}

/** Counters for the app's "how much do we know" screen. */
export function indexStats(index) {
  const entries = Array.isArray(index?.entries) ? index.entries : [];
  const series = new Set();
  const episodes = new Set();
  const sources = new Map();
  for (const entry of entries) {
    series.add(entry.seriesTitle);
    episodes.add(`${entry.anilistId}#${entry.episode ?? '?'}`);
    sources.set(entry.source, (sources.get(entry.source) ?? 0) + 1);
  }
  return {
    count: entries.length,
    series: series.size,
    episodes: episodes.size,
    sources: [...sources.keys()].sort(),
    builtAt: index?.builtAt ?? null,
    maxDistance: MAX_DISTANCE,
    // Detail for the app; `series` / `episodes` above stay plain counts.
    seriesList: [...series].sort(),
    sourceCounts: Object.fromEntries([...sources.entries()].sort()),
  };
}

/**
 * Nearest entry to `hash` within `maxDistance` bits.
 *
 * Entries are sorted, and two hashes at Hamming distance <= d must agree on
 * their leading 64 - d bits — so as soon as the sorted order passes that
 * prefix, nothing further can match and the scan stops.
 *
 * @returns {{match: object|null, distance: number|null, checked: number}}
 */
export function lookup(index, hash, maxDistance = MAX_DISTANCE) {
  const target = hexToBits(hash);
  const tolerance = Number.isInteger(maxDistance) && maxDistance >= 0 ? Math.min(64, maxDistance) : MAX_DISTANCE;
  const entries = Array.isArray(index?.entries) ? index.entries : [];
  const shift = BigInt(tolerance);
  const prefix = target >> shift;
  let checked = 0;
  let match = null;
  let best = tolerance + 1;

  for (const entry of entries) {
    const value = hexToBits(entry.hash);
    if ((value >> shift) > prefix) break; // sorted: nothing closer can follow
    checked += 1;
    const distance = hammingDistance(entry.hash, hash);
    if (distance <= tolerance && distance < best) {
      best = distance;
      match = entry;
    }
  }
  return { match, distance: match ? best : null, checked };
}

/**
 * Add (or replace) an entry, keeping the entries sorted by hash.
 * The newest entry for a given hash wins — that is what makes the endpoint a
 * correction tool as well as a collector.
 */
export function addEntry(index, entry) {
  const normalised = normaliseEntry(entry);
  const target = index && Array.isArray(index.entries) ? index : emptyIndex();
  const existing = target.entries.findIndex((e) => e.hash === normalised.hash);
  if (existing >= 0) target.entries[existing] = normalised;
  else target.entries.push(normalised);
  target.entries.sort((a, b) => compareHash(a.hash, b.hash));
  target.version = INDEX_VERSION;
  target.builtAt = new Date().toISOString();
  return target;
}

/** Atomic write: temp file in the same directory, then rename over the target. */
export function saveIndex(index, options = {}) {
  const file = options.file ? path.resolve(options.file) : INDEX_PATH;
  const entries = Array.isArray(index?.entries) ? [...index.entries] : [];
  entries.sort((a, b) => compareHash(a.hash, b.hash));
  const payload = { version: INDEX_VERSION, builtAt: index?.builtAt ?? new Date().toISOString(), entries };
  const dir = path.dirname(file);
  fs.mkdirSync(dir, { recursive: true });
  const temp = path.join(dir, `.${path.basename(file)}.${process.pid}.${Date.now()}.tmp`);
  fs.writeFileSync(temp, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
  try {
    fs.renameSync(temp, file);
  } catch (err) {
    fs.rmSync(temp, { force: true });
    throw err;
  }
  cache.set(file, { version: payload.version, builtAt: payload.builtAt, entries });
  return file;
}

/** Forget the cached copy (used by tests and after a rebuild). */
export function resetIndexCache(file) {
  if (file) cache.delete(path.resolve(file));
  else cache.clear();
}

/** Read one parameter from a URLSearchParams, a Map, or a plain object. */
function readParam(query, name) {
  if (!query) return null;
  if (typeof query.get === 'function') return query.get(name);
  if (query instanceof Map) return query.get(name);
  return query[name] ?? null;
}

/** Parse a JSON request body that may arrive as a Buffer, a string or an object. */
function parseBody(body) {
  if (body === null || body === undefined) return {};
  if (Buffer.isBuffer(body)) {
    if (body.length === 0) return {};
    return JSON.parse(body.toString('utf8'));
  }
  if (typeof body === 'string') return body.trim() === '' ? {} : JSON.parse(body);
  return body;
}

const badRequest = (message) => ({ status: 400, json: { error: 'bad_request', message } });

/**
 * The four index endpoints and nothing else.
 *
 * | Path | Meaning |
 * |------|---------|
 * | `GET  /api/frame/index/stats`  | counters + `maxDistance` |
 * | `GET  /api/frame/index/match?hash=<16 hex>` | nearest entry, 400 on a bad hash |
 * | `POST /api/frame/index/add`   | body = entry, de-duplicated by hash, saved |
 * | `POST /api/frame/index/rebuild` | clears the index |
 *
 * `query` may be a `URLSearchParams`, a `Map` or a plain object; `body` may be
 * a `Buffer`, a JSON string or an already parsed object.
 *
 * @returns {{status: number, json: object}}
 */
export function handleIndexRequest(pathname, query, body, deps = {}) {
  const route = String(pathname ?? '').replace(/\/+$/, '');

  if (route === '/api/frame/index/stats') {
    const index = loadIndex({ file: deps.indexFile });
    return { status: 200, json: { ok: true, ...indexStats(index) } };
  }

  if (route === '/api/frame/index/match') {
    const raw = String(readParam(query, 'hash') ?? '').trim();
    if (!HASH_PATTERN.test(raw)) return badRequest(`hash must be 16 hex characters, got ${JSON.stringify(raw).slice(0, 40)}`);
    const index = loadIndex({ file: deps.indexFile });
    const result = lookup(index, raw.toLowerCase(), MAX_DISTANCE);
    return {
      status: 200,
      json: {
        ok: true,
        hash: raw.toLowerCase(),
        matched: Boolean(result.match),
        distance: result.distance,
        checked: result.checked,
        maxDistance: MAX_DISTANCE,
        entry: result.match,
      },
    };
  }

  if (route === '/api/frame/index/add') {
    let parsed;
    try {
      parsed = parseBody(body);
    } catch (err) {
      return badRequest(`body is not valid JSON: ${String(err?.message ?? err).slice(0, 120)}`);
    }
    if (!isObject(parsed)) return badRequest('body must be a JSON object with the entry fields');
    let entry;
    try {
      entry = normaliseEntry(parsed);
    } catch (err) {
      return badRequest(String(err?.message ?? err));
    }
    const index = loadIndex({ file: deps.indexFile });
    addEntry(index, entry);
    saveIndex(index, { file: deps.indexFile });
    const stats = indexStats(index);
    log(deps, 'info', 'frame index entry added', { hash: entry.hash, source: entry.source, anilistId: entry.anilistId, count: stats.count });
    return { status: 200, json: { ok: true, count: stats.count, added: true, entry, stats } };
  }

  if (route === '/api/frame/index/rebuild') {
    const index = emptyIndex();
    saveIndex(index, { file: deps.indexFile });
    log(deps, 'info', 'frame index rebuilt', { count: 0 });
    return { status: 200, json: { ok: true, count: 0, cleared: true, stats: indexStats(index) } };
  }

  return { status: 404, json: { error: 'not_found' } };
}

export { bitsToHex, hexToBits, ImageDecodeError, MAX_DISTANCE as DEFAULT_MAX_DISTANCE };