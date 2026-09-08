// `editor-prefs.js` — the theme payload: what a shared theme may set, and
// how it lands on the page. A theme is data from ANOTHER user, so the
// sanitizer is the boundary: only allow-listed custom properties, only
// colour literals (no `url(…)`, no stray declarations), font stacks of
// plain characters, a clamped size. Then the apply path: inline on <body>
// (so it beats `body.theme-dark`), cleared on reset.
//
// Run:  node tools/runtime-test/theme-payload.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let failures = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}
function test(name, fn) {
  console.log(' ' + name);
  try { fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

function styleStub() {
  const props = new Map();
  return {
    props,
    setProperty(k, v) { props.set(k, v); },
    removeProperty(k) { props.delete(k); },
    get fontSize() { return props.get('font-size') || ''; },
    set fontSize(v) { if (v) props.set('font-size', v); else props.delete('font-size'); },
  };
}

function makeCtx() {
  const classes = new Set();
  const body = {
    style: styleStub(),
    classList: { toggle(c, on) { if (on) classes.add(c); else classes.delete(c); return classes.has(c); }, contains: (c) => classes.has(c) },
  };
  const html = { style: styleStub() };
  const store = {};
  const ctx = vm.createContext({
    console,
    document: {
      body, documentElement: html,
      getElementById: () => null, addEventListener() {}, readyState: 'complete',
    },
    window: { addEventListener() {}, innerWidth: 1200 },
    localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); } },
    getComputedStyle: () => ({ getPropertyValue: () => '' }),
    requestAnimationFrame: (fn) => fn(),
    fetch: () => Promise.reject(new Error('offline')),
  });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-prefs.js'), 'utf8'), ctx, { filename: 'editor-prefs.js' });
  return { ctx, body, html, classes, store };
}

test('the sanitizer keeps allow-listed colour tokens and drops everything else', () => {
  const { ctx } = makeCtx();
  const clean = ctx.window.gdSanitizeThemePayload({
    mode: 'dark',
    tokens: {
      '--gd-paper': '#101214',
      '--gd-ink': 'rgb(230, 233, 232)',
      '--gd-flow': 'hsl(174 73% 28%)',
      '--gd-paper-2': 'url(https://evil.example/px.png)',
      '--gd-ink-2': '#fff; background: red',
      '--not-a-token': '#000',
      '--gd-line': 42,
    },
    fonts: { ui: 'Inter, sans-serif', mono: 'JetBrains Mono', body: 'x</style><script>' },
    scale: 300,
  });
  assert(clean.mode === 'dark', 'mode kept');
  assert(clean.tokens['--gd-paper'] === '#101214', 'hex kept');
  assert(clean.tokens['--gd-ink'] === 'rgb(230, 233, 232)', 'rgb kept');
  assert(clean.tokens['--gd-flow'] === 'hsl(174 73% 28%)', 'hsl kept');
  assert(!('--gd-paper-2' in clean.tokens), 'url() dropped');
  assert(!('--gd-ink-2' in clean.tokens), 'a second declaration dropped');
  assert(!('--not-a-token' in clean.tokens), 'unknown property dropped');
  assert(!('--gd-line' in clean.tokens), 'non-string dropped');
  assert(clean.fonts.ui === 'Inter, sans-serif' && clean.fonts.mono === 'JetBrains Mono', 'font stacks kept');
  assert(!('body' in clean.fonts), 'a font with markup dropped');
  assert(clean.scale === 160, 'scale clamped to the max');
  assert(ctx.window.gdSanitizeThemePayload(null) === null, 'null stays null');
  assert(ctx.window.gdSanitizeThemePayload({}).mode === 'light', 'defaults: light, 100%');
  assert(ctx.window.gdSanitizeThemePayload({ scale: 'abc' }).scale === 100, 'a bad scale is 100');
});

test('applying a theme sets inline tokens on <body>, the mode class and the root size; reset clears them', () => {
  const { ctx, body, html, classes } = makeCtx();
  ctx.window.gdApplyThemePayload({
    mode: 'dark', tokens: { '--gd-paper': '#101214' }, fonts: { mono: 'Fira Code' }, scale: 120,
  });
  assert(body.style.props.get('--gd-paper') === '#101214', 'token inline on body');
  assert(body.style.props.get('--gd-mono') === 'Fira Code', 'mono font var set');
  assert(body.style.props.get('--mono') === 'Fira Code', 'legacy --mono mirrors the mono font');
  assert(classes.has('theme-dark'), 'dark base class set');
  assert(classes.has('gd-custom-theme'), 'custom-theme marker set');
  assert(html.style.fontSize === '120%', 'root font-size scaled');
  assert(ctx.window.gdActiveThemePayload().scale === 120, 'the active payload is readable');

  ctx.window.gdApplyThemePayload(null);
  assert(!body.style.props.has('--gd-paper'), 'token cleared on reset');
  assert(!body.style.props.has('--mono'), 'font cleared on reset');
  assert(html.style.fontSize === '', 'size cleared on reset');
  assert(!classes.has('gd-custom-theme'), 'marker cleared');
  assert(ctx.window.gdActiveThemePayload() === null, 'no active payload');
});

test('a second theme replaces the first completely (no leftover tokens)', () => {
  const { ctx, body } = makeCtx();
  ctx.window.gdApplyThemePayload({ tokens: { '--gd-paper': '#111111', '--gd-ink': '#eeeeee' } });
  ctx.window.gdApplyThemePayload({ tokens: { '--gd-paper': '#222222' } });
  assert(body.style.props.get('--gd-paper') === '#222222', 'new value wins');
  assert(!body.style.props.has('--gd-ink'), 'a token the new theme does not set is cleared');
});

test('the preference store mirrors to localStorage and applies on write', () => {
  const { ctx, body, store } = makeCtx();
  ctx.window.gdPrefWrite('theme', { source: { name: 't', version: '1.0.0' }, payload: { tokens: { '--gd-flow': '#123456' } } });
  assert(body.style.props.get('--gd-flow') === '#123456', 'written pref applied at once');
  const mirror = JSON.parse(store['graphden.prefs.server']);
  assert(mirror.theme.source.name === 't', 'mirror holds the pref');
  assert(ctx.window.gdPrefRead('theme').payload.tokens['--gd-flow'] === '#123456', 'readable back');
});

console.log(failures ? `\n${failures} failed, ${passes} passed` : `\nall ${passes} passed`);
process.exit(failures ? 1 : 0);
