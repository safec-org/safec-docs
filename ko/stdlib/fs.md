# 파일시스템 스택

SafeC는 `std/fs/`에 계층화된 파일시스템 스택을 제공합니다 — 원시 블록 장치부터 FAT32, ext2, 인메모리(tmpfs) 드라이버를 갖춘 VFS 추상화까지 아우릅니다.

```c
#include <std/fs/fs.h>  // master header: pulls in all modules' types/signatures
```

::: warning `fs.h`는 타입/시그니처만 포함합니다
다른 일부 std 모듈과 달리, 모든 모듈의 구현을 하나로 묶은 단일 `fs.sc`는 존재하지
않습니다 — 아래 예제들이 하는 것과 마찬가지로, `fs.h`와 함께 실제로 필요한 개별
`.sc` 파일(`block.sc`, `partition.sc`, `vfs.sc`, `fat.sc`, `ext.sc`, `tmpfs.sc`)을
직접 include합니다.
:::

## 아키텍처 {#architecture}

```
┌────────────────────────────────┐
│  VFS  (경로 라우팅, open)        │
├──────────┬─────────────────────┤
│  FAT32   │   ext2   │  tmpfs   │
├──────────┴─────────────────────┤
│  파티션 테이블 (MBR)              │
├────────────────────────────────┤
│  BlockDevice (드라이버 인터페이스)  │
└────────────────────────────────┘
```

각 계층은 작은 [트레이트](/ko/reference/generics#composing-traits)를 통해 아래 계층과
소통합니다. 파일시스템 드라이버는 [`FsRead`/`FsWrite`](#fs-traits) 메서드를 가진
모든 타입이고, 블록 드라이버는 [`BlockDriver`](#block-driver) 메서드를 가진 모든
타입입니다. 어느 쪽도 함수 테이블을 직접 작성하지 않습니다: `Vfs::mount`와
`BlockDevice::new`가 컴파일 타임에 드라이버의 메서드로부터 테이블을 만듭니다.

---

## BlockDevice {#blockdevice}

```c
#include <std/fs/block.h>
```

모든 블록 저장 장치(SD 카드, 플래시, RAM 디스크, …)를 위한 장치 인터페이스입니다. [`BlockDriver`](#block-driver)로부터 `BlockDevice::new`로 만듭니다. 장치는 드라이버의 메서드를 타입 소거된 형태로 저장하지만(하나의 VFS가 서로 다른 드라이버의 장치를 마운트할 수 있도록), 이를 설정하는 유일한 방법이 `BlockDevice::new`이므로 짝이 맞는지는 컴파일 타임에 검사됩니다.

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

`BLOCK_SIZE`는 섹터당 512바이트입니다.

### BlockDriver {#block-driver}

| 트레이트 | 요구 사항 | 만족하는 타입 |
|---|---|---|
| `BlockDriver` | `read(lba, buf, count)`와 `write(lba, buf, count)`: 장치의 `sector_size` 단위 섹터, 성공 시 0 | `BlockDevice` 자신, 그리고 여러분의 드라이버 |

`BlockDevice::new`는 `BlockDriver`로 제한된
[자체 타입 매개변수를 가진 메서드](/ko/reference/generics#methods-with-their-own-type-parameters)입니다.
`FatCtx::init`, `Ext2Ctx::init`, `PartTable::read`는 그 결과인 `BlockDevice`를
받습니다. 드라이버는 두 메서드를 가지기만 하면 준수합니다 — 따로 선언할 것은
없습니다. RAM 디스크 예:

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

두 메서드 중 하나라도 없는 타입은 `BlockDevice::new` 호출 지점에서 거부됩니다.
네트워크 인터페이스도 [`NetDriver`와 `NetIf::new`](/ko/stdlib/net#net-driver)로 같은
패턴을 따릅니다.

---

## 파티션 테이블 (MBR) {#partition-table-mbr}

```c
#include <std/fs/partition.h>
```

고전적인 4개 항목 MBR 파티션 테이블을 파싱합니다:

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

`get()`은 널 가능(nullable) 참조를 반환합니다 — 필드로 곧장 체이닝하지 말고 `match`(또는 `.is_null()`/`.default()`)로 언래핑합니다:

```c
unsigned long lba = 0;
match (parts.get(0)) {
    case null:    break;
    case some(e): lba = e.lba_start;
}
```

---

상수: `PART_MAX`(기본 엔트리 4개); 파티션 타입 `PART_TYPE_EMPTY`(0x00), `PART_TYPE_FAT32`(0x0B), `PART_TYPE_FAT32X`(0x0C, LBA를 쓰는 FAT32), `PART_TYPE_LINUX`(0x83), `PART_TYPE_SWAP`(0x82).

## VFS {#vfs}

```c
#include <std/fs/vfs.h>
```

VFS 계층은 최장 접두사 매칭(longest-prefix matching)을 사용해 경로를 마운트된 파일시스템 드라이버로 라우팅합니다(예: `/mnt/sd`와 `/mnt`가 둘 다 마운트되어 있으면 `/mnt/sd/foo`는 `/mnt/sd`로 라우팅됩니다). 드라이버가 보는 경로는 마운트 지점에 대한 상대 경로입니다(마운트의 루트는 `/`).

### 파일시스템 트레이트 {#fs-traits}

파일시스템 드라이버는 메서드를 가진 컨텍스트 구조체입니다. `vfs.h`는 이 메서드들을
단일 기능 [트레이트](/ko/reference/generics#composing-traits) 두 개로 이름 붙이고,
쓰기 가능한 마운트에 필요한 역할로 합성합니다:

| 트레이트 | 요구 사항 | 만족하는 타입 |
|---|---|---|
| `FsRead` | `open(path, flags, &node_out)`: `path`를 찾아(`VFS_O_CREATE`이면 없는 파일을 생성) 노드의 이름, 타입, 크기, 아이노드를 채우고 성공 시 0; `read(inode, offset, buf)`: 읽은 바이트 수; `readdir(inode, cb, user)`: 자식마다 `cb(entry, user)`, 항목 수 또는 −1 | `FatCtx`, `Ext2Ctx`, `TmpfsCtx` |
| `FsWrite` | `write(inode, offset, buf)`: 쓴 바이트 수; `unlink(path)`, `mkdir(path)`: 성공 시 0 | `TmpfsCtx` |
| `FileSystem` | `FsRead + FsWrite` | `TmpfsCtx` |

이 트레이트들은 `Vfs`의 두 마운트 메서드를 제한합니다([자체 타입 매개변수를 가진
메서드](/ko/reference/generics#methods-with-their-own-type-parameters)):

```c
generic<F: FileSystem> int Vfs::mount(const &char mountpoint, &F fs);     // read-write
generic<F: FsRead>     int Vfs::mount_ro(const &char mountpoint, &F fs);  // read-only
```

각 메서드는 컴파일 타임에 `F`의 메서드로부터 마운트의
[`VfsOps`](#vfsops-driver-interface) 테이블을 만듭니다 — 메서드마다 드라이버
타입에 대해 인스턴스화된 썽크(thunk) 하나씩 — 따라서 드라이버는 자체 테이블을
작성하지 않습니다. 읽기 전용 마운트는 쓰기 항목을 비워 두므로, 그 마운트를 통한
`write`, `unlink`, `mkdir`은 실패합니다. 읽기 전용 드라이버를 `mount`로 마운트하면
호출한 지점에서 컴파일 오류가 납니다(`type 'FatCtx' does not satisfy constraint
'FileSystem' ... (no method 'write', from 'FsWrite')`).

드라이버에 대한 제네릭 코드는 실제로 사용하는 가장 작은 트레이트로 제한합니다:
`FsRead`에 대한 함수는 FAT32, ext2, tmpfs에서 똑같이 동작합니다. 직접 만든
드라이버도 메서드를 가지기만 하면 준수합니다. 아래 드라이버는 읽기 전용 파일 하나를
제공하며, tmpfs 옆에 마운트되고 VFS를 통해 목록이 조회됩니다:

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

드라이버는 `VfsNode.fs_ctx`를 설정할 필요가 없습니다: VFS가 열린 노드와 `readdir`
콜백에 넘기는 항목 모두에 이를 채워 넣으므로, 콜백 안에서 항목을 곧바로 `read()`할
수 있습니다. `tests/stdlib/tests/test_fs.sc`는 하나의 제네릭 `FsRead` 검사를
`TmpfsCtx`, `FatCtx`, `Ext2Ctx`에 대해 실행합니다.

### VfsOps — 드라이버 인터페이스 {#vfsops-driver-interface}

마운트가 호출할 때 거치는 타입 소거된 테이블입니다. `Vfs::mount` / `mount_ro`가 드라이버의 메서드로부터 이를 만듭니다([위 참고](#fs-traits)). `ctx`는 드라이버 자신의 컨텍스트이며, 하나의 `Vfs`가 서로 다른 컨텍스트 타입의 드라이버를 마운트하므로 타입 소거된 형태로 저장됩니다. 널 항목(읽기 전용 마운트의 `write`/`unlink`/`mkdir`)은 해당 호출을 실패하게 합니다. 애플리케이션 코드는 이를 직접 호출하지 않습니다 — `Vfs::open`과 `VfsNode` 메서드를 거칩니다.

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

### VfsNode — 열린 파일 핸들 {#vfsnode-open-file-handle}

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

### Vfs 전역 {#vfs-global}

드라이버는 `mount`([`FileSystem`](#fs-traits))나 `mount_ro`(모든 [`FsRead`](#fs-traits))로 마운트하며, 컨텍스트를 참조로 넘깁니다. open/unlink/mkdir도 같은 전역 `vfs_root`를 거칩니다:

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

상수:

| 이름 | 값 | 의미 |
|------|-------|---------|
| `VFS_MAX_MOUNTS` | 8 | 마운트 테이블 크기 |
| `VFS_MAX_PATH` / `VFS_MAX_NAME` | 256 / 64 | 경로와 이름 길이 한도 |
| `VFS_TYPE_FILE` / `VFS_TYPE_DIR` | 0 / 1 | 노드 타입 |
| `VFS_O_READ`, `VFS_O_WRITE`, `VFS_O_CREATE`, `VFS_O_TRUNC` | 0x01, 0x02, 0x04, 0x08 | 열기 플래그 |
| `VFS_SEEK_SET`, `VFS_SEEK_CUR`, `VFS_SEEK_END` | 0, 1, 2 | 탐색 기준 |

## FAT32 드라이버 {#fat32-driver}

```c
#include <std/fs/fat.h>
```

8.3 파일명을 지원하는 읽기 전용 FAT32 드라이버입니다. `FatBpb`는 원시 온디스크 BIOS 파라미터 블록(FAT32가 정의하는 모든 필드)이며 — 직접 읽도록 만들어지지 않았습니다; `FatCtx` 자신의 `fat_lba`/`data_lba`/`root_cluster`/`bytes_per_cluster` 필드가 호출자가 실제로 필요로 하는, 이미 유도된 값입니다:

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

`FatCtx`는 [`FsRead`](#fs-traits)입니다: **open**(루트 클러스터부터의 8.3 경로 탐색, 이름은 대소문자를 구분하지 않고 일치), **read**(클러스터 체인 순회), **readdir**. 쓰기 메서드가 없으므로 `mount_ro`로 마운트합니다. `tests/stdlib/tests/test_fs.sc`는 `mkfs.vfat`으로 만든 FAT32 이미지를 읽습니다(도구가 없으면 건너뜁니다).

---

한도: 디렉터리 탐색은 최대 `FAT_MAX_DEPTH`(8)단계까지이며, `FatCtx::init`은 `FAT_MAX_CLUSTER`(4096바이트)보다 큰 클러스터를 거부합니다.

## ext2 드라이버 {#ext2-driver}

```c
#include <std/fs/ext.h>
```

읽기 전용 ext2(second extended filesystem) 드라이버입니다. 직접 블록(4 KiB 블록 기준 파일 크기 48 KB 이하)을 지원합니다. 간접 블록은 지원하지 않습니다.

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

`Ext2Ctx`는 [`FsRead`](#fs-traits)입니다: **open**(경로 토큰화, 루트 아이노드 2부터 탐색), **read**(직접 블록만 — 4 KiB 블록 기준 48 KB를 초과하는 파일은 이 드라이버가 갖고 있지 않은 간접 블록 지원이 필요합니다), **readdir**. 읽기 전용이므로 `mount_ro`로 마운트합니다. `test_fs.sc`는 `mke2fs -d`로 만든 ext2 이미지를 읽습니다(도구가 없으면 건너뜁니다).

---

상수: `EXT2_SUPER_MAGIC`(0xEF53), `EXT2_ROOT_INODE`(2), `EXT2_BLOCK_SIZE_BASE`(1024); `Ext2Ctx::init`은 `EXT2_MAX_BLOCK_SIZE`(4096)보다 큰 블록을 거부합니다. 아이노드 모드 비트 `EXT2_S_IFREG`(0x8000)와 `EXT2_S_IFDIR`(0x4000)로 파일과 디렉터리를 구분합니다.

## tmpfs — 인메모리 파일시스템 {#tmpfs-in-memory-filesystem}

```c
#include <std/fs/tmpfs.h>
```

완전히 메모리에서 동작하는 파일시스템입니다. 블록 장치가 필요 없습니다. 32개의 아이노드와 64 KiB의 데이터를 지원합니다. `/tmp`, 설정 스테이징, 테스트 픽스처 용도에 적합합니다.

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

`TmpfsCtx`는 완전한 [`FileSystem`](#fs-traits)입니다: **open**, **read**, **write**, **unlink**, **mkdir**, **readdir**. `mount_ro`로 읽기 전용 마운트도 할 수 있습니다.

---

## 마운트와 읽기 예제 {#mount-and-read-example}

tmpfs를 마운트하고 실제 쓰기/읽기 왕복을 수행합니다 — 실제 컴파일/실행으로 검증됨:

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

실제 블록 장치를 마운트하는 것도 동일한 형태를 따릅니다 — 드라이버를 `BlockDevice`로 감싸고, 파티션 테이블을 파싱하고, 파티션의 시작 LBA로부터 파일시스템 컨텍스트(`FatCtx`/`Ext2Ctx`)를 `init()`한 다음, tmpfs와 나란히 `mount_ro()`합니다(위의 최신 시그니처들에 대해 타입 검사는 되었지만, SD 카드 하드웨어가 필요하므로 여기서 실행되지는 않았습니다):

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
