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

function _tourCurrentBranch() {
  // editor-branches keeps its branch getter module-private; the URL param
  // IS the branch context (switchToBranch round-trips through it).
  try { return new URLSearchParams(window.location.search).get('branch'); }
  catch (_) { return null; }
}

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

// --- checks -----------------------------------------------------------------
// Declarative check → predicate over the editor's lexical graph state.
// graphData/lookups are script-scope globals (NOT window.*) — this module is
// concatenated into the same bundle, so the bare identifiers resolve.

function _tourFindFn(name) {
  if (typeof lookups !== 'undefined' && lookups && lookups.fnMap) {
    for (const f of lookups.fnMap.values()) if (f && f.name === name) return f;
  }
  if (typeof graphData !== 'undefined' && graphData && graphData.fns) {
    return graphData.fns.find((f) => f.name === name) || null;
  }
  return null;
}

function _tourCheckPasses(check) {
  if (!check || check.kind === 'manual') return false;
  try {
    switch (check.kind) {
      case 'ns-exists':
        // ROOT namespaces only: `name` is the SEGMENT, not the path, so a
        // nested ns elsewhere (the cloud's landing.tutorial lesson pages)
        // must not false-pass the "create a namespace" step.
        return typeof graphData !== 'undefined' && !!graphData
          && (graphData.namespaces || []).some(
            (n) => n.name === check.name && !n['parent-id']);
      case 'fn-exists':
        return !!_tourFindFn(check.name);
      case 'fn-parent': {
        const fn = _tourFindFn(check.name);
        if (!fn) return false;
        const parents = fn['parent-ids'] || [];
        if (!parents.length) return false;
        // If the parent row isn't in the lazy cache yet, accept any parent —
        // the lesson's instruction was followed structurally.
        return parents.some((pid) => {
          const p = lookups?.fnMap ? lookups.fnMap.get(pid) : null;
          return p ? p.name === check.parent : true;
        });
      }
      case 'binding-bound': {
        // The slot row belongs to the PARENT (slots are inherited);
        // the binding row belongs to the checked fn — so walk the fn's
        // bindings and resolve each slot's name, never slotByFnAndName
        // (which is keyed by the slot-OWNING fn).
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          if (!s || s.name !== check.slot) return false;
          if (b.value != null || b['ref-fn-id']) return true;
          // Sequence slots: the binding row itself carries no value —
          // the content lives in binding-list-item rows.
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length > 0;
        });
      }
      case 'selected': {
        if (typeof selectedFnId === 'undefined' || !selectedFnId) return false;
        const sel = lookups?.fnMap ? lookups.fnMap.get(selectedFnId) : null;
        return !!(sel && sel.name === check.name);
      }
      case 'on-branch': {
        // Branch context IS the URL param; switching reloads the page and
        // the tour resumes from localStorage, so this check re-evaluates on
        // the OTHER side of the reload — which is exactly what it asserts.
        // "main" also matches the no-param (default-branch) case.
        const cur = _tourCurrentBranch();
        return check.name === 'main' ? (!cur || cur === 'main')
                                     : cur === check.name;
      }
      case 'arg-named': {
        // "an arg on the canvas now carries THIS name" — the completion
        // signal for a rename. Neither `binding-bound` nor `bindings-count`
        // can see one: a rename-only binding has no value, no ref and no
        // items, and the client lookups don't carry it either — the new
        // name reaches the client as the layout's edge label. So read the
        // label: the check then passes exactly when the user can SEE the
        // rename, which is what the step asked for.
        return Array.from(document.querySelectorAll('.edge-label-overlay span'))
          .some((sp) => sp.textContent.trim() === check.arg);
      }
      case 'bindings-count': {
        // "at least N of this fn's slots are bound" — order-independent,
        // which is what a step asking for two sibling slots needs: the
        // canvas decides which placeholder sits where, and a lesson must
        // not depend on that.
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        const bound = list.filter((b) => {
          if (b.value != null || b['ref-fn-id']) return true;
          const items = lookups.itemsByBinding?.get(b.id) || [];
          return items.length > 0;
        });
        return bound.length >= (check.count || 1);
      }
      case 'binding-value': {
        // binding-bound, but the literal must equal `check.value`. Compared
        // as TEXT: a JSON literal round-trips through jsonb, so 42 can come
        // back as a number or a string depending on the slot's type.
        const fn = _tourFindFn(check.name);
        if (!fn || typeof lookups === 'undefined' || !lookups) return false;
        const list = (lookups.bindingsByFn?.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap?.get(b['slot-id']);
          return !!(s && s.name === check.slot
                    && b.value != null
                    && String(b.value) === String(check.value));
        });
      }
      case 'dom':
        return !!document.querySelector(check.selector);
      case 'dom-absent':
        // The inverse of `dom` — completes when something DISAPPEARS (a
        // type-error badge cleared by the fixing edit).
        return !document.querySelector(check.selector);
      default:
        return false;
    }
  } catch (_) { return false; }
}

// --- overlay elements --------------------------------------------------------

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
  const close = _tourBtn('End tour', 'gd-tour-btn-quiet', () => _tourEnd());
  head.appendChild(progress);
  head.appendChild(close);

  const title = document.createElement('div');
  title.className = 'gd-tour-title';
  title.textContent = step.title || '';

  const body = document.createElement('div');
  body.className = 'gd-tour-body';
  body.textContent = step.body || '';

  const foot = document.createElement('div');
  foot.className = 'gd-tour-foot';
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
  _tourPosition();
}

function _tourPosition() {
  if (!_tourEls || !_tourState) return;
  const step = _tourStep();
  const { spot, pop } = _tourEls;
  const target = step?.target ? document.querySelector(step.target) : null;
  const rect = target ? target.getBoundingClientRect() : null;
  const visible = rect && rect.width > 0 && rect.height > 0
    && rect.bottom > 0 && rect.top < window.innerHeight;

  if (visible) {
    const pad = 6;
    spot.style.left = (rect.left - pad) + 'px';
    spot.style.top = (rect.top - pad) + 'px';
    spot.style.width = (rect.width + pad * 2) + 'px';
    spot.style.height = (rect.height + pad * 2) + 'px';
    spot.classList.add('gd-tour-visible');

    // Popover: right of the target when it fits, else below, else above.
    const pw = pop.offsetWidth || 360;
    const ph = pop.offsetHeight || 180;
    let left = rect.right + 16;
    let top = rect.top;
    if (left + pw > window.innerWidth - 12) {
      left = Math.min(Math.max(12, rect.left), window.innerWidth - pw - 12);
      top = rect.bottom + 14;
      if (top + ph > window.innerHeight - 12) top = Math.max(12, rect.top - ph - 14);
    }
    top = Math.min(Math.max(12, top), Math.max(12, window.innerHeight - ph - 12));
    // Never cover the thing the step tells you to click: the clamps above
    // can push the popover back over a target near a viewport edge (the
    // branch chip sits top-left), and then the click lands on the popover.
    const overlaps = left < rect.right + 8 && left + pw > rect.left - 8
      && top < rect.bottom + 8 && top + ph > rect.top - 8;
    if (overlaps) {
      const below = rect.bottom + 14;
      const above = rect.top - ph - 14;
      if (below + ph <= window.innerHeight - 12) top = below;
      else if (above >= 12) top = above;
      else left = Math.min(rect.right + 16, Math.max(12, window.innerWidth - pw - 12));
    }
    pop.style.left = left + 'px';
    pop.style.top = top + 'px';
    pop.classList.remove('gd-tour-centered');
  } else {
    spot.classList.remove('gd-tour-visible');
    pop.classList.add('gd-tour-centered');
    pop.style.left = '';
    pop.style.top = '';
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
  // Still out of view (a long namespace list, a short window)? Bring it
  // in — a spotlight below the fold is the same dead end.
  if (step.target && _tourState._scrolledFor !== _tourState.step) {
    const el = document.querySelector(step.target);
    if (el && !_tourTargetVisible(step.target)) {
      _tourState._scrolledFor = _tourState.step;
      try { el.scrollIntoView({block: 'center', inline: 'nearest'}); } catch (_) { /* ignore */ }
    }
  }
  _tourPosition();
  if (step.check && step.check.kind !== 'manual' && _tourCheckPasses(step.check)) {
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
  if (_tourState.step >= lesson.steps.length) _tourEnd();
  else _tourRenderStep();
}

async function _tourDeleteCreatedBranches() {
  // Branches the LESSON created (lesson 08 forks one by hand). Deleted
  // before anything else: a branch with children refuses to delete, so a
  // lesson-made fork must go before the isolation branch it forked from.
  for (const c of (_tourState?.created) || []) {
    if (c.type !== 'branch') continue;
    try {
      await authFetch(API.api_branches_ref(c.name), { method: 'DELETE' });
    } catch (_) { /* already gone / refused — reported by the branch list */ }
  }
}

async function _tourDeleteCreated() {
  const created = (_tourState?.created) || [];
  await _tourDeleteCreatedBranches();
  // fns first — a namespace only deletes once empty.
  //
  // NEWEST FIRST. A lesson that builds a chain creates the target before
  // the fn that points at it (lesson 15: the cell, then the swap that
  // writes to it; lesson 27: the inner fn, then the outer one), and the
  // server refuses to delete a fn something still references — correctly.
  // Deleting in creation order therefore left the FIRST fn of every chain
  // behind; anything that still refuses is retried once below.
  const fns = created.filter((c) => c.type === 'fn').reverse();
  // Resolve through the SEARCH endpoint, not the lexical graph: the client
  // only holds the SELECTED fn's subtree, so a fn the lesson created earlier
  // can be absent from it by cleanup time — and an absent row reads as
  // "already gone", which is how the first fn of every chain survived.
  const idOf = async (name) => {
    try {
      const r = await authFetch(API.api_graph_entities
        + '?scope=search&q=' + encodeURIComponent(name));
      const payload = await r.json();
      return (payload.fns || []).find((f) => f.name === name)?.id || null;
    } catch (_) { return null; }
  };
  const retry = [];
  for (const c of fns) {
    const id = await idOf(c.name);
    if (!id) continue;
    try {
      await authMutate('DELETE', API.api_entities_type_id('fn', id));
    } catch (_) { retry.push(c); }
  }
  // Only what actually refused goes round again, so the happy path pays for
  // one pass — the namespace delete waits behind this loop and the user
  // (and the guard) waits on the namespace.
  for (const c of retry) {
    const id = await idOf(c.name);
    if (!id) continue;
    try { await authMutate('DELETE', API.api_entities_type_id('fn', id)); } catch (_) {}
  }
  for (const c of created) {
    if (c.type !== 'ns') continue;
    // The lesson created this namespace through the editor, so the client
    // already holds it — a full `initGraph()` just to learn one id costs
    // seconds on a large graph. Refresh only if it somehow isn't there.
    let ns = (typeof graphData !== 'undefined' && graphData
      && (graphData.namespaces || []).find((n) => n.name === c.name)) || null;
    if (!ns && typeof initGraph === 'function') {
      try { await initGraph(); } catch (_) {}
      ns = (typeof graphData !== 'undefined' && graphData
        && (graphData.namespaces || []).find((n) => n.name === c.name)) || null;
    }
    if (ns) {
      try { await authMutate('DELETE', API.api_entities_type_id('ns', ns.id)); } catch (_) {}
    }
  }
  if (typeof initGraph === 'function') { try { await initGraph(); } catch (_) {} }
  if (typeof gdToast === 'function') gdToast('Tutorial items deleted');
}

function _tourEnd() {
  // Branch-isolated run (org mode): the WHOLE lesson lives on a tour
  // branch, so the cleanup offer is one decision — delete the branch
  // (full rollback, returns to main) or keep it.
  if (_tourState?.branch && _tourCurrentBranch() === _tourState.branch) {
    const branch = _tourState.branch;
    const { spot, pop } = _tourEnsureEls();
    spot.classList.remove('gd-tour-visible');
    pop.replaceChildren();
    pop.classList.add('gd-tour-visible', 'gd-tour-centered');
    const title = document.createElement('div');
    title.className = 'gd-tour-title';
    title.textContent = 'Delete the tutorial branch?';
    const body = document.createElement('div');
    body.className = 'gd-tour-body';
    body.textContent = 'This lesson ran on its own branch “' + branch
      + '”. Deleting it removes everything the lesson created and returns'
      + ' you to main — the full rollback. Keeping it lets you continue'
      + ' exploring on the branch.';
    const foot = document.createElement('div');
    foot.className = 'gd-tour-foot';
    foot.appendChild(_tourBtn('Delete branch & return', 'gd-tour-btn-primary', async () => {
      try {
        // Children first — a fork the lesson itself made (lesson 08) would
        // otherwise block its parent's delete.
        await _tourDeleteCreatedBranches();
        await authFetch(API.api_branches_ref(branch), { method: 'DELETE' });
      } catch (_) { /* branch stays; still return to main */ }
      _tourTeardown();
      if (typeof switchToBranch === 'function') switchToBranch(null);
    }));
    foot.appendChild(_tourBtn('Keep branch', 'gd-tour-btn-quiet', () => _tourTeardown()));
    pop.appendChild(title);
    pop.appendChild(body);
    pop.appendChild(foot);
    return;
  }
  const created = (_tourState?.created) || [];
  const existing = created.filter((c) => {
    // A branch isn't in graphData (it's a routing context, not a graph
    // row) — offer it unconditionally; the delete is idempotent.
    if (c.type === 'branch') return true;
    if (c.type === 'fn') return !!_tourFindFn(c.name);
    return typeof graphData !== 'undefined' && graphData
      && (graphData.namespaces || []).some((n) => n.name === c.name);
  });
  if (!existing.length) { _tourTeardown(); return; }

  // Cleanup offer — reuse the popover as a small centered dialog.
  const { spot, pop } = _tourEnsureEls();
  spot.classList.remove('gd-tour-visible');
  pop.replaceChildren();
  pop.classList.add('gd-tour-visible', 'gd-tour-centered');

  const title = document.createElement('div');
  title.className = 'gd-tour-title';
  title.textContent = 'Clean up tutorial items?';
  const body = document.createElement('div');
  body.className = 'gd-tour-body';
  body.textContent = 'The tour created: '
    + existing.map((c) => c.type + ' “' + c.name + '”').join(', ')
    + '. Delete them, or keep them to explore? (Deletes are soft — nothing is lost for good.)';
  const foot = document.createElement('div');
  foot.className = 'gd-tour-foot';
  foot.appendChild(_tourBtn('Delete them', 'gd-tour-btn-primary', async () => {
    await _tourDeleteCreated();
    _tourTeardown();
  }));
  foot.appendChild(_tourBtn('Keep & close', 'gd-tour-btn-quiet', () => _tourTeardown()));
  pop.appendChild(title);
  pop.appendChild(body);
  pop.appendChild(foot);
}

function _tourTeardown() {
  _tourState = null;
  _tourSaveState();
  if (_tourTimer) { clearInterval(_tourTimer); _tourTimer = null; }
  if (_tourEls) {
    _tourEls.spot.remove();
    _tourEls.pop.remove();
    _tourEls = null;
  }
  document.removeEventListener('keydown', _tourOnKey);
}

function _tourOnKey(e) {
  if (e.key === 'Escape' && _tourState) _tourEnd();
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
  if (!_tourTimer) _tourTimer = setInterval(_tourTick, TOUR_TICK_MS);
  document.addEventListener('keydown', _tourOnKey);
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

// `:requires` values that are NOT capabilities. Each names a condition the
// editor can already answer for itself, plus the words to say when it fails.
const REQUIRE_SIGNALS = {
  // The dedicated tier (or a platform / single-tenant instance) — services run
  // on an executor the org owns, which lower plans don't get.
  services: {
    test: () => typeof window.gdServicesManageable === 'function'
             && window.gdServicesManageable(),
    phrase: 'the dedicated plan (or your own instance)',
    short: 'the dedicated plan',
  },
  // Anything that only exists once there ARE organizations: the org chip, the
  // per-org editor address, membership.
  org: {
    test: () => typeof window.graphdenTenancyActive === 'function'
             && window.graphdenTenancyActive(),
    phrase: 'an organization workspace',
    short: 'an organization',
  },
};


// Lesson picker — the shell-menu entry point once there is more than one
// lesson. Centered dialog listing every lesson from /api/tour.
async function openTutorialMenu() {
  const lessons = await _tourFetchLessons();
  if (!lessons || !(lessons.lessons || []).length) {
    if (typeof gdToast === 'function') gdToast('Tutorial unavailable on this deployment');
    return;
  }
  const { spot, pop } = _tourEnsureEls();
  spot.classList.remove('gd-tour-visible');
  pop.replaceChildren();
  pop.classList.add('gd-tour-visible', 'gd-tour-centered');
  const title = document.createElement('div');
  title.className = 'gd-tour-title';
  title.textContent = 'Interactive tutorial';
  const body = document.createElement('div');
  body.className = 'gd-tour-body';
  body.textContent = 'Pick a lesson. In an organization workspace the'
    + ' lesson runs on its own branch, so ending it can roll everything'
    + ' back in one step.';
  pop.appendChild(title);
  pop.appendChild(body);
  const list = document.createElement('div');
  list.className = 'gd-tour-foot gd-tour-lesson-list';
  // Lessons arrive in TEACHING order, grouped by `:chapter` — a flat wall of
  // fifteen buttons told the reader nothing about where to start. The heading
  // is emitted when the chapter changes, so the graph's order is the only
  // ordering authority (no sort here).
  let chapter = null;
  for (const lesson of lessons.lessons) {
    if (lesson.chapter && lesson.chapter !== chapter) {
      chapter = lesson.chapter;
      const head = document.createElement('div');
      head.className = 'gd-tour-chapter';
      head.textContent = chapter;
      list.appendChild(head);
    }
    // `:requires` names what the lesson's steps need. Usually that is a
    // CAPABILITY (the org lessons drive panels only an owner/admin can open);
    // a few needs aren't capabilities at all — services are a PLAN tier, and
    // the cross-org lesson only makes sense inside an organization — so those
    // resolve through named signals instead. Offering a lesson to a reader who
    // cannot complete it is a dead end, so it stays listed — seeing what
    // exists is the point of a catalogue — but disabled, with the reason on
    // the row rather than hidden in a tooltip.
    const need = lesson.requires;
    const signal = REQUIRE_SIGNALS[need];
    const allowed = !need
                 || (signal
                     ? signal.test()
                     : (typeof window.graphdenHasCap === 'function'
                        && window.graphdenHasCap(need)));
    const needPhrase = signal ? signal.phrase : ('the ' + need + ' capability');
    const btn = _tourBtn(
      lesson.id + ' · ' + (lesson.title || ''), 'gd-tour-btn-primary',
      () => { if (allowed) startTutorialIsolated(lesson.id); });
    if (!allowed) {
      btn.classList.add('gd-tour-btn-locked');
      btn.disabled = true;
      btn.title = 'Needs ' + needPhrase + ' — this session does not have it.';
      const note = document.createElement('span');
      note.className = 'gd-tour-lesson-note';
      note.textContent = ' — needs ' + (signal ? signal.short : need);
      btn.appendChild(note);
    }
    list.appendChild(btn);
  }
  list.appendChild(_tourBtn('Cancel', 'gd-tour-btn-quiet', () => {
    if (_tourState) _tourRenderStep();
    else _tourTeardown();
  }));
  pop.appendChild(list);
}

window.startTutorial = startTutorial;
window.startTutorialIsolated = startTutorialIsolated;
window.openTutorialMenu = openTutorialMenu;
window.maybeStartTutorial = maybeStartTutorial;
