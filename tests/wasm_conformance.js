/*
 * Builds small WebAssembly modules, runs each one through Node's own
 * WebAssembly engine to get the expected answer, then runs the same module
 * through our interpreter and compares. Node is the oracle, so these tests
 * check real conformance rather than agreeing with my own assumptions.
 *
 *   node tests/wasm_conformance.js <path-to-wasm_host_test.exe>
 */
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const OUT = path.join(__dirname, "build");
const EXE = process.argv[2];

// ---------------------------------------------------------------- encoding
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
const str = (s) => [...uleb(s.length), ...[...s].map((c) => c.charCodeAt(0))];

const I32 = 0x7f;
const OP = {
  unreachable: 0x00, nop: 0x01, block: 0x02, loop: 0x03, if: 0x04, else: 0x05,
  end: 0x0b, br: 0x0c, br_if: 0x0d, br_table: 0x0e, return: 0x0f, call: 0x10,
  drop: 0x1a, select: 0x1b,
  local_get: 0x20, local_set: 0x21, local_tee: 0x22, global_get: 0x23, global_set: 0x24,
  i32_load: 0x28, i32_load8_s: 0x2c, i32_load8_u: 0x2d, i32_load16_s: 0x2e, i32_load16_u: 0x2f,
  i32_store: 0x36, i32_store8: 0x3a, i32_store16: 0x3b, memory_size: 0x3f, memory_grow: 0x40,
  i32_const: 0x41,
  eqz: 0x45, eq: 0x46, ne: 0x47, lt_s: 0x48, lt_u: 0x49, gt_s: 0x4a, gt_u: 0x4b,
  le_s: 0x4c, le_u: 0x4d, ge_s: 0x4e, ge_u: 0x4f,
  clz: 0x67, ctz: 0x68, popcnt: 0x69,
  add: 0x6a, sub: 0x6b, mul: 0x6c, div_s: 0x6d, div_u: 0x6e, rem_s: 0x6f, rem_u: 0x70,
  and: 0x71, or: 0x72, xor: 0x73, shl: 0x74, shr_s: 0x75, shr_u: 0x76, rotl: 0x77, rotr: 0x78,
};
const c = (n) => [OP.i32_const, ...sleb(n)];
const get = (n) => [OP.local_get, ...uleb(n)];
const set = (n) => [OP.local_set, ...uleb(n)];

function build(spec) {
  const imports = spec.imports || [];
  const funcs = spec.funcs || [];
  const globals = spec.globals || [];
  const data = spec.data || [];

  // one type per distinct signature, imports first then local functions
  const sigs = [];
  const sigIndex = (np, nr) => {
    const key = np + ":" + nr;
    let i = sigs.findIndex((s) => s.key === key);
    if (i < 0) { i = sigs.length; sigs.push({ key, np, nr }); }
    return i;
  };
  imports.forEach((im) => (im.type = sigIndex(im.params, im.results)));
  funcs.forEach((fn) => (fn.type = sigIndex(fn.params, fn.results)));

  const parts = [];
  parts.push(section(1, vec(sigs.map((s) => [
    0x60, ...vec(Array(s.np).fill([I32])), ...vec(Array(s.nr).fill([I32])),
  ]))));

  if (imports.length) {
    parts.push(section(2, vec(imports.map((im) => [
      ...str(im.module), ...str(im.name), 0x00, ...uleb(im.type),
    ]))));
  }
  if (funcs.length) parts.push(section(3, vec(funcs.map((fn) => uleb(fn.type)))));
  if (spec.memory) parts.push(section(5, vec([[0x00, ...uleb(spec.memory)]])));
  if (globals.length) {
    parts.push(section(6, vec(globals.map((g) => [
      I32, 0x01, OP.i32_const, ...sleb(g), OP.end,
    ]))));
  }

  const exports = funcs
    .map((fn, i) => (fn.export ? [...str(fn.export), 0x00, ...uleb(imports.length + i)] : null))
    .filter(Boolean);
  parts.push(section(7, vec(exports)));

  parts.push(section(10, vec(funcs.map((fn) => {
    const locals = fn.locals ? [[...uleb(fn.locals), I32]] : [];
    const body = [...vec(locals), ...fn.body, OP.end];
    return [...uleb(body.length), ...body];
  }))));

  if (data.length) {
    parts.push(section(11, vec(data.map((d) => [
      0x00, OP.i32_const, ...sleb(d.offset), OP.end, ...uleb(d.bytes.length), ...d.bytes,
    ]))));
  }

  return Uint8Array.from([0x00, 0x61, 0x73, 0x6d, 1, 0, 0, 0, ...parts.flat()]);
}

// ------------------------------------------------------------------- cases
const cases = [];
const add = (name, spec, entry, args) => cases.push({ name, spec, entry, args });
// Cases where the correct outcome is a refusal. These are the sandbox tests:
// the guest must not be able to reach past the memory it declared.
const addTrap = (name, spec, entry, args) =>
  cases.push({ name, spec, entry, args, trap: true });

add("add", { funcs: [{ params: 2, results: 1, export: "run",
  body: [...get(0), ...get(1), OP.add] }] }, "run", [7, 35]);

add("arith_chain", { funcs: [{ params: 2, results: 1, export: "run",
  body: [...get(0), ...get(1), OP.mul, ...c(100), OP.sub, ...c(3), OP.div_s] }] }, "run", [9, 9]);

add("unsigned_ops", { funcs: [{ params: 2, results: 1, export: "run",
  body: [...get(0), ...get(1), OP.div_u, ...get(0), ...get(1), OP.rem_u, OP.add] }] },
  "run", [-7, 3]);

add("compare", { funcs: [{ params: 2, results: 1, export: "run",
  body: [...get(0), ...get(1), OP.lt_s, ...get(0), ...get(1), OP.lt_u, OP.add] }] },
  "run", [-1, 1]);

add("bits", { funcs: [{ params: 1, results: 1, export: "run",
  body: [...get(0), OP.clz, ...get(0), OP.ctz, OP.add, ...get(0), OP.popcnt, OP.add,
         ...get(0), ...c(5), OP.rotl, OP.add, ...get(0), ...c(3), OP.rotr, OP.add] }] },
  "run", [0x01020304]);

add("shifts", { funcs: [{ params: 2, results: 1, export: "run",
  body: [...get(0), ...get(1), OP.shl, ...get(0), ...get(1), OP.shr_s, OP.add,
         ...get(0), ...get(1), OP.shr_u, OP.add] }] }, "run", [-1000, 3]);

add("locals_tee", { funcs: [{ params: 1, results: 1, locals: 2, export: "run",
  body: [...get(0), ...c(2), OP.mul, ...set(1),
         ...get(1), ...c(5), OP.add, OP.local_tee, 2, ...get(1), OP.add] }] }, "run", [21]);

add("if_else", { funcs: [{ params: 2, results: 1, export: "run",
  body: [...get(0), ...get(1), OP.gt_s, OP.if, I32, ...get(0), OP.else, ...get(1), OP.end] }] },
  "run", [11, 47]);

add("if_no_else", { funcs: [{ params: 1, results: 1, locals: 1, export: "run",
  body: [...c(10), ...set(1),
         ...get(0), OP.if, 0x40, ...c(99), ...set(1), OP.end,
         ...get(1)] }] }, "run", [1]);

add("block_br", { funcs: [{ params: 1, results: 1, export: "run",
  body: [OP.block, I32, ...get(0), ...c(5), OP.gt_s, OP.if, 0x40, ...c(111), OP.br, 1, OP.end,
         ...c(222), OP.end] }] }, "run", [9]);

add("loop_sum", { funcs: [{ params: 1, results: 1, locals: 2, export: "run",
  body: [
    ...c(0), ...set(1),                        // acc
    ...c(1), ...set(2),                        // i
    OP.block, 0x40,
      OP.loop, 0x40,
        ...get(2), ...get(0), OP.gt_s, OP.br_if, 1,
        ...get(1), ...get(2), OP.add, ...set(1),
        ...get(2), ...c(1), OP.add, ...set(2),
        OP.br, 0,
      OP.end,
    OP.end,
    ...get(1)] }] }, "run", [100]);

add("nested_loops", { funcs: [{ params: 2, results: 1, locals: 3, export: "run",
  body: [
    ...c(0), ...set(2),
    ...c(0), ...set(3),
    OP.block, 0x40,
      OP.loop, 0x40,
        ...get(3), ...get(0), OP.ge_s, OP.br_if, 1,
        ...c(0), ...set(4),
        OP.block, 0x40,
          OP.loop, 0x40,
            ...get(4), ...get(1), OP.ge_s, OP.br_if, 1,
            ...get(2), ...c(1), OP.add, ...set(2),
            ...get(4), ...c(1), OP.add, ...set(4),
            OP.br, 0,
          OP.end,
        OP.end,
        ...get(3), ...c(1), OP.add, ...set(3),
        OP.br, 0,
      OP.end,
    OP.end,
    ...get(2)] }] }, "run", [7, 6]);

add("br_table", { funcs: [{ params: 1, results: 1, export: "run",
  body: [
    OP.block, I32,
      OP.block, 0x40,
        OP.block, 0x40,
          OP.block, 0x40,
            ...get(0), OP.br_table, 3, 0, 1, 2, 2,
          OP.end,
          ...c(10), OP.br, 2,
        OP.end,
        ...c(20), OP.br, 1,
      OP.end,
      ...c(30),
    OP.end] }] }, "run", [1]);

add("select_drop", { funcs: [{ params: 3, results: 1, export: "run",
  body: [...get(0), ...get(1), ...get(2), OP.select, ...c(1234), OP.drop] }] },
  "run", [5, 9, 0]);

add("recursion", { funcs: [
  { params: 1, results: 1, body: [
      ...get(0), ...c(2), OP.lt_s,
      OP.if, I32, ...c(1),
      OP.else, ...get(0), ...get(0), ...c(1), OP.sub, OP.call, 0, OP.mul, OP.end] },
  { params: 1, results: 1, export: "run", body: [...get(0), OP.call, 0] },
]}, "run", [10]);

add("memory_i32", { memory: 1, funcs: [{ params: 2, results: 1, export: "run",
  body: [...c(64), ...get(0), OP.i32_store, 2, 0,
         ...c(68), ...get(1), OP.i32_store, 2, 0,
         ...c(64), OP.i32_load, 2, 0, ...c(68), OP.i32_load, 2, 0, OP.add] }] },
  "run", [123456, -654321]);

add("memory_bytes", { memory: 1, funcs: [{ params: 1, results: 1, export: "run",
  body: [...c(16), ...get(0), OP.i32_store8, 0, 0,
         ...c(17), ...c(0xff), OP.i32_store8, 0, 0,
         ...c(16), OP.i32_load8_u, 0, 0,
         ...c(16), OP.i32_load8_s, 0, 0, OP.add,
         ...c(16), OP.i32_load16_u, 1, 0, OP.add] }] }, "run", [200]);

add("memory_data", { memory: 1, data: [{ offset: 8, bytes: [1, 2, 3, 4] }],
  funcs: [{ params: 0, results: 1, export: "run",
    body: [...c(8), OP.i32_load, 2, 0] }] }, "run", []);

add("memory_offset", { memory: 1, funcs: [{ params: 1, results: 1, export: "run",
  body: [...c(32), ...get(0), OP.i32_store, 2, 8,
         ...c(40), OP.i32_load, 2, 0] }] }, "run", [4242]);

add("globals", { globals: [7], funcs: [{ params: 1, results: 1, export: "run",
  body: [OP.global_get, 0, ...get(0), OP.add, OP.global_set, 0,
         OP.global_get, 0, OP.global_get, 0, OP.mul] }] }, "run", [5]);

add("host_import", { imports: [{ module: "env", name: "add3", params: 3, results: 1 }],
  funcs: [{ params: 2, results: 1, export: "run",
    body: [...get(0), ...get(1), ...c(100), OP.call, 0, ...c(2), OP.mul] }] },
  "run", [4, 5]);

add("memory_size", { memory: 2, funcs: [{ params: 0, results: 1, export: "run",
  body: [OP.memory_size, 0x00] }] }, "run", []);

addTrap("oob_store", { memory: 1, funcs: [{ params: 1, results: 1, export: "run",
  body: [...c(70000), ...get(0), OP.i32_store, 2, 0, ...c(0)] }] }, "run", [1]);

addTrap("oob_load", { memory: 1, funcs: [{ params: 0, results: 1, export: "run",
  body: [...c(65534), OP.i32_load, 2, 0] }] }, "run", []);

// A small base plus a static offset that together leave the single declared
// page: the check has to consider the sum, not just the dynamic address.
addTrap("oob_offset", { memory: 1, funcs: [{ params: 0, results: 1, export: "run",
  body: [...c(64), OP.i32_load, 2, ...uleb(65500)] }] }, "run", []);

addTrap("oob_negative", { memory: 1, funcs: [{ params: 0, results: 1, export: "run",
  body: [...c(-4), OP.i32_load, 2, 0] }] }, "run", []);

addTrap("div_zero", { funcs: [{ params: 2, results: 1, export: "run",
  body: [...get(0), ...get(1), OP.div_s] }] }, "run", [10, 0]);

addTrap("unreachable", { funcs: [{ params: 0, results: 1, export: "run",
  body: [OP.unreachable] }] }, "run", []);

// ------------------------------------------------------------------ runner
fs.mkdirSync(OUT, { recursive: true });

let pass = 0, fail = 0, skipped = 0;
for (const t of cases) {
  const bin = build(t.spec);
  const file = path.join(OUT, t.name + ".wasm");
  fs.writeFileSync(file, bin);

  if (!WebAssembly.validate(bin)) {
    console.log(`  ?? ${t.name.padEnd(16)} module rejected by Node's validator (test bug)`);
    skipped++;
    continue;
  }

  let expected, referenceTrapped = false;
  try {
    const inst = new WebAssembly.Instance(new WebAssembly.Module(bin), {
      env: { add3: (a, b, c2) => a + b + c2, mul: (a, b) => Math.imul(a, b) },
    });
    expected = inst.exports[t.entry](...t.args) | 0;
  } catch (e) {
    referenceTrapped = true;
  }
  if (!!t.trap !== referenceTrapped) {
    console.log(`  ?? ${t.name.padEnd(16)} reference disagrees about trapping (test bug)`);
    skipped++;
    continue;
  }

  let got;
  try {
    got = execFileSync(EXE, [file, t.entry, ...t.args.map(String)], { encoding: "utf8" }).trim();
  } catch (e) {
    got = ((e.stdout || "") + (e.stderr || "")).trim() || "no output";
  }

  if (t.trap) {
    if (got.startsWith("ERR")) {
      pass++;
      console.log(`  ok ${t.name.padEnd(16)} refused: ${got.slice(4)}`);
    } else {
      fail++;
      console.log(`  FAIL ${t.name.padEnd(14)} should have been refused, got "${got}"`);
    }
    continue;
  }

  const want = "OK " + expected;
  if (got === want) {
    pass++;
    console.log(`  ok ${t.name.padEnd(16)} ${expected}`);
  } else {
    fail++;
    console.log(`  FAIL ${t.name.padEnd(14)} expected "${want}", got "${got}"`);
  }
}

console.log(`\n${pass} passed, ${fail} failed, ${skipped} skipped (oracle: Node WebAssembly)`);
process.exit(fail ? 1 : 0);
