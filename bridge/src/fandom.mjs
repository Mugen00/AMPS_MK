/**
 * Fandom wiki access. Slug discovery is Wikidata P8080 first, then a hyphenated
 * probe of `<slug>.fandom.com/api.php` — the Community API is 403 and is never
 * called. A dead wiki answers `null`, never an exception.
 */

const WIKIDATA_API = 'https://www.wikidata.org/w/api.php';
const USER_AGENT = 'amps-bridge/1.0 (https://github.com/amps) node-fetch';
const DEFAULT_TIMEOUT_MS = 15000;
const FANDOM_PROPERTY = 'P8080';
const MAX_IMAGES = 50;
const IMAGE_WIDTH = 500;
const SEARCH_LIMIT = 10;
const CATEGORY_LIMIT = 200;
const NOCOOOK = 'static.wikia.nocookie.net';
const DIACRITICS = /[\u0300-\u036f]/g;

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const slugPattern = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/;

function apiUrl(slug, params) {
  return `https://${slug}.fandom.com/api.php?${new URLSearchParams({ format: 'json', ...params })}`;
}

function budgetOf(deps, timeoutMs) {
  if (Number.isFinite(timeoutMs)) return timeoutMs;
  const configured = Number(deps?.requestTimeoutMs);
  return Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_TIMEOUT_MS;
}

/** Answers `{ status, json }`; `null` only when the transport itself failed. */
async function request(url, { deps, timeoutMs, retry = 1 } = {}) {
  const budget = budgetOf(deps, timeoutMs);
  for (let attempt = 0; attempt <= retry; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), budget);
    try {
      const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' }, signal: controller.signal });
      if (res.status !== 200) {
        deps?.log?.debug?.('fandom: не 200', { url, status: res.status });
        return { status: res.status, json: null };
      }
      return { status: 200, json: await res.json() };
    } catch (err) {
      deps?.log?.debug?.('fandom: запрос не удался', { url, attempt, error: String(err?.message ?? err) });
    } finally {
      clearTimeout(timer);
    }
  }
  return null;
}

function normalizeSlug(value) {
  const raw = String(value ?? '').trim();
  if (!raw) return null;
  const host = /^https?:\/\/([^/]+)/i.exec(raw)?.[1] ?? raw;
  const slug = host.replace(/\.fandom\.com$/i, '').trim().toLowerCase();
  return slugPattern.test(slug) ? slug : null;
}

/** "Heroine Tarumono! ~Kiraware~" → "heroine-tarumono-kiraware". */
function slugFromTitle(title) {
  const ascii = String(title ?? '')
    .normalize('NFKD')
    .replace(DIACRITICS, '')
    .toLowerCase();
  const slug = ascii.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return slugPattern.test(slug) ? slug : null;
}

const ENTITIES = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#039;': "'", '&apos;': "'", '&nbsp;': ' ' };

function stripHtml(value) {
  return String(value ?? '')
    .replace(/<\s*(script|style)[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&#(\d+);/g, (m, code) => String.fromCodePoint(Number(code)))
    .replace(/&[a-z#0-9]+;/gi, (m) => ENTITIES[m.toLowerCase()] ?? ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function pageUrl(slug, title) {
  return `https://${slug}.fandom.com/wiki/${encodeURIComponent(String(title ?? '').trim().replace(/\s+/g, '_'))}`;
}

/** Same file served from two hosts is one picture: keep the CDN copy. */
function imageKey(url) {
  const parsed = new URL(url);
  const tail = parsed.pathname.replace(/^\/(commons|images)\//, '');
  return `${parsed.search}${tail.toLowerCase()}`;
}

function collectImages(page) {
  const byKey = new Map();
  const push = (url) => {
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return;
    const key = imageKey(url);
    const known = byKey.get(key);
    const cdn = url.includes(NOCOOOK);
    if (!known || (cdn && !known.cdn)) byKey.set(key, { url, cdn });
  };
  for (const image of Array.isArray(page?.images) ? page.images : []) {
    const info = Array.isArray(image?.imageinfo) ? image.imageinfo[0] : null;
    push(info?.thumburl ?? info?.url);
  }
  push(page?.thumbnail?.source);
  push(page?.original?.source);
  return [...byKey.values()].map((entry) => entry.url).slice(0, MAX_IMAGES);
}

async function fandomSlugFromQid(qid, deps) {
  const id = String(qid ?? '').trim().toUpperCase();
  if (!/^Q\d+$/.test(id)) return null;
  const url = `${WIKIDATA_API}?${new URLSearchParams({ action: 'wbgetentities', ids: id, props: 'claims', format: 'json' })}`;
  const res = await request(url, { deps });
  const claims = res?.json?.entities?.[id]?.claims;
  for (const statement of claims?.[FANDOM_PROPERTY] ?? []) {
    const value = statement?.mainsnak?.datavalue?.value;
    const slug = normalizeSlug(typeof value === 'string' ? value : '');
    if (slug) return slug;
  }
  return null;
}

function slugFromFacts(facts) {
  if (!isObject(facts)) return null;
  return normalizeSlug(facts.fandomSlug ?? facts.fandom ?? facts[FANDOM_PROPERTY] ?? facts.p8080 ?? '');
}

async function wikiExists(slug, deps) {
  const res = await request(apiUrl(slug, { action: 'query', meta: 'siteinfo' }), { deps });
  if (res?.status === 200 && isObject(res.json?.query)) return true;
  deps?.log?.debug?.('fandom: вики не отвечает', { slug, status: res?.status ?? null });
  return false;
}

export async function resolveFandomSlug(title, qidOrFacts, deps = {}) {
  const candidates = [];
  const fromFacts = slugFromFacts(qidOrFacts);
  if (fromFacts) candidates.push({ slug: fromFacts, from: 'facts' });
  else {
    const fromQid = await fandomSlugFromQid(isObject(qidOrFacts) ? qidOrFacts.qid : qidOrFacts, deps);
    if (fromQid) candidates.push({ slug: fromQid, from: 'P8080' });
  }
  const guessed = slugFromTitle(title);
  if (guessed) candidates.push({ slug: guessed, from: 'title' });

  const tried = new Set();
  for (const candidate of candidates) {
    if (tried.has(candidate.slug)) continue;
    tried.add(candidate.slug);
    if (await wikiExists(candidate.slug, deps)) {
      deps?.log?.info?.('fandom: вики найдена', { slug: candidate.slug, via: candidate.from });
      return candidate.slug;
    }
  }
  deps?.log?.info?.('fandom: вики не найдена', { title: String(title ?? ''), tried: [...tried] });
  return null;
}

export async function fetchFandomPage(slug, title, deps = {}) {
  const wiki = normalizeSlug(slug);
  const page = String(title ?? '').trim();
  if (!wiki || !page) return null;

  const res = await request(apiUrl(wiki, {
    action: 'query',
    prop: 'extracts|images|pageimages',
    titles: page,
    explaintext: '1',
    exintro: '1',
    imlimit: String(MAX_IMAGES),
    iiprop: 'url|size',
    iiurlwidth: String(IMAGE_WIDTH),
    redirects: '1',
    origin: '*',
  }), { deps });
  if (!res || res.status !== 200 || !isObject(res.json?.query?.pages)) return null;

  const pages = Object.values(res.json.query.pages);
  const found = pages.find((entry) => isObject(entry) && entry.missing === undefined) ?? null;
  if (!found) {
    deps?.log?.info?.('fandom: страница не найдена', { slug: wiki, title: page });
    return null;
  }

  return {
    url: pageUrl(wiki, found.title ?? page),
    intro: stripHtml(found.extract),
    images: collectImages(found),
  };
}

export async function searchFandomPage(slug, query, deps = {}) {
  const wiki = normalizeSlug(slug);
  const text = String(query ?? '').trim();
  if (!wiki || !text) return null;

  const res = await request(apiUrl(wiki, {
    action: 'query',
    list: 'search',
    srsearch: text,
    srlimit: String(SEARCH_LIMIT),
    srnamespace: '0',
    origin: '*',
  }), { deps });
  if (!res || res.status !== 200) return null;

  const rows = Array.isArray(res.json?.query?.search) ? res.json.query.search : null;
  if (!rows) return null;

  const out = [];
  const seen = new Set();
  for (const row of rows) {
    const title = typeof row?.title === 'string' ? row.title : null;
    if (!title || seen.has(title)) continue;
    seen.add(title);
    out.push({ title, url: pageUrl(wiki, title), snippet: stripHtml(row.snippet) });
  }
  return out.length > 0 ? out : null;
}

/**
 * Персонажи и места прямо из вики. У Fandom они лежат в категориях, и их названия
 * различаются от вики к вики («Characters», «Main Characters», «Locations»,
 * «Places», «Setting»…). Поэтому категории сначала перечисляются, а потом
 * отбираются по имени — жёстко зашитых имён тут не бывает.
 */
const CHARACTER_CATEGORY = /character|main|supporting|cast/i;
const PLACE_CATEGORY = /location|place|setting|world|town|city|country|school|geography/i;
const IGNORED_CATEGORY = /category|page|talk|user|template|wiki|help|portal|episode|release|staff|theme|music|edit/i;

/** Все категории вики, кроме служебных. */
export async function listFandomCategories(slug, deps = {}) {
  const wiki = normalizeSlug(slug);
  if (!wiki) return null;

  const res = await request(apiUrl(wiki, {
    action: 'query',
    list: 'allcategories',
    aclimit: String(CATEGORY_LIMIT),
    acprop: 'size',
    origin: '*',
  }), { deps });
  if (!res || res.status !== 200) return null;

  const rows = Array.isArray(res.json?.query?.allcategories) ? res.json.query.allcategories : [];
  const out = [];
  for (const row of rows) {
    // MediaWiki отдаёт имя категории под буквальным ключом "*", поэтому точечный
    // доступ здесь недопустим — только скобочный.
    const name = typeof row?.['*'] === 'string' ? row['*'] : null;
    if (!name || IGNORED_CATEGORY.test(name)) continue;
    out.push(name);
    if (out.length >= CATEGORY_LIMIT) break;
  }
  return out.length > 0 ? out : null;
}

/** Страницы одной категории: имена персонажей, названия мест, всё что в них лежит. */
export async function fandomCategoryMembers(slug, category, limit = 40, deps = {}) {
  const wiki = normalizeSlug(slug);
  const name = String(category ?? '').trim();
  if (!wiki || !name) return null;

  const res = await request(apiUrl(wiki, {
    action: 'query',
    list: 'categorymembers',
    cmtitle: `Category:${name}`,
    cmlimit: String(Math.min(Math.max(Number(limit) || 40, 1), 100)),
    cmnamespace: '0',
    origin: '*',
  }), { deps });
  if (!res || res.status !== 200) return null;

  const rows = Array.isArray(res.json?.query?.categorymembers) ? res.json.query.categorymembers : [];
  const out = [];
  const seen = new Set();
  for (const row of rows) {
    const title = typeof row?.title === 'string' ? row.title : null;
    if (!title || seen.has(title)) continue;
    // Служебные страницы вида «Category:.../Images» внутри категории — мусор.
    if (/^(category|file|template|portal|help|user|wikipedia):/i.test(title)) continue;
    // Подстраницы вида «Kurisu Makise/Gallery» или «Itaru Hashida/Plot» — это не
    // персонаж, а раздел внутри его страницы. В списке персонажей им самое место
    // не быть, а сам персонаж уже лежит в категории отдельной страницей.
    if (/\/(gallery|plot|images?|trivia|quotes?|history|references?|behind the scenes|screenshots?|episodes?|appearances?|voice|staff|development|themes?|music|relations?|family|background|notes?|spoilers?|timeline|manga|novel)$/i.test(title)) continue;
    seen.add(title);
    out.push({ name: title, url: pageUrl(wiki, title) });
  }
  return out.length > 0 ? out : null;
}

/**
 * Персонажи и места серии по её вики. Пустые части просто отсутствуют в ответе:
 * не у каждой вики есть отдельная категория мест.
 */
export async function fandomRoster(slug, title, deps = {}) {
  const wiki = normalizeSlug(slug);
  if (!wiki) return { characters: [], places: [] };

  const categories = await listFandomCategories(wiki, deps).catch(() => null);
  if (!categories) return { characters: [], places: [] };

  const characterCategories = categories.filter((c) => CHARACTER_CATEGORY.test(c) && !PLACE_CATEGORY.test(c)).slice(0, 3);
  const placeCategories = categories.filter((c) => PLACE_CATEGORY.test(c)).slice(0, 2);

  const collect = async (names) => {
    const out = [];
    const seen = new Set();
    for (const name of names) {
      const members = await fandomCategoryMembers(wiki, name, 40, deps).catch(() => null);
      for (const member of members ?? []) {
        if (seen.has(member.name)) continue;
        seen.add(member.name);
        out.push(member);
      }
    }
    return out.slice(0, 60);
  };

  const [characters, places] = await Promise.all([collect(characterCategories), collect(placeCategories)]);
  return { characters, places, categories };
}
