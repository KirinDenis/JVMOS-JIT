/*
 * Runs the FAT32 driver against an image file instead of the ATA syscalls.
 * The driver itself is unchanged: only the two sector functions differ, which
 * is the whole point of keeping them behind an interface.
 *
 *   fat32_test <image> format <megabytes>
 *   fat32_test <image> write <name> <text>
 *   fat32_test <image> read <name>
 *   fat32_test <image> list
 *
 * Not part of the kernel build; the Makefile filters tests/ out.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../fs/fat32.h"

static FILE *g_img;

int fat_read_sector(unsigned lba, unsigned char *buffer)
{
    if (fseek(g_img, (long)lba * FAT_SECTOR_SIZE, SEEK_SET) != 0) return 0;
    if (fread(buffer, 1, FAT_SECTOR_SIZE, g_img) != FAT_SECTOR_SIZE) return 0;
    return 1;
}

int fat_write_sector(unsigned lba, const unsigned char *buffer)
{
    if (fseek(g_img, (long)lba * FAT_SECTOR_SIZE, SEEK_SET) != 0) return 0;
    if (fwrite(buffer, 1, FAT_SECTOR_SIZE, g_img) != FAT_SECTOR_SIZE) return 0;
    fflush(g_img);
    return 1;
}

int main(int argc, char **argv)
{
    fat32_fs fs;
    const char *op;

    if (argc < 3) {
        printf("ERR usage\n");
        return 2;
    }
    g_img = fopen(argv[1], "r+b");
    if (!g_img) {
        printf("ERR cannot open %s\n", argv[1]);
        return 2;
    }
    op = argv[2];

    if (strcmp(op, "boot") == 0) {
        /* What fs_init does on a blank disk, so the result can be inspected
           from outside the OS instead of squinting at a window. */
        unsigned mb = argc > 3 ? (unsigned)atoi(argv[3]) : 10;
        unsigned sectors = mb * 1024u * 1024u / FAT_SECTOR_SIZE;
        const char *text =
            "JVMOS-JIT\r\n"
            "\r\n"
            "This volume was created by the system when it found no filesystem\r\n"
            "on the disk. Cluster size is 512 bytes.\r\n"
            "\r\n"
            "The first megabyte of the disk lies outside the partition and is\r\n"
            "reserved for the system image, so the two can grow separately.\r\n";
        unsigned n = 0;
        while (text[n]) n++;
        if (fat32_mount(&fs)) {
            printf("OK already mounted, would not format\n");
            return 0;
        }
        if (!fat32_format(&fs, sectors, 2048)) {
            printf("ERR format failed\n");
            return 1;
        }
        if (!fat32_write_file(&fs, "README.TXT", (const unsigned char *)text, n)) {
            printf("ERR could not write the welcome file\n");
            return 1;
        }
        printf("OK formatted and wrote README.TXT, %u bytes\n", n);
        return 0;
    }

    if (strcmp(op, "format") == 0) {
        unsigned mb = argc > 3 ? (unsigned)atoi(argv[3]) : 10;
        unsigned sectors = mb * 1024u * 1024u / FAT_SECTOR_SIZE;
        /* 1MB up front stays outside the partition for the system image */
        if (!fat32_format(&fs, sectors, 2048)) {
            printf("ERR format failed\n");
            return 1;
        }
        printf("OK formatted %u sectors, %u clusters of %u sectors, fat %u sectors\n",
               sectors, fs.cluster_count, fs.sectors_per_cluster, fs.fat_sectors);
        return 0;
    }

    if (!fat32_mount(&fs)) {
        printf("ERR mount failed\n");
        return 1;
    }

    if (strcmp(op, "list") == 0) {
        fat32_entry e;
        unsigned i = 0;
        unsigned spc = fs.sectors_per_cluster;
        while (fat32_list(&fs, i, &e)) {
            printf("%s %u%s\n", e.name, e.size, e.is_dir ? " DIR" : "");
            i++;
        }
        /* sectors/2, not clusters*bytes/1024: a 512 byte cluster is 0 KB */
        printf("OK %u entries, %u KB free of %u KB\n", i,
               fat32_free_clusters(&fs) * spc / 2, fs.cluster_count * spc / 2);
        return 0;
    }

    if (strcmp(op, "write") == 0 && argc >= 5) {
        const char *text = argv[4];
        if (!fat32_write_file(&fs, argv[3], (const unsigned char *)text, (unsigned)strlen(text))) {
            printf("ERR write failed\n");
            return 1;
        }
        printf("OK wrote %u bytes\n", (unsigned)strlen(text));
        return 0;
    }

    if (strcmp(op, "writebig") == 0 && argc >= 5) {
        /* a file spanning many clusters, to exercise the chain */
        unsigned n = (unsigned)atoi(argv[4]);
        unsigned char *buf = (unsigned char *)malloc(n);
        unsigned i;
        int ok;
        for (i = 0; i < n; i++) buf[i] = (unsigned char)(i * 7 + (i >> 8));
        ok = fat32_write_file(&fs, argv[3], buf, n);
        free(buf);
        if (!ok) {
            printf("ERR write failed\n");
            return 1;
        }
        printf("OK wrote %u bytes\n", n);
        return 0;
    }

    if (strcmp(op, "read") == 0 && argc >= 4) {
        static unsigned char buf[1 << 20];
        int n = fat32_read_file(&fs, argv[3], buf, sizeof(buf));
        if (n < 0) {
            printf("ERR no such file\n");
            return 1;
        }
        printf("OK %d bytes: ", n);
        {
            int i;
            for (i = 0; i < n && i < 60; i++) putchar(buf[i] >= 32 && buf[i] < 127 ? buf[i] : '.');
        }
        printf("\n");
        return 0;
    }

    if (strcmp(op, "verifybig") == 0 && argc >= 5) {
        static unsigned char buf[1 << 20];
        unsigned n = (unsigned)atoi(argv[4]);
        int got = fat32_read_file(&fs, argv[3], buf, sizeof(buf));
        unsigned i;
        if (got != (int)n) {
            printf("ERR size %d, expected %u\n", got, n);
            return 1;
        }
        for (i = 0; i < n; i++) {
            if (buf[i] != (unsigned char)(i * 7 + (i >> 8))) {
                printf("ERR byte %u differs\n", i);
                return 1;
            }
        }
        printf("OK %u bytes verified\n", n);
        return 0;
    }

    printf("ERR unknown op\n");
    return 2;
}
