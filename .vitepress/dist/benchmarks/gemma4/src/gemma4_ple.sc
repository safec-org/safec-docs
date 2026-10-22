// SafeC Standard Library — Gemma 4 Per-Layer Embeddings implementation,
// bf16 throughout (see gemma4_ple.h).
#pragma once
#include "gemma4_ple.h"
#include <std/math.h>

namespace std {

struct Gemma4Bf16Tensor gemma4_bf16_ple_combine(const &Gemma4Bf16Tensor inputsEmbeds, const &Gemma4Bf16Tensor perLayerModelProjectionW,
                            const &Gemma4Bf16Tensor perLayerProjectionNormW, const &Gemma4Bf16Tensor tokenIdentity,
                            unsigned long numLayers, unsigned long pleDim, float eps) {
    unsigned long hiddenSize = inputsEmbeds.shape1;
    float invSqrtHidden = (float)1.0 / sqrt_f((float)hiddenSize);

    struct Gemma4Bf16Tensor rawProj = gemma4_bf16_linear(inputsEmbeds, perLayerModelProjectionW); // [seqLen, numLayers*pleDim]
    struct Gemma4Bf16Tensor scaledProj = gemma4_bf16_scale(&rawProj, invSqrtHidden);
    gemma4_bf16_free(&rawProj);

    unsigned long seqLen = scaledProj.shape0;
    struct Gemma4Bf16Tensor normedProj = gemma4_bf16_new_2d(seqLen, numLayers * pleDim);

    unsigned long i = 0UL;
    while (i < numLayers) {
        struct Gemma4Bf16Tensor slice = gemma4_bf16_slice_cols(&scaledProj, i * pleDim, pleDim);
        struct Gemma4Bf16Tensor normed = gemma4_bf16_rmsnorm_rows(&slice, perLayerProjectionNormW, eps);
        gemma4_bf16_write_cols(&normedProj, &normed, i * pleDim, pleDim);
        gemma4_bf16_free(&slice); gemma4_bf16_free(&normed);
        i = i + 1UL;
    }
    gemma4_bf16_free(&scaledProj);

    struct Gemma4Bf16Tensor summed = gemma4_bf16_add(&normedProj, tokenIdentity);
    gemma4_bf16_free(&normedProj);
    struct Gemma4Bf16Tensor combined = gemma4_bf16_scale(&summed, (float)1.0 / sqrt_f((float)2.0));
    gemma4_bf16_free(&summed);

    return combined;
}

struct Gemma4Bf16Tensor gemma4_bf16_ple_slice_layer(const &Gemma4Bf16Tensor pleAll, unsigned long layerIdx, unsigned long pleDim) {
    return gemma4_bf16_slice_cols(pleAll, layerIdx * pleDim, pleDim);
}

} // namespace std
