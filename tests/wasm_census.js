/*
 * Lists the sections, imports, exports and the distinct opcodes a module uses,
 * and flags anything our interpreter does not implement.
 *
 * Compiling first and then reading off exactly what the compiler emitted beats
 * guessing which corners of the spec a real toolchain needs.
 *
 *   node tests/wasm_census.js <module.wasm>
 */
const fs = require("fs");

const buf = fs.readFileSync(process.argv[2]);
let p = 8;

const u8 = () => buf[p++];
const uleb = () => { let r = 0, s = 0, b; do { b = buf[p++]; r |= (b & 0x7f) << s; s += 7; } while (b & 0x80); return r >>> 0; };
const sleb = () => { let r = 0, s = 0, b; do { b = buf[p++]; r |= (b & 0x7f) << s; s += 7; } while (b & 0x80); if (s < 32 && (b & 0x40)) r |= -(1 << s); return r; };

const SECTION_NAMES = {
  0: "custom", 1: "type", 2: "import", 3: "function", 4: "table", 5: "memory",
  6: "global", 7: "export", 8: "start", 9: "element", 10: "code", 11: "data", 12: "datacount",
};

// Opcodes the interpreter implements.
const SUPPORTED = new Set([
  0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
  0x1a, 0x1b, 0x20, 0x21, 0x22, 0x23, 0x24,
  0x28, 0x2c, 0x2d, 0x2e, 0x2f, 0x36, 0x3a, 0x3b, 0x3f, 0x40, 0x41, 0x45,
  0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, 0x4f,
  0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72,
  0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
]);

const NAMES = {
  0x00: "unreachable", 0x01: "nop", 0x02: "block", 0x03: "loop", 0x04: "if", 0x05: "else",
  0x0b: "end", 0x0c: "br", 0x0d: "br_if", 0x0e: "br_table", 0x0f: "return",
  0x10: "call", 0x11: "call_indirect", 0x1a: "drop", 0x1b: "select",
  0x20: "local.get", 0x21: "local.set", 0x22: "local.tee", 0x23: "global.get", 0x24: "global.set",
  0x28: "i32.load", 0x29: "i64.load", 0x2c: "i32.load8_s", 0x2d: "i32.load8_u",
  0x2e: "i32.load16_s", 0x2f: "i32.load16_u", 0x36: "i32.store", 0x37: "i64.store",
  0x3a: "i32.store8", 0x3b: "i32.store16", 0x3f: "memory.size", 0x40: "memory.grow",
  0x41: "i32.const", 0x42: "i64.const", 0x45: "i32.eqz",
  0x46: "i32.eq", 0x47: "i32.ne", 0x48: "i32.lt_s", 0x49: "i32.lt_u", 0x4a: "i32.gt_s",
  0x4b: "i32.gt_u", 0x4c: "i32.le_s", 0x4d: "i32.le_u", 0x4e: "i32.ge_s", 0x4f: "i32.ge_u",
  0x67: "i32.clz", 0x68: "i32.ctz", 0x69: "i32.popcnt", 0x6a: "i32.add", 0x6b: "i32.sub",
  0x6c: "i32.mul", 0x6d: "i32.div_s", 0x6e: "i32.div_u", 0x6f: "i32.rem_s", 0x70: "i32.rem_u",
  0x71: "i32.and", 0x72: "i32.or", 0x73: "i32.xor", 0x74: "i32.shl", 0x75: "i32.shr_s",
  0x76: "i32.shr_u", 0x77: "i32.rotl", 0x78: "i32.rotr",
  0xc0: "i32.extend8_s", 0xc1: "i32.extend16_s",
};

const sections = {};
const codeBodies = [];
let importList = [], exportList = [], memPages = 0;

while (p < buf.length) {
  const id = u8();
  const size = uleb();
  const end = p + size;
  sections[SECTION_NAMES[id] || ("id" + id)] = size;

  if (id === 2) {
    const n = uleb();
    for (let i = 0; i < n; i++) {
      const ml = uleb(); const mod = buf.slice(p, p + ml).toString(); p += ml;
      const nl = uleb(); const nm = buf.slice(p, p + nl).toString(); p += nl;
      const kind = u8();
      if (kind === 0) uleb();
      else if (kind === 1) { u8(); const f = u8(); uleb(); if (f & 1) uleb(); }
      else if (kind === 2) { const f = u8(); uleb(); if (f & 1) uleb(); }
      else { u8(); u8(); }
      importList.push(`${mod}.${nm} (${["func", "table", "memory", "global"][kind]})`);
    }
  } else if (id === 5) {
    const n = uleb();
    for (let i = 0; i < n; i++) { const f = u8(); memPages = uleb(); if (f & 1) uleb(); }
  } else if (id === 7) {
    const n = uleb();
    for (let i = 0; i < n; i++) {
      const nl = uleb(); const nm = buf.slice(p, p + nl).toString(); p += nl;
      const kind = u8(); const idx = uleb();
      exportList.push(`${nm} (${["func", "table", "memory", "global"][kind]} ${idx})`);
    }
  } else if (id === 10) {
    const n = uleb();
    for (let i = 0; i < n; i++) {
      const sz = uleb();
      const bodyEnd = p + sz;
      const groups = uleb();
      for (let g = 0; g < groups; g++) { uleb(); u8(); }
      codeBodies.push([p, bodyEnd]);
      p = bodyEnd;
    }
  }
  p = end;
}

// opcode census over every function body
const seen = new Map();
for (const [start, end] of codeBodies) {
  p = start;
  while (p < end) {
    const op = u8();
    seen.set(op, (seen.get(op) || 0) + 1);
    if (op === 0x02 || op === 0x03 || op === 0x04) {
      if (buf[p] === 0x40 || (buf[p] >= 0x7b && buf[p] <= 0x7f)) p++; else sleb();
    } else if (op === 0x0c || op === 0x0d || op === 0x10 ||
               (op >= 0x20 && op <= 0x24)) uleb();
    else if (op === 0x0e) { const n = uleb(); for (let i = 0; i <= n; i++) uleb(); }
    else if (op === 0x11) { uleb(); uleb(); }
    else if (op >= 0x28 && op <= 0x3e) { uleb(); uleb(); }
    else if (op === 0x3f || op === 0x40) u8();
    else if (op === 0x41 || op === 0x42) sleb();
    else if (op === 0x43) p += 4;
    else if (op === 0x44) p += 8;
    else if (op === 0xfc) { uleb(); }   // saturating / bulk memory prefix
  }
}

console.log(`module: ${buf.length} bytes, memory ${memPages} page(s)`);
console.log("sections: " + Object.entries(sections).map(([k, v]) => `${k}=${v}`).join(" "));
console.log("imports:  " + (importList.join(", ") || "none"));
console.log("exports:  " + (exportList.join(", ") || "none"));
console.log(`functions with bodies: ${codeBodies.length}`);

const missing = [...seen.keys()].filter((op) => !SUPPORTED.has(op)).sort((a, b) => a - b);
console.log(`\ndistinct opcodes used: ${seen.size}`);
if (missing.length === 0) {
  console.log("all of them are implemented by the interpreter");
} else {
  console.log("NOT implemented by the interpreter:");
  for (const op of missing) {
    const name = NAMES[op] || "unknown";
    console.log(`  0x${op.toString(16).padStart(2, "0")}  ${name.padEnd(16)} used ${seen.get(op)}x`);
  }
}
