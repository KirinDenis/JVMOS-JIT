/*
 * The edit buffer: the text the editor is working on, and the name it will be
 * saved under.
 *
 * It lives in the kernel rather than in Java because this JIT can only draw
 * string literals -- a literal is a constant pool pointer with its length in
 * front of it, and a string built at runtime has no such header. A Java text
 * editor would have nothing it could put on the screen. See doc/JAVA-RULES.md.
 *
 * Everything goes through one entry point, fs_edit(op, a, b), because the
 * syscall dispatcher in JIT/jit.asm is a chain of compares: fourteen more
 * branches for one window would cost every other syscall in the system a
 * little. The operation codes are mirrored in kernel/Native.java, which cannot
 * include this file.
 */
#ifndef JVMOS_FSEDIT_H
#define JVMOS_FSEDIT_H

#define ED_CAPACITY    0
#define ED_LENGTH      1
#define ED_GET         2   /* a = offset                  -> byte, or -1 */
#define ED_INSERT      3   /* a = offset, b = byte        -> 1 on success */
#define ED_DELETE      4   /* a = offset                  -> 1 on success */
#define ED_CLEAR       5   /* empties the text and the name */
#define ED_OPEN        6   /* a = directory index         -> length, or -1 */
#define ED_SAVE        7   /* writes the buffer under the current name */
#define ED_NAME_LEN    8
#define ED_NAME_GET    9   /* a = offset                  -> character */
#define ED_NAME_CLEAR 10
#define ED_NAME_PUSH  11   /* a = character */
#define ED_NAME_POP   12
#define ED_REMOVE     13   /* a = directory index, deletes that file */
#define ED_DIRTY      14   /* 1 if the text changed since it was opened */

/*
 * Line arithmetic. These exist so the window costs what it shows rather than
 * what the file holds: without them Java walks the whole buffer one syscall
 * per byte, every frame, just to find where the caret's line begins.
 */
#define ED_LINE_OF    15   /* a = offset -> which line that offset is on */
#define ED_LINE_START 16   /* a = line   -> where it begins, or -1 */
#define ED_LINE_END   17   /* a = offset -> the newline at or after it */

#define FS_TEXT_MAX   16384
#define FS_NAME_MAX   12   /* "12345678.EXT" */

int fs_edit(int op, int a, int b);

#endif
