/*
 * The kernel's half of the filesystem: it gives fat32.c its two sector
 * functions, and gives the Java side a handful of calls flat enough to reach
 * through a syscall.
 *
 * The driver above this file has no idea whether it is talking to an ATA
 * controller or to a file on a desktop, which is what lets tests/fat32_test.c
 * exercise the identical code where a debugger exists.
 */
#include "fat32.h"
#include "fsedit.h"

extern int sys_disk_read_sector(unsigned lba, unsigned char *buffer);
extern int sys_disk_write_sector(unsigned lba, const unsigned char *buffer);
extern void sys_serial_puts(const char *s);

/*
 * The size of the disk the project ships. Nothing here asks the drive how big
 * it is: IDENTIFY would be another page of assembler, and guessing by probing
 * for the last readable sector is worse than a constant, because a controller
 * that answers out-of-range reads with zeros instead of an error would make us
 * format a volume claiming space that does not exist.
 *
 * Formatting still refuses unless the last sector really can be read, so a
 * wrong constant fails loudly rather than producing a corrupt volume.
 */
#define FS_DISK_SECTORS   (10u * 1024u * 1024u / FAT_SECTOR_SIZE)

/*
 * The first megabyte stays outside the partition. That is where a system image
 * belongs -- a boot sector, and room for the kernel to be written to the disk
 * later so it can start from there instead of from the ISO. The filesystem
 * never allocates into it, so the two can grow independently.
 */
#define FS_SYSTEM_SECTORS 2048u

#define FS_ABSENT     0   /* no disk answered */
#define FS_MOUNTED    1   /* an existing volume was found */
#define FS_FORMATTED  2   /* the disk was blank, so we made one */
#define FS_FOREIGN    3   /* something is there, and it is not ours to erase */

static fat32_fs g_fs;
static int g_status = FS_ABSENT;

int fat_read_sector(unsigned lba, unsigned char *buffer)
{
    return sys_disk_read_sector(lba, buffer);
}

int fat_write_sector(unsigned lba, const unsigned char *buffer)
{
    return sys_disk_write_sector(lba, buffer);
}

/*
 * True when sector zero holds a partition table with a partition of some other
 * type in it. A disk that is merely blank, or whose table is empty, is ours to
 * format; a disk carrying somebody else's volume is not, and losing it to a
 * silent reformat at boot would be unforgivable for the sake of a demo.
 */
static int foreign_volume(void)
{
    unsigned char sector[FAT_SECTOR_SIZE];
    int i;

    if (!fat_read_sector(0, sector)) return 0;
    if (sector[510] != 0x55 || sector[511] != 0xAA) return 0;

    for (i = 0; i < 4; i++) {
        unsigned char *e = sector + 0x1BE + i * 16;
        unsigned type = e[4];
        if (type == 0x00) continue;
        if (type == 0x0B || type == 0x0C) continue;   /* FAT32, ours */
        return 1;
    }
    return 0;
}

/*
 * Mounts the disk, and formats it only if there is plainly nothing to lose.
 * Called once at boot.
 */
int fs_init(void)
{
    unsigned char sector[FAT_SECTOR_SIZE];

    if (!fat_read_sector(0, sector)) {
        g_status = FS_ABSENT;
        sys_serial_puts("fs: no disk\n");
        return g_status;
    }

    if (fat32_mount(&g_fs)) {
        g_status = FS_MOUNTED;
        sys_serial_puts("fs: mounted\n");
        return g_status;
    }

    if (foreign_volume()) {
        g_status = FS_FOREIGN;
        sys_serial_puts("fs: unknown partition, not formatting\n");
        return g_status;
    }

    /* The constant above is only trustworthy if the disk really is that big. */
    if (!fat_read_sector(FS_DISK_SECTORS - 1, sector)) {
        g_status = FS_ABSENT;
        sys_serial_puts("fs: disk smaller than expected, not formatting\n");
        return g_status;
    }

    if (!fat32_format(&g_fs, FS_DISK_SECTORS, FS_SYSTEM_SECTORS)) {
        g_status = FS_ABSENT;
        sys_serial_puts("fs: format failed\n");
        return g_status;
    }

    sys_serial_puts("fs: formatted\n");
    g_status = FS_FORMATTED;

    /*
     * One file, written immediately. A freshly formatted volume that lists
     * nothing looks exactly like a volume whose directory code is broken, and
     * this exercises allocation, the FAT chain and the directory entry on
     * every first boot instead of leaving them to be discovered later.
     */
    {
        const char *text =
            "JVMOS-JIT\r\n"
            "\r\n"
            "This volume was created by the system when it found no filesystem\r\n"
            "on the disk. Cluster size is 512 bytes.\r\n"
            "\r\n"
            "The first megabyte of the disk lies outside the partition and is\r\n"
            "reserved for the system image, so the two can grow separately.\r\n";
        unsigned n = 0;
        while (text[n]) n++;          /* measured, never counted by hand */
        fat32_write_file(&g_fs, "README.TXT", (const unsigned char *)text, n);
    }

    return g_status;
}

int fs_status(void)
{
    return g_status;
}

/* How many entries the root directory holds. */
int fs_count(void)
{
    fat32_entry e;
    unsigned n = 0;
    if (g_status != FS_MOUNTED && g_status != FS_FORMATTED) return 0;
    while (fat32_list(&g_fs, n, &e)) n++;
    return (int)n;
}

/*
 * One character of one name. Java gets its strings out of the filesystem a
 * byte at a time because a syscall returns an int and nothing else; the same
 * trick already carries string literals across in sys_str_byte.
 */
int fs_name_byte(int index, int offset)
{
    fat32_entry e;
    if (offset < 0 || offset >= FAT_NAME_LEN) return 0;
    if (!fat32_list(&g_fs, (unsigned)index, &e)) return 0;
    return (unsigned char)e.name[offset];
}

int fs_entry_size(int index)
{
    fat32_entry e;
    if (!fat32_list(&g_fs, (unsigned)index, &e)) return 0;
    return (int)e.size;
}

int fs_entry_is_dir(int index)
{
    fat32_entry e;
    if (!fat32_list(&g_fs, (unsigned)index, &e)) return 0;
    return e.is_dir;
}

/*
 * Free and total space in kilobytes, walking the FAT rather than trusting the
 * cached count in FSInfo.
 *
 * Counted as sectors/2 rather than clusters * bytes / 1024: with the 512 byte
 * clusters this volume uses, a cluster is half a kilobyte, and dividing first
 * rounds every cluster down to nothing. This read as "0 KB free of 0 KB" until
 * the harness printed it.
 */
int fs_free_kb(void)
{
    if (g_status != FS_MOUNTED && g_status != FS_FORMATTED) return 0;
    return (int)(fat32_free_clusters(&g_fs) * g_fs.sectors_per_cluster / 2);
}

int fs_total_kb(void)
{
    if (g_status != FS_MOUNTED && g_status != FS_FORMATTED) return 0;
    return (int)(g_fs.cluster_count * g_fs.sectors_per_cluster / 2);
}

/* ---------------------------------------------------------- edit buffer */
/* Java holds the caret and nothing else; see fsedit.h for why. Insert and
   delete do their shifting here, where moving a byte costs one loop iteration
   rather than one syscall. */

static unsigned char g_text[FS_TEXT_MAX];
static unsigned g_text_len;
static char g_name[FS_NAME_MAX + 1];
static unsigned g_name_len;
static int g_dirty;

static void name_clear(void)
{
    unsigned i;
    for (i = 0; i <= FS_NAME_MAX; i++) g_name[i] = 0;
    g_name_len = 0;
}

/* Turns the on-disk "README  TXT" back into "README.TXT". */
static void name_unpack(const char *packed)
{
    int i, end;

    name_clear();
    for (end = 8; end > 0 && packed[end - 1] == ' '; end--) { }
    for (i = 0; i < end; i++) g_name[g_name_len++] = packed[i];

    for (end = 11; end > 8 && packed[end - 1] == ' '; end--) { }
    if (end > 8) {
        g_name[g_name_len++] = '.';
        for (i = 8; i < end; i++) g_name[g_name_len++] = packed[i];
    }
    g_name[g_name_len] = 0;
}

static int text_insert(unsigned off, int ch)
{
    unsigned i;
    if (g_text_len >= FS_TEXT_MAX) return 0;
    if (off > g_text_len) return 0;
    for (i = g_text_len; i > off; i--) g_text[i] = g_text[i - 1];
    g_text[off] = (unsigned char)ch;
    g_text_len++;
    g_dirty = 1;
    return 1;
}

static int text_delete(unsigned off)
{
    unsigned i;
    if (off >= g_text_len) return 0;
    for (i = off; i + 1 < g_text_len; i++) g_text[i] = g_text[i + 1];
    g_text_len--;
    g_dirty = 1;
    return 1;
}

static int text_open(int index)
{
    fat32_entry e;
    int n;

    if (!fat32_list(&g_fs, (unsigned)index, &e)) return -1;
    if (e.is_dir) return -1;

    name_unpack(e.name);
    n = fat32_read_file(&g_fs, g_name, g_text, FS_TEXT_MAX);
    if (n < 0) return -1;
    g_text_len = (unsigned)n;
    g_dirty = 0;
    return n;
}

static int text_save(void)
{
    if (g_name_len == 0) return 0;
    if (g_status != FS_MOUNTED && g_status != FS_FORMATTED) return 0;
    if (!fat32_write_file(&g_fs, g_name, g_text, g_text_len)) return 0;
    g_dirty = 0;
    return 1;
}

static int line_of(int off)
{
    unsigned i, line = 0;
    if (off < 0) return 0;
    if ((unsigned)off > g_text_len) off = (int)g_text_len;
    for (i = 0; i < (unsigned)off; i++) {
        if (g_text[i] == '\n') line++;
    }
    return (int)line;
}

static int line_start(int line)
{
    unsigned i, n = 0;
    if (line <= 0) return 0;
    for (i = 0; i < g_text_len; i++) {
        if (g_text[i] == '\n') {
            n++;
            if (n == (unsigned)line) return (int)(i + 1);
        }
    }
    return -1;
}

static int line_end(int off)
{
    unsigned i;
    if (off < 0) off = 0;
    for (i = (unsigned)off; i < g_text_len && g_text[i] != '\n'; i++) { }
    return (int)i;
}

static int text_remove(int index)
{
    fat32_entry e;
    if (!fat32_list(&g_fs, (unsigned)index, &e)) return 0;
    if (e.is_dir) return 0;
    name_unpack(e.name);
    return fat32_delete_file(&g_fs, g_name);
}

int fs_edit(int op, int a, int b)
{
    if (op == ED_CAPACITY) return FS_TEXT_MAX;
    if (op == ED_LENGTH) return (int)g_text_len;
    if (op == ED_GET) {
        if (a < 0 || (unsigned)a >= g_text_len) return -1;
        return g_text[a];
    }
    if (op == ED_INSERT) return text_insert((unsigned)a, b);
    if (op == ED_DELETE) return text_delete((unsigned)a);
    if (op == ED_CLEAR) {
        g_text_len = 0;
        g_dirty = 0;
        name_clear();
        return 1;
    }
    if (op == ED_OPEN) return text_open(a);
    if (op == ED_SAVE) return text_save();
    if (op == ED_NAME_LEN) return (int)g_name_len;
    if (op == ED_NAME_GET) {
        if (a < 0 || (unsigned)a >= g_name_len) return -1;
        return (unsigned char)g_name[a];
    }
    if (op == ED_NAME_CLEAR) {
        name_clear();
        return 1;
    }
    if (op == ED_NAME_PUSH) {
        if (g_name_len >= FS_NAME_MAX) return 0;
        g_name[g_name_len++] = (char)a;
        g_name[g_name_len] = 0;
        return 1;
    }
    if (op == ED_NAME_POP) {
        if (g_name_len == 0) return 0;
        g_name[--g_name_len] = 0;
        return 1;
    }
    if (op == ED_REMOVE) return text_remove(a);
    if (op == ED_DIRTY) return g_dirty;
    if (op == ED_LINE_OF) return line_of(a);
    if (op == ED_LINE_START) return line_start(a);
    if (op == ED_LINE_END) return line_end(a);
    return 0;
}
