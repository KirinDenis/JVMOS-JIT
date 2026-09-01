/*
 * FAT32 for JVMOS.
 *
 * Scope: one partition, short (8.3) names, files in the root directory only.
 * That is enough to keep programs and data on the disk, which is what the
 * system is missing; subdirectories and long names can come later without
 * changing anything here.
 *
 * The driver is portable C and reaches the disk through two functions the
 * platform provides, so exactly the same code runs inside the kernel against
 * the ATA syscalls and on a desktop against an image file. That is the only
 * practical way to debug a filesystem for this system: a wrong offset in a
 * boot sector is invisible from inside the OS.
 */
#ifndef JVMOS_FAT32_H
#define JVMOS_FAT32_H

#define FAT_SECTOR_SIZE   512
#define FAT_NAME_LEN      11      /* 8.3, space padded, no dot stored */

/* Provided by the platform. Return 1 on success, 0 on failure. */
int fat_read_sector(unsigned lba, unsigned char *buffer);
int fat_write_sector(unsigned lba, const unsigned char *buffer);

typedef struct {
    unsigned partition_lba;      /* first sector of the partition */
    unsigned partition_sectors;
    unsigned fat_lba;            /* absolute LBA of the first FAT */
    unsigned data_lba;           /* absolute LBA of cluster 2 */
    unsigned sectors_per_cluster;
    unsigned fat_sectors;
    unsigned fat_count;
    unsigned root_cluster;
    unsigned cluster_count;
    int mounted;
} fat32_fs;

typedef struct {
    char name[FAT_NAME_LEN + 1]; /* space padded, terminated */
    unsigned size;
    unsigned cluster;
    int is_dir;
} fat32_entry;

/* Reads the partition table and boot sector. 1 if a usable volume was found. */
int fat32_mount(fat32_fs *fs);

/*
 * Writes a partition table and an empty FAT32 volume over the whole disk.
 * The first `reserved_sectors` are left outside the partition, which is where
 * the system image belongs; the filesystem never touches them.
 */
int fat32_format(fat32_fs *fs, unsigned disk_sectors, unsigned reserved_sectors);

/* Root directory listing. Returns 1 while `index` names an entry. */
int fat32_list(fat32_fs *fs, unsigned index, fat32_entry *out);

/* Reads a file into buffer, returns the number of bytes read, or -1. */
int fat32_read_file(fat32_fs *fs, const char *name, unsigned char *buffer, unsigned max);

/* Creates or replaces a file in the root. Returns 1 on success. */
int fat32_write_file(fat32_fs *fs, const char *name, const unsigned char *data, unsigned length);

/* Releases a file's clusters and frees its directory slot. 1 on success. */
int fat32_delete_file(fat32_fs *fs, const char *name);

/* Turns "README.TXT" into the padded on-disk form "README  TXT". */
void fat32_pack_name(const char *name, char *out11);

/* Counts unallocated clusters by walking the FAT. */
unsigned fat32_free_clusters(fat32_fs *fs);

#endif
