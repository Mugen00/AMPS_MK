import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isStale, readJson, writeJson } from './cache.mjs';

export const ANILIST_ENDPOINT = 'https://graphql.anilist.co';
export const CACHE_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '.cache');
export const CACHE_FILE = 'anilist-catalog.json';
export const CATALOG_TTL_MS = 12 * 60 * 60 * 1000;

const PER_PAGE = 50;
const MAX_PAGES = 40;
const REQUEST_TIMEOUT_MS = 15000;
const RETRY_BACKOFF_MS = 750;
// AniList allows 90 requests/minute unauthenticated; a 40 page crawl with no
// pause trips the limiter, so every page costs a little politeness.
const PAGE_DELAY_MS = 900;
const RATE_LIMIT_BACKOFF_MS = 5000;
const REFRESH_COOLDOWN_MS = 60000;
const SEARCH_LIMIT = 10;
const SOURCE_ANILIST = 'anilist';

/** AniList answers GET with 404, so the query only ever goes out as a POST body. */
const PAGE_QUERY = `query ($page: Int) {
  Page(page: $page, perPage: ${PER_PAGE}) {
    pageInfo { hasNextPage currentPage }
    media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
      id idMal title { romaji english native } synonyms format status season seasonYear
      episodes duration genres averageScore popularity isAdult
      coverImage { extraLarge large medium }
      studios { nodes { name isAnimationStudio } }
      externalLinks { site url }
    }
  }
}`;

// Search order: exact title > title prefix > title substring > any synonym hit.
const RANK_TITLES = 0;

/**
 * Точечный запрос одного тайтла по id. Обход страниц берёт только самые
 * популярные сериалы, а trace.moe спокойно находит и редкие — поэтому для
 * найденного кадра каталог обязан уметь достать запись напрямую.
 */
const MEDIA_QUERY = `query ($id: Int) {
  Media(id: $id, type: ANIME) {
    id idMal title { romaji english native } synonyms format status season seasonYear
    episodes duration genres averageScore popularity isAdult
    coverImage { extraLarge large medium }
    studios { nodes { name isAnimationStudio } }
    externalLinks { site url }
  }
}`;
const RANK_SYNONYMS = 3;
const memory = new Map();
const inFlight = new Map();
const attempts = new Map();

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const text = (v) => (typeof v === 'string' && v.trim() ? v : null);
/** AniList answers `null` for every field it has no value for; that must not become `0`. */
const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v));
const errorText = (err) => String(err?.message ?? err).slice(0, 200);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function context(deps) {
  return {
    log: deps?.log ?? null,
    file: path.join(path.resolve(deps?.cacheDir ?? CACHE_DIR), CACHE_FILE),
    now: typeof deps?.now === 'function' ? deps.now : Date.now,
  };
}

function remember(file, catalog) {
  memory.set(file, catalog);
  return catalog;
}

function emptyCatalog() {
  return { entries: [], builtAt: null, stale: true, sources: [], pageCount: 0 };
}

function staleOf(previous, ctx) {
  if (previous) return remember(ctx.file, { ...previous, stale: true });
  return remember(ctx.file, emptyCatalog());
}

function normalizeEntry(media) {
  if (!isObject(media)) return null;
  const anilistId = num(media.id);
  if (anilistId === null) return null;
  const title = isObject(media.title) ? media.title : {};
  const cover = isObject(media.coverImage) ? media.coverImage : {};
  const studioNodes = isObject(media.studios) && Array.isArray(media.studios.nodes) ? media.studios.nodes : [];
  const links = Array.isArray(media.externalLinks) ? media.externalLinks : [];
  return {
    anilistId,
    idMal: num(media.idMal),
    title: { romaji: text(title.romaji), english: text(title.english), native: text(title.native) },
    synonyms: (Array.isArray(media.synonyms) ? media.synonyms : []).map(text).filter(Boolean),
    format: text(media.format),
    status: text(media.status),
    season: text(media.season),
    seasonYear: num(media.seasonYear),
    episodes: num(media.episodes),
    duration: num(media.duration),
    genres: (Array.isArray(media.genres) ? media.genres : []).map(text).filter(Boolean),
    averageScore: num(media.averageScore),
    popularity: num(media.popularity),
    isAdult: media.isAdult === true,
    studios: studioNodes.filter(isObject).map((s) => ({ name: text(s.name), isAnimationStudio: s.isAnimationStudio === true })),
    coverImage: { extraLarge: text(cover.extraLarge), large: text(cover.large), medium: text(cover.medium) },
    externalLinks: links.filter(isObject).map((l) => ({ site: text(l.site), url: text(l.url) })),
  };
}

async function postGraphql(ctx, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const res = await fetch(ANILIST_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'AMPS/1.0 (local bridge)' },
      body,
      signal: controller.signal,
    });
    if (!res.ok) {
      // 429 is AniList's rate limiter, not a failure of the query. Carry the
      // status so the retry path can wait exactly as long as it asks.
      const err = new Error(`AniList HTTP ${res.status}`);
      err.status = res.status;
      const retryAfter = Number(res.headers?.get?.('retry-after'));
      if (Number.isFinite(retryAfter) && retryAfter > 0) err.retryAfterMs = retryAfter * 1000;
      throw err;
    }
    const payload = await res.json();
    if (Array.isArray(payload?.errors) && payload.errors.length) {
      const messages = payload.errors.map((e) => text(e?.message) ?? 'ошибка').join('; ');
      throw new Error(`AniList GraphQL: ${messages.slice(0, 200)}`);
    }
    return isObject(payload?.data) ? payload.data : null;
  } catch (err) {
    if (err?.name === 'AbortError') throw new Error(`AniList не ответил за ${REQUEST_TIMEOUT_MS / 1000} с`);
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

/** One retry with a short backoff — a single dropped request must not end a 40 page crawl. */
async function fetchPage(ctx, page) {
  const body = JSON.stringify({ query: PAGE_QUERY, variables: { page } });
  let lastError = null;
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      return await postGraphql(ctx, body);
    } catch (err) {
      lastError = err;
      // AniList asked us to slow down: wait exactly as long as it asked instead
      // of hammering it once more.
      if (err?.status === 429) {
        const wait = Math.min(Math.max(err.retryAfterMs ?? 0, RATE_LIMIT_BACKOFF_MS), 60_000);
        ctx.log?.warn?.('AniList ограничил частоту запросов, ждём перед повтором', {
          page, attempt, waitSec: Math.round(wait / 1000),
        });
        await sleep(wait);
        continue;
      }
      if (attempt === 1) {
        ctx.log?.warn?.('AniList не ответил, повторяем запрос страницы', { page, attempt, error: errorText(err) });
        await sleep(RETRY_BACKOFF_MS);
      }
    }
  }
  throw lastError;
}

async function persist(ctx, catalog) {
  try {
    await writeJson(ctx.file, catalog);
  } catch (err) {
    ctx.log?.warn?.('не удалось записать кэш каталога на диск', { file: ctx.file, error: errorText(err) });
  }
}

/** Never rejects: a failed crawl resolves to `null` so the caller keeps the old cache. */
async function crawl(ctx) {
  attempts.set(ctx.file, ctx.now());
  try {
    const entries = [];
    let pages = 0;
    let hasNextPage = true;
    while (hasNextPage && pages < MAX_PAGES) {
      const data = await fetchPage(ctx, pages + 1);
      const page = isObject(data?.Page) ? data.Page : null;
      if (!page) throw new Error('AniList вернул ответ без поля Page');
      for (const media of Array.isArray(page.media) ? page.media : []) {
        const entry = normalizeEntry(media);
        if (entry) entries.push(entry);
      }
      pages++;
      hasNextPage = isObject(page.pageInfo) && page.pageInfo.hasNextPage === true;
      if (hasNextPage) await sleep(PAGE_DELAY_MS);
    }
    if (hasNextPage) {
      ctx.log?.warn?.('каталог AniList собран частично: достигнут лимит в 40 страниц (2000 сериалов)', { pages, entries: entries.length });
    }
    const catalog = { entries, builtAt: ctx.now(), stale: false, sources: [SOURCE_ANILIST], pageCount: pages };
    await persist(ctx, catalog);
    ctx.log?.info?.('каталог AniList обновлён', { entries: entries.length, pages });
    return remember(ctx.file, catalog);
  } catch (err) {
    ctx.log?.warn?.('не удалось обновить каталог AniList', { error: errorText(err) });
    return null;
  }
}

/** Two callers that arrive together share one crawl instead of racing. */
function beginRefresh(ctx) {
  const running = inFlight.get(ctx.file);
  if (running) return running;
  const promise = crawl(ctx).finally(() => inFlight.delete(ctx.file));
  inFlight.set(ctx.file, promise);
  return promise;
}

export async function loadCatalog(deps) {
  const ctx = context(deps);
  const known = memory.get(ctx.file);
  if (known && (known.stale === true || !isStale(known, CATALOG_TTL_MS))) return known;

  const previous = await readJson(ctx.file, 0);
  const settled = memory.get(ctx.file);
  if (settled && (settled.stale === true || !isStale(settled, CATALOG_TTL_MS))) return settled;
  if (previous && !isStale(previous, CATALOG_TTL_MS)) return remember(ctx.file, previous);

  const running = inFlight.get(ctx.file);
  if (running) return (await running) ?? staleOf(previous, ctx);

  const since = ctx.now() - (attempts.get(ctx.file) ?? 0);
  if (since < REFRESH_COOLDOWN_MS) {
    ctx.log?.debug?.('обновление каталога пропущено: предыдущая попытка была недавно', { seconds: Math.round(since / 1000) });
    return staleOf(previous, ctx);
  }

  // Обход 40 страниц AniList занимает десятки секунд. Заставлять телефон ждать
  // столько ради прочтения каталога незачем: отдаём что есть (даже устаревшее)
  // и обновляемся в фоне. Первый запуск без кэша — единственный случай, когда
  // ждать всё-таки нужно, иначе поиск не найдёт вообще ничего.
  if (previous || known) {
    ctx.log?.info?.('каталог устарел, обновляем в фоне', { entries: previous?.entries?.length ?? 0 });
    void beginRefresh(ctx).catch(() => {});
    return staleOf(previous, ctx);
  }

  return (await beginRefresh(ctx)) ?? staleOf(previous, ctx);
}

export async function refreshCatalog(deps) {
  const ctx = context(deps);
  const previous = await readJson(ctx.file, 0);
  const fresh = await beginRefresh(ctx);
  if (fresh) return fresh;
  if (previous) {
    ctx.log?.warn?.('каталог AniList не обновлён, отдаём прошлый кэш', { entries: previous.entries?.length ?? 0, builtAt: previous.builtAt ?? null });
  }
  return staleOf(previous, ctx);
}

function rankText(value, query, base) {
  const v = String(value ?? '').trim().toLowerCase();
  if (!v) return null;
  if (v === query) return base;
  if (v.startsWith(query)) return base + 1;
  if (v.includes(query)) return base + 2;
  return null;
}

function rankEntry(entry, query) {
  let best = null;
  const keep = (rank) => {
    if (rank !== null && (best === null || rank < best)) best = rank;
  };
  const title = isObject(entry?.title) ? entry.title : {};
  for (const value of [title.romaji, title.english, title.native]) keep(rankText(value, query, RANK_TITLES));
  for (const value of Array.isArray(entry?.synonyms) ? entry.synonyms : []) keep(rankText(value, query, RANK_SYNONYMS));
  return best;
}

const positiveInt = (value, fallback) => {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
};

export async function searchCatalog(query, limit = SEARCH_LIMIT, deps) {
  const q = String(query ?? '').trim().toLowerCase();
  if (!q) return [];
  const max = positiveInt(limit, SEARCH_LIMIT);
  const { entries } = await loadCatalog(deps);
  const hits = [];
  for (const entry of entries) {
    const rank = rankEntry(entry, q);
    if (rank !== null) hits.push({ entry, rank, popularity: Number(entry?.popularity) || 0 });
  }
  hits.sort((a, b) => a.rank - b.rank
    || b.popularity - a.popularity
    || (Number(a.entry?.anilistId) || 0) - (Number(b.entry?.anilistId) || 0));
  return hits.slice(0, max).map((hit) => hit.entry);
}

export async function getByAnilistId(id, deps) {
  const wanted = Number(id);
  if (!Number.isInteger(wanted) || wanted <= 0) return null;
  const { entries } = await loadCatalog(deps);
  const cached = entries.find((entry) => entry?.anilistId === wanted);
  if (cached) return cached;
  // Редкий тайтл в обход по популярности не попадает. Спрашиваем AniList напрямую
  // и кладём запись в кэш, чтобы следующий запрос уже был мгновенным.
  return fetchByAnilistId(wanted, deps);
}

/** Одна запись по id напрямую из AniList. Никогда не бросает: отказ — это null. */
export async function fetchByAnilistId(id, deps) {
  const wanted = Number(id);
  if (!Number.isInteger(wanted) || wanted <= 0) return null;
  const ctx = context(deps);
  try {
    const data = await postGraphql(ctx, JSON.stringify({ query: MEDIA_QUERY, variables: { id: wanted } }));
    const entry = normalizeEntry(isObject(data?.Media) ? data.Media : null);
    if (!entry) return null;
    const known = memory.get(ctx.file);
    if (known && Array.isArray(known.entries)) {
      known.entries.push(entry);
      void persist(ctx, known);
    }
    return entry;
  } catch (err) {
    ctx.log?.warn?.('не удалось получить тайтл AniList по id', { id: wanted, error: errorText(err) });
    return null;
  }
}

export function catalogStats(catalog) {
  const entries = Array.isArray(catalog?.entries) ? catalog.entries : [];
  const total = entries.length;
  const pages = Number(catalog?.pageCount);
  return {
    count: total,
    stale: catalog?.stale === true,
    builtAt: num(catalog?.builtAt),
    pageCount: Number.isInteger(pages) && pages > 0 ? pages : Math.ceil(total / PER_PAGE),
  };
}
