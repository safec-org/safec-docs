// SafeC docs — loader for the wasm client (site/client, built to
// assets/site.wasm). INTERIM glue: implements today's std/wasm/dom.h
// imports by hand; it is replaced by std/wasm's own JS glue loader once
// that lands, leaving only site_store_theme (or nothing, once std/wasm
// exposes localStorage).
let wasm = null;
const els = [];
const handles = new Map();
const dec = new TextDecoder();
const str = (p, n) => dec.decode(new Uint8Array(wasm.exports.memory.buffer, Number(p), Number(n)));
const handle = (el) => {
  if (handles.has(el)) return handles.get(el);
  els.push(el); handles.set(el, els.length - 1); return els.length - 1;
};

const env = {
  js_get_element: (p, n) => { const el = document.querySelector(str(p, n)); return el ? handle(el) : -1; },
  js_set_text: (h, p, n) => { els[h].textContent = str(p, n); },
  js_set_attr: (h, np, nn, vp, vn) => {
    const name = str(np, nn), value = str(vp, vn);
    // Interim: the client toggles state attributes; map them onto classes
    // until std/wasm has classList.
    if (name === 'class' && els[h] === document.documentElement) els[h].classList.toggle('dark', value === 'dark');
    else if (name === 'data-menu') els[h].classList.toggle('menu-open', value === 'open');
    else els[h].setAttribute(name, value);
  },
  js_add_click_listener: (h, id) => { els[h].addEventListener('click', () => wasm.exports.std_dom_dispatch_event(id)); return 0; },
  js_console_log: (p, n) => console.log('[site.wasm]', str(p, n)),
  site_store_theme: (dark) => { try { localStorage.setItem('safec-theme', dark ? 'dark' : 'light'); } catch (e) {} },
  // libc / compiler-rt pieces std/wasm/wasm_rt doesn't provide yet.
  strlen: (p) => { const m = new Uint8Array(wasm.exports.memory.buffer); let i = p; while (m[i]) i++; return i - p; },
  __multi3: () => { throw new Error('__multi3 unavailable'); },
  abort: () => { throw new Error('site.wasm: abort()'); },
  fprintf: () => 0,
  qsort: () => {},
};

try {
  const url = new URL('site.wasm', import.meta.url);
  const { instance } = await WebAssembly.instantiateStreaming(fetch(url), { env });
  wasm = instance;
  instance.exports.site_init(document.documentElement.classList.contains('dark') ? 1 : 0);
} catch (e) {
  console.warn('site.wasm unavailable; the page works without it.', e);
}
