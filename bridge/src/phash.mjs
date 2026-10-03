/**
 * 64-bit difference hash (dHash) — the cross-language contract with the Kotlin
 * implementation on the phone. Both sides must produce identical bits.
 *
 * The algorithm, exactly:
 *   1. decode the image to RGB;
 *   2. scale to 9x8 by area averaging — every output cell is the mean of the
 *      source pixels it covers, fractional coverage weighted by area;
 *   3. greyscale with the integer luma weights R*77 + G*150 + B*29, divided by
 *      256 and floored (0..255);
 *   4. bit `y * 8 + x` (row major, MSB first) = 1 when cell(x, y) > cell(x+1, y),
 *      else 0 — so a left-light / right-dark scene hashes to ffffffffffffffff
 *      and a left-dark / right-light scene hashes to 0000000000000000;
 *   5. the 64 bits are printed as 16 lowercase hex characters, most
 *      significant bit first, as one 64-bit integer.
 *
 * Two frames match when their Hamming distance is <= 10 of 64.
 *
 * Points the specification leaves open, decided once here and mirrored in the
 * Kotlin port (see bridge/README.md):
 *   * the mean of a cell is exact rational arithmetic over the fractional
 *     coverage weights, rounded half up to an integer channel, never truncated;
 *   * alpha is composited over black, rounded half up: `round(c * a / 255)`;
 *   * 16-bit samples keep their high byte (PNG only).
 */

import { decodePng, isPng, PngDecodeError } from './png-decode.mjs';
import { decodeJpeg, isJpeg, JpegDecodeError } from './jpeg-decode.mjs';

/** Size of the comparison grid the hash is built on. */
export const HASH_GRID = { width: 9, height: 8 };

/** Default match tolerance: 10 of 64 bits. */
export const DEFAULT_MAX_DISTANCE = 10;

export class ImageDecodeError extends Error {
  constructor(message, format = null) {
    super(message);
    this.name = 'ImageDecodeError';
    this.format = format;
    this.code = 'image_decode_failed';
  }
}

/** Human-readable name of each PNG colour type, mirroring the JPEG models. */
const PNG_COLOUR_MODEL = { 0: 'grey', 2: 'rgb', 3: 'palette', 4: 'grey+alpha', 6: 'rgba' };

/** Sniff the container; `mimeType` is only a hint, magic bytes decide. */
export function decodeImage(bytes, mimeType = '') {
  const buf = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes ?? []);
  if (buf.length === 0) throw new ImageDecodeError('empty image: nothing to hash');
  if (isPng(buf)) {
    const image = decodePng(buf);
    return { ...image, colourModel: PNG_COLOUR_MODEL[image.colourType] ?? `colour-type-${image.colourType}` };
  }
  if (isJpeg(buf)) return decodeJpeg(buf);
  const hint = String(mimeType ?? '').toLowerCase().split(';')[0].trim();
  const what = hint ? `${hint} ` : '';
  throw new ImageDecodeError(`unsupported image format: ${what}file is neither PNG nor baseline JPEG`);
}

/**
 * Exact area-average downscale to 9x8.
 *
 * Each output cell covers exactly `width / 9` by `height / 8` source pixels.
 * Scaled by the denominators the overlaps are integers: the x coverage of cell
 * `ox` over source pixel `sx`, times 9, is `nx = min((ox+1)*W, (sx+1)*9) -
 * max(ox*W, sx*9)` and likewise `ny` for y with 8 instead of 9. The weighted
 * sum `N = sum(channel * nx * ny)` is therefore an exact integer multiple of the
 * true mean: the weights of one cell add up to exactly `W * H`, so the mean is
 * `N / (W * H)` — the same rational value as normalising the weights so that a
 * cell sums to 72 and dividing by 72, only expressed in integers instead of
 * floating point. It is rounded half up, never truncated, so Java and
 * JavaScript agree bit for bit.
 *
 * @param {{width:number,height:number,data:Uint8Array}} image RGB, 3 bytes per pixel
 * @param {{width:number,height:number}} [size] target grid, 9x8 by default
 * @returns {{width:number,height:number,data:Uint8Array}} RGB grid
 */
export function areaAverage(image, size = HASH_GRID) {
  const { width, height, data } = image;
  const outWidth = size.width;
  const outHeight = size.height;
  if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0) {
    throw new ImageDecodeError(`invalid image size ${width}x${height}`);
  }
  if (data.length < width * height * 3) throw new ImageDecodeError('pixel buffer is shorter than the declared size');

  // Per-axis coverage weights, precomputed once.
  const xWeights = buildAxisWeights(width, outWidth);
  const yWeights = buildAxisWeights(height, outHeight);
  const cellWeight = width * height; // sum of nx * ny over one cell, exactly

  const out = new Uint8Array(outWidth * outHeight * 3);
  for (let oy = 0; oy < outHeight; oy++) {
    const ys = yWeights[oy];
    for (let ox = 0; ox < outWidth; ox++) {
      const xs = xWeights[ox];
      let r = 0;
      let g = 0;
      let b = 0;
      for (let yi = 0; yi < ys.length; yi++) {
        const sy = ys[yi].index;
        const ny = ys[yi].weight;
        const rowBase = sy * width;
        for (let xi = 0; xi < xs.length; xi++) {
          const weight = xs[xi].weight * ny;
          const p = (rowBase + xs[xi].index) * 3;
          r += data[p] * weight;
          g += data[p + 1] * weight;
          b += data[p + 2] * weight;
        }
      }
      const o = (oy * outWidth + ox) * 3;
      out[o] = roundHalfUp(r, cellWeight);
      out[o + 1] = roundHalfUp(g, cellWeight);
      out[o + 2] = roundHalfUp(b, cellWeight);
    }
  }
  return { width: outWidth, height: outHeight, data: out };
}

/** Coverage weights of one axis: `[{ index, weight }]` per output cell. */
function buildAxisWeights(sourceSize, outSize) {
  const perCell = sourceSize / outSize; // exact rational source pixels per output cell
  const weights = [];
  for (let o = 0; o < outSize; o++) {
    const start = o * perCell;
    const end = start + perCell;
    const first = Math.floor(start);
    const last = Math.min(sourceSize, Math.ceil(end));
    const entries = [];
    for (let s = first; s < last; s++) {
      // overlap times the denominator, always an integer
      const overlap = Math.min(end, s + 1) - Math.max(start, s);
      if (overlap <= 0) continue;
      entries.push({ index: s, weight: Math.round(overlap * outSize) });
    }
    weights.push(entries);
  }
  return weights;
}

/** Exact half-up rounding of `numerator / denominator` (both integers). */
function roundHalfUp(numerator, denominator) {
  const value = (2 * numerator + denominator) / (2 * denominator);
  return value >= 255 ? 255 : value <= 0 ? 0 : Math.floor(value);
}

/** Luma: R*77 + G*150 + B*29, divided by 256 and floored (weights sum to 256). */
export function lumaToByte(r, g, b) {
  return Math.floor((r * 77 + g * 150 + b * 29) / 256);
}

/**
 * The 8 rows of 9 luma cells the bits are compared in (`y * 9 + x`), because
 * the last comparison of a row needs the ninth cell. Keeping all 72 values is
 * deliberate: dropping the ninth makes `cell(7, y)` compare against `cell(0, y+1)`
 * and flips one bit per row.
 */
export function lumaCells(image) {
  const grid = areaAverage(image, HASH_GRID);
  const cells = new Uint8Array(8 * 9);
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 9; x++) {
      const p = (y * 9 + x) * 3;
      cells[y * 9 + x] = lumaToByte(grid.data[p], grid.data[p + 1], grid.data[p + 2]);
    }
  }
  return cells;
}

/** Pack the 8x9 grid of cells into the 64-bit hash the way the contract says. */
export function cellsToBits(cells) {
  let value = 0n;
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      value = (value << 1n) | (cells[y * 9 + x] > cells[y * 9 + x + 1] ? 1n : 0n);
    }
  }
  return value;
}

/**
 * Perceptual hash of raw image bytes: PNG or baseline JPEG in, 16 hex
 * characters out. Anything else throws — a wrong hash is worse than none.
 *
 * @param {Buffer|Uint8Array} bytes raw file bytes
 * @param {string} [mimeType] optional hint (`image/png`, `image/jpeg`)
 * @returns {string} 16 lowercase hex characters
 */
export function hashFrame(bytes, mimeType = '') {
  return bitsToHex(hashValue(decodeImage(bytes, mimeType)));
}

/** Full pipeline returning the BigInt, for callers that want to compare cheaply. */
export function hashValue(image) {
  return cellsToBits(lumaCells(image));
}

/** 64-bit BigInt -> 16 lowercase hex characters, most significant bit first. */
export function bitsToHex(value) {
  return value.toString(16).padStart(16, '0');
}

/** Parse 16 hex characters into a BigInt; throws on anything malformed. */
export function hexToBits(hex) {
  const text = String(hex ?? '').trim();
  if (!/^[0-9a-fA-F]{16}$/.test(text)) throw new ImageDecodeError(`malformed hash: ${JSON.stringify(String(hex ?? '')).slice(0, 40)}`);
  return BigInt(`0x${text.toLowerCase()}`);
}

export { decodePng, decodeJpeg, isPng, isJpeg, PngDecodeError, JpegDecodeError };