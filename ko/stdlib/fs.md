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

---

## BlockDevice {#blockdevice}

```c
#include <std/fs/block.h>
```

모든 블록 저장 장치(SD 카드, 플래시, RAM 디스크, …)를 위한 드라이버 인터페이스입니다. `block_device_new`로 만들며, 이 함수는 드라이버 컨텍스트와 그에 대해 타입이 지정된 두 콜백을 받습니다. 장치는 이들을 타입 소거된 형태로 저장하지만(하나의 VFS가 서로 다른 드라이버의 장치를 마운트할 수 있도록), 이를 설정하는 유일한 방법이 `block_device_new`이므로 짝이 맞는지는 컴파일 타임에 검사됩니다.

```c
struct BlockDevice {
    unsigned long  sector_count;
    unsigned long  sector_size;   // typically 512

    int  read(unsigned long lba, &unsigned char buf, unsigned long count);         // 0 on success
    int  write(unsigned long lba, const &unsigned char buf, unsigned long count);
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
    std::safe_memcpy(buf, &d.bytes[lba * 512UL], count * 512UL);
    return 0;
}

static int ram_write(&RamDisk d, unsigned long lba, const &unsigned char buf, unsigned long count) {
    if ((lba + count) * 512UL > 64UL * 512UL) return -1;
    std::safe_memcpy(&d.bytes[lba * 512UL], buf, count * 512UL);
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

`BLOCK_SIZE`는 섹터당 512바이트입니다.

## 파티션 테이블 (MBR) {#partition-table-mbr}

```c
#include <std/fs/partition.h>
```

고전적인 4개 항목 MBR 파티션 테이블을 파싱합니다:

```c
struct PartEntry {
    unsigned char  status;       // 0x80 = 부팅 가능
    unsigned char  type;         // 파티션 타입 바이트
    unsigned long  lba_start;
    unsigned long  sector_count;
}

struct PartTable {
    PartEntry entries[4];
    int       count;             // 비어 있지 않은 항목 수

    // entries[]에 대한 참조, 또는 idx가 범위를 벗어나면 빈(null) 참조 --
    // 파티션이 기록되지 않은 유효한 인덱스도 있을 수 있으므로
    // entry.type != PART_TYPE_EMPTY 도 함께 확인할 것.
    const ?&PartEntry get(int idx) const;
}

// LBA 0에서 MBR을 읽는다; 0x55/0xAA 부트 시그니처를 검증한다
int partition_read(&BlockDevice dev, &PartTable table_out);
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

VFS 계층은 최장 접두사 매칭(longest-prefix matching)을 사용해 경로를 등록된 파일시스템 드라이버로 라우팅합니다(예: `/mnt/sd`와 `/mnt`가 둘 다 마운트되어 있으면 `/mnt/sd/foo`는 `/mnt/sd`로 라우팅됩니다).

### VfsOps — 드라이버 인터페이스 {#vfsops-driver-interface}

파일시스템 드라이버가 채우는 테이블입니다(`tmpfs_ops()`, `fat_ops()`, `ext2_ops()`는 미리 만들어진 테이블을 반환합니다). 이는 드라이버 내부용입니다: `ctx`는 해당 드라이버 자신의 컨텍스트이며, 하나의 `Vfs`가 서로 다른 컨텍스트 타입의 드라이버를 마운트하므로 타입 소거된 형태로 저장됩니다. 애플리케이션 코드는 이를 직접 호출하지 않습니다 — `vfs_mount`, `Vfs::open`, `VfsNode` 메서드를 거칩니다.

```c
struct VfsOps {
    fn int(?&void ctx, unsigned long size) mount;
    fn int(?&void ctx) unmount;
    fn int(?&void ctx, const &char path, int flags, &VfsNode node_out) open;
    fn int(?&void ctx, const &char path) unlink;
    fn int(?&void ctx, const &char path) mkdir;
    fn unsigned long(?&void ctx, unsigned long inode, unsigned long off,
                      &unsigned char buf, unsigned long len) read;
    fn unsigned long(?&void ctx, unsigned long inode, unsigned long off,
                      const &unsigned char buf, unsigned long len) write;
    fn int(?&void ctx, unsigned long inode,
           fn void(&VfsNode, ?&void) cb, ?&void user) readdir;
}
```

### VfsNode — 열린 파일 핸들 {#vfsnode-open-file-handle}

```c
struct VfsNode {
    char          name[64];
    int           type;      // VFS_TYPE_FILE / VFS_TYPE_DIR
    unsigned long size;
    unsigned long inode;
    ?&void        fs_ctx;    // driver-internal: the owning VfsMount

    unsigned long read(unsigned long offset, &unsigned char buf, unsigned long len);
    unsigned long write(unsigned long offset, const &unsigned char buf, unsigned long len);
}

// Calls cb(entry, user) for each child of directory `dir`; returns the count, -1 on error.
generic<T> int vfs_readdir(&VfsNode dir, fn void(&VfsNode, &T) cb, &T user);
```

### Vfs 전역 인스턴스 {#vfs-global}

`vfs_mount`로 마운트하며, 드라이버 컨텍스트를 타입이 지정된 참조로 넘깁니다. open/unlink/mkdir은 전역 `vfs_root`의 메서드입니다:

```c
extern struct Vfs vfs_root;  // global VFS instance
void vfs_init();             // zero it out; call once before any mount

// ctx is the context the driver's ops expect (TmpfsCtx, FatCtx, Ext2Ctx, ...)
generic<T> int vfs_mount(&Vfs v, const &char mountpoint, struct VfsOps ops, &T ctx);

struct Vfs {
    int  unmount(const &char mountpoint);
    int  open(const &char path, int flags, &VfsNode node_out);
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
    unsigned long  partition_lba;      // FAT32 파티션 시작 LBA
    struct FatBpb  bpb;                // 원시 BIOS 파라미터 블록
    unsigned long  fat_lba;            // FAT 영역의 LBA
    unsigned long  data_lba;           // 첫 데이터 클러스터의 LBA
    unsigned long  root_cluster;       // 루트 디렉터리의 클러스터
    unsigned long  bytes_per_cluster;

    // 클러스터를 `buf`로 읽어들인다 (bytes_per_cluster 바이트 이상이어야 함).
    int read_cluster(unsigned long cluster, &unsigned char buf);

    // FAT 체인을 따라가 `start_cluster`로부터 위치 `idx`에 있는 클러스터를 찾는다.
    unsigned long follow_fat(unsigned long start_cluster, unsigned long idx);
}

// `lba`에서 시작하는 블록 장치 파티션으로부터 FAT32 컨텍스트를 초기화한다.
int fat_init(&FatCtx ctx, &BlockDevice dev, unsigned long lba);

// FAT32 드라이버용 VfsOps — Vfs::mount에 전달되는 'ctx'는 FatCtx*여야 한다.
struct VfsOps fat_ops();
```

`fat_ops()`는 **open**(루트 클러스터부터의 8.3 경로 탐색), **read**(클러스터 체인 순회), **readdir**을 제공합니다. write와 unlink는 `-1`을 반환합니다(읽기 전용). 컴파일 검증만 되었습니다 — 실제 FAT32 볼륨을 마운트하고 읽으려면 실제 포맷된 디스크 이미지가 필요한데, 이 환경에서는 사용할 수 없습니다.

---

한도: 디렉터리 탐색은 최대 `FAT_MAX_DEPTH`(8)단계까지이며, `fat_init`은 `FAT_MAX_CLUSTER`(4096바이트)보다 큰 클러스터를 거부합니다.

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
    unsigned short magic;           // EXT2_SUPER_MAGIC (0xEF53) 이어야 함
    // ...호출자에게 필요 없는 타임스탬프/마운트 횟수/OS 필드들이 더 있음
}

struct Ext2Inode {
    unsigned short mode;
    unsigned int   size_lo;
    unsigned short links_count;
    unsigned int   blocks_count;  // 512바이트 블록 단위
    unsigned int   block[15];     // 직접 12개 + 간접 1개 + 이중 간접 1개 + 삼중 간접 1개
    // ...uid/gid/타임스탬프/플래그 필드가 더 있음
}

struct Ext2Ctx {
    struct BlockDevice dev;
    struct Ext2Super   super;
    unsigned long      block_size;
    unsigned long      inodes_per_group;
    unsigned long      blocks_per_group;
    unsigned long      group_count;

    // 블록 `block_no`를 `buf`로 읽어들인다 (block_size 바이트 이상이어야 함).
    int  read_block(unsigned long block_no, &unsigned char buf);

    // 아이노드 `ino`를 `inode_out`으로 읽어들인다.
    int  read_inode(unsigned long ino, &Ext2Inode inode_out);

    // 아이노드 `ino`의 파일 데이터를 바이트 오프셋 `offset`부터 `len` 바이트만큼 `buf`로 읽는다.
    unsigned long read_file(unsigned long ino, unsigned long offset,
                             &unsigned char buf, unsigned long len);
}

// 블록 장치로부터 ext2 컨텍스트를 초기화한다 (장치의 LBA 0로부터
// 바이트 오프셋 1024에서 슈퍼블록을 읽음; EXT2_SUPER_MAGIC을 검증함).
int ext2_init(&Ext2Ctx ctx, &BlockDevice dev);

// ext2 드라이버용 VfsOps — Vfs::mount에 전달되는 'ctx'는 Ext2Ctx*여야 한다.
struct VfsOps ext2_ops();
```

`ext2_ops()`는 **open**(경로 토큰화, 루트 아이노드 2부터 탐색), **read**(직접 블록만 — 4 KiB 블록 기준 48 KB를 초과하는 파일은 이 드라이버가 갖고 있지 않은 간접 블록 지원이 필요합니다), **readdir**을 제공합니다. 읽기 전용입니다. 위의 FAT32와 마찬가지로 실제 디스크 이미지가 필요하다는 제약 아래 컴파일 검증만 되었습니다.

---

상수: `EXT2_SUPER_MAGIC`(0xEF53), `EXT2_ROOT_INODE`(2), `EXT2_BLOCK_SIZE_BASE`(1024); `ext2_init`은 `EXT2_MAX_BLOCK_SIZE`(4096)보다 큰 블록을 거부합니다. 아이노드 모드 비트 `EXT2_S_IFREG`(0x8000)와 `EXT2_S_IFDIR`(0x4000)로 파일과 디렉터리를 구분합니다.

## tmpfs — 인메모리 파일시스템 {#tmpfs-in-memory-filesystem}

```c
#include <std/fs/tmpfs.h>
```

완전히 메모리에서 동작하는 파일시스템입니다. 블록 장치가 필요 없습니다. 32개의 아이노드와 64 KiB의 데이터를 지원합니다. `/tmp`, 설정 스테이징, 테스트 픽스처 용도에 적합합니다.

```c
struct TmpfsInode {
    char           name[64];
    int            type;            // VFS_TYPE_FILE / VFS_TYPE_DIR
    unsigned long  parent;          // 부모 아이노드 인덱스 (0 = 루트)
    unsigned long  data_off;        // 데이터 풀 내 오프셋 (파일 전용)
    unsigned long  size;
    int            used;
}

struct TmpfsCtx {
    TmpfsInode     inodes[32];      // TMPFS_MAX_FILES
    unsigned char  data[65536];     // TMPFS_MAX_DATA
    unsigned long  data_used;
    int            inode_count;
}

// tmpfs 컨텍스트를 초기화한다 (0으로 채움).
void tmpfs_init(&TmpfsCtx ctx);

// tmpfs 드라이버용 VfsOps — Vfs::mount에 전달되는 'ctx'는 TmpfsCtx*여야 한다.
struct VfsOps tmpfs_ops();
```

`tmpfs_ops()`는 완전한 **open**, **read**, **write**, **unlink**, **mkdir**, **readdir**을 제공합니다.

---

## 마운트 및 읽기 예제 {#mount-and-read-example}

tmpfs를 마운트하고 실제 쓰기/읽기 왕복을 수행합니다 — 실제 컴파일/실행으로 검증됨:

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

실제 블록 장치를 마운트하는 것도 동일한 형태를 따릅니다 — 파티션 테이블을 파싱하고, 파티션의 시작 LBA로부터 드라이버 컨텍스트를 `fat_init`/`ext2_init`한 다음, tmpfs와 나란히 그 `VfsOps`를 `mount()`합니다(위의 최신 시그니처들에 대해 타입 검사는 되었지만, 실제 포맷된 디스크 이미지가 필요하므로 여기서 실행되지는 않았습니다):

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
