#!/usr/bin/env node
/**
 * Frame-index harvester.
 *
 *   node tools/build-frame-index.mjs [--limit N] [--source <name>] [--out <file>]
 *                                   [--dry-run] [--live] [--timeout <ms>]
 *
 * Every adapter is a named async function returning candidate frames, and every
 * candidate carries the `source` it came from, so provenance is never lost.
 * Both shipped adapters check the licence of a file BEFORE anything is
 * downloaded and skip everything that is not explicitly free: copyrighted anime
 * frames are never scraped. On a machine with no outbound network the tool says
 * so, prints the plan and exits 0, which is what makes it testable offline.
 */

import path from 'node:path';
import { parseArgv, createLogger } from '../src/config.mjs';
import { hashFrame } from '../src/phash.mjs';
import { addEntry, saveIndex, loadIndex, INDEX_VERSION, INDEX_PATH } from '../src/frameindex.mjs';

const log = createLogger('info');
const USER_AGENT = 'amps-frame-index/1.0 (+https://github.com/amps)';
const MAX_BYTES = 12 * 1024 * 1024;
// Fandom serves uploads as `.../Episode_3.png/revision/latest?cb=…`, so the
// extension is followed by a slash, not by the end of the path.
const IMAGE_EXTENSIONS = /\.(jpe?g|png)(\/|$)/i;

/**
 * A handful of well-known anime wikis. Every slug here was checked to resolve:
 * `one-piece-piece` and `spiritedaway` do not exist on Fandom and answered
 * HTTP 404, so they are gone. `search` is the file-namespace search term.
 */
const FANDOM_WIKIS = [
  { slug: 'onepiece', title: 'ONE PIECE', anilistId: 21, search: 'Episode' },
  { slug: 'naruto', title: 'NARUTO', anilistId: 20, search: 'Episode' },
  { slug: 'evangelion', title: 'Neon Genesis Evangelion', anilistId: 32, search: 'Episode' },
  { slug: 'cowboybebop', title: 'Cowboy Bebop', anilistId: 1, search: 'Episode' },
  { slug: 'swordartonline', title: 'Sword Art Online', anilistId: 30, search: 'Episode' },
  { slug: 'kiki', title: "Kiki's Delivery Service", anilistId: 1526, search: 'Screenshot' },
];

/**
 * Internet Archive queries that are likely to surface genuinely free material.
 *
 * The wildcard has to lead: the Archive's search treats `.` as a token
 * separator, so `licenseurl:(creativecommons.org*)` matches nothing at all
 * (verified: 0 hits) while `licenseurl:(*creativecommons*)` matches ~1.1M
 * items. The licence gate still has the final say on every one of them.
 */
const ARCHIVE_QUERIES = [
  'collection:(animationandcartoons) AND licenseurl:(*creativecommons*)',
  'subject:(anime) AND mediatype:(image) AND licenseurl:(*)',
];

/* ------------------------------------------------------------------ licences */

/**
 * Only an explicit free licence counts as harvestable. Anything unknown —
 * "fair use", "all rights reserved", an empty field — is refused.
 * @returns {{free: boolean, licence: string, reason: string}}
 */
export function classifyLicence(raw) {
  const text = String(raw ?? '').trim();
  if (!text) return { free: false, licence: '', reason: 'no licence declared' };
  const lower = text.toLowerCase();
  const refusals = ['all rights reserved', 'fair use', 'fairuse', 'non-commercial', 'nc-', 'cc-by-nc', 'no derivative', 'nd-', 'unknown', '©'];
  const hit = refusals.find((needle) => lower.includes(needle));
  if (hit) return { free: false, licence: text.slice(0, 80), reason: `licence looks restricted (${hit})` };
  const allowances = ['cc0', 'cc-zero', 'publicdomain', 'public domain', 'pdm', 'creativecommons.org/licenses/by/', 'creativecommons.org/licenses/by-sa/', 'usgov', 'no known copyright'];
  const ok = allowances.some((needle) => lower.includes(needle));
  return ok
    ? { free: true, licence: text.slice(0, 120), reason: 'explicitly free' }
    : { free: false, licence: text.slice(0, 80), reason: 'licence is not an explicit free one' };
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
  const res = await fetch(url, { ...options, headers: { 'user-agent': USER_AGENT, ...(options.headers ?? {}) }, signal: AbortSignal.timeout(timeoutMs) });
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  return res;
}

/** Is there any outbound network at all? Used to pick live vs dry-run. */
async function networkAvailable(timeoutMs = 4000) {
  try {
    const controller = AbortSignal.timeout(timeoutMs);
    await fetch('https://archive.org/metadata/sintel', { method: 'HEAD', headers: { 'user-agent': USER_AGENT }, signal: controller });
    return true;
  } catch {
    return false;
  }
}

/* --------------------------------------------------------------- adapters -- */

/**
 * Fandom gallery adapter: the MediaWiki API on `https://<slug>.fandom.com/api.php`.
 *
 * `generator=images` (the whole-file list) is disabled on Fandom and answers
 * with zero pages, so files are found through the file-namespace search
 * generator instead, which does work.
 *
 * Anime screenshots on a Fandom wiki are almost always fair use, and Fandom
 * leaves `LicenseShortName` empty on most uploads, so this adapter is expected
 * to harvest very little — the licence gate rejecting the list is the point.
 */
export async function fandomAdapter({ wiki, limit, dryRun, timeoutMs, log: out }) {
  const candidates = [];
  const skipped = [];
  const search = wiki.search ?? 'Episode';
  const api = `https://${wiki.slug}.fandom.com/api.php?action=query&generator=search&gsrsearch=${encodeURIComponent(search)}&gsrnamespace=6&gsrlimit=50&prop=imageinfo&iiprop=url|size|extmetadata&iiurlwidth=640&format=json&formatversion=2`;
  if (dryRun) {
    out?.info('fandom: would query the MediaWiki API', { wiki: wiki.slug, api });
    for (let i = 0; i < Math.min(limit, 3); i++) {
      skipped.push({ source: `fandom:${wiki.slug}`, url: `https://${wiki.slug}.fandom.com/wiki/File:<image ${i + 1}>.jpg`, reason: 'dry run — nothing downloaded' });
    }
    return { candidates, skipped };
  }

  let payload;
  try {
    const res = await fetchWithTimeout(api, {}, timeoutMs);
    payload = await res.json();
  } catch (err) {
    out?.warn('fandom: request failed', { wiki: wiki.slug, error: String(err?.message ?? err) });
    return { candidates, skipped };
  }

  const pages = Array.isArray(payload?.query?.pages) ? payload.query.pages : [];
  for (const page of pages) {
    if (candidates.length >= limit) break;
    const info = Array.isArray(page?.imageinfo) ? page.imageinfo[0] : null;
    if (!info?.url || !IMAGE_EXTENSIONS.test(new URL(info.url).pathname)) continue;
    const meta = info.extmetadata ?? {};
    const licence = classifyLicence(meta.LicenseShortName?.value ?? meta.License?.value ?? meta.UsageTerms?.value ?? '');
    const record = {
      source: `fandom:${wiki.slug}`,
      imageUrl: info.thumburl ?? info.url,
      width: info.thumbwidth ?? info.width ?? null,
      height: info.thumbheight ?? info.height ?? null,
      anilistId: wiki.anilistId,
      seriesTitle: wiki.title,
      episode: null,
      timestampSec: null,
      licence: licence.licence,
    };
    if (!licence.free) {
      skipped.push({ source: record.source, url: record.imageUrl, reason: licence.reason });
      continue;
    }
    candidates.push(record);
  }
  return { candidates, skipped };
}

/**
 * Internet Archive adapter: advanced search for items, then the per-item
 * metadata file. Only items whose metadata declares a free licence are opened,
 * and only their still-image files are taken.
 */
export async function archiveAdapter({ query, limit, dryRun, timeoutMs, log: out }) {
  const candidates = [];
  const skipped = [];
  const search = `https://archive.org/advancedsearch.php?q=${encodeURIComponent(query)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=licenseurl&rows=${Math.min(limit, 50)}&page=1&output=json`;
  if (dryRun) {
    out?.info('archive: would run the advanced search', { query, search });
    skipped.push({ source: 'internet-archive', url: search, reason: 'dry run — nothing downloaded' });
    return { candidates, skipped };
  }

  let payload;
  try {
    const res = await fetchWithTimeout(search, {}, timeoutMs);
    payload = await res.json();
  } catch (err) {
    out?.warn('archive: search failed', { query, error: String(err?.message ?? err) });
    return { candidates, skipped };
  }

  const docs = Array.isArray(payload?.response?.docs) ? payload.response.docs : [];
  for (const doc of docs) {
    if (candidates.length >= limit) break;
    const identifier = String(doc.identifier ?? '');
    if (!identifier) continue;
    const licence = classifyLicence(doc.licenseurl ?? '');
    if (!licence.free) {
      skipped.push({ source: 'internet-archive', url: `https://archive.org/details/${identifier}`, reason: licence.reason });
      continue;
    }
    let meta;
    try {
      const res = await fetchWithTimeout(`https://archive.org/metadata/${encodeURIComponent(identifier)}`, {}, timeoutMs);
      meta = await res.json();
    } catch (err) {
      out?.warn('archive: metadata failed', { identifier, error: String(err?.message ?? err) });
      continue;
    }
    const files = Array.isArray(meta?.files) ? meta.files : [];
    for (const file of files) {
      if (candidates.length >= limit) break;
      const name = String(file?.name ?? '');
      if (!IMAGE_EXTENSIONS.test(name)) continue;
      candidates.push({
        source: 'internet-archive',
        imageUrl: `https://archive.org/download/${encodeURIComponent(identifier)}/${encodeURIComponent(name)}`,
        width: Number(file.width) || null,
        height: Number(file.height) || null,
        anilistId: Number(meta?.metadata?.anilist_id) || null,
        seriesTitle: String(meta?.metadata?.title ?? identifier),
        episode: Number(meta?.metadata?.episode) || null,
        timestampSec: null,
        licence: licence.licence,
        bytes: Number(file.size) || null,
      });
    }
  }
  return { candidates, skipped };
}

/** The adapter registry; `--source <name>` selects one of these keys. */
export const ADAPTERS = {
  fandom: {
    description: 'Fandom MediaWiki gallery API, licence gated',
    async collect(context) {
      const merged = { candidates: [], skipped: [] };
      for (const wiki of FANDOM_WIKIS) {
        const result = await fandomAdapter({ ...context, wiki });
        merged.candidates.push(...result.candidates);
        merged.skipped.push(...result.skipped);
      }
      return merged;
    },
    plan() {
      return FANDOM_WIKIS.map((wiki) => ({
        source: `fandom:${wiki.slug}`,
        request: `https://${wiki.slug}.fandom.com/api.php?action=query&generator=images&prop=imageinfo&iiprop=url|size|extmetadata`,
        note: 'only images whose extmetadata names a free licence survive the gate',
      }));
    },
  },
  archive: {
    description: 'Internet Archive items whose metadata declares a free licence',
    async collect(context) {
      const merged = { candidates: [], skipped: [] };
      for (const query of ARCHIVE_QUERIES) {
        const result = await archiveAdapter({ ...context, query });
        merged.candidates.push(...result.candidates);
        merged.skipped.push(...result.skipped);
      }
      return merged;
    },
    plan() {
      return ARCHIVE_QUERIES.map((query) => ({
        source: 'internet-archive',
        request: `https://archive.org/advancedsearch.php?q=${encodeURIComponent(query)}`,
        note: 'item metadata licenceurl must be CC/PD before any file is opened',
      }));
    },
  },
};

/** Download one candidate and hash it; failures skip the frame, never poison the index. */
async function hashCandidate(candidate, timeoutMs, out) {
  try {
    const res = await fetchWithTimeout(candidate.imageUrl, {}, timeoutMs);
    if (Number(res.headers.get('content-length') ?? 0) > MAX_BYTES) throw new Error('file is larger than the limit');
    const bytes = Buffer.from(await res.arrayBuffer());
    if (bytes.length === 0) throw new Error('empty response');
    const hash = hashFrame(bytes, res.headers.get('content-type') ?? '');
    return {
      hash,
      anilistId: candidate.anilistId,
      seriesTitle: candidate.seriesTitle,
      episode: candidate.episode ?? null,
      timestampSec: candidate.timestampSec ?? null,
      source: candidate.source,
      imageUrl: candidate.imageUrl,
      width: candidate.width ?? null,
      height: candidate.height ?? null,
    };
  } catch (err) {
    out?.warn('frame skipped: could not hash it', { url: candidate.imageUrl, error: String(err?.message ?? err) });
    return null;
  }
}

async function main() {
  const args = parseArgv(process.argv.slice(2));
  const limit = Number(args.LIMIT) > 0 ? Math.floor(Number(args.LIMIT)) : 25;
  const requested = String(args.SOURCE ?? '').toLowerCase();
  const outFile = args.OUT ? path.resolve(String(args.OUT)) : INDEX_PATH;
  const timeoutMs = Number(args.TIMEOUT) > 0 ? Number(args.TIMEOUT) : 15000;
  const names = requested ? [requested] : Object.keys(ADAPTERS);

  for (const name of names) {
    if (!ADAPTERS[name]) {
      process.stderr.write(`unknown source "${name}"; available: ${Object.keys(ADAPTERS).join(', ')}\n`);
      process.exit(2);
    }
  }

  let dryRun = args.DRY_RUN === 'true';
  if (!dryRun && args.LIVE === 'true') dryRun = false;
  if (!dryRun) {
    const online = await networkAvailable(Math.min(timeoutMs, 4000));
    if (!online) {
      dryRun = true;
      process.stdout.write('сеть недоступна — переключаюсь в режим --dry-run (план запросов, ничего не скачивается)\n');
    }
  }

  process.stdout.write(`\n  сбор индекса кадров  mode=${dryRun ? 'dry-run' : 'live'}  limit=${limit}  sources=${names.join(',')}\n`);
  process.stdout.write(`  вывод: ${outFile}\n\n`);

  const context = { limit, dryRun, timeoutMs, log };
  const allCandidates = [];
  const allSkipped = [];

  for (const name of names) {
    const adapter = ADAPTERS[name];
    process.stdout.write(`--- ${name}: ${adapter.description}\n`);
    if (dryRun) {
      for (const step of adapter.plan()) {
        process.stdout.write(`    would fetch  ${step.request}\n`);
        process.stdout.write(`      from      ${step.source} — ${step.note}\n`);
      }
      allSkipped.push({ source: name, url: adapter.plan()[0]?.request ?? '', reason: 'dry run — nothing downloaded' });
      continue;
    }
    const result = await adapter.collect(context);
    for (const candidate of result.candidates) allCandidates.push(candidate);
    allSkipped.push(...result.skipped);
    process.stdout.write(`    кандидатов: ${result.candidates.length}, отклонено по лицензии: ${result.skipped.length}\n`);
  }

  if (dryRun) {
    process.stdout.write(`\n  dry-run: скачиваний 0, лицензионных отказов ${allSkipped.length}\n`);
    process.stdout.write('  повторите с --live на машине с доступом в интернет, чтобы собрать настоящий индекс\n\n');
    process.exit(0);
  }

  let index = loadIndex({ file: outFile, force: true });
  let added = 0;
  let failed = 0;
  for (const candidate of allCandidates.slice(0, limit)) {
    const entry = await hashCandidate(candidate, timeoutMs, log);
    if (!entry) {
      failed += 1;
      continue;
    }
    if (!entry.anilistId) {
      // A frame without an AniList id cannot be shown in the app; keep it out.
      allSkipped.push({ source: candidate.source, url: candidate.imageUrl, reason: 'no AniList id for this item' });
      continue;
    }
    addEntry(index, entry);
    added += 1;
  }
  index.builtAt = new Date().toISOString();
  saveIndex(index, { file: outFile });

  process.stdout.write(`\n  добавлено: ${added}, пропущено: ${failed + allSkipped.length}, всего в индексе: ${index.entries.length}\n`);
  // A breakdown by reason, so a run that harvests nothing explains itself
  // instead of just printing a row of skip lines.
  const byReason = new Map();
  for (const skip of [...allSkipped, ...(failed > 0 ? [{ reason: 'не удалось скачать или разобрать' }] : [])]) {
    byReason.set(skip.reason, (byReason.get(skip.reason) ?? 0) + 1);
  }
  for (const [reason, count] of [...byReason].sort((a, b) => b[1] - a[1])) {
    process.stdout.write(`    ${String(count).padStart(4)} x ${reason}\n`);
  }
  for (const skip of allSkipped.slice(0, 5)) process.stdout.write(`      напр.: ${skip.source} ${String(skip.url).slice(0, 70)} — ${skip.reason}\n`);
  process.stdout.write(`\n  записан ${outFile} (schema v${INDEX_VERSION})\n\n`);
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]).endsWith('build-frame-index.mjs');
if (invokedDirectly) {
  main().catch((err) => {
    process.stderr.write(`\nСБОЙ СБОРА: ${err?.stack ?? err}\n`);
    process.exit(1);
  });
}

export { main, FANDOM_WIKIS, ARCHIVE_QUERIES };