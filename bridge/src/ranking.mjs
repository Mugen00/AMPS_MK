/**
 * Turn several weak signals into one ranked, explained, honest answer.
 * Pure and synchronous: every byte arrives through `signals`, nothing is
 * fetched here.
 */

const PERSON_LABELS = new Set(['person', 'people', 'face', 'human', 'man', 'woman', 'boy', 'girl', 'crowd', 'portrait']);

const SOURCE_NAMES = { trace: 'trace.moe', sauce: 'SauceNAO', index: 'индекс кадров' };

/**
 * `score` is an agreement sum, not a probability: two agreeing sources plus the
 * independence bonus legitimately land near 1.0, three can exceed it. Only
 * `confidence` is clamped to 0..1.
 */
export const DEFAULT_WEIGHTS = Object.freeze({
  trace: 0.55,
  sauce: 0.26,
  index: 0.24,
  agreement: 0.16,
  agreementMax: 0.32,
});

export const DEFAULT_THRESHOLDS = Object.freeze({
  floor: 0.45,
  identified: 0.7,
  sauceDrop: 0.45,
  sauceOutrank: 0.6,
  sauceConflict: 0.3,
  indexWindow: 60,
  conflictFactor: 0.75,
  labelFactor: 0.7,
  labelTrust: 0.5,
  maxCandidates: 5,
});

const clamp01 = (v) => (Number.isFinite(Number(v)) ? Math.min(1, Math.max(0, Number(v))) : 0);
const round = (v, digits) => Number(Number(v).toFixed(digits));
const percent = (v) => `${(clamp01(v) * 100).toFixed(1)} %`;
const sourceName = (id) => SOURCE_NAMES[id] ?? id;
const normalizeTitle = (value) => String(value ?? '').toLowerCase().replace(/[^a-z0-9а-яё]+/g, ' ').trim();
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const list = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim()) : []);

function anilistIdOf(signal) {
  const raw = signal?.anilistId ?? signal?.anilist_id ?? signal?.anilist?.id;
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : null;
}

function titleAliases(anilist) {
  if (!isObject(anilist)) return [];
  const t = isObject(anilist.title) ? anilist.title : {};
  return [t.english, t.romaji, t.native, ...list(anilist.synonyms)].map((v) => String(v ?? '').trim()).filter(Boolean);
}

function titleOf(signal) {
  return titleAliases(signal)[0] ?? null;
}

/** SauceNAO hits often carry a series name instead of an AniList id. */
function nameMatchesAnilist(hit, titles) {
  const target = normalizeTitle(hit.series || hit.copyright || hit.title);
  if (!target || target.split(' ').length < 3) return false;
  return titles.some((t) => {
    const alias = normalizeTitle(t);
    if (!alias) return false;
    return alias === target || (alias.length >= 8 && alias.includes(target)) || (target.length >= 8 && target.includes(alias));
  });
}

function readTrace(signals) {
  const t = signals?.trace;
  if (!isObject(t) || t.matched === false) return null;
  const titles = titleAliases(t.anilist);
  const anilistId = anilistIdOf(t);
  if (!anilistId && titles.length === 0) return null;
  return { anilistId, similarity: clamp01(t.similarity), episode: t.episode ?? null, timestamp: t.timestamp ?? null, titles, title: titleOf(t.anilist) };
}

function readIndex(signals) {
  const idx = signals?.index;
  if (!isObject(idx) || idx.matched === false) return null;
  const anilistId = anilistIdOf(idx);
  const distance = Number(idx.distance);
  return {
    anilistId,
    episode: idx.episode ?? null,
    timestamp: idx.timestamp ?? null,
    distance: Number.isFinite(distance) ? distance : 0,
  };
}

/** `results` already repeats the best hit at the top level, so prefer it. */
function readSauceHits(signals) {
  const s = signals?.sauce;
  if (!isObject(s) || s.configured === false) return [];
  const rows = Array.isArray(s.results) && s.results.length > 0
    ? s.results
    : [{ similarity: s.similarity, source: s.source, index: s.index, title: s.title, series: s.series, copyright: s.copyright, characters: s.characters, tags: s.tags }];
  return rows
    .filter(isObject)
    .map((row, i) => ({
      order: Number(row.index) || i + 1,
      anilistId: anilistIdOf(row),
      similarity: clamp01(row.similarity),
      title: typeof row.title === 'string' ? row.title : null,
      series: typeof row.series === 'string' ? row.series : null,
      copyright: typeof row.copyright === 'string' ? row.copyright : null,
      characters: list(row.characters),
      tags: list(row.tags),
      linked: false,
      slot: null,
    }))
    .filter((hit) => hit.similarity > 0);
}

function readLabels(signals) {
  const raw = signals?.labels;
  if (!Array.isArray(raw)) return { present: false, person: false, names: [] };
  const names = raw
    .map((row) => (isObject(row) ? { label: String(row.label ?? '').trim(), confidence: Number(row.confidence) } : { label: String(row ?? '').trim(), confidence: 1 }))
    .filter((row) => row.label);
  return { present: names.length > 0, person: false, names };
}

function hasPersonLabel(labels, T) {
  return labels.names.some((row) => PERSON_LABELS.has(row.label.toLowerCase()) && (Number.isFinite(row.confidence) ? row.confidence : 1) >= T.labelTrust);
}

function blankSlot(key) {
  return { key, anilistId: null, titles: [], trace: null, sauce: [], index: null };
}

function closeness(distance, T) {
  const d = Number(distance);
  if (!Number.isFinite(d) || d <= 0) return 1;
  return clamp01(1 - d / T.indexWindow);
}

function scoreSlot(slot, W, T) {
  const parts = [];
  const contribution = {};
  const sources = [];
  let score = 0;

  if (slot.trace) {
    const add = W.trace * slot.trace.similarity;
    score += add;
    sources.push('trace');
    contribution.trace = add;
    const when = slot.trace.episode ? `, серия ${slot.trace.episode}` : '';
    parts.push(`trace.moe: сходство ${percent(slot.trace.similarity)}${when} → вклад ${round(add, 3)}`);
  }

  if (slot.sauce.length > 0) {
    const best = slot.sauce.reduce((a, b) => (b.similarity > a.similarity ? b : a));
    const add = W.sauce * best.similarity * best.similarity;
    score += add;
    sources.push('sauce');
    contribution.sauce = add;
    parts.push(`SauceNAO: сходство ${percent(best.similarity)} → вклад ${round(add, 3)} (затухание: сходство возведено в квадрат)`);
    if (best.characters.length > 0) parts.push(`SauceNAO опознал персонажей: ${best.characters.slice(0, 4).join(', ')}`);
    if (best.linked) parts.push('SauceNAO назван по совпадению названия серии, AniList ID не отдавал');
    const weaker = slot.sauce.length - 1;
    if (weaker > 0) parts.push(`ещё ${weaker} совпадени(й) SauceNAO по тому же тайтлу учтено как одно подтверждение`);
  }

  if (slot.index) {
    const add = W.index * closeness(slot.index.distance, T);
    score += add;
    sources.push('index');
    contribution.index = add;
    parts.push(`индекс кадров: смещение ${slot.index.distance} → вклад ${round(add, 3)}`);
  }

  if (sources.length >= 2) {
    const bonus = Math.min(W.agreementMax, W.agreement * (sources.length - 1));
    score += bonus;
    parts.push(`бонус за независимое подтверждение: ${sources.map(sourceName).join(' + ')} → +${round(bonus, 3)}`);
  }

  const lead = sources.reduce((best, id) => (contribution[id] > (contribution[best] ?? -1) ? id : best), sources[0] ?? null);
  return { score: round(score, 3), sources, parts, lead };
}

function compareCandidates(a, b) {
  if (a.score !== b.score) return b.score - a.score;
  if (a.sources.length !== b.sources.length) return b.sources.length - a.sources.length;
  return String(a.slot.key).localeCompare(String(b.slot.key));
}

/** Rule 2: a SauceNAO hit under 60 % may never overtake a trace.moe/index hit. */
function applyOutrankGuard(ranked, T, reasons, warnings) {
  const top = ranked[0];
  if (!top || top.sources.includes('trace') || top.sources.includes('index')) return;
  const rival = ranked.find((row) => row.sources.includes('trace') || row.sources.includes('index'));
  if (!rival) return;
  const best = top.slot.sauce.reduce((a, b) => (b.similarity > a.similarity ? b : a));
  if (best.similarity >= T.sauceOutrank) return;
  ranked.splice(ranked.indexOf(top), 1);
  ranked.splice(ranked.indexOf(rival), 0, top);
  reasons.push(`SauceNAO (${percent(best.similarity)}) не может обойти ${sourceName(rival.sources.find((s) => s !== 'sauce'))}: сходство ниже ${percent(T.sauceOutrank)} — оставляем его ниже.`);
  warnings.push(`SauceNAO дал ${percent(best.similarity)} и не смог опередить ${sourceName(rival.sources.find((s) => s !== 'sauce'))}.`);
}

function bySimilarityDesc(rows) {
  return [...rows].sort((a, b) => b.similarity - a.similarity);
}

export function rankCandidates(signals = {}, deps = {}) {
  const W = { ...DEFAULT_WEIGHTS, ...(isObject(deps?.weights) ? deps.weights : {}) };
  const T = { ...DEFAULT_THRESHOLDS, ...(isObject(deps?.thresholds) ? deps.thresholds : {}) };
  const reasons = [];
  const warnings = [];
  const debug = (msg, fields) => deps?.log?.debug?.(msg, fields);

  const traceHit = readTrace(signals);
  const indexHit = readIndex(signals);
  const sauceHits = readSauceHits(signals);
  const labels = readLabels(signals);
  labels.person = labels.present ? hasPersonLabel(labels, T) : false;

  if (isObject(signals?.sauce) && signals.sauce.configured === false) {
    warnings.push('SauceNAO не настроен: второго источника нет, подтвердить совпадение нечем.');
  }

  const pool = new Map();
  const slot = (key) => {
    const existing = pool.get(key);
    if (existing) return existing;
    const created = blankSlot(key);
    pool.set(key, created);
    return created;
  };

  const traceSlot = traceHit
    ? slot(traceHit.anilistId ? `id:${traceHit.anilistId}` : `name:${normalizeTitle(traceHit.title)}`)
    : null;
  if (traceSlot && traceHit) {
    traceSlot.anilistId = traceHit.anilistId;
    traceSlot.titles = traceHit.titles;
    traceSlot.trace = traceHit;
  }

  const indexSlot = indexHit
    ? slot(indexHit.anilistId ? `id:${indexHit.anilistId}` : `name:${normalizeTitle(indexHit.episode ?? '')}`)
    : null;
  if (indexSlot && indexHit) {
    indexSlot.anilistId = indexSlot.anilistId ?? indexHit.anilistId;
    indexSlot.index = indexHit;
  }

  const keptHits = [];
  const droppedHits = [];
  for (const hit of sauceHits) {
    if (hit.similarity < T.sauceDrop) {
      droppedHits.push(hit);
      continue;
    }
    if (hit.anilistId) {
      hit.slot = slot(`id:${hit.anilistId}`);
    } else if (traceSlot && nameMatchesAnilist(hit, traceSlot.titles)) {
      hit.linked = true;
      hit.slot = traceSlot;
    } else {
      const name = normalizeTitle(hit.series || hit.copyright || hit.title);
      if (!name) {
        droppedHits.push(hit);
        continue;
      }
      hit.slot = slot(`name:${name}`);
    }
    if (hit.slot.anilistId === null && hit.anilistId) hit.slot.anilistId = hit.anilistId;
    hit.slot.sauce.push(hit);
    keptHits.push(hit);
  }

  const ranked = [...pool.values()]
    .map((entry) => ({ slot: entry, ...scoreSlot(entry, W, T) }))
    .filter((row) => row.score >= T.floor)
    .sort(compareCandidates);
  applyOutrankGuard(ranked, T, reasons, warnings);

  const candidateRows = ranked.slice(0, T.maxCandidates).map((row) => ({
    anilistId: row.slot.anilistId,
    title: row.slot.trace?.title
      ?? row.slot.sauce.reduce((best, hit) => (normalizeTitle(hit.series || hit.title).length > normalizeTitle(best?.series || best?.title).length ? hit : best), null)?.series
      ?? row.slot.sauce[0]?.title
      ?? null,
    score: row.score,
    sources: row.sources,
    why: row.parts,
  }));

  if (droppedHits.length > 0) {
    const listed = bySimilarityDesc(droppedHits)
      .slice(0, 4)
      .map((hit) => percent(hit.similarity))
      .join(', ');
    reasons.push(`SauceNAO вернул ещё ${droppedHits.length} совпадени(й) ниже порога ${percent(T.sauceDrop)} (${listed}) — это шум, они не попали в ответ.`);
    debug('ranking: отброшены слабые совпадения', { count: droppedHits.length, similarities: droppedHits.map((h) => round(h.similarity, 3)) });
  }

  const winner = ranked[0] ?? null;
  const runner = ranked[1] ?? null;

  const conflicts = [];
  if (winner) {
    for (const hit of sauceHits) {
      if (hit.similarity < T.sauceConflict) continue;
      if (hit.slot === winner.slot) continue;
      if (conflicts.some((c) => c.hit === hit)) continue;
      conflicts.push(hit);
    }
  }
  for (const hit of conflicts) {
    const winnerId = winner?.slot.anilistId ?? null;
    const winnerName = winner?.slot.trace?.title ?? winner?.slot.sauce[0]?.series ?? 'другой тайтл';
    const hitName = hit.series || hit.copyright || hit.title || 'без названия';
    warnings.push(
      `Источники не согласны: ${sourceName('trace')} называет AniList ${winnerId ?? winnerName} (${percent(winner.slot.trace?.similarity ?? 0)}), `
      + `а ${sourceName('sauce')} — ${hit.anilistId ? `AniList ${hit.anilistId}` : `"${hitName}"`} (${percent(hit.similarity)}). `
      + `Одно из двух неверно, поэтому выбор не делаем.`,
    );
  }

  const characterScoped = Boolean(winner && winner.slot.sauce.some((hit) => hit.characters.length > 0));
  let labelConflict = false;
  if (labels.present && !labels.person && characterScoped) {
    labelConflict = true;
    warnings.push('В кадре нет ни одного упоминания человека, а совпадение найдено по персонажам — точность снижена.');
    reasons.push(`Метки содержимого (${labels.names.slice(0, 4).map((row) => row.label).join(', ')}) не содержат ни одного человека, хотя совпадение character-scoped — по персонажам. Уверенность снижена до ${Math.round(T.labelFactor * 100)} %.`);
  } else if (labels.present && !labels.person) {
    reasons.push(`Метки содержимого (${labels.names.slice(0, 4).map((row) => row.label).join(', ')}) не содержат человека — совпадение не опровергнуто, но и не подтверждено.`);
  } else if (labels.present) {
    reasons.push('Метки содержимого содержат человека — совпадение по персонажу подтверждено картинкой.');
  } else {
    reasons.push('Метки содержимого не пришли — проверка содержимого не выполнялась.');
  }

  let decision = 'rejected';
  let confidence = 0;
  if (winner) {
    const margin = runner ? clamp01((winner.score - runner.score) / Math.max(winner.score, 1e-6)) : 1;
    confidence = clamp01(winner.score) * (0.75 + 0.25 * margin);
    if (winner.sources.length < 2) confidence = Math.min(confidence, 0.55);
    if (conflicts.length > 0) {
      confidence *= T.conflictFactor;
      decision = 'uncertain';
      reasons.push(`Конфликт источников: ${conflicts.length} совпадени(й) SauceNAO с сходством не ниже ${percent(T.sauceConflict)} указывают на другой тайтл. Ответ не схлопываем в одну догадку, уверенность × ${T.conflictFactor}.`);
    } else if (labelConflict) {
      confidence *= T.labelFactor;
      decision = 'uncertain';
    } else if (winner.score >= T.identified && winner.sources.length >= 2) {
      decision = 'identified';
    } else {
      decision = 'uncertain';
    }
    reasons.push(
      `Лучший кандидат: ${winner.slot.anilistId ? `AniList ${winner.slot.anilistId}` : `"${winner.slot.trace?.title ?? winner.slot.sauce[0]?.series ?? 'без названия'}"`} `
      + `с оценкой ${winner.score} по источникам ${winner.sources.map(sourceName).join(' + ')}; `
      + (runner ? `запас над вторым кандидатом ${round(winner.score - runner.score, 3)}.` : 'второго кандидата в списке нет.'),
    );
    if (winner.sources.length < 2) {
      reasons.push('Источник один — независимого подтверждения нет, даже при высоком сходстве это не «identified».');
    }
  } else {
    const best = [...pool.values()].map((entry) => ({ slot: entry, ...scoreSlot(entry, W, T) })).sort(compareCandidates)[0];
    const anySource = Boolean(traceHit || indexHit || keptHits.length > 0);
    reasons.push(
      anySource
        ? `Ни один кандидат не набрал порог ${round(T.floor, 3)}: лучшая оценка ${best?.score ?? 0}. Сказать «не уверен» честнее, чем назвать не то.`
        : 'Совпадений не было вовсе: trace.moe и индекс кадров молчат, SauceNAO ничего не дал.',
    );
    if (labels.present && !anySource) {
      reasons.push('Одни метки содержимого (sky, building и т.п.) тайтл не называют — источник совпадения всё равно нужен.');
    }
    if (labels.present) reasons.push('Метки содержимого не могут назвать тайтл сами по себе, в ответ они не идут.');
  }

  return {
    decision,
    confidence: round(clamp01(confidence), 3),
    primary: winner
      ? {
        anilistId: winner.slot.anilistId,
        title: candidateRows[0]?.title ?? null,
        source: winner.lead,
        agreement: winner.sources,
      }
      : null,
    candidates: candidateRows,
    reasons,
    warnings,
  };
}
