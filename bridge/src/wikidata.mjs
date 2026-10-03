/**
 * Wikidata lookups: series resolution, the facts the wiki card needs, and the
 * P8080 "Fandom wiki ID" that `fandom.mjs` uses for slug discovery.
 * Every entry point swallows transport errors and answers `null`.
 */

const API = 'https://www.wikidata.org/w/api.php';
const USER_AGENT = 'amps-bridge/1.0 (https://github.com/amps) node-fetch';
const DEFAULT_TIMEOUT_MS = 15000;
const LANGUAGES = 'en|ru';
const ENTITY_BATCH = 50;
const ANIME_CLASSES = new Set(['Q1107', 'Q20650540', 'Q21191270']);
const FANDOM_PROPERTY = 'P8080';
const CANDIDATE_COUNT = 5;

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

function apiUrl(params) {
  return `${API}?${new URLSearchParams({ format: 'json', ...params })}`;
}

function budgetOf(deps, timeoutMs) {
  if (Number.isFinite(timeoutMs)) return timeoutMs;
  const configured = Number(deps?.requestTimeoutMs);
  return Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_TIMEOUT_MS;
}

async function getJson(url, { deps, timeoutMs, retry = 1 } = {}) {
  const budget = budgetOf(deps, timeoutMs);
  for (let attempt = 0; attempt <= retry; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), budget);
    try {
      const res = await fetch(url, {
        headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' },
        signal: controller.signal,
      });
      if (res.status !== 200) {
        deps?.log?.debug?.('wikidata: не 200', { url, status: res.status });
        if (res.status < 500) return null;
        throw new Error(`HTTP ${res.status}`);
      }
      return await res.json();
    } catch (err) {
      deps?.log?.debug?.('wikidata: запрос не удался', { url, attempt, error: String(err?.message ?? err) });
    } finally {
      clearTimeout(timer);
    }
  }
  return null;
}

function snakId(statement) {
  const value = statement?.mainsnak?.datavalue?.value;
  if (isObject(value) && typeof value.id === 'string') return value.id;
  if (isObject(value) && Number.isFinite(Number(value['numeric-id']))) return `Q${Number(value['numeric-id'])}`;
  return null;
}

function itemIds(claims, property, limit = 60) {
  const seen = [];
  for (const statement of claims?.[property] ?? []) {
    const id = snakId(statement);
    if (id && !seen.includes(id)) seen.push(id);
    if (seen.length >= limit) break;
  }
  return seen;
}

function stringClaim(claims, property) {
  for (const statement of claims?.[property] ?? []) {
    const value = statement?.mainsnak?.datavalue?.value;
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return null;
}

function labelPair(entity) {
  return {
    en: entity?.labels?.en?.value ?? null,
    ru: entity?.labels?.ru?.value ?? null,
  };
}

function sitelinkOf(entity, preferred) {
  const sitelinks = isObject(entity?.sitelinks) ? entity.sitelinks : {};
  for (const site of preferred) {
    if (typeof sitelinks[site]?.title === 'string') return sitelinks[site].title;
  }
  const anyWiki = Object.keys(sitelinks)
    .filter((site) => site.endsWith('wiki') && typeof sitelinks[site]?.title === 'string')
    .sort();
  return anyWiki.length > 0 ? sitelinks[anyWiki[0]].title : null;
}

/** P8080 stores the bare subdomain, but a pasted URL is accepted too. */
function fandomSlugOf(claims) {
  const raw = stringClaim(claims, FANDOM_PROPERTY);
  if (!raw) return null;
  const host = /^https?:\/\/([^/]+)/i.exec(raw)?.[1] ?? raw;
  const slug = host.replace(/\.fandom\.com$/i, '').trim().toLowerCase();
  return /^[a-z0-9-]+$/.test(slug) ? slug : null;
}

async function labelItems(ids, deps) {
  const out = [];
  for (let i = 0; i < ids.length; i += ENTITY_BATCH) {
    const batch = ids.slice(i, i + ENTITY_BATCH);
    const payload = await getJson(apiUrl({ action: 'wbgetentities', ids: batch.join('|'), props: 'labels', languages: LANGUAGES }), { deps });
    for (const id of batch) {
      const entity = payload?.entities?.[id];
      if (!entity) continue;
      const { en, ru } = labelPair(entity);
      out.push({ qid: id, name: en ?? ru ?? id, nameRu: ru });
    }
  }
  return out;
}

function mentionsAnime(hit) {
  return /\b(anime|manga|animē|manhwa|webtoon|ONA|OVA)\b/i.test(String(hit?.description ?? ''));
}

async function pickAnimeHit(hits, deps) {
  const described = hits.find(mentionsAnime);
  if (described) return described;

  const inline = hits.find((hit) => itemIds(hit?.claims ?? {}, 'P31').some((id) => ANIME_CLASSES.has(id)));
  if (inline) return inline;

  const top = hits.slice(0, 3);
  const payload = await getJson(apiUrl({ action: 'wbgetentities', ids: top.map((hit) => hit.id).join('|'), props: 'claims', languages: 'en' }), { deps });
  const byInstance = top.find((hit) => itemIds(payload?.entities?.[hit.id]?.claims, 'P31').some((id) => ANIME_CLASSES.has(id)));
  return byInstance ?? hits[0];
}

export async function resolveWikidata(title, deps = {}) {
  const query = String(title ?? '').trim();
  if (!query) return null;

  const search = await getJson(apiUrl({ action: 'wbsearchentities', search: query, language: 'en', uselang: 'en', limit: String(CANDIDATE_COUNT) }), { deps });
  const hits = Array.isArray(search?.search) ? search.search.filter((hit) => isObject(hit) && hit.id) : [];
  if (hits.length === 0) {
    deps?.log?.info?.('wikidata: серия не найдена', { title: query });
    return null;
  }

  const hit = await pickAnimeHit(hits, deps);
  const payload = await getJson(apiUrl({ action: 'wbgetentities', ids: hit.id, props: 'claims|sitelinks|labels', languages: LANGUAGES }), { deps });
  const entity = payload?.entities?.[hit.id];
  if (!entity) {
    deps?.log?.info?.('wikidata: сущность не отдалась', { title: query, qid: hit.id });
    return null;
  }

  return {
    qid: entity.id ?? hit.id,
    labels: labelPair(entity),
    fandomSlug: fandomSlugOf(entity.claims),
    sitelinks: { wiki: sitelinkOf(entity, ['enwiki']), ru: sitelinkOf(entity, ['ruwiki']) },
  };
}

export async function seriesFacts(qid, deps = {}) {
  const id = String(qid ?? '').trim().toUpperCase();
  if (!/^Q\d+$/.test(id)) return null;

  const payload = await getJson(apiUrl({ action: 'wbgetentities', ids: id, props: 'claims', languages: LANGUAGES }), { deps });
  const entity = payload?.entities?.[id];
  if (!entity) return null;

  const claims = entity.claims ?? {};
  const [characters, places, genres, facts] = await Promise.all([
    labelItems(itemIds(claims, 'P175'), deps),
    labelItems(itemIds(claims, 'P840'), deps),
    labelItems(itemIds(claims, 'P136'), deps),
    labelItems(itemIds(claims, 'P31'), deps),
  ]);

  deps?.log?.debug?.('wikidata: факты серии собраны', { qid: id, characters: characters.length, places: places.length, genres: genres.length });
  return { characters, places, genres, facts };
}

export async function qidLabel(qid, deps = {}) {
  const id = String(qid ?? '').trim().toUpperCase();
  if (!/^Q\d+$/.test(id)) return null;

  const payload = await getJson(apiUrl({ action: 'wbgetentities', ids: id, props: 'labels', languages: LANGUAGES }), { deps });
  const entity = payload?.entities?.[id];
  if (!entity) return null;

  const { en, ru } = labelPair(entity);
  return en || ru ? { en, ru } : null;
}
