# Writing Java for this system

This JIT supports a subset of Java. The subset is not documented by the
compiler: `javac` accepts everything, and the parts this JIT cannot express
fail at runtime, silently, as a black screen or a hang. Every rule below was
found by something breaking.

Read this before changing `kernel/Boot.java`.

# 1. No method overloading, anywhere
Methods are resolved **by name only**. The descriptor is ignored, so the first
method with a matching name wins and the others are unreachable.

This is not theoretical. `java.awt.Toolkit` has `beep()` and `beep(int)`. Every
`beep(1047)` in a melody resolved to the no-argument `beep()`, which is
hardcoded to 1000 Hz, so the tune played as one flat tone. The fix was to stop
calling `Toolkit` and go to the syscall directly.

Constructors are `<init>`, so the same rule applies: one constructor per class.

# 2. No field initializers
Static initializers are never executed. `static int x = 5;` leaves `x` at zero,
and `static final int[] TABLE = {...}` leaves a null array that will read
garbage or trap.

Assign everything in an `init()` method you call yourself. The original author
already worked this way, which is why `initColors()` exists.

The one exception is `static final int`, `static final char` and similar
compile-time constants: `javac` inlines their value at each use site, so no
initializer is needed and they are safe.

# 3. Object fields only work inside their own class
The offset of a field is derived from the **constant pool index** of the field
reference, not from a real object layout. Two classes referring to the same
field get different indices, and therefore read different offsets.

In practice: a class whose fields are touched from outside will silently read
the wrong memory. Keep data in flat arrays inside the class that uses them.
This is the main reason the entire desktop lives in one class.

# 4. Only string literals can be drawn
`drawString` works because a literal is a pointer into the constant pool with
its length stored in the two bytes before it. A string built at runtime has no
such header, so it renders garbage.

Numbers are drawn digit by digit with `Graphics2D.drawInt`. To read the bytes
of a literal, use `SYS_STR_LEN` and `SYS_STR_BYTE`; `String.getBytes()` cannot
work here for the reason in rule 3.

# 5. Never allocate in a redraw loop
`new` hands out a fixed 4KB block from a bump allocator with no free. A
`new Color(...)` inside a drawing loop costs 4KB per frame and never returns it.

Use `Graphics2D.setRGB(int)` with plain int colour constants instead of `Color`
objects.

# 6. Around 27 local variable slots per method
Local variables are addressed with a one byte displacement. Past roughly 27
slots the displacement overflows and the method quietly corrupts memory instead
of failing.

Keep methods small. Static fields are unlimited, so put state there rather than
in locals.

# 7. At most 16 parameters
The prologue copies up to 16 arguments into local slots. Beyond that they are
not copied and the method reads whatever was in those slots.

# Checking your work
`javac` cannot check any of this, but the compiled class can. See
[TESTING.md](TESTING.md): compiling against the project's own stub classes and
then reading the class file with `javap` catches violations of rules 1, 2, 6 and
7 in seconds, which is much cheaper than a build and a black screen.
