// SafeC playground page: loads the editor (playground.wasm, a SafeC module
// on std/wasm — site/playground) and runs programs through the compiler
// integration point (playground-compiler.js). The wasm module owns the
// editor; this file owns running: status line, output and diagnostics.
import { loadSafeC } from './safec_dom.js';
import { compilerAvailable, startRun } from './playground-compiler.js';

const $ = (id) => document.getElementById(id);
const root = $('playground');
const label = (k) => root?.dataset['label' + k[0].toUpperCase() + k.slice(1)] || k;
let app = null;
let current = null;
let available = null;          // null until probed
const probe = compilerAvailable().then((ok) => {
  available = ok;
  if (!ok) $('pg-notice').hidden = false;
  return ok;
});

const text = (ptr, len) =>
  new TextDecoder().decode(new Uint8Array(app.exports.memory.buffer, Number(ptr), Number(len)));

function setStatus(msg, cls = '') {
  const s = $('pg-status');
  s.textContent = msg;
  s.className = 'pg-status' + (cls ? ' ' + cls : '');
}

function showPane(name) {
  for (const el of document.querySelectorAll('.pg-tab, .pg-pane')) {
    const on = el.dataset.pane === name;
    el.classList.toggle('active', on);
    if (el.classList.contains('pg-tab')) el.setAttribute('aria-selected', String(on));
  }
}

function append(cls, s) {
  const out = $('pg-out');
  const span = document.createElement('span');
  if (cls) span.className = cls;
  span.textContent = s;
  out.append(span);
  out.scrollTop = out.scrollHeight;
}

// Moves the caret to a diagnostic's line:col.
function jumpTo(line, col) {
  const src = $('pg-src');
  const lines = src.value.split('\n');
  let at = 0;
  for (let i = 0; i < Math.min(line - 1, lines.length); i++) at += lines[i].length + 1;
  at += Math.max(0, col - 1);
  src.focus();
  src.setSelectionRange(at, at);
}

function showDiagnostics(list) {
  const ul = $('pg-diag');
  ul.textContent = '';
  const errors = list.filter((d) => d.severity === 'error').length;
  $('pg-diag-count').textContent = list.length ? String(list.length) : '';
  if (!list.length) {
    const li = document.createElement('li');
    li.className = 'meta';
    li.textContent = label('nodiag');
    ul.append(li);
    return errors;
  }
  for (const d of list) {
    const li = document.createElement('li');
    li.className = 'pg-diag-item ' + d.severity;
    li.tabIndex = 0;
    if (d.line) {
      const loc = document.createElement('span');
      loc.className = 'loc';
      loc.textContent = `${d.line}:${d.col || 1}`;
      li.append(loc);
      li.addEventListener('click', () => jumpTo(d.line, d.col));
      li.addEventListener('keydown', (e) => { if (e.key === 'Enter') jumpTo(d.line, d.col); });
    }
    li.append(`${d.severity}: ${d.message}`);
    ul.append(li);
  }
  return errors;
}

function setRunning(on) {
  $('pg-run').disabled = on;
  $('pg-stop').hidden = !on;
}

async function run(source) {
  current?.stop();
  $('pg-out').textContent = '';
  showPane('out');
  if (!(available ?? (await probe))) {
    append('meta', label('unavailable'));
    setStatus('');
    return;
  }
  setRunning(true);
  setStatus(label('compiling'));
  const t0 = performance.now();
  current = startRun(source, {
    phase: (p) => setStatus(label(p === 'running' ? 'running' : 'compiling')),
    diagnostics: (list, ok) => {
      showDiagnostics(list);
      if (!ok) { setRunning(false); setStatus(label('failed'), 'bad'); showPane('diag'); }
    },
    stdout: (s) => append('', s),
    stderr: (s) => append('err', s),
    exit: (code) => {
      setRunning(false);
      const ms = Math.round(performance.now() - t0);
      setStatus(`${label('done')} ${code} · ${ms} ms`, code === 0 ? 'ok' : 'bad');
      current = null;
    },
    error: (msg) => { setRunning(false); append('err', msg + '\n'); setStatus(label('failed'), 'bad'); current = null; },
  });
}

const imports = {
  pg_run: (ptr, len) => { run(text(ptr, len)); },
  pg_stop: () => {
    if (!current) return;
    current.stop();
    current = null;
    setRunning(false);
    setStatus(label('stopped'), 'bad');
  },
  // execCommand keeps the textarea's undo history (and fires 'input');
  // deferred so the wasm keydown handler has returned first.
  pg_insert: (ptr, len) => {
    const s = text(ptr, len);
    queueMicrotask(() => {
      const src = $('pg-src');
      if (!document.execCommand('insertText', false, s)) {
        src.setRangeText(s, src.selectionStart, src.selectionEnd, 'end');
        src.dispatchEvent(new Event('input'));
      }
    });
  },
  pg_clipboard: (ptr, len) => { navigator.clipboard?.writeText(text(ptr, len)).catch(() => {}); },
  snprintf: () => 0,
};

const globals = {
  fetch: (...a) => fetch(...a),
  requestAnimationFrame: (cb) => requestAnimationFrame(cb),
  cancelAnimationFrame: (id) => cancelAnimationFrame(id),
  setTimeout: (...a) => setTimeout(...a),
  clearTimeout: (id) => clearTimeout(id),
};

try {
  app = await loadSafeC(fetch(new URL('playground.wasm', import.meta.url)), { imports, globals, prefix: '[playground] ' });
  app.exports.pg_init();
} catch (e) {
  console.warn('playground.wasm unavailable', e);
}
