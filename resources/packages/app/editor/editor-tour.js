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

const TOUR_STORE_KEY = 'graphden.tour';
const TOUR_TICK_MS = 600;

let _tourLessons = null; // fetched /api/tour payload
let _tourState = null;   // {lessonId, step, created: [{type,name}]}
let _tourTimer = null;
let _tourEls = null;     // {spot, pop}

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

function _tourLesson() {
  if (!_tourLessons || !_tourState) return null;
  return (_tourLessons.lessons || []).find((l) => l.id === _tourState.lessonId) || null;
}

function _tourStep() {
  const lesson = _tourLesson();
  if (!lesson) return null;
  return (lesson.steps || [])[_tourState.step] || null;
}

function _tourEnsureEls() {
  if (_tourEls) return _tourEls;
  const spot = document.createElement('div');
  spot.id = 'gd-tour-spot';
  spot.setAttribute('aria-hidden', 'true');
  const pop = document.createElement('div');
  pop.id = 'gd-tour-pop';
  pop.setAttribute('role', 'dialog');
  pop.setAttribute('aria-modal', 'false');
  pop.setAttribute('aria-label', 'Interactive tutorial');
  document.body.appendChild(spot);
  document.body.appendChild(pop);
  _tourEls = { spot, pop };
  return _tourEls;
}

function _tourBtn(label, cls, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'gd-tour-btn' + (cls ? ' ' + cls : '');
  b.textContent = label;
  b.addEventListener('click', onClick);
  return b;
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
  title.textContent = step.title || '';

  const body = document.createElement('div');
  body.className = 'gd-tour-body';
  body.textContent = step.body || '';

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
}

// A phone has no room BESIDE anything. The 360px popover on a 390px screen
// lands on top of the panel the step is pointing at, and the reader cannot
// reach the control the text just named — lesson 01 dead-ends at "click +",
// because + is under the popover. Below this width the popover docks to the
// bottom edge as a sheet (CSS owns that geometry) and the spotlight keeps
// pointing at the target above it.
function _tourNarrow() {
  return window.innerWidth <= 700;
}

// The sheet covers the bottom of a fixed-height scroll panel, and a row under
// it cannot be scrolled up because the panel has nothing below to scroll to.
// Reserve the sheet's height at the bottom of the panels a lesson points at,
// so `scrollIntoView` has somewhere to go. Reset to 0 when the sheet is gone.
function _tourReserveForSheet(px) {
  const root = document.documentElement;
  if (px > 0) {
    root.style.setProperty('--gd-tour-sheet-h', px + 'px');
    document.body.classList.add('gd-tour-sheet-open');
  } else {
    root.style.removeProperty('--gd-tour-sheet-h');
    document.body.classList.remove('gd-tour-sheet-open');
  }
}

// Is the target hidden UNDER the sheet? On a phone that is as unreachable as
// below the fold, and the fix is the same one: scroll it into view.
function _tourUnderSheet(selector) {
  const el = selector ? document.querySelector(selector) : null;
  if (!el || !_tourEls?.pop.classList.contains('gd-tour-visible')) return false;
  const r = el.getBoundingClientRect();
  const sheet = _tourEls.pop.getBoundingClientRect();
  return r.bottom > sheet.top && r.top < sheet.bottom
    && r.right > sheet.left && r.left < sheet.right;
}

// The panel a target sits in — the region the popover must not cover, since
// the reader has to keep using it. Today that is the Explorer sidebar and the
// operations surface; both are wide, scrolling regions whose contents ARE the
// step's subject. Returns a DOMRect or null.
function _tourPanelOf(target) {
  if (!target) return null;
  const panel = target.closest('#side-menu, #gd-operate-panels, #gd-shell-surface');
  if (!panel) return null;
  const r = panel.getBoundingClientRect();
  // Only worth avoiding if it is actually a panel-sized region on screen.
  return (r.width > 160 && r.height > 160) ? r : null;
}


function _tourPosition() {
  if (!_tourEls || !_tourState) return;
  const step = _tourStep();
  const { spot, pop } = _tourEls;
  const narrow = _tourNarrow();
  pop.classList.toggle('gd-tour-sheet', narrow);
  _tourReserveForSheet(narrow && pop.classList.contains('gd-tour-visible')
                       ? pop.offsetHeight : 0);
  const target = step?.target ? document.querySelector(step.target) : null;
  const rect = target ? target.getBoundingClientRect() : null;
  const visible = rect && rect.width > 0 && rect.height > 0
    && rect.bottom > 0 && rect.top < window.innerHeight;

  if (visible && narrow) {
    // Spotlight still anchors; the sheet's own geometry is in the stylesheet.
    const pad = 6;
    spot.style.left = (rect.left - pad) + 'px';
    spot.style.top = (rect.top - pad) + 'px';
    spot.style.width = (rect.width + pad * 2) + 'px';
    spot.style.height = (rect.height + pad * 2) + 'px';
    spot.classList.add('gd-tour-visible');
    pop.classList.remove('gd-tour-centered');
    pop.style.left = '';
    pop.style.top = '';
  } else if (visible) {
    const pad = 6;
    spot.style.left = (rect.left - pad) + 'px';
    spot.style.top = (rect.top - pad) + 'px';
    spot.style.width = (rect.width + pad * 2) + 'px';
    spot.style.height = (rect.height + pad * 2) + 'px';
    spot.classList.add('gd-tour-visible');

    const pw = pop.offsetWidth || 360;
    const ph = pop.offsetHeight || 180;
    // Clear of the PANEL the target lives in, not merely of the target. A
    // step that rings the Explorer's filter box put the popover 16px to the
    // right of a ~250px-wide input — i.e. straight over the list of results
    // the same step tells the reader to click. Thirty-five steps across the
    // tutorial anchor into that sidebar, and every guard walked them anyway
    // because a guard clicks by selector; a person cannot. Where there is
    // room, start after the panel.
    const panel = _tourPanelOf(target);
    // Candidate positions, best first: right of the panel/target, below,
    // above, left, then the bottom corners as last resorts. Each is scored
    // against the target AND every visible floating surface (menus,
    // popovers a step just told the reader to open) — the winner is the
    // first that covers nothing, else the least-covering one. Re-run every
    // tick, so a menu opening mid-step pushes the popover away within
    // ~600ms.
    const startLeft = Math.max(rect.right + 16, panel ? panel.right + 16 : 0);
    const cands = [
      { left: startLeft, top: rect.top },
      { left: rect.left, top: rect.bottom + 14 },
      { left: rect.left, top: rect.top - ph - 14 },
      { left: rect.left - pw - 16, top: rect.top },
      { left: window.innerWidth - pw - 12, top: window.innerHeight - ph - 12 },
      { left: 12, top: window.innerHeight - ph - 12 },
    ];
    const avoid = _tourFloatingRects();
    const best = _tourPickSpot(cands, pw, ph, rect, avoid);
    pop.style.left = best.left + 'px';
    pop.style.top = best.top + 'px';
    pop.classList.remove('gd-tour-centered');
  } else if (step?.target) {
    // The step names a target that is not on screen (not rendered yet, or
    // scrolled away). A CENTERED modal here sat exactly on top of the
    // canvas area the step talks about — dock to a corner instead, scored
    // against the open floating surfaces, and keep re-checking each tick
    // until the target appears.
    spot.classList.remove('gd-tour-visible');
    const pw = pop.offsetWidth || 360;
    const ph = pop.offsetHeight || 180;
    const cands = [
      { left: window.innerWidth - pw - 12, top: window.innerHeight - ph - 12 },
      { left: window.innerWidth - pw - 12, top: 12 },
      { left: 12, top: window.innerHeight - ph - 12 },
    ];
    const best = _tourPickSpot(cands, pw, ph, null, _tourFloatingRects());
    pop.style.left = best.left + 'px';
    pop.style.top = best.top + 'px';
    pop.classList.remove('gd-tour-centered');
  } else {
    spot.classList.remove('gd-tour-visible');
    pop.classList.add('gd-tour-centered');
    pop.style.left = '';
    pop.style.top = '';
  }
}

// Overlap area between a candidate popover box and a DOMRect, with an
// 8px margin around the rect. 0 = clear.
function _tourOverlapArea(left, top, pw, ph, r) {
  const m = 8;
  const w = Math.min(left + pw, r.right + m) - Math.max(left, r.left - m);
  const h = Math.min(top + ph, r.bottom + m) - Math.max(top, r.top - m);
  return (w > 0 && h > 0) ? w * h : 0;
}

// Fixed/absolute, visible, body-level floating UI — the editor's menus and
// popovers. Regions the tour popover must not cover: a step routinely opens
// one ("click ⋯, then ▶ Run") and the reader has to reach it. The tour's own
// elements are excluded; so are full-screen overlays (nothing avoids those).
// Body has a few dozen direct children — a per-tick scan is cheap.
function _tourFloatingRects() {
  const out = [];
  if (!document.body) return out;
  for (const el of document.body.children) {
    if (!(el instanceof HTMLElement)) continue;
    if (_tourEls && (el === _tourEls.pop || el === _tourEls.spot)) continue;
    const cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    if (!(parseInt(cs.zIndex, 10) >= 300)) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 40 || r.height < 24) continue;
    if (r.width > window.innerWidth * 0.9
        && r.height > window.innerHeight * 0.9) continue;
    out.push(r);
  }
  return out;
}

// Pick the popover spot: clamp each candidate into the viewport, hard-weight
// covering the TARGET, soft-weight covering any floating surface; first
// zero-score candidate wins, else the least-covering one.
function _tourPickSpot(cands, pw, ph, targetRect, avoidRects) {
  let best = null;
  for (const c of cands) {
    const left = Math.min(Math.max(12, c.left), Math.max(12, window.innerWidth - pw - 12));
    const top = Math.min(Math.max(12, c.top), Math.max(12, window.innerHeight - ph - 12));
    let score = 0;
    if (targetRect) score += _tourOverlapArea(left, top, pw, ph, targetRect) * 1000;
    for (const r of avoidRects) score += _tourOverlapArea(left, top, pw, ph, r);
    if (score === 0) return { left, top };
    if (!best || score < best.score) best = { left, top, score };
  }
  return best || { left: 12, top: 12 };
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
  // (Lessons 22 / 23, where the lens IS the subject, name no fn in their
  // checks, so they are untouched.)
  if (_tourState._lensClearedFor !== _tourState.step) {
    const wanted = step.check?.name;
    if (wanted && typeof toggleKindLens === 'function' && _tourFnRowHidden(wanted)) {
      _tourState._lensClearedFor = _tourState.step;
      try { toggleKindLens('all'); } catch (_) { /* leave the lens alone */ }
    }
  }
  // Still out of view (a long namespace list, a short window, or — on a
  // phone — under the sheet)? Bring it in: a spotlight the reader cannot
  // reach is the same dead end whichever edge hides it.
  if (step.target && _tourState._scrolledFor !== _tourState.step) {
    const el = document.querySelector(step.target);
    if (el && (!_tourTargetVisible(step.target) || _tourUnderSheet(step.target))) {
      _tourState._scrolledFor = _tourState.step;
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
    _tourEls.spot.classList.remove('gd-tour-visible');
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

async function _tourEnd() {
  // STOP THE POLL FIRST, before any await. The last `_tourAdvance` moves the
  // step index past the end, so the very next tick sees no step and tears the
  // tour down — including `_tourState`. That fired while this function was
  // awaiting the survivors fetch, and the outcome was the worst kind: the
  // dialog rendered anyway, one tick later, over a null state, so "Delete
  // them" deleted NOTHING and still reported "Tutorial items deleted".
  // (Reproduced on the stack: 600ms poll vs a ~1.5s survivors read.)
  if (_tourTimer) { clearInterval(_tourTimer); _tourTimer = null; }
  // Read what the lesson made ONCE, here — the dialog's buttons run much
  // later, and nothing else may be holding the state by then.
  const created = (_tourState?.created) || [];

  // Branch-isolated run (org mode): the WHOLE lesson lives on a tour
  // branch, so the cleanup offer is one decision — delete the branch
  // (full rollback, returns to main) or keep it.
  if (_tourState?.branch && _tourCurrentBranch() === _tourState.branch) {
    const branch = _tourState.branch;
    _tourDialog({
      title: _tourCopy('branch-title', 'Delete the tutorial branch?'),
      body: _tourCopy('branch-body',
                      'This lesson ran on its own branch “{branch}”. Deleting it'
                      + ' removes everything the lesson created and returns you'
                      + ' to main.', { branch }),
      primary: [_tourCopy('branch-confirm', 'Delete branch & return'), async () => {
        let ok = true;
        try {
          // Children first — a fork the lesson itself made (lesson 08) would
          // otherwise block its parent's delete.
          await _tourDeleteCreatedBranches(created);
          const r = await authFetch(API.api_branches_ref(branch), { method: 'DELETE' });
          ok = !!r?.ok;
        } catch (_) { ok = false; }
        _tourTeardown();
        // The hash may name a fn that existed only on the deleted branch —
        // carried to main it selects nothing and the canvas opens silently
        // empty. Drop it before the branch switch reloads.
        try {
          const cur = decodeURIComponent((location.hash || '').replace(/^#/, ''));
          if (created.some((c) => c.type === 'fn' && c.name === cur)) {
            history.replaceState(null, '', location.pathname + location.search);
          }
        } catch (_) { /* keep the hash */ }
        if (typeof switchToBranch === 'function') switchToBranch(null);
        _tourReport(ok, ok ? _tourCopy('branch-done', 'Tutorial branch deleted')
                           : _tourCopy('branch-failed',
                                       'Branch “{branch}” could not be deleted', { branch }));
      }],
      quiet: [_tourCopy('branch-keep', 'Keep branch'), () => _tourTeardown()],
    });
    return;
  }

  // Which of the lesson's creations are still around? Asking the registry
  // makes this async, so the dialog is rendered from the resolved list —
  // a type the deleter knows and this list does not is a row the reader is
  // never told about (that is how a published version went unmentioned).
  const survivors = await _tourSurvivors(created);
  if (!survivors.length) { _tourTeardown(); return; }

  const listOf = (rows) => rows.map((c) => c.type + ' “' + c.name + '”').join(', ');
  _tourDialog({
    title: _tourCopy('cleanup-title', 'Clean up tutorial items?'),
    body: _tourCopy('cleanup-body',
                    'The tour created: {items}. Delete them, or keep them to'
                    + ' explore? (Deletes are soft.)',
                    { items: listOf(survivors) }),
    primary: [_tourCopy('cleanup-confirm', 'Delete them'), async () => {
      const { failed } = await _tourDeleteCreated(created);
      _tourTeardown();
      _tourReport(!failed.length,
                  failed.length
                    ? _tourCopy('cleanup-failed',
                                'Kept: {items} — the server refused',
                                { items: listOf(failed) })
                    : _tourCopy('cleanup-done', 'Tutorial items deleted'));
    }],
    quiet: [_tourCopy('cleanup-keep', 'Keep & close'), () => _tourTeardown()],
  });
}

// The popover doubles as a small centered dialog: title, body, one primary
// and one quiet action. Three end-of-tour prompts built the same DOM by hand
// before this.
function _tourDialog({ title, body, primary, quiet }) {
  const { spot, pop } = _tourEnsureEls();
  spot.classList.remove('gd-tour-visible');
  pop.replaceChildren();
  pop.classList.add('gd-tour-visible', 'gd-tour-centered');
  // Same rule as a step: on a phone this is a bottom sheet, not a 360px box
  // floating in a 390px window.
  pop.classList.toggle('gd-tour-sheet', _tourNarrow());
  const titleEl = document.createElement('div');
  titleEl.className = 'gd-tour-title';
  titleEl.textContent = title;
  const bodyEl = document.createElement('div');
  bodyEl.className = 'gd-tour-body';
  bodyEl.textContent = body;
  const foot = document.createElement('div');
  foot.className = 'gd-tour-foot';
  foot.appendChild(_tourBtn(primary[0], 'gd-tour-btn-primary', primary[1]));
  if (quiet) foot.appendChild(_tourBtn(quiet[0], 'gd-tour-btn-quiet', quiet[1]));
  pop.appendChild(titleEl);
  pop.appendChild(bodyEl);
  pop.appendChild(foot);
}

// Say what actually happened. Every delete here is best-effort, so an
// unconditional success toast over swallowed failures is a lie the reader
// only discovers later, by finding the rows still in their graph.
function _tourReport(ok, message) {
  if (typeof gdToast === 'function') gdToast(message, ok ? undefined : 'error');
}

function _tourTeardown() {
  _tourState = null;
  _tourReserveForSheet(0);
  _tourSaveState();
  if (_tourTimer) { clearInterval(_tourTimer); _tourTimer = null; }
  if (_tourEls) {
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
// helper, was never added, and closing it killed the tour mid-lesson 14.
//
// The list survives as a belt for surfaces that close WITHOUT a keydown
// handler of their own (a menu that closes on blur, an inline input).
const TOUR_ESCAPE_OWNERS = [
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
  if (requested) {
    params.delete('tutorial');
    const qs = params.toString();
    window.history.replaceState(null, '',
      window.location.pathname + (qs ? '?' + qs : '') + window.location.hash);
    // A 2-digit id ("01") or bare number ("1") both resolve.
    const id = requested.length === 1 ? '0' + requested : requested;
    return startTutorial(id);
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
  switchToBranch(branch); // reload; maybeStartTutorial resumes on the branch
  return true;
}


window.startTutorial = startTutorial;
window.startTutorialIsolated = startTutorialIsolated;
window.maybeStartTutorial = maybeStartTutorial;
