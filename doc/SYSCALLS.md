# Syscalls

Java reaches the kernel through `kernel.Native`:

```java
Native.sys(id, a, b, c, d);
```

The JIT special-cases any class whose name ends in `Native`: instead of
compiling a method call, it emits a stub that copies the five arguments into
fixed globals and calls `sys_native_dispatch` in `JIT/jit.asm`, which is a
chain of comparisons on the id. There is no interrupt and no privilege change.

`Native.sys` is declared twice, once with `Object c` and once with `int c`.
That is the one place overloading is safe, because the JIT never looks the
method up by name.

| Id | Name | Arguments | Returns |
| --- | --- | --- | --- |
| 0 | `SYS_KALLOC` | a = size | pointer, or the current heap pointer when size is 0 |
| 1 | `SYS_SET_COLOR` | a = `0x00RRGGBB` | |
| 2 | `SYS_FILL_RECT` | a, b, c, d = x, y, w, h | |
| 3 | `SYS_DRAW_RECT` | a, b, c, d = x, y, w, h | |
| 4 | `SYS_DRAW_LINE` | a, b, c, d = x1, y1, x2, y2 | |
| 5 | `SYS_DRAW_STRING` | a, b = x, y; c = string literal | |
| 6 | `SYS_READ_KEYBOARD` | | `modifiers \| code`, 0 if none |
| 7 | `SYS_READ_MOUSE` | a: 0 = x, 1 = y, 2 = buttons | value |
| 8 | `SYS_DISK_READ` | a = LBA, c = buffer | |
| 9 | `SYS_DISK_WRITE` | a = LBA, c = buffer | |
| 10 | `SYS_INB` | a = port | byte |
| 11 | `SYS_OUTB` | a = port, b = value | |
| 12 | `SYS_SLEEP` | a = milliseconds | |
| 13 | `SYS_GET_TIME` | a = field, 0 sec to 5 year | value from the CMOS clock |
| 14 | `SYS_GET_PIXEL` | a, b = x, y | colour |
| 15 | `SYS_DRAW_CHAR` | a, b = x, y; c = character | |
| 16 | `SYS_SET_KBD_LAYOUT` | a = layout | |
| 17 | `SYS_EXIT` | | powers the machine off |
| 18 | `SYS_GET_TICKS` | | milliseconds since boot |
| 19 | `SYS_SERIAL_PUTC` | a = character | |
| 20 | `SYS_SERIAL_PUTS` | c = string literal | |
| 21 | `SYS_PCI_READ` | a, b, c, d = bus, slot, func, offset | dword |
| 22 | `SYS_BEEP` | a = Hz, 0 to stop | |
| 23 | `SYS_RTL8139_INIT` | a = I/O base | |
| 24 | `SYS_RTL8139_SEND` | b = length, c = buffer | |
| 25 | `SYS_NET_RECEIVE` | b = length, c = buffer | bytes read |
| 26 | `SYS_PRESENT` | | copies the back buffer to the screen |
| 27 | `SYS_SET_CLIP` | a, b, c, d = x, y, w, h | |
| 28 | `SYS_FILL_BLEND` | a, b, c, d = x, y, w, h | |
| 29 | `SYS_STR_LEN` | c = string literal | length |
| 30 | `SYS_STR_BYTE` | a = index, c = string literal | byte, or -1 out of range |
| 31 | `SYS_SET_BLEND` | a = k, opacity is `1/2^k` | |
| 32 | `SYS_C_SELFTEST` | | `0x5741534D` if the C objects linked |
| 33 | `SYS_WASM_DRAW` | a, b, c, d = x, y, w, h | 1, or a negated error code |
| 34 | `SYS_WASM_KEY` | a = key code | |
| 35 | `SYS_WASM_SOUND` | a = 1 on, 0 off | |
| 36 | `SYS_WASM_MUSIC` | a = 1 audible, 0 silent | advances the sequencer one step |
| 37 | `SYS_SB16_STATUS` | | 1 if a Sound Blaster answered the reset |
| 38 | `SYS_SND_PLAY` | a = clip id, 0 boot to 6 fanfare | |

The network calls target an RTL8139. That card is not emulated by v86, which
provides an NE2000, so those three are dead code in a browser.

# Adding one
1. write the routine in `boot/sys_api.asm` and add it to the `global` list
2. add an `extern` and a dispatch branch in `JIT/jit.asm`
3. add the constant to `kernel/Native.java`
4. if it is graphics, add a wrapper to `java/awt/Graphics2D.java`

C code in the kernel can call the assembly routines directly with a plain
`extern` declaration; the calling convention is cdecl and the return value is
in `eax`.
