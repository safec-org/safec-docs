# Filesystem Stack

SafeC provides a layered filesystem stack in `std/fs/` — from raw block devices up to a VFS abstraction with FAT32, ext2, and in-memory (tmpfs) drivers.

```c
#include "fs/fs.h"  // master header: pulls in all modules below
```

## Architecture

```
┌────────────────────────────────┐
│  VFS  (path routing, open)     │
├──────────┬─────────────────────┤
│  FAT32   │   ext2   │  tmpfs   │
├──────────┴─────────────────────┤
│  Partition table (MBR)         │
├────────────────────────────────┤
│  BlockDevice (driver interface)│
└────────────────────────────────┘
```

---

## BlockDevice

```c
#include "fs/block.h"
```

Driver interface for any block storage (SD card, flash, RAM disk, etc.):

```c
struct BlockDevice {
    // Function pointer fields — raw C pointers (driver interface boundary)
    void*          read_fn;   // int(*)(void* ctx, lba, unsigned char* buf, count)
    void*          write_fn;  // int(*)(void* ctx, lba, const unsigned char* buf, count)
    void*          ctx;
    unsigned long  sector_count;
    unsigned long  sector_size;   // typically 512

    int  read(unsigned long lba, &stack unsigned char buf, unsigned long count);
    int  write(unsigned long lba, const &stack unsigned char buf, unsigned long count);
    int  valid() const;  // 1 if read_fn != NULL and sector_count > 0
}
```

Implement a driver by populating the function pointers. The function pointer signatures use raw `unsigned char*` because they are C FFI callbacks — the raw-to-safe boundary is crossed inside `BlockDevice::read/write` via an explicit cast inside `unsafe {}`:

```c
int my_sd_read(void* ctx, unsigned long lba,
               unsigned char* buf, unsigned long count) {
    // ... read `count` sectors at `lba` into `buf`
    return 0;  // 0 = success, negative = error
}

struct BlockDevice sd = {
    .read_fn      = (void*)my_sd_read,
    .write_fn     = (void*)my_sd_write,
    .ctx          = 0,
    .sector_count = 8*1024*1024 / 512,
    .sector_size  = 512
};

// Caller uses safe references — the unsafe cast happens inside the method:
unsigned char sector[512];
sd.read(0, sector, 1);
```

---

## Partition Table (MBR)

```c
#include "fs/partition.h"
```

Parses the classic 4-entry MBR partition table:

```c
struct PartEntry {
    unsigned char  status;       // 0x80 = bootable
    unsigned char  type;         // partition type byte
    unsigned int   lba_start;
    unsigned int   sector_count;
}

struct PartTable {
    PartEntry     entries[4];
    unsigned long count;         // number of non-empty entries

    PartEntry get(unsigned long idx) const;
}

// Read MBR at LBA 0; validates 0x55/0xAA boot signature
int partition_read(struct BlockDevice* dev, struct PartTable* out);
```

---

## VFS

```c
#include "fs/vfs.h"
```

The VFS layer routes paths to registered filesystem drivers using longest-prefix matching (e.g. `/mnt/sd/foo` routes to `/mnt/sd` if both `/mnt/sd` and `/mnt` are mounted).

### VfsOps — driver interface

Driver callbacks use raw C pointer types (FFI boundary). The `VfsNode` methods cast to safe references before forwarding:

```c
struct VfsOps {
    int(*mount)(void* ctx, unsigned long size);
    int(*unmount)(void* ctx);
    int(*open)(void* ctx, const char* path, int flags, &stack VfsNode node_out);
    int(*unlink)(void* ctx, const char* path);
    int(*mkdir)(void* ctx, const char* path);
    // raw unsigned char* here — C function pointer signatures stay raw
    unsigned long (*read)(void* ctx, unsigned long inode, unsigned long off,
                          unsigned char* buf, unsigned long len);
    unsigned long (*write)(void* ctx, unsigned long inode, unsigned long off,
                           const unsigned char* buf, unsigned long len);
    int(*readdir)(void* ctx, unsigned long inode, void* cb, void* user);
}
```

### VfsNode — open file handle

```c
struct VfsNode {
    char          name[64];
    int           type;      // VFS_TYPE_FILE / VFS_TYPE_DIR
    unsigned long size;
    unsigned long inode;
    void*         fs_ctx;    // opaque pointer back to mounted filesystem

    // Safe-reference methods — cast to raw inside unsafe{} when forwarding to VfsOps
    unsigned long read(unsigned long offset, &stack unsigned char buf, unsigned long len);
    unsigned long write(unsigned long offset, const &stack unsigned char buf, unsigned long len);
    int           readdir(void* cb, void* user);
}
```

### Vfs global

```c
extern struct Vfs vfs;  // global VFS instance

int vfs_mount(const char* path, struct VfsOps* ops, void* ctx);
int vfs_unmount(const char* path);
int vfs_open(const char* path, struct VfsNode* out);
int vfs_unlink(const char* path);
int vfs_mkdir(const char* path);
```

---

## FAT32 Driver

```c
#include "fs/fat.h"
```

Read-only FAT32 driver with 8.3 filename support:

```c
struct FatBpb {
    unsigned short bytes_per_sector;
    unsigned char  sectors_per_cluster;
    unsigned short reserved_sectors;
    unsigned char  num_fats;
    unsigned int   sectors_per_fat;
    unsigned int   root_cluster;
    unsigned long  data_lba;
    unsigned long  fat_lba;
}

struct FatCtx {
    struct BlockDevice dev;
    FatBpb             bpb;

    int           read_cluster(unsigned long cluster, void* out);
    unsigned int  follow_fat(unsigned int cluster);  // next cluster in chain
}

// Initialise by reading BPB from `lba`
int fat_init(struct FatCtx* ctx, struct BlockDevice dev, unsigned long lba);

// Build VfsOps to mount this FAT32 volume into the VFS
struct VfsOps fat_ops(struct FatCtx* ctx);
```

`fat_ops` provides: **open** (8.3 path walk from root cluster), **read** (cluster chain traversal), **readdir**. Write and unlink return `-1` (read-only).

---

## ext2 Driver

```c
#include "fs/ext.h"
```

Read-only ext2 (second extended filesystem) driver. Supports direct blocks (files ≤ 48 KB with 4 KiB blocks). No indirect block support.

```c
struct Ext2Super {
    unsigned long inode_count;
    unsigned long block_count;
    unsigned long block_size;      // 1024 << log_block_size
    unsigned long inodes_per_group;
    unsigned long blocks_per_group;
    unsigned long group_count;
}

struct Ext2Inode {
    unsigned short mode;
    unsigned long  size;
    unsigned int   block[15];  // 0..11 direct, 12 single-indirect, etc.
    unsigned short links_count;
}

struct Ext2Ctx {
    struct BlockDevice dev;
    Ext2Super          super;
    unsigned long      lba;

    int read_block(unsigned long block_no, void* out);
    int read_inode(unsigned long ino, struct Ext2Inode* out);
    int read_file(struct Ext2Inode* ino, void* buf, unsigned long len);
}

// Read superblock at byte offset 1024 from `lba`; validate EXT2_SUPER_MAGIC (0xEF53)
int ext2_init(struct Ext2Ctx* ctx, struct BlockDevice dev, unsigned long lba);

struct VfsOps ext2_ops(struct Ext2Ctx* ctx);
```

`ext2_ops` provides: **open** (path tokenisation, walks from root inode 2), **read** (direct blocks), **readdir**. Read-only.

---

## tmpfs — In-Memory Filesystem

```c
#include "fs/tmpfs.h"
```

A fully in-memory filesystem. No block device required. Supports 32 inodes and 64 KiB of data. Suitable for `/tmp`, configuration staging, or test fixtures.

```c
struct TmpfsCtx {
    // embedded inode table (32 entries)
    // embedded data pool (65536 bytes)
}

int tmpfs_init(struct TmpfsCtx* ctx);

struct VfsOps tmpfs_ops(struct TmpfsCtx* ctx);
```

`tmpfs_ops` provides full **open**, **read**, **write**, **unlink**, **mkdir**, **readdir**.

---

## Mount and Read Example

```c
#include "fs/fs.h"

// Assume sd_read/sd_write are provided by the board HAL
struct BlockDevice sd = { sd_read, sd_write, 0, 16*1024*1024/512, 512 };

int main() {
    // Parse MBR partition table
    struct PartTable parts;
    partition_read(&sd, &parts);

    // Mount first FAT32 partition
    struct FatCtx fat;
    fat_init(&fat, sd, parts.get(0).lba_start);
    struct VfsOps fat_ops_impl = fat_ops(&fat);
    vfs_mount("/sd", &fat_ops_impl, &fat);

    // Mount tmpfs at /tmp
    struct TmpfsCtx tmp;
    tmpfs_init(&tmp);
    struct VfsOps tmp_ops = tmpfs_ops(&tmp);
    vfs_mount("/tmp", &tmp_ops, &tmp);

    // Open and read a file from SD card
    struct VfsNode node;
    if (vfs_root.open("/sd/config.txt", VFS_O_READ, node) == 0) {
        unsigned char buf[256];
        unsigned long n = node.read(0, buf, sizeof(buf));
        // process buf[0..n]
    }

    // Write a file to tmpfs
    struct VfsNode tmp_node;
    vfs_root.open("/tmp/log.txt", VFS_O_WRITE | VFS_O_CREATE, tmp_node);
    unsigned char msg[] = "boot ok\n";
    tmp_node.write(0, msg, 8);

    return 0;
}
```
