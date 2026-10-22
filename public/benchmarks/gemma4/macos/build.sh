#!/usr/bin/env bash
# Builds a gemma4 entry point on macOS (the CPU path, plus the Metal path
# through std/ml/gpu_mps) against a release std archive.
#
#   usage: macos/build.sh <entry.sc> <output>
#   e.g.   macos/build.sh tests/bench_safec.sc /tmp/bench_safec
#          /tmp/bench_safec cpu 1 ~/models/gemma4-e2b
#
# Env: SAFEC_HOME (SafeC checkout; default ../../../../SafeC from here),
#      SAFEC, CLANG (Homebrew LLVM's clang by default -- Apple's clang is
#      older than the IR safec emits), SAFEGUARD (default: on PATH),
#      STD_ARCHIVE (a prebuilt release libsafec_std.a; otherwise built once
#      under macos/build/_std with 'safeguard build --release', [build] std = "all").
# Accelerate is linked for std's CBLAS / vDSP paths (std::sgemm, FIR).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
ENTRY="$1"; OUT="$2"
export SAFEC_HOME="${SAFEC_HOME:-$(cd "$PROJ/../../../../SafeC" 2>/dev/null && pwd)}"
SAFEC="${SAFEC:-safec}"; SAFEGUARD="${SAFEGUARD:-safeguard}"
CLANG="${CLANG:-}"
if [[ -z "$CLANG" ]]; then
    for c in /opt/homebrew/opt/llvm/bin/clang /usr/local/opt/llvm/bin/clang clang; do
        if command -v "$c" >/dev/null 2>&1; then CLANG="$c"; break; fi
    done
fi
export SAFEC
B="$HERE/build"
mkdir -p "$B"

STD="${STD_ARCHIVE:-$B/_std/build/deps/libsafec_std.a}"
if [[ ! -f "$STD" ]]; then
    mkdir -p "$B/_std/src"
    printf '[package]\nname = "gemma4_std"\nversion = "0.1.0"\n\n[build]\nedition = "2026"\nstd = "all"\n' > "$B/_std/Package.toml"
    echo 'int main() { return 0; }' > "$B/_std/src/main.sc"
    (cd "$B/_std" && "$SAFEGUARD" build --release >/dev/null)
fi

cat > "$B/clang_link.sh" <<LINK
#!/bin/sh
exec "$CLANG" -w "\$@" "$STD" -framework Metal -framework Foundation -framework Accelerate -lobjc -lm -lpthread
LINK
chmod +x "$B/clang_link.sh"

SRCS=()
for f in safetensors gemma4_bf16 gemma4_rope gemma4_attention gemma4_ple gemma4_ffn gemma4_model_bf16 \
         gemma4_tokenizer gemma4_generate; do
    SRCS+=("$PROJ/src/$f.sc")
done
SAFEC_CLANG="$B/clang_link.sh" "$SAFEC" "$ENTRY" "${SRCS[@]}" -I "$SAFEC_HOME" -O2 --emit-bin -o "$OUT"
