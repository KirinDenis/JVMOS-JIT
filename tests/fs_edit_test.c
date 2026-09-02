/*
 * Drives the edit buffer through the exact code the kernel runs, with the ATA
 * syscalls pointed at an image file. This is the only way to see whether
 * opening a file, editing it, naming it and saving it actually round-trips:
 * inside the OS the same bug would look like an empty window.
 *
 *   fs_edit_test <image>
 *
 * Not part of the kernel build; the Makefile filters tests/ out.
 */
#include <stdio.h>
#include <string.h>
#include "../fs/fat32.h"
#include "../fs/fsedit.h"

extern int fs_init(void);
extern int fs_count(void);
extern int fs_name_byte(int index, int offset);
extern int fs_entry_size(int index);
extern int fs_run(int index);

static FILE *g_img;
static int g_fail;

/*
 * Stand-ins for the WebAssembly host, so the loader can be exercised without
 * dragging the interpreter and the framebuffer in here. The builtin module is
 * a byte pattern of the same size as the real one, which is the thing worth
 * testing: that a binary of that size survives being written to the volume at
 * format time and read back byte for byte when it is launched.
 */
#define FAKE_BUILTIN_LEN 22155
static unsigned char g_builtin[FAKE_BUILTIN_LEN];
static unsigned char g_progbuf[65536];
static int g_prog_len = -1;

const unsigned char *wasm_builtin_bytes(void) { return g_builtin; }
int wasm_builtin_len(void) { return FAKE_BUILTIN_LEN; }
unsigned char *wasm_prog_buffer(void) { return g_progbuf; }
int wasm_prog_capacity(void) { return (int)sizeof(g_progbuf); }
int wasm_prog_use(int len) { g_prog_len = len; return 1; }

/* The two functions fat32_disk.c expects the platform to provide. */
int sys_disk_read_sector(unsigned lba, unsigned char *buffer)
{
    if (fseek(g_img, (long)lba * FAT_SECTOR_SIZE, SEEK_SET) != 0) return 0;
    return fread(buffer, 1, FAT_SECTOR_SIZE, g_img) == FAT_SECTOR_SIZE;
}

int sys_disk_write_sector(unsigned lba, const unsigned char *buffer)
{
    if (fseek(g_img, (long)lba * FAT_SECTOR_SIZE, SEEK_SET) != 0) return 0;
    if (fwrite(buffer, 1, FAT_SECTOR_SIZE, g_img) != FAT_SECTOR_SIZE) return 0;
    fflush(g_img);
    return 1;
}

void sys_serial_puts(const char *s)
{
    printf("  kernel: %s", s);
}

static void check(const char *what, int ok)
{
    if (ok) {
        printf("  ok   %s\n", what);
    } else {
        g_fail++;
        printf("  FAIL %s\n", what);
    }
}

static void check_int(const char *what, int got, int want)
{
    if (got == want) {
        printf("  ok   %s = %d\n", what, got);
    } else {
        g_fail++;
        printf("  FAIL %s = %d, expected %d\n", what, got, want);
    }
}

/* Reads the edit buffer's name back out, the way the window draws it. */
static void read_name(char *out)
{
    int n = fs_edit(ED_NAME_LEN, 0, 0);
    int i;
    for (i = 0; i < n; i++) out[i] = (char)fs_edit(ED_NAME_GET, i, 0);
    out[n] = 0;
}

/* Types a name in, the way the filename field does. */
static void type_name(const char *s)
{
    int i;
    fs_edit(ED_NAME_CLEAR, 0, 0);
    for (i = 0; s[i]; i++) fs_edit(ED_NAME_PUSH, s[i], 0);
}

/* Types text at the caret, the way editorKey does. */
static int type_text(int at, const char *s)
{
    int i;
    for (i = 0; s[i]; i++) {
        if (!fs_edit(ED_INSERT, at, s[i])) return -1;
        at++;
    }
    return at;
}

static void read_text(char *out, int max)
{
    int n = fs_edit(ED_LENGTH, 0, 0);
    int i;
    if (n > max - 1) n = max - 1;
    for (i = 0; i < n; i++) out[i] = (char)fs_edit(ED_GET, i, 0);
    out[n] = 0;
}

static int find_entry(const char *packed_prefix)
{
    int count = fs_count();
    int i, j;
    for (i = 0; i < count; i++) {
        int match = 1;
        for (j = 0; packed_prefix[j]; j++) {
            if (fs_name_byte(i, j) != packed_prefix[j]) match = 0;
        }
        if (match) return i;
    }
    return -1;
}

int main(int argc, char **argv)
{
    char name[32];
    char text[512];
    int at, idx;

    if (argc < 2) {
        printf("ERR usage: fs_edit_test <image>\n");
        return 2;
    }
    g_img = fopen(argv[1], "r+b");
    if (!g_img) {
        printf("ERR cannot open %s\n", argv[1]);
        return 2;
    }

    for (at = 0; at < FAKE_BUILTIN_LEN; at++)
        g_builtin[at] = (unsigned char)(at * 31 + (at >> 7));

    printf("\n-- boot --\n");
    check("mount or format succeeded", fs_init() != 0);
    check_int("entries after formatting", fs_count(), 2);

    printf("\n-- the program the format wrote --\n");
    idx = find_entry("SOKOBAN");
    check("SOKOBAN.WSM is on the volume", idx >= 0);
    check_int("its size on disk", fs_entry_size(idx), FAKE_BUILTIN_LEN);
    check_int("launching it succeeds", fs_run(idx), 1);
    check_int("the loader read the whole file", g_prog_len, FAKE_BUILTIN_LEN);
    {
        int i, same = 1;
        for (i = 0; i < FAKE_BUILTIN_LEN; i++)
            if (g_progbuf[i] != g_builtin[i]) { same = 0; break; }
        check("every byte came back unchanged", same);
    }
    check_int("launching a directory index that is not there refuses",
              fs_run(999), -1);
    check("launching did not disturb the editor's filename",
          fs_edit(ED_NAME_LEN, 0, 0) == 0);

    printf("\n-- open the file the format wrote --\n");
    idx = find_entry("README");
    check("README is in the directory", idx >= 0);
    check_int("open returns the file's length", fs_edit(ED_OPEN, idx, 0),
              fs_entry_size(idx));
    read_name(name);
    check("the name unpacks to README.TXT", strcmp(name, "README.TXT") == 0);
    if (strcmp(name, "README.TXT") != 0) printf("       got \"%s\"\n", name);
    check_int("a freshly opened file is not dirty", fs_edit(ED_DIRTY, 0, 0), 0);
    check_int("first character is J", fs_edit(ED_GET, 0, 0), 'J');
    check_int("reading past the end refuses",
              fs_edit(ED_GET, fs_edit(ED_LENGTH, 0, 0), 0), -1);

    printf("\n-- type a new file --\n");
    fs_edit(ED_CLEAR, 0, 0);
    check_int("clear empties the buffer", fs_edit(ED_LENGTH, 0, 0), 0);
    check_int("clear empties the name", fs_edit(ED_NAME_LEN, 0, 0), 0);
    check_int("saving with no name refuses", fs_edit(ED_SAVE, 0, 0), 0);

    at = type_text(0, "line one\nline two\n");
    check_int("typed length", fs_edit(ED_LENGTH, 0, 0), 18);
    check_int("typing marks it dirty", fs_edit(ED_DIRTY, 0, 0), 1);

    /* Backspace at the caret, the way the editor does it. */
    at--;
    fs_edit(ED_DELETE, at, 0);
    check_int("delete removes one byte", fs_edit(ED_LENGTH, 0, 0), 17);

    /* Insert in the middle, which is what exercises the shifting. */
    check("insert in the middle", fs_edit(ED_INSERT, 4, '!') == 1);
    read_text(text, sizeof(text));
    check("text reads back with the insert in place",
          strcmp(text, "line! one\nline two") == 0);
    if (strcmp(text, "line! one\nline two") != 0) printf("       got \"%s\"\n", text);

    printf("\n-- name it and save --\n");
    type_name("notes.txt");
    read_name(name);
    check("the field keeps what was typed", strcmp(name, "notes.txt") == 0);
    check("save succeeds", fs_edit(ED_SAVE, 0, 0) == 1);
    check_int("saving clears dirty", fs_edit(ED_DIRTY, 0, 0), 0);
    check_int("the directory now holds three files", fs_count(), 3);

    printf("\n-- reopen it, as a fresh boot would --\n");
    idx = find_entry("NOTES");
    check("NOTES is in the directory, upper cased on disk", idx >= 0);
    check_int("size on disk", fs_entry_size(idx), 18);
    fs_edit(ED_CLEAR, 0, 0);
    check_int("reopen returns the length", fs_edit(ED_OPEN, idx, 0), 18);
    read_text(text, sizeof(text));
    check("the contents survived the round trip",
          strcmp(text, "line! one\nline two") == 0);
    read_name(name);
    check("the name comes back with its dot", strcmp(name, "NOTES.TXT") == 0);
    if (strcmp(name, "NOTES.TXT") != 0) printf("       got \"%s\"\n", name);

    printf("\n-- overwrite, not duplicate --\n");
    check("insert", fs_edit(ED_INSERT, 0, 'X') == 1);
    check("save over the same name", fs_edit(ED_SAVE, 0, 0) == 1);
    check_int("still three files, not four", fs_count(), 3);
    check_int("the new size is on disk", fs_entry_size(find_entry("NOTES")), 19);

    printf("\n-- delete --\n");
    check("remove", fs_edit(ED_REMOVE, find_entry("NOTES"), 0) == 1);
    check_int("two files left", fs_count(), 2);
    check("NOTES is gone", find_entry("NOTES") < 0);
    check("README survived", find_entry("README") >= 0);

    printf("\n-- line arithmetic --\n");
    /* "aa\nbbbb\ncc": offsets 0,1 line 0; 3..6 line 1; 8,9 line 2 */
    fs_edit(ED_CLEAR, 0, 0);
    type_text(0, "aa\nbbbb\ncc");
    check_int("offset 0 is on line 0", fs_edit(ED_LINE_OF, 0, 0), 0);
    check_int("the newline itself still counts as its own line",
              fs_edit(ED_LINE_OF, 2, 0), 0);
    check_int("just past a newline is the next line", fs_edit(ED_LINE_OF, 3, 0), 1);
    check_int("the last character", fs_edit(ED_LINE_OF, 9, 0), 2);
    check_int("line 0 starts at 0", fs_edit(ED_LINE_START, 0, 0), 0);
    check_int("line 1 starts after the first newline",
              fs_edit(ED_LINE_START, 1, 0), 3);
    check_int("line 2", fs_edit(ED_LINE_START, 2, 0), 8);
    check_int("a line past the end has no start", fs_edit(ED_LINE_START, 3, 0), -1);
    check_int("line 1 ends at its newline", fs_edit(ED_LINE_END, 3, 0), 7);
    check_int("the last line ends at the end of the text",
              fs_edit(ED_LINE_END, 8, 0), 10);
    check_int("an offset past the end clamps",
              fs_edit(ED_LINE_OF, 9999, 0), 2);

    printf("\n-- limits --\n");
    check_int("capacity", fs_edit(ED_CAPACITY, 0, 0), FS_TEXT_MAX);
    check_int("inserting past the end refuses",
              fs_edit(ED_INSERT, fs_edit(ED_LENGTH, 0, 0) + 5, 'x'), 0);
    check_int("deleting past the end refuses",
              fs_edit(ED_DELETE, fs_edit(ED_LENGTH, 0, 0), 0), 0);
    fs_edit(ED_NAME_CLEAR, 0, 0);
    for (at = 0; at < 40; at++) fs_edit(ED_NAME_PUSH, 'A', 0);
    check_int("the name field stops at its limit",
              fs_edit(ED_NAME_LEN, 0, 0), FS_NAME_MAX);
    check_int("popping an empty name refuses",
              (fs_edit(ED_NAME_CLEAR, 0, 0), fs_edit(ED_NAME_POP, 0, 0)), 0);
    check_int("an unknown operation returns zero", fs_edit(999, 0, 0), 0);

    printf("\n%s\n", g_fail ? "CHECKS FAILED" : "all checks passed");
    return g_fail ? 1 : 0;
}
