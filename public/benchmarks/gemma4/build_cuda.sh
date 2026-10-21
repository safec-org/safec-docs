#!/usr/bin/env bash
# Builds build/gemma4_cuda, the CUDA entry point (src/main_cuda.sc). Needs safec, clang and
# CUDA 12+, and SafeC's std archive: run 'safeguard test' in $SAFEC_HOME/tests/stdlib first.
# Usage: SAFEC_HOME=/path/to/SafeC ./build_cuda.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"
SAFEC="${SAFEC:-safec}"; CLANG="${CLANG:-clang}"; CUDA="${CUDA_PATH:-/usr/local/cuda}"
ROOT="${SAFEC_HOME:?set SAFEC_HOME to the SafeC checkout}"
STD="$ROOT/tests/stdlib/build/deps/libsafec_std.a"
OUT="${OUT:-$HERE/build}"; mkdir -p "$OUT"
for f in main_cuda gemma4_cuda gemma4_tokenizer safetensors gemma4_bf16; do
    "$SAFEC" -I "$ROOT" -I "$SRC" "$SRC/$f.sc" -O2 -o "$OUT/$f.ll"
done
"$CLANG" -O2 -c "$HERE/linux/metal_stubs.c" -o "$OUT/metal_stubs.o"   # Metal stand-ins (std/ml/gpu_mps)
"$CLANG" -O2 -w "$OUT"/main_cuda.ll "$OUT"/gemma4_cuda.ll "$OUT"/gemma4_tokenizer.ll "$OUT"/safetensors.ll "$OUT"/gemma4_bf16.ll "$OUT"/metal_stubs.o \
    "$STD" -L"$CUDA/lib64" -Wl,-rpath,"$CUDA/lib64" -lcuda -lcublas -lcublasLt -lnvrtc -lcudnn -lcufft -lopenblas -lm -lpthread \
    -o "$OUT/gemma4_cuda"
echo "built $OUT/gemma4_cuda"
