/*
 * Runs the compiled Rust Sokoban guest through Node's WebAssembly and through
 * our interpreter, feeding both the same keys, and compares what the game drew.
 *
 * The guest reports its whole visible state through draw_int (level, moves,
 * pushes, crates on goal, crates total), so comparing those numbers plus the
 * rectangle count checks the real game end to end, not a toy module.
 *
 *   node tests/wasm_game_diff.js <wasm_host_test.exe> <guest.wasm>
 */
const fs = require("fs");
const { execFileSync } = require("child_process");

const EXE = process.argv[2];
const MODULE = process.argv[3];
const bin = fs.readFileSync(MODULE);

const K = { UP: 1, DOWN: 2, LEFT: 3, RIGHT: 4, RESTART: 5, NEXT: 6, PREV: 7 };

// Key sequences worth checking: plain walking, pushing a crate, restarting,
// and moving between levels.
const K_GALLERY = 8;

const RUNS = [
  { name: "title_art", keys: [] },
  { name: "art_next", keys: [K.NEXT, K.NEXT] },
  { name: "art_prev_wraps", keys: [K.PREV] },
  { name: "art_then_play", keys: [K.UP, K.UP, K.UP] },
  { name: "back_to_gallery", keys: [K.UP, K.UP, K_GALLERY, K.NEXT] },
  { name: "boot", keys: [] },
  { name: "walk_up", keys: [K.UP, K.UP, K.UP] },
  { name: "walk_around", keys: [K.UP, K.LEFT, K.LEFT, K.UP, K.RIGHT, K.DOWN] },
  { name: "push_attempt", keys: [K.UP, K.UP, K.UP, K.UP, K.LEFT, K.LEFT, K.LEFT] },
  { name: "into_walls", keys: [K.UP, K.DOWN, K.DOWN, K.DOWN, K.DOWN, K.DOWN] },
  { name: "restart_after_moves", keys: [K.UP, K.UP, K.LEFT, K.RESTART] },
  { name: "next_level", keys: [K.UP, K.NEXT, K.UP, K.LEFT] },
  { name: "next_next_prev", keys: [K.UP, K.NEXT, K.NEXT, K.PREV, K.UP] },
  { name: "long_walk", keys: [K.UP, K.UP, K.UP, K.LEFT, K.DOWN, K.RIGHT, K.RIGHT, K.UP, K.LEFT, K.DOWN, K.UP] },
  { name: "last_level", keys: Array(64).fill(K.NEXT) },
];

function reference(keys) {
  let rects = 0, imgs = 0, ints = [], pending = 0;
  const inst = new WebAssembly.Instance(new WebAssembly.Module(bin), {
    env: {
      set_color: () => {},
      fill_rect: () => { rects++; },
      width: () => 360,
      height: () => 240,
      ticks: () => 1000,
      draw_int: (v) => { ints.push(v | 0); },
      draw_image: () => { imgs++; },
      key: () => { const k = pending; pending = 0; return k; },
    },
  });
  // first frame boots the level, then one key per frame, mirroring the harness
  for (let i = 0; i <= keys.length; i++) {
    pending = i === 0 ? 0 : keys[i - 1];
    rects = 0;
    imgs = 0;
    ints = [];
    inst.exports.frame();
  }
  return `RECTS ${rects} IMGS ${imgs} INTS ${ints.join(" ")}`.trim();
}

let pass = 0, fail = 0;
for (const run of RUNS) {
  const want = reference(run.keys);
  let got;
  try {
    got = execFileSync(EXE, ["--game", MODULE, ...run.keys.map(String)], { encoding: "utf8" }).trim();
  } catch (e) {
    got = ((e.stdout || "") + (e.stderr || "")).trim() || "no output";
  }
  if (got === want) {
    pass++;
    console.log(`  ok ${run.name.padEnd(20)} ${want}`);
  } else {
    fail++;
    console.log(`  FAIL ${run.name.padEnd(18)}\n       reference: ${want}\n       ours:      ${got}`);
  }
}
console.log(`\n${pass} passed, ${fail} failed (same Rust module in both engines)`);
process.exit(fail ? 1 : 0);
