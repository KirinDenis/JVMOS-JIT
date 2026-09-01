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

# What still cannot be checked here
The assembler and the picture. There is no nasm and no gcc on a typical Windows
machine, so `boot/*.asm` and the kernel link are only proven by CI, and how it
all looks is only proven by opening the page.

That is worth remembering when reading a change: the C and Java parts of a patch
may be verified while the assembly parts of the same patch are not.
