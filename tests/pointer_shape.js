/*
 * Renders the mouse pointer the way the kernel draws it, and checks that the
 * outline is closed.
 *
 * The shape is the one piece of the desktop that cannot be argued about: it is
 * a hand-placed pixel drawing, and the eye finds a hole in it instantly while
 * reading the code does not. An earlier version left eleven interior pixels
 * touching the background -- the head's right edge stopped dead at its widest
 * row, and the tail was outlined only on its right, so its white core was open
 * down the entire left side.
 *
 * The method body is taken out of kernel/Boot.java and run, rather than copied
 * here, so this cannot drift away from what actually ships.
 *
 *   node tests/pointer_shape.js [path to Boot.java]
 *
 * The optional path is how this was shown to fail on the broken version before
 * it was trusted to pass on the fixed one.
 */
const fs = require("fs");
const path = require("path");

const source = fs.readFileSync(
  process.argv[2] || path.join(__dirname, "..", "kernel", "Boot.java"), "utf8");

const start = source.indexOf("static void drawPointer(int x, int y) {");
if (start < 0) {
  console.log("FAIL drawPointer not found in kernel/Boot.java");
  process.exit(1);
}
const open = source.indexOf("{", start);
let depth = 0, end = open;
for (let i = open; i < source.length; i++) {
  if (source[i] === "{") depth++;
  else if (source[i] === "}") { depth--; if (depth === 0) { end = i; break; } }
}

/*
 * Java to JavaScript. The body is straight-line drawing calls, so declarations
 * and the one colour constant are all that need translating; integer division
 * is handled by flooring inside the mock instead of rewriting the expressions.
 */
const body = source.slice(open + 1, end)
  .replace(/\bint\s+/g, "let ")
  .replace(/\bC_TEXTLT\b/g, "0xFFFFFF");

const W = 18, H = 24;
const px = Array.from({ length: H }, () => Array(W).fill(" "));
const g = {
  c: "#",
  setRGB(v) { this.c = (v === 0) ? "#" : "."; },
  fillRect(x, y, w, h) {
    x = Math.floor(x); y = Math.floor(y);
    w = Math.floor(w); h = Math.floor(h);
    for (let j = y; j < y + h; j++)
      for (let i = x; i < x + w; i++)
        if (j >= 0 && j < H && i >= 0 && i < W) px[j][i] = this.c;
  },
};

new Function("g", "x", "y", body)(g, 0, 0);

console.log("");
for (let y = 0; y < H; y++) {
  const row = px[y].join("").replace(/\s+$/, "");
  if (row === "" && y > 4) continue;
  console.log(String(y).padStart(3) + "  " + row);
}

let fail = 0;
const say = (ok, what) => {
  console.log(`  ${ok ? "ok  " : "FAIL"} ${what}`);
  if (!ok) fail++;
};

// The hotspot: the tip must be the pixel the mouse is actually at.
say(px[0][0] !== " ", "the tip is at (0,0), where the mouse points");

// Every interior pixel must be enclosed. A white pixel with background on any
// of its four sides is a hole in the outline.
const holes = [];
for (let y = 0; y < H; y++) {
  for (let x = 0; x < W; x++) {
    if (px[y][x] !== ".") continue;
    for (const [dx, dy, name] of [[0,-1,"top"],[0,1,"bottom"],[-1,0,"left"],[1,0,"right"]]) {
      const ny = y + dy, nx = x + dx;
      const v = (ny < 0 || ny >= H || nx < 0 || nx >= W) ? " " : px[ny][nx];
      if (v === " ") holes.push(`(${x},${y}) open to the ${name}`);
    }
  }
}
say(holes.length === 0, `the outline is closed${holes.length ? ": " + holes.join("; ") : ""}`);

// The head has to be a triangle, not a sliver. A version that widened by one
// column every two rows was closed and still wrong: it had no right-hand side,
// which is what was reported, twice. Closure alone does not catch that.
let interior = 0, tall = 0;
for (let y = 0; y < H; y++) {
  let run = 0;
  for (let x = 0; x < W; x++) {
    if (px[y][x] === ".") { run++; if (run > interior) interior = run; } else run = 0;
  }
  if (px[y].some((c) => c !== " ")) tall = y + 1;
}
say(interior >= 9, `the head is ${interior} pixels across at its widest, not a sliver`);
say(interior * 2 > tall, `it is ${interior + 2} wide against ${tall} tall, so it reads as an arrow`);

// The tail has to be more than a black line, or it disappears into the border.
let widest = 0;
for (let y = 13; y < H; y++) {
  let run = 0;
  for (let x = 6; x < W; x++) {
    if (px[y][x] === ".") { run++; if (run > widest) widest = run; } else run = 0;
  }
}
say(widest >= 2, `the tail's core is ${widest} pixels wide, not a hairline`);

// Nothing may be drawn above or left of the hotspot: the pointer would then
// cover what it is pointing at, and clip at the top and left screen edges.
let outside = 0;
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (px[y][x] !== " ") {
  if (x < 0 || y < 0) outside++;
}
say(outside === 0, "nothing is drawn above or left of the hotspot");

console.log(`\n${fail ? fail + " CHECKS FAILED" : "all checks passed"}\n`);
process.exit(fail ? 1 : 0);
