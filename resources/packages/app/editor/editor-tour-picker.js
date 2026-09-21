// editor-tour-picker.js — the lesson catalogue.
//
// Twenty-five lessons in five chapters is a library, not a menu, so the list
// answers three questions before it lists anything: where did I stop, what
// have I already done, and which of these can this session even run.
//
// graph-first-exception: this list is a function of the graph payload the
// engine already fetched AND two things only the browser knows — the reading
// history in localStorage and the capability signals the session carries. A
// server partial would have to be told the reader's own history to render
// their own catalogue, which is worse on every axis including privacy.
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
  // The dedicated tier (or a platform / single-tenant instance) — services run
  // on an executor the org owns, which lower plans don't get.
  services: {
    test: () => typeof window.gdServicesManageable === 'function'
             && window.gdServicesManageable(),
    phrase: 'the dedicated plan (or your own instance)',
    short: 'the dedicated plan',
  },
  // Lesson 38 names the editor's OWN web-server as the service it calls. A
  // cloud organization has no such service of its own (the platform's row is
  // not the tenant's to resolve — the run answers an internal error), so the
  // tour needs both services and a single-tenant instance.
  'own-web-server': {
    test: () => typeof window.gdServicesManageable === 'function'
             && window.gdServicesManageable()
             && !(typeof window.graphdenTenancyActive === 'function'
                  && window.graphdenTenancyActive()),
    phrase: 'your own instance (a cloud organization has no web-server of its own to name)',
    short: 'your own instance',
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
  const runnable = (l) => _tourRequirement(l).allowed;
  const idx = all.findIndex((l) => l.id === lessonId);
  const after = all.slice(idx + 1).filter(runnable);
  const done = _tourDoneSet();
  const next = after[0] || null;
  const unfinished = after.find((l) => !done.has(_tourKey(l)))
    || all.filter(runnable).find((l) => !done.has(_tourKey(l)) && l.id !== lessonId)
    || null;
  return { next, unfinished: (unfinished && unfinished !== next) ? unfinished : null };
}

async function openTutorialMenu() {
  const lessons = await _tourFetchLessons();
  if (!lessons || !(lessons.lessons || []).length) {
    if (typeof gdToast === 'function') gdToast('Tutorial unavailable on this deployment');
    return;
  }
  const { pop } = _tourEnsureEls();
  _tourSpotHide();   // both spotlight layers — the ring AND the scrim
  pop.replaceChildren();
  pop.classList.add('gd-tour-visible');
  _tourCenterPop(pop);
  // The catalogue is a dialog: focus goes in when it opens and back to the
  // menu chip (or wherever the reader was) when it closes; Escape is the
  // Cancel button — consumed, so the tour engine's "Escape ends the
  // lesson" never sees it (a paused lesson resumes, as Cancel does).
  const returnEl = document.activeElement;
  const cancel = () => {
    _tourResume();
    if (returnEl && typeof returnEl.focus === 'function' && document.contains(returnEl)) {
      returnEl.focus();
    }
  };
  pop.onkeydown = (e) => {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    e.stopPropagation();
    cancel();
  };
  // On a phone the catalogue is a bottom sheet like every other tour surface
  // — `_tourPosition` sets this while a lesson RUNS, and the catalogue can be
  // opened without one.
  pop.classList.toggle('gd-tour-sheet', _tourNarrow());

  let done = _tourDoneMap();
  const saved = _tourState || _tourLoadState();
  // What is news THIS time — measured before the look is recorded, so the
  // chips show once, on the look that answers the menu's count.
  const news = _tourNews(lessons);
  _tourWriteSeen(lessons.lessons);

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

  // Where the reader stands, in one line: finished / runnable here / listed.
  // "Available" is the session's, not the reader's — a locked lesson is still
  // listed (that is the point of a catalogue) but cannot be counted as a move.
  const counts = document.createElement('div');
  counts.className = 'gd-tour-counts';
  counts.setAttribute('role', 'status');
  pop.appendChild(counts);
  const renderCounts = () => {
    counts.replaceChildren();
    const all = lessons.lessons;
    const finished = all.filter((l) => done.has(_tourKey(l))).length;
    const available = all.filter((l) => _tourRequirement(l).allowed).length;
    const seg = (text, cls) => {
      const el = document.createElement('span');
      el.className = 'gd-tour-count' + (cls ? ' ' + cls : '');
      el.textContent = text;
      counts.appendChild(el);
    };
    seg(finished + ' done', 'gd-tour-count-done');
    seg(available + ' available', 'gd-tour-count-available');
    seg(all.length + ' lesson' + (all.length === 1 ? '' : 's'), 'gd-tour-count-total');
    // "new" is this look's news; "updated" is STATE — every ✓ the script has
    // since moved past, chipped on its row until the lesson is finished again.
    const stale = all.filter((l) => _tourStale(l, done)).length;
    if (news.fresh.length) seg(news.fresh.length + ' new', 'gd-tour-count-new');
    if (stale) seg(stale + ' updated', 'gd-tour-count-updated');
  };
  renderCounts();

  // An unfinished lesson is the single most likely reason the catalogue is
  // open at all, so it goes first, with the step count it stopped at — the
  // state was always resumable, it was just invisible.
  if (saved?.lessonId) {
    const paused = (lessons.lessons || []).find((l) => l.id === saved.lessonId);
    if (paused) {
      const resume = _tourBtn(
        'Continue ' + _tourLessonLabel(paused)
        + ' — step ' + ((saved.step || 0) + 1) + '/' + (paused.steps || []).length,
        'gd-tour-btn-primary gd-tour-btn-resume',
        () => startTutorial(paused.id, saved.step, saved.created));
      pop.appendChild(resume);
    }
  }

  // The filter is a plain input, not a search endpoint: the catalogue is
  // already in memory and typing must not wait on a round trip.
  const filter = document.createElement('input');
  filter.type = 'search';
  filter.className = 'gd-tour-filter';
  filter.placeholder = 'Filter lessons…';
  filter.setAttribute('aria-label', 'Filter lessons');
  pop.appendChild(filter);

  const list = document.createElement('div');
  list.className = 'gd-tour-lesson-list';
  pop.appendChild(list);

  // Lessons arrive in TEACHING order, grouped by `:chapter` — a flat wall of
  // buttons told the reader nothing about where to start. The heading is
  // emitted when the chapter changes, so the graph's order is the only
  // ordering authority (no sort here).
  const render = (needle) => {
    list.replaceChildren();
    const q = (needle || '').trim().toLowerCase();
    let chapter = null;
    let shown = 0;
    for (const lesson of lessons.lessons) {
      const label = _tourLessonLabel(lesson);
      if (q && !label.toLowerCase().includes(q)
          && !(lesson.chapter || '').toLowerCase().includes(q)) continue;
      shown++;
      if (lesson.chapter && lesson.chapter !== chapter) {
        chapter = lesson.chapter;
        const head = document.createElement('div');
        head.className = 'gd-tour-chapter';
        head.textContent = chapter;
        list.appendChild(head);
      }
      // Offering a lesson to a reader who cannot complete it is a dead end,
      // so it stays listed — seeing what exists is the point of a catalogue —
      // but disabled, with the reason on the row rather than in a tooltip.
      const need = _tourRequirement(lesson);
      const btn = _tourBtn(label, 'gd-tour-btn-primary',
                           () => { if (need.allowed) startTutorialIsolated(lesson.id); });
      // The row is a CONTAINER, not just the button: a done lesson carries a
      // second control (take the ✓ off), and a control nested inside a button
      // is neither valid markup nor reachable by keyboard.
      const row = document.createElement('div');
      row.className = 'gd-tour-lesson-row';
      row.setAttribute('data-lesson-id', lesson.id);
      row.appendChild(btn);
      // A lesson the reader's last look did not list is chipped once, on the
      // look that follows the menu's count; a lesson they finished at an
      // older edition stays chipped until they finish it again — the ✓ is
      // still theirs, the chip says what it no longer vouches for.
      const chip = (text, cls, title) => {
        const c = document.createElement('span');
        c.className = 'gd-tour-lesson-badge ' + cls;
        c.textContent = text;
        if (title) c.title = title;
        btn.appendChild(c);
      };
      if (news.fresh.includes(lesson.id)) {
        row.classList.add('gd-tour-lesson-row-new');
        chip('new', 'gd-tour-lesson-badge-new', 'Added since you last opened the catalogue');
      }
      if (done.has(_tourKey(lesson))) {
        row.classList.add('gd-tour-lesson-row-done');
        btn.classList.add('gd-tour-btn-done');
        const mark = document.createElement('span');
        mark.className = 'gd-tour-lesson-note';
        mark.textContent = ' ✓ done';
        btn.appendChild(mark);
        if (_tourStale(lesson, done)) {
          row.classList.add('gd-tour-lesson-row-updated');
          chip('updated', 'gd-tour-lesson-badge-updated',
               'Changed since you finished it (edition ' + done.get(_tourKey(lesson))
               + ' → ' + _tourVersionOf(lesson) + ') — worth taking again');
        }
        const undo = _tourBtn('↺', 'gd-tour-btn-quiet gd-tour-unmark', () => {
          if (!_tourUnmarkDone(lesson)) return;
          done = _tourDoneMap();
          renderFoot(false);
          renderCounts();
          render(filter.value);
          // The re-render threw away the button that had focus; the lesson it
          // belonged to is still listed, so focus lands back on its row.
          const back = list.querySelector(
            '[data-lesson-id="' + lesson.id + '"] .gd-tour-btn');
          if (back && typeof back.focus === 'function') back.focus();
          _tourSay('Lesson ' + lesson.id + ' marked as not done');
        });
        undo.title = 'Mark lesson ' + lesson.id + ' as not done';
        undo.setAttribute('aria-label', 'Mark lesson ' + label + ' as not done');
        row.appendChild(undo);
      }
      if (!need.allowed) {
        btn.classList.add('gd-tour-btn-locked');
        btn.disabled = true;
        btn.title = 'Needs ' + need.phrase + ' — this session does not have it.';
        const note = document.createElement('span');
        note.className = 'gd-tour-lesson-note';
        note.textContent = ' — needs ' + need.short;
        btn.appendChild(note);
      }
      list.appendChild(row);
    }
    if (!shown) {
      const empty = document.createElement('div');
      empty.className = 'gd-tour-hint';
      empty.textContent = 'No lesson matches “' + q + '”.';
      list.appendChild(empty);
    }
  };

  // Cancel sits OUTSIDE the list, which scrolls: twenty-five lessons plus five
  // chapter headings are taller than a laptop viewport, and a way out that
  // scrolls off the bottom is no way out.
  const foot = document.createElement('div');
  foot.className = 'gd-tour-foot gd-tour-picker-foot';
  pop.appendChild(foot);

  // Two footers, one slot. The normal one is Cancel (plus "clear my ✓s" when
  // there are any); confirming the clear swaps that row rather than opening a
  // second dialog over the catalogue — the list behind it is the context for
  // the decision, and a dialog would cover exactly what the reader is deciding
  // about.
  const renderFoot = (confirming) => {
    foot.replaceChildren();
    if (confirming) {
      const ask = document.createElement('span');
      ask.className = 'gd-tour-hint';
      ask.textContent = 'Clear ' + done.size + ' ✓ mark'
        + (done.size === 1 ? '' : 's') + '?';
      foot.appendChild(ask);
      const yes = _tourBtn('Clear', 'gd-tour-btn-primary gd-tour-clear-confirm', () => {
        const n = _tourClearDone();
        done = _tourDoneMap();
        renderFoot(false);
        renderCounts();
        render(filter.value);
        _tourSay(n + ' lesson mark' + (n === 1 ? '' : 's') + ' cleared');
        if (typeof gdToast === 'function') {
          gdToast('Progress cleared — ' + n + ' lesson'
                  + (n === 1 ? '' : 's') + ' no longer marked done');
        }
        const back = foot.querySelector('.gd-tour-btn-quiet');
        if (back && typeof back.focus === 'function') back.focus();
      });
      const no = _tourBtn('Keep them', 'gd-tour-btn-quiet', () => {
        renderFoot(false);
        const back = foot.querySelector('.gd-tour-clear');
        if (back && typeof back.focus === 'function') back.focus();
      });
      foot.appendChild(yes);
      foot.appendChild(no);
      if (typeof yes.focus === 'function') yes.focus();
      return;
    }
    // Dismissing the catalogue returns to the tour it covered — ARMED. A bare
    // re-render left a step that polled nothing and ignored Escape.
    foot.appendChild(_tourBtn('Cancel', 'gd-tour-btn-quiet', cancel));
    if (done.size) {
      foot.appendChild(_tourBtn(
        'Clear progress (' + done.size + ')', 'gd-tour-btn-quiet gd-tour-clear',
        () => renderFoot(true)));
    }
  };

  renderFoot(false);
  filter.addEventListener('input', () => render(filter.value));
  // Escape inside the filter clears it rather than ending anything — the
  // catalogue's own Cancel is the way out.
  filter.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || !filter.value) return;   // empty: the dialog's Escape (Cancel)
    e.stopPropagation();
    filter.value = '';
    render('');
  });
  render('');
  // Focus goes in once the catalogue is built — onto the filter, the first
  // focusable thing (synchronous: the vm tests run without timers).
  if (typeof focusIntoDialog === 'function') focusIntoDialog(pop);
  else if (typeof filter.focus === 'function') filter.focus();
}

window.openTutorialMenu = openTutorialMenu;
