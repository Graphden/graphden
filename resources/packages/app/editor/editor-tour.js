// editor-tour.js — the interactive-tutorial overlay: spotlight + step popover.
//
// graph-first-exception: spotlight geometry, popover anchoring and check
// polling bind to live DOM/graph state — client-only lifecycle. The step
// CONTENT (texts, targets, checks) is served from the graph at GET /api/tour
// (app.tour/_tour-lessons), so lessons are editable like any other fn-def.
//
// A step's :check auto-advances the tour by polling the same lexical
// graphData/lookups the editor renders from — the user performs the real
// action, the tour observes the graph. Everything the lesson asks the user
// to create is tracked (step :creates) and offered for deletion at the end
// (soft-deletes — nothing is unrecoverable).
//
// State survives reloads in localStorage (the create-fn flow re-runs
// initGraph, not a page reload, but a mid-lesson F5 must not lose the tour).
//
// Spotlight / popover GEOMETRY is editor-tour-spot.js,
// the end-of-lesson dialogs are editor-tour-end.js (both load right after
// this file). This file is the ENGINE: state + persistence, step rendering,
// the check poll (`_tourTick`), Back / Pause / Escape, and the entry points.

const TOUR_STORE_KEY = 'graphden.tour';
// One lesson id, parked across a page load. Continuing straight into the next
// lesson from a branch-isolated run cannot be a function call: rolling the
// branch back means switching to main, which RELOADS — so the intent has to
// survive the reload the same way the in-flight tour state does.
const TOUR_NEXT_KEY = 'graphden.tour.next';
const TOUR_TICK_MS = 600;

let _tourLessons = null; // fetched /api/tour payload
let _tourState = null;   // {lessonId, step, created: [{type,name}]}
let _tourTimer = null;
let _tourEls = null;     // {dim, spot, pop}

function _tourSaveState() {
  try {
    if (_tourState) localStorage.setItem(TOUR_STORE_KEY, JSON.stringify(_tourState));
    else localStorage.removeItem(TOUR_STORE_KEY);
  } catch (_) { /* private mode — tour still works, just won't survive reload */ }
}

function _tourLoadState() {
  try {
    const raw = localStorage.getItem(TOUR_STORE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (_) { return null; }
}

function _tourQueueNext(lessonId) {
  try { localStorage.setItem(TOUR_NEXT_KEY, lessonId); } catch (_) { /* private mode */ }
}

// Read-and-clear: a queued lesson that survived a failed start would re-fire on
// every load after it, which is a tour nobody asked for.
function _tourTakeNext() {
  try {
    const queued = localStorage.getItem(TOUR_NEXT_KEY);
    if (queued) localStorage.removeItem(TOUR_NEXT_KEY);
    return queued || null;
  } catch (_) { return null; }
}

function _tourLesson() {
  if (!_tourLessons || !_tourState) return null;
  return (_tourLessons.lessons || []).find((l) => l.id === _tourState.lessonId) || null;
}

function _tourStep() {
  const lesson = _tourLesson();
  if (!lesson) return null;
  return (lesson.steps || [])[_tourState.step] || null;
}


function _tourBtn(label, cls, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'gd-tour-btn' + (cls ? ' ' + cls : '');
  b.textContent = label;
  b.addEventListener('click', onClick);
  return b;
}

// Step prose carries two inline marks, so "press this" and "type this" stop
// looking like ordinary quoted words:
//   [[Label]] — a UI element the step wants pressed (button, menu item, key)
//               — rendered as a keycap-style chip;
//   `text`    — text the reader must type — rendered as a monospace chip
//               that copies itself to the clipboard on click, so a JSON
//               payload never has to be retyped from prose.
// Plain text passes through verbatim; a body with no marks renders exactly
// as before.
function _tourRenderBody(el, text) {
  const parts = String(text || '').split(/(\[\[[^\]]+\]\]|`[^`\n]+`)/);
  for (const part of parts) {
    if (!part) continue;
    let m = /^\[\[([^\]]+)\]\]$/.exec(part);
    if (m) {
      const chip = document.createElement('span');
      chip.className = 'gd-tour-ui';
      chip.textContent = m[1];
      el.appendChild(chip);
      continue;
    }
    m = /^`([^`\n]+)`$/.exec(part);
    if (m) {
      el.appendChild(_tourCopyChip(m[1]));
      continue;
    }
    el.appendChild(document.createTextNode(part));
  }
}

function _tourCopyChip(text) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'gd-tour-copy';
  b.title = 'Click to copy';
  b.setAttribute('aria-label', 'Copy to clipboard: ' + text);
  const t = document.createElement('span');
  t.className = 'gd-tour-copy-text';
  t.textContent = text;
  const ic = document.createElement('span');
  ic.className = 'gd-tour-copy-ic';
  ic.setAttribute('aria-hidden', 'true');
  ic.textContent = '⧉';
  b.appendChild(t);
  b.appendChild(ic);
  b.addEventListener('click', async () => {
    const ok = await _tourClipboard(text);
    ic.textContent = ok ? '✓' : '⧉';
    b.classList.toggle('gd-tour-copied', ok);
    setTimeout(() => {
      ic.textContent = '⧉';
      b.classList.remove('gd-tour-copied');
    }, 1400);
  });
  return b;
}

async function _tourClipboard(text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (_) { /* insecure context / permission — fall through */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  } catch (_) { return false; }
}

function _tourRenderStep() {
  const lesson = _tourLesson();
  const step = _tourStep();
  if (!lesson || !step) return;
  const { pop } = _tourEnsureEls();
  pop.replaceChildren();

  const head = document.createElement('div');
  head.className = 'gd-tour-head';
  const progress = document.createElement('span');
  progress.className = 'gd-tour-progress';
  progress.textContent = 'Lesson ' + lesson.id + ' · step '
    + (_tourState.step + 1) + '/' + lesson.steps.length;
  // Two ways out, and they are different promises. "Pause" keeps everything
  // and simply stops showing steps — the state is already in localStorage, so
  // the catalogue offers to continue. "End tour" is the one that asks about
  // deleting what the lesson made. Before this, the only exit was the second,
  // which made stepping away look like a decision about your data.
  head.appendChild(progress);
  head.appendChild(_tourBtn('Pause', 'gd-tour-btn-quiet', () => _tourPause()));
  head.appendChild(_tourBtn('End tour', 'gd-tour-btn-quiet', () => _tourEnd()));

  const title = document.createElement('div');
  title.className = 'gd-tour-title';
  title.id = 'gd-tour-title';
  title.textContent = step.title || '';

  const body = document.createElement('div');
  body.className = 'gd-tour-body';
  body.id = 'gd-tour-body';
  _tourRenderBody(body, step.body);

  const foot = document.createElement('div');
  foot.className = 'gd-tour-foot';
  // A reader who skipped a step, or simply wants to re-read the one before,
  // had no way back: the tour only ever moved forward. Going back never
  // un-does anything — the step's own check decides whether it is satisfied,
  // and an already-done step passes again immediately.
  if (_tourState.step > 0) {
    foot.appendChild(_tourBtn('Back', 'gd-tour-btn-quiet', () => _tourBack()));
  }
  const isManual = !step.check || step.check.kind === 'manual';
  if (isManual) {
    const last = _tourState.step >= lesson.steps.length - 1;
    foot.appendChild(_tourBtn(last ? 'Finish' : 'Next', 'gd-tour-btn-primary',
      () => _tourAdvance(false)));
  } else {
    const hint = document.createElement('span');
    hint.className = 'gd-tour-hint';
    hint.textContent = 'Advances automatically when done';
    foot.appendChild(hint);
    foot.appendChild(_tourBtn('Skip step', 'gd-tour-btn-quiet',
      () => _tourAdvance(true)));
  }

  pop.appendChild(head);
  pop.appendChild(title);
  pop.appendChild(body);
  pop.appendChild(foot);
  pop.classList.add('gd-tour-visible');
  // When this step appeared on screen — the auto-advance below waits a
  // beat past this, so a check that is ALREADY true (satisfied by stale
  // state, or by the same action that finished the previous step) still
  // shows the step instead of silently skipping it. The step counter
  // used to jump 3->5 with nothing readable in between.
  _tourState._shownAt = (typeof performance !== 'undefined' && performance.now)
                        ? performance.now() : Date.now();
  _tourPosition();

  // The popup is rebuilt from scratch on every step (replaceChildren above),
  // so without this a screen-reader user gets no signal that the step
  // changed — the text simply differs the next time they happen to look.
  // Name the dialog by its own title/body, then say the step out loud.
  pop.setAttribute('aria-labelledby', 'gd-tour-title');
  pop.setAttribute('aria-describedby', 'gd-tour-body');
  if (typeof window.gdAnnounce === 'function') {
    window.gdAnnounce((step.title ? step.title + '. ' : '')
                      + (body.textContent || '').trim());
  }
}


// --- lifecycle ----------------------------------------------------------------

// Is the step's target actually on screen? Same predicate `_tourPosition`
// uses to decide between an anchored spotlight and a centered modal — a
// zero-sized (collapsed rail) or off-screen element counts as invisible.
function _tourTargetVisible(selector) {
  const el = selector ? document.querySelector(selector) : null;
  if (!el) return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0
    && r.bottom > 0 && r.top < window.innerHeight
    && r.right > 0 && r.left < window.innerWidth;
}

// A step whose action passes through transient UI (a ⋯ menu, the
// literal-vs-ref chooser, the fn picker) may carry `:targets` — an ordered
// selector chain, one entry per stage. The spotlight follows the DEEPEST
// stage currently on screen: "click ⋯, then ▶ Run, then Run" rings the menu
// item the moment the menu opens, and the Run button once the popover
// renders. Stages past the first are best-effort — a stale selector just
// leaves the ring on the previous stage (the e2e guard pins `:target`).
function _tourEffTarget(step) {
  const chain = step?.targets;
  if (Array.isArray(chain)) {
    for (let i = chain.length - 1; i >= 0; i--) {
      if (_tourTargetVisible(chain[i])) return _tourSearchUpgrade(step, chain[i]);
    }
  }
  return _tourSearchUpgrade(step, step?.target || null);
}

// A search-and-pick step ("type `x` in the filter, click the row") anchors
// on the filter INPUT — and the scrim then dimmed the very result list the
// reader was told to read, so the pick happened in the dark. The row the
// step wants is already named by its own check (the fn it waits to see
// selected, or the parent it waits to see extended), so the moment that row
// is rendered the ring moves ONTO it — and falls back to the input whenever
// further typing filters it away again. Re-resolved every tick, so the ring
// follows the row as the list re-renders under the reader's keystrokes.
// (While the ring is still on the input, `_tourPosition` widens the lit
// hole over the result list — the second half of the same fix.)
function _tourWantedRowSel(step) {
  const check = step?.check;
  const name = check?.kind === 'selected' ? check.name
    : check?.kind === 'fn-parent' ? check.parent : null;
  if (!name || typeof _tourFindFn !== 'function') return null;
  const fn = _tourFindFn(name);
  return fn?.id
    ? '#entity-list .entity-item[data-fn-id="' + fn.id + '"]' : null;
}

function _tourSearchUpgrade(step, sel) {
  const el = sel ? document.querySelector(sel) : null;
  if (el?.id !== 'search-input') return sel;
  const rowSel = _tourWantedRowSel(step);
  return (rowSel && _tourTargetVisible(rowSel)) ? rowSel : sel;
}


function _tourTick() {
  if (!_tourState) return;
  const step = _tourStep();
  if (!step) { _tourTeardown(); return; }
  // A sidebar-anchored step is unreachable while the Explorer is
  // collapsed (narrow viewports default to collapsed) — expand it once
  // per step so the spotlight has something to point at. "Unreachable"
  // is not only ABSENT: a collapsed Explorer can keep its input in the
  // DOM at zero size, which left the step centered with no spotlight
  // while the text said "click in the Explorer".
  if (step.target && _tourState._expandedFor !== _tourState.step
      && document.body.classList.contains('sidebar-collapsed')
      && !_tourTargetVisible(step.target)
      && typeof toggleCollapsed === 'function') {
    _tourState._expandedFor = _tourState.step;
    try { toggleCollapsed(false); } catch (_) { /* stay collapsed */ }
  }
  // A LENS the reader left on can hide the very fn the step names. The row
  // is in the DOM, `hidden`, and the step's check will never pass — the
  // popover just says "advances automatically when done" forever. Same dead
  // end as a collapsed Explorer, same treatment: clear the lens once per
  // step, and only when the fn this step is waiting for is the one hidden.
  // (Lessons 18 / 23, where the lens IS the subject, name no fn in their
  // checks, so they are untouched.)
  // The row a step needs is the fn its check NAMES — or, for an
  // "extend X" step (`fn-parent`), the PARENT the reader must find first:
  // tour 15's "click const, then Extend" was unreachable with the tests
  // lens left on by tour 14, because the child did not exist yet.
  if (_tourState._lensClearedFor !== _tourState.step) {
    const check = step.check;
    const wanted = check?.kind === 'fn-parent' ? check.parent : check?.name;
    if (wanted && typeof toggleKindLens === 'function' && _tourFnRowHidden(wanted)) {
      _tourState._lensClearedFor = _tourState.step;
      try { toggleKindLens('all'); } catch (_) { /* leave the lens alone */ }
    }
  }
  // Still out of view (a long namespace list, a short window, or — on a
  // phone — under the sheet)? Bring it in: a spotlight the reader cannot
  // reach is the same dead end whichever edge hides it.
  // Keyed by step AND selector: a `:targets` chain re-earns its one scroll
  // when the spotlight advances to a deeper stage mid-step.
  const effSel = _tourEffTarget(step);
  if (effSel && _tourState._scrolledFor !== _tourState.step + ':' + effSel) {
    const el = document.querySelector(effSel);
    if (el && (!_tourTargetVisible(effSel) || _tourUnderSheet(effSel))) {
      _tourState._scrolledFor = _tourState.step + ':' + effSel;
      try { el.scrollIntoView({block: 'center', inline: 'nearest'}); } catch (_) { /* ignore */ }
    }
  }
  _tourPosition();
  const now = (typeof performance !== 'undefined' && performance.now)
              ? performance.now() : Date.now();
  const dwellOk = now - (_tourState._shownAt || 0) >= 900;
  if (dwellOk && step.check && step.check.kind !== 'manual'
      && _tourCheckPasses(step.check)) {
    if (typeof gdToast === 'function') gdToast('Step complete ✓');
    _tourAdvance(false);
  }
}

function _tourAdvance(skipped) {
  const lesson = _tourLesson();
  const step = _tourStep();
  if (!lesson || !step) return;
  if (step.creates && !skipped) {
    const dup = _tourState.created.some(
      (c) => c.type === step.creates.type && c.name === step.creates.name);
    if (!dup) _tourState.created.push(step.creates);
  }
  _tourState.step += 1;
  _tourSaveState();
  _tourCount('step', lesson.id, _tourState.step);
  if (_tourState.step >= lesson.steps.length) {
    // Reaching the last step IS finishing it, whatever the reader decides
    // about the rows afterwards — the catalogue's ✓ marks reading, not
    // cleanup.
    if (typeof _tourMarkDone === 'function') _tourMarkDone(lesson.id);
    _tourCount('finished', lesson.id, _tourState.step);
    _tourEnd();
  } else {
    _tourRenderStep();
  }
}

// Step back one. The checks are stateless predicates over the graph, so a
// re-entered step re-evaluates on the next tick; nothing is rolled back.
function _tourBack() {
  if (!_tourState || _tourState.step <= 0) return;
  _tourState.step -= 1;
  _tourSaveState();
  _tourRenderStep();
}

// What makes a RENDERED step live: the poll that advances it and the key
// handler that ends it. Re-rendering alone produced a step that said
// "Advances automatically when done" while nothing polled — the state a
// reader reached by pausing and then dismissing the catalogue.
function _tourArm() {
  if (!_tourTimer) _tourTimer = setInterval(_tourTick, TOUR_TICK_MS);
  document.removeEventListener('keydown', _tourOnKey);
  document.addEventListener('keydown', _tourOnKey);
}

// Show a tour that is still in memory but no longer running.
function _tourResume() {
  if (!_tourState) { _tourTeardown(); return; }
  _tourRenderStep();
  _tourArm();
}

// Stop showing steps, keep the state. `maybeStartTutorial` resumes it on the
// next load, and the catalogue offers "Continue …" right away.
function _tourPause() {
  const lesson = _tourLesson();
  if (_tourTimer) { clearInterval(_tourTimer); _tourTimer = null; }
  if (_tourEls) {
    _tourSpotHide();
    _tourEls.pop.classList.remove('gd-tour-visible');
  }
  _tourReserveForSheet(0);
  document.removeEventListener('keydown', _tourOnKey);
  if (typeof gdToast === 'function') {
    gdToast(_tourCopy('paused', 'Lesson {lesson} paused — continue it from the'
                      + ' account menu', { lesson: lesson ? lesson.id : '' }));
  }
}

// Prose for the end-of-tour prompts comes from the same payload the steps do
// (`:copy` in app.tour/_tour-lessons) — the only reason it ever lived here is
// that it hangs off no single step. `{placeholder}` slots are filled by the
// caller.
function _tourCopy(key, fallback, vars) {
  const raw = _tourLessons?.copy?.[key] || fallback;
  return Object.entries(vars || {}).reduce(
    (text, [k, v]) => text.split('{' + k + '}').join(v), raw);
}


function _tourTeardown() {
  _tourState = null;
  _tourReserveForSheet(0);
  _tourSaveState();
  if (_tourTimer) { clearInterval(_tourTimer); _tourTimer = null; }
  if (_tourEls) {
    _tourEls.dim.remove();
    _tourEls.spot.remove();
    _tourEls.pop.remove();
    _tourEls = null;
  }
  document.removeEventListener('keydown', _tourOnKey);
}

// Escape ends the tour — but ONLY when it is the topmost thing on screen.
// Every dialog the lessons ask the reader to open treats Escape as "close
// me", and ending the whole lesson because someone dismissed a dialog is a
// trap: the step said "click ⬆, fill it in, close it", and closing it the
// obvious way threw the tour away.
//
// The rule is now "was this key already consumed?" — every handler that
// closes something on Escape calls `preventDefault` to say so (Escape has no
// default action, so the call means exactly that and nothing else). The old
// rule was a LIST of dismissible selectors, and a list of other people's
// surfaces goes stale: the Packages panel shipped through the shared popover
// helper, was never added, and closing it killed the tour mid-lesson 29.
//
// The list survives as a belt for surfaces that close WITHOUT a keydown
// handler of their own (a menu that closes on blur, an inline input).
const TOUR_ESCAPE_OWNERS = [
  // The shortcuts overlays register their OWN keydown lazily (on first
  // open) — AFTER the tour armed its listener, so their preventDefault
  // fires too late in the document order to be seen here.
  '#gd-cheatsheet.visible',
  '.gd-which-key',
  '#gd-nspub-pop',
  '.fn-picker-popover',
  '.arg-value-edit-popover',
  '.row-actions-popover',
  '.create-menu',
  '.inline-input',
  '.gd-pop',                        // context-bar popovers (packages, workspace)
  '#gd-asset-editor .gd-asset-diff',
].join(', ');

function _tourOnKey(e) {
  if (e.key !== 'Escape' || !_tourState) return;
  if (e.defaultPrevented) return;
  if (document.querySelector(TOUR_ESCAPE_OWNERS)) return;
  _tourEnd();
}

// --- the funnel ---------------------------------------------------------------
// Three events per lesson — started, one per advance, finished — bumped into
// the process counters `/metrics` already exposes. Twenty-five lessons and no
// way to know where a reader stops was the gap; a Prometheus scrape turns
// these into the series that answers it.
//
// Fire-and-forget by construction: a lesson must never wait on, or fail
// because of, a metric. Nothing identifying is sent — a two-digit id, a step
// index, one of three words.
function _tourCount(event, lessonId, step) {
  if (!lessonId || !(window.API && API.api_tour_progress)) return;
  try {
    authFetch(API.api_tour_progress, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lesson: lessonId, event, step: step || 0 }),
    }).catch(() => {});
  } catch (_) { /* a metric is not worth a broken step */ }
}


async function _tourFetchLessons() {
  if (_tourLessons) return _tourLessons;
  if (!(window.API && API.api_tour)) return null;
  try {
    const r = await authFetch(API.api_tour);
    if (!r.ok) return null;
    _tourLessons = await r.json();
    return _tourLessons;
  } catch (_) { return null; }
}

// Entry point — shell menu and ?tutorial=NN both land here.
async function startTutorial(lessonId, resumeStep, resumeCreated) {
  const lessons = await _tourFetchLessons();
  if (!lessons) {
    if (typeof gdToast === 'function') gdToast('Tutorial unavailable on this deployment');
    return false;
  }
  const lesson = (lessons.lessons || []).find((l) => l.id === lessonId)
    || (lessons.lessons || [])[0];
  if (!lesson) return false;
  // A tour's steps anchor into the BUILD surface (the Explorer, the
  // canvas); lessons that need another surface tell the reader to open
  // it. Starting — or resuming after a branch-switch reload — while an
  // '#@organization'-style deep link holds another surface open left
  // every step's target buried under that surface (lesson 14 ends on
  // Organization; the next lesson then dead-ended on "click + New
  // namespace").
  if (typeof gdShellSurface === 'function'
      && document.body.getAttribute('data-surface') !== 'build') {
    gdShellSurface('build');
  }
  _tourState = {
    lessonId: lesson.id,
    step: Math.min(resumeStep || 0, lesson.steps.length - 1),
    created: resumeCreated || [],
  };
  {
    const cur = _tourCurrentBranch();
    if (cur && /^tutorial-/.test(cur)) _tourState.branch = cur;
  }
  _tourSaveState();
  _tourEnsureEls();
  _tourRenderStep();
  _tourArm();
  // Resuming is not a new start: counting it would inflate the denominator
  // every time a reader reloads mid-lesson.
  if (!resumeStep) _tourCount('started', lesson.id, 0);
  return true;
}

// Boot hook — called by editor-main after initGraph resolves. Starts a tour
// for ?tutorial=NN (param stripped, survives the ?demo=1 reload), else
// resumes a mid-lesson tour from localStorage.
async function maybeStartTutorial() {
  const params = new URLSearchParams(window.location.search);
  const requested = params.get('tutorial');
  // Read-and-clear ALWAYS, even when something else wins this load: a queued
  // lesson left in storage would open on some unrelated later visit.
  const queued = _tourTakeNext();
  if (requested) {
    params.delete('tutorial');
    const qs = params.toString();
    window.history.replaceState(null, '',
      window.location.pathname + (qs ? '?' + qs : '') + window.location.hash);
    // A 2-digit id ("01") or bare number ("1") both resolve.
    const id = requested.length === 1 ? '0' + requested : requested;
    return startTutorial(id);
  }
  // "Start the next lesson" from the end of a branch-isolated run: the
  // rollback switched to main, which reloaded the page, and this is the other
  // side of that. Isolated again — the next lesson gets its own branch.
  if (queued) {
    if (typeof gdToast === 'function') gdToast('Starting lesson ' + queued + '…');
    return startTutorialIsolated(queued);
  }
  const saved = _tourLoadState();
  if (saved?.lessonId) {
    return startTutorial(saved.lessonId, saved.step, saved.created);
  }
  return false;
}

// Org-mode entry: run the lesson on its OWN branch — create
// tutorial-<lesson>-<suffix> off main, switch (the reload resumes the
// saved tour state on the branch), and the end-of-tour dialog offers
// branch deletion = full rollback. Falls back to a plain in-place tour
// when branch creation is unavailable (401/403/older deploys).
async function startTutorialIsolated(lessonId) {
  const lessons = await _tourFetchLessons();
  if (!lessons) {
    if (typeof gdToast === 'function') gdToast('Tutorial unavailable on this deployment');
    return false;
  }
  const canBranch = window.API && API.api_branches
    && typeof switchToBranch === 'function';
  const onMain = canBranch && !_tourCurrentBranch();
  // A lesson that MANAGES branches itself (lesson 20) opts out of the
  // scratch-branch isolation — double-wrapping broke its own "main
  // never saw it" beat and leaked the scratch branch.
  const lesson = (lessons.lessons || []).find((l) => l.id === lessonId);
  if (lesson?.['in-place']) return startTutorial(lessonId);
  if (!canBranch || !onMain) return startTutorial(lessonId);
  const branch = 'tutorial-' + lessonId + '-'
    + Math.random().toString(36).slice(2, 6);
  try {
    const r = await authFetch(API.api_branches, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: branch, 'base-branch-id': 'main' }),
    });
    const bodyJson = await r.json().catch(() => ({}));
    if (!r.ok || bodyJson.ok === false) throw new Error('branch create failed');
  } catch (_) {
    if (typeof gdToast === 'function') gdToast('Starting in place (no branch)');
    return startTutorial(lessonId);
  }
  _tourState = { lessonId, step: 0, created: [], branch };
  _tourSaveState();
  // switchToBranch preserves the hash — drop a surface deep link
  // (#@organization etc.) so the reload resumes the tour on Build,
  // not buried under the previous surface.
  if (/^#@/.test(location.hash)) {
    try {
      history.replaceState(null, '', location.pathname + location.search);
    } catch (_) { /* keep the hash */ }
  }
  switchToBranch(branch); // reload; maybeStartTutorial resumes on the branch
  return true;
}


window.startTutorial = startTutorial;
window.startTutorialIsolated = startTutorialIsolated;
window.maybeStartTutorial = maybeStartTutorial;
