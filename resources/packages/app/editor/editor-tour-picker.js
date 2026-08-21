// editor-tour-picker.js — the lesson catalogue.
//
// Twenty-five lessons in five chapters is a library, not a menu, so the list
// answers three questions before it lists anything: where did I stop, what
// have I already done, and which of these can this session even run.
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

function _tourMarkDone(lessonId) {
  if (!lessonId) return;
  try {
    const done = _tourDoneSet();
    done.add(lessonId);
    localStorage.setItem(TOUR_DONE_KEY, JSON.stringify([...done]));
  } catch (_) { /* private mode — the catalogue just won't remember */ }
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

  const done = _tourDoneSet();
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
        'Continue ' + paused.id + ' · ' + (paused.title || '')
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
      const label = lesson.id + ' · ' + (lesson.title || '');
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
      if (done.has(lesson.id)) {
        btn.classList.add('gd-tour-btn-done');
        const mark = document.createElement('span');
        mark.className = 'gd-tour-lesson-note';
        mark.textContent = ' ✓ done';
        btn.appendChild(mark);
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
      list.appendChild(btn);
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
  // Dismissing the catalogue returns to the tour it covered — ARMED. A bare
  // re-render left a step that polled nothing and ignored Escape.
  foot.appendChild(_tourBtn('Cancel', 'gd-tour-btn-quiet', () => _tourResume()));
  pop.appendChild(foot);

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
