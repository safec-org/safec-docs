// Gemma 4 E2B decode on ROCm (see gemma4_rocm.h).
#pragma once
#include "gemma4_rocm.h"
#include "gemma4_rocm_kernels.h"
#include <std/ml/gpu_rocm.h>
#include <std/ml/float16.h>
#include <std/math.h>

namespace std {

extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
extern int hiprtcCreateProgram(void** prog, const char* src, const char* name, int numHeaders,
                               const char** headers, const char** includeNames);
extern int hiprtcCompileProgram(void* prog, int numOptions, const char** options);
extern int hiprtcGetProgramLogSize(void* prog, unsigned long* size);
extern int hiprtcGetProgramLog(void* prog, char* log);
extern int hiprtcGetCodeSize(void* prog, unsigned long* size);
extern int hiprtcGetCode(void* prog, char* code);
extern int hiprtcDestroyProgram(void** prog);
extern int hipModuleLoadData(void** module, const void* image);
extern int hipModuleGetFunction(void** func, void* module, const char* name);
extern int hipModuleLaunchKernel(void* func, unsigned int gx, unsigned int gy, unsigned int gz,
                                 unsigned int bx, unsigned int by, unsigned int bz,
                                 unsigned int shmem, void* stream, void** params, void** extra);
extern int hipMemcpyAsync(void* dst, const void* src, unsigned long size, int kind, void* stream);
extern int hipStreamSynchronize(void* stream);
extern int hipStreamBeginCapture(void* stream, int mode);
extern int hipStreamEndCapture(void* stream, void** graph);
extern int hipGraphInstantiate(void** exec, void* graph, void* errNode, char* log, unsigned long logSize);
extern int hipGraphLaunch(void* exec, void* stream);
extern int hipGraphDestroy(void* graph);
extern int hipGraphExecDestroy(void* exec);

#define G4R_H2D 1
#define G4R_D2H 2
#define G4R_MAXL 35
#define G4R_ARGMAX_BLOCKS 1024UL

// Kernel table, in this order.
#define G4K_GEMV 0
#define G4K_GEMV_GEGLU 1
#define G4K_GEMV_GELUVEC 2
#define G4K_RESNORM 3
#define G4K_EMBED 4
#define G4K_QKV_POST 5
#define G4K_ATTN256 6
#define G4K_ATTN512 7
#define G4K_ATTN_COMBINE 8
#define G4K_PLE 9
#define G4K_ARGMAX_PART 10
#define G4K_ARGMAX_FINAL 11
#define G4K_ADVANCE 12
#define G4K_COUNT 13

static const char* g4r_kernel_name_(int k) {
    if (k == G4K_GEMV) return "g4_gemv";
    if (k == G4K_GEMV_GEGLU) return "g4_gemv_geglu";
    if (k == G4K_GEMV_GELUVEC) return "g4_gemv_gelu_vec";
    if (k == G4K_RESNORM) return "g4_resnorm";
    if (k == G4K_EMBED) return "g4_embed";
    if (k == G4K_QKV_POST) return "g4_qkv_post";
    if (k == G4K_ATTN256) return "g4_attn256";
    if (k == G4K_ATTN512) return "g4_attn512";
    if (k == G4K_ATTN_COMBINE) return "g4_attn_combine";
    if (k == G4K_PLE) return "g4_ple_combine";
    if (k == G4K_ARGMAX_PART) return "g4_argmax_part";
    if (k == G4K_ARGMAX_FINAL) return "g4_argmax_final";
    return "g4_advance";
}

// One layer's device weights (bf16) and, for layers that own a cache, its K/V.
struct G4RLayer {
    struct RocmHalfBuf inLn;
    struct RocmHalfBuf postAttnLn;
    struct RocmHalfBuf preFfLn;
    struct RocmHalfBuf postFfLn;
    struct RocmHalfBuf qkv;       // q|k|v fused, or q alone for kv-shared layers
    struct RocmHalfBuf qNorm;
    struct RocmHalfBuf kNorm;     // dev 0 for kv-shared layers
    struct RocmHalfBuf o;
    struct RocmHalfBuf gateUp;    // gate and up rows interleaved: gate_0, up_0, gate_1, ...
    struct RocmHalfBuf down;
    struct RocmHalfBuf pleGate;
    struct RocmHalfBuf pleProj;
    struct RocmHalfBuf postPleLn;
    struct RocmBuf kc;            // [maxSeq][hd] fp32; dev 0 for kv-shared layers
    struct RocmBuf vc;
    unsigned long hd;
    unsigned long qkvRows;
    unsigned long inter;
    unsigned long kvSrc;          // layer whose cache this layer reads
    int sliding;
    int shared;
    float scalar;
};

static int g_g4r_ready = 0;
static void* g_g4r_stream = (void*)0;
static void* g_g4r_fn[G4K_COUNT];
static struct G4RLayer g_g4r_l[G4R_MAXL];
static unsigned long g_g4r_nl = 0UL;
static unsigned long g_g4r_hidden = 0UL;
static unsigned long g_g4r_ple = 0UL;
static unsigned long g_g4r_vocab = 0UL;
static unsigned long g_g4r_maxSeq = 0UL;
static unsigned long g_g4r_window = 0UL;
static unsigned long g_g4r_nq = 0UL;
static unsigned long g_g4r_pos = 0UL;
static float g_g4r_eps = (float)1e-6;
static float g_g4r_softcap = (float)30.0;
static struct RocmHalfBuf g_g4r_embed;
static struct RocmHalfBuf g_g4r_embedPle;
static struct RocmHalfBuf g_g4r_pleModelProj;
static struct RocmHalfBuf g_g4r_pleProjNorm;
static struct RocmHalfBuf g_g4r_finalNorm;
// activations (fp32) and small int buffers (4-byte slots of a RocmBuf)
static struct RocmBuf g_g4r_x;       // residual stream
static struct RocmBuf g_g4r_h;
static struct RocmBuf g_g4r_qkv;
static struct RocmBuf g_g4r_attn;
static struct RocmBuf g_g4r_o;
static struct RocmBuf g_g4r_gu;
static struct RocmBuf g_g4r_g;
static struct RocmBuf g_g4r_f;
static struct RocmBuf g_g4r_pg;
static struct RocmBuf g_g4r_pg2;
static struct RocmBuf g_g4r_pp;
static struct RocmBuf g_g4r_pleAll;
static struct RocmBuf g_g4r_pleRaw;
static struct RocmBuf g_g4r_logits;
static struct RocmBuf g_g4r_part;
static struct RocmBuf g_g4r_amv;
static struct RocmBuf g_g4r_ami;
static struct RocmBuf g_g4r_tok;      // [1] next input token
static struct RocmBuf g_g4r_posb;     // [0] position, [1] count of generated tokens
static struct RocmBuf g_g4r_out;      // [maxSeq] generated tokens
static void* g_g4r_graph = (void*)0;
static void* g_g4r_exec = (void*)0;

// ── Uploads ──────────────────────────────────────────────────────────────────

static struct RocmHalfBuf g4r_up_(const &Gemma4Bf16Tensor t, &int ok) {
    struct RocmHalfBuf b = rocm_half_alloc(t.size, ROCM_BF16);
    if (b.dev == 0UL) { *ok = 0; return b; }
    unsafe {
        if (!rocm_half_write(b, (const &unsigned short)t.data, t.size)) *ok = 0;
    }
    return b;
}

// gate and up stacked row-interleaved (gate_0, up_0, gate_1, up_1, ...), so
// one wave computes a pair and writes gelu(gate) * up (g4_gemv_geglu).
static struct RocmHalfBuf g4r_up_interleaved_(const &Gemma4Bf16Tensor g, const &Gemma4Bf16Tensor u, &int ok) {
    unsigned long rows = g.shape0;
    unsigned long cols = g.shape1;
    struct RocmHalfBuf b = rocm_half_alloc(2UL * rows * cols, ROCM_BF16);
    if (b.dev == 0UL) { *ok = 0; return b; }
    unsafe {
        unsigned short* tmp = (unsigned short*)malloc(2UL * rows * cols * 2UL);
        if (tmp == (unsigned short*)0) { *ok = 0; return b; }
        unsigned long r = 0UL;
        while (r < rows) {
            unsigned long c = 0UL;
            while (c < cols) {
                tmp[(2UL * r) * cols + c] = g.data[r * cols + c];
                tmp[(2UL * r + 1UL) * cols + c] = u.data[r * cols + c];
                c = c + 1UL;
            }
            r = r + 1UL;
        }
        if (!rocm_half_write(b, (const &unsigned short)tmp, 2UL * rows * cols)) *ok = 0;
        rocm_sync();
        free((void*)tmp);
    }
    return b;
}

static struct RocmBuf g4r_buf_(unsigned long n, &int ok) {
    struct RocmBuf b = rocm_alloc(n);
    if (b.dev == 0UL) *ok = 0;
    return b;
}

// ── Kernel launches (queued on the std ROCm stream) ─────────────────────────

static int g4r_launch_(int k, unsigned long grid, unsigned long block, &void params) {
    unsafe {
        return hipModuleLaunchKernel(g_g4r_fn[k], (unsigned int)grid, 1U, 1U, (unsigned int)block, 1U, 1U, 0U,
                                     g_g4r_stream, (void**)params, (void**)0) == 0;
    }
}

static int g4r_gemv_(unsigned long w, unsigned long x, unsigned long y, unsigned long n, unsigned long k, float alpha) {
    unsafe {
        void* pw = (void*)w; void* px = (void*)x; void* py = (void*)y;
        int nn = (int)n; int kk = (int)k; float al = alpha;
        void* p[6];
        p[0] = (void*)&pw; p[1] = (void*)&px; p[2] = (void*)&py; p[3] = (void*)&nn; p[4] = (void*)&kk; p[5] = (void*)&al;
        return g4r_launch_(G4K_GEMV, (n + 3UL) / 4UL, 256UL, (&void)&p[0]);
    }
}

static int g4r_gemv_epi_(int kern, unsigned long w, unsigned long x, unsigned long y, unsigned long n, unsigned long k,
                         unsigned long ev) {
    unsafe {
        void* pw = (void*)w; void* px = (void*)x; void* py = (void*)y; int nn = (int)n; int kk = (int)k; void* pe = (void*)ev;
        void* p[6];
        p[0] = (void*)&pw; p[1] = (void*)&px; p[2] = (void*)&py; p[3] = (void*)&nn; p[4] = (void*)&kk; p[5] = (void*)&pe;
        return g4r_launch_(kern, (n + 3UL) / 4UL, 256UL, (&void)&p[0]);
    }
}

// Between sublayers: x = (x + rmsnorm(r) * wr) * scale, then
// h = rmsnorm(x) * wn (wn == 0: no h). One block of 512 threads.
static int g4r_resnorm_(unsigned long r, unsigned long wr, float scale, unsigned long wn) {
    unsafe {
        void* px = (void*)g_g4r_x.dev; void* pr = (void*)r; void* pwr = (void*)wr; float sc = scale; void* pwn = (void*)wn;
        void* ph = (void*)g_g4r_h.dev; int n = (int)g_g4r_hidden; float eps = g_g4r_eps;
        void* p[8];
        p[0] = (void*)&px; p[1] = (void*)&pr; p[2] = (void*)&pwr; p[3] = (void*)&sc; p[4] = (void*)&pwn;
        p[5] = (void*)&ph; p[6] = (void*)&n; p[7] = (void*)&eps;
        return g4r_launch_(G4K_RESNORM, 1UL, 512UL, (&void)&p[0]);   // 3 values per thread: hidden <= 1536
    }
}

// One whole decode step for the token in g_g4r_tok at position g_g4r_posb[0]:
// embeddings, 35 layers, LM head, argmax into g_g4r_tok, position += 1.
// Twelve kernels per layer: four GEMVs plus the gated-GELU GEMVs (the GELUs
// ride in their epilogues), three one-block residual/norm steps
// (g4r_resnorm_), q/k/v post-processing, and attention + merge.
static int g4r_step_() {
    int ok = 1;
    unsigned long H = g_g4r_hidden;
    unsigned long D = g_g4r_ple;
    unsigned long L = g_g4r_nl;
    struct G4RLayer l0;
    unsafe { l0 = g_g4r_l[0]; }
    unsafe {
        // x = E[tok] sqrt(H); h = rmsnorm(x) w_in(0)
        void* pE = (void*)g_g4r_embed.dev; void* pTok = (void*)g_g4r_tok.dev; void* pX = (void*)g_g4r_x.dev;
        float es = sqrt_f((float)H); int hn = (int)H; void* pwn = (void*)l0.inLn.dev; void* ph = (void*)g_g4r_h.dev;
        float eps = g_g4r_eps;
        void* p1[8];
        p1[0] = (void*)&pE; p1[1] = (void*)&pTok; p1[2] = (void*)&es; p1[3] = (void*)&pX; p1[4] = (void*)&hn;
        p1[5] = (void*)&pwn; p1[6] = (void*)&ph; p1[7] = (void*)&eps;
        ok = ok && g4r_launch_(G4K_EMBED, 1UL, 256UL, (&void)&p1[0]);
    }
    // per-layer inputs for all layers at once
    ok = ok && g4r_gemv_(g_g4r_pleModelProj.dev, g_g4r_x.dev, g_g4r_pleRaw.dev, L * D, H, (float)1.0);
    unsafe {
        void* pr = (void*)g_g4r_pleRaw.dev; void* pn = (void*)g_g4r_pleProjNorm.dev; void* pe = (void*)g_g4r_embedPle.dev;
        void* pt = (void*)g_g4r_tok.dev; void* pa = (void*)g_g4r_pleAll.dev;
        int dd = (int)D; int ll = (int)L; float ps = (float)1.0 / sqrt_f((float)H); float ts = sqrt_f((float)D);
        float eps = g_g4r_eps;
        void* p2[10];
        p2[0] = (void*)&pr; p2[1] = (void*)&pn; p2[2] = (void*)&pe; p2[3] = (void*)&pt; p2[4] = (void*)&pa;
        p2[5] = (void*)&dd; p2[6] = (void*)&ll; p2[7] = (void*)&ps; p2[8] = (void*)&ts; p2[9] = (void*)&eps;
        ok = ok && g4r_launch_(G4K_PLE, L, 256UL, (&void)&p2[0]);
    }
    unsigned long li = 0UL;
    while (li < L && ok) {
        struct G4RLayer ly;
        unsafe { ly = g_g4r_l[li]; }
        unsigned long hd = ly.hd;
        unsigned long nq = g_g4r_nq;
        // h = rmsnorm(x) w_in is ready (embedding or the previous layer's tail)
        ok = ok && g4r_gemv_(ly.qkv.dev, g_g4r_h.dev, g_g4r_qkv.dev, ly.qkvRows, H, (float)1.0);
        struct G4RLayer src;
        unsafe { src = g_g4r_l[ly.kvSrc]; }
        unsafe {
            void* pq = (void*)g_g4r_qkv.dev; void* pqn = (void*)ly.qNorm.dev; void* pkn = (void*)ly.kNorm.dev;
            void* pk = (void*)ly.kc.dev; void* pv = (void*)ly.vc.dev; void* pp = (void*)g_g4r_posb.dev;
            int h = (int)hd; int n = (int)nq; int full = ly.sliding ? 0 : 1; float eps = g_g4r_eps;
            void* p3[10];
            p3[0] = (void*)&pq; p3[1] = (void*)&pqn; p3[2] = (void*)&pkn; p3[3] = (void*)&pk; p3[4] = (void*)&pv;
            p3[5] = (void*)&pp; p3[6] = (void*)&h; p3[7] = (void*)&n; p3[8] = (void*)&full; p3[9] = (void*)&eps;
            ok = ok && g4r_launch_(G4K_QKV_POST, ly.shared ? nq : nq + 2UL, 256UL, (&void)&p3[0]);
        }
        unsafe {
            void* pq = (void*)g_g4r_qkv.dev; void* pk = (void*)src.kc.dev; void* pv = (void*)src.vc.dev;
            void* pt = (void*)g_g4r_part.dev; void* pp = (void*)g_g4r_posb.dev;
            int win = ly.sliding ? (int)g_g4r_window : 0;
            void* p4[6];
            p4[0] = (void*)&pq; p4[1] = (void*)&pk; p4[2] = (void*)&pv; p4[3] = (void*)&pt; p4[4] = (void*)&pp;
            p4[5] = (void*)&win;
            // fixed grid (graph-replayable): 8 keys per wave, 4 waves per block;
            // waves past the last key return at once
            unsigned long span = ly.sliding ? g_g4r_window : g_g4r_maxSeq;
            unsigned long blocks = (span + 31UL) / 32UL;
            ok = ok && g4r_launch_(hd == 512UL ? G4K_ATTN512 : G4K_ATTN256, blocks, 256UL, (&void)&p4[0]);
            void* pa = (void*)g_g4r_attn.dev; int h = (int)hd;
            void* p5[5];
            p5[0] = (void*)&pt; p5[1] = (void*)&pa; p5[2] = (void*)&pp; p5[3] = (void*)&win; p5[4] = (void*)&h;
            ok = ok && g4r_launch_(G4K_ATTN_COMBINE, nq, 256UL, (&void)&p5[0]);
        }
        // o = W_o attn; x += rmsnorm(o) w_post_attn; h = rmsnorm(x) w_pre_ff
        ok = ok && g4r_gemv_(ly.o.dev, g_g4r_attn.dev, g_g4r_o.dev, H, nq * hd, (float)1.0);
        ok = ok && g4r_resnorm_(g_g4r_o.dev, ly.postAttnLn.dev, (float)1.0, ly.preFfLn.dev);
        // g = gelu(gate h) * (up h); f = W_down g; x += rmsnorm(f) w_post_ff
        ok = ok && g4r_gemv_epi_(G4K_GEMV_GEGLU, ly.gateUp.dev, g_g4r_h.dev, g_g4r_g.dev, ly.inter, H, 0UL);
        ok = ok && g4r_gemv_(ly.down.dev, g_g4r_g.dev, g_g4r_f.dev, H, ly.inter, (float)1.0);
        ok = ok && g4r_resnorm_(g_g4r_f.dev, ly.postFfLn.dev, (float)1.0, 0UL);
        // pg2 = gelu(W_gate x) * ple[li]; pp = W_proj pg2;
        // x = (x + rmsnorm(pp) w) * layer_scalar; h = rmsnorm(x) w_in(next layer), or w_final
        ok = ok && g4r_gemv_epi_(G4K_GEMV_GELUVEC, ly.pleGate.dev, g_g4r_x.dev, g_g4r_pg2.dev, D, H,
                                 g_g4r_pleAll.dev + li * D * 4UL);
        unsigned long nextNorm = g_g4r_finalNorm.dev;
        if (li + 1UL < L) { unsafe { nextNorm = g_g4r_l[li + 1UL].inLn.dev; } }
        ok = ok && g4r_gemv_(ly.pleProj.dev, g_g4r_pg2.dev, g_g4r_pp.dev, H, D, (float)1.0);
        ok = ok && g4r_resnorm_(g_g4r_pp.dev, ly.postPleLn.dev, ly.scalar, nextNorm);
        li = li + 1UL;
    }
    ok = ok && g4r_gemv_(g_g4r_embed.dev, g_g4r_h.dev, g_g4r_logits.dev, g_g4r_vocab, H, (float)1.0);
    unsafe {
        void* pl = (void*)g_g4r_logits.dev; int n = (int)g_g4r_vocab; void* pv = (void*)g_g4r_amv.dev; void* pi = (void*)g_g4r_ami.dev;
        void* p6[4];
        p6[0] = (void*)&pl; p6[1] = (void*)&n; p6[2] = (void*)&pv; p6[3] = (void*)&pi;
        ok = ok && g4r_launch_(G4K_ARGMAX_PART, G4R_ARGMAX_BLOCKS, 256UL, (&void)&p6[0]);
        int nb = (int)G4R_ARGMAX_BLOCKS; void* pt = (void*)g_g4r_tok.dev; void* po = (void*)g_g4r_out.dev; void* pp = (void*)g_g4r_posb.dev;
        void* p7[6];
        p7[0] = (void*)&pv; p7[1] = (void*)&pi; p7[2] = (void*)&nb; p7[3] = (void*)&pt; p7[4] = (void*)&po; p7[5] = (void*)&pp;
        ok = ok && g4r_launch_(G4K_ARGMAX_FINAL, 1UL, 256UL, (&void)&p7[0]);
        void* p8[1];
        p8[0] = (void*)&pp;
        ok = ok && g4r_launch_(G4K_ADVANCE, 1UL, 64UL, (&void)&p8[0]);
    }
    return ok;
}

// Records one step as a graph (once); afterwards g4r_run_ replays it.
static int g4r_capture_() {
    if (g_g4r_exec != (void*)0) return 1;
    unsafe {
        if (hipStreamBeginCapture(g_g4r_stream, 0) != 0) return 0;
        int ok = g4r_step_();
        void* graph = (void*)0;
        if (hipStreamEndCapture(g_g4r_stream, &graph) != 0 || !ok) return 0;
        void* exec = (void*)0;
        if (hipGraphInstantiate(&exec, graph, (void*)0, (char*)0, 0UL) != 0) { hipGraphDestroy(graph); return 0; }
        g_g4r_graph = graph;
        g_g4r_exec = exec;
    }
    return 1;
}

static int g4r_run_(int useGraph) {
    if (useGraph && g4r_capture_()) {
        unsafe { return hipGraphLaunch(g_g4r_exec, g_g4r_stream) == 0; }
    }
    return g4r_step_();
}

// ── Public API ───────────────────────────────────────────────────────────────

int gemma4_rocm_init(&Gemma4Bf16Model model, unsigned long maxSeq) {
    if (g_g4r_ready) return 1;
    if (!rocm_available() || model.numLayers > (unsigned long)G4R_MAXL) return 0;
    unsafe { g_g4r_stream = (void*)rocm_stream_handle(); }
    // compile the kernels
    unsafe {
        void* prog = (void*)0;
        if (hiprtcCreateProgram(&prog, gemma4_rocm_kernel_source_(), "gemma4_rocm.hip", 0,
                                (const char**)0, (const char**)0) != 0) return 0;
        const char* opts[1];
        opts[0] = "-O3";
        if (hiprtcCompileProgram(prog, 1, &opts[0]) != 0) {
            unsigned long ls = 0UL;
            hiprtcGetProgramLogSize(prog, &ls);
            char* log = (char*)malloc(ls + 1UL);
            if (log != (char*)0) { hiprtcGetProgramLog(prog, log); log[ls] = (char)0; printf("gemma4_rocm: kernel compile failed:\n%s\n", log); free((void*)log); }
            hiprtcDestroyProgram(&prog);
            return 0;
        }
        unsigned long cs = 0UL;
        hiprtcGetCodeSize(prog, &cs);
        char* code = (char*)malloc(cs);
        hiprtcGetCode(prog, code);
        hiprtcDestroyProgram(&prog);
        void* module = (void*)0;
        int loaded = hipModuleLoadData(&module, (const void*)code);
        free((void*)code);
        if (loaded != 0) return 0;
        int k = 0;
        while (k < G4K_COUNT) {
            if (hipModuleGetFunction(&g_g4r_fn[k], module, g4r_kernel_name_(k)) != 0) return 0;
            k = k + 1;
        }
    }
    g_g4r_nl = model.numLayers;
    g_g4r_hidden = model.hiddenSize;
    g_g4r_ple = model.pleDim;
    g_g4r_vocab = model.vocabSize;
    g_g4r_maxSeq = maxSeq;
    g_g4r_window = model.slidingWindow;
    g_g4r_nq = model.numHeads;
    g_g4r_eps = model.eps;
    g_g4r_softcap = model.finalLogitSoftcap;
    if (model.numKvHeads != 1UL || model.numHeads != 8UL) { printf("gemma4_rocm: expects 8 query heads over 1 kv head\n"); return 0; }

    int ok = 1;
    g_g4r_embed = g4r_up_(&model.embedTokensW, &ok);
    g_g4r_embedPle = g4r_up_(&model.embedTokensPerLayerW, &ok);
    g_g4r_pleModelProj = g4r_up_(&model.perLayerModelProjectionW, &ok);
    g_g4r_pleProjNorm = g4r_up_(&model.perLayerProjectionNormW, &ok);
    g_g4r_finalNorm = g4r_up_(&model.finalNormW, &ok);
    unsigned long maxQkv = 0UL;
    unsigned long maxInter = 0UL;
    unsigned long maxAttn = 0UL;
    unsigned long li = 0UL;
    while (li < model.numLayers && ok) {
        struct G4RLayer ly;
        unsafe {
            ly.hd = model.layers[li].headDim;
            ly.sliding = model.layers[li].isSliding;
            ly.shared = model.layers[li].isKvShared;
            ly.inter = model.layers[li].gateProjW.shape0;
            ly.scalar = bf16_to_f32(model.layers[li].layerScalarW.data[0]);
            ly.inLn = g4r_up_(&model.layers[li].inputLayernormW, &ok);
            ly.postAttnLn = g4r_up_(&model.layers[li].postAttentionLayernormW, &ok);
            ly.preFfLn = g4r_up_(&model.layers[li].preFeedforwardLayernormW, &ok);
            ly.postFfLn = g4r_up_(&model.layers[li].postFeedforwardLayernormW, &ok);
            ly.qNorm = g4r_up_(&model.layers[li].qNormW, &ok);
            ly.o = g4r_up_(&model.layers[li].oProjW, &ok);
            ly.gateUp = g4r_up_interleaved_(&model.layers[li].gateProjW, &model.layers[li].upProjW, &ok);
            ly.down = g4r_up_(&model.layers[li].downProjW, &ok);
            ly.pleGate = g4r_up_(&model.layers[li].perLayerInputGateW, &ok);
            ly.pleProj = g4r_up_(&model.layers[li].perLayerProjectionW, &ok);
            ly.postPleLn = g4r_up_(&model.layers[li].postPerLayerInputNormW, &ok);
            if (ly.shared) {
                ly.qkv = g4r_up_(&model.layers[li].qProjW, &ok);
                ly.qkvRows = model.layers[li].qProjW.shape0;
                ly.kNorm.dev = 0UL; ly.kNorm.len = 0UL; ly.kNorm.dtype = ROCM_BF16;
                ly.kc.dev = 0UL; ly.kc.len = 0UL;
                ly.vc.dev = 0UL; ly.vc.len = 0UL;
                ly.kvSrc = ly.sliding ? model.lastSlidingBeforeShared : model.lastFullBeforeShared;
            } else {
                ly.qkv = g4r_up_(&model.layers[li].qkvProjW, &ok);
                ly.qkvRows = model.layers[li].qkvProjW.shape0;
                ly.kNorm = g4r_up_(&model.layers[li].kNormW, &ok);
                ly.kc = g4r_buf_(maxSeq * ly.hd, &ok);
                ly.vc = g4r_buf_(maxSeq * ly.hd, &ok);
                ly.kvSrc = li;
            }
            g_g4r_l[li] = ly;
        }
        if (ly.qkvRows > maxQkv) maxQkv = ly.qkvRows;
        if (ly.inter > maxInter) maxInter = ly.inter;
        if (model.numHeads * ly.hd > maxAttn) maxAttn = model.numHeads * ly.hd;
        li = li + 1UL;
    }
    unsigned long H = model.hiddenSize;
    g_g4r_x = g4r_buf_(H, &ok);
    g_g4r_h = g4r_buf_(H, &ok);
    g_g4r_qkv = g4r_buf_(maxQkv, &ok);
    g_g4r_attn = g4r_buf_(maxAttn, &ok);
    g_g4r_o = g4r_buf_(H, &ok);
    g_g4r_gu = g4r_buf_(2UL * maxInter, &ok);
    g_g4r_g = g4r_buf_(maxInter, &ok);
    g_g4r_f = g4r_buf_(H, &ok);
    g_g4r_pg = g4r_buf_(model.pleDim, &ok);
    g_g4r_pg2 = g4r_buf_(model.pleDim, &ok);
    g_g4r_pp = g4r_buf_(H, &ok);
    g_g4r_pleAll = g4r_buf_(model.numLayers * model.pleDim, &ok);
    g_g4r_pleRaw = g4r_buf_(model.numLayers * model.pleDim, &ok);
    g_g4r_logits = g4r_buf_(model.vocabSize, &ok);
    // attention partials: one per 8-key wave over the longest span, 8 heads x (512 + 2)
    g_g4r_part = g4r_buf_(((maxSeq + 31UL) / 32UL) * 4UL * 8UL * 514UL, &ok);
    g_g4r_amv = g4r_buf_(G4R_ARGMAX_BLOCKS, &ok);
    g_g4r_ami = g4r_buf_(G4R_ARGMAX_BLOCKS, &ok);
    g_g4r_tok = g4r_buf_(1UL, &ok);
    g_g4r_posb = g4r_buf_(2UL, &ok);
    g_g4r_out = g4r_buf_(maxSeq, &ok);
    if (!ok) { printf("gemma4_rocm: GPU allocation/upload failed\n"); return 0; }
    rocm_sync();
    g_g4r_ready = 1;
    gemma4_rocm_reset();
    return 1;
}

void gemma4_rocm_reset() {
    if (!g_g4r_ready) return;
    g_g4r_pos = 0UL;
    unsafe {
        int z[2];
        z[0] = 0; z[1] = 0;
        hipMemcpyAsync((void*)g_g4r_posb.dev, (const void*)&z[0], 8UL, G4R_H2D, g_g4r_stream);
        hipStreamSynchronize(g_g4r_stream);
    }
}

unsigned long gemma4_rocm_position() { return g_g4r_pos; }

int gemma4_rocm_prefill(const &int prompt, unsigned long n, ?&float logitsOut, int useGraph) {
    if (!g_g4r_ready || n == 0UL || g_g4r_pos + n > g_g4r_maxSeq) return -1;
    unsigned long i = 0UL;
    while (i < n) {
        unsafe {
            if (hipMemcpyAsync((void*)g_g4r_tok.dev, (const void*)&prompt[i], 4UL, G4R_H2D, g_g4r_stream) != 0) return -1;
        }
        // the input token must be in place before the step reads it; the
        // copy is from pageable host memory, so finish it before moving on
        unsafe { hipStreamSynchronize(g_g4r_stream); }
        if (!g4r_run_(useGraph)) return -1;
        i = i + 1UL;
    }
    g_g4r_pos = g_g4r_pos + n;
    int next = -1;
    unsafe {
        // the prefill's predictions are not generated tokens: restart the count at 1
        // with the last prediction as out[0]
        hipMemcpyAsync((void*)&next, (const void*)g_g4r_tok.dev, 4UL, G4R_D2H, g_g4r_stream);
        hipStreamSynchronize(g_g4r_stream);
        int cnt = 1;
        hipMemcpyAsync((void*)(g_g4r_posb.dev + 4UL), (const void*)&cnt, 4UL, G4R_H2D, g_g4r_stream);
        hipMemcpyAsync((void*)g_g4r_out.dev, (const void*)&next, 4UL, G4R_H2D, g_g4r_stream);
        hipStreamSynchronize(g_g4r_stream);
    }
    match (logitsOut) {
        case some(lo): {
            rocm_read(g_g4r_logits, lo, g_g4r_vocab);
            unsigned long v = 0UL;
            float cap = g_g4r_softcap;
            unsafe { while (v < g_g4r_vocab) { lo[v] = tanh_f(lo[v] / cap) * cap; v = v + 1UL; } }
        }
        case null: { }
    }
    return next;
}

unsigned long gemma4_rocm_generate(unsigned long maxNew, &int outIds, int eosId,
                                   unsigned long checkEvery, int useGraph) {
    if (!g_g4r_ready || maxNew == 0UL) return 0UL;
    if (checkEvery == 0UL) checkEvery = maxNew;
    unsigned long have = 1UL;                  // out[0] is the prefill's prediction
    unsigned long done = 0UL;                  // tokens checked for eos so far
    while (have < maxNew && g_g4r_pos < g_g4r_maxSeq) {
        unsigned long batch = checkEvery;
        if (have + batch > maxNew) batch = maxNew - have;
        if (g_g4r_pos + batch > g_g4r_maxSeq) batch = g_g4r_maxSeq - g_g4r_pos;
        unsigned long s = 0UL;
        while (s < batch) {
            if (!g4r_run_(useGraph)) return 0UL;
            s = s + 1UL;
        }
        g_g4r_pos = g_g4r_pos + batch;
        have = have + batch;
        if (eosId >= 0) {
            unsafe {
                hipMemcpyAsync((void*)&outIds[0], (const void*)g_g4r_out.dev, have * 4UL, G4R_D2H, g_g4r_stream);
                hipStreamSynchronize(g_g4r_stream);
            }
            unsigned long j = done;
            while (j < have) {
                unsafe { if (outIds[j] == eosId) return j + 1UL; }
                j = j + 1UL;
            }
            done = have;
        }
    }
    unsafe {
        hipMemcpyAsync((void*)&outIds[0], (const void*)g_g4r_out.dev, have * 4UL, G4R_D2H, g_g4r_stream);
        hipStreamSynchronize(g_g4r_stream);
    }
    if (eosId >= 0) {
        unsigned long j = 0UL;
        while (j < have) { unsafe { if (outIds[j] == eosId) return j + 1UL; } j = j + 1UL; }
    }
    return have;
}

void gemma4_rocm_free() {
    if (!g_g4r_ready) return;
    unsafe {
        if (g_g4r_exec != (void*)0) { hipGraphExecDestroy(g_g4r_exec); g_g4r_exec = (void*)0; }
        if (g_g4r_graph != (void*)0) { hipGraphDestroy(g_g4r_graph); g_g4r_graph = (void*)0; }
    }
    rocm_half_free(&g_g4r_embed); rocm_half_free(&g_g4r_embedPle); rocm_half_free(&g_g4r_pleModelProj);
    rocm_half_free(&g_g4r_pleProjNorm); rocm_half_free(&g_g4r_finalNorm);
    unsigned long li = 0UL;
    while (li < g_g4r_nl) {
        unsafe {
            rocm_half_free(&g_g4r_l[li].inLn); rocm_half_free(&g_g4r_l[li].postAttnLn);
            rocm_half_free(&g_g4r_l[li].preFfLn); rocm_half_free(&g_g4r_l[li].postFfLn);
            rocm_half_free(&g_g4r_l[li].qkv); rocm_half_free(&g_g4r_l[li].qNorm);
            if (g_g4r_l[li].kNorm.dev != 0UL) rocm_half_free(&g_g4r_l[li].kNorm);
            rocm_half_free(&g_g4r_l[li].o); rocm_half_free(&g_g4r_l[li].gateUp);
            rocm_half_free(&g_g4r_l[li].down); rocm_half_free(&g_g4r_l[li].pleGate);
            rocm_half_free(&g_g4r_l[li].pleProj); rocm_half_free(&g_g4r_l[li].postPleLn);
            if (g_g4r_l[li].kc.dev != 0UL) { rocm_free(&g_g4r_l[li].kc); rocm_free(&g_g4r_l[li].vc); }
        }
        li = li + 1UL;
    }
    rocm_free(&g_g4r_x); rocm_free(&g_g4r_h); rocm_free(&g_g4r_qkv); rocm_free(&g_g4r_attn);
    rocm_free(&g_g4r_o); rocm_free(&g_g4r_gu); rocm_free(&g_g4r_g); rocm_free(&g_g4r_f);
    rocm_free(&g_g4r_pg); rocm_free(&g_g4r_pg2); rocm_free(&g_g4r_pp); rocm_free(&g_g4r_pleAll);
    rocm_free(&g_g4r_pleRaw); rocm_free(&g_g4r_logits); rocm_free(&g_g4r_part); rocm_free(&g_g4r_amv);
    rocm_free(&g_g4r_ami); rocm_free(&g_g4r_tok); rocm_free(&g_g4r_posb); rocm_free(&g_g4r_out);
    g_g4r_ready = 0;
}

} // namespace std
