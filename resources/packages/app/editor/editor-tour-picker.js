// editor-tour-picker.js — the lesson catalogue.
//
// The ordinary catalogue graph filters and presents browser-local display
// facts. This host owns reading history, requirement checks, navigation,
// actions, input focus and DOM lifetime. Neither history nor capability
// signals are sent to a server to render the catalogue.
//
// Completion lives in localStorage next to the in-flight tour state — it is a
// reading history, personal to the browser, not graph data. `:requires` is the
// gate: usually a capability, sometimes a named condition (a plan tier, an
// organization) resolved through REQUIRE_SIGNALS below.
//
// Lessons have EDITIONS (`:version` in the script, bumped when the flow
// changes — see app/tour/fns.edn). The history records which edition the
// reader finished, so a lesson that moved on after they did is told apart
// from one they simply finished: the catalogue chips it "updated", and the
// account menu counts it — together with lessons that were not in the
// catalogue the last time they looked ("new").

// `{id: version finished}` — as JSON. Before editions it was a list of ids;
// that shape is still READ (those lessons were finished when 1 was the only
// edition there was) and written back in the new one at the next change.
const TOUR_DONE_KEY = 'graphden.tour.done';
// The catalogue as the reader last saw it — `{id: version}`. What the menu's
// count is measured against: absent = never looked, and the first look sets
// the baseline without announcing every lesson as new.
const TOUR_SEEN_KEY = 'graphden.tour.seen';

// A lesson's IDENTITY for the reader's history: its `:slug` (the written
// lesson's file name without the number — stable across renumbering), the
// `:id` for a script that has none. Numbers are positions; a lesson inserted
// mid-sequence moves every number after it, and a ✓ keyed by number would
// move with them onto a lesson the reader never took.
function _tourKey(lesson) {
  return lesson ? (lesson.slug || lesson.id || '') : '';
}

// The history used to be keyed by lesson NUMBER. A 2-digit key that names a
// lesson in the current catalogue is translated to that lesson's slug on
// read — best effort: a number can only mean what it means today.
function _tourMigrateKeys(map) {
  const all = (typeof _tourLessons !== 'undefined' && _tourLessons?.lessons) || [];
  if (!map || !all.length) return map;
  const out = new Map();
  for (const [k, v] of map) {
    const byId = /^\d\d$/.test(k) ? all.find((l) => l.id === k) : null;
    const key = byId ? _tourKey(byId) : k;
    if (!out.has(key)) out.set(key, v);
  }
  return out;
}

// A lesson's edition: `:version` from the script, 1 when it says nothing.
function _tourVersionOf(lesson) {
  const v = lesson ? Number(lesson.version) : Number.NaN;
  return (Number.isInteger(v) && v > 0) ? v : 1;
}

function _tourReadMap(key, legacyListVersion) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return legacyListVersion == null
        ? null
        : new Map(parsed.map((id) => [String(id), legacyListVersion]));
    }
    if (parsed && typeof parsed === 'object') {
      return new Map(Object.entries(parsed).map(([id, v]) => {
        const n = Number(v);
        return [id, (Number.isInteger(n) && n > 0) ? n : 1];
      }));
    }
    return null;
  } catch (_) { return null; }
}

function _tourWriteMap(key, map) {
  try {
    if (map?.size) localStorage.setItem(key, JSON.stringify(Object.fromEntries(map)));
    else localStorage.removeItem(key);
  } catch (_) { /* private mode — the catalogue still works, the mark just won't stick */ }
}

// The reading history: `Map key → edition finished` (`_tourKey`).
function _tourDoneMap() {
  return _tourMigrateKeys(_tourReadMap(TOUR_DONE_KEY, 1) || new Map());
}

// The keys alone — what most callers ask ("has the reader finished this?").
function _tourDoneSet() {
  return new Set(_tourDoneMap().keys());
}

function _tourWriteDone(done) {
  _tourWriteMap(TOUR_DONE_KEY, done);
}

// Called by the engine when the last step is reached — with the edition the
// reader just walked, so a later bump is visible as "finished at 1, now 2".
function _tourMarkDone(lesson, version) {
  const key = (typeof lesson === 'string') ? lesson : _tourKey(lesson);
  if (!key) return;
  const done = _tourDoneMap();
  done.set(key, (Number.isInteger(version) && version > 0) ? version : 1);
  _tourWriteDone(done);
}

// Finished, but at an OLDER edition than the script now carries.
function _tourStale(lesson, done) {
  if (lesson?.['text-only?']) return false;
  const at = (done || _tourDoneMap()).get(_tourKey(lesson));
  return at != null && at < _tourVersionOf(lesson);
}

// The ✓ is the reader's claim, so it is theirs to take back — one lesson (a
// skipped-through walk, a lesson from long ago that needs re-reading) or the
// whole history. Answers whether anything changed, so the caller can leave the
// list alone when the reader un-marks what was not marked.
function _tourUnmarkDone(lesson) {
  const key = (typeof lesson === 'string') ? lesson : _tourKey(lesson);
  if (!key) return false;
  const done = _tourDoneMap();
  if (!done.delete(key)) return false;
  _tourWriteDone(done);
  return true;
}

// Everything at once — the count is what the confirmation and the toast say.
function _tourClearDone() {
  const n = _tourDoneMap().size;
  _tourWriteDone(new Map());
  return n;
}

// Record the catalogue as it stands — every listed lesson at its edition.
function _tourWriteSeen(lessons) {
  _tourWriteMap(TOUR_SEEN_KEY,
    new Map((lessons || []).map((l) => [_tourKey(l), _tourVersionOf(l)])));
}

// What changed since the reader last looked at the catalogue —
// `{fresh: [id…], updated: [id…], count}`:
//   fresh   — listed now, not listed then, and not finished (a lesson they
//             took from a deep link without ever opening the catalogue is not
//             news to them);
//   updated — finished at an older edition, and the bump is one they have not
//             seen listed yet. An unread lesson's bump is nobody's news.
// No baseline yet → this look BECOMES it, and nothing is news: to a first-time
// reader the whole catalogue is new, and a count saying so would say nothing.
function _tourNews(lessons) {
  const all = lessons?.lessons || [];
  const seen = _tourMigrateKeys(_tourReadMap(TOUR_SEEN_KEY, null));
  if (!seen) {
    if (all.length) _tourWriteSeen(all);
    return { fresh: [], updated: [], count: 0 };
  }
  const done = _tourDoneMap();
  const fresh = [];
  const updated = [];
  for (const l of all) {
    const v = _tourVersionOf(l);
    const k = _tourKey(l);
    if (!seen.has(k)) {
      if (!done.has(k)) fresh.push(l.id);
    } else if (seen.get(k) !== v && _tourStale(l, done)) {
      updated.push(l.id);
    }
  }
  return { fresh, updated, count: fresh.length + updated.length };
}

// For the account menu: the count on the "Interactive tutorial" row. Fetches
// the scripts if this page has not yet (cached after the first time).
async function gdTourNews() {
  const lessons = (typeof _tourFetchLessons === 'function') ? await _tourFetchLessons() : null;
  return lessons ? _tourNews(lessons) : { fresh: [], updated: [], count: 0 };
}
window.gdTourNews = gdTourNews;

const REQUIRE_SIGNALS = {
  'api-tokens': {
    test: () => !!window.gdAccount?.id && typeof window.graphdenTenancyActive === 'function'
      && window.graphdenTenancyActive(),
    phrase: 'a signed-in account with API token support',
    short: 'account and API tokens',
  },
  'publish-packages': {
    test: () => typeof canPublishPackages === 'function' && canPublishPackages(),
    phrase: 'the publish-packages capability',
    short: 'publish access',
  },
  'package-lifecycle': {
    test: () => !!(window.API?.api_packages_publish && window.API?.api_packages_installed
      && window.API?.api_packages_panel_install && window.API?.api_packages_panel_update
      && window.API?.api_branches && typeof canPublishPackages === 'function'
      && canPublishPackages()),
    phrase: 'the package registry, branches and publish-packages capability',
    short: 'registry and publish access',
  },
  'handler-preview': {
    test: () => typeof window.gdHandlerPreviewAvailable === 'function' && window.gdHandlerPreviewAvailable()
      && typeof window.graphdenHasCap === 'function' && window.graphdenHasCap('manage-apps'),
    phrase: 'an account browser session, an isolated apps domain and manage-apps access',
    short: 'isolated HTML preview and Apps access',
  },
  'temporary-http': {
    test: () => typeof window.gdTemporaryHttpAvailable === 'function' && window.gdTemporaryHttpAvailable(),
    phrase: 'an authenticated owner (accounts or a static token) and a configured public HTTP host',
    short: 'authenticated HTTP publication',
  },
  // The dedicated tier (or a platform / single-tenant instance) — services run
  // on an executor the org owns, which lower plans don't get.
  services: {
    test: () => typeof window.gdServicesManageable === 'function'
             && window.gdServicesManageable(),
    phrase: 'the dedicated plan (or your own instance)',
    short: 'the dedicated plan',
  },
  // The Assets panel edits the frontend every session on the instance loads,
  // so it exists only on a single-tenant deployment — under the cloud tenancy
  // addon it is hidden and its writes are platform-only.
  assets: {
    test: () => !(typeof window.graphdenTenancyActive === 'function'
                  && window.graphdenTenancyActive()),
    phrase: 'a single-tenant instance (your own deployment)',
    short: 'your own instance',
  },
  // This is interface/session availability, not an org-wide write grant.
  // Namespace read/view-impl/write/execute and quota checks remain authoritative
  // in creation preview/apply; the signed-in reader chooses a writable target.
  'personal-ui-graphs': {
    test: () => typeof window.isAuthenticated === 'function' && window.isAuthenticated()
      && typeof window.gdRenderUIComponentCatalog === 'function',
    phrase: 'a signed-in editor with personal UI graph support',
    short: 'signed-in UI graphs',
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

// Can this session run the lesson? → `{allowed, phrase, short}`.
function _tourRequirement(lesson) {
  const need = lesson.requires;
  if (!need) return { allowed: true };
  const signal = REQUIRE_SIGNALS[need];
  const allowed = signal
    ? signal.test()
    : (typeof window.graphdenHasCap === 'function' && window.graphdenHasCap(need));
  return {
    allowed,
    phrase: signal ? signal.phrase : ('the ' + need + ' capability'),
    short: signal ? signal.short : need,
  };
}

// `id · Title` — the one label the catalogue, the resume row and the
// end-of-lesson "next up" buttons all use, so a lesson reads the same
// everywhere it is offered.
function _tourLessonLabel(lesson) {
  return lesson ? (lesson.id + ' · ' + (lesson.title || '')) : '';
}

// What to offer after `lessonId` finishes: `{next, unfinished}`.
//
// `next` is the one that comes NEXT in teaching order — the payload's order is
// the reading order, so this is just the following entry, skipping whatever
// this session cannot run (offering a locked lesson as the obvious next move
// is a dead end, and the reader did not choose it from a catalogue this time).
//
// `unfinished` is the first one after it the reader has NOT done, and is only
// returned when it differs from `next` — someone who took 08 out of order gets
// both "the next one" and "the next NEW one" rather than a single button that
// silently means one of them. With nothing unfinished left ahead, it wraps to
// the earliest unfinished lesson instead of going quiet: at the end of a
// chapter the gap is usually behind you.
function _tourNextUp(lessonId) {
  const all = (typeof _tourLessons !== 'undefined' && _tourLessons)
    ? (_tourLessons.lessons || []) : [];
  if (!all.length) return { next: null, unfinished: null };
  // A text-only lesson has nothing to start — it is read, not walked.
  const runnable = (l) => !l['text-only?'] && _tourRequirement(l).allowed;
  const idx = all.findIndex((l) => l.id === lessonId);
  const after = all.slice(idx + 1).filter(runnable);
  const done = _tourDoneSet();
  const next = after[0] || null;
  const unfinished = after.find((l) => !done.has(_tourKey(l)))
    || all.filter(runnable).find((l) => !done.has(_tourKey(l)) && l.id !== lessonId)
    || null;
  return { next, unfinished: (unfinished && unfinished !== next) ? unfinished : null };
}

let _tourCatalogEpoch = 0;
let _tourCatalogClose = null;

function _tourCatalogLocation() {
  return JSON.stringify([
    typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : null,
    typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null]);
}

function _tourCatalogContext() {
  return JSON.stringify([window.gdPrefOwner || null, _tourCatalogLocation()]);
}

function _tourCatalogAuthIdentity() {
  return JSON.stringify([window.gdAccount?.id || null,
    typeof isAuthenticated === 'function' ? !!isAuthenticated() : null]);
}

window.addEventListener('gd-auth-changed', () => {
  _tourCatalogEpoch++;
  if (_tourCatalogClose) {
    _tourCatalogClose();
    _tourTeardown(true);
  }
});
window.addEventListener('gd-graph-changed', () => {
  if (_tourCatalogClose && !_tourCatalogClose.current()) _tourCatalogClose();
});

async function openTutorialMenu() {
  _tourCatalogClose?.();
  const epoch = ++_tourCatalogEpoch;
  const location = _tourCatalogLocation();
  const authIdentity = _tourCatalogAuthIdentity();
  const openingOwner = window.gdPrefOwner || null;
  const openingPrefsReady = !!window.gdPrefsReady;
  const closePending = () => {
    if (_tourCatalogClose !== closePending) return;
    _tourCatalogEpoch++;
    _tourCatalogClose = null;
    if (window.gdTourCatalogClose === closePending) window.gdTourCatalogClose = null;
  };
  closePending.current = () => false;
  _tourCatalogClose = closePending;
  window.gdTourCatalogClose = closePending;
  let lessons;
  try { lessons = await _tourFetchLessons(); }
  catch (error) { closePending(); throw error; }
  const owner = window.gdPrefOwner || null;
  const firstOwnerLoaded = openingOwner === null && !openingPrefsReady && !!window.gdPrefsReady;
  if (epoch !== _tourCatalogEpoch || location !== _tourCatalogLocation()
    || authIdentity !== _tourCatalogAuthIdentity()
    || (owner !== openingOwner && !firstOwnerLoaded)) {
    closePending();
    return;
  }
  // Initial preference hydration does not change the account; unlike an auth
  // event, it only resolves the owner of the page that requested this menu.
  const context = _tourCatalogContext();
  _tourCatalogClose = null;
  window.gdTourCatalogClose = null;
  if (!lessons || !(lessons.lessons || []).length) {
    if (typeof gdToast === 'function') gdToast('Tutorial unavailable on this deployment');
    return;
  }
  const {pop} = _tourEnsureEls();
  _tourStopFollowing();
  if (typeof _tourTimer !== 'undefined' && _tourTimer) {
    clearInterval(_tourTimer);
    _tourTimer = null;
  }
  _tourSpotHide();
  pop.replaceChildren();
  pop.classList.add('gd-tour-visible');
  _tourCenterPop(pop);
  const returnEl = document.activeElement;
  let disposed = false;
  let graph = null;
  let countsRenderer = null;
  let introRenderer = null;
  let emptyRenderer = null;
  const rows = new Map();
  const headings = [];
  const current = () => !disposed && epoch === _tourCatalogEpoch
    && context === _tourCatalogContext() && pop.isConnected;
  const retire = () => {
    if (disposed) return;
    disposed = true;
    if (_tourCatalogClose === retire) _tourCatalogClose = null;
    if (window.gdTourCatalogClose === retire) window.gdTourCatalogClose = null;
    graph?.dispose();
    countsRenderer?.dispose();
    introRenderer?.dispose();
    emptyRenderer?.dispose();
    for (const entry of rows.values()) entry.bodyRenderer.dispose();
    rows.clear();
    pop.replaceChildren();
    pop.onkeydown = null;
    pop.classList.remove('gd-tour-visible');
  };
  retire.current = current;
  _tourCatalogClose = retire;
  window.gdTourCatalogClose = retire;
  const cancel = () => {
    if (!current()) return;
    retire();
    _tourResume();
    if (returnEl && typeof returnEl.focus === 'function' && document.contains(returnEl)) {
      returnEl.focus();
    }
  };
  pop.onkeydown = e => {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    e.stopPropagation();
    cancel();
  };
  pop.classList.toggle('gd-tour-sheet', _tourNarrow());

  let done = _tourDoneMap();
  const saved = _tourState || _tourLoadState();
  const news = _tourNews(lessons);
  _tourWriteSeen(lessons.lessons);

  const title = document.createElement('div');
  title.className = 'gd-tour-title';
  title.textContent = 'Interactive tutorial';
  const body = document.createElement('div');
  body.className = 'gd-tour-body';
  pop.appendChild(title);
  pop.appendChild(body);
  introRenderer = window.GraphdenRenderer.mount(body, {nodeLimit: 48});

  const counts = document.createElement('div');
  counts.className = 'gd-tour-counts';
  counts.setAttribute('role', 'status');
  pop.appendChild(counts);
  countsRenderer = window.GraphdenRenderer.mount(counts, {nodeLimit: 96});

  const paused = saved?.lessonId
    ? lessons.lessons.find(lesson => lesson.id === saved.lessonId) : null;
  const ended = !!paused && (saved.phase === 'cleanup' || saved.step >= (paused.steps || []).length);
  let resume = null;
  if (paused) {
    resume = _tourBtn('', 'gd-tour-btn-primary gd-tour-btn-resume', () => {
      if (!current() || !lessons.lessons.some(lesson => lesson.id === paused.id)) return;
      retire();
      _tourRestoreSession(saved);
    });
    pop.appendChild(resume);
    if (!ended) pop.appendChild(_tourBtn('End lesson & clean up', 'gd-tour-btn-quiet', () => {
      if (!current() || !lessons.lessons.some(lesson => lesson.id === paused.id)) return;
      retire();
      _tourRestoreSession(saved, true);
    }));
  }

  const filter = document.createElement('input');
  filter.type = 'search';
  filter.className = 'gd-tour-filter';
  filter.placeholder = 'Filter lessons…';
  filter.setAttribute('aria-label', 'Filter lessons');
  pop.appendChild(filter);
  const list = document.createElement('div');
  list.className = 'gd-tour-lesson-list';
  pop.appendChild(list);
  const emptyHost = document.createElement('div');
  emptyRenderer = window.GraphdenRenderer.mount(emptyHost, {nodeLimit: 48});
  const foot = document.createElement('div');
  foot.className = 'gd-tour-foot gd-tour-picker-foot';
  pop.appendChild(foot);

  function nativeRow(lesson) {
    const row = document.createElement('div');
    row.className = 'gd-tour-lesson-row';
    row.dataset.lessonId = lesson.id;
    const textOnly = !!lesson['text-only?'];
    const btn = textOnly ? document.createElement('a') : _tourBtn('', 'gd-tour-btn-primary', () => {
      if (!current() || !lessons.lessons.some(item => item.id === lesson.id)
        || !_tourRequirement(lesson).allowed) return;
      retire();
      _tourChooseLesson(lesson.id);
    });
    if (textOnly) {
      btn.className = 'gd-tour-btn gd-tour-btn-primary gd-tour-btn-text';
      const textUrl = typeof _tourTextUrl === 'function' ? _tourTextUrl(lesson) : null;
      if (textUrl) { btn.href = textUrl; btn.target = '_blank'; btn.rel = 'noopener'; }
    }
    const bodyHost = document.createElement('span');
    btn.appendChild(bodyHost);
    row.appendChild(btn);
    graph.own(bodyHost);
    const readUrl = typeof _tourTextUrl === 'function' ? _tourTextUrl(lesson) : null;
    if (!textOnly && readUrl) {
      const read = _tourBtn('↗', 'gd-tour-btn-quiet gd-tour-read-btn', () => {
        if (!current() || !lessons.lessons.some(item => item.id === lesson.id)) return;
        const url = typeof _tourTextUrl === 'function' ? _tourTextUrl(lesson) : null;
        if (url) window.open(url, '_blank', 'noopener');
      });
      const readLabel = _tourCopy('read-label', 'Read the written lesson {lesson}', {lesson: lesson.id});
      const readSummary = lesson.reads
        ? _tourCopy('read-adds', 'In the text: {adds}', {adds: lesson.reads}) : '';
      read.title = readLabel + (readSummary ? '\n' + readSummary : '');
      read.setAttribute('aria-label', readLabel);
      if (readSummary) read.setAttribute('aria-description', readSummary);
      row.appendChild(read);
    }
    const undo = _tourBtn('↺', 'gd-tour-btn-quiet gd-tour-unmark', () => {
      if (!current() || !lessons.lessons.some(item => item.id === lesson.id)) return;
      if (!_tourUnmarkDone(lesson)) return;
      done = _tourDoneMap();
      renderFoot(false);
      render(filter.value);
      btn.focus();
      _tourSay('Lesson ' + lesson.id + ' marked as not done');
    });
    undo.title = 'Mark lesson ' + lesson.id + ' as not done';
    undo.setAttribute('aria-label', 'Mark lesson ' + _tourLessonLabel(lesson) + ' as not done');
    return {row, btn, undo, bodyRenderer: window.GraphdenRenderer.mount(bodyHost, {nodeLimit: 48})};
  }

  // The shared renderer owns its host attribute and clears it for empty local
  // rules. Restore the catalogue sheet only after each passive body render.
  function renderPassive(renderer, host, tree) {
    renderer.render(tree, []);
    graph.own(host);
  }

  function render(needle) {
    if (!current()) return;
    const facts = lessons.lessons.map(lesson => {
      const need = _tourRequirement(lesson);
      const key = _tourKey(lesson);
      return {id: lesson.id, title: lesson.title || '', chapter: lesson.chapter || '',
        done: done.has(key), stale: _tourStale(lesson, done), fresh: news.fresh.includes(lesson.id),
        allowed: !!need.allowed, 'text-only': !!lesson['text-only?'],
        'need-short': need.short || '', 'done-version': done.get(key) || 0, version: _tourVersionOf(lesson)};
    });
    const input = {lessons: facts, query: needle || '',
      resume: {active: !!paused, ended, id: paused?.id || '', title: paused?.title || '',
        step: saved?.step || 0, steps: (paused?.steps || []).length},
      'text-only-copy': _tourCopy('read-only', 'text only')};
    let model;
    try { model = graph.view(input); }
    catch (error) {
      retire();
      if (typeof gdToast === 'function') gdToast('Tutorial catalogue unavailable');
      return;
    }
    renderPassive(countsRenderer, counts, model.counts);
    renderPassive(introRenderer, body, model.intro);
    if (resume) resume.textContent = model.resumeLabel;
    const desired = [];
    const lessonsById = new Map(lessons.lessons.map(lesson => [lesson.id, lesson]));
    for (const [sectionIndex, section] of model.sections.entries()) {
      if (section.chapter) {
        let heading = headings[sectionIndex];
        if (!heading) {
          heading = document.createElement('div');
          heading.className = 'gd-tour-chapter';
          headings[sectionIndex] = heading;
        }
        heading.textContent = section.chapter;
        desired.push(heading);
      }
      for (const item of section.rows) {
        const lesson = lessonsById.get(item.id);
        let entry = rows.get(item.id);
        if (!entry) {
          entry = nativeRow(lesson);
          rows.set(item.id, entry);
        }
        const need = _tourRequirement(lesson);
        entry.row.className = 'gd-tour-lesson-row'
          + (item['text-only'] ? ' gd-tour-lesson-row-text' : '')
          + (item.fresh ? ' gd-tour-lesson-row-new' : '')
          + (item.done ? ' gd-tour-lesson-row-done' : '')
          + (item.stale ? ' gd-tour-lesson-row-updated' : '');
        entry.btn.classList.toggle('gd-tour-btn-done', item.done);
        entry.btn.classList.toggle('gd-tour-btn-locked', !need.allowed && !lesson['text-only?']);
        if (!lesson['text-only?']) {
          entry.btn.disabled = !need.allowed;
          entry.btn.title = need.allowed ? '' : 'Needs ' + need.phrase + ' — this session does not have it.';
        }
        if (done.has(_tourKey(lesson))) {
          if (entry.undo.parentNode !== entry.row) entry.row.appendChild(entry.undo);
        } else if (entry.undo.parentNode === entry.row) entry.undo.remove();
        renderPassive(entry.bodyRenderer, entry.btn.firstChild, item.tree);
        desired.push(entry.row);
      }
    }
    if (!desired.length && model.empty !== null) {
      renderPassive(emptyRenderer, emptyHost, model.empty);
      desired.push(emptyHost);
    }
    const focused = document.activeElement;
    const retainFocus = focused && desired.some(node => node.contains(focused));
    desired.forEach((node, index) => {
      if (list.children[index] !== node) list.insertBefore(node, list.children[index] || null);
    });
    while (list.children.length > desired.length) list.children[list.children.length - 1].remove();
    if (retainFocus && document.activeElement !== focused && focused.isConnected) focused.focus();
  }

  function renderFoot(confirming) {
    if (!current()) return;
    foot.replaceChildren();
    if (confirming) {
      const ask = document.createElement('span');
      ask.className = 'gd-tour-hint';
      ask.textContent = 'Clear ' + done.size + ' ✓ mark' + (done.size === 1 ? '' : 's') + '?';
      foot.appendChild(ask);
      const yes = _tourBtn('Clear', 'gd-tour-btn-primary gd-tour-clear-confirm', () => {
        if (!current()) return;
        const n = _tourClearDone();
        done = _tourDoneMap();
        renderFoot(false);
        render(filter.value);
        _tourSay(n + ' lesson mark' + (n === 1 ? '' : 's') + ' cleared');
        if (typeof gdToast === 'function') gdToast('Progress cleared — ' + n + ' lesson'
          + (n === 1 ? '' : 's') + ' no longer marked done');
        foot.querySelector('.gd-tour-btn-quiet')?.focus();
      });
      const no = _tourBtn('Keep them', 'gd-tour-btn-quiet', () => {
        if (!current()) return;
        renderFoot(false);
        foot.querySelector('.gd-tour-clear')?.focus();
      });
      foot.appendChild(yes);
      foot.appendChild(no);
      if (typeof yes.focus === 'function') yes.focus();
      return;
    }
    foot.appendChild(_tourBtn('Cancel', 'gd-tour-btn-quiet', cancel));
    if (done.size) foot.appendChild(_tourBtn('Clear progress (' + done.size + ')',
      'gd-tour-btn-quiet gd-tour-clear', () => renderFoot(true)));
  }

  graph = window.gdTourCatalogGraph.mount(() => render(filter.value));
  graph.own(body);
  graph.own(counts);
  graph.own(emptyHost);
  renderFoot(false);
  filter.addEventListener('input', () => render(filter.value));
  filter.addEventListener('keydown', e => {
    if (e.key !== 'Escape' || !filter.value) return;
    e.stopPropagation();
    filter.value = '';
    render('');
  });
  render('');
  if (typeof focusIntoDialog === 'function') focusIntoDialog(pop);
  else if (typeof filter.focus === 'function') filter.focus();
}

window.openTutorialMenu = openTutorialMenu;
