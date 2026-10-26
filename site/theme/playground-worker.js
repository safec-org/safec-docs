// SafeC playground worker: compiles and runs one program with the
// in-browser compiler (see playground-compiler.js for the contract).
let scc = null;

self.onmessage = async (e) => {
  const { source, compiler } = e.data;
  const post = (m) => self.postMessage(m);
  try {
    scc ??= await import(compiler);
    post({ type: 'phase', phase: 'compiling' });
    const r = await scc.compile(source);
    const diagnostics = (r && r.diagnostics || []).map((d) => (typeof d === 'string' ? d : { ...d }));
    post({ type: 'compiled', ok: !!(r && r.ok), diagnostics });
    if (!r || !r.ok) return;
    post({ type: 'phase', phase: 'running' });
    const code = await scc.run(r.wasm, {
      stdout: (text) => post({ type: 'stdout', text: String(text) }),
      stderr: (text) => post({ type: 'stderr', text: String(text) }),
    });
    post({ type: 'exit', code: Number(code ?? 0) });
  } catch (err) {
    post({ type: 'error', message: String((err && err.message) || err) });
  }
};
