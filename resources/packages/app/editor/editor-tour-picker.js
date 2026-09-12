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

const TOUR_DONE_KEY = 'graphden.tour.done';

function _tourDoneSet() {
  try {
    const raw = localStorage.getItem(TOUR_DONE_KEY);
    return new Set(raw ? JSON.parse(raw) : []);
  } catch (_) { return new Set(); }
}

// The whole history is ONE write, so marking, unmarking and clearing share it
// — three callers each doing their own read/modify/write is how one of them
// ends up persisting a Set (`"{}"`) instead of a list.
function _tourWriteDone(done) {
  try {
    if (done.size) localStorage.setItem(TOUR_DONE_KEY, JSON.stringify([...done]));
    else localStorage.removeItem(TOUR_DONE_KEY);
  } catch (_) { /* private mode — the catalogue just won't remember */ }
}

function _tourMarkDone(lessonId) {
  if (!lessonId) return;
  const done = _tourDoneSet();
  done.add(lessonId);
  _tourWriteDone(done);
}

// The ✓ is a claim about the READER, and readers are the only ones who know
// whether it is still true: a lesson skipped step-by-step to see the end, or
// one taken so long ago it needs re-reading, is marked done and should not be.
// Taking the mark off is the same kind of act as putting it on — local, free,
// and nothing else depends on it.
function _tourUnmarkDone(lessonId) {
  if (!lessonId) return false;
  const done = _tourDoneSet();
  if (!done.delete(lessonId)) return false;
  _tourWriteDone(done);
  return true;
}

// Start the whole catalogue over — a shared browser, a demo account, or a
// second read-through of all thirty-nine.
function _tourClearDone() {
  const n = _tourDoneSet().size;
  _tourWriteDone(new Set());
  return n;
}

const REQUIRE_SIGNALS = {
  // The dedicated tier (or a platform / single-tenant instance) — services run
  // on an executor the org owns, which lower plans don't get.
  services: {
    test: () => typeof window.gdServicesManageable === 'function'
             && window.gdServicesManageable(),
    phrase: 'the dedicated plan (or your own instance)',
    short: 'the dedicated plan',
  },
  // Lesson 35 names the editor's OWN web-server as the service it calls. A
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
  const unfinished = after.find((l) => !done.has(l.id))
    || all.filter(runnable).find((l) => !done.has(l.id) && l.id !== lessonId)
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
  pop.classList.add('gd-tour-visible', 'gd-tour-centered');
  // On a phone the catalogue is a bottom sheet like every other tour surface
  // — `_tourPosition` sets this while a lesson RUNS, and the catalogue can be
  // opened without one.
  pop.classList.toggle('gd-tour-sheet', _tourNarrow());
  // Opened MID-LESSON the popover still carries the last step's anchored
  // position as inline styles, which beat the centered class — the catalogue
  // then hangs off wherever that step's target was, and its capped height runs
  // past the bottom of the window.
  pop.style.left = '';
  pop.style.top = '';

  let done = _tourDoneSet();
  const saved = _tourState || _tourLoadState();

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
      if (done.has(lesson.id)) {
        row.classList.add('gd-tour-lesson-row-done');
        btn.classList.add('gd-tour-btn-done');
        const mark = document.createElement('span');
        mark.className = 'gd-tour-lesson-note';
        mark.textContent = ' ✓ done';
        btn.appendChild(mark);
        const undo = _tourBtn('↺', 'gd-tour-btn-quiet gd-tour-unmark', () => {
          if (!_tourUnmarkDone(lesson.id)) return;
          done = _tourDoneSet();
          renderFoot(false);
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
        done = _tourDoneSet();
        renderFoot(false);
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
    foot.appendChild(_tourBtn('Cancel', 'gd-tour-btn-quiet', () => _tourResume()));
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
    if (e.key !== 'Escape') return;
    e.stopPropagation();
    if (filter.value) { filter.value = ''; render(''); }
  });
  render('');
}

window.openTutorialMenu = openTutorialMenu;
