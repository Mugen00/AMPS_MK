/**
 * Baseline JPEG decoder — Node standard library only.
 *
 * Implemented: baseline sequential DCT (SOF0) and extended sequential (SOF1),
 * Huffman entropy coding with byte stuffing and restart intervals, 8- and
 * 16-bit quantisation tables, chroma subsampling 4:4:4 / 4:2:2 / 4:2:0 / 4:1:1
 * (and any other legal h/v factors), greyscale, Adobe APP14 transform handling,
 * explicit truncation detection.
 *
 * Deliberately NOT supported (throws `JpegDecodeError` with a clear reason):
 * progressive JPEG (SOF2), arithmetic coding, hierarchical / differential /
 * lossless modes, CMYK and YCCK (4 components), DNL, and files whose Huffman or
 * quantisation tables are missing. A frame we cannot decode is reported, never
 * guessed: a wrong hash poisons the frame index.
 *
 * Output: `{ width, height, data }` where `data` is `width * height * 3` bytes
 * of 8-bit RGB, three channels interleaved, top-left origin, row-major.
 */

const ZIGZAG = new Int32Array([
  0, 1, 8, 16, 9, 2, 3, 10,
  17, 24, 32, 25, 18, 11, 4, 5,
  12, 19, 26, 33, 40, 48, 41, 34,
  27, 20, 13, 6, 7, 14, 21, 28,
  35, 42, 49, 56, 57, 50, 43, 36,
  29, 22, 15, 23, 30, 37, 44, 51,
  58, 59, 52, 45, 38, 31, 39, 46,
  53, 60, 61, 54, 47, 55, 62, 63,
]);

/** Frame types we cannot decode, keyed by marker, with a human reason. */
const UNSUPPORTED_FRAMES = {
  0xc2: 'progressive JPEG (SOF2) is not supported, only baseline sequential',
  0xc3: 'lossless JPEG (SOF3) is not supported',
  0xc5: 'differential sequential JPEG (SOF5) is not supported',
  0xc6: 'differential progressive JPEG (SOF6) is not supported',
  0xc7: 'differential lossless JPEG (SOF7) is not supported',
  0xc9: 'arithmetic sequential JPEG (SOF9) is not supported',
  0xca: 'arithmetic progressive JPEG (SOF10) is not supported',
  0xcb: 'arithmetic lossless JPEG (SOF11) is not supported',
  0xcd: 'differential arithmetic sequential JPEG (SOF13) is not supported',
  0xce: 'differential arithmetic progressive JPEG (SOF14) is not supported',
  0xcf: 'differential arithmetic lossless JPEG (SOF15) is not supported',
};

const MAX_PIXELS = 64 * 1024 * 1024;

/** Separable inverse DCT basis: out[x] = sum_u BASIS[x * 8 + u] * in[u]. */
const IDCT_BASIS = (() => {
  const m = new Float64Array(64);
  for (let x = 0; x < 8; x++) {
    for (let u = 0; u < 8; u++) {
      const cu = u === 0 ? Math.SQRT1_2 : 1;
      m[x * 8 + u] = 0.5 * cu * Math.cos(((2 * x + 1) * u * Math.PI) / 16);
    }
  }
  return m;
})();

export class JpegDecodeError extends Error {
  constructor(message) {
    super(message);
    this.name = 'JpegDecodeError';
    this.format = 'jpeg';
    this.code = 'image_decode_failed';
  }
}

export function isJpeg(bytes) {
  const buf = toBuffer(bytes);
  return buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
}

function toBuffer(bytes) {
  if (Buffer.isBuffer(bytes)) return bytes;
  if (bytes instanceof Uint8Array) return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  throw new JpegDecodeError('expected a Buffer or Uint8Array of JPEG bytes');
}

/**
 * Canonical Huffman table (ITU T.81 figure C.2), stored as a map keyed by
 * `length * 65536 + code` so the decoder can read one bit at a time.
 *
 * A tree of nodes — the classic shortcut — cannot represent a *complete* code
 * (Kraft sum exactly 1), which legal tables do contain; this formulation can.
 */
function buildHuffmanTable(codeLengths, values) {
  let total = 0;
  for (const count of codeLengths) total += count;
  if (total === 0) throw new JpegDecodeError('empty Huffman table');
  if (total > values.length) throw new JpegDecodeError('malformed Huffman table: more codes than symbols');

  const table = new Map();
  let code = 0;
  let k = 0;
  for (let length = 1; length <= 16; length++) {
    for (let i = 0; i < codeLengths[length - 1]; i++) {
      table.set(length * 65536 + code, values[k++]);
      code += 1;
    }
    code <<= 1;
  }
  return table;
}

/** Bit reader over the entropy-coded segment; transparently un-stuffs `FF 00`. */
class EntropyReader {
  constructor(data, offset) {
    this.data = data;
    this.pos = offset;
    this.bitBuffer = 0;
    this.bitCount = 0;
  }

  readBit() {
    if (this.bitCount === 0) {
      if (this.pos >= this.data.length) throw new JpegDecodeError('entropy-coded data ends before the scan is complete');
      const byte = this.data[this.pos];
      if (byte === 0xff) {
        const next = this.data[this.pos + 1];
        if (next === undefined) throw new JpegDecodeError('entropy-coded data ends inside a stuffed byte');
        if (next !== 0x00) throw new JpegDecodeError(`unexpected marker 0xFF${next.toString(16).padStart(2, '0')} inside the entropy-coded segment`);
        this.pos += 2;
      } else {
        this.pos += 1;
      }
      this.bitBuffer = byte;
      this.bitCount = 8;
    }
    this.bitCount -= 1;
    return (this.bitBuffer >> this.bitCount) & 1;
  }

  readBits(count) {
    let value = 0;
    for (let i = 0; i < count; i++) value = (value << 1) | this.readBit();
    return value;
  }

  /** Restart markers are byte aligned: drop the rest of the current byte first. */
  readRestartMarker() {
    this.bitCount = 0;
    for (let guard = 0; guard < 2; guard++) {
      if (this.pos + 1 >= this.data.length) break;
      if (this.data[this.pos] !== 0xff) break;
      const marker = this.data[this.pos + 1];
      if (marker >= 0xd0 && marker <= 0xd7) {
        this.pos += 2;
        return;
      }
      if (marker === 0x00) {
        this.pos += 2;
        continue;
      }
      break;
    }
    throw new JpegDecodeError(`expected a restart marker RSTn at offset ${this.pos}, found 0x${this.data[this.pos]?.toString(16) ?? 'eof'}`);
  }

  /** After the last MCU only stuffed padding and then a marker may follow. */
  expectEndOfScan() {
    this.bitCount = 0;
    let pos = this.pos;
    while (pos + 1 < this.data.length) {
      if (this.data[pos] === 0xff && this.data[pos + 1] !== 0x00) return pos;
      pos += 1;
    }
    throw new JpegDecodeError('scan is not followed by a marker: the file is truncated');
  }
}

function decodeHuffman(reader, table, name) {
  let code = 0;
  for (let length = 1; length <= 16; length++) {
    code = (code << 1) | reader.readBit();
    const symbol = table.get(length * 65536 + code);
    if (symbol !== undefined) return symbol;
  }
  throw new JpegDecodeError(`invalid ${name} Huffman code (longer than 16 bits)`);
}

/**
 * Sign-extend a `size`-bit magnitude (ITU T.81 EXTEND).
 *
 * The threshold is inverted relative to the naive reading: the encoder writes
 * `v >= 0 ? v : v + 2^size - 1`, so when the high bit of the field is SET the
 * field is the value itself, and when it is CLEAR the field is the complement.
 * Verified against libjpeg output: +16 is written 10000 and -16 is written
 * 01111, both with size 5, and only this rule turns them back into +16 / -16.
 */
function extend(value, size) {
  return value >= 1 << (size - 1) ? value : value + (-1 << size) + 1;
}

/** Dequantised coefficients for one 8x8 block, natural (de-zigzagged) order. */
function decodeBlock(reader, coefficients, dcTree, acTree, quant, predictor) {
  coefficients.fill(0);

  const size = decodeHuffman(reader, dcTree, 'DC');
  const diff = size === 0 ? 0 : extend(reader.readBits(size), size);
  predictor.value += diff;
  coefficients[0] = predictor.value * quant[0];

  let k = 1;
  while (k < 64) {
    const rs = decodeHuffman(reader, acTree, 'AC');
    const magnitude = rs & 15;
    const run = rs >> 4;
    if (magnitude === 0) {
      if (run < 15) break; // EOB
      k += 16; // ZRL: sixteen zeroes
      continue;
    }
    k += run;
    if (k > 63) throw new JpegDecodeError('AC coefficient index out of range: the scan data is corrupt');
    // `quant` is in natural (de-zigzagged) order, so the table lookup must
    // follow the same zigzag step the coefficient index just did.
    coefficients[ZIGZAG[k]] = extend(reader.readBits(magnitude), magnitude) * quant[ZIGZAG[k]];
    k += 1;
  }
  return coefficients;
}

/**
 * Inverse DCT of one 8x8 block, level-shifted and clamped, written into the
 * component plane at `outOffset`. `stride` is the plane width in pixels: the
 * eight rows of the block are `stride` apart, not 8.
 */
function idctBlock(block, scratch, out, outOffset, stride) {
  for (let y = 0; y < 8; y++) {
    const row = y * 8;
    for (let x = 0; x < 8; x++) {
      let sum = 0;
      for (let u = 0; u < 8; u++) {
        const coeff = block[row + u];
        if (coeff !== 0) sum += coeff * IDCT_BASIS[x * 8 + u];
      }
      scratch[row + x] = sum;
    }
  }
  for (let x = 0; x < 8; x++) {
    for (let y = 0; y < 8; y++) {
      let sum = 0;
      for (let v = 0; v < 8; v++) {
        const value = scratch[v * 8 + x];
        if (value !== 0) sum += value * IDCT_BASIS[y * 8 + v];
      }
      const pixel = Math.round(128 + sum);
      out[outOffset + y * stride + x] = pixel < 0 ? 0 : pixel > 255 ? 255 : pixel;
    }
  }
}

function readFrameHeader(segment, marker) {
  if (marker === 0xc0 || marker === 0xc1) {
    // baseline / extended sequential: the only two modes decoded here
  } else if (marker in UNSUPPORTED_FRAMES) {
    throw new JpegDecodeError(UNSUPPORTED_FRAMES[marker]);
  }
  if (segment[0] !== 8) throw new JpegDecodeError(`unsupported sample precision ${segment[0]}: only 8-bit JPEG is supported`);
  const height = segment.readUInt16BE(1);
  const width = segment.readUInt16BE(3);
  const componentCount = segment[5];
  if (width === 0 || height === 0) throw new JpegDecodeError(`invalid image size ${width}x${height}`);
  if (width * height > MAX_PIXELS) throw new JpegDecodeError(`image is too large: ${width}x${height}`);
  if (componentCount < 1 || componentCount > 4) throw new JpegDecodeError(`unsupported component count ${componentCount}`);
  if (segment.length < 6 + componentCount * 3) throw new JpegDecodeError('truncated frame header');

  const components = [];
  let maxH = 1;
  let maxV = 1;
  for (let i = 0; i < componentCount; i++) {
    const base = 6 + i * 3;
    const id = segment[base];
    const h = segment[base + 1] >> 4;
    const v = segment[base + 1] & 15;
    const quantId = segment[base + 2];
    if (h < 1 || h > 4 || v < 1 || v > 4) throw new JpegDecodeError(`invalid sampling factors ${h}x${v} for component ${id}`);
    maxH = Math.max(maxH, h);
    maxV = Math.max(maxV, v);
    components.push({ id, h, v, quantId, predictor: { value: 0 }, blocksPerLine: 0, blocksPerColumn: 0, blockData: null, output: null, outputWidth: 0, outputHeight: 0 });
  }

  const frame = {
    width,
    height,
    components,
    maxH,
    maxV,
    mcusPerLine: Math.ceil(width / (maxH * 8)),
    mcusPerColumn: Math.ceil(height / (maxV * 8)),
  };
  for (const component of components) {
    component.blocksPerLine = frame.mcusPerLine * component.h;
    component.blocksPerColumn = frame.mcusPerColumn * component.v;
    component.blockData = new Int32Array(component.blocksPerColumn * component.blocksPerLine * 64);
    component.outputWidth = component.blocksPerLine * 8;
    component.outputHeight = component.blocksPerColumn * 8;
    component.output = new Uint8Array(component.outputWidth * component.outputHeight);
    // Fraction of the image width one component sample covers. A 4:2:0 chroma
    // component has h = 1 against a luma max of 2, so image pixel x maps to
    // component sample x / 2. Upsampling multiplies by this, never by h.
    component.ratioX = component.h / frame.maxH;
    component.ratioY = component.v / frame.maxV;
  }
  return frame;
}

function readScanHeader(segment, frame, dcTrees, acTrees) {
  const count = segment[0];
  if (count !== frame.components.length) throw new JpegDecodeError('the scan does not cover every component of the frame');
  if (count + 4 > segment.length) throw new JpegDecodeError('truncated scan header');
  const scanComponents = [];
  for (let i = 0; i < count; i++) {
    const id = segment[1 + i * 2];
    const tables = segment[2 + i * 2];
    const component = frame.components.find((c) => c.id === id);
    if (!component) throw new JpegDecodeError(`the scan references unknown component id ${id}`);
    const dcId = tables >> 4;
    const acId = tables & 15;
    if (!dcTrees[dcId]) throw new JpegDecodeError(`missing DC Huffman table ${dcId}`);
    if (!acTrees[acId]) throw new JpegDecodeError(`missing AC Huffman table ${acId}`);
    scanComponents.push({ component, dcTree: dcTrees[dcId], acTree: acTrees[acId] });
  }
  const spectralStart = segment[1 + count * 2];
  const spectralEnd = segment[2 + count * 2];
  const approximation = segment[3 + count * 2];
  if (spectralStart !== 0 || spectralEnd !== 63 || approximation !== 0) {
    throw new JpegDecodeError('successive approximation inside a baseline scan: the file is progressive');
  }
  return scanComponents;
}

function decodeScan(data, start, frame, scanComponents, quantTables, restartInterval) {
  const reader = new EntropyReader(data, start);
  const coefficients = new Int32Array(64);
  const scratch = new Float64Array(64);
  let mcusDone = 0;

  for (let mcuRow = 0; mcuRow < frame.mcusPerColumn; mcuRow++) {
    for (let mcuCol = 0; mcuCol < frame.mcusPerLine; mcuCol++) {
      if (restartInterval > 0 && mcusDone > 0 && mcusDone % restartInterval === 0) {
        reader.readRestartMarker();
        for (const { component } of scanComponents) component.predictor.value = 0;
      }
      for (const { component, dcTree, acTree } of scanComponents) {
        const quant = quantTables[component.quantId];
        if (!quant) throw new JpegDecodeError(`component ${component.id} references missing quantisation table ${component.quantId}`);
        for (let j = 0; j < component.v; j++) {
          for (let i = 0; i < component.h; i++) {
            decodeBlock(reader, coefficients, dcTree, acTree, quant, component.predictor);
            const blockRow = mcuRow * component.v + j;
            const blockCol = mcuCol * component.h + i;
            component.blockData.set(coefficients, (blockRow * component.blocksPerLine + blockCol) * 64);
          }
        }
      }
      mcusDone += 1;
    }
  }

  const after = reader.expectEndOfScan();

  // Dequantise + inverse DCT every block into the component's pixel plane.
  for (const component of frame.components) {
    const { blocksPerLine, blocksPerColumn, blockData, output } = component;
    for (let blockRow = 0; blockRow < blocksPerColumn; blockRow++) {
      for (let blockCol = 0; blockCol < blocksPerLine; blockCol++) {
        idctBlock(
          blockData.subarray((blockRow * blocksPerLine + blockCol) * 64, (blockRow * blocksPerLine + blockCol) * 64 + 64),
          scratch,
          output,
          (blockRow * 8) * component.outputWidth + blockCol * 8,
          component.outputWidth,
        );
      }
    }
  }
  return after;
}

/** Nearest-neighbour (box) chroma upsampling, matching what a phone resampler does. */
function sampleAt(component, x, y) {
  const sx = Math.min(component.outputWidth - 1, Math.floor(x * component.ratioX));
  const sy = Math.min(component.outputHeight - 1, Math.floor(y * component.ratioY));
  return component.output[sy * component.outputWidth + sx];
}

function clamp255(value) {
  return value < 0 ? 0 : value > 255 ? 255 : value | 0;
}

function buildRgb(frame, adobeTransform) {
  const { width, height, components } = frame;
  const out = new Uint8Array(width * height * 3);

  let model;
  if (components.length === 1) model = 'grey';
  else if (components.length === 3) {
    const [r, g, b] = components;
    const rgbIds = r.id === 82 && g.id === 71 && b.id === 66;
    model = adobeTransform === 0 || rgbIds ? 'rgb' : 'ycbcr';
  } else {
    throw new JpegDecodeError(`unsupported colour space: ${components.length} components (CMYK/YCCK are not supported)`);
  }

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const o = (y * width + x) * 3;
      if (model === 'grey') {
        const v = sampleAt(components[0], x, y);
        out[o] = v;
        out[o + 1] = v;
        out[o + 2] = v;
      } else if (model === 'rgb') {
        out[o] = sampleAt(components[0], x, y);
        out[o + 1] = sampleAt(components[1], x, y);
        out[o + 2] = sampleAt(components[2], x, y);
      } else {
        const Y = sampleAt(components[0], x, y);
        const Cb = sampleAt(components[1], x, y) - 128;
        const Cr = sampleAt(components[2], x, y) - 128;
        out[o] = clamp255(Y + 1.402 * Cr);
        out[o + 1] = clamp255(Y - 0.344136 * Cb - 0.714136 * Cr);
        out[o + 2] = clamp255(Y + 1.772 * Cb);
      }
    }
  }
  return { width, height, data: out, source: 'jpeg', components: components.length, colourModel: model };
}

/**
 * Decode a baseline JPEG into RGB bytes.
 * @param {Buffer|Uint8Array} input raw file bytes
 * @returns {{width:number,height:number,data:Uint8Array,source:string}}
 */
export function decodeJpeg(input) {
  const buf = toBuffer(input);
  if (buf.length < 4) throw new JpegDecodeError('file is too short to be a JPEG');
  if (!isJpeg(buf)) throw new JpegDecodeError('not a JPEG: missing SOI marker');

  const quantTables = [];
  const dcTrees = [];
  const acTrees = [];
  let frame = null;
  let restartInterval = 0;
  let adobeTransform = null;
  let scanSeen = false;
  let offset = 2;

  while (offset < buf.length) {
    if (buf[offset] !== 0xff) {
      offset += 1; // resynchronise: some encoders pad between segments
      continue;
    }
    let marker = buf[offset + 1];
    while (marker === 0xff && offset + 2 < buf.length) {
      offset += 1;
      marker = buf[offset + 1];
    }
    if (marker === undefined) break;
    offset += 2;

    if (marker === 0xd9) break; // EOI
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue; // TEM / stray RSTn

    if (marker === 0xda) {
      if (!frame) throw new JpegDecodeError('SOS before any frame header');
      if (scanSeen) throw new JpegDecodeError('a baseline JPEG must contain exactly one scan (multiple scans means progressive)');
      const length = buf.readUInt16BE(offset);
      if (length < 2 || offset + length > buf.length) throw new JpegDecodeError('truncated SOS segment');
      const scanComponents = readScanHeader(buf.subarray(offset + 2, offset + length), frame, dcTrees, acTrees);
      offset = decodeScan(buf, offset + length, frame, scanComponents, quantTables, restartInterval);
      scanSeen = true;
      continue;
    }

    const length = buf.readUInt16BE(offset);
    if (length < 2 || offset + length > buf.length) throw new JpegDecodeError(`segment 0xFF${marker.toString(16).padStart(2, '0')} declares a length that runs past the end of the file`);
    const segment = buf.subarray(offset + 2, offset + length);

    switch (marker) {
      case 0xdb: { // DQT
        let pos = 0;
        while (pos < segment.length) {
          const spec = segment[pos++];
          const precision = spec >> 4;
          const id = spec & 15;
          if (id > 3) throw new JpegDecodeError(`quantisation table id ${id} is invalid`);
          if (precision > 1) throw new JpegDecodeError(`unsupported quantisation table precision ${precision}`);
          const size = precision === 0 ? 64 : 128;
          if (pos + size > segment.length) throw new JpegDecodeError('truncated DQT segment');
          const table = new Int32Array(64);
          for (let i = 0; i < 64; i++) {
            table[ZIGZAG[i]] = precision === 0 ? segment[pos + i] : segment.readUInt16BE(pos + i * 2);
          }
          quantTables[id] = table;
          pos += size;
        }
        break;
      }
      case 0xc4: { // DHT
        let pos = 0;
        while (pos < segment.length) {
          const spec = segment[pos++];
          const tableClass = spec >> 4;
          const id = spec & 15;
          if (tableClass > 1) throw new JpegDecodeError(`unsupported Huffman table class ${tableClass}`);
          if (id > 3) throw new JpegDecodeError(`Huffman table id ${id} is invalid`);
          if (pos + 16 > segment.length) throw new JpegDecodeError('truncated DHT segment');
          const counts = new Uint8Array(16);
          let total = 0;
          for (let i = 0; i < 16; i++) {
            counts[i] = segment[pos + i];
            total += counts[i];
          }
          pos += 16;
          if (pos + total > segment.length) throw new JpegDecodeError('truncated DHT segment');
          const values = new Uint8Array(total);
          for (let i = 0; i < total; i++) values[i] = segment[pos + i];
          pos += total;
          const table = buildHuffmanTable(counts, values);
          if (tableClass === 0) dcTrees[id] = table;
          else acTrees[id] = table;
        }
        break;
      }
      case 0xdd: // DRI
        restartInterval = segment.readUInt16BE(0);
        break;
      case 0xee: // APP14 Adobe
        if (segment.length >= 12 && segment.toString('latin1', 0, 5) === 'Adobe') adobeTransform = segment[11];
        break;
      case 0xc0:
      case 0xc1:
        if (frame) throw new JpegDecodeError('the file contains more than one frame header');
        frame = readFrameHeader(segment, marker);
        break;
      case 0xdc:
        throw new JpegDecodeError('DNL (define number of lines) is not supported');
      default:
        if (marker in UNSUPPORTED_FRAMES) throw new JpegDecodeError(UNSUPPORTED_FRAMES[marker]);
        break;
    }
    offset += length;
  }

  if (!frame) throw new JpegDecodeError('no frame header found: the file is not a usable JPEG');
  if (!scanSeen) throw new JpegDecodeError('no scan found: the file contains no image data');
  return buildRgb(frame, adobeTransform);
}