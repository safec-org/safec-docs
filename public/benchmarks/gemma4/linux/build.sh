#!/usr/bin/env bash
# Builds a gemma4 entry point on Linux (the ROCm engine, src/gemma4_rocm.h,
# or the CPU path) against a release std archive.
#
#   usage: linux/build.sh <entry.sc> <output>
#   e.g.   linux/build.sh tests/bench_rocm.sc /tmp/bench_rocm
#          /tmp/bench_rocm ~/models/gemma4-e2b 128
#
# Env: SAFEC_HOME (SafeC checkout; default ../../../../SafeC from here),
#      SAFEC, CLANG, SAFEGUARD (default: on PATH), ROCM_PATH (/opt/rocm),
#      BLAS_LIBS (default -lblas; the CPU path's sgemm), STD_ARCHIVE (a
#      prebuilt release libsafec_std.a; otherwise built once under
#      linux/build/_std with 'safeguard build --release').
# std/ml/gpu_mps.sc references Metal/Objective-C symbols on every platform;
# linux/metal_stubs.c provides null stand-ins so it links (no Metal device:
# the MPS paths report unavailable).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
ENTRY="$1"; OUT="$2"
export SAFEC_HOME="${SAFEC_HOME:-$(cd "$PROJ/../../../../SafeC" 2>/dev/null && pwd)}"
SAFEC="${SAFEC:-safec}"; CLANG="${CLANG:-clang}"; SAFEGUARD="${SAFEGUARD:-safeguard}"
ROCM="${ROCM_PATH:-/opt/rocm}"
BLAS="${BLAS_LIBS:--lblas}"
B="$HERE/build"
mkdir -p "$B"

STD="${STD_ARCHIVE:-$B/_std/build/deps/libsafec_std.a}"
if [[ ! -f "$STD" ]]; then
    mkdir -p "$B/_std/src"
    printf '[package]\nname = "gemma4_std"\nversion = "0.1.0"\n\n[build]\nedition = "2026"\n' > "$B/_std/Package.toml"
    echo 'int main() { return 0; }' > "$B/_std/src/main.sc"
    (cd "$B/_std" && "$SAFEGUARD" build --release >/dev/null)
fi

"$CLANG" -O2 -c "$HERE/metal_stubs.c" -o "$B/metal_stubs.o"
cat > "$B/clang_link.sh" <<EOF
#!/bin/sh
exec "$CLANG" -w "\$@" "$STD" "$B/metal_stubs.o" -L"$ROCM/lib" -Wl,-rpath,"$ROCM/lib" \\
    -lamdhip64 -lhiprtc -lrocblas $BLAS -lm -lpthread
EOF
chmod +x "$B/clang_link.sh"

SRCS=()
for f in safetensors gemma4_bf16 gemma4_rope gemma4_attention gemma4_ple gemma4_ffn gemma4_model_bf16 \
         gemma4_tokenizer gemma4_generate gemma4_rocm; do
    SRCS+=("$PROJ/src/$f.sc")
done
python3 "$PROJ/tools/embed_hip.py" >/dev/null      # src/gemma4_rocm_kernels.h from the .hip source
SAFEC_CLANG="$B/clang_link.sh" "$SAFEC" "$ENTRY" "${SRCS[@]}" -I "$SAFEC_HOME" -O2 --emit-bin -o "$OUT"
