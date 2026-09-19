// editor-tour-end.js — the END of a lesson: cleanup offer, "next up", the card.
//
// `_tourEnd` stops the poll, reads
// what the lesson created ONCE and asks one question per run shape: a
// branch-isolated lesson offers to delete its branch (full rollback — created
// namespaces first, while the branch still resolves), an in-place lesson
// lists the survivors and offers soft-deletes, a lesson that created nothing
// shows a small finished card instead of vanishing. `_tourDialog` is the shared
// centered card (title / body / primary / quiet / optional "Next up" from
// `_tourNextSection`, which the catalogue resolves — teaching order, the
// reader's ✓s, what this session can run). Continuing into the next lesson
// runs that dialog's own cleanup FIRST; from a branch-isolated run the lesson
// id is parked in localStorage (`_tourQueueNext`) because the rollback ends in
// a page-loading branch switch. Unit-tested in tools/runtime-test/tour-end.test.js.

// The lesson is over and one question is always open: what next? The answer
// lives in the CATALOGUE (teaching order, the reader's ✓s, what this session
// can run), so the picker resolves it — `{next, unfinished}` — and this builds
// the buttons. `start(id)` is the caller's: each end-of-tour dialog has its own
// cleanup to finish first, and leaving a tutorial branch alive underneath the
// next lesson would nest one scratch branch inside another.
function _tourNextSection(lessonId, note, start) {
  if (typeof _tourNextUp !== 'function') return null;
  const { next, unfinished } = _tourNextUp(lessonId);
  const actions = [];
  const label = (l) => (typeof _tourLessonLabel === 'function'
    ? _tourLessonLabel(l) : (l.id + ' · ' + (l.title || '')));
  if (next) {
    actions.push([
      _tourCopy('next-start', 'Start {lesson}', { lesson: label(next) }),
      'gd-tour-btn-primary gd-tour-next-btn', () => start(next.id)]);
  }
  // Only when it differs from `next` — otherwise one button would be offered
  // twice under two different names.
  if (unfinished) {
    actions.push([
      _tourCopy('next-unfinished', 'Start {lesson} — not done yet',
                { lesson: label(unfinished) }),
      'gd-tour-btn-quiet gd-tour-next-btn', () => start(unfinished.id)]);
  }
  if (!actions.length) return null;
  return { label: _tourCopy('next-label', 'Next up'), note, actions };
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
  const lessonId = _tourState?.lessonId || null;
  // Escape ends the tour from ANY step, and "what would you like next?" after
  // step 2 of 9 reads as a shrug. Only a lesson whose index ran past its last
  // step was actually finished — the same condition that marks it ✓.
  const lesson = _tourLesson();
  const finished = !!lesson && (_tourState?.step || 0) >= (lesson.steps || []).length;

  // Branch-isolated run (org mode): the WHOLE lesson lives on a tour
  // branch, so the cleanup offer is one decision — delete the branch
  // (full rollback, returns to main) or keep it.
  // Offer the branch rollback whenever the tour OWNS a scratch branch —
  // not only while standing on it. A lesson that ended after switching
  // away (or a mis-scoped lesson) used to skip this offer entirely and
  // leak one tutorial-* branch per run.
  if (_tourState?.branch) {
    const branch = _tourState.branch;
    // `thenStart` — a lesson id to open once the rollback lands. It cannot be
    // started here: the rollback ends in a branch switch, and a branch switch
    // is a page load. Park it and let `maybeStartTutorial` pick it up on the
    // other side.
    const rollback = async (thenStart) => {
      let ok = true;
      try {
        // Children first — a fork the lesson itself made (lesson 21) would
        // otherwise block its parent's delete.
        await _tourDeleteCreatedBranches(created);
        // Namespaces are IDENTITY rows with no branch scope — deleting
        // the branch removes every version row the lesson wrote, but a
        // namespace the lesson created would stay visible on main as an
        // empty orphan. "Full rollback" includes it: clear + delete the
        // created ns rows FIRST, while this branch still resolves (the
        // fetch wrapper stamps its header; after the branch delete the
        // same requests would 4xx on a dead branch).
        const nsOnly = created.filter((c) => c.type === 'ns');
        if (nsOnly.length && typeof _tourDeleteNamespaces === 'function') {
          const failedNs = await _tourDeleteNamespaces(nsOnly);
          if (failedNs.length) ok = false;
        }
        const r = await authFetch(API.api_branches_ref(branch), { method: 'DELETE' });
        ok = ok && !!r?.ok;
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
      if (thenStart) _tourQueueNext(thenStart);
      if (typeof switchToBranch === 'function') switchToBranch(null);
      _tourReport(ok, ok ? _tourCopy('branch-done', 'Tutorial branch deleted')
                         : _tourCopy('branch-failed',
                                     'Branch “{branch}” could not be deleted', { branch }));
    };
    _tourDialog({
      title: _tourCopy('branch-title', 'Delete the tutorial branch?'),
      body: _tourCopy('branch-body',
                      'This lesson ran on its own branch “{branch}”. Deleting it'
                      + ' removes everything the lesson created and returns you'
                      + ' to main.', { branch }),
      primary: [_tourCopy('branch-confirm', 'Delete branch & return'),
                () => rollback(null)],
      quiet: [_tourCopy('branch-keep', 'Keep branch'), () => _tourTeardown()],
      next: finished && _tourNextSection(
        lessonId,
        _tourCopy('next-note-branch',
                  'Deletes this branch first, then opens the next lesson.'),
        (id) => rollback(id)),
    });
    return;
  }

  // Which of the lesson's creations are still around? Asking the registry
  // makes this async, so the dialog is rendered from the resolved list —
  // a type the deleter knows and this list does not is a row the reader is
  // never told about (that is how a published version went unmentioned).
  const survivors = await _tourSurvivors(created);

  // Nothing to clean up. The tour used to simply vanish here, which answers
  // the cleanup question and none of the others: half the lessons create
  // nothing, and a reader working through a thirty-nine-lesson course was
  // handed an empty screen and told to go find the catalogue again. With no
  // next lesson to offer (the last one, or everything ahead locked) it still
  // vanishes — a card whose only button is Close is furniture.
  if (!survivors.length) {
    const next = finished && _tourNextSection(
      lessonId, null, (id) => { _tourTeardown(); startTutorialIsolated(id); });
    if (!next) { _tourTeardown(); return; }
    _tourDialog({
      title: _tourCopy('finished-title', 'Lesson {lesson} finished',
                       { lesson: lessonId || '' }),
      body: _tourCopy('finished-body',
                      'Nothing to clean up — this lesson only looked around.'),
      quiet: [_tourCopy('finished-close', 'Close'), () => _tourTeardown()],
      next,
    });
    return;
  }

  const listOf = (rows) => rows.map((c) => c.type + ' “' + c.name + '”').join(', ');
  const cleanup = async () => {
    const { failed } = await _tourDeleteCreated(created);
    _tourTeardown();
    _tourReport(!failed.length,
                failed.length
                  ? _tourCopy('cleanup-failed',
                              'Kept: {items} — the server refused',
                              { items: listOf(failed) })
                  : _tourCopy('cleanup-done', 'Tutorial items deleted'));
  };
  _tourDialog({
    title: _tourCopy('cleanup-title', 'Clean up tutorial items?'),
    body: _tourCopy('cleanup-body',
                    'The tour created: {items}. Delete them, or keep them to'
                    + ' explore? (Deletes are soft.)',
                    { items: listOf(survivors) }),
    primary: [_tourCopy('cleanup-confirm', 'Delete them'), cleanup],
    quiet: [_tourCopy('cleanup-keep', 'Keep & close'), () => _tourTeardown()],
    next: finished && _tourNextSection(
      lessonId,
      _tourCopy('next-note-items',
                'Deletes the items above first, then opens the next lesson.'),
      async (id) => { await cleanup(); startTutorialIsolated(id); }),
  });
}

// The popover doubles as a small centered dialog: title, body, up to one
// primary and one quiet action, and an optional "next up" section under them.
// Three end-of-tour prompts built the same DOM by hand before this.
function _tourDialog({ title, body, primary, quiet, next }) {
  const { pop } = _tourEnsureEls();
  _tourSpotHide();
  pop.replaceChildren();
  pop.classList.add('gd-tour-visible');
  _tourCenterPop(pop);
  // Same rule as a step: on a phone this is a bottom sheet, not a 360px box
  // floating in a 390px window.
  pop.classList.toggle('gd-tour-sheet', _tourNarrow());
  const titleEl = document.createElement('div');
  titleEl.className = 'gd-tour-title';
  titleEl.id = 'gd-tour-title';
  titleEl.textContent = title;
  const bodyEl = document.createElement('div');
  bodyEl.className = 'gd-tour-body';
  bodyEl.id = 'gd-tour-body';
  _tourRenderBody(bodyEl, body);
  const foot = document.createElement('div');
  foot.className = 'gd-tour-foot';
  // `primary` is optional: a card whose real action is "read the next lesson"
  // has no decision to make here, and dressing its Close up as the answer
  // would say otherwise.
  if (primary) foot.appendChild(_tourBtn(primary[0], 'gd-tour-btn-primary', primary[1]));
  if (quiet) foot.appendChild(_tourBtn(quiet[0], 'gd-tour-btn-quiet', quiet[1]));
  pop.appendChild(titleEl);
  pop.appendChild(bodyEl);
  pop.appendChild(foot);
  // "What now?" is a real question at the end of a lesson, and the only answer
  // the editor used to give was "go find the catalogue again". The section
  // stays BELOW the cleanup decision — that decision is what the dialog is
  // about — but it is where the reader is already looking when they finish.
  if (next && (next.actions || []).length) {
    const box = document.createElement('div');
    box.className = 'gd-tour-next';
    const head = document.createElement('div');
    head.className = 'gd-tour-next-label';
    head.textContent = next.label;
    box.appendChild(head);
    for (const [text, cls, onClick] of next.actions) {
      box.appendChild(_tourBtn(text, cls, onClick));
    }
    if (next.note) {
      const note = document.createElement('div');
      note.className = 'gd-tour-hint gd-tour-next-note';
      note.textContent = next.note;
      box.appendChild(note);
    }
    pop.appendChild(box);
  }
  // Same reason the steps announce themselves (ACCESSIBILITY.md — the tour is
  // deliberately not focus-trapped, so a rebuilt popup is otherwise silent):
  // the lesson just ended and this card is the only thing that says so. The
  // ids are re-set above because the step's title/body elements they used to
  // point at were thrown away by `replaceChildren`.
  pop.setAttribute('aria-labelledby', 'gd-tour-title');
  pop.setAttribute('aria-describedby', 'gd-tour-body');
  if (typeof window.gdAnnounce === 'function') {
    window.gdAnnounce(title + '. ' + (bodyEl.textContent || '').trim());
  }
}

// Say what actually happened. Every delete here is best-effort, so an
// unconditional success toast over swallowed failures is a lie the reader
// only discovers later, by finding the rows still in their graph.
function _tourReport(ok, message) {
  if (typeof gdToast === 'function') gdToast(message, ok ? undefined : 'error');
}

// A state change nothing visibly moves for (a ✓ taken off a row further down
// the list, a cleared history) still has to reach a screen reader —
// ACCESSIBILITY.md's rule, and the catalogue's controls are exactly that kind.
function _tourSay(message) {
  if (typeof window.gdAnnounce === 'function') window.gdAnnounce(message);
}
