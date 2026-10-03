import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { extractToolPayload } from './mcp-client.mjs';
import { loadCatalog, searchCatalog, getByAnilistId, catalogStats } from './catalog.mjs';
import { rankCandidates } from './ranking.mjs';
import { resolveWikidata, seriesFacts } from './wikidata.mjs';
import { resolveFandomSlug, fetchFandomPage, fandomRoster } from './fandom.mjs';
import { handleIndexRequest } from './frameindex.mjs';

export const VERSION = '1.0.0';
const TOOLS_TTL_MS = 30000;
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

export class HttpError extends Error {
  constructor(status, payload) {
    super(payload.message ?? payload.error);
    this.status = status;
    this.payload = payload;
  }
}

const badRequest = (message) => new HttpError(400, { error: 'bad_request', message });
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const pick = (o, k) => (isObject(o) && o[k] !== undefined && o[k] !== null ? o[k] : null);
/** AniList отдаёт id числом, но через прокси он может прийти строкой. */
const numberOrNull = (v) => {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
};

export function normalizeAnilist(a) {
  if (!isObject(a)) return null;
  const title = isObject(a.title) ? a.title : {};
  const cover = isObject(a.coverImage) ? a.coverImage : {};
  const start = isObject(a.startDate) ? a.startDate : {};
  return {
    id: pick(a, 'id'),
    idMal: pick(a, 'idMal'),
    title: { romaji: title.romaji ?? null, english: title.english ?? null, native: title.native ?? null },
    description: pick(a, 'description'),
    coverImage: { extraLarge: cover.extraLarge ?? cover.large ?? null },
    episodes: pick(a, 'episodes'),
    genres: Array.isArray(a.genres) ? a.genres : [],
    startDate: { year: pick(start, 'year') ?? null, month: pick(start, 'month') ?? null, day: pick(start, 'day') ?? null },
    status: pick(a, 'status'),
    format: pick(a, 'format'),
    averageScore: pick(a, 'averageScore'),
    popularity: pick(a, 'popularity'),
    synonyms: Array.isArray(a.synonyms) ? a.synonyms : [],
    isAdult: a.isAdult === true,
  };
}

/**
 * Тело приходит сырым Buffer, а не разобранным объектом. `isObject` на Buffer
 * проходит молча (это объект и не массив), поэтому разбирать JSON нужно явно —
 * иначе сигналы молча превращаются в null, а ранжирование отвечает «rejected».
 */
function jsonBody(ctx) {
  const raw = ctx.body;
  if (!raw || raw.length === 0) return {};
  if (!Buffer.isBuffer(raw)) return isObject(raw) ? raw : {};
  try {
    const parsed = JSON.parse(raw.toString('utf8'));
    return isObject(parsed) ? parsed : {};
  } catch (err) {
    throw badRequest('тело запроса не является корректным JSON');
  }
}

/**
 * Wikidata даёт QID и подписи, вики — человеческие имена и ссылки. Склеиваем по
 * имени, приоритет у вики: там имена именно в той форме, в которой их знает читатель.
 */
function mergeRoster(fromWikidata, fromWiki) {
  const out = [];
  const seen = new Set();
  for (const item of fromWiki ?? []) {
    const key = String(item?.name ?? '').trim().toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push({ name: item.name, url: item.url ?? null, source: 'fandom' });
  }
  for (const item of fromWikidata ?? []) {
    const key = String(item?.name ?? '').trim().toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push({ name: item.name, nameRu: item.nameRu ?? null, qid: item.qid ?? null, url: null, source: 'wikidata' });
  }
  return out;
}

/** Плоская сводка записи каталога для телефона — без внутренних полей обхода. */
function summarizeCatalogEntry(entry) {
  const title = isObject(entry?.title) ? entry.title : {};
  const cover = isObject(entry?.coverImage) ? entry.coverImage : {};
  return {
    anilistId: entry?.anilistId ?? null,
    idMal: entry?.idMal ?? null,
    title: {
      romaji: title.romaji ?? null,
      english: title.english ?? null,
      native: title.native ?? null,
    },
    synonyms: Array.isArray(entry?.synonyms) ? entry.synonyms : [],
    format: entry?.format ?? null,
    status: entry?.status ?? null,
    season: entry?.season ?? null,
    seasonYear: entry?.seasonYear ?? null,
    episodes: entry?.episodes ?? null,
    duration: entry?.duration ?? null,
    genres: Array.isArray(entry?.genres) ? entry.genres : [],
    averageScore: entry?.averageScore ?? null,
    popularity: entry?.popularity ?? null,
    isAdult: entry?.isAdult === true,
    cover: cover.large ?? cover.medium ?? cover.extraLarge ?? null,
    studios: Array.isArray(entry?.studios)
      ? entry.studios.map((s) => (isObject(s) ? s.name : s)).filter(Boolean)
      : [],
  };
}

/** trace.moe returns `video` either as an object or as a media URL string. */
function normalizeVideo(video, episode) {
  if (isObject(video)) return { id: pick(video, 'id'), part: pick(video, 'part'), length: pick(video, 'length'), url: pick(video, 'url') };
  if (typeof video === 'string' && video) {
    // media.trace.moe URLs end in `/<videoId>/<W>x<H>`.
    const parts = video.split('?')[0].split('/').filter(Boolean);
    const last = parts[parts.length - 1] ?? video;
    const id = /^\d+x\d+$/i.test(last) ? (parts[parts.length - 2] ?? last) : last;
    return { id, part: episode ?? null, length: null, url: video };
  }
  return { id: null, part: episode ?? null, length: null, url: null };
}

export function normalizeTrace(payload, raw) {
  const episode = null;
  const miss = {
    engine: 'trace.moe',
    matched: false,
    anilist: null,
    episode,
    frame: null,
    timestamp: null,
    similarity: null,
    video: { id: null, part: null, length: null, url: null },
    raw: typeof raw === 'string' ? raw : '',
  };
  const list = Array.isArray(payload) ? payload : Array.isArray(payload?.result) ? payload.result : [];
  const top = list[0];
  if (!isObject(top)) return miss;
  const ep = pick(top, 'episode') ?? pick(top, 'episode_start') ?? episode;
  return {
    ...miss,
    matched: true,
    anilist: normalizeAnilist(top.anilist) ?? normalizeAnilist({ id: pick(top, 'anilistID') }),
    episode: ep,
    frame: pick(top, 'frame'),
    timestamp: pick(top, 'from') ?? pick(top, 'timestamp'),
    similarity: pick(top, 'similarity'),
    video: normalizeVideo(top.video, ep),
  };
}

/** imgfind answers in plain text (`key: value` lines), so parse it. */
export function normalizeSauce(text, configured) {
  if (!configured) return { configured: false };
  const t = String(text ?? '');
  const field = (name) => {
    const m = t.match(new RegExp(`\\b${name}:\\s*([^|\\n]+)`, 'i'));
    return m ? m[1].trim() : null;
  };
  const similarity = t.match(/similarity:\s*([\d.]+)\s*%/i);
  const engine = t.match(/MATCH via\s+([^\s]+)/i);
  const source = field('source') ?? field('anilist') ?? field('pixiv_id');
  const indexId = field('index_id');
  const title = field('title');
  const author = field('author') ?? field('member_name');
  return {
    configured: true,
    matched: /MATCH via/i.test(t) || Boolean(similarity),
    similarity: similarity ? Number(similarity[1]) / 100 : null,
    index: indexId ? Number(indexId) || 1 : 1,
    source: engine ? engine[1] : source ? 'unknown' : null,
    title,
    url: source && /^https?:\/\//i.test(source) ? source : null,
    author,
    characters: [],
    tags: [],
    raw: t,
  };
}

function inspectBody(ctx) {
  const body = ctx.body;
  if (!Buffer.isBuffer(body) || body.length === 0) throw badRequest('empty_body: expected raw image bytes');
  const contentType = String(ctx.headers['content-type'] ?? '').toLowerCase();
  if (contentType && !contentType.startsWith('image/') && !contentType.includes('octet-stream')) {
    throw badRequest(`unsupported content-type: ${contentType} (expected image/jpeg or image/png)`);
  }
  const isJpeg = body.length > 3 && body[0] === 0xff && body[1] === 0xd8 && body[2] === 0xff;
  const isPng = body.length > 8 && body.subarray(0, 8).equals(PNG_MAGIC);
  if (!isJpeg && !isPng) throw badRequest('body is neither a JPEG nor a PNG image');
  const requested = String(ctx.headers['x-filename'] ?? 'frame.png').toLowerCase();
  const ext = /\.jpe?g$|\.png$|\.webp$|\.bmp$/.exec(requested)?.[0] ?? (isJpeg ? '.jpg' : '.png');
  return { buffer: body, ext };
}

/** Write the upload to a unique temp file; always delete it afterwards. */
async function withTempImage(ctx, fn) {
  const { buffer, ext } = inspectBody(ctx);
  const filePath = path.join(os.tmpdir(), `amps-${crypto.randomUUID()}${ext}`);
  await writeFile(filePath, buffer);
  try {
    return await fn(filePath);
  } finally {
    await rm(filePath, { force: true }).catch(() => {});
  }
}

function traceArgs(ctx, filePath) {
  const args = { filePath, cutBorders: String(ctx.headers['x-cut-borders'] ?? 'true').toLowerCase() !== 'false', anilistInfo: true };
  const anilistID = Number(ctx.headers['x-anilist-id']);
  if (Number.isInteger(anilistID) && anilistID > 0) args.anilistID = anilistID;
  return args;
}

async function callTrace(deps, ctx, filePath) {
  const result = await deps.tracemoe.callTool('search_anime_by_image_file', traceArgs(ctx, filePath), { timeoutMs: deps.requestTimeoutMs });
  const { data, raw } = extractToolPayload(result);
  if (result?.isError) return { engine: 'trace.moe', matched: false, error: raw.slice(0, 500), raw };
  return normalizeTrace(data, raw);
}

async function callSauce(deps, filePath) {
  if (!deps.sauceNaoKey) return { configured: false };
  const result = await deps.imgfind.callTool('find_image_source', { filePath, limit: 6 }, { timeoutMs: deps.requestTimeoutMs });
  const { raw } = extractToolPayload(result);
  if (result?.isError) return { configured: true, matched: false, error: raw.slice(0, 500), raw };
  const summary = normalizeSauce(raw, true);
  const detailed = await sauceNaoDetail(deps, filePath).catch((err) => {
    deps.log?.debug?.('saucenao detail failed', String(err?.message ?? err));
    return null;
  });
  return detailed ? mergeSauce(summary, detailed) : summary;
}

/**
 * imgfind reports SauceNAO as one formatted line, which throws away the
 * character and tag arrays the app needs to pick a character out of a frame.
 * With a key configured the same request is repeated against the SauceNAO API
 * so the structured fields survive; the MCP result stays the source of truth
 * for "did anything match".
 */
async function sauceNaoDetail(deps, filePath) {
  const bytes = await readFile(filePath);
  const form = new FormData();
  form.append('file', new Blob([bytes]), path.basename(filePath));
  const url = new URL('https://saucenao.com/search.php');
  url.search = new URLSearchParams({
    output_type: '2',
    numres: '6',
    db: '999',
    api_key: deps.sauceNaoKey,
  });
  const res = await fetch(url, {
    method: 'POST',
    body: form,
    headers: { 'User-Agent': 'amps-bridge/1.0' },
    signal: AbortSignal.timeout(deps.requestTimeoutMs),
  });
  if (!res.ok) throw new Error(`SauceNAO HTTP ${res.status}`);
  const payload = await res.json();
  return (Array.isArray(payload?.results) ? payload.results : []).map((entry) => {
    const header = isObject(entry?.header) ? entry.header : {};
    const data = isObject(entry?.data) ? entry.data : {};
    const links = Array.isArray(data.ext_urls) ? data.ext_urls : [];
    return {
      index: Number(header.index_id) || null,
      similarity: Number(header.similarity ?? 0) / 100 || null,
      // 1.0.2: ранжирование сопоставляет совпадения SauceNAO с trace.moe по
      // AniList id. Без этого поля пришлось бы сравнивать названия строкой,
      // и «Ta нет»-подобные совпадения притворялись бы другой серией.
      anilistId: numberOrNull(data.anilist_id ?? data.anidb_id),
      title: typeof data.title === 'string' ? data.title : null,
      author: data.author_name ?? data.member_name ?? null,
      url: links[0] ?? null,
      characters: Array.isArray(data.characters) ? data.characters.filter((c) => typeof c === 'string') : [],
      tags: Array.isArray(data.tags) ? data.tags.filter((t) => typeof t === 'string') : [],
      series: typeof data.series === 'string' ? data.series : (Array.isArray(data.series) ? data.series.join(', ') : null),
      copyright: typeof data.copyright === 'string' ? data.copyright : (Array.isArray(data.copyright) ? data.copyright.join(', ') : null),
      danbooruId: data.danbooru_id ?? null,
      pixivId: data.pixiv_id ?? null,
    };
  });
}

function mergeSauce(summary, results) {
  const best = results.reduce((a, b) => ((b.similarity ?? 0) > (a?.similarity ?? 0) ? b : a), null);
  const characters = [...new Set(results.flatMap((r) => r.characters))].slice(0, 24);
  const tags = [...new Set(results.flatMap((r) => r.tags))].slice(0, 60);
  return {
    ...summary,
    characters,
    tags,
    series: best?.series ?? null,
    copyright: best?.copyright ?? null,
    results,
  };
}

async function nodeState(client, cache) {
  const hit = cache.get(client);
  if (hit && Date.now() - hit.at < TOOLS_TTL_MS) return hit.state;
  let state;
  try {
    const tools = await client.listTools();
    state = { ready: true, tools: tools.map((t) => t?.name).filter(Boolean) };
  } catch (err) {
    state = { ready: false, error: String(err?.message ?? err), tools: [], stderr: client.stderrTail(5) };
  }
  cache.set(client, { at: Date.now(), state });
  return state;
}

/** Shared with the discovery responder so both report one and the same state. */
const nodeStates = new Map();

/**
 * Last known handshake state of a node, or `null` when it has never been probed.
 * `/api/health` is what fills this cache; UDP must not probe on its own.
 */
export function lastKnownNodeState(client) {
  return nodeStates.get(client)?.state ?? null;
}

export function createRouter(deps) {
  const toolsCache = nodeStates;
  const log = deps.log;

  const handlers = new Map([
    ['GET /api/health', async () => {
      const [tracemoe, imgfind] = await Promise.all([
        nodeState(deps.tracemoe, toolsCache),
        nodeState(deps.imgfind, toolsCache),
      ]);
      return {
        ok: true,
        version: VERSION,
        uptimeSec: Math.round(process.uptime()),
        keys: { traceMoe: Boolean(deps.traceMoeKey), sauceNao: Boolean(deps.sauceNaoKey) },
        nodes: { tracemoe, imgfind },
        discovery: {
          port: deps.discovery?.port ?? null,
          active: Boolean(deps.discovery?.active),
        },
      };
    }],

    ['POST /api/frame/lookup', async (ctx) => withTempImage(ctx, async (filePath) => callTrace(deps, ctx, filePath))],

    ['POST /api/frame/identify', async (ctx) => withTempImage(ctx, async (filePath) => {
      const [trace, sauce] = await Promise.all([
        callTrace(deps, ctx, filePath),
        callSauce(deps, filePath),
      ]);
      return { trace, sauce };
    })],

    ['GET /api/anime/search', async (ctx) => {
      const query = String(ctx.query.get('q') ?? '').trim();
      if (!query) throw badRequest('missing query parameter q');
      const result = await deps.tracemoe.callTool('search_anime_by_name', { query }, { timeoutMs: deps.requestTimeoutMs });
      const { data } = extractToolPayload(result);
      const rows = Array.isArray(data) ? data : Array.isArray(data?.result) ? data.result : [];
      return {
        query,
        results: rows.map((row) => ({
          anilist: normalizeAnilist(isObject(row) ? row.anilist ?? row : null),
          similarity: isObject(row) ? pick(row, 'similarity') : null,
        })),
      };
    }],

    // 1.0.2 — одно взвешенное решение по всем сигналам вместо «первого
    // попавшегося». Чистая функция: никакой сети, никаких часов.
    ['POST /api/rank', async (ctx) => {
      const body = jsonBody(ctx);
      return rankCandidates(
        { trace: body.trace ?? null, sauce: body.sauce ?? null, index: body.index ?? null, labels: body.labels ?? null },
        { log },
      );
    }],

    // 1.0.2 — собственный каталог аниме. Нужен, чтобы перебирать варианты и
    // сверяться с вики, а не полагаться на один ответ trace.moe.
    ['GET /api/catalog/status', async () => {
      const catalog = await loadCatalog(deps);
      const stats = catalogStats(catalog);
      return { ok: true, ...stats, sources: catalog.sources ?? [] };
    }],

    ['GET /api/catalog/search', async (ctx) => {
      const query = String(ctx.query.get('q') ?? '').trim();
      if (!query) throw badRequest('missing query parameter q');
      const rawLimit = Number(ctx.query.get('limit'));
      const limit = Number.isFinite(rawLimit) && rawLimit > 0 ? Math.min(Math.trunc(rawLimit), 50) : 10;
      const entries = await searchCatalog(query, limit, deps);
      return { query, results: entries.map((e) => summarizeCatalogEntry(e)) };
    }],
  ]);

  // Пути с параметром в конце: /api/catalog/anime/138459. Точное совпадение по
  // ключу сюда не попадает, поэтому они разбираются отдельно.
  const prefixHandlers = [
    ['GET /api/catalog/anime/', async (ctx) => {
      const raw = ctx.pathname.slice('/api/catalog/anime/'.length);
      const id = Number(raw);
      if (!Number.isInteger(id) || id <= 0) throw badRequest('bad anilist id');
      const entry = await getByAnilistId(id, deps);
      if (!entry) throw new HttpError(404, { error: 'not_found', message: `anime ${id} не найден в каталоге` });

      // Вики и фандомы подтягиваем отдельно от каталога: они отвечают медленно
      // и могут отсутствовать, а карточку серии это ломать не должно.
      const wikiDeps = { log, requestTimeoutMs: Math.min(deps.requestTimeoutMs ?? 15000, 15000) };
      const titles = [entry.title?.romaji, entry.title?.english, entry.title?.native].filter(Boolean);
      const primaryTitle = titles[0] ?? null;

      let wiki = null;
      let qid = null;
      let wd = null;
      let roster = { characters: [], places: [] };
      if (primaryTitle) {
        // resolveWikidata отдаёт объект { qid, labels, ... }, а seriesFacts ждёт
        // сам QID. Передать объект — значит тихо потерять и персонажей, и места.
        wd = await resolveWikidata(primaryTitle, wikiDeps).catch(() => null);
        qid = wd?.qid ?? null;
        const slug = await resolveFandomSlug(primaryTitle, wd, wikiDeps).catch(() => null);
        if (slug) {
          const page = await fetchFandomPage(slug, primaryTitle, wikiDeps).catch(() => null);
          // Ссылку на вики отдаём даже если статья не открылась: сам домен уже
          // полезен пользователю, молча терять его нельзя.
          wiki = {
            slug,
            url: page?.url ?? `https://${slug}.fandom.com/wiki/${encodeURIComponent(String(primaryTitle).replace(/ /g, '_'))}`,
            intro: page?.intro ?? null,
            images: (page?.images ?? []).slice(0, 24),
            pageFound: Boolean(page),
          };
          // Персонажи и места приходят из категорий вики: это единственное место,
          // где они названы по-человечески, а не как в Wikidata, где у аниме
          // утверждения о персонажах просто нет.
          roster = await fandomRoster(slug, primaryTitle, wikiDeps).catch(() => ({ characters: [], places: [] }));
        }
      }

      const facts = qid ? await seriesFacts(qid, wikiDeps).catch(() => null) : null;

      return {
        anime: summarizeCatalogEntry(entry),
        wiki,
        qid,
        wikidataLabels: wd?.labels ?? null,
        sitelinks: wd?.sitelinks ?? null,
        characters: mergeRoster(facts?.characters, roster.characters),
        places: mergeRoster(facts?.places, roster.places),
        genres: facts?.genres ?? [],
        wikiCategories: roster.categories ?? [],
      };
    }],
  ];

  return async function dispatch(ctx) {
    let handler = handlers.get(`${ctx.method} ${ctx.pathname}`);
    if (!handler) {
      for (const [route, fn] of prefixHandlers) {
        const [method, prefix] = route.split(' ');
        // Не `ctx.method + ' ' + ctx.pathname.startsWith(prefix)`: конкатенация
        // даёт непустую строку даже при false, и условие всегда истинно — тогда
        // любой путь без точного ключа уезжает в обработчик каталога.
        if (ctx.method === method && ctx.pathname.startsWith(prefix)) { handler = fn; break; }
      }
    }
    // Свой индекс кадров: четыре маршрута живут в frameindex.mjs, он сам решает,
    // что ответить, и сам валидирует хэш.
    if (!handler && (ctx.method === 'GET' || ctx.method === 'POST') && ctx.pathname.startsWith('/api/frame/index')) {
      handler = async (c) => {
        const { status, json } = handleIndexRequest(c.pathname, c.query, jsonBody(c), { log });
        if (status >= 400) throw new HttpError(status, json);
        return json;
      };
    }
    const startedAt = process.hrtime.bigint();
    let status = 200;
    let json;
    try {
      if (!handler) {
        status = 404;
        json = { error: 'not_found' };
      } else {
        json = await handler(ctx);
      }
    } catch (err) {
      if (err instanceof HttpError) {
        status = err.status;
        json = err.payload;
      } else if (err?.code === 'ETIMEDOUT') {
        status = 504;
        json = { error: 'upstream_timeout' };
      } else {
        status = 500;
        json = { error: 'upstream_error', message: String(err?.message ?? err).slice(0, 500) };
        log?.error('request failed', { method: ctx.method, path: ctx.pathname, error: json.message });
      }
    }
    const ms = Number(process.hrtime.bigint() - startedAt) / 1e6;
    log?.info('http_request', { method: ctx.method, path: ctx.pathname, status, ms: Math.round(ms) });
    return { status, json };
  };
}
