# Networking Stack

SafeC provides a complete embedded networking stack in `std/net/`. All modules work directly on a `PacketBuf` — a fixed-size byte buffer — with no heap allocation required.

```c
#include "net/net.h"  // master header: pulls in all modules below
```

## Layer Map

```
┌─────────────────────────────────┐
│  Application  (dns, dhcp)       │
├─────────────────────────────────┤
│  Transport    (tcp, udp)        │
├─────────────────────────────────┤
│  Network      (ipv4, ipv6)      │
├─────────────────────────────────┤
│  Link         (ethernet, arp)   │
├─────────────────────────────────┤
│  PacketBuf    (raw byte buffer) │
└─────────────────────────────────┘
```

---

## net-core

```c
#include "net/net_core.h"
```

**PacketBuf** — the fundamental packet container:

```c
struct PacketBuf {
    unsigned char data[NET_MTU];  // 1514 bytes
    unsigned long len;

    void* at(unsigned long off);  // pointer into data at offset
    void  reset();                 // zero the buffer and set len = 0
}
```

**NetIf** — an abstract network interface handle. It has no `rx`/`tx` `PacketBuf` fields of its own — the caller owns its own `PacketBuf`(s) and passes one to `tx()` explicitly:

```c
struct NetIf {
    unsigned char mac[6];      // hardware MAC address
    unsigned int  ip4;         // assigned IPv4, network byte order
    unsigned int  gateway;     // default gateway IPv4, network byte order
    unsigned int  netmask;     // subnet mask, network byte order

    void*         tx_fn;       // driver send callback, cast before calling --
                                // fn(void* iface_ctx, unsigned char* data, unsigned long len) -> int
    void*         iface_ctx;   // opaque driver context, passed as tx_fn's first argument

    // Transmit 'pkt'. Casts tx_fn and calls it as
    // tx_fn(iface_ctx, pkt.data, pkt.len). Returns 0 on success, -1 if tx_fn is unset.
    int  tx(&stack PacketBuf pkt);
}
```

::: warning `tx_fn` is `void*`, not a typed function pointer
Assigning to it and implementing a driver both go through an explicit cast —
`iface.tx_fn = (void*)my_driver_send;` to set it (inside `unsafe {}`), and
`tx()`'s own implementation casts it back to
`fn int(void*, unsigned char*, unsigned long)` before calling. There's no
`PacketBuf rx`/`PacketBuf tx` field to build a frame into directly — build
into your own local `struct PacketBuf pkt` (via `udp_frame`/`eth_build`/etc.,
below) and pass `&pkt` to `iface.tx(...)`, as the end-to-end example does.
:::

**Byte-order utilities:**

```c
unsigned short net_htons(unsigned short x);
unsigned int   net_htonl(unsigned int x);
unsigned short net_ntohs(unsigned short x);
unsigned int   net_ntohl(unsigned int x);
```

**Address helpers:**

```c
unsigned int net_ip4(unsigned char a, unsigned char b,
                     unsigned char c, unsigned char d);
void net_ip4_str(unsigned int ip, char* out);   // "192.168.1.1\0"
void net_mac_str(const unsigned char mac[6], char* out); // "aa:bb:cc:dd:ee:ff\0"
```

---

## ethernet

```c
#include "net/ethernet.h"
```

```c
#define ETH_TYPE_IPV4   0x0800
#define ETH_TYPE_ARP    0x0806
#define ETH_TYPE_IPV6   0x86DD
#define ETH_HDR_LEN     14   // 6 dst + 6 src + 2 ethertype

struct EthernetHdr {
    unsigned char  dst[6];
    unsigned char  src[6];
    unsigned short ethertype;   // host byte order after parse
};

// Parse Ethernet header from packet (returns 0 on success, -1 if too short).
int eth_parse(&stack PacketBuf pkt, &stack EthernetHdr hdr_out);

// Write Ethernet header into packet at offset 0; sets pkt.len to ETH_HDR_LEN.
void eth_build(&stack PacketBuf pkt, const unsigned char dst[6],
               const unsigned char src[6], unsigned short ethertype);
```

---

## arp

```c
#include "net/arp.h"
```

`ArpTable` holds up to 16 entries (`ARP_TABLE_SIZE`) — `entries[i].ip4 == 0` marks a slot empty; there's no separate `valid` flag or entry count field.

```c
#define ARP_TABLE_SIZE  16
#define ARP_OP_REQUEST  1
#define ARP_OP_REPLY    2

struct ArpEntry {
    unsigned int  ip4;      // IPv4 in network byte order (0 = empty)
    unsigned char mac[6];
};

struct ArpTable {
    struct ArpEntry entries[16];

    void  update(unsigned int ip4, const unsigned char mac[6]);
    int   lookup(unsigned int ip4, unsigned char mac_out[6]) const;  // 1 on hit
    void  evict(unsigned int ip4);
    void  clear();
};

// Build an ARP request/reply packet into pkt (starting after the Ethernet
// header -- pkt.len must already include ETH_HDR_LEN).
void arp_build_packet(&stack PacketBuf pkt,
                      unsigned short op,
                      const unsigned char sha[6], unsigned int spa,
                      const unsigned char tha[6], unsigned int tpa);

// Parse an ARP packet at pkt.data + offset. Fills op/sha/spa/tha/tpa on
// success (returns 0), -1 on bad length.
int  arp_parse_packet(&stack PacketBuf pkt, unsigned long offset,
                      &stack unsigned short op,
                      unsigned char sha[6], &stack unsigned int spa,
                      unsigned char tha[6], &stack unsigned int tpa);
```

---

## ipv4

```c
#include "net/ipv4.h"
```

```c
#define IPV4_HDR_LEN  20   // minimum, no options

#define IP_PROTO_ICMP  1
#define IP_PROTO_TCP   6
#define IP_PROTO_UDP   17

struct Ipv4Hdr {
    unsigned char  ihl;        // header length in 32-bit words (usually 5)
    unsigned char  dscp;
    unsigned short total_len;  // host byte order
    unsigned short id;
    unsigned short frag_off;   // host byte order (includes flags)
    unsigned char  ttl;
    unsigned char  proto;
    unsigned short checksum;   // 0 after parse (not verified)
    unsigned int   src;        // network byte order
    unsigned int   dst;        // network byte order
};

unsigned short ip_checksum(const unsigned char* data, unsigned long len);

// Parse IPv4 header from pkt at byte offset 'offset'.
int  ipv4_parse(&stack PacketBuf pkt, unsigned long offset, &stack Ipv4Hdr hdr_out);

// Write IPv4 header at 'offset'; computes/fills the checksum.
// Returns byte offset of the first payload byte.
unsigned long  ipv4_build(&stack PacketBuf pkt, unsigned long offset,
                          unsigned char proto, unsigned int src, unsigned int dst,
                          unsigned short payload_len);
```

---

## ipv6

```c
#include "net/ipv6.h"
```

```c
#define IPV6_HDR_LEN   40   // fixed header length
#define IPV6_ADDR_LEN  16   // bytes in an IPv6 address

struct Ipv6Addr {
    unsigned char bytes[16];
};

struct Ipv6Hdr {
    unsigned int   ver_tc_fl;   // version(4) + traffic class(8) + flow label(20)
    unsigned short payload_len; // length of payload (after the fixed header)
    unsigned char  next_hdr;    // same numbering as IPv4's proto field
    unsigned char  hop_limit;
    struct Ipv6Addr src;
    struct Ipv6Addr dst;
};

// Address utilities -- all take addresses by '&stack Ipv6Addr' reference.
int  ipv6_addr_eq(const &stack Ipv6Addr a, const &stack Ipv6Addr b);
int  ipv6_addr_is_unspecified(const &stack Ipv6Addr a);
int  ipv6_addr_is_loopback(const &stack Ipv6Addr a);       // ::1
int  ipv6_addr_is_link_local(const &stack Ipv6Addr a);     // fe80::/10
void ipv6_addr_str(const &stack Ipv6Addr addr, char* buf); // 40-byte buf

// Parse IPv6 header from pkt at byte offset 'offset'.
int  ipv6_parse(&stack PacketBuf pkt, unsigned long offset, &stack Ipv6Hdr hdr_out);

// Write IPv6 header at 'offset'. Returns byte offset of the first payload byte.
unsigned long ipv6_build(&stack PacketBuf pkt, unsigned long offset,
                          unsigned char next_hdr, unsigned char hop_limit,
                          const &stack Ipv6Addr src, const &stack Ipv6Addr dst,
                          unsigned short payload_len);

// Full frame: Ethernet + IPv6 header, caller writes the payload at the
// returned offset. Returns byte offset of the first payload byte.
unsigned long ipv6_frame(&stack PacketBuf pkt,
                          const unsigned char eth_src[6],
                          const unsigned char eth_dst[6],
                          unsigned char next_hdr,
                          const &stack Ipv6Addr src,
                          const &stack Ipv6Addr dst,
                          unsigned short payload_len);
```

---

## udp

```c
#include "net/udp.h"
```

```c
#define UDP_HDR_LEN  8

struct UdpHdr {
    unsigned short src_port;   // host byte order
    unsigned short dst_port;   // host byte order
    unsigned short length;     // total UDP datagram length (header + payload)
    unsigned short checksum;
};

// Parse UDP header from pkt at byte offset 'offset'.
int  udp_parse(&stack PacketBuf pkt, unsigned long offset, &stack UdpHdr hdr_out);

// Write UDP header at 'offset'; payload_len = bytes of data after the
// header (checksum is left 0 -- optional in IPv4). Returns byte offset of
// the first payload byte.
unsigned long udp_build(&stack PacketBuf pkt, unsigned long offset,
                        unsigned short src_port, unsigned short dst_port,
                        unsigned short payload_len);

// Full frame: Ethernet + IPv4 + UDP headers; pkt is reset first. Returns
// byte offset where the caller should write the payload -- see the
// End-to-End Example below for the full call/write/tx sequence.
unsigned long udp_frame(&stack PacketBuf pkt,
                        const unsigned char eth_src[6],
                        const unsigned char eth_dst[6],
                        unsigned int ip_src, unsigned int ip_dst,
                        unsigned short src_port, unsigned short dst_port,
                        unsigned short payload_len);
```

---

## tcp

```c
#include "net/tcp.h"
```

`TcpConn` implements the full 11-state RFC 793 TCP state machine. It has no `iface`/`NetIf` field — `build_segment`/`recv` each take the MAC addresses or packet offset they need directly, per-call, the same "caller owns the `PacketBuf`" shape as `NetIf::tx()` above:

```c
// Connection states
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
    unsigned int   snd_nxt;         // next sequence number to send
    unsigned int   snd_una;         // oldest unacknowledged sequence number
    unsigned int   rcv_nxt;         // next expected receive sequence number
    unsigned int   rcv_wnd;         // receive window advertised
    unsigned char  rx_buf[2048];
    unsigned long  rx_len;
    unsigned char  tx_buf[2048];
    unsigned long  tx_len;

    // Feed a received packet (starting at TCP header offset in pkt).
    // Returns 1 if state changed, 0 otherwise.
    int  recv(&stack PacketBuf pkt, unsigned long tcp_offset);

    // Enqueue payload data to send. Returns bytes accepted (0 if full).
    unsigned long send(const unsigned char* data, unsigned long len);

    // Build next outgoing segment into pkt; returns 1 if a segment was produced.
    int  build_segment(&stack PacketBuf pkt,
                       const unsigned char eth_src[6],
                       const unsigned char eth_dst[6]);

    int  rx_ready() const;   // is there received data waiting?

    // Consume up to 'len' bytes from rx_buf into 'out'; returns bytes copied.
    unsigned long read(unsigned char* out, unsigned long len);

    // Initiate active open (send SYN).
    void connect(unsigned int remote_ip, unsigned short remote_port,
                 unsigned int local_ip, unsigned short local_port,
                 unsigned int isn);

    void close();   // send FIN
}

// Parse TCP header from pkt at byte offset 'offset'.
int tcp_parse(&stack PacketBuf pkt, unsigned long offset, &stack TcpHdr hdr_out);

unsigned short tcp_checksum(unsigned int src_ip, unsigned int dst_ip,
                             void* segment, unsigned long len);
```

---

## dns

```c
#include "net/dns.h"
```

```c
#define DNS_PORT       53
#define DNS_MAX_NAME  255
#define DNS_MAX_MSG   512

// Build a DNS A-record query for 'name' into pkt. 'txid' is caller-chosen
// (not generated internally) -- match it against the reply yourself.
unsigned short dns_query(&stack PacketBuf pkt,
                         const unsigned char eth_src[6],
                         const unsigned char eth_dst[6],
                         unsigned int ip_src, unsigned int ip_dns,
                         unsigned short src_port,
                         const char* name,
                         unsigned short txid);

// Parse a DNS reply in pkt (starting at the UDP payload offset). On
// success fills ip4_out with the first A record and returns 1; returns 0
// on a parse error, no A record, or a txid that doesn't match expected_txid.
int dns_parse_reply(&stack PacketBuf pkt, unsigned long udp_payload_offset,
                    unsigned short expected_txid,
                    &stack unsigned int ip4_out);
```

---

## dhcp

```c
#include "net/dhcp.h"
```

```c
#define DHCP_SERVER_PORT  67
#define DHCP_CLIENT_PORT  68

// DHCP client state machine
#define DHCP_STATE_IDLE       0
#define DHCP_STATE_SELECTING  1
#define DHCP_STATE_REQUESTING 2
#define DHCP_STATE_BOUND      3

struct DhcpLease {
    unsigned int  your_ip;      // offered/assigned IPv4 (network order)
    unsigned int  server_ip;    // DHCP server IPv4 (network order)
    unsigned int  gateway;      // default gateway (network order)
    unsigned int  subnet_mask;  // subnet mask (network order)
    unsigned int  dns;          // DNS server (network order)
    unsigned int  lease_secs;   // lease duration in seconds
};

struct DhcpClient {
    int              state;
    unsigned int     xid;         // transaction ID
    struct DhcpLease lease;
    unsigned char    mac[6];

    // Build a DHCPDISCOVER packet.
    void discover(&stack PacketBuf pkt, const unsigned char eth_dst[6]);

    // Build a DHCPREQUEST for 'offered_ip' from 'server_ip'.
    void request(&stack PacketBuf pkt, const unsigned char eth_dst[6],
                 unsigned int offered_ip, unsigned int server_ip);

    // Parse an incoming DHCP reply; fills lease on ACK/OFFER. Returns
    // DHCP_OFFER, DHCP_ACK, DHCP_NAK, or 0 on unrecognised.
    int  parse_reply(&stack PacketBuf pkt, unsigned long udp_payload_offset);

    int  is_bound() const;   // 1 if the client has a valid lease
}
```

::: warning No `NetIf* iface` field, no zero-argument `discover()`/`request()`
`DhcpClient` doesn't hold a reference to a `NetIf` — like `TcpConn`, it
builds into a caller-supplied `&stack PacketBuf`, and `discover()`/
`request()` take the destination MAC (and, for `request()`, the offered/
server IPs) as explicit arguments rather than reading them from a stored
interface. Transmit the packet the same way the End-to-End Example does:
build it, then call `iface.tx(&pkt)` yourself.
:::

---

## End-to-End Example

`udp_frame` builds the Ethernet + IPv4 + UDP headers into a `PacketBuf` and
returns the byte offset where the payload belongs (it also pre-sets
`pkt.len` to cover the payload region) — write your payload bytes there
yourself, then hand the packet to `iface.tx(...)`. Verified against a real
compile/run:

```c
#include <std/net/net_core.sc>
#include <std/net/ethernet.sc>
#include <std/net/ipv4.sc>
#include <std/net/udp.sc>

// A real driver would hand these bytes to hardware/a socket; this one just
// reports how many bytes it was asked to send.
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
    iface.iface_ctx = (void*)0;   // no driver context needed here

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

    int rc = iface.tx(&pkt);   // transmit via driver
    printf("tx rc=%d\n", rc);
    // driver: sending 47 bytes  (14 eth + 20 ipv4 + 8 udp + 5 payload)
    // tx rc=0
    return 0;
}
```
