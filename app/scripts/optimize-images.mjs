#!/usr/bin/env node
// Makes the interface's JPEG and PNG pictures smaller without changing a single pixel, before they go into the app
// (every picture in src/assets is embedded in flint-app.exe).
//
//   node scripts/optimize-images.mjs src/assets/wallpapers/*.jpg src/assets/news/*.png …   (prints what it would save)
//   node scripts/optimize-images.mjs --write <files>                                       (replaces them)
//
// JPEG (baseline): the scan's coded symbols and extra bits are kept exactly, so the DCT coefficients and every
// decoded pixel stay the same, and only the Huffman tables are rebuilt from the picture's own symbol counts (what
// `jpegtran -optimize -copy none` does). The file stays baseline, not progressive, so it decodes as fast as before.
// PNG: the same pixels (and palette and transparency), each row re-filtered with whichever of the five filters
// compresses best and deflated at level 9; chunks a browser ignores (pHYs, tEXt, tIME…) are left out.
// A file is replaced only when it gets smaller and reading the new file back gives exactly what the old one held.
import { readFileSync, writeFileSync } from "node:fs";
import zlib from "node:zlib";

function optimizeJpeg(buf) {
  if (buf[0] !== 0xff || buf[1] !== 0xd8) throw new Error("not a JPEG");
  let i = 2;
  const keep = []; // segments to copy (APPn, DQT, SOF0, DRI, COM...), in order, minus DHT
  const huff = { 0: {}, 1: {} }; // class -> id -> decoder
  let sof = null, sos = null, dri = 0;
  for (;;) {
    if (buf[i] !== 0xff) throw new Error("marker expected at " + i);
    let m = buf[i + 1];
    while (m === 0xff) { i++; m = buf[i + 1]; }
    const len = buf.readUInt16BE(i + 2);
    const seg = buf.subarray(i, i + 2 + len);
    if (m === 0xc4) {
      let p = i + 4;
      while (p < i + 2 + len) {
        const tc = buf[p] >> 4, th = buf[p] & 15;
        const bits = [...buf.subarray(p + 1, p + 17)];
        const n = bits.reduce((a, b) => a + b, 0);
        const vals = [...buf.subarray(p + 17, p + 17 + n)];
        huff[tc][th] = decoderFor(bits, vals);
        p += 17 + n;
      }
      i += 2 + len;
    } else if (m === 0xda) {
      const ns = buf[i + 4];
      sos = { seg, comps: [] };
      for (let k = 0; k < ns; k++) sos.comps.push({ id: buf[i + 5 + 2 * k], td: buf[i + 6 + 2 * k] >> 4, ta: buf[i + 6 + 2 * k] & 15 });
      const ss = buf[i + 5 + 2 * ns], se = buf[i + 6 + 2 * ns], ahal = buf[i + 7 + 2 * ns];
      if (ss !== 0 || se !== 63 || ahal !== 0) throw new Error("not a baseline scan");
      i += 2 + len;
      break;
    } else {
      if (m >= 0xc1 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) throw new Error("not baseline (SOF" + m.toString(16) + ")");
      if (m === 0xc0) {
        const nf = buf[i + 9];
        sof = { h: buf.readUInt16BE(i + 5), w: buf.readUInt16BE(i + 7), comps: [] };
        for (let k = 0; k < nf; k++) sof.comps.push({ id: buf[i + 10 + 3 * k], h: buf[i + 11 + 3 * k] >> 4, v: buf[i + 11 + 3 * k] & 15 });
      }
      if (m === 0xdd) dri = buf.readUInt16BE(i + 4);
      keep.push(seg);
      i += 2 + len;
    }
  }
  // --- entropy-decode the scan into symbols ---
  const comps = sos.comps.map((c) => ({ ...c, ...sof.comps.find((s) => s.id === c.id) }));
  const hmax = Math.max(...sof.comps.map((c) => c.h)), vmax = Math.max(...sof.comps.map((c) => c.v));
  const single = comps.length === 1;
  let mcux, mcuy;
  if (single) {
    const c = comps[0];
    mcux = Math.ceil(Math.ceil((sof.w * c.h) / hmax) / 8); mcuy = Math.ceil(Math.ceil((sof.h * c.v) / vmax) / 8);
  } else { mcux = Math.ceil(sof.w / (8 * hmax)); mcuy = Math.ceil(sof.h / (8 * vmax)); }
  let pos = i, bitbuf = 0, bitcnt = 0, hitMarker = false;
  const readBit = () => {
    if (bitcnt === 0) {
      if (hitMarker) { bitbuf = 0; bitcnt = 8; } else {
        let b = buf[pos++];
        if (b === 0xff) { const nx = buf[pos]; if (nx === 0) pos++; else { hitMarker = true; pos--; b = 0; } }
        bitbuf = b; bitcnt = 8;
      }
    }
    bitcnt--; return (bitbuf >> bitcnt) & 1;
  };
  const readBits = (n) => { let v = 0; for (let k = 0; k < n; k++) v = (v << 1) | readBit(); return v; };
  const decode = (t) => { let code = 0; for (let l = 1; l <= 16; l++) { code = (code << 1) | readBit(); const s = t.map[l].get(code); if (s !== undefined) return s; } throw new Error("bad huffman code at " + pos); };
  const syms = []; // [tableKey, symbol, extraBitCount, extraBits]; tableKey -1 = restart marker
  const total = mcux * mcuy;
  const blocksOf = (c) => (single ? 1 : c.h * c.v);
  for (let mcu = 0; mcu < total; mcu++) {
    if (dri && mcu > 0 && mcu % dri === 0) {
      bitcnt = 0; hitMarker = false;
      if (buf[pos] !== 0xff || (buf[pos + 1] & 0xf8) !== 0xd0) throw new Error("RST expected");
      pos += 2; syms.push([-1, 0, 0, 0]);
    }
    for (const c of comps) {
      for (let b = 0; b < blocksOf(c); b++) {
        const dc = decode(huff[0][c.td]); const dv = readBits(dc); syms.push([c.td * 2, dc, dc, dv]);
        for (let k = 1; k < 64; ) {
          const rs = decode(huff[1][c.ta]); const s = rs & 15, r = rs >> 4;
          const v = readBits(s); syms.push([c.ta * 2 + 1, rs, s, v]);
          if (s === 0) { if (r === 15) { k += 16; continue; } break; }
          k += r + 1;
        }
      }
    }
  }
  // what follows the scan: EOI
  while (!(buf[pos] === 0xff && buf[pos + 1] !== 0 && !(buf[pos + 1] >= 0xd0 && buf[pos + 1] <= 0xd7))) pos++;
  const tail = buf.subarray(pos);
  if (tail[0] !== 0xff || tail[1] !== 0xd9 || tail.length !== 2) throw new Error("expected only EOI after the scan, got " + tail.length + " bytes");
  // --- optimal tables ---
  const freq = {};
  for (const [t, s] of syms) if (t >= 0) { freq[t] ??= new Array(257).fill(0); freq[t][s]++; }
  const tables = {};
  for (const t of Object.keys(freq)) tables[t] = buildTable(freq[t]);
  // --- write: the same segments, the new tables in place of the old ones (after SOF0), the same scan symbols ---
  const out = [buf.subarray(0, 2)];
  const sofIndex = keep.findIndex((s) => s[1] === 0xc0);
  keep.forEach((s, k) => { out.push(s); if (k === sofIndex) out.push(dht(tables)); });
  out.push(sos.seg);
  const w = new Writer();
  let rst = 0;
  for (const [t, s, n, v] of syms) {
    if (t < 0) { w.flush(); w.bytes.push(0xff, 0xd0 + (rst++ & 7)); continue; }
    const { codes, lens } = tables[t];
    if (!lens[s]) throw new Error("symbol without a code");
    w.put(codes[s], lens[s]); if (n) w.put(v, n);
  }
  w.flush();
  out.push(Buffer.from(w.bytes), tail);
  return { jpeg: Buffer.concat(out), symbols: syms };
}

function decoderFor(bits, vals) {
  const map = {}; let code = 0, k = 0;
  for (let l = 1; l <= 16; l++) { map[l] = new Map(); for (let n = 0; n < bits[l - 1]; n++) map[l].set(code++, vals[k++]); code <<= 1; }
  return { map };
}

// JPEG Annex K.2: code lengths from the counts, with a reserved symbol (256) so no code is all ones, limited to 16 bits.
function buildTable(counts) {
  const freq = counts.slice(); freq[256] = 1;
  const codesize = new Array(257).fill(0), others = new Array(257).fill(-1);
  for (;;) {
    let c1 = -1, c2 = -1, v = Infinity;
    for (let i = 0; i <= 256; i++) if (freq[i] && freq[i] <= v) { v = freq[i]; c1 = i; }
    v = Infinity;
    for (let i = 0; i <= 256; i++) if (freq[i] && freq[i] <= v && i !== c1) { v = freq[i]; c2 = i; }
    if (c2 < 0) break;
    freq[c1] += freq[c2]; freq[c2] = 0;
    codesize[c1]++; while (others[c1] >= 0) { c1 = others[c1]; codesize[c1]++; }
    others[c1] = c2;
    codesize[c2]++; while (others[c2] >= 0) { c2 = others[c2]; codesize[c2]++; }
  }
  const bits = new Array(33).fill(0);
  for (let i = 0; i <= 256; i++) if (codesize[i]) bits[codesize[i]]++;
  for (let i = 32; i > 16; i--) {
    while (bits[i] > 0) {
      let j = i - 2; while (bits[j] === 0) j--;
      bits[i] -= 2; bits[i - 1]++; bits[j + 1] += 2; bits[j]--;
    }
  }
  let top = 16; while (bits[top] === 0) top--; bits[top]--; // remove the reserved code point
  const order = [];
  for (let l = 1; l <= 32; l++) for (let s = 0; s < 256; s++) if (codesize[s] === l) order.push(s);
  const lens = new Array(256).fill(0), codes = new Array(256).fill(0);
  let k = 0;
  for (let l = 1; l <= 16; l++) for (let n = 0; n < bits[l]; n++) lens[order[k++]] = l;
  let code = 0; k = 0;
  for (let l = 1; l <= 16; l++) { for (let n = 0; n < bits[l]; n++) codes[order[k++]] = code++; code <<= 1; }
  const used = bits.slice(1, 17);
  return { bits: used, vals: order.slice(0, used.reduce((a, b) => a + b, 0)), codes, lens };
}

function dht(tables) {
  const parts = [];
  for (const t of Object.keys(tables).map(Number).sort((a, b) => (a % 2) - (b % 2) || a - b)) {
    const tc = t % 2, th = t >> 1, { bits, vals } = tables[t];
    parts.push(Buffer.from([(tc << 4) | th, ...bits, ...vals]));
  }
  const body = Buffer.concat(parts);
  const h = Buffer.from([0xff, 0xc4, 0, 0]); h.writeUInt16BE(body.length + 2, 2);
  return Buffer.concat([h, body]);
}

class Writer {
  bytes = []; acc = 0; n = 0;
  put(v, len) { for (let k = len - 1; k >= 0; k--) { this.acc = (this.acc << 1) | ((v >> k) & 1); if (++this.n === 8) this.emit(); } }
  emit() { this.bytes.push(this.acc); if (this.acc === 0xff) this.bytes.push(0); this.acc = 0; this.n = 0; }
  flush() { while (this.n) { this.acc = (this.acc << 1) | 1; if (++this.n === 8) { this.emit(); break; } } }
}

const crcT = new Int32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc32 = (b) => { let c = -1; for (let i = 0; i < b.length; i++) c = crcT[(c ^ b[i]) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0; };

function chunks(buf) {
  const out = []; let p = 8;
  while (p < buf.length) {
    const len = buf.readUInt32BE(p), type = buf.subarray(p + 4, p + 8).toString("latin1");
    out.push({ type, data: buf.subarray(p + 8, p + 8 + len) });
    p += 12 + len;
  }
  return out;
}
function chunk(type, data) {
  const h = Buffer.alloc(8); h.writeUInt32BE(data.length, 0); h.write(type, 4, "latin1");
  const c = Buffer.alloc(4); c.writeUInt32BE(crc32(Buffer.concat([h.subarray(4), data])), 0);
  return Buffer.concat([h, data, c]);
}
const paeth = (a, b, c) => { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); return pa <= pb && pa <= pc ? a : pb <= pc ? b : c; };

function rawRows(ihdr, idat) {
  const w = ihdr.readUInt32BE(0), h = ihdr.readUInt32BE(4), depth = ihdr[8], color = ihdr[9], interlace = ihdr[12];
  if (interlace) throw new Error("interlaced");
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  const bpp = Math.max(1, (channels * depth) >> 3), stride = Math.ceil((w * channels * depth) / 8);
  const data = zlib.inflateSync(idat);
  const rows = []; let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = data[y * (stride + 1)], src = data.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)), row = Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? row[x - bpp] : 0, b = prev[x], c = x >= bpp ? prev[x - bpp] : 0;
      row[x] = (src[x] + [0, a, b, (a + b) >> 1, paeth(a, b, c)][f]) & 0xff;
    }
    rows.push(row); prev = row;
  }
  return { rows, bpp, stride };
}

function encode({ rows, bpp, stride }, strategy) {
  const out = []; let prev = Buffer.alloc(stride);
  for (const row of rows) {
    let best = null, bestScore = Infinity;
    const filters = strategy === "none" ? [0] : [0, 1, 2, 3, 4];
    for (const f of filters) {
      const o = Buffer.alloc(stride + 1); o[0] = f; let score = 0;
      for (let x = 0; x < stride; x++) {
        const a = x >= bpp ? row[x - bpp] : 0, b = prev[x], c = x >= bpp ? prev[x - bpp] : 0;
        const v = (row[x] - [0, a, b, (a + b) >> 1, paeth(a, b, c)][f]) & 0xff;
        o[x + 1] = v; score += v < 128 ? v : 256 - v;
      }
      if (score < bestScore) { bestScore = score; best = o; }
    }
    out.push(best); prev = row;
  }
  return zlib.deflateSync(Buffer.concat(out), { level: 9, memLevel: 9, strategy: zlib.constants.Z_DEFAULT_STRATEGY });
}

function optimizePng(buf) {
  const cs = chunks(buf);
  const ihdr = cs.find((c) => c.type === "IHDR").data;
  const idat = Buffer.concat(cs.filter((c) => c.type === "IDAT").map((c) => c.data));
  const raw = rawRows(ihdr, idat);
  let best = null;
  for (const strategy of ["none", "minsum"]) {
    const data = encode(raw, strategy);
    if (!best || data.length < best.length) best = data;
  }
  const keep = cs.filter((c) => KEPT.includes(c.type));
  const png = Buffer.concat([buf.subarray(0, 8), ...keep.map((c) => chunk(c.type, c.data)), chunk("IDAT", best), chunk("IEND", Buffer.alloc(0))]);
  const same = rawRows(ihdr, best).rows.every((row, i) => row.equals(raw.rows[i]));
  return { png, same };
}
const KEPT = ["IHDR", "PLTE", "tRNS", "gAMA", "sRGB", "iCCP", "cHRM"];

const write = process.argv.includes("--write");
let before = 0, after = 0;
for (const file of process.argv.slice(2).filter((a) => a !== "--write")) {
  const src = readFileSync(file);
  let out, same;
  if (/\.jpe?g$/i.test(file)) {
    const result = optimizeJpeg(src);
    const again = optimizeJpeg(result.jpeg).symbols;
    same = again.length === result.symbols.length && again.every((s, k) => s.every((v, j) => v === result.symbols[k][j]));
    out = result.jpeg;
  } else if (/\.png$/i.test(file)) {
    ({ png: out, same } = optimizePng(src));
  } else {
    console.log(`${file}: not a JPEG or PNG, left as it is`);
    continue;
  }
  if (!same) throw new Error(`${file}: the optimised copy doesn't read back the same; left as it is`);
  const smaller = out.length < src.length;
  before += src.length;
  after += smaller ? out.length : src.length;
  console.log(`${file}: ${src.length} -> ${smaller ? out.length : src.length}`);
  if (write && smaller) writeFileSync(file, out);
}
console.log(`${before} -> ${after} bytes${write ? "" : " (nothing written; --write replaces the files)"}`);
