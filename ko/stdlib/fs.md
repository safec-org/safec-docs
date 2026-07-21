# 파일시스템 스택

SafeC는 `std/fs/`에 계층화된 파일시스템 스택을 제공합니다 — 원시 블록 장치부터 FAT32, ext2, 인메모리(tmpfs) 드라이버를 갖춘 VFS 추상화까지 아우릅니다.

```c
#include "fs/fs.h"  // 마스터 헤더: 모든 모듈의 타입/시그니처를 가져온다
```

::: warning `fs.h`는 타입/시그니처만 포함합니다
다른 일부 std 모듈과 달리, 모든 모듈의 구현을 하나로 묶은 단일 `fs.sc`는 존재하지
않습니다 — 아래 예제들이 하는 것과 마찬가지로, `fs.h`와 함께 실제로 필요한 개별
`.sc` 파일(`block.sc`, `partition.sc`, `vfs.sc`, `fat.sc`, `ext.sc`, `tmpfs.sc`)을
직접 include하세요.
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
#include "fs/block.h"
```

모든 블록 저장 장치(SD 카드, 플래시, RAM 디스크 등)를 위한 드라이버 인터페이스입니다:

```c
struct BlockDevice {
    // 함수 포인터 필드 — 원시 C 포인터 (드라이버 인터페이스 경계)
    void*          read_fn;   // int(*)(void* ctx, lba, unsigned char* buf, count)
    void*          write_fn;  // int(*)(void* ctx, lba, const unsigned char* buf, count)
    void*          ctx;
    unsigned long  sector_count;
    unsigned long  sector_size;   // 보통 512

    int  read(unsigned long lba, &stack unsigned char buf, unsigned long count);
    int  write(unsigned long lba, const &stack unsigned char buf, unsigned long count);
    int  valid() const;  // read_fn != NULL 이고 sector_count > 0 이면 1
}
```

함수 포인터를 채워 넣는 방식으로 드라이버를 구현합니다. 함수 포인터 시그니처는 C FFI 콜백이기 때문에 원시 `unsigned char*`를 사용합니다 — 원시 타입에서 안전 타입으로의 경계는 `BlockDevice::read/write` 내부에서 `unsafe {}` 안의 명시적 캐스트를 통해 넘어갑니다:

```c
int my_sd_read(void* ctx, unsigned long lba,
               unsigned char* buf, unsigned long count) {
    // ... `lba`에서 `count` 섹터를 읽어 `buf`에 저장 ...
    return 0;  // 0 = 성공, 음수 = 오류
}

struct BlockDevice sd = {
    .read_fn      = (void*)my_sd_read,
    .write_fn     = (void*)my_sd_write,
    .ctx          = (void*)0,
    .sector_count = 8*1024*1024 / 512,
    .sector_size  = 512
};

// 호출자는 안전 참조를 사용한다 — unsafe 캐스트는 메서드 내부에서 일어난다:
unsigned char sector[512];
sd.read(0, sector, 1);
```

---

## 파티션 테이블 (MBR) {#partition-table-mbr}

```c
#include "fs/partition.h"
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
int partition_read(&stack BlockDevice dev, &stack PartTable table_out);
```

`get()`은 널 가능(nullable) 참조를 반환합니다 — 필드로 곧장 체이닝하지 말고 `match`(또는 `.is_null()`/`.default()`)로 언래핑하세요:

```c
unsigned long lba = 0;
match (parts.get(0)) {
    case null:    break;
    case some(e): lba = e.lba_start;
}
```

---

## VFS {#vfs}

```c
#include "fs/vfs.h"
```

VFS 계층은 최장 접두사 매칭(longest-prefix matching)을 사용해 경로를 등록된 파일시스템 드라이버로 라우팅합니다(예: `/mnt/sd`와 `/mnt`가 둘 다 마운트되어 있으면 `/mnt/sd/foo`는 `/mnt/sd`로 라우팅됩니다).

### VfsOps — 드라이버 인터페이스 {#vfsops-driver-interface}

각 필드는 `fn ReturnType(Params) name;` 형태의 함수 포인터 필드입니다(SafeC의 타입이 지정된 콜백 문법이며, C의 `int(*name)(...)` 선언자 형태가 아닙니다) — `ctx`는 항상 드라이버 자신의 불투명(opaque) 컨텍스트이며, 모든 호출에서 다시 전달됩니다. 드라이버 콜백은 원시 `unsigned char*`(FFI 경계)를 받습니다; `VfsNode` 자신의 메서드가 이를 안전 참조로/안전 참조에서 캐스트한 뒤 콜백으로 전달합니다:

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

### VfsNode — 열린 파일 핸들 {#vfsnode-open-file-handle}

```c
struct VfsNode {
    char          name[64];
    int           type;      // VFS_TYPE_FILE / VFS_TYPE_DIR
    unsigned long size;
    unsigned long inode;
    void*         fs_ctx;    // 소유 VfsMount로 돌아가는 불투명 포인터

    // 안전 참조 메서드 — VfsOps로 전달할 때 unsafe{} 안에서 원시 타입으로 캐스트
    unsigned long read(unsigned long offset, &stack unsigned char buf, unsigned long len);
    unsigned long write(unsigned long offset, const &stack unsigned char buf, unsigned long len);
    int           readdir(void* cb, void* user);
}
```

### Vfs 전역 인스턴스 {#vfs-global}

자유 함수(free function) 형태의 `vfs_mount`/`vfs_open` API는 없습니다 — 전역 `vfs_root` 인스턴스에 직접 메서드를 호출하세요:

```c
extern struct Vfs vfs_root;  // 전역 VFS 인스턴스
void vfs_init();             // 0으로 초기화; 어떤 마운트보다도 먼저 한 번 호출

struct Vfs {
    int  mount(const char* mountpoint, struct VfsOps ops, void* ctx);
    int  unmount(const char* mountpoint);
    int  open(const char* path, int flags, &stack VfsNode node_out);
    int  unlink(const char* path);
    int  mkdir(const char* path);
}
```

---

## FAT32 드라이버 {#fat32-driver}

```c
#include "fs/fat.h"
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
    int read_cluster(unsigned long cluster, unsigned char* buf);

    // FAT 체인을 따라가 `start_cluster`로부터 위치 `idx`에 있는 클러스터를 찾는다.
    unsigned long follow_fat(unsigned long start_cluster, unsigned long idx);
}

// `lba`에서 시작하는 블록 장치 파티션으로부터 FAT32 컨텍스트를 초기화한다.
int fat_init(&stack FatCtx ctx, &stack BlockDevice dev, unsigned long lba);

// FAT32 드라이버용 VfsOps — Vfs::mount에 전달되는 'ctx'는 FatCtx*여야 한다.
struct VfsOps fat_ops();
```

`fat_ops()`는 **open**(루트 클러스터부터의 8.3 경로 탐색), **read**(클러스터 체인 순회), **readdir**을 제공합니다. write와 unlink는 `-1`을 반환합니다(읽기 전용). 컴파일 검증만 되었습니다 — 실제 FAT32 볼륨을 마운트하고 읽으려면 실제 포맷된 디스크 이미지가 필요한데, 이 환경에서는 사용할 수 없습니다.

---

## ext2 드라이버 {#ext2-driver}

```c
#include "fs/ext.h"
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
    int  read_block(unsigned long block_no, unsigned char* buf);

    // 아이노드 `ino`를 `inode_out`으로 읽어들인다.
    int  read_inode(unsigned long ino, &stack Ext2Inode inode_out);

    // 아이노드 `ino`의 파일 데이터를 바이트 오프셋 `offset`부터 `len` 바이트만큼 `buf`로 읽는다.
    unsigned long read_file(unsigned long ino, unsigned long offset,
                             unsigned char* buf, unsigned long len);
}

// 블록 장치로부터 ext2 컨텍스트를 초기화한다 (장치의 LBA 0로부터
// 바이트 오프셋 1024에서 슈퍼블록을 읽음; EXT2_SUPER_MAGIC을 검증함).
int ext2_init(&stack Ext2Ctx ctx, &stack BlockDevice dev);

// ext2 드라이버용 VfsOps — Vfs::mount에 전달되는 'ctx'는 Ext2Ctx*여야 한다.
struct VfsOps ext2_ops();
```

`ext2_ops()`는 **open**(경로 토큰화, 루트 아이노드 2부터 탐색), **read**(직접 블록만 — 4 KiB 블록 기준 48 KB를 초과하는 파일은 이 드라이버가 갖고 있지 않은 간접 블록 지원이 필요합니다), **readdir**을 제공합니다. 읽기 전용입니다. 위의 FAT32와 마찬가지로 실제 디스크 이미지가 필요하다는 제약 아래 컴파일 검증만 되었습니다.

---

## tmpfs — 인메모리 파일시스템 {#tmpfs-in-memory-filesystem}

```c
#include "fs/tmpfs.h"
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
void tmpfs_init(&stack TmpfsCtx ctx);

// tmpfs 드라이버용 VfsOps — Vfs::mount에 전달되는 'ctx'는 TmpfsCtx*여야 한다.
struct VfsOps tmpfs_ops();
```

`tmpfs_ops()`는 완전한 **open**, **read**, **write**, **unlink**, **mkdir**, **readdir**을 제공합니다.

---

## 마운트 및 읽기 예제 {#mount-and-read-example}

tmpfs를 마운트하고 실제 쓰기/읽기 왕복을 수행합니다 — 실제 컴파일/실행으로 검증됨:

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

    // 쓰기
    struct VfsNode node;
    std::vfs_root.open("/tmp/log.txt", VFS_O_WRITE | VFS_O_CREATE, &node);
    unsigned char msg[8];
    msg[0]='b'; msg[1]='o'; msg[2]='o'; msg[3]='t';
    msg[4]=' '; msg[5]='o'; msg[6]='k'; msg[7]='\n';
    node.write(0, msg, 8UL);

    // 다시 읽기
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
#include "fs/fs.h"
#include "fs/block.sc"
#include "fs/partition.sc"
#include "fs/fat.sc"

// sd_read/sd_write는 보드 HAL이 제공한다고 가정
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
        // buf[0..n]을 처리
    }

    return 0;
}
```
