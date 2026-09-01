/*
 * FAT32 implementation. See fat32.h for the scope.
 *
 * Everything is little endian on disk and is read and written byte by byte
 * rather than by casting a struct over the buffer: the kernel is compiled with
 * its own flags and this way the layout cannot drift with alignment or packing
 * decisions. It also makes each offset in the specification visible in the
 * code, which matters when a single wrong offset makes a volume unreadable.
 */
#include "fat32.h"

static unsigned char g_sector[FAT_SECTOR_SIZE];

static void put16(unsigned char *p, unsigned v)
{
    p[0] = (unsigned char)(v & 0xFF);
    p[1] = (unsigned char)((v >> 8) & 0xFF);
}

static void put32(unsigned char *p, unsigned v)
{
    p[0] = (unsigned char)(v & 0xFF);
    p[1] = (unsigned char)((v >> 8) & 0xFF);
    p[2] = (unsigned char)((v >> 16) & 0xFF);
    p[3] = (unsigned char)((v >> 24) & 0xFF);
}

static unsigned get16(const unsigned char *p)
{
    return (unsigned)p[0] | ((unsigned)p[1] << 8);
}

static unsigned get32(const unsigned char *p)
{
    return (unsigned)p[0] | ((unsigned)p[1] << 8) |
           ((unsigned)p[2] << 16) | ((unsigned)p[3] << 24);
}

static void zero(unsigned char *p, unsigned n)
{
    while (n--) *p++ = 0;
}

static void copy(unsigned char *d, const unsigned char *s, unsigned n)
{
    while (n--) *d++ = *s++;
}

static int same_name(const char *a, const char *b)
{
    int i;
    for (i = 0; i < FAT_NAME_LEN; i++) {
        if (a[i] != b[i]) return 0;
    }
    return 1;
}

static char upper(char c)
{
    if (c >= 'a' && c <= 'z') return (char)(c - 32);
    return c;
}

void fat32_pack_name(const char *name, char *out11)
{
    int i = 0, o = 0;
    for (i = 0; i < FAT_NAME_LEN; i++) out11[i] = ' ';

    /* base, up to eight characters, stopping at the dot */
    for (i = 0; name[i] && name[i] != '.' && o < 8; i++) out11[o++] = upper(name[i]);
    while (name[i] && name[i] != '.') i++;

    if (name[i] == '.') {
        i++;
        o = 8;
        for (; name[i] && o < FAT_NAME_LEN; i++) out11[o++] = upper(name[i]);
    }
    out11[FAT_NAME_LEN] = 0;
}

/* ------------------------------------------------------------------ FAT */

static unsigned fat_entry_get(fat32_fs *fs, unsigned cluster)
{
    unsigned offset = cluster * 4u;
    unsigned lba = fs->fat_lba + offset / FAT_SECTOR_SIZE;
    if (!fat_read_sector(lba, g_sector)) return 0x0FFFFFF7;
    return get32(&g_sector[offset % FAT_SECTOR_SIZE]) & 0x0FFFFFFFu;
}

static int fat_entry_set(fat32_fs *fs, unsigned cluster, unsigned value)
{
    unsigned offset = cluster * 4u;
    unsigned within = offset % FAT_SECTOR_SIZE;
    unsigned i;

    /* Both copies of the FAT are kept identical, as a real driver would. */
    for (i = 0; i < fs->fat_count; i++) {
        unsigned lba = fs->fat_lba + i * fs->fat_sectors + offset / FAT_SECTOR_SIZE;
        if (!fat_read_sector(lba, g_sector)) return 0;
        put32(&g_sector[within], value & 0x0FFFFFFFu);
        if (!fat_write_sector(lba, g_sector)) return 0;
    }
    return 1;
}

static unsigned cluster_lba(fat32_fs *fs, unsigned cluster)
{
    return fs->data_lba + (cluster - 2) * fs->sectors_per_cluster;
}

static unsigned alloc_cluster(fat32_fs *fs)
{
    unsigned c;
    for (c = 2; c < fs->cluster_count + 2; c++) {
        if (fat_entry_get(fs, c) == 0) {
            if (!fat_entry_set(fs, c, 0x0FFFFFFFu)) return 0;
            return c;
        }
    }
    return 0;
}

static void free_chain(fat32_fs *fs, unsigned cluster)
{
    unsigned guard = 0;
    while (cluster >= 2 && cluster < 0x0FFFFFF8u && guard < 0x100000u) {
        unsigned next = fat_entry_get(fs, cluster);
        fat_entry_set(fs, cluster, 0);
        cluster = next;
        guard++;
    }
}

/* --------------------------------------------------------------- mount */

int fat32_mount(fat32_fs *fs)
{
    unsigned part_lba, part_sectors, reserved, bytes_per_sector;

    fs->mounted = 0;

    if (!fat_read_sector(0, g_sector)) return 0;
    if (g_sector[510] != 0x55 || g_sector[511] != 0xAA) return 0;

    /* first partition entry */
    part_lba = get32(&g_sector[0x1BE + 8]);
    part_sectors = get32(&g_sector[0x1BE + 12]);
    if (part_lba == 0 || part_sectors == 0) return 0;

    if (!fat_read_sector(part_lba, g_sector)) return 0;
    if (g_sector[510] != 0x55 || g_sector[511] != 0xAA) return 0;

    bytes_per_sector = get16(&g_sector[11]);
    if (bytes_per_sector != FAT_SECTOR_SIZE) return 0;
    if (get16(&g_sector[22]) != 0) return 0;          /* FATSz16 must be 0 on FAT32 */

    reserved = get16(&g_sector[14]);
    fs->partition_lba = part_lba;
    fs->partition_sectors = part_sectors;
    fs->sectors_per_cluster = g_sector[13];
    fs->fat_count = g_sector[16];
    fs->fat_sectors = get32(&g_sector[36]);
    fs->root_cluster = get32(&g_sector[44]);
    fs->fat_lba = part_lba + reserved;
    fs->data_lba = fs->fat_lba + fs->fat_count * fs->fat_sectors;

    if (fs->sectors_per_cluster == 0 || fs->fat_sectors == 0 ||
        fs->fat_count == 0 || fs->root_cluster < 2) {
        return 0;
    }

    fs->cluster_count = (part_sectors - reserved - fs->fat_count * fs->fat_sectors) /
                        fs->sectors_per_cluster;
    fs->mounted = 1;
    return 1;
}

/* -------------------------------------------------------------- format */

int fat32_format(fat32_fs *fs, unsigned disk_sectors, unsigned reserved_sectors)
{
    unsigned part_lba = reserved_sectors;
    unsigned part_sectors, reserved = 32, fat_sectors, tmp1, tmp2, i;
    unsigned spc = 1;

    if (disk_sectors <= reserved_sectors + 128) return 0;
    part_sectors = disk_sectors - part_lba;

    /* Sizing rule from the FAT specification. */
    tmp1 = part_sectors - reserved;
    tmp2 = (256u * spc + 2u) / 2u;
    fat_sectors = (tmp1 + tmp2 - 1) / tmp2;
    if (fat_sectors == 0) return 0;

    /* --- master boot record, with the system area left outside --- */
    zero(g_sector, FAT_SECTOR_SIZE);
    g_sector[0x1BE + 0] = 0x80;                    /* bootable */
    g_sector[0x1BE + 1] = 0x01;                    /* CHS start, unused with LBA */
    g_sector[0x1BE + 2] = 0x01;
    g_sector[0x1BE + 3] = 0x00;
    g_sector[0x1BE + 4] = 0x0C;                    /* FAT32 LBA */
    g_sector[0x1BE + 5] = 0xFE;
    g_sector[0x1BE + 6] = 0xFF;
    g_sector[0x1BE + 7] = 0xFF;
    put32(&g_sector[0x1BE + 8], part_lba);
    put32(&g_sector[0x1BE + 12], part_sectors);
    g_sector[510] = 0x55;
    g_sector[511] = 0xAA;
    if (!fat_write_sector(0, g_sector)) return 0;

    /* --- volume boot record --- */
    zero(g_sector, FAT_SECTOR_SIZE);
    g_sector[0] = 0xEB; g_sector[1] = 0x58; g_sector[2] = 0x90;
    copy(&g_sector[3], (const unsigned char *)"JVMOS1.0", 8);
    put16(&g_sector[11], FAT_SECTOR_SIZE);
    g_sector[13] = (unsigned char)spc;
    put16(&g_sector[14], reserved);
    g_sector[16] = 2;                              /* two FATs */
    put16(&g_sector[17], 0);                       /* no fixed root directory */
    put16(&g_sector[19], 0);                       /* total sectors live in the 32-bit field */
    g_sector[21] = 0xF8;                           /* fixed disk */
    put16(&g_sector[22], 0);                       /* FATSz16 = 0 marks FAT32 */
    put16(&g_sector[24], 63);
    put16(&g_sector[26], 255);
    put32(&g_sector[28], part_lba);                /* hidden sectors */
    put32(&g_sector[32], part_sectors);
    put32(&g_sector[36], fat_sectors);
    put16(&g_sector[40], 0);                       /* flags: FATs mirrored */
    put16(&g_sector[42], 0);                       /* version */
    put32(&g_sector[44], 2);                       /* root directory cluster */
    put16(&g_sector[48], 1);                       /* FSInfo sector */
    put16(&g_sector[50], 6);                       /* backup boot sector */
    g_sector[64] = 0x80;
    g_sector[66] = 0x29;                           /* extended signature */
    put32(&g_sector[67], 0x4A564D53u);             /* volume id */
    copy(&g_sector[71], (const unsigned char *)"JVMOS      ", 11);
    copy(&g_sector[82], (const unsigned char *)"FAT32   ", 8);
    g_sector[510] = 0x55;
    g_sector[511] = 0xAA;
    if (!fat_write_sector(part_lba, g_sector)) return 0;
    if (!fat_write_sector(part_lba + 6, g_sector)) return 0;   /* backup copy */

    /* --- FSInfo --- */
    zero(g_sector, FAT_SECTOR_SIZE);
    put32(&g_sector[0], 0x41615252u);
    put32(&g_sector[484], 0x61417272u);
    put32(&g_sector[488], 0xFFFFFFFFu);            /* free count unknown */
    put32(&g_sector[492], 0xFFFFFFFFu);            /* next free unknown */
    put32(&g_sector[508], 0xAA550000u);
    if (!fat_write_sector(part_lba + 1, g_sector)) return 0;

    /* --- both FATs, cleared, with the reserved entries and the root chain --- */
    zero(g_sector, FAT_SECTOR_SIZE);
    for (i = 0; i < fat_sectors * 2u; i++) {
        if (!fat_write_sector(part_lba + reserved + i, g_sector)) return 0;
    }
    put32(&g_sector[0], 0x0FFFFFF8u);              /* media descriptor */
    put32(&g_sector[4], 0x0FFFFFFFu);              /* end of chain marker */
    put32(&g_sector[8], 0x0FFFFFFFu);              /* root directory, one cluster */
    if (!fat_write_sector(part_lba + reserved, g_sector)) return 0;
    if (!fat_write_sector(part_lba + reserved + fat_sectors, g_sector)) return 0;

    /* --- empty root directory --- */
    zero(g_sector, FAT_SECTOR_SIZE);
    for (i = 0; i < spc; i++) {
        if (!fat_write_sector(part_lba + reserved + 2u * fat_sectors + i, g_sector)) return 0;
    }

    return fat32_mount(fs);
}

/* ---------------------------------------------------------- directory */

/*
 * Walks the root directory. `slot` counts 32-byte entries from the start of
 * the chain, so callers can both enumerate and locate a free slot.
 */
static int root_slot_read(fat32_fs *fs, unsigned slot, unsigned char *out32, unsigned *lba_out, unsigned *off_out)
{
    unsigned per_sector = FAT_SECTOR_SIZE / 32;
    unsigned per_cluster = per_sector * fs->sectors_per_cluster;
    unsigned cluster = fs->root_cluster;
    unsigned guard = 0;

    while (slot >= per_cluster) {
        cluster = fat_entry_get(fs, cluster);
        if (cluster < 2 || cluster >= 0x0FFFFFF8u) return 0;
        slot -= per_cluster;
        if (++guard > 0x10000u) return 0;
    }

    {
        unsigned lba = cluster_lba(fs, cluster) + slot / per_sector;
        unsigned off = (slot % per_sector) * 32u;
        if (!fat_read_sector(lba, g_sector)) return 0;
        copy(out32, &g_sector[off], 32);
        if (lba_out) *lba_out = lba;
        if (off_out) *off_out = off;
        return 1;
    }
}

int fat32_list(fat32_fs *fs, unsigned index, fat32_entry *out)
{
    unsigned char raw[32];
    unsigned slot = 0, seen = 0;
    int i;

    if (!fs->mounted) return 0;

    while (root_slot_read(fs, slot, raw, 0, 0)) {
        if (raw[0] == 0x00) return 0;                       /* nothing further */
        if (raw[0] != 0xE5 && (raw[11] & 0x0F) != 0x0F &&   /* not deleted, not a long name */
            (raw[11] & 0x08) == 0) {                        /* not the volume label */
            if (seen == index) {
                for (i = 0; i < FAT_NAME_LEN; i++) out->name[i] = (char)raw[i];
                out->name[FAT_NAME_LEN] = 0;
                out->is_dir = (raw[11] & 0x10) ? 1 : 0;
                out->cluster = (get16(&raw[20]) << 16) | get16(&raw[26]);
                out->size = get32(&raw[28]);
                return 1;
            }
            seen++;
        }
        slot++;
    }
    return 0;
}

static int find_entry(fat32_fs *fs, const char *name11, unsigned *slot_out, unsigned char *raw)
{
    unsigned slot = 0;
    while (root_slot_read(fs, slot, raw, 0, 0)) {
        if (raw[0] == 0x00) return 0;
        if (raw[0] != 0xE5 && (raw[11] & 0x0F) != 0x0F) {
            if (same_name((const char *)raw, name11)) {
                if (slot_out) *slot_out = slot;
                return 1;
            }
        }
        slot++;
    }
    return 0;
}

/* -------------------------------------------------------------- files */

int fat32_read_file(fat32_fs *fs, const char *name, unsigned char *buffer, unsigned max)
{
    char packed[FAT_NAME_LEN + 1];
    unsigned char raw[32];
    unsigned cluster, remaining, done = 0, guard = 0;

    if (!fs->mounted) return -1;
    fat32_pack_name(name, packed);
    if (!find_entry(fs, packed, 0, raw)) return -1;

    cluster = (get16(&raw[20]) << 16) | get16(&raw[26]);
    remaining = get32(&raw[28]);
    if (remaining > max) remaining = max;

    while (remaining > 0 && cluster >= 2 && cluster < 0x0FFFFFF8u) {
        unsigned s;
        for (s = 0; s < fs->sectors_per_cluster && remaining > 0; s++) {
            unsigned take = remaining < FAT_SECTOR_SIZE ? remaining : FAT_SECTOR_SIZE;
            if (!fat_read_sector(cluster_lba(fs, cluster) + s, g_sector)) return -1;
            copy(buffer + done, g_sector, take);
            done += take;
            remaining -= take;
        }
        cluster = fat_entry_get(fs, cluster);
        if (++guard > 0x100000u) return -1;
    }
    return (int)done;
}

int fat32_write_file(fat32_fs *fs, const char *name, const unsigned char *data, unsigned length)
{
    char packed[FAT_NAME_LEN + 1];
    unsigned char raw[32];
    unsigned slot, lba, off;
    unsigned first = 0, prev = 0, written = 0;
    int i;

    if (!fs->mounted) return 0;
    fat32_pack_name(name, packed);

    /* Replacing a file releases its old clusters first. */
    if (find_entry(fs, packed, &slot, raw)) {
        unsigned old = (get16(&raw[20]) << 16) | get16(&raw[26]);
        if (old >= 2) free_chain(fs, old);
    } else {
        slot = 0;
        for (;;) {
            if (!root_slot_read(fs, slot, raw, 0, 0)) return 0;   /* directory full */
            if (raw[0] == 0x00 || raw[0] == 0xE5) break;
            slot++;
        }
    }

    /* Write the data, extending the chain a cluster at a time. */
    while (written < length) {
        unsigned cluster = alloc_cluster(fs);
        unsigned s;
        if (cluster == 0) return 0;                            /* out of space */
        if (first == 0) first = cluster;
        if (prev != 0) fat_entry_set(fs, prev, cluster);
        prev = cluster;

        for (s = 0; s < fs->sectors_per_cluster; s++) {
            unsigned take = length - written;
            if (take > FAT_SECTOR_SIZE) take = FAT_SECTOR_SIZE;
            zero(g_sector, FAT_SECTOR_SIZE);
            copy(g_sector, data + written, take);
            if (!fat_write_sector(cluster_lba(fs, cluster) + s, g_sector)) return 0;
            written += take;
            if (written >= length) break;
        }
    }

    /* Finally the directory entry, so a half written file is never listed. */
    if (!root_slot_read(fs, slot, raw, &lba, &off)) return 0;
    zero(raw, 32);
    for (i = 0; i < FAT_NAME_LEN; i++) raw[i] = (unsigned char)packed[i];
    raw[11] = 0x20;                                            /* archive */
    put16(&raw[20], (first >> 16) & 0xFFFF);
    put16(&raw[26], first & 0xFFFF);
    put32(&raw[28], length);

    if (!fat_read_sector(lba, g_sector)) return 0;
    copy(&g_sector[off], raw, 32);
    return fat_write_sector(lba, g_sector);
}

/* ---------------------------------------------------------- free space */

/*
 * Counts unallocated clusters by reading the FAT straight through, a sector at
 * a time. FSInfo carries a cached count, but it is only a hint that any driver
 * is allowed to leave stale, so walking is the answer that cannot be wrong.
 */
unsigned fat32_free_clusters(fat32_fs *fs)
{
    unsigned free_count = 0;
    unsigned examined = 0;
    unsigned s;

    if (!fs->mounted) return 0;

    for (s = 0; s < fs->fat_sectors && examined < fs->cluster_count + 2; s++) {
        unsigned i;
        if (!fat_read_sector(fs->fat_lba + s, g_sector)) return free_count;
        for (i = 0; i < FAT_SECTOR_SIZE; i += 4) {
            if (examined >= fs->cluster_count + 2) break;
            /* entries 0 and 1 are the media descriptor and the end marker */
            if (examined >= 2 && (get32(&g_sector[i]) & 0x0FFFFFFFu) == 0) free_count++;
            examined++;
        }
    }
    return free_count;
}
