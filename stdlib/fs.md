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

## Architecture {#architecture}

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

Each layer talks to the one below through a small [trait](/reference/generics#composing-traits):
a filesystem driver is anything with the [`FsRead`/`FsWrite`](#fs-traits)
methods, and a block driver anything with the [`BlockDriver`](#block-driver)
methods. Neither writes a function table: `Vfs::mount` and
`BlockDevice::new` build one from the driver's methods at compile time.

---

## BlockDevice {#blockdevice}

```c
#include <std/fs/block.h>
```

Device interface for any block storage (SD card, flash, RAM disk, …). Build one with `BlockDevice::new` from a [`BlockDriver`](#block-driver): the device stores the driver's methods type-erased (one VFS can mount devices from different drivers), and `BlockDevice::new` is the only way they get set, so the pairing is checked at compile time.

```c
struct BlockDevice {
    unsigned long  sector_count;
    unsigned long  sector_size;   // typically 512

    // A device whose read()/write() call drv.read(...)/drv.write(...).
    // drv must outlive it.
    generic<T: BlockDriver>
    static struct BlockDevice new(&T drv, unsigned long sector_count, unsigned long sector_size);

    int read(unsigned long lba, []unsigned char buf);         // 0 on success
    int write(unsigned long lba, []const unsigned char buf);
    int  valid() const;
}
```

`BLOCK_SIZE` is 512 bytes per sector.

### BlockDriver {#block-driver}

| Trait | Requires | Satisfied by |
|---|---|---|
| `BlockDriver` | `read(lba, buf)` and `write(lba, buf)` (`[]unsigned char` / `[]const unsigned char`, a whole number of the device's `sector_size` sectors): 0 on success | `BlockDevice` itself, and your driver |

`BlockDevice::new` is a [method with its own type parameter](/reference/generics#methods-with-their-own-type-parameters)
bounded by `BlockDriver`; `FatCtx::init`, `Ext2Ctx::init` and
`PartTable::read` take the resulting `BlockDevice`. A driver conforms by
having the two methods — there is nothing to declare. A RAM disk:

```c
#include <std/fs/block.h>
#include <std/mem.h>

// A BlockDriver: any type with these two methods.
struct RamDisk {
    unsigned char bytes[64 * 512];

    int read(unsigned long lba, []unsigned char buf);
    int write(unsigned long lba, []const unsigned char buf);
};

int RamDisk::read(unsigned long lba, []unsigned char buf) {
    unsigned long off = lba * 512UL;
    if (off + buf.len > 64UL * 512UL) return -1;
    std::safe_memcpy(buf, self.bytes[off..off + buf.len]);
    return 0;
}

int RamDisk::write(unsigned long lba, []const unsigned char buf) {
    unsigned long off = lba * 512UL;
    if (off + buf.len > 64UL * 512UL) return -1;
    std::safe_memcpy(self.bytes[off..off + buf.len], buf);
    return 0;
}

static struct RamDisk g_disk;

int main() {
    struct BlockDevice dev = std::BlockDevice::new(&g_disk, 64UL, 512UL);
    unsigned char sector[512];
    unsafe { sector[0] = 0x55; }
    dev.write(3UL, sector[..]);
    unsafe { sector[0] = 0; }
    dev.read(3UL, sector[..]);
    unsafe { return sector[0] == 0x55 ? 0 : 1; }
}
```

A type missing either method is rejected at the `BlockDevice::new` call.
Network interfaces follow the same pattern with
[`NetDriver` and `NetIf::new`](/stdlib/net#net-driver).

---

## Partition Table (MBR) {#partition-table-mbr}

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

    // Read the MBR at LBA 0 of `dev` into this table; validates the
    // 0x55/0xAA boot signature. 0 on success, -1 on error.
    int read(&BlockDevice dev);
}
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

## VFS {#vfs}

```c
#include <std/fs/vfs.h>
```

The VFS layer routes paths to mounted filesystem drivers using longest-prefix matching (e.g. `/mnt/sd/foo` routes to `/mnt/sd` if both `/mnt/sd` and `/mnt` are mounted). Paths a driver sees are relative to its mountpoint (`/` for the mount's root).

### Filesystem traits {#fs-traits}

A filesystem driver is a context struct with methods. `vfs.h` names them as
two single-capability [traits](/reference/generics#composing-traits) and
composes them into the role a writable mount needs:

| Trait | Requires | Satisfied by |
|---|---|---|
| `FsRead` | `open(path, flags, &node_out)`: look up `path` (with `VFS_O_CREATE`, create a missing file) and fill the node's name, type, size and inode, 0 on success; `read(inode, offset, buf)`: bytes read; `readdir(inode, cb, user)`: `cb(entry, user)` per child, the entry count or −1 | `FatCtx`, `Ext2Ctx`, `TmpfsCtx` |
| `FsWrite` | `write(inode, offset, buf)`: bytes written; `unlink(path)`, `mkdir(path)`: 0 on success | `TmpfsCtx` |
| `FileSystem` | `FsRead + FsWrite` | `TmpfsCtx` |

They bound the two mount methods on `Vfs` ([methods with their own type
parameters](/reference/generics#methods-with-their-own-type-parameters)):

```c
generic<F: FileSystem> int Vfs::mount(const &char mountpoint, &F fs);     // read-write
generic<F: FsRead>     int Vfs::mount_ro(const &char mountpoint, &F fs);  // read-only
```

Each builds the mount's [`VfsOps`](#vfsops-driver-interface) table from
`F`'s methods at compile time — one thunk per method, instantiated for the
driver's type — so a driver writes no table of its own. A read-only mount
leaves the write entries empty: `write`, `unlink` and `mkdir` through it
fail. Mounting a read-only driver with `mount` is a compile error where it
is named (`type 'FatCtx' does not satisfy constraint 'FileSystem' ... (no
method 'write', from 'FsWrite')`).

Generic code over a driver is bounded by the smallest trait it uses: a
function over `FsRead` runs on FAT32, ext2 and tmpfs alike. A driver of
your own conforms by having the methods. This one serves a single
read-only file, mounts next to tmpfs, and is listed through the VFS:

```c
#include <std/fs/vfs.h>
#include <std/fs/tmpfs.h>
#include <std/str.h>

// A one-file read-only filesystem: "/" holds "version" (inode 1).
// Having open, read and readdir makes it an FsRead.
struct VersionFs {
    int unused_;

    int           open(const &char path, int flags, &VfsNode node_out);
    unsigned long read(unsigned long inode, unsigned long offset, []unsigned char buf);
    int           readdir(unsigned long inode, fn void(&VfsNode, ?&Erased) cb, ?&Erased user);
};

static void fill_file_(&VfsNode n) {
    n.name[0] = 'v'; n.name[1] = '\0';
    n.type = VFS_TYPE_FILE;
    n.size = 6UL;
    n.inode = 1UL;
}

int VersionFs::open(const &char path, int flags, &VfsNode node_out) {
    if ((flags & (VFS_O_WRITE | VFS_O_CREATE)) != 0) return -1;
    if (std::str_eq(path, "/")) {
        node_out.name[0] = '\0';
        node_out.type = VFS_TYPE_DIR;
        node_out.size = 0UL;
        node_out.inode = 0UL;
        return 0;
    }
    if (std::str_eq(path, "/version")) { fill_file_(node_out); return 0; }
    return -1;
}

unsigned long VersionFs::read(unsigned long inode, unsigned long offset, []unsigned char buf) {
    if (inode != 1UL || offset >= 6UL) return 0UL;
    unsigned long n = 6UL - offset;
    if (n > buf.len) n = buf.len;
    []const char text = std::str_bytes("1.4.2\n");
    unsigned long i = 0UL;
    while (i < n) {
        buf[i] = (unsigned char)text[offset + i];
        i = i + 1UL;
    }
    return n;
}

int VersionFs::readdir(unsigned long inode, fn void(&VfsNode, ?&Erased) cb, ?&Erased user) {
    if (inode != 0UL) return -1;
    struct VfsNode e;
    fill_file_(&e);
    cb(&e, user);
    return 1;
}

// Generic over any FsRead: the size of a file, straight on the driver.
generic<F: FsRead>
long long file_size(&F fs, const &char path) {
    struct VfsNode n;
    if (fs.open(path, VFS_O_READ, &n) != 0) return -1LL;
    return (long long)n.size;
}

static void count_entry(&VfsNode n, &int seen) { *seen = *seen + 1; }

static struct VersionFs g_ver;
static struct TmpfsCtx  g_tmp;

int main() {
    std::vfs_init();
    g_tmp.init();
    std::vfs_root.mount("/tmp", &g_tmp);       // TmpfsCtx: a FileSystem
    std::vfs_root.mount_ro("/etc", &g_ver);    // VersionFs: FsRead only

    if (file_size(&g_ver, "/version") != 6LL) return 1;
    if (file_size(&g_tmp, "/") != 0LL) return 2;   // the same function over tmpfs

    struct VfsNode f;
    if (std::vfs_root.open("/etc/version", VFS_O_READ, &f) != 0) return 3;
    unsigned char buf[8];
    if (f.read(0UL, buf[..]) != 6UL) return 4;
    if (std::vfs_root.mkdir("/etc/x") == 0) return 5;   // read-only mount refuses

    struct VfsNode dir;
    std::vfs_root.open("/etc", VFS_O_READ, &dir);
    int seen = 0;
    dir.readdir(count_entry, &seen);
    return seen == 1 ? 0 : 6;
}
```

A driver need not set `VfsNode.fs_ctx`: the VFS fills it in, on opened
nodes and on the entries it hands to a `readdir` callback alike, so an
entry can be `read()` straight from the callback.
`tests/stdlib/tests/test_fs.sc` runs one generic `FsRead` check over
`TmpfsCtx`, `FatCtx` and `Ext2Ctx`.

### VfsOps — driver interface {#vfsops-driver-interface}

The type-erased table a mount calls through. `Vfs::mount` / `mount_ro` build it from the driver's methods ([above](#fs-traits)); `ctx` is the driver's own context, stored type-erased because one `Vfs` mounts drivers with different context types. A null entry (a read-only mount's `write`/`unlink`/`mkdir`) makes that call fail. Application code never calls these directly — it goes through `Vfs::open` and the `VfsNode` methods.

```c
struct VfsOps {
    ?fn int(&Erased ctx, const &char path, int flags, &VfsNode node_out) open;
    ?fn int(&Erased ctx, const &char path) unlink;
    ?fn int(&Erased ctx, const &char path) mkdir;
    ?fn unsigned long(&Erased ctx, unsigned long inode, unsigned long off, []unsigned char buf) read;
    ?fn unsigned long(&Erased ctx, unsigned long inode, unsigned long off, []const unsigned char buf) write;
    ?fn int(&Erased ctx, unsigned long inode,
            fn void(&VfsNode, ?&Erased) cb, ?&Erased user) readdir;
}
```

### VfsNode — open file handle {#vfsnode-open-file-handle}

```c
struct VfsNode {
    char          name[64];
    int           type;      // VFS_TYPE_FILE / VFS_TYPE_DIR
    unsigned long size;
    unsigned long inode;
    ?&struct VfsMount fs_ctx; // driver-internal: the owning VfsMount

    unsigned long read(unsigned long offset, []unsigned char buf);
    unsigned long write(unsigned long offset, []const unsigned char buf);

    // Calls cb(entry, user) for each child of this directory; returns the
    // count, -1 on error. T is the type of your user data.
    generic<T> int readdir(fn void(&VfsNode, &T) cb, &T user);
}
```

### Vfs global {#vfs-global}

Mount a driver with `mount` (a [`FileSystem`](#fs-traits)) or `mount_ro` (any [`FsRead`](#fs-traits)), passing its context by reference; open/unlink/mkdir go through the same global `vfs_root`:

```c
extern struct Vfs vfs_root;  // global VFS instance
void vfs_init();             // zero it out; call once before any mount

struct Vfs {
    // 0 on success, -1 if every mount slot is taken. fs must outlive the mount.
    generic<F: FileSystem> int mount(const &char mountpoint, &F fs);
    generic<F: FsRead>     int mount_ro(const &char mountpoint, &F fs);

    int  unmount(const &char mountpoint);
    int  open(const &char path, int flags, &VfsNode node_out);   // 0 on success
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

## FAT32 Driver {#fat32-driver}

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

    // Initialise this context in place from a block device partition
    // starting at `lba` (the device is copied into `dev`). 0 on success.
    int init(&BlockDevice dev, unsigned long lba);

    // Read a cluster into `buf` (must be >= bytes_per_cluster bytes).
    int read_cluster(unsigned long cluster, []unsigned char buf);

    // Follow the FAT chain to the cluster at position `idx` from `start_cluster`.
    unsigned long follow_fat(unsigned long start_cluster, unsigned long idx);

    // FsRead: mount with vfs_root.mount_ro(mountpoint, &ctx)
    int           open(const &char path, int flags, &VfsNode node_out);
    unsigned long read(unsigned long inode, unsigned long offset, []unsigned char buf);
    int           readdir(unsigned long inode, fn void(&VfsNode, ?&Erased) cb, ?&Erased user);
}
```

`FatCtx` is an [`FsRead`](#fs-traits): **open** (8.3 path walk from the root cluster; names match case-insensitively), **read** (cluster chain traversal), **readdir**. It has no write methods, so it mounts with `mount_ro`. `tests/stdlib/tests/test_fs.sc` reads a FAT32 image built by `mkfs.vfat` (skipped when the tool is missing).

---

Limits: directory walks go at most `FAT_MAX_DEPTH` (8) levels deep, and `FatCtx::init` rejects clusters larger than `FAT_MAX_CLUSTER` (4096 bytes).

## ext2 Driver {#ext2-driver}

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

    // Initialise this context in place from a block device (copied into
    // `dev`): reads the superblock at byte offset 1024 from the device's
    // LBA 0 and validates EXT2_SUPER_MAGIC. 0 on success.
    int  init(&BlockDevice dev);

    // Read block `block_no` into `buf` (must be >= block_size bytes).
    int read_block(unsigned long block_no, []unsigned char buf);

    // Read inode `ino` into `inode_out`.
    int  read_inode(unsigned long ino, &Ext2Inode inode_out);

    // Read file data for inode `ino` at byte `offset` into `buf` for `len` bytes.
    unsigned long read_file(unsigned long ino, unsigned long offset, []unsigned char buf);

    // FsRead: mount with vfs_root.mount_ro(mountpoint, &ctx)
    int           open(const &char path, int flags, &VfsNode node_out);
    unsigned long read(unsigned long inode, unsigned long offset, []unsigned char buf);
    int           readdir(unsigned long inode, fn void(&VfsNode, ?&Erased) cb, ?&Erased user);
}
```

`Ext2Ctx` is an [`FsRead`](#fs-traits): **open** (path tokenisation, walks from root inode 2), **read** (direct blocks only — files > 48 KB with 4 KiB blocks need indirect-block support this driver doesn't have), **readdir**. Read-only, so it mounts with `mount_ro`. `test_fs.sc` reads an ext2 image built by `mke2fs -d` (skipped when the tool is missing).

---

Constants: `EXT2_SUPER_MAGIC` (0xEF53), `EXT2_ROOT_INODE` (2), `EXT2_BLOCK_SIZE_BASE` (1024); `Ext2Ctx::init` rejects blocks larger than `EXT2_MAX_BLOCK_SIZE` (4096). Inode mode bits `EXT2_S_IFREG` (0x8000) and `EXT2_S_IFDIR` (0x4000) tell files from directories.

## tmpfs — In-Memory Filesystem {#tmpfs-in-memory-filesystem}

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

    // Initialise this context in place (zero-fills, creates the root).
    void init();

    // FileSystem: mount with vfs_root.mount(mountpoint, &ctx)
    int           open(const &char path, int flags, &VfsNode node_out);
    unsigned long read(unsigned long inode, unsigned long offset, []unsigned char buf);
    int           readdir(unsigned long inode, fn void(&VfsNode, ?&Erased) cb, ?&Erased user);
    unsigned long write(unsigned long inode, unsigned long offset, []const unsigned char buf);
    int           unlink(const &char path);
    int           mkdir(const &char path);
}
```

`TmpfsCtx` is a full [`FileSystem`](#fs-traits): **open**, **read**, **write**, **unlink**, **mkdir**, **readdir**. It can also be mounted read-only with `mount_ro`.

---

## Mount and Read Example {#mount-and-read-example}

Mounting tmpfs and doing a real write/read round-trip — verified against a real compile/run:

```c
#include <std/fs/vfs.h>
#include <std/fs/tmpfs.h>

static struct TmpfsCtx tmp;   // 64 KiB data pool: keep it static

int main() {
    std::vfs_init();

    tmp.init();
    std::vfs_root.mount("/tmp", &tmp);

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

    return n == 8UL ? 0 : 1;
}
```

Mounting a real block device follows the same shape — wrap the driver in a `BlockDevice`, parse the partition table, `init()` the filesystem context (`FatCtx`/`Ext2Ctx`) from the partition's starting LBA, then `mount_ro()` it alongside tmpfs (type-checked against the current signatures above; not run here since it needs SD-card hardware):

```c
#include <std/fs/fs.h>

// The board HAL's SD driver: a BlockDriver (read and write sectors).
struct SdCard {
    int spi_bus;
    int read(unsigned long lba, []unsigned char buf);
    int write(unsigned long lba, []const unsigned char buf);
};

static struct SdCard g_card;
static struct FatCtx g_fat;      // FAT context holds a cluster buffer: keep it static

int mount_sd() {
    std::vfs_init();
    struct BlockDevice sd = std::BlockDevice::new(&g_card, 16UL * 1024UL * 1024UL / 512UL, 512UL);
    struct PartTable parts;
    if (parts.read(&sd) != 0) return -1;

    unsigned long part0_lba = 0UL;
    match (parts.get(0)) {
        case null:    return -1;
        case some(e): part0_lba = e.lba_start;
    }

    if (g_fat.init(&sd, part0_lba) != 0) return -1;   // copies the device
    std::vfs_root.mount_ro("/sd", &g_fat);            // FatCtx is FsRead only

    struct VfsNode node;
    if (std::vfs_root.open("/sd/config.txt", VFS_O_READ, &node) == 0) {
        unsigned char buf[256];
        unsigned long n = node.read(0UL, buf[0..256]);
        // process buf[0..n]
    }
    return 0;
}
```
