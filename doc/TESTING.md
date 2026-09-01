# Testing without building the image

Building the ISO takes a round trip through CI, and when something is wrong the
result is a black screen with no message. Almost everything can be checked
before that, on a normal desktop.

# Java: compile against the project's own classes
The real JDK will shadow `java.lang.String`, `java.awt.Graphics2D` and the rest
with its own, so compiling `kernel/Boot.java` directly checks it against the
wrong library. Copy the project's classes to a scratch directory, rewrite their
package names so the JDK cannot shadow them, and compile that:

```
package java.awt;   ->  package jw.awt;
package kernel;     ->  package jw.kernel;
```

Then `javac` is checking your code against the fifteen stub classes this system
actually has.

# Java: read the class file for what javac cannot see
Four of the rules in [JAVA-RULES.md](JAVA-RULES.md) are visible in the compiled
class, and `javap` reports them in seconds:

```
javap -v -p Boot.class | grep -oE 'locals=[0-9]+'      # must stay under ~27
javap -v -p Boot.class | grep -oE 'args_size=[0-9]+'   # must stay under 16
javap -c -p Boot.class | grep -c 'static {}'           # must be 0
javap -p   Boot.class | grep -oE '[a-z]+\(' | sort | uniq -d   # must be empty
```

The last one lists duplicate method names, which is how overloading gets caught
before it silently makes a method unreachable.

# WASM: run the interpreter on a desktop, against a reference
The interpreter is freestanding, so it compiles unchanged both into the kernel
and into an ordinary program. `tests/wasm_host_test.c` is that program.

The oracle is Node's own WebAssembly engine. `tests/wasm_conformance.js` builds
small modules, runs each through Node to get the expected answer, then through
our interpreter, and compares. That checks conformance to the specification
rather than agreement with the author's assumptions.

```
cl /nologo /O2 /Fe:tests\build\wasm_host_test.exe tests\wasm_host_test.c wasm\wasm.c
node tests/wasm_conformance.js tests/build/wasm_host_test.exe
```

It found three real bugs on the first run, each of which would have been an
identical black screen in the OS:

* a loop label stored the address of the blocktype byte instead of the first
  instruction, so branching back re-read `0x40` as an opcode, which happens to
  decode as `memory.grow`. Every loop was broken.
* the memory section is a vector, and the element count was never consumed, so
  the count byte was read as the flags and every module got zero pages
* bounds were checked against the host's buffer rather than the memory the
  module declared

Some of the cases are sandbox escapes where the correct outcome is a refusal:
writing past the end, reading past the end, a static offset that overflows, a
negative address, division by zero, `unreachable`.

# WASM: diff the real game between both engines
`tests/wasm_game_diff.js` runs the compiled Rust guest through Node and through
our interpreter with the same key sequences and compares what the game drew.
The guest reports its whole visible state through `draw_int`, so the numbers
being equal means level, moves, pushes and crate counts all match.

```
node tests/wasm_game_diff.js tests/build/wasm_host_test.exe \
  wasm/guest/target/wasm32-unknown-unknown/release/sokoban_guest.wasm
```

# FAT32: the driver against an image, and a second opinion

There is no mtools and no 7-Zip on a typical Windows machine, so nothing here
can open a FAT image except what we wrote. The answer is two implementations
that do not share an understanding: the C driver, and a reader in
`tests/fat32_verify.js` written from the specification, which checks the MBR,
the BPB, both FATs against each other, FSInfo, the backup boot sector and every
cluster chain.

`tests/fat32_test.c` hands the driver a file instead of the ATA syscalls, which
is the whole reason `fat_read_sector` and `fat_write_sector` are an interface
rather than inline `in`/`out`.

```
cl /nologo /W4 /Fe:build\fat32_test.exe tests\fat32_test.c fs\fat32.c
build\fat32_test.exe disk.img boot 10
node tests\fat32_verify.js disk.img README.TXT 253
```

`boot` does what `fs_init` does on a blank disk, so first boot and second boot
can be inspected from outside the OS instead of by squinting at a window.
Running it twice must format once and mount the second time. `writebig` and
`verifybig` push a file across many clusters and read every byte back, which is
what exercises the FAT chain rather than a single-cluster file.

What this has already caught:

* free space reported as `0 KB free of 0 KB`. Kilobytes were computed as
  `clusters * bytes_per_cluster / 1024`, and a 512 byte cluster divides to zero
  before it is ever multiplied. Counted as sectors/2 now.
* the welcome file's length, counted by hand as 258 when it is 253. It is
  measured at runtime now instead of written down.

# The editor, through the code the kernel actually runs

`tests/fs_edit_test.c` links the real `fs/fat32_disk.c` and points the ATA
syscalls at an image file, so the whole path the editor uses is exercised where
its result can be read: format, open the file, unpack `README  TXT` back into
`README.TXT`, type, insert in the middle, name it, save, reopen it as a fresh
boot would, overwrite it without duplicating the entry, and delete it.

```
cl /nologo /W4 /Fe:build\fs_edit_test.exe tests\fs_edit_test.c fs\fat32_disk.c fs\fat32.c
build\fs_edit_test.exe disk.img
node tests\fat32_verify.js disk.img
```

Running the independent reader afterwards is the point of the last step: it
confirms that creating, overwriting and deleting left a volume that something
other than our own driver still considers valid, and that the deleted file's
clusters really went back to the FAT.

# What still cannot be checked here
The assembler and the picture. There is no nasm and no gcc on a typical Windows
machine, so `boot/*.asm` and the kernel link are only proven by CI, and how it
all looks is only proven by opening the page.

That is worth remembering when reading a change: the C and Java parts of a patch
may be verified while the assembly parts of the same patch are not.
