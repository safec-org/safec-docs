// SafeC playground — the ONE integration point with the in-browser compiler.
//
// The compiler is a self-hosted SafeC compiler built to WebAssembly
// (SafeC repository, selfhost/: selfhost/build-web.sh builds it into
// selfhost/web/, whose entry module is scc.js). site/build.sh copies
// selfhost/web/ to assets/scc/ when the SafeC checkout it builds against
// has it, so the site finds the compiler at assets/scc/scc.js. The
// contract this file relies on:
//
//   import * as scc from './scc/scc.js';
//   scc.compile(source: string)
//     -> { ok: boolean, diagnostics: Diagnostic[], wasm?: Uint8Array }   (or a Promise of it)
//   scc.run(wasm: Uint8Array, { stdout(text: string), stderr?(text: string) })
//     -> exitCode: number                                                (or a Promise of it)
//
// where a Diagnostic is either a string ("main.sc:3:5: error: ...") or
// { line, column|col, severity: 'error'|'warning'|'note', message }.
// Both calls run inside a module Web Worker (playground-worker.js), so a
// long compile or an endless loop never blocks the page and Stop can
// terminate it. To wire up a compiler with a different shape, change only
// this file and playground-worker.js.

export const COMPILER_URL = new URL('scc/scc.js', import.meta.url);

// Is the compiler deployed with this site?
export async function compilerAvailable() {
  try {
    const r = await fetch(COMPILER_URL, { method: 'HEAD', cache: 'no-store' });
    return r.ok;
  } catch {
    return false;
  }
}

// Diagnostics as { line, col, severity, message } (line/col 0 = unknown).
export function normalizeDiagnostics(list) {
  const out = [];
  for (const d of list || []) {
    if (typeof d === 'string') {
      const m = /^(?:[^:\n]*:)?(\d+):(\d+):\s*(error|warning|note)?:?\s*([\s\S]*)$/.exec(d);
      if (m) out.push({ line: +m[1], col: +m[2], severity: m[3] || 'error', message: m[4] });
      else out.push({ line: 0, col: 0, severity: 'error', message: d });
    } else if (d && typeof d === 'object') {
      out.push({
        line: +(d.line ?? 0), col: +(d.column ?? d.col ?? 0),
        severity: String(d.severity || 'error'), message: String(d.message ?? d.text ?? ''),
      });
    }
  }
  return out;
}

// Compiles and runs 'source' in a fresh worker. 'on' receives:
//   phase('compiling' | 'running'), diagnostics(list, ok), stdout(text),
//   stderr(text), exit(code), error(message)
// Returns { stop() }.
export function startRun(source, on) {
  const worker = new Worker(new URL('playground-worker.js', import.meta.url), { type: 'module' });
  let finished = false;
  const finish = () => { if (!finished) { finished = true; worker.terminate(); } };
  worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === 'phase') on.phase?.(m.phase);
    else if (m.type === 'compiled') { on.diagnostics?.(normalizeDiagnostics(m.diagnostics), m.ok); if (!m.ok) finish(); }
    else if (m.type === 'stdout') on.stdout?.(m.text);
    else if (m.type === 'stderr') on.stderr?.(m.text);
    else if (m.type === 'exit') { on.exit?.(m.code); finish(); }
    else if (m.type === 'error') { on.error?.(m.message); finish(); }
  };
  worker.onerror = (e) => { on.error?.(e.message || 'worker error'); finish(); };
  worker.postMessage({ source, compiler: COMPILER_URL.href });
  return { stop: finish };
}
