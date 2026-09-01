/*
 * Builds the guest module that ships inside the kernel, emits it both as a
 * .wasm file (so it can be run through Node and through the host harness) and
 * as a C array (so the kernel can carry it without a filesystem).
 *
 *   node tests/gen_demo_module.js
 *
 * The guest draws animated rings. It deliberately uses the whole feature set
 * we care about: imports, linear memory with a data section, nested loops and
 * signed arithmetic. It never receives a pointer to anything the host owns; it
 * asks for the window size and paints in its own coordinate space starting at
 * 0,0, and the host translates and clips.
 */
const fs = require("fs");
const path = require("path");

const uleb = (v) => { const o = []; do { let b = v & 0x7f; v >>>= 7; if (v) b |= 0x80; o.push(b); } while (v); return o; };
const sleb = (v) => {
  const o = []; let more = true;
  while (more) {
    let b = v & 0x7f; v >>= 7;
    if ((v === 0 && !(b & 0x40)) || (v === -1 && (b & 0x40))) more = false; else b |= 0x80;
    o.push(b);
  }
  return o;
};
const vec = (items) => [...uleb(items.length), ...items.flat()];
const section = (id, payload) => [id, ...uleb(payload.length), ...payload];
const str = (s) => [...uleb(s.length), ...[...s].map((ch) => ch.charCodeAt(0))];

const I32 = 0x7f;
const O = {
  block: 0x02, loop: 0x03, if: 0x04, else: 0x05, end: 0x0b, br: 0x0c, br_if: 0x0d,
  call: 0x10, drop: 0x1a, local_get: 0x20, local_set: 0x21, i32_load: 0x28,
  i32_const: 0x41, ge_s: 0x4e, add: 0x6a, sub: 0x6b, mul: 0x6c, div_s: 0x6d, and: 0x71,
};
const c = (n) => [O.i32_const, ...sleb(n)];
const get = (n) => [O.local_get, ...uleb(n)];
const set = (n) => [O.local_set, ...uleb(n)];

// imported host functions, in index order
const IMPORTS = [
  { name: "set_color", params: 1, results: 0 },  // 0
  { name: "fill_rect", params: 4, results: 0 },  // 1
  { name: "width",     params: 0, results: 1 },  // 2
  { name: "height",    params: 0, results: 1 },  // 3
  { name: "ticks",     params: 0, results: 1 },  // 4
];

const PALETTE = [
  0x0e1620, 0x14303a, 0x1a4a54, 0x20646e,
  0x267e88, 0x2c98a2, 0x32b2bc, 0x38ccd6,
];
const CELL = 12;

// locals: 0 t, 1 cols, 2 rows, 3 j, 4 i, 5 d, 6 tmp
const T = 0, COLS = 1, ROWS = 2, J = 3, I = 4, D = 5, TMP = 6;

const body = [
  ...[O.call, 4], ...c(48), O.div_s, ...set(T),          // t = ticks() / 48
  ...[O.call, 2], ...c(CELL), O.div_s, ...set(COLS),
  ...[O.call, 3], ...c(CELL), O.div_s, ...set(ROWS),
  ...c(0), ...set(J),

  O.block, 0x40,
    O.loop, 0x40,
      ...get(J), ...get(ROWS), O.ge_s, O.br_if, 1,
      ...c(0), ...set(I),

      O.block, 0x40,
        O.loop, 0x40,
          ...get(I), ...get(COLS), O.ge_s, O.br_if, 1,

          // d = (i - cols/2)^2 + (j - rows/2)^2
          ...get(I), ...get(COLS), ...c(2), O.div_s, O.sub, ...set(TMP),
          ...get(TMP), ...get(TMP), O.mul, ...set(D),
          ...get(J), ...get(ROWS), ...c(2), O.div_s, O.sub, ...set(TMP),
          ...get(D), ...get(TMP), ...get(TMP), O.mul, O.add, ...set(D),

          // colour = palette[(d + t) & 7], palette lives in linear memory
          ...get(D), ...get(T), O.add, ...c(7), O.and, ...c(4), O.mul,
          O.i32_load, 2, 0,
          O.call, 0,

          // fill_rect(i*CELL, j*CELL, CELL-1, CELL-1)
          ...get(I), ...c(CELL), O.mul,
          ...get(J), ...c(CELL), O.mul,
          ...c(CELL - 1), ...c(CELL - 1),
          O.call, 1,

          ...get(I), ...c(1), O.add, ...set(I),
          O.br, 0,
        O.end,
      O.end,

      ...get(J), ...c(1), O.add, ...set(J),
      O.br, 0,
    O.end,
  O.end,
];

// signatures, deduplicated
const sigs = [];
const sigIndex = (np, nr) => {
  const key = np + ":" + nr;
  let i = sigs.findIndex((s) => s.key === key);
  if (i < 0) { i = sigs.length; sigs.push({ key, np, nr }); }
  return i;
};
IMPORTS.forEach((im) => (im.type = sigIndex(im.params, im.results)));
const drawType = sigIndex(0, 0);

const paletteBytes = PALETTE.flatMap((v) => [v & 255, (v >> 8) & 255, (v >> 16) & 255, (v >> 24) & 255]);
const localsDecl = [[...uleb(7), I32]];
const code = [...vec(localsDecl), ...body, O.end];

const bin = Uint8Array.from([
  0x00, 0x61, 0x73, 0x6d, 1, 0, 0, 0,
  ...section(1, vec(sigs.map((s) => [0x60, ...vec(Array(s.np).fill([I32])), ...vec(Array(s.nr).fill([I32]))]))),
  ...section(2, vec(IMPORTS.map((im) => [...str("env"), ...str(im.name), 0x00, ...uleb(im.type)]))),
  ...section(3, vec([uleb(drawType)])),
  ...section(5, vec([[0x00, ...uleb(1)]])),
  ...section(7, vec([[...str("draw"), 0x00, ...uleb(IMPORTS.length)]])),
  ...section(10, vec([[...uleb(code.length), ...code]])),
  ...section(11, vec([[0x00, O.i32_const, ...sleb(0), O.end, ...uleb(paletteBytes.length), ...paletteBytes]])),
]);

// Sanity check against the reference engine before emitting anything.
if (!WebAssembly.validate(bin)) {
  console.error("FAIL: the reference validator rejected the demo module");
  process.exit(1);
}
let rects = 0;
const inst = new WebAssembly.Instance(new WebAssembly.Module(bin), {
  env: {
    set_color: () => {},
    fill_rect: () => { rects++; },
    width: () => 360,
    height: () => 240,
    ticks: () => 1000,
  },
});
inst.exports.draw();
console.log(`reference run: ${rects} rectangles for a 360x240 window, module is ${bin.length} bytes`);
if (rects !== Math.floor(360 / CELL) * Math.floor(240 / CELL)) {
  console.error("FAIL: unexpected rectangle count");
  process.exit(1);
}

fs.mkdirSync(path.join(__dirname, "build"), { recursive: true });
fs.writeFileSync(path.join(__dirname, "build", "demo.wasm"), bin);

const rows = [];
for (let i = 0; i < bin.length; i += 12) {
  rows.push("    " + [...bin.slice(i, i + 12)].map((b) => "0x" + b.toString(16).padStart(2, "0")).join(", "));
}
fs.writeFileSync(path.join(__dirname, "..", "wasm", "demo_module.h"),
`/*
 * Generated by tests/gen_demo_module.js - do not edit by hand.
 *
 * A WebAssembly guest embedded in the kernel image. It imports set_color,
 * fill_rect, width, height and ticks from the host and exports draw().
 */
#ifndef JVMOS_WASM_DEMO_H
#define JVMOS_WASM_DEMO_H

static const unsigned char wasm_demo_module[] = {
${rows.join(",\n")}
};

#endif
`);
console.log("wrote wasm/demo_module.h and tests/build/demo.wasm");
