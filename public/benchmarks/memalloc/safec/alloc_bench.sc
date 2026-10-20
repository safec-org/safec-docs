// std::alloc / std::dealloc vs raw malloc / free (see benchmarks.md,
// "Memory allocation"). Two patterns, each run against both allocators:
//   interleaved: allocate one 48-byte block and free it, 20M times
//   batch:       allocate 1M 24-byte blocks, then free them all, 10 rounds
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long n);
extern void free(void* p);
#include <std/mem.h>
#include <std/time.h>

#define INTERLEAVED 20000000UL
#define BATCH 1000000UL
#define ROUNDS 10UL

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

// Every block's address is folded into this checksum, which main prints, so
// the optimizer can't pair up and delete an allocation and its free.
static unsigned long g_escape = 0UL;

static double interleaved(int useStd) {
    double t0 = now_ms();
    unsigned long sink = 0UL;
    unsigned long i = 0UL;
    while (i < INTERLEAVED) {
        unsafe {
            if (useStd) {
                ?&heap unsigned long p = std::alloc(48UL);
                unsigned long* q = (unsigned long*)p;
                *q = i;                        // touch it
                g_escape = g_escape ^ (unsigned long)q;
                sink = sink + *q;
                std::dealloc(p);
            } else {
                unsigned long* q = (unsigned long*)malloc(48UL);
                *q = i;
                g_escape = g_escape ^ (unsigned long)q;
                sink = sink + *q;
                free((void*)q);
            }
        }
        i = i + 1UL;
    }
    double t = now_ms() - t0;
    if (sink == 1UL) unsafe { printf("."); }
    return t;
}

static double batch(int useStd) {
    void** ptrs;
    unsafe { ptrs = (void**)malloc(BATCH * sizeof(void*)); }
    double t0 = now_ms();
    unsigned long r = 0UL;
    while (r < ROUNDS) {
        unsigned long i = 0UL;
        while (i < BATCH) {
            unsafe {
                void* p;
                if (useStd) { ?&heap unsigned long blk = std::alloc(24UL); p = (void*)blk; }
                else p = malloc(24UL);
                *(unsigned long*)p = i;
                g_escape = g_escape ^ (unsigned long)p;
                ptrs[i] = p;
            }
            i = i + 1UL;
        }
        i = 0UL;
        while (i < BATCH) {
            unsafe {
                if (useStd) std::dealloc((?&heap unsigned long)ptrs[i]);
                else free(ptrs[i]);
            }
            i = i + 1UL;
        }
        r = r + 1UL;
    }
    double t = now_ms() - t0;
    unsafe { free((void*)ptrs); }
    return t;
}

int main() {
    // warm both allocators' caches once
    interleaved(1); interleaved(0); batch(1); batch(0);
    double si = interleaved(1);
    double mi = interleaved(0);
    double sb = batch(1);
    double mb = batch(0);
    unsafe {
        printf("interleaved std_ns_per_pair=%.2f malloc_ns_per_pair=%.2f\n",
               si * 1e6 / (double)INTERLEAVED, mi * 1e6 / (double)INTERLEAVED);
        printf("batch std_ms=%.2f malloc_ms=%.2f\n", sb, mb);
        printf("(checksum %lu)\n", g_escape);
    }
    return 0;
}
