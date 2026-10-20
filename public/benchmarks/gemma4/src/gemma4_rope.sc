// SafeC Standard Library — Gemma 4 RoPE implementation (see
// gemma4_rope.h).
#pragma once
#include "gemma4_rope.h"
#include <std/math.h>

namespace std {

extern void* malloc(unsigned long size);
extern void  free(void* ptr);

struct Gemma4RopeTable gemma4_rope_build(int isFullAttention, unsigned long headDim, const unsigned long* positions, unsigned long count) {
    struct Gemma4RopeTable table;
    unsigned long halfDim = headDim / 2UL;
    table.count = count;
    table.headDim = headDim;

    float invFreq[256]; // halfDim <= 256 for both configs used by this model
    if (isFullAttention) {
        // p-RoPE: theta=1e6, only the first 64 of 256 pairs rotate; the
        // rest are zero-padded ("NoPE" -- see header comment).
        unsigned long ropeAngles = 64UL;
        unsigned long j = 0UL;
        while (j < halfDim) {
            if (j < ropeAngles) {
                float exponent = ((float)(2UL * j)) / (float)headDim;
                invFreq[j] = (float)1.0 / pow_f((float)1000000.0, exponent);
            } else {
                invFreq[j] = (float)0.0;
            }
            j = j + 1UL;
        }
    } else {
        // Standard RoPE: theta=10000, full rotation.
        unsigned long i = 0UL;
        while (i < halfDim) {
            float exponent = ((float)(2UL * i)) / (float)headDim;
            invFreq[i] = (float)1.0 / pow_f((float)10000.0, exponent);
            i = i + 1UL;
        }
    }

    unsafe {
        table.cosTable = (&heap float)malloc(sizeof(float) * count * headDim);
        table.sinTable = (&heap float)malloc(sizeof(float) * count * headDim);
    }

    unsigned long r = 0UL;
    while (r < count) {
        unsigned long pos;
        unsafe { pos = positions[r]; }
        unsigned long i = 0UL;
        while (i < halfDim) {
            float freq = (float)pos * invFreq[i];
            float cosVal = cos_f(freq);
            float sinVal = sin_f(freq);
            // emb = cat(freqs, freqs) -- same value written at i and i+halfDim.
            unsafe {
                table.cosTable[r * headDim + i] = cosVal;
                table.cosTable[r * headDim + i + halfDim] = cosVal;
                table.sinTable[r * headDim + i] = sinVal;
                table.sinTable[r * headDim + i + halfDim] = sinVal;
            }
            i = i + 1UL;
        }
        r = r + 1UL;
    }

    return table;
}

void gemma4_rope_table_free(&Gemma4RopeTable t) {
    unsafe {
        if (t.cosTable != (&heap float)0) { free((void*)t.cosTable); }
        if (t.sinTable != (&heap float)0) { free((void*)t.sinTable); }
    }
    t.count = 0UL;
    t.headDim = 0UL;
}

// rotate_half(x)[i] = -x[i+halfDim] for i<halfDim, x[i-halfDim]
// otherwise; out = x*cos + rotate_half(x)*sin. cos/sin tables stay F32
// (small precomputed trig lookups, not activations -- no reason to
// shrink them); x itself widens/narrows bf16<->float32 per element,
// same discipline as gemma4_bf16.sc's RMSNorm.
struct Gemma4Bf16Tensor gemma4_bf16_rope_apply(const &Gemma4Bf16Tensor x, &Gemma4RopeTable table) {
    unsigned long rows = x.shape0; unsigned long headDim = x.shape1;
    unsigned long halfDim = headDim / 2UL;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(rows, headDim);
    unsafe {
        unsigned long r = 0UL;
        while (r < rows) {
            unsigned long i = 0UL;
            while (i < headDim) {
                float xVal = bf16_to_f32(x.data[r * headDim + i]);
                float rotVal;
                if (i < halfDim) {
                    rotVal = -(bf16_to_f32(x.data[r * headDim + i + halfDim]));
                } else {
                    rotVal = bf16_to_f32(x.data[r * headDim + i - halfDim]);
                }
                float cosVal = table.cosTable[r * headDim + i];
                float sinVal = table.sinTable[r * headDim + i];
                out.data[r * headDim + i] = f32_to_bf16(xVal * cosVal + rotVal * sinVal);
                i = i + 1UL;
            }
            r = r + 1UL;
        }
    }
    return out;
}

} // namespace std
