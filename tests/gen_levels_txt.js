/*
 * Extracts the level maps out of kernel/Boot.java into a plain text file the
 * Rust guest embeds with include_str!. Reading them back from Boot.java rather
 * than re-downloading guarantees the WASM port plays exactly the same levels
 * as the Java version.
 *
 *   node tests/gen_levels_txt.js
 */
const fs = require("fs");
const path = require("path");

const boot = path.join(__dirname, "..", "kernel", "Boot.java");
const out = path.join(__dirname, "..", "wasm", "guest", "src", "levels.txt");

const src = fs.readFileSync(boot, "utf8");
const re = /if \(n == (\d+)\) return "((?:[^"\\]|\\.)*)";/g;

const levels = [];
let m;
while ((m = re.exec(src)) !== null) {
  levels[Number(m[1])] = m[2].replace(/\\n/g, "\n");
}

if (levels.length !== 61 || levels.some((l) => !l)) {
  console.error(`expected 61 levels, found ${levels.filter(Boolean).length}`);
  process.exit(1);
}

// ';' never occurs inside a map, so it is a safe record separator.
const text = levels.join("\n;\n");
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, text);
console.log(`wrote ${path.relative(process.cwd(), out)}: ${levels.length} levels, ${text.length} bytes`);
