/**
 * PNG decoder — Node standard library only (`node:zlib` + `node:buffer`).
 *
 * Supported: all five colour types (grey, RGB, palette, grey+alpha, RGBA),
 * bit depths 1/2/4/8/16, both interlace methods (none and Adam7), all five
 * zlib filter types, and `tRNS` transparency.
 *
 * Everything else is rejected with a `PngDecodeError` instead of being guessed
 * at: a silently wrong pixel buffer would produce a silently wrong hash, and a
 * wrong hash in the frame index is worse than no hash at all.
 *
 * 16-bit samples are reduced by keeping the high byte (documented extension;
 * the perceptual hash contract is defined for 8-bit sources).
 *
 * Output: `{ width, height, data }` where `data` is `width * height * 3` bytes
 * of 8-bit RGB, three channels interleaved, top-left origin, row-major.
 */

import zlib from 'node:zlib';

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** Defensive ceiling; real phone screenshots are ~2 MP. */
const MAX_PIXELS = 64 * 1024 * 1024;

const CHANNELS_BY_COLOUR_TYPE = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 };

/** Adam7 pass geometry, in the order defined by the PNG specification. */
const ADAM7 = [
  { xStart: 0, yStart: 0, xStep: 8, yStep: 8 },
  { xStart: 4, yStart: 0, xStep: 8, yStep: 8 },
  { xStart: 0, yStart: 4, xStep: 8, yStep: 8 },
  { xStart: 2, yStart: 0, xStep: 4, yStep: 8 },
  { xStart: 0, yStart: 2, xStep: 4, yStep: 4 },
  { xStart: 1, yStart: 0, xStep: 2, yStep: 4 },
  { xStart: 0, yStart: 1, xStep: 2, yStep: 2 },
];

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

export class PngDecodeError extends Error {
  constructor(message) {
    super(message);
    this.name = 'PngDecodeError';
    this.format = 'png';
    this.code = 'image_decode_failed';
  }
}

export function isPng(bytes) {
  const buf = toBuffer(bytes);
  return buf.length >= 8 && buf.subarray(0, 8).equals(PNG_SIGNATURE);
}

function toBuffer(bytes) {
  if (Buffer.isBuffer(bytes)) return bytes;
  if (bytes instanceof Uint8Array) return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  throw new PngDecodeError('expected a Buffer or Uint8Array of PNG bytes');
}

/** Standard Paeth predictor used by filter type 4. */
function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/**
 * Reverse one scanline's filter into `out` (which must hold `bytesPerLine`
 * bytes) and return the offset of the next scanline in `raw`.
 *
 * Rows are unfiltered one at a time on purpose: a scratch buffer big enough for
 * one row is all we ever allocate, and out-of-bounds writes into a typed array
 * are silently dropped — which would quietly corrupt every row but the first.
 */
function unfilterRow(raw, src, bytesPerLine, bytesPerPixel, out, priorRow) {
  if (src + 1 + bytesPerLine > raw.length) throw new PngDecodeError('compressed stream is shorter than the image needs');
  const filter = raw[src];
  const line = raw.subarray(src + 1, src + 1 + bytesPerLine);
  for (let i = 0; i < bytesPerLine; i++) {
    const x = line[i];
    const a = i >= bytesPerPixel ? out[i - bytesPerPixel] : 0;
    const b = priorRow && i < priorRow.length ? priorRow[i] : 0;
    const c = priorRow && i >= bytesPerPixel ? priorRow[i - bytesPerPixel] : 0;
    let value;
    switch (filter) {
      case 0: value = x; break;
      case 1: value = x + a; break;
      case 2: value = x + b; break;
      case 3: value = x + ((a + b) >> 1); break;
      case 4: value = x + paeth(a, b, c); break;
      default: throw new PngDecodeError(`unknown filter type ${filter} on scanline at offset ${src}`);
    }
    out[i] = value & 0xff;
  }
  return src + 1 + bytesPerLine;
}

/** Read packed samples (bit depths 1/2/4) or byte samples out of one row. */
function readSamples(row, passWidth, bitDepth, channels, dst, dstOffset) {
  if (bitDepth >= 8) {
    const step = bitDepth === 16 ? 2 : 1;
    for (let x = 0; x < passWidth; x++) {
      for (let c = 0; c < channels; c++) {
        dst[dstOffset + x * channels + c] = row[(x * channels + c) * step];
      }
    }
    return;
  }
  const mask = (1 << bitDepth) - 1;
  for (let x = 0; x < passWidth; x++) {
    const byte = row[x >> 3];
    const shift = 8 - bitDepth - (x & 7) * bitDepth;
    dst[dstOffset + x] = (byte >> shift) & mask;
  }
}

function readHeader(data) {
  if (data.length < 13) throw new PngDecodeError('IHDR chunk is too short');
  const width = data.readUInt32BE(0);
  const height = data.readUInt32BE(4);
  const bitDepth = data[8];
  const colourType = data[9];
  const compression = data[10];
  const filterMethod = data[11];
  const interlace = data[12];

  if (width === 0 || height === 0) throw new PngDecodeError(`invalid image size ${width}x${height}`);
  if (width * height > MAX_PIXELS) throw new PngDecodeError(`image is too large: ${width}x${height}`);
  if (!(colourType in CHANNELS_BY_COLOUR_TYPE)) throw new PngDecodeError(`unsupported colour type ${colourType}`);
  if (![1, 2, 4, 8, 16].includes(bitDepth)) throw new PngDecodeError(`unsupported bit depth ${bitDepth}`);
  if (compression !== 0) throw new PngDecodeError(`unsupported compression method ${compression}`);
  if (filterMethod !== 0) throw new PngDecodeError(`unsupported filter method ${filterMethod}`);
  if (interlace !== 0 && interlace !== 1) throw new PngDecodeError(`unsupported interlace method ${interlace}`);
  if (bitDepth < 8 && colourType !== 0 && colourType !== 3) {
    throw new PngDecodeError(`bit depth ${bitDepth} is only valid for greyscale and palette images`);
  }
  if (bitDepth === 16 && colourType === 3) throw new PngDecodeError('16-bit palette images do not exist');
  return { width, height, bitDepth, colourType, interlace, channels: CHANNELS_BY_COLOUR_TYPE[colourType] };
}

function parseTransparency(colourType, data) {
  switch (colourType) {
    case 0:
      return data.length >= 2 ? { grey: data.readUInt16BE(0) } : null;
    case 2:
      return data.length >= 6 ? { r: data.readUInt16BE(0), g: data.readUInt16BE(2), b: data.readUInt16BE(4) } : null;
    case 3: {
      const alpha = new Uint8Array(data.length);
      for (let i = 0; i < data.length; i++) alpha[i] = data[i];
      return { alpha };
    }
    default:
      return null; // colour types 4 and 6 carry alpha per pixel already
  }
}

/** Samples (`channels` bytes per pixel, one per source channel) -> RGB, alpha over black. */
function toRgb(header, samples, palette, transparency) {
  const { width, height, bitDepth, colourType, channels } = header;
  const out = new Uint8Array(width * height * 3);
  const total = width * height;
  for (let p = 0; p < total; p++) {
    let r;
    let g;
    let b;
    let a = 255;
    const s = p * channels;
    switch (colourType) {
      case 0: {
        r = g = b = samples[s];
        if (transparency?.grey !== undefined && samples[s] === (transparency.grey >> 8)) a = 0;
        break;
      }
      case 2:
        r = samples[s];
        g = samples[s + 1];
        b = samples[s + 2];
        if (transparency && r === (transparency.r >> 8) && g === (transparency.g >> 8) && b === (transparency.b >> 8)) a = 0;
        break;
      case 3: {
        const idx = samples[s];
        if (!palette || idx * 3 + 2 >= palette.length) throw new PngDecodeError(`palette index ${idx} is out of range`);
        r = palette[idx * 3];
        g = palette[idx * 3 + 1];
        b = palette[idx * 3 + 2];
        if (transparency?.alpha && idx < transparency.alpha.length) a = transparency.alpha[idx];
        break;
      }
      case 4:
        r = g = b = samples[s];
        a = samples[s + 1];
        break;
      default:
        r = samples[s];
        g = samples[s + 1];
        b = samples[s + 2];
        a = samples[s + 3];
        break;
    }
    const o = p * 3;
    if (a === 255) {
      out[o] = r;
      out[o + 1] = g;
      out[o + 2] = b;
    } else {
      // Composite over black so a transparent PNG still has defined pixels.
      out[o] = Math.round((r * a) / 255);
      out[o + 1] = Math.round((g * a) / 255);
      out[o + 2] = Math.round((b * a) / 255);
    }
  }
  return { width, height, data: out, source: 'png', bitDepth, colourType };
}

/**
 * Decode a PNG into RGB bytes.
 * @param {Buffer|Uint8Array} input raw file bytes
 * @returns {{width:number,height:number,data:Uint8Array,source:string,bitDepth:number,colourType:number}}
 */
export function decodePng(input) {
  const buf = toBuffer(input);
  if (!isPng(buf)) throw new PngDecodeError('not a PNG: signature mismatch');

  let header = null;
  let palette = null;
  let transparency = null;
  const idat = [];
  let offset = 8;
  let sawEnd = false;

  while (offset + 8 <= buf.length && !sawEnd) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString('latin1', offset + 4, offset + 8);
    const dataStart = offset + 8;
    const dataEnd = dataStart + length;
    if (length > buf.length || dataEnd + 4 > buf.length) throw new PngDecodeError(`truncated ${type} chunk`);
    const expected = buf.readUInt32BE(dataEnd);
    if (crc32(buf.subarray(offset + 4, dataEnd)) !== expected) throw new PngDecodeError(`CRC mismatch in ${type} chunk`);
    const data = buf.subarray(dataStart, dataEnd);

    switch (type) {
      case 'IHDR':
        header = readHeader(data);
        break;
      case 'PLTE':
        palette = new Uint8Array(data.length);
        palette.set(data);
        break;
      case 'tRNS':
        transparency = parseTransparency(header?.colourType ?? -1, data);
        break;
      case 'IDAT':
        idat.push(Buffer.from(data));
        break;
      case 'IEND':
        sawEnd = true;
        break;
      default:
        break; // APNG / ancillary / unknown chunks are skipped on purpose
    }
    offset = dataEnd + 4;
  }

  if (!header) throw new PngDecodeError('no IHDR chunk: not a PNG');
  if (!sawEnd) throw new PngDecodeError('no IEND chunk: file is truncated');
  if (idat.length === 0) throw new PngDecodeError('no IDAT chunks: file contains no image data');
  if (header.colourType === 3 && !palette) throw new PngDecodeError('palette image without a PLTE chunk');

  const { width, height, bitDepth, colourType, interlace, channels } = header;
  const bitsPerPixel = channels * bitDepth;
  const maxBytesPerLine = Math.ceil((width * bitsPerPixel) / 8);
  // Worst case inflated size: every Adam7 pass stored at full width, plus filter bytes.
  const maxRaw = (maxBytesPerLine + 1) * (height + 7 * Math.ceil(height / 8) + 8);

  let raw;
  try {
    raw = zlib.inflateSync(Buffer.concat(idat), { maxOutputLength: maxRaw });
  } catch (err) {
    throw new PngDecodeError(`zlib inflate failed: ${String(err?.message ?? err)}`);
  }

  const bytesPerPixel = Math.max(1, Math.floor(bitsPerPixel / 8));
  const samples = new Uint8Array(width * height * channels);
  // Two scanline buffers, swapped after every row: the "previous row" that the
  // Paeth predictor reads must not be the row currently being written into.
  let current = new Uint8Array(maxBytesPerLine);
  let previous = new Uint8Array(maxBytesPerLine);
  let consumed = 0;

  if (interlace === 0) {
    const bytesPerLine = Math.ceil((width * bitsPerPixel) / 8);
    for (let y = 0; y < height; y++) {
      consumed = unfilterRow(raw, consumed, bytesPerLine, bytesPerPixel, current, previous);
      readSamples(current, width, bitDepth, channels, samples, y * width * channels);
      const swap = current;
      current = previous;
      previous = swap;
    }
  } else {
    for (const pass of ADAM7) {
      const passWidth = Math.ceil((width - pass.xStart) / pass.xStep);
      const passHeight = Math.ceil((height - pass.yStart) / pass.yStep);
      if (passWidth <= 0 || passHeight <= 0) continue;
      const bytesPerLine = Math.ceil((passWidth * bitsPerPixel) / 8);
      current = new Uint8Array(bytesPerLine);
      previous = new Uint8Array(bytesPerLine);
      for (let y = 0; y < passHeight; y++) {
        consumed = unfilterRow(raw, consumed, bytesPerLine, bytesPerPixel, current, previous);
        // Scatter this pass row into the full-size sample array.
        for (let x = 0; x < passWidth; x++) {
          const targetX = pass.xStart + x * pass.xStep;
          const targetY = pass.yStart + y * pass.yStep;
          if (targetX >= width || targetY >= height) continue;
          const dstOffset = (targetY * width + targetX) * channels;
          if (bitDepth >= 8) {
            const step = bitDepth === 16 ? 2 : 1;
            for (let c = 0; c < channels; c++) samples[dstOffset + c] = current[(x * channels + c) * step];
          } else {
            const byte = current[x >> 3];
            const shift = 8 - bitDepth - (x & 7) * bitDepth;
            samples[dstOffset] = (byte >> shift) & ((1 << bitDepth) - 1);
          }
        }
        const swap = current;
        current = previous;
        previous = swap;
      }
    }
  }

  if (consumed > raw.length) throw new PngDecodeError('image data does not match the declared size');
  return toRgb(header, samples, palette, transparency);
}