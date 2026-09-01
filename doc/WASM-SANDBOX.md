# The WebAssembly sandbox

A second execution environment beside the Java one. The point is not another
language: it is that a WASM guest can only address its own linear memory, so
untrusted code can run on a machine that has no MMU and no privilege levels.

Files:

* `wasm/wasm.c`, `wasm/wasm.h` - the interpreter, freestanding C
* `wasm/host.c` - the bridge: host functions, input queue, image blitter
* `wasm/guest/` - the Rust guest, compiled to `wasm32-unknown-unknown`
* `wasm/guest_module.h`, `wasm/images.h` - generated, embedded in the kernel

# What the interpreter supports
The i32 core of the MVP instruction set: signed and unsigned arithmetic,
comparisons, bit operations, locals and globals, `block`, `loop`, `if`/`else`,
`br`, `br_if`, `br_table`, calls and recursion, linear memory at every width,
the data section, and imported host functions.

`i64` and floating point are **rejected at load time** rather than executed
incorrectly. `call_indirect` is not implemented.

That is enough for real programs: the Rust Sokoban guest uses 38 distinct
opcodes and all of them are covered. Before writing anything large, compile it
and run `node tests/wasm_census.js module.wasm`, which lists the opcodes a
module actually uses and flags anything missing. Reading off what the compiler
emitted beats guessing which corners of the spec it needs.

# Design notes
* **No allocation.** The caller supplies the module struct and the linear
  memory; everything else is fixed-size arrays. Nothing calls malloc, nothing
  calls libc.
* **Branches scan forward.** WASM control flow is structured, so `br 2` means
  "leave two enclosing blocks", not "jump to address N". Instead of a
  precomputed table of jump targets, a branch walks forward counting nesting
  until it has left the requested number of blocks. Loops are the cheap case:
  their label carries the address to jump back to. This costs more per branch
  but removes a whole pass and its storage.
* **Bounds are the module's, not the host's.** Memory accesses are checked
  against the memory the module declared, not against the buffer the host
  provided, so a guest asking for one page cannot wander into the rest of that
  buffer.

# The host ABI
A guest imports these from module `env`:

| Import | Meaning |
| --- | --- |
| `set_color(rgb)` | current colour, `0x00RRGGBB` |
| `fill_rect(x, y, w, h)` | filled rectangle in guest coordinates |
| `draw_int(value, x, y)` | a number, rendered by the host font |
| `draw_image(index, x, y, scale)` | one of the pictures the kernel carries |
| `width()`, `height()` | size of the window the guest was given |
| `key()` | next pending key, or 0 |
| `beep(hz, ms)` | a tone on the PC speaker, then silence |
| `music(track)` | 0 stops, 1 the fanfare once, 2 the theme looped |

Exports: `frame()`, called once per repaint, and `set_sound(on)`, which the
desktop pushes in so the guest follows the "Sound enabled" setting.

`beep` blocks for the length of the note, exactly as the Java side does: there
is no scheduler to play it in the background. Music is different: the melodies
are the note tables from the original game's `sound.rs`, and the sequencer runs
in the host, ticked from the desktop's idle loop. It has to advance roughly
every millisecond, and running the interpreter that often would cost far more
than the music does; the guest only chooses a track. v86 emulates a Sound Blaster 16,
so real sampled audio is possible, but the kernel only drives the PC speaker
today.

The guest draws in its own coordinate space starting at 0,0. The host
translates into the window and clamps. Keys use a small explicit set
(1 up, 2 down, 3 left, 4 right, 5 restart, 6 next, 7 previous, 8 artwork) so
neither side depends on the other's keyboard details.

Pictures are host assets: the guest names one by index and never touches the
pixels, the same way a program asks the system for a font. Going through
`fill_rect` would mean 25000 calls across the sandbox boundary for one image,
so the copy happens in C, straight into the back buffer.

# Layers of containment
Three independent things must fail before a guest can touch something it should
not:

1. the interpreter bounds checks every linear memory access
2. the host functions clamp drawing to the window rectangle
3. the graphics clip rectangle is enforced down in the framebuffer routines

# Building a guest
The guest is `no_std`: no allocator, no OS, no libc. State lives in statics,
which land in the guest's own linear memory, so it keeps state between frames.

A panic must not spin. An infinite loop inside the guest would hang the whole
machine, since there is no scheduler to preempt it. The panic handler calls
`core::arch::wasm32::unreachable()`, which traps and returns control to the
interpreter.

```
cd wasm/guest
RUSTFLAGS="-C target-feature=-sign-ext,-bulk-memory,-nontrapping-fptoint,-reference-types,-multivalue,-extended-const -C link-arg=-zstack-size=32768" \
  cargo build --release --target wasm32-unknown-unknown
cd ../..
node tests/gen_guest_header.js
```

The target features are switched off so LLVM stays inside the instruction set
the interpreter implements. The stack size matters more than it looks: Rust
reserves a 1MB stack by default, which alone pushed the module's memory
requirement to 17 pages. At 32KB it fits in one.

# Regenerating the assets
```
node tests/gen_levels_txt.js    # level maps, taken from kernel/Boot.java
node tests/gen_images.js        # artwork, palette encoded
node tests/gen_guest_header.js  # the compiled module, as a C array
```

The artwork is stored as a palette plus one index per pixel: every picture uses
at most 256 colours, which cuts 375KB to 127KB with a decoder that is a single
array lookup. Run-length coding was measured and came out larger, because the
art is dithered and its runs are short.
