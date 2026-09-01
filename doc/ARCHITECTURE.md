# Architecture

# Boot chain
* GRUB loads the kernel through a multiboot header that also asks for a
  1024x768x32 VESA mode, and loads every `.class` file beside it as a separate
  multiboot module
* `boot/multiboot.asm` installs its own GDT, reads the framebuffer address,
  pitch and resolution out of the multiboot info, then calls `bootjvm_start`
* `jvm/bootjvm.asm` walks the module list, checks that module 0 begins with
  `CAFEBABE`, parses its constant pool into an index table, finds `main` by
  comparing names byte for byte, and hands its bytecode to the JIT
* the JIT returns a pointer to native code, which the kernel simply calls

There is no interpreter anywhere in this path. Bytecode is never executed, only
translated.

# The JIT
`JIT/jit.asm` is a 256 entry table mapping each bytecode to a routine that
emits x86 bytes. `iadd` becomes pop, pop, add, push. The whole compiler is that
table plus a driver loop, which is why it fits in one file.

Two things in it are worth knowing about:

* **Lazy compilation.** A call is first emitted as a call to a trampoline. The
  first time it runs, the trampoline resolves and compiles the target, then
  rewrites the `rel32` displacement of the original call instruction in place.
  Every later call goes straight there. The code modifies itself.
* **Syscalls are not interrupts.** When the JIT sees a call into a class whose
  name ends in `Native`, it does not compile a method call at all. It emits a
  stub that copies the arguments into fixed globals and calls the assembly
  dispatcher. After compilation, `g.fillRect(...)` is close to a direct call to
  `sys_fill_rect`.

# Memory map
Flat 32-bit protected mode, no paging, everything in ring 0.

| Region | Purpose |
| --- | --- |
| `0x00100000` | kernel image and BSS |
| `0x00200000 - 0x00600000` | JIT code buffer, 4MB |
| `0x00600000 - 0x00A00000` | graphics back buffer |
| `0x00A00000 +` | `sys_kalloc` heap, used by `new` |
| `0x01000000 +` | array heap, used by `newarray` |

Both heaps are bump allocators with no free. Nothing is ever returned, which is
why nothing may allocate inside a redraw loop.

# Graphics
All drawing goes to a back buffer in RAM, never straight to video memory.
`sys_present` copies the whole buffer to the framebuffer in one `rep movsd`
once a frame, which is what removes the flicker.

* `sys_set_clip` limits every later primitive to a rectangle, clamped to the
  screen. The desktop sets it to a window's client area before drawing that
  window's content, so a window cannot paint outside its own frame. Without the
  clamp a window dragged past the left edge produced negative coordinates that
  wrapped around in the linear framebuffer and painted onto the opposite side of
  the screen.
* `sys_fill_blend` mixes the current colour into what is already there. The
  opacity is `1/2^k`, set by `sys_set_blend`, so blending is a shift and a mask
  rather than a multiply per pixel. Window shadows are several faint passes
  layered on top of each other, which produces the fade without any per-pixel
  arithmetic.
* Text is an 8x16 bitmap font in `boot/font.asm`, drawn pixel by pixel with the
  clip rectangle applied per pixel.

# Input
* IRQ1 keeps the shift, control and alt state and the `0xE0` prefix that marks
  extended keys, and queues only key presses. The high bit of a queued byte,
  free because releases are never queued, marks that the key was extended.
* `sys_read_keyboard_scancode` returns `modifiers | code`, where codes above
  `0xFF` are `0x100 + scancode`. That is how arrows, F keys and combinations
  like Alt+F4 reach the desktop, which otherwise only ever saw plain ASCII.
* IRQ12 assembles the three byte PS/2 mouse packet and keeps x, y and buttons.

# The desktop
`kernel/Boot.java` is the whole user interface: window management, the widget
set, the taskbar, both games. It is one class on purpose, for reasons explained
in [JAVA-RULES.md](JAVA-RULES.md).

Redrawing is immediate mode. There is no retained widget tree and no damage
tracking: when something changes, the frame is drawn again from scratch into the
back buffer and presented. Repaint happens on mouse movement, on a key, or when
the clock second changes.

# The WebAssembly sandbox
A second execution environment, described in
[WASM-SANDBOX.md](WASM-SANDBOX.md). The kernel Java code is privileged and
fast; a WASM guest is isolated by construction, since it can only address its
own linear memory. That is the only isolation in the system, as there is no MMU.
