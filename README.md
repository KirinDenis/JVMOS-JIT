# JVMOS-JIT
It is an improved fork of the repository: https://github.com/aayes89/JVMOS.

A baremetal operating system whose userland is Java bytecode compiled to native
x86 at runtime. There is no JVM underneath and no operating system underneath:
the kernel parses `.class` files itself and translates their bytecode into
machine code. Alongside it runs a WebAssembly interpreter, so a second kind of
program can execute in a sandbox that cannot reach kernel memory.

It boots in a browser, with no install: https://kirindenis.github.io/JVMOS-JIT/

# What is in it
* A JIT that turns Java bytecode into x86 machine code, with lazy compilation
  and self-patching call sites
* A desktop with resizable windows, menus, a taskbar, keyboard navigation and
  a flat widget set (buttons, checkboxes, radio groups, text fields, lists)
* Double buffered VESA graphics with clipping and alpha blended shadows
* Sokoban, twice: once written in Java and executed by the JIT, once written in
  Rust, compiled to WebAssembly and executed inside the sandbox
* A WebAssembly interpreter covering the i32 core of the MVP instruction set,
  with every linear memory access bounds checked
* A FAT32 volume the system mounts at boot, and formats itself if the disk is
  blank, keeping the first megabyte outside the partition for a system image
* A text editor, and file associations: the File Manager lists the real
  directory, and Enter opens a file in whatever handles its extension

# Requirements
* C-Compiler: gcc-12
* Linker: ld
* Assembly: nasm
* Java-Compiler from JDK: javac
* GRUB2
* GIT
* QEMU, to run it outside a browser
* Rust with the `wasm32-unknown-unknown` target, only to rebuild the WASM guest
* Node.js, only to run the tests

# HOW to USE
* clone the repository <code>[git clone](https://github.com/KirinDenis/JVMOS-JIT.git)</code>
* run <code>clear && make clean && make run</code><br>
<b>Note:</b> I shared a 10MB image pre-configured for QEMU so you won't have any issues starting it up, but you can run `kernel.bin` if you'd like to test without a hard drive.

# In a browser
Every push to `main` rebuilds `os.iso` in GitHub Actions and publishes it with
`docs/index.html`, which boots the image with the v86 emulator. The page also
shows the kernel's COM1 output, which is the only way to see where boot stopped
when the screen freezes.<br>
<b>Note:</b> the image is around 12MB and is cached hard, so the page asks the
server for its current version and puts it in the URL. Pressing Reset in the
emulator restarts the machine from the image already in memory; reload the page
to pick up a new build.

# Documentation
* [doc/ARCHITECTURE.md](doc/ARCHITECTURE.md) - boot chain, the JIT, memory map,
  graphics and input
* [doc/JAVA-RULES.md](doc/JAVA-RULES.md) - what this JIT does not support, and
  why breaking those rules fails silently instead of failing loudly
* [doc/WASM-SANDBOX.md](doc/WASM-SANDBOX.md) - the interpreter, the host ABI and
  how to build a guest program
* [doc/FILESYSTEM.md](doc/FILESYSTEM.md) - the FAT32 volume, the system area
  reserved outside the partition, and when the system will and will not format
  a disk
* [doc/SYSCALLS.md](doc/SYSCALLS.md) - the syscall table
* [doc/TESTING.md](doc/TESTING.md) - how to check changes without building the
  image

# TODO
* Load programs from disk or over the wire instead of embedding them
* Sound: v86 emulates a Sound Blaster 16, the kernel only drives the PC speaker
* Network support
* Subdirectories and long file names; the volume is 8.3 and root-only today
* (FAT/FAT32, NTFS, etc.) support
* Test useful apps (Notepad, Paint, Calculator)

# Screenshots

### JIT Tests PASSED!
<img width="1016" height="389" alt="imagen" src="https://github.com/user-attachments/assets/ca85eb75-cab0-4432-a411-461381ccc0fc" />

### Test UI
<img width="1024" height="834" alt="imagen" src="https://github.com/user-attachments/assets/431dbd24-4842-4d5b-98a3-fe33ddc94120" />

<img width="1021" height="826" alt="imagen" src="https://github.com/user-attachments/assets/4bd80ea3-48e1-4743-b3a5-f7174f54dbbe" />
