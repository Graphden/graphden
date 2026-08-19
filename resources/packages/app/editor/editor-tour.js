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
        return typeof graphData !== 'undefined' && !!graphData
          && (graphData.namespaces || []).some((n) => n.name === check.name);
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
          const p = lookups && lookups.fnMap ? lookups.fnMap.get(pid) : null;
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
        const list = (lookups.bindingsByFn && lookups.bindingsByFn.get(fn.id)) || [];
        return list.some((b) => {
          const s = lookups.slotMap && lookups.slotMap.get(b['slot-id']);
          return s && s.name === check.slot
            && (b.value != null || b['ref-fn-id']);
        });
      }
      case 'dom':
        return !!document.querySelector(check.selector);
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
  const target = step && step.target ? document.querySelector(step.target) : null;
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

function _tourTick() {
  if (!_tourState) return;
  const step = _tourStep();
  if (!step) { _tourTeardown(); return; }
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

async function _tourDeleteCreated() {
  const created = (_tourState && _tourState.created) || [];
  // fns first — a namespace only deletes once empty.
  for (const c of created) {
    if (c.type !== 'fn') continue;
    const fn = _tourFindFn(c.name);
    if (fn) {
      try { await authMutate('DELETE', API.api_entities_type_id('fn', fn.id)); } catch (_) {}
    }
  }
  for (const c of created) {
    if (c.type !== 'ns') continue;
    const ns = (typeof graphData !== 'undefined' && graphData
      && (graphData.namespaces || []).find((n) => n.name === c.name)) || null;
    if (ns) {
      try { await authMutate('DELETE', API.api_entities_type_id('ns', ns.id)); } catch (_) {}
    }
  }
  if (typeof initGraph === 'function') { try { await initGraph(); } catch (_) {} }
  if (typeof gdToast === 'function') gdToast('Tutorial items deleted');
}

function _tourEnd() {
  const created = (_tourState && _tourState.created) || [];
  const existing = created.filter((c) => (c.type === 'fn' ? _tourFindFn(c.name)
    : typeof graphData !== 'undefined' && graphData
      && (graphData.namespaces || []).some((n) => n.name === c.name)));
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
  if (saved && saved.lessonId) {
    return startTutorial(saved.lessonId, saved.step, saved.created);
  }
  return false;
}

window.startTutorial = startTutorial;
window.maybeStartTutorial = maybeStartTutorial;
