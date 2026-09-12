// devtour page script — slurped verbatim into the generated index.html.
// No build step, no dependencies: the page must open from file:// with the
// checkout unavailable, so everything (data, styles, script) is inlined.
const MODEL = JSON.parse(document.getElementById('tour-data').textContent);
const T = MODEL.ui;

// flatten toured steps into a single ordered spine — index === step.gi
const SPINE = [];
MODEL.blocks.forEach(b => {
  (b.steps || []).forEach(s => { SPINE.push({ block: b, step: s }); });
});
const BY_KEY = {};
SPINE.forEach((e, i) => { BY_KEY[e.step.key] = i; });

const FOLD_OVER = 60;   // lines: longer forms open folded
const FOLD_HEAD = 40;   // lines shown while folded

let cur = -1;           // current global step index (-1 = intro)
let expanded = false;   // current step's code un-folded?

// --- persistence (file:// origins can refuse storage — never let it throw) --
const SK = 'devtour:v1:';
function store(k, v) { try { localStorage.setItem(SK + k, v); } catch (_) { } }
function load(k) { try { return localStorage.getItem(SK + k); } catch (_) { return null; } }
let seen = new Set();
try { seen = new Set(JSON.parse(load('seen') || '[]')); } catch (_) { }
function markSeen(key) {
  if (seen.has(key)) return;
  seen.add(key);
  store('seen', JSON.stringify([...seen]));
}

const esc = s => String(s).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const attr = s => esc(s).replace(/"/g, '&quot;');
const mins = m => (m < 1 ? '<1' : Math.round(m)) + ' ' + T.min;

// tiny markdown: paragraphs, `code`, **bold**, [text](url)
function md(src) {
  return (src || '').split(/\n\s*\n/).map(p => {
    let h = esc(p);
    h = h.replace(/`([^`]+)`/g, (_, x) => '<code class="inl">' + x + '</code>');
    h = h.replace(/\*\*([^*]+)\*\*/g, (_, x) => '<b>' + x + '</b>');
    h = h.replace(/\[([^\]]+)\]\(([^)]+)\)/g,
      (_, t, u) => '<a href="' + attr(u) + '">' + esc(t) + '</a>');
    return '<p>' + h + '</p>';
  }).join('');
}

// minimal JS tokeniser — strings, comments, keywords. Line-scoped like the
// Clojure one: good enough to read, never claims to parse.
const JS_KW = new RegExp('\\b(async|await|break|case|catch|class|const|continue|' +
  'default|delete|do|else|export|extends|finally|for|function|if|import|in|' +
  'instanceof|let|new|of|return|super|switch|this|throw|try|typeof|var|void|' +
  'while|yield|null|undefined|true|false)\\b', 'g');

function hlJs(line) {
  let out = '';
  let i = 0;
  while (i < line.length) {
    const c = line[i];
    if (c === '/' && line[i + 1] === '/') { out += '<span class=tok-cmt>' + esc(line.slice(i)) + '</span>'; break; }
    if (c === '*' && /^\s*\*/.test(line) && i === line.search(/\S/)) {
      out += '<span class=tok-cmt>' + esc(line.slice(i)) + '</span>'; break;
    }
    if (c === '"' || c === "'" || c === '`') {
      let j = i + 1;
      while (j < line.length) { if (line[j] === '\\') { j += 2; continue; } if (line[j] === c) { j++; break; } j++; }
      out += '<span class=tok-str>' + esc(line.slice(i, j)) + '</span>'; i = j; continue;
    }
    let j = i + 1;
    while (j < line.length && line[j] !== '"' && line[j] !== "'" && line[j] !== '`'
      && !(line[j] === '/' && line[j + 1] === '/')) j++;
    out += esc(line.slice(i, j)).replace(JS_KW, m => '<span class=tok-kw>' + m + '</span>');
    i = j;
  }
  return out;
}

// minimal Clojure tokeniser -> highlighted HTML for one line
function hl(line) {
  let out = '';
  let i = 0;
  while (i < line.length) {
    const c = line[i];
    if (c === ';') { out += '<span class=tok-cmt>' + esc(line.slice(i)) + '</span>'; break; }
    if (c === '"') {
      let j = i + 1;
      while (j < line.length) {
        if (line[j] === '\\') { j += 2; continue; }
        if (line[j] === '"') { j++; break; } j++;
      }
      out += '<span class=tok-str>' + esc(line.slice(i, j)) + '</span>'; i = j; continue;
    }
    if (c === ':') {
      let j = i + 1;
      while (j < line.length && /[\w*+!?<>=./-]/.test(line[j])) j++;
      out += '<span class=tok-kw>' + esc(line.slice(i, j)) + '</span>'; i = j; continue;
    }
    let j = i + 1;
    while (j < line.length && line[j] !== ';' && line[j] !== '"' && line[j] !== ':') j++;
    out += esc(line.slice(i, j)); i = j;
  }
  return out;
}

// --- the code card: path, jump-out actions, folded long forms ---------------

function ghUrl(step) {
  if (!MODEL.repo) return null;
  const last = step.line + step.code.split('\n').length - 1;
  return MODEL.repo + '/' + step.file + '#L' + step.line + '-L' + last;
}

// Handled by docs/devtour/devtour.el (org-protocol sub-protocol `devtour`):
// opens the repo-relative file at the line in the running emacs.
const emacsUrl = step =>
  'org-protocol://devtour?file=' + encodeURIComponent(step.file) +
  '&line=' + step.line;

function codeBlock(step) {
  const lines = step.code.split('\n');
  const folded = lines.length > FOLD_OVER && !expanded;
  const shown = folded ? lines.slice(0, FOLD_HEAD) : lines;
  const rows = shown.map((ln, k) => {
    const n = step.line + k;
    const head = k === 0 ? ' head' : '';
    return '<div class="ln' + head + '"><span class=g>' + n +
      '</span><span class=c>' + (step.lang === 'js' ? hlJs(ln) : hl(ln)) + '</span></div>';
  }).join('');
  const gh = ghUrl(step);
  const head =
    '<div class=fhead><span class=p id=copypath title="' + attr(T.copyPath) + '">' +
    esc(step.file) + ':' + step.line + '</span> — <b>' + esc(step.defn) + '</b>' +
    '<span class=sp></span>' +
    '<span class=dim>' + lines.length + ' ' + T.lines + ' · ~' + mins(step.mins) + '</span>' +
    '<a class=act href="' + attr(emacsUrl(step)) + '" title="' + attr(T.emacsHint) + '">' +
    T.openEmacs + '</a>' +
    (gh ? '<a class=act href="' + attr(gh) + '" target=_blank rel=noreferrer>' + T.openGithub + '</a>' : '') +
    '</div>';
  const fold = lines.length > FOLD_OVER
    ? '<div class=fold><button id=foldbtn>' +
    (folded ? T.showAll.replace('%d', lines.length) : T.collapse) + '</button></div>'
    : '';
  return '<div class=card>' + head + '<pre class=code>' + rows + '</pre>' + fold + '</div>';
}

function linkBlock(step) {
  const grp = (label, items) => items?.length
    ? '<div class=grp><span class=lbl>' + label + '</span>' +
    items.map(x => '<a data-gi="' + x.gi + '">' + esc(x.label) + '</a>').join('') + '</div>'
    : '';
  const html = grp(T.seeAlso, step.see) + grp(T.refs, step.refs);
  return html ? '<div class=links>' + html + '</div>' : '';
}

// --- views ------------------------------------------------------------------

function renderIntro() {
  cur = -1;
  document.getElementById('crumb').innerHTML = '';
  const last = load('last');
  const li = last !== null && BY_KEY[last] !== undefined ? BY_KEY[last] : null;
  const resume = li === null ? '' :
    '<div class=resume>' + T.resume + ' <a data-gi="' + li + '">' +
    esc(SPINE[li].block.title) + ' › ' + esc(SPINE[li].step.defn) + '</a> ' +
    '<span class=dim>(' + T.step + ' ' + (li + 1) + ' / ' + SPINE.length + ')</span></div>';
  document.getElementById('stage').innerHTML =
    '<div class=intro>' + md(MODEL.intro || '') +
    '<p class=budget>' + T.budget
      .replace('%s', SPINE.length)
      .replace('%b', MODEL.blocks.filter(b => b.status === 'toured').length)
      .replace('%t', Math.round(MODEL.mins / 60 * 10) / 10) + '</p>' +
    '<p style="margin-top:14px">' + T.introKeys + '</p>' + resume + '</div>';
  document.getElementById('stage').scrollTop = 0;
  paint();
}

function renderStub(b) {
  cur = -1;
  document.getElementById('crumb').innerHTML = '<b>' + esc(b.title) + '</b>';
  document.getElementById('stage').innerHTML =
    '<h2>' + esc(b.title) + '</h2><div class=say>' + md(b.summary || '') + '</div>' +
    '<div class=stub-note>' + T.stub + '<div class=paths>' +
    (b.paths || []).map(p => '<code>' + esc(p) + '</code>').join('') +
    '</div><p style="margin:10px 0 0">' + T.stubAdd + '</p></div>';
  paint();
}

function go(gi, keepExpanded) {
  if (gi !== cur && !keepExpanded) expanded = false;
  cur = gi;
  const { block, step } = SPINE[gi];
  markSeen(step.key);
  store('last', step.key);
  document.getElementById('crumb').innerHTML =
    '<b>' + esc(block.title) + '</b> › ' + T.step + ' ' + step.n + ' / ' + (block.steps || []).length +
    ' — <code class=inl>' + esc(step.defn) + '</code> <span class=dim>~' + mins(step.mins) + '</span>';
  document.getElementById('stage').innerHTML =
    '<div class=say>' + md(step.say) + '</div>' + linkBlock(step) + codeBlock(step);
  document.getElementById('stage').scrollTop = 0;
  paint();
}

// Navigation goes through the URL hash: deep links, working browser Back and
// the tour's own Back button are then the same mechanism. (pushState is
// refused on file:// origins — the hash is not.)
// Only the characters that would break a fragment are escaped, so a shared
// link still reads `#executor/execute`.
const encKey = k => k.replace(/%/g, '%25').replace(/#/g, '%23').replace(/ /g, '%20');

function nav(gi) {
  const key = SPINE[gi].step.key;
  if (decodeURIComponent(location.hash.slice(1)) === key) go(gi);
  else location.hash = '#' + encKey(key);
}

function route() {
  const h = decodeURIComponent(location.hash.slice(1));
  if (!h) return renderIntro();
  if (h.charAt(0) === '!') {                       // #!<block-id> — a stub block
    const b = MODEL.blocks.find(x => x.id === h.slice(1));
    return b ? renderStub(b) : renderIntro();
  }
  const gi = BY_KEY[h];
  if (gi === undefined) return renderIntro();
  go(gi);
}

function paint() {
  document.querySelectorAll('.steps li').forEach(li => {
    const on = +li.dataset.gi === cur;
    li.classList.toggle('on', on);
    li.classList.toggle('seen', seen.has(li.dataset.key));
    // The spine is long enough that walking it with Next scrolls the current
    // step out of the map entirely — keep the highlight in view.
    if (on) li.scrollIntoView({ block: 'nearest' });
  });
  document.querySelectorAll('.blk[data-id]').forEach(el => {
    const c = el.querySelector('.done');
    if (!c) return;
    const b = MODEL.blocks.find(x => x.id === el.dataset.id);
    const done = (b.steps || []).filter(s => seen.has(s.key)).length;
    c.textContent = done + '/' + (b.steps || []).length;
  });
  document.getElementById('pos').textContent =
    cur < 0 ? '' : (T.step + ' ' + (cur + 1) + ' / ' + SPINE.length);
  document.getElementById('prev').disabled = cur <= 0;
  document.getElementById('next').disabled = cur >= SPINE.length - 1;
  document.getElementById('back').disabled = history.length <= 1;
  const pct = SPINE.length ? Math.round(seen.size / SPINE.length * 100) : 0;
  document.querySelector('#bar i').style.width = pct + '%';
  document.getElementById('pnum').textContent = seen.size + '/' + SPINE.length;
}

function buildMap() {
  const map = document.getElementById('map');
  MODEL.blocks.forEach(b => {
    const wrap = document.createElement('div');
    wrap.className = 'blk ' + (b.status === 'toured' ? 'toured' : 'stub');
    wrap.dataset.id = b.id;
    let html = '<div class=blk-h><span class=t>' + esc(b.title) + '</span>' +
      '<span class=s>' + esc(b.summary || '') + '</span>';
    if (b.status === 'toured')
      html += '<span class=meta><span class=done></span> · ~' + mins(b.mins) + '</span>';
    html += '</div>';
    if (b.after?.length)
      html += '<div class=after>' + T.after + ' ' + b.after.map(esc).join(', ') + '</div>';
    if (b.status === 'toured') {
      html += '<ul class=steps>' + (b.steps || []).map(s =>
        '<li data-gi="' + s.gi + '" data-key="' + attr(s.key) + '">' +
        '<span class=nm>' + esc(s.defn) + '</span><span class=tick>✓</span></li>').join('') + '</ul>';
    }
    wrap.innerHTML = html;
    if (b.status !== 'toured')
      wrap.querySelector('.blk-h').onclick = () => { location.hash = '#!' + b.id; };
    wrap.querySelectorAll('.steps li').forEach(li => {
      li.onclick = () => nav(+li.dataset.gi);
    });
    map.appendChild(wrap);
  });
}

// --- search -----------------------------------------------------------------

let hits = [];
let sel = 0;

function snippet(text, q) {
  const i = text.toLowerCase().indexOf(q);
  if (i < 0) return '';
  const from = Math.max(0, i - 40);
  return (from ? '…' : '') + esc(text.slice(from, i)) +
    '<mark>' + esc(text.slice(i, i + q.length)) + '</mark>' +
    esc(text.slice(i + q.length, i + q.length + 60)) + '…';
}

function search(q) {
  q = q.trim().toLowerCase();
  hits = []; sel = 0;
  if (q) {
    const scored = [];
    SPINE.forEach(({ block, step }, gi) => {
      const nm = step.defn.toLowerCase();
      let sc = 0;
      let where = '';
      if (nm.startsWith(q)) { sc = 100; where = 'name'; }
      else if (nm.includes(q)) { sc = 80; where = 'name'; }
      else if ((step.ns + ' ' + step.file).toLowerCase().includes(q)) { sc = 60; where = 'file'; }
      else if (step.say.toLowerCase().includes(q)) { sc = 40; where = 'say'; }
      else if (step.code.toLowerCase().includes(q)) { sc = 20; where = 'code'; }
      if (sc) scored.push({ gi, block, step, sc, where });
    });
    scored.sort((a, b) => b.sc - a.sc || a.gi - b.gi);
    hits = scored.slice(0, 50);
  }
  const res = document.getElementById('res');
  res.innerHTML = hits.length
    ? hits.map((h, i) =>
      '<li data-gi="' + h.gi + '" class="' + (i === 0 ? 'on' : '') + '">' +
      '<div class=h1><b>' + esc(h.step.defn) + '</b> <span class=dim>· ' +
      esc(h.block.title) + '</span></div><div class=h2>' +
      (h.where === 'say' ? snippet(h.step.say, q)
        : h.where === 'code' ? snippet(h.step.code.replace(/\s+/g, ' '), q)
          : esc(h.step.file)) + '</div></li>').join('')
    : '<li class=h2 style="padding:14px">' + (q ? T.noHits : T.searchHint) + '</li>';
  res.querySelectorAll('li[data-gi]').forEach(li => {
    li.onclick = () => { closeOvl(); nav(+li.dataset.gi); };
  });
}

function moveSel(d) {
  if (!hits.length) return;
  sel = (sel + d + hits.length) % hits.length;
  document.querySelectorAll('#res li').forEach((li, i) => {
    li.classList.toggle('on', i === sel);
    if (i === sel) li.scrollIntoView({ block: 'nearest' });
  });
}

function openSearch() {
  document.getElementById('help').hidden = true;
  document.getElementById('find').hidden = false;
  const q = document.getElementById('q');
  q.value = ''; q.focus(); search('');
}

function closeOvl() {
  document.getElementById('find').hidden = true;
  document.getElementById('help').hidden = true;
}

// --- chrome -----------------------------------------------------------------

let toastT = null;
function toast(msg) {
  const el = document.getElementById('toast');
  el.textContent = msg; el.hidden = false;
  clearTimeout(toastT);
  toastT = setTimeout(() => { el.hidden = true; }, 1400);
}

function copyPath() {
  if (cur < 0) return;
  const s = SPINE[cur].step;
  const text = s.file + ':' + s.line;
  const done = () => toast(T.copied + ' ' + text);
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(done, () => fallbackCopy(text, done));
  } else fallbackCopy(text, done);
}

function fallbackCopy(text, done) {
  const ta = document.createElement('textarea');
  ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
  document.body.appendChild(ta); ta.select();
  try { document.execCommand('copy'); done(); } catch (_) { }
  ta.remove();
}

function applyTheme() {
  const t = load('theme');
  if (t) document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
}

function toggleTheme() {
  const now = document.documentElement.dataset.theme
    || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
  const next = now === 'dark' ? 'light' : 'dark';
  store('theme', next); applyTheme();
}

function clearProgress() {
  if (!confirm(T.clearConfirm)) return;
  seen = new Set();
  store('seen', '[]'); store('last', '');
  paint(); toast(T.cleared);
}

document.getElementById('next').onclick = () => { if (cur < SPINE.length - 1) nav(cur + 1); };
document.getElementById('prev').onclick = () => { if (cur > 0) nav(cur - 1); };
document.getElementById('back').onclick = () => history.back();
document.getElementById('btn-find').onclick = openSearch;
document.getElementById('btn-help').onclick = () => {
  document.getElementById('find').hidden = true;
  document.getElementById('help').hidden = false;
};
document.getElementById('btn-theme').onclick = toggleTheme;
document.getElementById('btn-clear').onclick = clearProgress;
document.getElementById('q').oninput = e => search(e.target.value);
document.getElementById('q').onkeydown = e => {
  if (e.key === 'ArrowDown') { e.preventDefault(); moveSel(1); }
  else if (e.key === 'ArrowUp') { e.preventDefault(); moveSel(-1); }
  else if (e.key === 'Enter' && hits[sel]) { const gi = hits[sel].gi; closeOvl(); nav(gi); }
};
document.querySelectorAll('.ovl').forEach(o => {
  o.onclick = e => { if (e.target === o) closeOvl(); };
});

document.addEventListener('keydown', e => {
  if (e.key === 'Escape') return closeOvl();
  if (e.target.tagName === 'INPUT' || e.metaKey || e.ctrlKey || e.altKey) return;
  const k = e.key;
  if (k === '/') { e.preventDefault(); return openSearch(); }
  if (k === '?') { e.preventDefault(); document.getElementById('help').hidden = false; return; }
  if (k === 'ArrowRight' || k === 'j' || k === 'n') {
    if (cur < SPINE.length - 1) nav(cur < 0 ? 0 : cur + 1);
  } else if (k === 'ArrowLeft' || k === 'k' || k === 'p') {
    if (cur > 0) nav(cur - 1);
  } else if (k === 'b') history.back();
  else if (k === 'g') { location.hash = ''; }
  else if (k === 'e' && cur >= 0) { expanded = !expanded; go(cur, true); }
  else if (k === 'c') copyPath();
  else if (k === 'o' && cur >= 0) { location.href = emacsUrl(SPINE[cur].step); }
});

document.getElementById('stage').addEventListener('click', e => {
  const a = e.target.closest('a[data-gi]');
  if (a) { e.preventDefault(); return nav(+a.dataset.gi); }
  if (e.target.id === 'copypath') return copyPath();
  if (e.target.id === 'foldbtn') { expanded = !expanded; go(cur, true); }
});

window.addEventListener('hashchange', route);

applyTheme();
buildMap();
route();
