# Filesystem Stack

SafeC provides a layered filesystem stack in `std/fs/` — from raw block devices up to a VFS abstraction with FAT32, ext2, and in-memory (tmpfs) drivers.

```c
#include "fs/fs.h"  // master header: pulls in all modules' types/signatures
```

::: warning `fs.h` is types/signatures only
Unlike some other std modules, there's no single `fs.sc` bundling every
module's implementation — include the specific `.sc` file(s) you actually
need (`block.sc`, `partition.sc`, `vfs.sc`, `fat.sc`, `ext.sc`, `tmpfs.sc`)
alongside `fs.h`, the same way the examples below do.
:::

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
    .ctx          = (void*)0,
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
    unsigned long  lba_start;
    unsigned long  sector_count;
}

struct PartTable {
    PartEntry entries[4];
    int       count;             // number of non-empty entries

    // Reference into entries[], or empty (null) if idx is out of range --
    // check entry.type != PART_TYPE_EMPTY too, for a valid index that just
    // has no partition recorded there.
    const ?&PartEntry get(int idx) const;
}

// Read MBR at LBA 0; validates 0x55/0xAA boot signature
int partition_read(&stack BlockDevice dev, &stack PartTable table_out);
```

`get()` returns a nullable reference — unwrap it with `match` (or `.is_null()`/`.default()`) rather than chaining straight into a field:

```c
unsigned long lba = 0;
match (parts.get(0)) {
    case null:    break;
    case some(e): lba = e.lba_start;
}
```

---

## VFS

```c
#include "fs/vfs.h"
```

The VFS layer routes paths to registered filesystem drivers using longest-prefix matching (e.g. `/mnt/sd/foo` routes to `/mnt/sd` if both `/mnt/sd` and `/mnt` are mounted).

### VfsOps — driver interface

Each field is a `fn ReturnType(Params) name;` function-pointer field (SafeC's typed-callback syntax, not C's `int(*name)(...)` declarator form) — `ctx` is always the driver's own opaque context, passed back on every call. Driver callbacks take raw `unsigned char*` (FFI boundary); `VfsNode`'s own methods cast to/from safe references before forwarding to them:

```c
struct VfsOps {
    fn int(void* ctx, unsigned long size) mount;
    fn int(void* ctx) unmount;
    fn int(void* ctx, const char* path, int flags, &stack VfsNode node_out) open;
    fn int(void* ctx, const char* path) unlink;
    fn int(void* ctx, const char* path) mkdir;
    fn unsigned long(void* ctx, unsigned long inode, unsigned long off,
                      unsigned char* buf, unsigned long len) read;
    fn unsigned long(void* ctx, unsigned long inode, unsigned long off,
                      const unsigned char* buf, unsigned long len) write;
    fn int(void* ctx, unsigned long inode, void* cb, void* user) readdir;
}
```

### VfsNode — open file handle

```c
struct VfsNode {
    char          name[64];
    int           type;      // VFS_TYPE_FILE / VFS_TYPE_DIR
    unsigned long size;
    unsigned long inode;
    void*         fs_ctx;    // opaque pointer back to the owning VfsMount

    // Safe-reference methods — cast to raw inside unsafe{} when forwarding to VfsOps
    unsigned long read(unsigned long offset, &stack unsigned char buf, unsigned long len);
    unsigned long write(unsigned long offset, const &stack unsigned char buf, unsigned long len);
    int           readdir(void* cb, void* user);
}
```

### Vfs global

There's no free-function `vfs_mount`/`vfs_open` API — call methods directly on the global `vfs_root` instance:

```c
extern struct Vfs vfs_root;  // global VFS instance
void vfs_init();             // zero it out; call once before any mount

struct Vfs {
    int  mount(const char* mountpoint, struct VfsOps ops, void* ctx);
    int  unmount(const char* mountpoint);
    int  open(const char* path, int flags, &stack VfsNode node_out);
    int  unlink(const char* path);
    int  mkdir(const char* path);
}
```

---

## FAT32 Driver

```c
#include "fs/fat.h"
```

Read-only FAT32 driver with 8.3 filename support. `FatBpb` is the raw on-disk BIOS Parameter Block (every field FAT32 defines) — not meant to be read directly; `FatCtx`'s own `fat_lba`/`data_lba`/`root_cluster`/`bytes_per_cluster` fields are the already-derived values callers actually need:

```c
struct FatCtx {
    struct BlockDevice dev;
    unsigned long  partition_lba;      // LBA of FAT32 partition start
    struct FatBpb  bpb;                // raw BIOS Parameter Block
    unsigned long  fat_lba;            // LBA of FAT region
    unsigned long  data_lba;           // LBA of first data cluster
    unsigned long  root_cluster;       // cluster of root directory
    unsigned long  bytes_per_cluster;

    // Read a cluster into `buf` (must be >= bytes_per_cluster bytes).
    int read_cluster(unsigned long cluster, unsigned char* buf);

    // Follow the FAT chain to the cluster at position `idx` from `start_cluster`.
    unsigned long follow_fat(unsigned long start_cluster, unsigned long idx);
}

// Initialise FAT32 context from a block device partition starting at `lba`.
int fat_init(&stack FatCtx ctx, &stack BlockDevice dev, unsigned long lba);

// VfsOps for the FAT32 driver — 'ctx' passed to Vfs::mount must be a FatCtx*.
struct VfsOps fat_ops();
```

`fat_ops()` provides: **open** (8.3 path walk from root cluster), **read** (cluster chain traversal), **readdir**. Write and unlink return `-1` (read-only). Compile-verified only — mounting and reading a real FAT32 volume needs an actual formatted disk image, not available in this environment.

---

## ext2 Driver

```c
#include "fs/ext.h"
```

Read-only ext2 (second extended filesystem) driver. Supports direct blocks (files ≤ 48 KB with 4 KiB blocks). No indirect block support.

```c
struct Ext2Super {
    unsigned int  inodes_count;
    unsigned int  blocks_count;
    unsigned int  reserved_blocks;
    unsigned int  free_blocks;
    unsigned int  free_inodes;
    unsigned int  first_data_block;
    unsigned int  log_block_size;   // block_size = 1024 << log_block_size
    unsigned int  log_frag_size;
    unsigned int  blocks_per_group;
    unsigned int  frags_per_group;
    unsigned int  inodes_per_group;
    unsigned short magic;           // must be EXT2_SUPER_MAGIC (0xEF53)
    // ...plus timestamp/mount-count/OS fields not needed by callers
}

struct Ext2Inode {
    unsigned short mode;
    unsigned int   size_lo;
    unsigned short links_count;
    unsigned int   blocks_count;  // 512-byte blocks
    unsigned int   block[15];     // 12 direct + 1 indirect + 1 dbl + 1 trpl
    // ...plus uid/gid/timestamps/flags
}

struct Ext2Ctx {
    struct BlockDevice dev;
    struct Ext2Super   super;
    unsigned long      block_size;
    unsigned long      inodes_per_group;
    unsigned long      blocks_per_group;
    unsigned long      group_count;

    // Read block `block_no` into `buf` (must be >= block_size bytes).
    int  read_block(unsigned long block_no, unsigned char* buf);

    // Read inode `ino` into `inode_out`.
    int  read_inode(unsigned long ino, &stack Ext2Inode inode_out);

    // Read file data for inode `ino` at byte `offset` into `buf` for `len` bytes.
    unsigned long read_file(unsigned long ino, unsigned long offset,
                             unsigned char* buf, unsigned long len);
}

// Initialise ext2 context from a block device (reads the superblock at
// byte offset 1024 from the device's LBA 0; validates EXT2_SUPER_MAGIC).
int ext2_init(&stack Ext2Ctx ctx, &stack BlockDevice dev);

// VfsOps for the ext2 driver — 'ctx' passed to Vfs::mount must be an Ext2Ctx*.
struct VfsOps ext2_ops();
```

`ext2_ops()` provides: **open** (path tokenisation, walks from root inode 2), **read** (direct blocks only — files > 48 KB with 4 KiB blocks need indirect-block support this driver doesn't have), **readdir**. Read-only. Compile-verified only — same real-disk-image caveat as FAT32 above.

---

## tmpfs — In-Memory Filesystem

```c
#include "fs/tmpfs.h"
```

A fully in-memory filesystem. No block device required. Supports 32 inodes and 64 KiB of data. Suitable for `/tmp`, configuration staging, or test fixtures.

```c
struct TmpfsInode {
    char           name[64];
    int            type;            // VFS_TYPE_FILE / VFS_TYPE_DIR
    unsigned long  parent;          // parent inode index (0 = root)
    unsigned long  data_off;        // offset into data pool (file only)
    unsigned long  size;
    int            used;
}

struct TmpfsCtx {
    TmpfsInode     inodes[32];      // TMPFS_MAX_FILES
    unsigned char  data[65536];     // TMPFS_MAX_DATA
    unsigned long  data_used;
    int            inode_count;
}

// Initialise a tmpfs context (zero-fills).
void tmpfs_init(&stack TmpfsCtx ctx);

// VfsOps for the tmpfs driver — 'ctx' passed to Vfs::mount must be a TmpfsCtx*.
struct VfsOps tmpfs_ops();
```

`tmpfs_ops()` provides full **open**, **read**, **write**, **unlink**, **mkdir**, **readdir**.

---

## Mount and Read Example

Mounting tmpfs and doing a real write/read round-trip — verified against a real compile/run:

```c
#include "fs/vfs.h"
#include "fs/vfs.sc"
#include "fs/tmpfs.h"
#include "fs/tmpfs.sc"

int main() {
    std::vfs_init();

    struct TmpfsCtx tmp;
    std::tmpfs_init(&tmp);
    void* tmp_ctx;
    unsafe { tmp_ctx = (void*)&tmp; }
    std::vfs_root.mount("/tmp", std::tmpfs_ops(), tmp_ctx);

    // Write
    struct VfsNode node;
    std::vfs_root.open("/tmp/log.txt", VFS_O_WRITE | VFS_O_CREATE, &node);
    unsigned char msg[8];
    msg[0]='b'; msg[1]='o'; msg[2]='o'; msg[3]='t';
    msg[4]=' '; msg[5]='o'; msg[6]='k'; msg[7]='\n';
    node.write(0, msg, 8UL);

    // Read back
    struct VfsNode node2;
    std::vfs_root.open("/tmp/log.txt", VFS_O_READ, &node2);
    unsigned char buf[8];
    unsigned long n = node2.read(0, buf, 8UL);
    // buf[0..n] == "boot ok\n"

    return 0;
}
```

Mounting a real block device follows the same shape — parse the partition table, `fat_init`/`ext2_init` the driver context from the partition's starting LBA, then `mount()` its `VfsOps` alongside tmpfs (type-checked against the current signatures above; not run here since it needs a real formatted disk image):

```c
#include "fs/fs.h"
#include "fs/block.sc"
#include "fs/partition.sc"
#include "fs/fat.sc"

// Assume sd_read/sd_write are provided by the board HAL
struct BlockDevice sd = {
    .read_fn      = (void*)sd_read,
    .write_fn     = (void*)sd_write,
    .ctx          = (void*)0,
    .sector_count = 16*1024*1024 / 512,
    .sector_size  = 512
};

int main() {
    std::vfs_init();

    struct PartTable parts;
    std::partition_read(&sd, &parts);

    unsigned long part0_lba = 0;
    match (parts.get(0)) {
        case null:    break;
        case some(e): part0_lba = e.lba_start;
    }

    struct FatCtx fat;
    std::fat_init(&fat, &sd, part0_lba);
    void* fat_ctx;
    unsafe { fat_ctx = (void*)&fat; }
    std::vfs_root.mount("/sd", std::fat_ops(), fat_ctx);

    struct VfsNode node;
    if (std::vfs_root.open("/sd/config.txt", VFS_O_READ, &node) == 0) {
        unsigned char buf[256];
        unsigned long n = node.read(0, buf, 256UL);
        // process buf[0..n]
    }

    return 0;
}
```
