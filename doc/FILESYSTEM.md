# The disk

Until now the system had nowhere to put anything. Every program, every level,
every image was a literal compiled into the kernel, which means the only way to
change a file was to rebuild the whole OS. FAT32 is the smallest format that
fixes that and that every other machine can also read.

# What is on the disk

```
LBA 0            master boot record, one partition entry
LBA 1 .. 2047    the system area: reserved, outside the partition
LBA 2048         the FAT32 volume starts here
   +0            boot sector (BPB)
   +1            FSInfo
   +6            backup boot sector
   +32           first FAT
   +32+n         second FAT, kept identical
   ...           data, cluster 2 onwards; cluster 2 is the root directory
```

The first megabyte is deliberately left outside the partition. That is where a
system image belongs: a boot sector, and later the kernel itself, so the machine
can start from the disk instead of from the ISO. Because it is outside the
partition, the filesystem can never allocate into it, and the two can grow
without either one having to know about the other.

# Formatting

`fs_init` runs once at boot, from `sys_hardware_init` after interrupts are
enabled. It mounts what is there; if nothing mounts it formats, but only when
there is plainly nothing to lose.

A disk holding a partition of some other type is reported as `FS_FOREIGN` and
left alone. Reformatting somebody's disk at boot because we could not read it
would be unforgivable, and "I could not understand it" is not evidence that it
is empty.

Formatting also refuses unless the last sector the constant claims exists can
actually be read, so a wrong `FS_DISK_SECTORS` fails loudly instead of writing a
volume that claims space the disk does not have.

Immediately after a format the system writes `README.TXT`. A freshly formatted
volume that lists nothing looks exactly like a volume whose directory code is
broken, and writing one file exercises allocation, the FAT chain and the
directory entry on every first boot.

# Scope

One partition, short 8.3 names, files in the root directory only. That is
enough to keep programs and data on the disk, which is what the system was
missing. Subdirectories and long names can be added later without changing the
layout or the syscalls.

# The 65525 problem

The disk the project ships is 10MB, which with 512 byte clusters gives 18114
clusters. The FAT32 specification says a volume with fewer than 65525 clusters
is not FAT32; that threshold is what tells a driver whether to read the FAT as
12, 16 or 32 bit entries.

Our volume is structurally FAT32 in every other respect and our driver reads it,
but strict tools, Windows included, will refuse to mount it. Clearing the
threshold needs an image of about 34MB, and `disk.img` is downloaded by the
browser on every boot, so the cost is real and the benefit is currently nothing:
no tool other than ours opens the image today. The image size is one number, and
raising it is the whole fix if that ever changes.

`tests/fat32_verify.js` prints the cluster count and this note on every run, so
the trade-off is never silently forgotten.

# Portability, and why it is not optional

`fs/fat32.c` reaches the disk through two functions:

```c
int fat_read_sector(unsigned lba, unsigned char *buffer);
int fat_write_sector(unsigned lba, const unsigned char *buffer);
```

In the kernel `fs/fat32_disk.c` points them at the ATA syscalls. In
`tests/fat32_test.c` they point at a file, so the identical driver runs on a
desktop where a debugger exists.

This is not tidiness. A wrong offset in a boot sector is invisible from inside
the OS: the volume simply does not mount, and the window says nothing. Being
able to format an image and then read it with something that was not written
from the same misunderstanding is the only way to find that class of bug.
See `doc/TESTING.md`.

# The ATA routines

`sys_disk_read_sector` and `sys_disk_write_sector` were rewritten when the
filesystem started using them. Every wait is bounded and both report failure.

The original polled `in al, dx` / `test` with no limit and no error check. On a
machine with no disk the status port floats to `0xFF`, which has the busy bit
set, so the loop never ends: a missing `disk.img` would have been a frozen boot
rather than a File Manager saying there is no disk. The routines now also check
the drive's ERR and DF bits, and issue FLUSH CACHE after a write.
