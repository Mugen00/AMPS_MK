/**
 * Сквозная проверка моста: поднимается сервер, проверяются все маршруты 1.0.2
 * и — обязательно — совместимость ответа `/api/frame/identify` с 1.0.1,
 * потому что старый телефон должен понимать ответ нового моста.
 *
 * Запуск:  node test/bridge-e2e.mjs [база] [кадр.png] [эталон.json]
 */
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const base = process.argv[2] || 'http://127.0.0.1:8787';
const framePath = process.argv[3] || resolve(root, '..', 'demo', 'anime-frame-commons.png');
const goldenPath = process.argv[4] || resolve(root, '..', 'demo', 'bridge-identify.json');

let passed = 0;
let failed = 0;

function check(name, condition, detail = '') {
  if (condition) {
    passed += 1;
    console.log(`PASS  ${name}`);
  } else {
    failed += 1;
    console.log(`FAIL  ${name}${detail ? `  — ${detail}` : ''}`);
  }
}

async function getJson(path) {
  const response = await fetch(`${base}${path}`, { signal: AbortSignal.timeout(120_000) });
  const text = await response.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }
  return { status: response.status, body, text };
}

async function postJson(path, payload) {
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(120_000),
  });
  const text = await response.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }
  return { status: response.status, body, text };
}

const server = spawn(process.execPath, ['src/server.mjs'], { cwd: root, stdio: 'ignore' });
process.on('exit', () => server.kill());

async function waitForHealth(attempts = 40) {
  for (let i = 0; i < attempts; i += 1) {
    try {
      const response = await fetch(`${base}/api/health`, { signal: AbortSignal.timeout(3000) });
      if (response.ok) return true;
    } catch {
      // сервер ещё поднимается
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  return false;
}

try {
  console.log('=== 1. мост поднялся ===');
  check('health отвечает', await waitForHealth());
  const health = await getJson('/api/health');
  check('health отдаёт ключи', Boolean(health.body?.keys), health.text.slice(0, 120));

  console.log('\n=== 2. совместимость с 1.0.1 ===');
  let golden = {};
  try {
    golden = JSON.parse(await readFile(goldenPath, 'utf8'));
  } catch {
    check('эталон 1.0.1 прочитан', false, goldenPath);
  }
  const bytes = await readFile(framePath);
  const identify = await fetch(`${base}/api/frame/identify`, {
    method: 'POST',
    headers: { 'content-type': 'image/png', 'x-filename': 'frame.png' },
    body: bytes,
    signal: AbortSignal.timeout(180_000),
  });
  const identifyBody = await identify.json();
  check('identify отвечает 200', identify.status === 200, `status=${identify.status}`);
  check(
    'trace.moe-поля на месте',
    identifyBody?.trace && 'matched' in identifyBody.trace && 'anilist' in identifyBody.trace,
    JSON.stringify(Object.keys(identifyBody?.trace ?? {})),
  );
  if (golden?.trace?.matched) {
    check(
      'тот же кадр даёт ту же серию, что и в 1.0.1',
      identifyBody?.trace?.anilist?.id === golden.trace.anilist.id,
      `1.0.2=${identifyBody?.trace?.anilist?.id} против 1.0.1=${golden.trace.anilist.id}`,
    );
  }

  console.log('\n=== 3. каталог AniList ===');
  const status = await getJson('/api/catalog/status');
  check('каталог загружен', Number(status.body?.count) > 0, `count=${status.body?.count}`);
  const search = await getJson('/api/catalog/search?q=Cowboy');
  check('поиск по каталогу находит серию', (search.body?.results ?? []).length > 0);
  const badId = await getJson('/api/catalog/anime/abc');
  check('нечисловой id даёт 400, а не 500', badId.status === 400, `status=${badId.status}`);
  const missing = await getJson('/api/catalog/anime/999999999');
  check('несуществующий id даёт 404', missing.status === 404, `status=${missing.status}`);

  console.log('\n=== 4. фандом-вики: персонажи и места ===');
  const bebop = await getJson('/api/catalog/anime/1');
  check('Cowboy Bebop: вики найдена', Boolean(bebop.body?.wiki?.slug), JSON.stringify(bebop.body?.wiki));
  check(
    'Cowboy Bebop: имена персонажей настоящие',
    (bebop.body?.characters ?? []).some((c) => ['Ed', 'Ein', 'Faye Valentine', 'Jet Black'].includes(c.name)),
    (bebop.body?.characters ?? []).slice(0, 6).map((c) => c.name).join(', '),
  );
  check(
    'Cowboy Bebop: места настоящие',
    (bebop.body?.places ?? []).some((p) => ['Earth', 'Ganymede', 'Mars'].includes(p.name)),
    (bebop.body?.places ?? []).slice(0, 6).map((p) => p.name).join(', '),
  );
  check(
    'в списке нет служебных подстраниц',
    !(bebop.body?.characters ?? []).some((c) => /\/(gallery|plot|images)$/i.test(c.name ?? '')),
  );
  check('ссылка на вики присутствует', typeof bebop.body?.wiki?.url === 'string');

  console.log('\n=== 5. ранжирование кандидатов ===');
  const ranked = await postJson('/api/rank', {
    trace: { matched: true, anilistId: 9253, episode: 12, timestamp: 300.5, similarity: 0.96 },
    sauce: { configured: true, similarity: 0.8, source: 'danbooru', characters: ['Okabe Rintaro'], tags: [] },
    labels: [{ label: 'Person', confidence: 0.9 }],
  });
  check('rank отвечает 200', ranked.status === 200, `status=${ranked.status}`);
  check('вердикт известен', ['identified', 'uncertain', 'rejected'].includes(ranked.body?.decision), JSON.stringify(ranked.body?.decision));
  check('уверенность в пределах 0..1', ranked.body?.confidence >= 0 && ranked.body?.confidence <= 1, String(ranked.body?.confidence));
  check('есть объяснение', (ranked.body?.reasons ?? []).length > 0);
  check('метки не решают за источники', !/label/i.test(ranked.body?.reasons?.join(' ') ?? '') || ranked.body?.decision !== 'identified' || (ranked.body?.primary?.agreement ?? []).length >= 2);

  const empty = await postJson('/api/rank', {});
  check('пустой запрос отвечает rejected', empty.body?.decision === 'rejected', JSON.stringify(empty.body?.decision));

  console.log('\n=== 6. индекс кадров ===');
  const stats = await getJson('/api/frame/index/stats');
  check('статистика индекса отвечает', stats.status === 200, `status=${stats.status}`);
  const badHash = await getJson('/api/frame/index/match?hash=zzz');
  check('кривой хеш даёт 400', badHash.status === 400, `status=${badHash.status}`);
  const miss = await getJson('/api/frame/index/match?hash=' + '0'.repeat(16));
  check('чужой кадр не считается совпадением', miss.body?.matched === false, JSON.stringify(miss.body));
  const added = await postJson('/api/frame/index/add', {
    hash: 'f8dc8cddc1553233',
    anilistId: 9253,
    seriesTitle: 'Steins;Gate',
    episode: 12,
    timestampSec: 300.5,
    source: 'e2e',
  });
  check('кадр принят в индекс', added.status === 200 || added.status === 201, `status=${added.status} ${added.text.slice(0, 120)}`);
  const hit = await getJson('/api/frame/index/match?hash=f8dc8cddc1553233');
  check('тот же кадр теперь находится', hit.body?.matched === true && hit.body?.entry?.anilistId === 9253, JSON.stringify(hit.body));

  console.log('\n=== 7. обратная совместимость маршрутов ===');
  check('неизвестный путь даёт 404', (await getJson('/api/nope')).status === 404);
  check('health не сломан после всего', (await getJson('/api/health')).status === 200);
} catch (error) {
  failed += 1;
  console.log(`FAIL  непойманная ошибка: ${error?.message ?? error}`);
} finally {
  server.kill();
}

console.log(`\n${failed === 0 ? 'ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ' : 'ЕСТЬ ПРОВАЛЫ'}: ${passed} ок, ${failed} нет`);
process.exit(failed === 0 ? 0 : 1);
