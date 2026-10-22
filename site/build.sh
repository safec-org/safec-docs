#!/usr/bin/env bash
# Builds the SafeC docs site: the wasm client, the generator, then the
# site itself into ../dist.
#
#   site/build.sh            # build + link check (what CI runs)
#   site/build.sh serve      # build, then serve on http://localhost:5173/ with rebuild-on-change
#   site/build.sh build --base /    # any generator command line
#
# Needs safec + safeguard on PATH and SAFEC_HOME pointing at a SafeC
# checkout (its std/, including std/wasm/safec_dom.js); wasm-ld for the client.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
: "${SAFEC_HOME:?set SAFEC_HOME to a SafeC checkout}"
SAFEGUARD="${SAFEGUARD:-safeguard}"

# 1. The wasm client and std's JS glue -> theme/ (copied to dist/assets/).
(cd "$HERE/client" && "$SAFEGUARD" build --release --target wasm32 >/dev/null)
cp "$HERE/client/build/site-client" "$HERE/theme/site.wasm"
cp "$SAFEC_HOME/std/wasm/safec_dom.js" "$HERE/theme/safec_dom.js"

# 2. The generator.
(cd "$HERE" && "$SAFEGUARD" build --release >/dev/null)

# 3. The site.
cd "$HERE"
if [[ $# -eq 0 ]]; then set -- check; fi
exec ./build/safec-site "$@"
