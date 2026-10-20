# Filesystem Stack

SafeC provides a layered filesystem stack in `std/fs/` — from raw block devices up to a VFS abstraction with FAT32, ext2, and in-memory (tmpfs) drivers.

```c
#include <std/fs/fs.h>  // master header: pulls in all modules' types/signatures
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
#include <std/fs/block.h>
```

Driver interface for any block storage (SD card, flash, RAM disk, …). Build one with `block_device_new`, which takes your driver context and two callbacks typed against it; the device stores them type-erased (one VFS can mount devices from different drivers), and `block_device_new` is the only way they get set, so the pairing is checked at compile time.

```c
struct BlockDevice {
    unsigned long  sector_count;
    unsigned long  sector_size;   // typically 512

    int  read(unsigned long lba, &stack unsigned char buf, unsigned long count);         // 0 on success
    int  write(unsigned long lba, const &stack unsigned char buf, unsigned long count);
    int  valid() const;
};

generic<T>
struct BlockDevice block_device_new(&T ctx, unsigned long sector_count, unsigned long sector_size,
                                    fn int(&T, unsigned long, &unsigned char, unsigned long) read,
                                    fn int(&T, unsigned long, const &unsigned char, unsigned long) write);
```

```c
#include <std/fs/block.h>
#include <std/mem.h>

struct RamDisk { unsigned char bytes[64 * 512]; };

static int ram_read(&RamDisk d, unsigned long lba, &unsigned char buf, unsigned long count) {
    if ((lba + count) * 512UL > 64UL * 512UL) return -1;
    unsafe { std::safe_memcpy((void*)buf, (const void*)&d.bytes[lba * 512UL], count * 512UL); }
    return 0;
}

static int ram_write(&RamDisk d, unsigned long lba, const &unsigned char buf, unsigned long count) {
    if ((lba + count) * 512UL > 64UL * 512UL) return -1;
    unsafe { std::safe_memcpy((void*)&d.bytes[lba * 512UL], (const void*)buf, count * 512UL); }
    return 0;
}

static struct RamDisk g_disk;

int main() {
    struct BlockDevice dev = std::block_device_new(&g_disk, 64UL, 512UL, ram_read, ram_write);
    unsigned char sector[512];
    unsafe { sector[0] = 0x55; }
    dev.write(3UL, &sector[0], 1UL);
    unsafe { sector[0] = 0; }
    dev.read(3UL, &sector[0], 1UL);
    unsafe { return sector[0] == 0x55 ? 0 : 1; }
}
```

---

`BLOCK_SIZE` is 512 bytes per sector.

## Partition Table (MBR)

```c
#include <std/fs/partition.h>
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

Constants: `PART_MAX` (4 primary entries); partition types `PART_TYPE_EMPTY` (0x00), `PART_TYPE_FAT32` (0x0B), `PART_TYPE_FAT32X` (0x0C, FAT32 with LBA), `PART_TYPE_LINUX` (0x83), `PART_TYPE_SWAP` (0x82).

## VFS

```c
#include <std/fs/vfs.h>
```

The VFS layer routes paths to registered filesystem drivers using longest-prefix matching (e.g. `/mnt/sd/foo` routes to `/mnt/sd` if both `/mnt/sd` and `/mnt` are mounted).

### VfsOps — driver interface

The table a filesystem driver fills in (`tmpfs_ops()`, `fat_ops()`, `ext2_ops()` return ready-made ones). It is driver-internal: `ctx` is that driver's own context, stored type-erased because one `Vfs` mounts drivers with different context types. Application code never calls these directly — it goes through `vfs_mount`, `Vfs::open` and the `VfsNode` methods.

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
    void*         fs_ctx;    // driver-internal: the owning VfsMount

    unsigned long read(unsigned long offset, &stack unsigned char buf, unsigned long len);
    unsigned long write(unsigned long offset, const &stack unsigned char buf, unsigned long len);
}

// Calls cb(entry, user) for each child of directory `dir`; returns the count, -1 on error.
generic<T> int vfs_readdir(&VfsNode dir, fn void(&VfsNode, &T) cb, &T user);
```

### Vfs global

Mount with `vfs_mount`, passing the driver's context as a typed reference; open/unlink/mkdir are methods on the global `vfs_root`:

```c
extern struct Vfs vfs_root;  // global VFS instance
void vfs_init();             // zero it out; call once before any mount

// ctx is the context the driver's ops expect (TmpfsCtx, FatCtx, Ext2Ctx, ...)
generic<T> int vfs_mount(&Vfs v, const &char mountpoint, struct VfsOps ops, &T ctx);

struct Vfs {
    int  unmount(const &char mountpoint);
    int  open(const &char path, int flags, &stack VfsNode node_out);
    int  unlink(const &char path);
    int  mkdir(const &char path);
}
```

---

Constants:

| Name | Value | Meaning |
|------|-------|---------|
| `VFS_MAX_MOUNTS` | 8 | mount table size |
| `VFS_MAX_PATH` / `VFS_MAX_NAME` | 256 / 64 | path and name length limits |
| `VFS_TYPE_FILE` / `VFS_TYPE_DIR` | 0 / 1 | node types |
| `VFS_O_READ`, `VFS_O_WRITE`, `VFS_O_CREATE`, `VFS_O_TRUNC` | 0x01, 0x02, 0x04, 0x08 | open flags |
| `VFS_SEEK_SET`, `VFS_SEEK_CUR`, `VFS_SEEK_END` | 0, 1, 2 | seek origins |

## FAT32 Driver

```c
#include <std/fs/fat.h>
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
    int read_cluster(unsigned long cluster, &unsigned char buf);

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

Limits: directory walks go at most `FAT_MAX_DEPTH` (8) levels deep, and `fat_init` rejects clusters larger than `FAT_MAX_CLUSTER` (4096 bytes).

## ext2 Driver

```c
#include <std/fs/ext.h>
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
    int  read_block(unsigned long block_no, &unsigned char buf);

    // Read inode `ino` into `inode_out`.
    int  read_inode(unsigned long ino, &stack Ext2Inode inode_out);

    // Read file data for inode `ino` at byte `offset` into `buf` for `len` bytes.
    unsigned long read_file(unsigned long ino, unsigned long offset,
                             &unsigned char buf, unsigned long len);
}

// Initialise ext2 context from a block device (reads the superblock at
// byte offset 1024 from the device's LBA 0; validates EXT2_SUPER_MAGIC).
int ext2_init(&stack Ext2Ctx ctx, &stack BlockDevice dev);

// VfsOps for the ext2 driver — 'ctx' passed to Vfs::mount must be an Ext2Ctx*.
struct VfsOps ext2_ops();
```

`ext2_ops()` provides: **open** (path tokenisation, walks from root inode 2), **read** (direct blocks only — files > 48 KB with 4 KiB blocks need indirect-block support this driver doesn't have), **readdir**. Read-only. Compile-verified only — same real-disk-image caveat as FAT32 above.

---

Constants: `EXT2_SUPER_MAGIC` (0xEF53), `EXT2_ROOT_INODE` (2), `EXT2_BLOCK_SIZE_BASE` (1024); `ext2_init` rejects blocks larger than `EXT2_MAX_BLOCK_SIZE` (4096). Inode mode bits `EXT2_S_IFREG` (0x8000) and `EXT2_S_IFDIR` (0x4000) tell files from directories.

## tmpfs — In-Memory Filesystem

```c
#include <std/fs/tmpfs.h>
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
#include <std/fs/vfs.h>
#include <std/fs/tmpfs.h>

int main() {
    std::vfs_init();

    struct TmpfsCtx tmp;
    std::tmpfs_init(&tmp);
    std::vfs_mount(&std::vfs_root, "/tmp", std::tmpfs_ops(), &tmp);

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
#include <std/fs/fs.h>

// The board HAL's SD driver: context type + two callbacks typed against it.
struct SdCard { int spi_bus; };
int sd_read(&SdCard sd, unsigned long lba, &unsigned char buf, unsigned long count);
int sd_write(&SdCard sd, unsigned long lba, const &unsigned char buf, unsigned long count);

static struct SdCard g_card;
static struct FatCtx g_fat;      // FAT context holds a cluster buffer: keep it static

int mount_sd() {
    std::vfs_init();
    struct BlockDevice sd = std::block_device_new(&g_card, 16UL * 1024UL * 1024UL / 512UL, 512UL,
                                                  sd_read, sd_write);
    struct PartTable parts;
    if (std::partition_read(&sd, &parts) != 0) return -1;

    unsigned long part0_lba = 0UL;
    match (parts.get(0)) {
        case null:    return -1;
        case some(e): part0_lba = e.lba_start;
    }

    if (std::fat_init(&g_fat, &sd, part0_lba) != 0) return -1;
    std::vfs_mount(&std::vfs_root, "/sd", std::fat_ops(), &g_fat);

    struct VfsNode node;
    if (std::vfs_root.open("/sd/config.txt", VFS_O_READ, &node) == 0) {
        unsigned char buf[256];
        unsigned long n = node.read(0UL, &buf[0], 256UL);
        // process buf[0..n]
    }
    return 0;
}
```
