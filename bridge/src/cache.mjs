import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Recursive mkdir; an already existing directory is not an error. */
export async function ensureDir(dirPath) {
  await mkdir(path.resolve(dirPath), { recursive: true });
}

/**
 * A cached object is stale when `builtAt` is missing (age cannot be proven) or
 * when it is at least `ttlMs` old. A non-numeric or non-positive `ttlMs` means
 * "no expiry" — the entry is then never stale.
 */
export function isStale(entry, ttlMs) {
  const builtAt = entry?.builtAt;
  if (builtAt === null || builtAt === undefined || !Number.isFinite(Number(builtAt))) return true;
  if (!Number.isFinite(ttlMs) || ttlMs <= 0) return false;
  return Date.now() - Number(builtAt) >= ttlMs;
}

/**
 * Parsed JSON, or `null` for anything unusable: missing, unreadable or corrupt.
 * With a `ttlMs` given, a stale entry also reads back as `null`.
 */
export async function readJson(filePath, ttlMs) {
  let parsed;
  try {
    parsed = JSON.parse(await readFile(filePath, 'utf8'));
  } catch {
    return null;
  }
  if (ttlMs !== undefined && isStale(parsed, ttlMs)) return null;
  return parsed;
}

/**
 * Write through `${filePath}.tmp` and rename over the target, so a crash can
 * never leave a half-written cache behind. A failed write removes the temp file
 * and rethrows.
 */
export async function writeJson(filePath, value) {
  const target = path.resolve(filePath);
  const tmp = `${target}.tmp`;
  await ensureDir(path.dirname(target));
  try {
    await writeFile(tmp, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
    await rename(tmp, target);
  } catch (err) {
    await rm(tmp, { force: true }).catch(() => {});
    throw err;
  }
}
