#!/usr/bin/env bash
# Builds the SafeC website: the wasm client and playground editor, the
# generator, then the site itself into ../dist.
#
#   site/build.sh            # build + link check (what CI runs)
#   site/build.sh serve      # build, then serve on http://localhost:5173/ with rebuild-on-change
#   site/build.sh build --base /    # any generator command line
#
# Needs safec + safeguard on PATH and SAFEC_HOME pointing at a SafeC
# checkout (its std/, including std/wasm/safec_dom.js; its CHANGELOG.md
# for the changelog and release notes); wasm-ld for the wasm modules.
#
# Optional, from the same SafeC checkout:
#   - the in-browser compiler for the playground: when SafeC has
#     selfhost/build-web.sh, it is run and selfhost/web/ is copied to
#     assets/scc/ (see theme/playground-compiler.js). SITE_SKIP_SCC=1 skips it.
#   - the std API reference under /api/ (step 4; needs a SafeC with
#     'safeguard doc'). SITE_SKIP_API=1 skips it.
#   - SITE_FETCH_RELEASES=1 refreshes the release history from GitHub
#     (tools/releases-snapshot.py -> build/releases.json); without it, or
#     offline, the checked-in data/releases.json is used.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
: "${SAFEC_HOME:?set SAFEC_HOME to a SafeC checkout}"
SAFEGUARD="${SAFEGUARD:-safeguard}"

# 1. The wasm modules and std's JS glue -> theme/ (copied to dist/assets/).
(cd "$HERE/client" && "$SAFEGUARD" build --release --target wasm32 >/dev/null)
cp "$HERE/client/build/site-client" "$HERE/theme/site.wasm"
(cd "$HERE/playground" && "$SAFEGUARD" build --release --target wasm32 >/dev/null)
cp "$HERE/playground/build/site-playground" "$HERE/theme/playground.wasm"
cp "$SAFEC_HOME/std/wasm/safec_dom.js" "$HERE/theme/safec_dom.js"

# 2. The playground's compiler, when the SafeC checkout can build it.
rm -rf "$HERE/theme/scc"
if [[ "${SITE_SKIP_SCC:-0}" != 1 && -f "$SAFEC_HOME/selfhost/build-web.sh" ]]; then
  if (cd "$SAFEC_HOME" && bash selfhost/build-web.sh >&2) && [[ -f "$SAFEC_HOME/selfhost/web/scc.js" ]]; then
    cp -R "$SAFEC_HOME/selfhost/web" "$HERE/theme/scc"
    echo "site: playground compiler from $SAFEC_HOME/selfhost/web" >&2
  else
    echo "site: selfhost/build-web.sh failed; the playground ships without a compiler" >&2
  fi
fi

# 3. Release history (optional refresh).
mkdir -p "$HERE/build"
rm -f "$HERE/build/releases.json"
if [[ "${SITE_FETCH_RELEASES:-0}" == 1 ]] && command -v python3 >/dev/null; then
  python3 "$HERE/tools/releases-snapshot.py" "$HERE/build/releases.json" --safec "$SAFEC_HOME" >&2 \
    || echo "site: release history refresh failed; using data/releases.json" >&2
fi

# 4. The std API reference (/api/): 'safeguard doc --std' (safec-doc, from
#    the same SafeC checkout), into build/api, which the generator copies
#    into the output (site.yaml 'generated'). It runs its own link check.
#    SITE_SKIP_API=1 skips it (then the /api/ links fail the check).
rm -rf "$HERE/build/api"
if [[ "${SITE_SKIP_API:-0}" != 1 ]]; then
  if "$SAFEGUARD" --help 2>/dev/null | grep -q '^  doc '; then
    (cd "$HERE" && "$SAFEGUARD" doc --std --out "$HERE/build/api" --home "../=SafeC docs" \
       --source-ref "${SITE_API_SOURCE_REF:-main}" >&2)
  else
    echo "site: this safeguard has no 'doc' command (an older SafeC); no /api/ reference" >&2
  fi
fi

# 5. The generator.
(cd "$HERE" && "$SAFEGUARD" build --release >/dev/null)

# 6. The site.
cd "$HERE"
if [[ $# -eq 0 ]]; then set -- check; fi
exec ./build/safec-site "$@"
