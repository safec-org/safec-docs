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

    void* at(unsigned long off) const;  // pointer into data at offset
    void  reset();                       // set len = 0
}
```

**NetIf** — a network interface handle:

```c
struct NetIf {
    unsigned char  mac[6];
    unsigned int   ip4;
    unsigned int   gateway;
    unsigned int   netmask;
    PacketBuf      rx;
    PacketBuf      tx;
    int(*tx_fn)(struct NetIf*);  // driver send callback

    int tx();  // calls tx_fn
}
```

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
void net_mac_str(unsigned char* mac, char* out); // "aa:bb:cc:dd:ee:ff\0"
```

---

## ethernet

```c
#include "net/ethernet.h"
```

```c
#define ETH_HDR_LEN   14
#define ETH_TYPE_IP4  0x0800
#define ETH_TYPE_ARP  0x0806
#define ETH_TYPE_IP6  0x86DD

struct EthernetHdr {
    unsigned char  dst[6];
    unsigned char  src[6];
    unsigned short ethertype;
}

int  eth_parse(struct PacketBuf* pkt, struct EthernetHdr* out);
void eth_build(struct PacketBuf* pkt,
               unsigned char* dst, unsigned char* src,
               unsigned short ethertype);
```

---

## arp

```c
#include "net/arp.h"
```

`ArpTable` holds up to 16 entries with FIFO eviction when full.

```c
struct ArpEntry {
    unsigned int  ip;
    unsigned char mac[6];
    int           valid;
}

struct ArpTable {
    ArpEntry      entries[16];
    unsigned long count;

    void update(unsigned int ip, unsigned char* mac);
    int  lookup(unsigned int ip, unsigned char* mac_out) const;  // 1=found
    void evict();
    void clear();
}

// Build a 28-byte ARP payload (op=1 request, op=2 reply)
void arp_build_packet(struct PacketBuf* pkt,
                      unsigned char* src_mac, unsigned int src_ip,
                      unsigned char* tgt_mac, unsigned int tgt_ip,
                      unsigned short op);

// Parse ARP payload; returns 1 on success
int  arp_parse_packet(struct PacketBuf* pkt,
                      unsigned int* src_ip, unsigned char* src_mac);
```

---

## ipv4

```c
#include "net/ipv4.h"
```

```c
struct Ipv4Hdr {
    unsigned char  version_ihl;
    unsigned char  tos;
    unsigned short total_len;
    unsigned short id;
    unsigned short flags_frag;
    unsigned char  ttl;
    unsigned char  protocol;
    unsigned short checksum;
    unsigned int   src_ip;
    unsigned int   dst_ip;
}

unsigned short ip_checksum(void* data, unsigned long len);
int            ipv4_parse(struct PacketBuf* pkt, struct Ipv4Hdr* out);
unsigned long  ipv4_build(struct PacketBuf* pkt,
                           unsigned int src, unsigned int dst,
                           unsigned char proto, unsigned short payload_len);
// Returns byte offset of payload start after the IP header
```

---

## ipv6

```c
#include "net/ipv6.h"
```

```c
struct Ipv6Addr {
    unsigned char bytes[16];
}

struct Ipv6Hdr {
    unsigned int   ver_tc_fl;     // version=6 in top nibble
    unsigned short payload_len;
    unsigned char  next_hdr;
    unsigned char  hop_limit;
    Ipv6Addr       src;
    Ipv6Addr       dst;
}

// Address utilities
int  ipv6_addr_eq(Ipv6Addr a, Ipv6Addr b);
int  ipv6_addr_is_loopback(Ipv6Addr a);     // ::1
int  ipv6_addr_is_link_local(Ipv6Addr a);   // fe80::/10
void ipv6_addr_str(Ipv6Addr a, char* out);  // "2001:0db8:0000:..." (no compression)

// Packet building
int  ipv6_parse(struct PacketBuf* pkt, struct Ipv6Hdr* out);
void ipv6_build(struct PacketBuf* pkt,
                Ipv6Addr src, Ipv6Addr dst,
                unsigned char next_hdr, unsigned short payload_len);
void ipv6_frame(struct PacketBuf* pkt,
                unsigned char* dst_mac, unsigned char* src_mac,
                Ipv6Addr src, Ipv6Addr dst,
                unsigned char next_hdr, unsigned short payload_len);
// ipv6_frame writes Ethernet header (ethertype 0x86DD) + IPv6 header
```

---

## udp

```c
#include "net/udp.h"
```

```c
struct UdpHdr {
    unsigned short src_port;
    unsigned short dst_port;
    unsigned short length;
    unsigned short checksum;
}

int  udp_parse(struct PacketBuf* pkt, struct UdpHdr* out, unsigned long offset);
void udp_build(struct PacketBuf* pkt,
               unsigned short src_port, unsigned short dst_port,
               unsigned long offset, unsigned long payload_len);

// Convenience: reset pkt, write Ethernet + IPv4 + UDP headers
void udp_frame(struct PacketBuf* pkt,
               unsigned char* eth_src, unsigned char* eth_dst,
               unsigned int src_ip, unsigned short src_port,
               unsigned int dst_ip, unsigned short dst_port,
               const void* payload, unsigned long payload_len);
```

---

## tcp

```c
#include "net/tcp.h"
```

`TcpConn` implements the full 10-state RFC 793 TCP state machine.

```c
// Connection states
#define TCP_CLOSED      0
#define TCP_SYN_SENT    1
#define TCP_ESTABLISHED 2
#define TCP_FIN_WAIT1   3
#define TCP_FIN_WAIT2   4
#define TCP_CLOSE_WAIT  5
#define TCP_CLOSING     6
#define TCP_LAST_ACK    7
#define TCP_TIME_WAIT   8

struct TcpConn {
    int            state;
    unsigned short local_port;
    unsigned short remote_port;
    unsigned int   local_ip;
    unsigned int   remote_ip;
    unsigned int   seq;         // send next
    unsigned int   ack;         // receive next
    unsigned int   snd_nxt;
    unsigned int   snd_una;
    unsigned int   rcv_nxt;
    unsigned short rcv_wnd;
    unsigned char  rx_buf[2048];
    unsigned long  rx_len;
    unsigned char  tx_buf[2048];
    unsigned long  tx_len;
    struct NetIf*  iface;

    void recv(struct PacketBuf* pkt);   // state machine update
    int  send(const void* data, unsigned long len);  // enqueue to tx
    int  read(void* out, unsigned long max);          // dequeue from rx
    void connect();                                    // send SYN
    void close();                                      // send FIN
    void build_segment(unsigned char flags,
                       const void* payload, unsigned long len);
}

unsigned short tcp_checksum(unsigned int src_ip, unsigned int dst_ip,
                             void* segment, unsigned long len);
```

---

## dns

```c
#include "net/dns.h"
```

```c
// Build a DNS A-record query UDP frame
unsigned short dns_query(struct PacketBuf* pkt,
                          const char* hostname,
                          unsigned int dns_server_ip,
                          unsigned char* src_mac,
                          unsigned int src_ip);
// Returns the transaction ID used

// Parse a DNS reply; returns first A-record IP, or 0 on failure
// Handles label compression (0xC0 pointer bytes)
unsigned int dns_parse_reply(struct PacketBuf* pkt);
```

---

## dhcp

```c
#include "net/dhcp.h"
```

```c
struct DhcpLease {
    unsigned int offered_ip;
    unsigned int server_ip;
    unsigned int subnet_mask;
    unsigned int gateway;
    unsigned int dns_ip;
    unsigned int lease_time;
}

struct DhcpClient {
    int           state;  // DISCOVER/OFFER/REQUEST/BOUND
    DhcpLease     lease;
    struct NetIf* iface;

    void discover();                          // broadcast DHCPDISCOVER
    void request();                           // send DHCPREQUEST
    int  parse_reply(struct PacketBuf* pkt);  // process OFFER or ACK
    int  is_bound() const;                    // 1 if IP assigned
}
```

---

## End-to-End Example

```c
#include "net/net.h"
#include "io.h"

// Minimal UDP send example
int main() {
    struct NetIf iface;
    // Set up: iface.mac, iface.ip4, driver callback in iface.tx_fn
    // ...

    unsigned char dst_mac[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
    unsigned int  src_ip     = net_ip4(192, 168, 1, 10);
    unsigned int  dst_ip     = net_ip4(192, 168, 1, 255);
    unsigned char payload[]  = "hello";

    udp_frame(&iface.tx, iface.mac, dst_mac,
              src_ip, 1234, dst_ip, 5678,
              payload, 5);

    iface.tx();  // transmit via driver
    return 0;
}
```
