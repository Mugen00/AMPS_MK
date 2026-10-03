#!/usr/bin/env node
/**
 * Perceptual-hash frame index test suite.
 *
 *   node test/phash.test.mjs
 *
 * Zero dependencies: the PNG and JPEG *encoders* the tests need live in this
 * file, so the suite is self-contained and runs anywhere Node runs.
 *
 * The synthetic vectors here are the cross-language contract with the Kotlin
 * implementation on the phone. If one of them fails, the implementation is
 * wrong — the specification is not.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  hashFrame, hammingDistance, loadIndex, saveIndex, indexStats, lookup, addEntry,
  handleIndexRequest, resetIndexCache,
} from '../src/frameindex.mjs';
import { decodePng, decodeJpeg, areaAverage, lumaCells } from '../src/phash.mjs';

const TEST_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(TEST_DIR, '..');
const DEMO_FRAME = path.join(REPO_ROOT, 'demo', 'anime-frame-commons.png');

/** Vector pinned by the parent implementation; the suite must reproduce it. */
const DEMO_FRAME_EXPECTED = 'f8dc8cddc1553233';

let failures = 0;
let skips = 0;
const write = (line) => process.stdout.write(`${line}\n`);

function check(ok, label, detail = '') {
  if (!ok) failures += 1;
  write(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? `  — ${detail}` : ''}`);
}

function skip(label, detail = '') {
  skips += 1;
  write(`SKIP  ${label}${detail ? `  — ${detail}` : ''}`);
}

function section(title) {
  write(`\n=== ${title} ===`);
}

/* =========================================================== PNG encoder === */

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

function pngChunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const typed = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typed));
  return Buffer.concat([length, typed, crc]);
}

function paethPredictor(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/**
 * Encode RGB(A) bytes as a PNG. `filterMode: 'cycle'` rotates through all five
 * zlib filter types, row by row, so the decoder's unfiltering is exercised.
 *
 * @param {Uint8Array} rgb width*height*3 bytes
 */
export function encodePng(rgb, width, height, { filterMode = 'cycle' } = {}) {
  const bpp = 3;
  const bytesPerLine = width * bpp;
  const raw = Buffer.alloc(height * (bytesPerLine + 1));
  const prior = Buffer.alloc(bytesPerLine);

  for (let y = 0; y < height; y++) {
    const line = Buffer.from(rgb.buffer ?? rgb, (rgb.byteOffset ?? 0) + y * bytesPerLine, bytesPerLine);
    const filter = filterMode === 'cycle' ? y % 5 : Number(filterMode) || 0;
    const target = raw.subarray(y * (bytesPerLine + 1), y * (bytesPerLine + 1) + 1 + bytesPerLine);
    target[0] = filter;
    for (let i = 0; i < bytesPerLine; i++) {
      const x = line[i];
      const a = i >= bpp ? line[i - bpp] : 0;
      const b = y > 0 ? prior[i] : 0;
      const c = y > 0 && i >= bpp ? prior[i - bpp] : 0;
      let value;
      switch (filter) {
        case 0: value = x; break;
        case 1: value = x - a; break;
        case 2: value = x - b; break;
        case 3: value = x - ((a + b) >> 1); break;
        default: value = x - paethPredictor(a, b, c); break;
      }
      target[1 + i] = value & 0xff;
    }
    line.copy(prior);
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type: truecolour
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0; // interlace: none
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Encode an 8-bit greyscale PNG (colour type 0). */
function encodeGreyPng(grey, width, height) {
  const raw = Buffer.alloc(height * (width + 1));
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0;
    for (let x = 0; x < width; x++) raw[y * (width + 1) + 1 + x] = grey[y * width + x];
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 0; // greyscale
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

/* =========================================================== JPEG encoder === */

/** Annex K quantisation tables, scaled by quality (50 = tables as printed). */
const BASE_LUMA_QUANT = [
  16, 11, 10, 16, 24, 40, 51, 61,
  12, 12, 14, 19, 26, 58, 60, 55,
  14, 13, 16, 24, 40, 57, 69, 56,
  14, 17, 22, 29, 51, 87, 80, 62,
  18, 22, 37, 56, 68, 109, 103, 77,
  24, 35, 55, 64, 81, 104, 113, 92,
  49, 64, 78, 87, 103, 121, 120, 101,
  72, 92, 95, 98, 112, 100, 103, 99,
];

const BASE_CHROMA_QUANT = [
  17, 18, 24, 47, 99, 99, 99, 99,
  18, 21, 26, 66, 99, 99, 99, 99,
  24, 26, 56, 99, 99, 99, 99, 99,
  47, 66, 99, 99, 99, 99, 99, 99,
  99, 99, 99, 99, 99, 99, 99, 99,
  99, 99, 99, 99, 99, 99, 99, 99,
  99, 99, 99, 99, 99, 99, 99, 99,
  99, 99, 99, 99, 99, 99, 99, 99,
];

const ZIGZAG = [
  0, 1, 8, 16, 9, 2, 3, 10,
  17, 24, 32, 25, 18, 11, 4, 5,
  12, 19, 26, 33, 40, 48, 41, 34,
  27, 20, 13, 6, 7, 14, 21, 28,
  35, 42, 49, 56, 57, 50, 43, 36,
  29, 22, 15, 23, 30, 37, 44, 51,
  58, 59, 52, 45, 38, 31, 39, 46,
  53, 60, 61, 54, 47, 55, 62, 63,
];

const ZIGZAG_INVERSE = (() => {
  const inverse = new Int32Array(64);
  for (let k = 0; k < 64; k++) inverse[ZIGZAG[k]] = k;
  return inverse;
})();

const FDCT_BASIS = (() => {
  const m = new Float64Array(64);
  for (let x = 0; x < 8; x++) {
    for (let u = 0; u < 8; u++) {
      const cu = u === 0 ? Math.SQRT1_2 : 1;
      m[x * 8 + u] = 0.5 * cu * Math.cos(((2 * x + 1) * u * Math.PI) / 16);
    }
  }
  return m;
})();

function scaleQuantTable(base, quality) {
  const q = Math.min(100, Math.max(1, quality));
  const factor = q < 50 ? Math.floor(5000 / q) : 200 - q * 2;
  return base.map((value) => {
    const scaled = Math.floor((value * factor + 50) / 100);
    return Math.min(255, Math.max(1, scaled));
  });
}

class BitWriter {
  constructor() {
    this.bytes = [];
    this.acc = 0;
    this.count = 0;
  }

  writeBits(value, length) {
    for (let i = length - 1; i >= 0; i--) {
      this.acc = (this.acc << 1) | ((value >> i) & 1);
      this.count += 1;
      if (this.count === 8) {
        const byte = this.acc & 0xff;
        this.bytes.push(byte);
        if (byte === 0xff) this.bytes.push(0x00); // byte stuffing
        this.acc = 0;
        this.count = 0;
      }
    }
  }

  /** Pad the final byte with 1 bits, as the standard requires. */
  flush() {
    while (this.count !== 0) this.writeBits(1, 1);
    return Buffer.from(this.bytes);
  }
}

function category(value) {
  let v = Math.abs(value);
  let bits = 0;
  while (v > 0) {
    bits += 1;
    v >>= 1;
  }
  return bits;
}

/**
 * Canonical Huffman table with one uniform code length — a valid prefix code
 * (Kraft sum n * 2^-L <= 1) and legal in JPEG, which lets the test encoder
 * build tables from the symbols it actually used instead of shipping the
 * Annex K tables.
 */
function buildUniformTable(usedSymbols) {
  const symbols = [...new Set(usedSymbols)].sort((a, b) => a - b);
  if (symbols.length === 0) symbols.push(0);
  const length = Math.max(1, Math.ceil(Math.log2(symbols.length)));
  const counts = new Array(16).fill(0);
  counts[length - 1] = symbols.length;
  const codes = new Map();
  symbols.forEach((symbol, index) => codes.set(symbol, { code: index, length }));
  return { counts, values: symbols, codes };
}

function jpegSegment(marker, payload) {
  return Buffer.concat([Buffer.from([0xff, marker]), Buffer.from([(payload.length + 2) >> 8, (payload.length + 2) & 0xff]), payload]);
}

/**
 * Baseline JPEG encoder: 4:4:4, one scan, custom Huffman tables.
 * Only what a round-trip test needs — no subsampling, no restart markers.
 */
export function encodeJpeg(rgb, width, height, { quality = 95 } = {}) {
  const lumaQuant = scaleQuantTable(BASE_LUMA_QUANT, quality);
  const chromaQuant = scaleQuantTable(BASE_CHROMA_QUANT, quality);
  const quantFor = [lumaQuant, chromaQuant, chromaQuant];

  // RGB -> YCbCr, level shifted so that 128 is black in every plane. No
  // chroma subsampling: every component is 1x1 sampled, so one MCU is one
  // 8x8 block of Y followed by one of Cb and one of Cr.
  const planeSize = width * height;
  const planes = [new Float64Array(planeSize), new Float64Array(planeSize), new Float64Array(planeSize)];
  for (let i = 0; i < planeSize; i++) {
    const r = rgb[i * 3];
    const g = rgb[i * 3 + 1];
    const b = rgb[i * 3 + 2];
    planes[0][i] = 0.299 * r + 0.587 * g + 0.114 * b - 128;
    planes[1][i] = -0.168736 * r - 0.331264 * g + 0.5 * b;
    planes[2][i] = 0.5 * r - 0.418688 * g - 0.081312 * b;
  }

  const blocksX = Math.ceil(width / 8);
  const blocksY = Math.ceil(height / 8);
  const blockCount = blocksX * blocksY;
  // blocks[component][blockIndex] — zigzag-ordered quantised coefficients.
  const blocks = [new Int32Array(blockCount * 64), new Int32Array(blockCount * 64), new Int32Array(blockCount * 64)];
  const block = new Float64Array(64);
  const row = new Float64Array(64);

  for (let by = 0; by < blocksY; by++) {
    for (let bx = 0; bx < blocksX; bx++) {
      const blockIndex = by * blocksX + bx;
      for (let c = 0; c < 3; c++) {
        const plane = planes[c];
        for (let y = 0; y < 8; y++) {
          const sy = Math.min(height - 1, by * 8 + y);
          for (let x = 0; x < 8; x++) {
            const sx = Math.min(width - 1, bx * 8 + x);
            block[y * 8 + x] = plane[sy * width + sx];
          }
        }
        // forward DCT, separable. The basis is laid out as [position * 8 +
        // frequency], exactly like IDCT_BASIS in the decoder, and it is not
        // symmetric — indexing it as [frequency * 8 + position] silently
        // produces a different (non-involutive) transform.
        for (let y = 0; y < 8; y++) {
          for (let u = 0; u < 8; u++) {
            let sum = 0;
            for (let x = 0; x < 8; x++) sum += block[y * 8 + x] * FDCT_BASIS[x * 8 + u];
            row[y * 8 + u] = sum;
          }
        }
        const quant = quantFor[c];
        const zz = blocks[c].subarray(blockIndex * 64, blockIndex * 64 + 64);
        for (let u = 0; u < 8; u++) {
          for (let v = 0; v < 8; v++) {
            let sum = 0;
            for (let y = 0; y < 8; y++) sum += row[y * 8 + u] * FDCT_BASIS[y * 8 + v];
            // ZIGZAG maps zigzag position -> natural index, so storing a
            // coefficient found at natural index n needs the inverse table.
            // Using ZIGZAG[n] directly leaves the DC right and scatters every
            // AC coefficient, which flat images hide completely.
            zz[ZIGZAG_INVERSE[v * 8 + u]] = Math.round(sum / quant[v * 8 + u]);
          }
        }
      }
    }
  }

  // Pass 1: which DC/AC symbols does this image need? All three components
  // share one table pair, so the symbol set is collected across every MCU of
  // every component before any code is written.
  const dcSymbols = [0];
  const acSymbols = [0x00, 0xf0];
  const previousDc = [0, 0, 0];
  const collect = (c, blockIndex) => {
    const yy = blocks[c].subarray(blockIndex * 64, blockIndex * 64 + 64);
    const diff = yy[0] - previousDc[c];
    previousDc[c] = yy[0];
    dcSymbols.push(category(diff));
    let run = 0;
    for (let k = 1; k < 64; k++) {
      if (yy[k] === 0) {
        run += 1;
        continue;
      }
      while (run > 15) {
        acSymbols.push(0xf0);
        run -= 16;
      }
      acSymbols.push((run << 4) | category(yy[k]));
      run = 0;
    }
  };
  for (let blockIndex = 0; blockIndex < blockCount; blockIndex++) {
    for (let c = 0; c < 3; c++) collect(c, blockIndex);
  }

  const dcTable = buildUniformTable(dcSymbols);
  const acTable = buildUniformTable(acSymbols);

  // Pass 2: write the entropy-coded segment in MCU order (Y, Cb, Cr).
  const writer = new BitWriter();
  previousDc[0] = 0;
  previousDc[1] = 0;
  previousDc[2] = 0;
  for (let blockIndex = 0; blockIndex < blockCount; blockIndex++) {
   for (let c = 0; c < 3; c++) {
    const yy = blocks[c].subarray(blockIndex * 64, blockIndex * 64 + 64);
    const diff = yy[0] - previousDc[c];
    previousDc[c] = yy[0];
    const dcSize = category(diff);
    const dc = dcTable.codes.get(dcSize);
    writer.writeBits(dc.code, dc.length);
    if (dcSize > 0) writer.writeBits(diff < 0 ? diff + (1 << dcSize) - 1 : diff, dcSize);

    let run = 0;
    for (let k = 1; k < 64; k++) {
      if (yy[k] === 0) {
        run += 1;
        continue;
      }
      while (run > 15) {
        const zrl = acTable.codes.get(0xf0);
        writer.writeBits(zrl.code, zrl.length);
        run -= 16;
      }
      const size = category(yy[k]);
      const symbol = (run << 4) | size;
      const code = acTable.codes.get(symbol);
      writer.writeBits(code.code, code.length);
      writer.writeBits(yy[k] < 0 ? yy[k] + (1 << size) - 1 : yy[k], size);
      run = 0;
    }
    if (run > 0) {
      const eob = acTable.codes.get(0x00);
      writer.writeBits(eob.code, eob.length);
    }
   }
  }

  // DQT stores the table in zigzag order, not row-major.
  const toZigzag = (natural) => ZIGZAG.map((naturalIndex) => natural[naturalIndex]);
  const dqt = Buffer.concat([
    Buffer.from([0x00]), Buffer.from(toZigzag(lumaQuant)),
    Buffer.from([0x01]), Buffer.from(toZigzag(chromaQuant)),
  ]);
  const dhtPayload = (tableClass, id, table) => Buffer.concat([
    Buffer.from([(tableClass << 4) | id]),
    Buffer.from(table.counts),
    Buffer.from(table.values),
  ]);
  const dht = Buffer.concat([dhtPayload(0, 0, dcTable), dhtPayload(1, 0, acTable)]);

  // Three components, all 1x1 sampled; Y uses quant table 0, Cb and Cr use 1.
  const sof = Buffer.alloc(6 + 9);
  sof[0] = 8;
  sof.writeUInt16BE(height, 1);
  sof.writeUInt16BE(width, 3);
  sof[5] = 3; // component count
  sof[6] = 1; sof[7] = 0x11; sof[8] = 0; // Y
  sof[9] = 2; sof[10] = 0x11; sof[11] = 1; // Cb
  sof[12] = 3; sof[13] = 0x11; sof[14] = 1; // Cr

  const sos = Buffer.from([3, 1, 0x00, 2, 0x00, 3, 0x00, 0, 63, 0]);

  return Buffer.concat([
    Buffer.from([0xff, 0xd8]),
    jpegSegment(0xdb, dqt),
    jpegSegment(0xc4, dht),
    jpegSegment(0xc0, sof),
    jpegSegment(0xda, sos),
    writer.flush(),
    Buffer.from([0xff, 0xd9]),
  ]);
}

/* ============================================================== fixtures === */

const fill = (width, height, fn) => {
  const data = new Uint8Array(width * height * 3);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const [r, g, b] = fn(x, y);
      const p = (y * width + x) * 3;
      data[p] = r;
      data[p + 1] = g;
      data[p + 2] = b;
    }
  }
  return data;
};

/** Left dark, right light: every comparison is `cell(x) < cell(x+1)`, so all bits 0. */
const darkToLight = (width, height) => fill(width, height, (x) => {
  const v = Math.round((x / (width - 1)) * 255);
  return [v, v, v];
});

/** Left light, right dark: every comparison is `cell(x) > cell(x+1)`, so all bits 1. */
const lightToDark = (width, height) => fill(width, height, (x) => {
  const v = 255 - Math.round((x / (width - 1)) * 255);
  return [v, v, v];
});

/** 36x24, even rows dark->light and odd rows light->dark: alternating rows of bits. */
const striped = (width, height) => fill(width, height, (x, y) => {
  const v = y % 2 === 0 ? Math.round((x / (width - 1)) * 255) : 255 - Math.round((x / (width - 1)) * 255);
  return [v, v, v];
});

const solid = (width, height, rgb = [37, 91, 203]) => fill(width, height, () => rgb);

/* ================================================================== tests === */

function testDeterministic() {
  section('1. детерминизм: один и тот же PNG — один и тот же хеш');
  const png = encodePng(darkToLight(64, 48), 64, 48);
  const first = hashFrame(png, 'image/png');
  const second = hashFrame(png, 'image/png');
  write(`      bytes: ${png.length}, hash: ${first}`);
  check(first === second, 'одинаковые байты дают одинаковый хеш (дважды)', `${first} / ${second}`);
  check(/^[0-9a-f]{16}$/.test(first), 'хеш — 16 шестнадцатеричных символов', first);
  check(first === hashFrame(Buffer.from(png), 'image/png'), 'хеш не зависит от копии буфера');

  // Re-encoding the same pixels with different filters must not change the hash.
  const none = encodePng(darkToLight(64, 48), 64, 48, { filterMode: 0 });
  check(hashFrame(none, 'image/png') === first, 'все 5 фильтров PNG дают тот же хеш', `filter 0: ${hashFrame(none, 'image/png')}`);
  return { png, hash: first };
}

function testKnownVectors() {
  section('2. эталонные векторы (контракт с Kotlin)');
  const solidHash = hashFrame(encodePng(solid(32, 32), 32, 32), 'image/png');
  check(solidHash === '0000000000000000', 'однотонный кадр -> все нули', solidHash);

  const darkLight = hashFrame(encodePng(darkToLight(32, 32), 32, 32), 'image/png');
  check(darkLight === '0000000000000000', 'градиент слева-темно / справа-светло -> все нули', darkLight);

  const lightDark = hashFrame(encodePng(lightToDark(32, 32), 32, 32), 'image/png');
  check(lightDark === 'ffffffffffffffff', 'градиент слева-светло / справа-темно -> все единицы', lightDark);

  const stripeHash = hashFrame(encodePng(striped(36, 24), 36, 24), 'image/png');
  check(stripeHash === '00ff00ff00ff00ff', '36x24 чередование строк -> 00ff00ff00ff00ff', stripeHash);

  const grey = new Uint8Array(16 * 16).fill(128);
  check(hashFrame(encodeGreyPng(grey, 16, 16), 'image/png') === '0000000000000000', '16-битный путь не нужен, серый PNG -> все нули');

  write(`      NOTE: правило контракта — бит = 1, если cell(x,y) > cell(x+1,y).`);
  write(`      NOTE: «слева-темно/справа-светло» поэтому даёт 0000000000000000, а ffffffffffffffff`);
  write(`      NOTE: получается только у «слева-светло/справа-темно». Обе строки закреплены выше.`);
}

function testHamming() {
  section('3. расстояние Хэмминга');
  const d = hammingDistance('0000000000000000', '0000000000000003');
  check(d === 2, "hammingDistance('0000000000000000','0000000000000003') === 2", String(d));
  check(hammingDistance('ffffffffffffffff', 'ffffffffffffffff') === 0, 'одинаковые хеши: расстояние 0');
  check(hammingDistance('ffffffffffffffff', '0000000000000000') === 64, 'полностью разные хеши: расстояние 64');
  check(hammingDistance('f8dc8cddc1553233', 'f8dc8cddc1553233') === 0, 'вектор демо-кадра сравним с самим собой');
}

function testJpegRoundTrip() {
  section('4. JPEG: кодирование тестом -> декодирование моим декодером');
  const width = 48;
  const height = 32;
  // Smooth, photographic-like content: JPEG is lossy, and a hard 0/255
  // checkerboard is its worst case (libjpeg itself scores mean 40 on the
  // pattern below), so a tight pixel bound would only measure ringing.
  const rgb = fill(width, height, (x, y) => [
    Math.round((x / (width - 1)) * 255),
    Math.round((y / (height - 1)) * 255),
    Math.round(128 + 60 * Math.sin((x / width) * Math.PI) * Math.cos((y / height) * Math.PI)),
  ]);

  let jpeg;
  try {
    jpeg = encodeJpeg(rgb, width, height, { quality: 95 });
  } catch (err) {
    check(false, 'JPEG кодировщик теста собрал файл', String(err?.message ?? err));
    return;
  }
  check(jpeg.length > 100 && jpeg[0] === 0xff && jpeg[1] === 0xd8, 'JPEG начинается с SOI', `${jpeg.length} байт`);

  let decoded;
  try {
    decoded = decodeJpeg(jpeg);
    check(true, 'декодер JPEG разбирает файл без исключения', `${decoded.width}x${decoded.height}`);
  } catch (err) {
    check(false, 'декодер JPEG разбирает файл без исключения', String(err?.message ?? err));
    return;
  }

  check(decoded.width === width && decoded.height === height, 'размеры совпали после round-trip', `${decoded.width}x${decoded.height} vs ${width}x${height}`);
  check(decoded.data.length === width * height * 3, 'длина буфера = width*height*3', String(decoded.data.length));

  let sum = 0;
  let max = 0;
  for (let i = 0; i < decoded.data.length; i++) {
    const diff = Math.abs(decoded.data[i] - rgb[i]);
    sum += diff;
    if (diff > max) max = diff;
  }
  const mean = sum / decoded.data.length;
  check(mean < 6, 'средняя ошибка пикселя после round-trip < 6', `mean=${mean.toFixed(3)} max=${max}`);

  // A flat image must stay flat: this catches a broken DC predictor / quantiser.
  const flat = solid(32, 32, [90, 120, 150]);
  const flatJpeg = encodeJpeg(flat, 32, 32, { quality: 95 });
  const flatDecoded = decodeJpeg(flatJpeg);
  let flatSpread = 0;
  for (let i = 0; i < flatDecoded.data.length; i++) flatSpread = Math.max(flatSpread, Math.abs(flatDecoded.data[i] - flat[i]));
  check(flatSpread <= 3, 'плоский кадр остаётся плоским после JPEG', `max отклонение=${flatSpread}`);
  check(hashFrame(flatJpeg, 'image/jpeg') === '0000000000000000', 'плоский JPEG -> хеш всех нулей', hashFrame(flatJpeg, 'image/jpeg'));

  // Same picture, two independent encoders: the hash must agree. Again the
  // subject has to be smooth — dHash reads an 8x8 grid, and JPEG ringing
  // around hard one-pixel stripes flips comparison bits that PNG keeps.
  const photo = fill(36, 24, (x, y) => [
    Math.round(60 + 150 * (x / 35)),
    Math.round(40 + 120 * (y / 23)),
    Math.round(90 + 100 * (1 - x / 35)),
  ]);
  const pngHash = hashFrame(encodePng(photo, 36, 24), 'image/png');
  const jpegHash = hashFrame(encodeJpeg(photo, 36, 24, { quality: 98 }), 'image/jpeg');
  const gap = hammingDistance(pngHash, jpegHash);
  check(gap <= 2, 'PNG и JPEG одного изображения дают один хеш', `${pngHash} / ${jpegHash}, расстояние ${gap}`);

  // Progressive JPEG must be refused, not guessed at.
  const progressive = Buffer.from(jpeg);
  progressive[progressive.indexOf(Buffer.from([0xff, 0xc0])) + 1] = 0xc2;
  try {
    decodeJpeg(progressive);
    check(false, 'прогрессивный JPEG отклоняется с внятной ошибкой', 'декодер принял SOF2');
  } catch (err) {
    check(/progressive/i.test(String(err?.message)), 'прогрессивный JPEG отклоняется с внятной ошибкой', String(err?.message));
  }

  // A truncated file must fail loudly instead of returning garbage pixels.
  try {
    decodeJpeg(jpeg.subarray(0, Math.floor(jpeg.length / 2)));
    check(false, 'обрезанный JPEG отклоняется, а не декодируется частично', 'декодер принял половину файла');
  } catch (err) {
    check(true, 'обрезанный JPEG отклоняется, а не декодируется частично', String(err?.message).slice(0, 80));
  }
}

/**
 * A real libjpeg file. Everything else in this section round-trips through the
 * encoder below, so a shared mistake (a transposed basis, an inverted zigzag,
 * a mis-signed DC difference) cancels out and stays invisible. This fixture was
 * written by libjpeg at quality 90 with 4:2:0 chroma subsampling, so it also
 * covers the Annex K Huffman tables, 2x2 MCU interleaving and box chroma
 * upsampling — none of which the test encoder produces.
 */
const LIBJPEG_FIXTURE_JPEG_B64 = [
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQ',
  'ERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU',
  'FBQUFBQUFBQUFBQUFBT/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA',
  'AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6',
  'Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG',
  'x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA',
  'AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5',
  'OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE',
  'xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD4/wDBfwh/1f7j9K958F/CH/V/uP0r2PwX',
  '8If9X+4/SvefBfwh/wBX+4/SjB4zbUPDzxD+D3z/2Q==',
];

function testLibjpegFixture() {
  section('4c. декодер JPEG против файла, написанного libjpeg');
  const jpeg = Buffer.from(LIBJPEG_FIXTURE_JPEG_B64.join(''), 'base64');
  check(jpeg.length === 679, 'фикстура libjpeg загрузилась целиком', `${jpeg.length} байт`);

  const source = fill(16, 16, (x, y) => [(x * 16) % 256, (y * 16) % 256, ((x + y) * 8) % 256]);
  const image = decodeJpeg(jpeg);
  check(image.width === 16 && image.height === 16, 'размер совпал с кадром libjpeg', `${image.width}x${image.height}`);

  let sum = 0;
  let max = 0;
  for (let i = 0; i < image.data.length; i++) {
    const diff = Math.abs(image.data[i] - source[i]);
    sum += diff;
    if (diff > max) max = diff;
  }
  const mean = sum / image.data.length;
  // libjpeg's own loss on this file is mean 1.73, max 13; box instead of
  // triangular chroma upsampling moves the 2x2 chroma edges further out.
  check(mean <= 5, 'ошибка против исходника в пределах потерь самого libjpeg', `mean=${mean.toFixed(3)} max=${max}`);
  check(hashFrame(jpeg, 'image/jpeg') === hashFrame(encodePng(source, 16, 16), 'image/png'),
    'хеш libjpeg-файла совпадает с тем же кадром в PNG', hashFrame(jpeg, 'image/jpeg'));
}

function testDecoders() {
  section('4b. декодеры PNG и JPEG против эталонных пикселей');
  const greyPng = encodeGreyPng(new Uint8Array(64).map((_, i) => (i * 4) % 256), 8, 8);
  const greyImage = decodePng(greyPng);
  let greyOk = greyImage.width === 8 && greyImage.height === 8;
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      const expected = ((y * 8 + x) * 4) % 256;
      const p = (y * 8 + x) * 3;
      if (greyImage.data[p] !== expected || greyImage.data[p + 1] !== expected || greyImage.data[p + 2] !== expected) greyOk = false;
    }
  }
  check(greyOk, '8-битный greyscale PNG декодируется верно', `${greyImage.width}x${greyImage.height}`);

  for (const [label, bytes, expect] of [
    ['мусор вместо картинки', Buffer.from('not an image at all'), /unsupported|neither/i],
    ['PNG с испорченным CRC', corruptCrc(encodePng(solid(16, 16), 16, 16)), /CRC/i],
    ['обрезанный PNG', encodePng(solid(16, 16), 16, 16).subarray(0, 40), /IHDR|IEND|chunk/i],
  ]) {
    try {
      hashFrame(bytes, 'image/png');
      check(false, `отклоняется: ${label}`, 'декодер принял битые данные');
    } catch (err) {
      check(expect.test(String(err?.message)), `отклоняется: ${label}`, String(err?.message).slice(0, 90));
    }
  }
}

function corruptCrc(png) {
  const copy = Buffer.from(png);
  copy[29] = copy[29] ^ 0xff; // inside the IHDR CRC field
  return copy;
}

function testDemoFrame() {
  section('5. реальный кадр из demo/ (вектор, закрепленный родителем)');
  if (!fs.existsSync(DEMO_FRAME)) {
    skip('demo/anime-frame-commons.png отсутствует', DEMO_FRAME);
    return;
  }
  const bytes = fs.readFileSync(DEMO_FRAME);
  const hash = hashFrame(bytes, 'image/png');
  const distance = hammingDistance(hash, DEMO_FRAME_EXPECTED);
  write(`      DEMO FRAME: ${hash}`);
  write(`      expected  : ${DEMO_FRAME_EXPECTED}   distance: ${distance}`);
  check(hash === DEMO_FRAME_EXPECTED, 'демо-кадр совпал с закрепленным вектором', `${hash}, расстояние ${distance}`);

  const image = decodePng(bytes);
  const grid = areaAverage(image);
  const cells = lumaCells(image);
  write(`      сетка 9x8 (первая строка): ${Array.from(cells.subarray(0, 9)).join(' ')}`);
  write(`      размер: ${image.width}x${image.height}, цвет: RGB8 (alpha composited over black)`);
}

function testIndex() {
  section('6. индекс: добавление, поиск, сохранение, HTTP-обработчик');
  // Scratch goes to the OS temp directory, never into the repository.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'amps-phash-test-'));
  const file = path.join(dir, 'frame-index.json');
  resetIndexCache(file);

  const entries = [
    { hash: '0000000000000000', anilistId: 21, seriesTitle: 'ONE PIECE', episode: 1, timestampSec: 12.5, source: 'test', imageUrl: 'https://example.invalid/1.png' },
    { hash: 'ffffffffffffffff', anilistId: 1, seriesTitle: 'Cowboy Bebop', episode: 3, timestampSec: 300, source: 'test', imageUrl: null },
    { hash: 'f8dc8cddc1553233', anilistId: 138459, seriesTitle: 'Demon Slayer', episode: 12, timestampSec: 1337.25, source: 'fandom:onepiece', imageUrl: 'https://example.invalid/3.png', width: 1280, height: 720 },
  ];

  let index = { version: 1, builtAt: new Date().toISOString(), entries: [] };
  for (const entry of entries) index = addEntry(index, entry);
  check(index.entries.length === 3, 'добавлены 3 записи', String(index.entries.length));

  const sorted = index.entries.map((e) => e.hash);
  check(JSON.stringify(sorted) === JSON.stringify([...sorted].sort()), 'записи отсортированы по хешу', sorted.join(','));

  index = addEntry(index, { ...entries[0], seriesTitle: 'ONE PIECE (исправлено)', source: 'manual' });
  check(index.entries.length === 3, 'повторный хеш не создает дубль', String(index.entries.length));
  check(index.entries.find((e) => e.hash === '0000000000000000').source === 'manual', 'повторный хеш заменяет запись', index.entries.find((e) => e.hash === '0000000000000000').source);

  // 10 bits flipped: still inside the tolerance.
  const near = '0000000000000abc';
  const nearHit = lookup(index, near);
  check(nearHit.match !== null, 'поиск находит соседа в пределах 10 бит', `${nearHit.distance} бит, проверок: ${nearHit.checked}`);
  check(nearHit.match.hash === '0000000000000000', 'найден именно ближайший', String(nearHit.match.hash));

  // Far away: no match, but the scan must stop early thanks to the sort order.
  const far = lookup(index, '7fffffffffffffff');
  check(far.match === null, 'за пределами допуска возвращается null', `distance=${far.distance}, проверок: ${far.checked}`);
  check(far.checked <= index.entries.length, 'проверок не больше размера индекса', String(far.checked));

  const stats = indexStats(index);
  // Series titles now: 'ONE PIECE (исправлено)', 'Cowboy Bebop', 'Demon Slayer' -> 3.
  check(stats.count === 3 && stats.series === 3 && stats.episodes === 3, 'статистика индекса', JSON.stringify({ count: stats.count, series: stats.series, episodes: stats.episodes }));
  check(Array.isArray(stats.sources) && stats.sources.includes('test'), 'источники перечислены в статистике', JSON.stringify(stats.sources));
  check(stats.maxDistance === 10, 'в статистике есть maxDistance', String(stats.maxDistance));

  saveIndex(index, { file });
  check(fs.existsSync(file), 'индекс записан на диск', file);
  resetIndexCache(file);
  const reloaded = loadIndex({ file });
  check(reloaded.entries.length === 3, 'индекс перечитан с диска', String(reloaded.entries.length));
  check(lookup(reloaded, near).match !== null, 'поиск работает и после перезагрузки');

  const deps = { indexFile: file };
  let response = handleIndexRequest('/api/frame/index/stats', new URLSearchParams(), Buffer.alloc(0), deps);
  check(response.status === 200 && response.json.count === 3, 'GET /api/frame/index/stats', JSON.stringify({ status: response.status, count: response.json.count }));

  response = handleIndexRequest('/api/frame/index/match', new URLSearchParams({ hash: DEMO_FRAME_EXPECTED }), Buffer.alloc(0), deps);
  check(response.status === 200 && response.json.matched === true && response.json.entry.seriesTitle === 'Demon Slayer', 'GET /api/frame/index/match находит демо-кадр', JSON.stringify(response.json.entry?.seriesTitle));

  response = handleIndexRequest('/api/frame/index/match', new URLSearchParams({ hash: '7fffffffffffffff' }), Buffer.alloc(0), deps);
  check(response.status === 200 && response.json.matched === false, 'match без результата — 200 и matched:false', JSON.stringify({ matched: response.json.matched, distance: response.json.distance }));

  for (const bad of ['zzz', '0000', '', '00000000000000000']) {
    response = handleIndexRequest('/api/frame/index/match', new URLSearchParams({ hash: bad }), Buffer.alloc(0), deps);
    check(response.status === 400, `match с некорректным хешем «${bad}» -> 400`, String(response.status));
  }

  response = handleIndexRequest('/api/frame/index/add', new URLSearchParams(), Buffer.from(JSON.stringify({
    hash: '0f0f0f0f0f0f0f0f', anilistId: 20, seriesTitle: 'NARUTO', episode: 2, timestampSec: 42, source: 'manual', imageUrl: 'https://example.invalid/4.png',
  })), deps);
  check(response.status === 200 && response.json.count === 4, 'POST /api/frame/index/add', JSON.stringify({ status: response.status, count: response.json.count }));

  response = handleIndexRequest('/api/frame/index/add', new URLSearchParams(), Buffer.from(JSON.stringify({
    hash: '0f0f0f0f0f0f0f0f', anilistId: 20, seriesTitle: 'NARUTO (обновлено)', episode: 2, source: 'manual',
  })), deps);
  check(response.json.count === 4, 'add дедуплицирует по хешу', String(response.json.count));

  for (const [label, body] of [
    ['без хеша', { anilistId: 1, seriesTitle: 'x', source: 'y' }],
    ['плохой хеш', { hash: 'nope', anilistId: 1, seriesTitle: 'x', source: 'y' }],
    ['без источника', { hash: '0f0f0f0f0f0f0f0e', anilistId: 1, seriesTitle: 'x' }],
    ['плохой anilistId', { hash: '0f0f0f0f0f0f0f0e', anilistId: -3, seriesTitle: 'x', source: 'y' }],
    ['не-JSON', '{{{'],
  ]) {
    response = handleIndexRequest('/api/frame/index/add', new URLSearchParams(), typeof body === 'string' ? Buffer.from(body) : Buffer.from(JSON.stringify(body)), deps);
    check(response.status === 400, `add отклоняет: ${label}`, String(response.status));
  }

  response = handleIndexRequest('/api/frame/index/rebuild', new URLSearchParams(), Buffer.alloc(0), deps);
  check(response.status === 200 && response.json.count === 0, 'POST /api/frame/index/rebuild очищает индекс', JSON.stringify({ status: response.status, count: response.json.count }));
  check(loadIndex({ file }).entries.length === 0, 'индекс на диске пуст после rebuild');

  for (const path of ['/api/frame/index', '/api/frame/index/list', '/api/frame/index/add/extra', '/']) {
    response = handleIndexRequest(path, new URLSearchParams(), Buffer.alloc(0), deps);
    check(response.status === 404, `404 на неизвестный путь ${path}`, String(response.status));
  }

  fs.rmSync(dir, { recursive: true, force: true });
}

/** The harvester must be importable and honest about what it can reach. */
async function testHarvester() {
  section('7. сборщик: адаптеры и проверка лицензий');
  const tool = await import(pathToFileURL(path.join(TEST_DIR, 'tools', 'build-frame-index.mjs')).href);
  check(typeof tool.fandomAdapter === 'function' && typeof tool.archiveAdapter === 'function', 'адаптеры fandom и archive экспортированы');
  check(Object.keys(tool.ADAPTERS).join(',') === 'fandom,archive', 'реестр адаптеров', Object.keys(tool.ADAPTERS).join(','));

  const licences = [
    ['https://creativecommons.org/publicdomain/zero/1.0/', true],
    ['http://creativecommons.org/licenses/by/4.0/', true],
    ['https://creativecommons.org/licenses/by-sa/3.0/', true],
    ['Public Domain', true],
    ['', false],
    ['Fair use', false],
    ['All rights reserved', false],
    ['CC-BY-NC 3.0', false],
    ['© 2024 some studio', false],
    [undefined, false],
  ];
  let licenceOk = true;
  for (const [text, expected] of licences) {
    const verdict = tool.classifyLicence(text);
    if (verdict.free !== expected) licenceOk = false;
  }
  check(licenceOk, 'лицензия проверяется: свободные проходят, закрытые отбрасываются', `${licences.length} случаев`);

  const fandom = await tool.fandomAdapter({ wiki: tool.FANDOM_WIKIS[0], limit: 3, dryRun: true, timeoutMs: 10, log: null });
  check(fandom.candidates.length === 0 && fandom.skipped.length > 0, 'fandom в dry-run ничего не скачивает', `кандидатов: ${fandom.candidates.length}, отказов: ${fandom.skipped.length}`);
  const archive = await tool.archiveAdapter({ query: tool.ARCHIVE_QUERIES[0], limit: 3, dryRun: true, timeoutMs: 10, log: null });
  check(archive.candidates.length === 0 && archive.skipped.length > 0, 'archive в dry-run ничего не скачивает', `кандидатов: ${archive.candidates.length}, отказов: ${archive.skipped.length}`);
}

async function main() {
  write(`amps phash test suite (node ${process.version})`);
  write(`node ${process.execPath}`);

  testDeterministic();
  testKnownVectors();
  testHamming();
  testJpegRoundTrip();
  testLibjpegFixture();
  testDecoders();
  testDemoFrame();
  testIndex();
  await testHarvester();

  write(`\n${failures === 0 ? `ALL TESTS PASSED${skips ? ` (${skips} skipped)` : ''}` : `${failures} CHECK(S) FAILED`}`);
  process.exit(failures === 0 ? 0 : 1);
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]).endsWith('phash.test.mjs');
if (invokedDirectly) {
  main().catch((err) => {
    process.stderr.write(`\nТЕСТ УПАЛ: ${err?.stack ?? err}\n`);
    process.exit(1);
  });
}