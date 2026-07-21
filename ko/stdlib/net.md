# 네트워킹 스택

SafeC는 `std/net/`에 완전한 임베디드 네트워킹 스택을 제공합니다. 모든 모듈은 힙 할당 없이 고정 크기 바이트 버퍼인 `PacketBuf` 위에서 직접 동작합니다.

```c
#include "net/net.h"  // 마스터 헤더: 아래의 모든 모듈을 가져온다
```

## 계층 지도 {#layer-map}

```
┌─────────────────────────────────┐
│  애플리케이션  (dns, dhcp)        │
├─────────────────────────────────┤
│  전송         (tcp, udp)         │
├─────────────────────────────────┤
│  네트워크      (ipv4, ipv6)      │
├─────────────────────────────────┤
│  링크          (ethernet, arp)   │
├─────────────────────────────────┤
│  PacketBuf    (원시 바이트 버퍼)  │
└─────────────────────────────────┘
```

---

## net-core {#net-core}

```c
#include "net/net_core.h"
```

**PacketBuf** — 기본 패킷 컨테이너:

```c
struct PacketBuf {
    unsigned char data[NET_MTU];  // 1514 바이트
    unsigned long len;

    void* at(unsigned long off);  // data 내 오프셋 위치를 가리키는 포인터
    void  reset();                 // 버퍼를 0으로 채우고 len = 0으로 설정
}
```

**NetIf** — 추상 네트워크 인터페이스 핸들입니다. 자체 `rx`/`tx` `PacketBuf` 필드를 갖지 않습니다 — 호출자가 자신의 `PacketBuf`를 소유하고 그것을 `tx()`에 명시적으로 전달합니다:

```c
struct NetIf {
    unsigned char mac[6];      // 하드웨어 MAC 주소
    unsigned int  ip4;         // 할당된 IPv4, 네트워크 바이트 순서
    unsigned int  gateway;     // 기본 게이트웨이 IPv4, 네트워크 바이트 순서
    unsigned int  netmask;     // 서브넷 마스크, 네트워크 바이트 순서

    void*         tx_fn;       // 드라이버 송신 콜백, 호출 전에 캐스트 --
                                // fn(void* iface_ctx, unsigned char* data, unsigned long len) -> int
    void*         iface_ctx;   // 불투명 드라이버 컨텍스트, tx_fn의 첫 번째 인자로 전달됨

    // 'pkt'를 전송한다. tx_fn을 캐스트한 뒤
    // tx_fn(iface_ctx, pkt.data, pkt.len)로 호출한다. 성공 시 0, tx_fn이 설정되지 않았으면 -1을 반환한다.
    int  tx(&stack PacketBuf pkt);
}
```

::: warning `tx_fn`은 타입이 지정된 함수 포인터가 아니라 `void*`입니다
값을 대입하는 것과 드라이버를 구현하는 것 둘 다 명시적 캐스트를 거칩니다 —
설정할 때는 `iface.tx_fn = (void*)my_driver_send;`(`unsafe {}` 안에서), `tx()`
자체의 구현은 호출 전에 이를 다시 `fn int(void*, unsigned char*, unsigned long)`로
캐스트합니다. 프레임을 직접 만들어 넣을 `PacketBuf rx`/`PacketBuf tx` 필드는
없습니다 — 아래의 예제가 하는 것처럼, `udp_frame`/`eth_build`/등을 통해 여러분
자신의 로컬 `struct PacketBuf pkt`에 프레임을 만들고 `&pkt`를 `iface.tx(...)`에
전달하세요.
:::

**바이트 순서 유틸리티:**

```c
unsigned short net_htons(unsigned short x);
unsigned int   net_htonl(unsigned int x);
unsigned short net_ntohs(unsigned short x);
unsigned int   net_ntohl(unsigned int x);
```

**주소 헬퍼:**

```c
unsigned int net_ip4(unsigned char a, unsigned char b,
                     unsigned char c, unsigned char d);
void net_ip4_str(unsigned int ip, char* out);   // "192.168.1.1\0"
void net_mac_str(const unsigned char mac[6], char* out); // "aa:bb:cc:dd:ee:ff\0"
```

---

## ethernet {#ethernet}

```c
#include "net/ethernet.h"
```

```c
#define ETH_TYPE_IPV4   0x0800
#define ETH_TYPE_ARP    0x0806
#define ETH_TYPE_IPV6   0x86DD
#define ETH_HDR_LEN     14   // dst 6바이트 + src 6바이트 + ethertype 2바이트

struct EthernetHdr {
    unsigned char  dst[6];
    unsigned char  src[6];
    unsigned short ethertype;   // 파싱 후에는 호스트 바이트 순서
};

// 패킷에서 Ethernet 헤더를 파싱한다 (성공 시 0, 너무 짧으면 -1을 반환).
int eth_parse(&stack PacketBuf pkt, &stack EthernetHdr hdr_out);

// 오프셋 0에 Ethernet 헤더를 기록한다; pkt.len을 ETH_HDR_LEN으로 설정한다.
void eth_build(&stack PacketBuf pkt, const unsigned char dst[6],
               const unsigned char src[6], unsigned short ethertype);
```

---

## arp {#arp}

```c
#include "net/arp.h"
```

`ArpTable`은 최대 16개 항목(`ARP_TABLE_SIZE`)을 담습니다 — `entries[i].ip4 == 0`은 슬롯이 비어 있음을 뜻합니다; 별도의 `valid` 플래그나 항목 개수 필드는 없습니다.

```c
#define ARP_TABLE_SIZE  16
#define ARP_OP_REQUEST  1
#define ARP_OP_REPLY    2

struct ArpEntry {
    unsigned int  ip4;      // 네트워크 바이트 순서의 IPv4 (0 = 비어 있음)
    unsigned char mac[6];
};

struct ArpTable {
    struct ArpEntry entries[16];

    void  update(unsigned int ip4, const unsigned char mac[6]);
    int   lookup(unsigned int ip4, unsigned char mac_out[6]) const;  // 적중 시 1
    void  evict(unsigned int ip4);
    void  clear();
};

// pkt에 ARP 요청/응답 패킷을 만든다 (Ethernet 헤더 이후부터 시작 --
// pkt.len은 이미 ETH_HDR_LEN을 포함하고 있어야 함).
void arp_build_packet(&stack PacketBuf pkt,
                      unsigned short op,
                      const unsigned char sha[6], unsigned int spa,
                      const unsigned char tha[6], unsigned int tpa);

// pkt.data + offset 위치의 ARP 패킷을 파싱한다. 성공하면 (0을 반환하며)
// op/sha/spa/tha/tpa를 채우고, 길이가 잘못됐으면 -1을 반환한다.
int  arp_parse_packet(&stack PacketBuf pkt, unsigned long offset,
                      &stack unsigned short op,
                      unsigned char sha[6], &stack unsigned int spa,
                      unsigned char tha[6], &stack unsigned int tpa);
```

---

## ipv4 {#ipv4}

```c
#include "net/ipv4.h"
```

```c
#define IPV4_HDR_LEN  20   // 최소 크기, 옵션 없음

#define IP_PROTO_ICMP  1
#define IP_PROTO_TCP   6
#define IP_PROTO_UDP   17

struct Ipv4Hdr {
    unsigned char  ihl;        // 32비트 워드 단위 헤더 길이 (보통 5)
    unsigned char  dscp;
    unsigned short total_len;  // 호스트 바이트 순서
    unsigned short id;
    unsigned short frag_off;   // 호스트 바이트 순서 (플래그 포함)
    unsigned char  ttl;
    unsigned char  proto;
    unsigned short checksum;   // 파싱 후에는 0 (검증되지 않음)
    unsigned int   src;        // 네트워크 바이트 순서
    unsigned int   dst;        // 네트워크 바이트 순서
};

unsigned short ip_checksum(const unsigned char* data, unsigned long len);

// pkt의 바이트 오프셋 'offset'에서 IPv4 헤더를 파싱한다.
int  ipv4_parse(&stack PacketBuf pkt, unsigned long offset, &stack Ipv4Hdr hdr_out);

// 'offset'에 IPv4 헤더를 기록한다; 체크섬을 계산해 채운다.
// 첫 페이로드 바이트의 바이트 오프셋을 반환한다.
unsigned long  ipv4_build(&stack PacketBuf pkt, unsigned long offset,
                          unsigned char proto, unsigned int src, unsigned int dst,
                          unsigned short payload_len);
```

---

## ipv6 {#ipv6}

```c
#include "net/ipv6.h"
```

```c
#define IPV6_HDR_LEN   40   // 고정 헤더 길이
#define IPV6_ADDR_LEN  16   // IPv6 주소의 바이트 수

struct Ipv6Addr {
    unsigned char bytes[16];
};

struct Ipv6Hdr {
    unsigned int   ver_tc_fl;   // 버전(4비트) + 트래픽 클래스(8비트) + 플로우 레이블(20비트)
    unsigned short payload_len; // 페이로드 길이 (고정 헤더 이후)
    unsigned char  next_hdr;    // IPv4의 proto 필드와 동일한 번호 체계
    unsigned char  hop_limit;
    struct Ipv6Addr src;
    struct Ipv6Addr dst;
};

// 주소 유틸리티 -- 모두 '&stack Ipv6Addr' 참조로 주소를 받는다.
int  ipv6_addr_eq(const &stack Ipv6Addr a, const &stack Ipv6Addr b);
int  ipv6_addr_is_unspecified(const &stack Ipv6Addr a);
int  ipv6_addr_is_loopback(const &stack Ipv6Addr a);       // ::1
int  ipv6_addr_is_link_local(const &stack Ipv6Addr a);     // fe80::/10
void ipv6_addr_str(const &stack Ipv6Addr addr, char* buf); // 40바이트 buf

// pkt의 바이트 오프셋 'offset'에서 IPv6 헤더를 파싱한다.
int  ipv6_parse(&stack PacketBuf pkt, unsigned long offset, &stack Ipv6Hdr hdr_out);

// 'offset'에 IPv6 헤더를 기록한다. 첫 페이로드 바이트의 바이트 오프셋을 반환한다.
unsigned long ipv6_build(&stack PacketBuf pkt, unsigned long offset,
                          unsigned char next_hdr, unsigned char hop_limit,
                          const &stack Ipv6Addr src, const &stack Ipv6Addr dst,
                          unsigned short payload_len);

// 전체 프레임: Ethernet + IPv6 헤더, 호출자는 반환된 오프셋에 페이로드를
// 기록한다. 첫 페이로드 바이트의 바이트 오프셋을 반환한다.
unsigned long ipv6_frame(&stack PacketBuf pkt,
                          const unsigned char eth_src[6],
                          const unsigned char eth_dst[6],
                          unsigned char next_hdr,
                          const &stack Ipv6Addr src,
                          const &stack Ipv6Addr dst,
                          unsigned short payload_len);
```

---

## udp {#udp}

```c
#include "net/udp.h"
```

```c
#define UDP_HDR_LEN  8

struct UdpHdr {
    unsigned short src_port;   // 호스트 바이트 순서
    unsigned short dst_port;   // 호스트 바이트 순서
    unsigned short length;     // UDP 데이터그램 전체 길이 (헤더 + 페이로드)
    unsigned short checksum;
};

// pkt의 바이트 오프셋 'offset'에서 UDP 헤더를 파싱한다.
int  udp_parse(&stack PacketBuf pkt, unsigned long offset, &stack UdpHdr hdr_out);

// 'offset'에 UDP 헤더를 기록한다; payload_len은 헤더 이후 데이터의
// 바이트 수다 (체크섬은 0으로 남겨둠 -- IPv4에서는 선택 사항). 첫 페이로드
// 바이트의 바이트 오프셋을 반환한다.
unsigned long udp_build(&stack PacketBuf pkt, unsigned long offset,
                        unsigned short src_port, unsigned short dst_port,
                        unsigned short payload_len);

// 전체 프레임: Ethernet + IPv4 + UDP 헤더; pkt는 먼저 리셋된다. 호출자가
// 페이로드를 기록해야 할 바이트 오프셋을 반환한다 -- 전체 호출/기록/tx
// 순서는 아래의 종단 간 예제를 참고하라.
unsigned long udp_frame(&stack PacketBuf pkt,
                        const unsigned char eth_src[6],
                        const unsigned char eth_dst[6],
                        unsigned int ip_src, unsigned int ip_dst,
                        unsigned short src_port, unsigned short dst_port,
                        unsigned short payload_len);
```

---

## tcp {#tcp}

```c
#include "net/tcp.h"
```

`TcpConn`은 RFC 793의 완전한 11개 상태 TCP 상태 기계를 구현합니다. 자체 `iface`/`NetIf` 필드는 갖지 않습니다 — `build_segment`/`recv`는 각각 필요한 MAC 주소나 패킷 오프셋을 호출마다 직접 받습니다. 위의 `NetIf::tx()`와 동일한 "호출자가 `PacketBuf`를 소유한다"는 형태입니다:

```c
// 연결 상태
#define TCP_CLOSED       0
#define TCP_LISTEN       1
#define TCP_SYN_SENT     2
#define TCP_SYN_RECEIVED 3
#define TCP_ESTABLISHED  4
#define TCP_FIN_WAIT1    5
#define TCP_FIN_WAIT2    6
#define TCP_CLOSE_WAIT   7
#define TCP_CLOSING      8
#define TCP_LAST_ACK     9
#define TCP_TIME_WAIT    10

struct TcpConn {
    int            state;
    unsigned int   local_ip;
    unsigned int   remote_ip;
    unsigned short local_port;
    unsigned short remote_port;
    unsigned int   snd_nxt;         // 다음에 보낼 시퀀스 번호
    unsigned int   snd_una;         // 아직 확인되지 않은 가장 오래된 시퀀스 번호
    unsigned int   rcv_nxt;         // 다음에 받을 것으로 기대되는 시퀀스 번호
    unsigned int   rcv_wnd;         // 광고된 수신 윈도우
    unsigned char  rx_buf[2048];
    unsigned long  rx_len;
    unsigned char  tx_buf[2048];
    unsigned long  tx_len;

    // 수신한 패킷을 처리한다 (pkt 안의 TCP 헤더 오프셋부터 시작).
    // 상태가 바뀌었으면 1, 아니면 0을 반환한다.
    int  recv(&stack PacketBuf pkt, unsigned long tcp_offset);

    // 보낼 페이로드 데이터를 큐에 넣는다. 받아들여진 바이트 수를 반환한다 (가득 찼으면 0).
    unsigned long send(const unsigned char* data, unsigned long len);

    // 다음 송신 세그먼트를 pkt에 만든다; 세그먼트가 만들어졌으면 1을 반환한다.
    int  build_segment(&stack PacketBuf pkt,
                       const unsigned char eth_src[6],
                       const unsigned char eth_dst[6]);

    int  rx_ready() const;   // 수신 대기 중인 데이터가 있는가?

    // rx_buf에서 최대 'len' 바이트를 'out'으로 소비한다; 복사된 바이트 수를 반환한다.
    unsigned long read(unsigned char* out, unsigned long len);

    // 능동 오픈을 시작한다 (SYN 전송).
    void connect(unsigned int remote_ip, unsigned short remote_port,
                 unsigned int local_ip, unsigned short local_port,
                 unsigned int isn);

    void close();   // FIN 전송
}

// pkt의 바이트 오프셋 'offset'에서 TCP 헤더를 파싱한다.
int tcp_parse(&stack PacketBuf pkt, unsigned long offset, &stack TcpHdr hdr_out);

unsigned short tcp_checksum(unsigned int src_ip, unsigned int dst_ip,
                             void* segment, unsigned long len);
```

---

## dns {#dns}

```c
#include "net/dns.h"
```

```c
#define DNS_PORT       53
#define DNS_MAX_NAME  255
#define DNS_MAX_MSG   512

// 'name'에 대한 DNS A 레코드 질의를 pkt에 만든다. 'txid'는 (내부에서
// 생성되는 것이 아니라) 호출자가 직접 선택한다 -- 응답과 직접 대조해서 확인하라.
unsigned short dns_query(&stack PacketBuf pkt,
                         const unsigned char eth_src[6],
                         const unsigned char eth_dst[6],
                         unsigned int ip_src, unsigned int ip_dns,
                         unsigned short src_port,
                         const char* name,
                         unsigned short txid);

// pkt(UDP 페이로드 오프셋에서 시작)의 DNS 응답을 파싱한다. 성공하면 첫
// A 레코드로 ip4_out을 채우고 1을 반환한다; 파싱 오류, A 레코드 없음,
// 또는 expected_txid와 일치하지 않는 txid인 경우 0을 반환한다.
int dns_parse_reply(&stack PacketBuf pkt, unsigned long udp_payload_offset,
                    unsigned short expected_txid,
                    &stack unsigned int ip4_out);
```

---

## dhcp {#dhcp}

```c
#include "net/dhcp.h"
```

```c
#define DHCP_SERVER_PORT  67
#define DHCP_CLIENT_PORT  68

// DHCP 클라이언트 상태 기계
#define DHCP_STATE_IDLE       0
#define DHCP_STATE_SELECTING  1
#define DHCP_STATE_REQUESTING 2
#define DHCP_STATE_BOUND      3

struct DhcpLease {
    unsigned int  your_ip;      // 제안/할당된 IPv4 (네트워크 순서)
    unsigned int  server_ip;    // DHCP 서버 IPv4 (네트워크 순서)
    unsigned int  gateway;      // 기본 게이트웨이 (네트워크 순서)
    unsigned int  subnet_mask;  // 서브넷 마스크 (네트워크 순서)
    unsigned int  dns;          // DNS 서버 (네트워크 순서)
    unsigned int  lease_secs;   // 임대 기간 (초)
};

struct DhcpClient {
    int              state;
    unsigned int     xid;         // 트랜잭션 ID
    struct DhcpLease lease;
    unsigned char    mac[6];

    // DHCPDISCOVER 패킷을 만든다.
    void discover(&stack PacketBuf pkt, const unsigned char eth_dst[6]);

    // 'server_ip'로부터 'offered_ip'에 대한 DHCPREQUEST를 만든다.
    void request(&stack PacketBuf pkt, const unsigned char eth_dst[6],
                 unsigned int offered_ip, unsigned int server_ip);

    // 들어오는 DHCP 응답을 파싱한다; ACK/OFFER면 lease를 채운다. 반환값은
    // DHCP_OFFER, DHCP_ACK, DHCP_NAK, 또는 인식하지 못하면 0이다.
    int  parse_reply(&stack PacketBuf pkt, unsigned long udp_payload_offset);

    int  is_bound() const;   // 클라이언트가 유효한 임대를 갖고 있으면 1
}
```

::: warning `NetIf* iface` 필드도, 인자 없는 `discover()`/`request()`도 없음
`DhcpClient`는 `NetIf`에 대한 참조를 갖고 있지 않습니다 — `TcpConn`처럼,
호출자가 제공한 `&stack PacketBuf`에 프레임을 만들며, `discover()`/
`request()`는 저장된 인터페이스에서 읽어오는 대신 대상 MAC(그리고
`request()`의 경우 제안받은 IP/서버 IP)을 명시적인 인자로 받습니다.
종단 간 예제와 같은 방식으로 패킷을 전송하세요 — 패킷을 만든 다음
직접 `iface.tx(&pkt)`를 호출하면 됩니다.
:::

---

## 종단 간 예제 {#end-to-end-example}

`udp_frame`은 `PacketBuf`에 Ethernet + IPv4 + UDP 헤더를 만들고 페이로드가
들어갈 바이트 오프셋을 반환합니다(페이로드 영역을 포괄하도록 `pkt.len`도
미리 설정합니다) — 직접 그 위치에 페이로드 바이트를 기록한 다음
`iface.tx(...)`에 패킷을 넘기세요. 실제 컴파일/실행으로 검증됨:

```c
#include <std/net/net_core.sc>
#include <std/net/ethernet.sc>
#include <std/net/ipv4.sc>
#include <std/net/udp.sc>

// 실제 드라이버라면 이 바이트들을 하드웨어나 소켓에 넘기겠지만, 여기서는
// 몇 바이트를 보내라고 요청받았는지만 알려준다.
int my_driver_send(void* ctx, unsigned char* data, unsigned long len) {
    printf("driver: sending %lu bytes\n", len);
    return 0;
}

int main() {
    struct NetIf iface;
    iface.mac[0] = 0xAA; iface.mac[1] = 0xBB; iface.mac[2] = 0xCC;
    iface.mac[3] = 0xDD; iface.mac[4] = 0xEE; iface.mac[5] = 0xFF;
    iface.ip4 = std::net_ip4(192, 168, 1, 10);
    unsafe { iface.tx_fn = (void*)my_driver_send; }
    iface.iface_ctx = (void*)0;   // 여기서는 드라이버 컨텍스트가 필요 없음

    unsigned char dst_mac[6];
    dst_mac[0] = 0xFF; dst_mac[1] = 0xFF; dst_mac[2] = 0xFF;
    dst_mac[3] = 0xFF; dst_mac[4] = 0xFF; dst_mac[5] = 0xFF;

    unsigned int  src_ip  = std::net_ip4(192, 168, 1, 10);
    unsigned int  dst_ip  = std::net_ip4(192, 168, 1, 255);
    unsigned char payload[5];
    payload[0] = 'h'; payload[1] = 'e'; payload[2] = 'l';
    payload[3] = 'l'; payload[4] = 'o';

    struct PacketBuf pkt;
    unsigned long offset = std::udp_frame(&pkt, iface.mac, dst_mac,
                                          src_ip, dst_ip,
                                          1234, 5678, 5);
    unsafe {
        unsigned char* d = (unsigned char*)pkt.at(offset);
        int i = 0;
        while (i < 5) { d[i] = payload[i]; i = i + 1; }
    }

    int rc = iface.tx(&pkt);   // 드라이버를 통해 전송
    printf("tx rc=%d\n", rc);
    // driver: sending 47 bytes  (eth 14 + ipv4 20 + udp 8 + payload 5)
    // tx rc=0
    return 0;
}
```
