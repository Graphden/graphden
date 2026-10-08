// Unit tests for the END of a lesson — `_tourEnd` in editor-tour-end.js, with the
// catalogue (editor-tour-picker.js) loaded beside it exactly as the browser
// loads them.
//
// Three arms, and the differences between them are the whole point:
//
//   * a branch-isolated run offers the rollback, and "start the next lesson"
//     there cannot be a function call — the rollback ends in a branch switch,
//     which RELOADS. The intent is parked in `graphden.tour.next` and picked
//     up by `maybeStartTutorial` on the other side.
//   * an in-place run that created rows offers the per-item cleanup, and
//     continuing runs that cleanup FIRST.
//   * a lesson that created nothing used to just vanish. It now says so and
//     offers what is next — but only if there IS a next, and only if the
//     reader actually reached the end: Escape ends a tour from any step, and
//     "what would you like next?" after step 2 of 9 reads as a shrug.
//
// None of this is reachable from a lesson walk without finishing whole
// lessons in a browser, which is minutes per arm and gate-only.
//
// Run:  node tools/runtime-test/tour-end.test.js
// Exit: 0 on pass, 1 on failure.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const editor = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');
const read = (f) => fs.readFileSync(path.join(editor, f), 'utf8');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; return; }
  failures++;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  return Promise.resolve().then(fn)
    .catch((e) => { failures++; console.error('  ✗ threw: ' + e.stack); });
}

const LESSONS = {
  copy: {
    'branch-title': 'Delete the tutorial branch?',
    'branch-confirm': 'Delete branch & return',
    'branch-keep': 'Keep branch',
    'cleanup-title': 'Clean up tutorial items?',
    'cleanup-confirm': 'Delete them',
    'cleanup-keep': 'Keep & close',
    'next-label': 'Next up',
    'next-start': 'Start {lesson}',
    'next-unfinished': 'Start {lesson} — not done yet',
    'next-note-branch': 'Deletes this branch first, then opens the next lesson.',
    'next-note-items': 'Deletes the items above first, then opens the next lesson.',
    'finished-title': 'Lesson {lesson} finished',
    'finished-body': 'Nothing to clean up.',
    'finished-close': 'Close',
  },
  lessons: [
    { id: '01', title: 'First fn', steps: [{}, {}] },
    { id: '02', title: 'Slots', steps: [{}] },
    { id: '03', title: 'Free args', steps: [{}] },
  ],
};

// One world per case: the two tour modules in one context, every surface the
// end-of-tour dialogs reach for stubbed and RECORDED.
function makeWorld(opts) {
  const o = Object.assign({ done: [], survivors: null, deleted: [] }, opts);
  const store = new Map();
  if (o.done.length) store.set('graphden.tour.done', JSON.stringify(o.done));
  if (o.saved) store.set('graphden.tour', JSON.stringify(o.saved));
  const document = createDocument();
  const calls = [];
  const ctx = {
    console, JSON, Set, URLSearchParams, Math, Object, Array, Promise,
    document,
    setInterval: () => 0,
    clearInterval: () => {},
    performance: { now: () => Date.now() },
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k),
    },
    location: { href: 'http://x/', pathname: '/', search: o.branch ? '?branch=' + o.branch : '', hash: o.hash || '' },
    history: { replaceState: (_state, _title, url) => calls.push('replaceState ' + url) },
    API: { api_branches_ref: (b) => '/api/branches/' + b, api_branches: '/api/branches' },
    authFetch: async (url, init) => {
      calls.push(((init && init.method) || 'GET') + ' ' + url);
      if (url === '/api/branches' && !init?.method) {
        const names = o.availableBranches || [o.branch, o.state?.sandboxBranch,
          o.state?.branch, o.saved?.sandboxBranch, o.saved?.branch, o.saved?.activeBranch];
        return {ok: true, json: async () => o.branchRows || names.filter(Boolean).map((name) => ({name}))};
      }
      return { ok: true, json: async () => ({ ok: !o.branchFailure }) };
    },
    switchToBranch: (b) => calls.push('switchToBranch ' + b),
    getCurrentBranchName: () => o.branch ?? o.state?.sandboxBranch ?? o.state?.branch ?? 'main',
    gdToast: (m) => calls.push('toast ' + m),
    // The cleanup module is NOT loaded — these are its seams, recorded.
    _tourSurvivors: async (created) => (o.survivors || created),
    _tourDeleteCreated: async (created, options) => {
      ctx.__cleanupOptions = options;
      calls.push('deleteCreated ' + created.map((c) => c.name).join(','));
      return { failed: o.failedItems || [] };
    },
    _tourDeleteCreatedBranches: async () => { calls.push('deleteBranches'); return o.failedBranches || []; },
    _tourDeleteNamespaces: async () => { calls.push('deleteNamespaces'); return []; },
  };
  ctx.window = ctx;
  ctx.window.innerWidth = 1400;
  ctx.window.graphdenHasCap = () => true;
  ctx.window.gdAnnounce = (m) => calls.push('announce ' + m);
  vm.createContext(ctx);
  vm.runInContext(read('editor-tour.js'), ctx);
  vm.runInContext(read('editor-tour-session.js'), ctx);
  // The end-of-lesson dialogs and the spotlight geometry they hide moved to
  // their own files (2026-09-13); the browser loads them right after the engine.
  vm.runInContext(read('editor-tour-spot.js'), ctx);
  const seams = Object.fromEntries(['_tourSurvivors', '_tourDeleteCreated',
    '_tourDeleteCreatedBranches', '_tourDeleteNamespaces'].map((k) => [k, ctx[k]]));
  vm.runInContext(read('editor-tour-cleanup.js'), ctx);
  Object.assign(ctx, seams);
  vm.runInContext(read('editor-tour-end.js'), ctx);
  vm.runInContext(read('editor-tour-picker.js'), ctx);
  // `_tourLessons` / `_tourState` are script-scope `let`s — the browser's tour
  // engine owns them, so a test seeds them the only way anything else can.
  vm.runInContext('_tourLessons = ' + JSON.stringify(LESSONS) + ';', ctx);
  vm.runInContext('_tourState = ' + JSON.stringify(o.state) + ';', ctx);
  // Starting the next lesson for real would create a branch and reload.
  ctx.startTutorialIsolated = (id) => { calls.push('startIsolated ' + id); return true; };
  const pop = () => document.body.querySelector('div#gd-tour-pop');
  return {
    ctx, calls, pop,
    saved: () => JSON.parse(store.get('graphden.tour') || 'null'),
    queued: () => store.get('graphden.tour.next') || null,
    title: () => pop()?.querySelector('div.gd-tour-title')?.textContent || null,
    next: () => pop()?.querySelector('div.gd-tour-next') || null,
    btn: (label) => (pop() ? pop().querySelectorAll('button.gd-tour-btn')
      .find((b) => b.textContent.trim() === label) || null : null),
    btns: () => (pop() ? pop().querySelectorAll('button.gd-tour-btn')
      .map((b) => b.textContent.trim()) : []),
  };
}

// A lesson whose step index ran PAST its last step — the finished shape.
const finishedOn = (id, extra) => Object.assign(
  { lessonId: id, step: (LESSONS.lessons.find((l) => l.id === id).steps.length),
    created: [], ...(extra?.branch ? {sandboxBranch: extra.branch} : {}) }, extra || {});

(async () => {
  await test('a branch run offers the rollback, and parks the next lesson across it',
             async () => {
    const w = makeWorld({ state: finishedOn('01', { branch: 'tutorial-01-ab12' }) });
    await w.ctx._tourEnd();
    assert(w.title() === 'Delete the tutorial branch?', 'the rollback dialog opens');
    assert(w.next(), 'with a "next up" section under it');
    assert(w.btn('Start 02 · Slots'), 'offering the lesson that follows (got: '
      + JSON.stringify(w.btns()) + ')');
    assert(!w.btn('Start 03 · Free args — not done yet'),
      'and only that one, while it is unread');
    assert(/Deletes this branch first/.test(w.next().textContent),
      'saying what continuing does to the branch (got: ' + w.next().textContent + ')');

    w.btn('Start 02 · Slots').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.includes('DELETE /api/branches/tutorial-01-ab12'),
      'continuing rolls the branch back first (got: ' + JSON.stringify(w.calls) + ')');
    assert(w.calls.includes('switchToBranch null'), 'and returns to main');
    assert(w.queued() === '02',
      'the next lesson is parked for after the reload (got: ' + w.queued() + ')');
    assert(!w.calls.some((c) => /^startIsolated/.test(c)),
      'nothing is started on THIS page — the branch switch is a page load');
  });

  await test('the plain rollback parks nothing', async () => {
    const w = makeWorld({ state: finishedOn('01', { branch: 'tutorial-01-cd34' }) });
    await w.ctx._tourEnd();
    w.btn('Delete branch & return').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.includes('switchToBranch null'), 'it still returns to main');
    assert(w.queued() === null,
      'but queues no lesson — the reader asked for nothing next (got: '
      + w.queued() + ')');
  });

  await test('rollback drops a qualified lesson selection before returning to main', async () => {
    const w = makeWorld({ hash: '#tutorial%2Fcreated',
      state: finishedOn('01', { branch: 'tutorial-01-qualified',
        created: [{ type: 'fn', name: 'created' }] }) });
    await w.ctx._tourEnd();
    w.btn('Delete branch & return').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.indexOf('replaceState /') >= 0, 'the qualified selection is cleared');
    assert(w.calls.indexOf('replaceState /') < w.calls.indexOf('switchToBranch null'),
      'it is cleared before the reload');
  });

  for (const failure of [{ branchFailure: true },
    { failedBranches: [{ type: 'branch', name: 'child' }] }]) {
    await test('a refused rollback stays available for retry: ' + JSON.stringify(failure), async () => {
      const w = makeWorld({ ...failure,
        state: finishedOn('01', { branch: 'tutorial-01-refused' }) });
      await w.ctx._tourEnd();
      w.btn('Start 02 · Slots').click();
      await new Promise((r) => setTimeout(r, 0));
      assert(!w.calls.includes('switchToBranch null'), 'no reload hides the refusal');
      assert(w.queued() === null, 'no next lesson starts after failed cleanup');
      assert(w.btn('Delete branch & return'), 'the reader can retry cleanup');
      assert(w.calls.some((c) => c.includes('could not be deleted')), 'failure is reported');
      if (failure.failedBranches) assert(!w.calls.includes('DELETE /api/branches/tutorial-01-refused'),
        'the sandbox is retained when a child could not be removed');
    });
  }

  await test('the queued lesson is taken exactly once', async () => {
    const w = makeWorld({ state: finishedOn('01', { branch: 'tutorial-01-ef56' }) });
    await w.ctx._tourEnd();
    w.btn('Start 02 · Slots').click();
    await new Promise((r) => setTimeout(r, 0));
    // The other side of the reload.
    vm.runInContext('_tourState = null;', w.ctx);
    assert(await w.ctx.maybeStartTutorial() === true, 'the next load picks it up');
    assert(w.calls.includes('startIsolated 02'),
      'and starts it ISOLATED, on its own branch (got: '
      + JSON.stringify(w.calls) + ')');
    assert(w.queued() === null, 'the note is consumed');
    w.calls.length = 0;
    await w.ctx.maybeStartTutorial();
    assert(!w.calls.some((c) => /^startIsolated/.test(c)),
      'a later load starts nothing — a stale note would be a tour nobody asked for');
  });

  await test('an in-place run cleans up first, then continues', async () => {
    const w = makeWorld({
      state: finishedOn('01', { created: [{ type: 'fn', name: 'greet' }] }),
    });
    await w.ctx._tourEnd();
    assert(w.title() === 'Clean up tutorial items?', 'the per-item dialog opens');
    assert(/Deletes the items above first/.test(w.next().textContent),
      'and says what continuing does to them');
    w.btn('Start 02 · Slots').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.indexOf('deleteCreated greet') >= 0, 'the rows go');
    assert(w.calls.indexOf('deleteCreated greet') < w.calls.indexOf('startIsolated 02'),
      'BEFORE the next lesson opens (got: ' + JSON.stringify(w.calls) + ')');
    assert(w.queued() === null,
      'nothing is parked — no reload to survive, so it starts here');
  });

  await test('a refused in-place cleanup keeps its ledger and does not start the next lesson', async () => {
    const created = [{type: 'fn', name: 'greet'}];
    const failedItems = [...created];
    const w = makeWorld({state: finishedOn('01', {created}), failedItems});
    await w.ctx._tourEnd();
    w.btn('Start 02 · Slots').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(!w.calls.includes('startIsolated 02'), 'refused cleanup does not start another lesson');
    assert(w.btn('Delete them'), 'the cleanup action remains available for retry');
    assert(vm.runInContext('_tourState.created.length', w.ctx) === 1,
      'the session retains the original cleanup ledger');
    failedItems.length = 0;
    w.btn('Delete them').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.pop() === null, 'successful retry closes the dialog');
  });

  await test('a lesson that made nothing says so and offers what is next', async () => {
    const w = makeWorld({ state: finishedOn('01') });
    await w.ctx._tourEnd();
    assert(w.title() === 'Lesson 01 finished',
      'the tour no longer just vanishes (got: ' + w.title() + ')');
    assert(w.calls.some((c) => /^announce Lesson 01 finished/.test(c)),
      'and says so out loud — the tour is not focus-trapped, so a rebuilt'
      + ' popup is otherwise silent (got: ' + JSON.stringify(w.calls) + ')');
    assert(w.btn('Close'), 'with a way out');
    assert(w.btn('Start 02 · Slots'), 'and the next lesson');
    w.btn('Close').click();
    assert(w.pop() === null, 'Close tears the overlay down');
  });

  await test('a lesson read out of order offers both "next" and "next new"',
             async () => {
    const w = makeWorld({ state: finishedOn('01'), done: ['02'] });
    await w.ctx._tourEnd();
    assert(w.btn('Start 02 · Slots'), 'the one that follows is still offered');
    assert(w.btn('Start 03 · Free args — not done yet'),
      'and so is the first unread one (got: ' + JSON.stringify(w.btns()) + ')');
    w.btn('Start 03 · Free args — not done yet').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.includes('startIsolated 03'), 'which starts the one it names');
  });

  await test('nothing left to read means no card at all', async () => {
    const w = makeWorld({ state: finishedOn('03'), done: ['01', '02'] });
    await w.ctx._tourEnd();
    assert(w.pop() === null,
      'a card whose only button is Close is furniture (got: '
      + JSON.stringify(w.btns()) + ')');
  });

  await test('quitting mid-lesson is not finishing it', async () => {
    // Escape at step 1 of 2 — `_tourEnd` runs, but nothing was finished.
    const w = makeWorld({ state: { lessonId: '01', step: 1, created: [] } });
    await w.ctx._tourEnd();
    assert(w.pop() === null, 'no "finished" card for a lesson that was abandoned');

    const withRows = makeWorld({
      state: { lessonId: '01', step: 1, created: [{ type: 'fn', name: 'greet' }] },
    });
    await withRows.ctx._tourEnd();
    assert(withRows.title() === 'Clean up tutorial items?',
      'the cleanup offer still stands — the rows are real either way');
    assert(!withRows.next(),
      'but nothing is suggested next (got: ' + JSON.stringify(withRows.btns()) + ')');
  });

  await test('a reload at Finish restores cleanup, not the final lesson step', async () => {
    const saved = finishedOn('01', { branch: 'tutorial-owned', activeBranch: 'tutorial-owned',
      created: [{type: 'branch', name: 'child'}] });
    const w = makeWorld({state: null, saved, branch: 'tutorial-owned'});
    assert(await w.ctx.maybeStartTutorial(), 'the interrupted session is recovered');
    assert(w.title() === 'Delete the tutorial branch?', 'the cleanup decision is restored');
    assert(w.saved().step === 2 && w.saved().phase === 'cleanup',
      'the finished step and cleanup phase survive another reload');
    assert(w.saved().created[0].name === 'child', 'the original ledger survives recovery');
  });

  await test('Finish recovery also works before the lesson catalogue is cached', async () => {
    const saved = finishedOn('01', {created: [{type: 'fn', name: 'owned'}]});
    const w = makeWorld({state: null, saved});
    vm.runInContext('_tourLessons = null;', w.ctx);
    w.ctx.API.api_tour = '/api/tour';
    w.ctx.authFetch = async () => ({ok: true, json: async () => LESSONS});
    await w.ctx.maybeStartTutorial();
    assert(w.title() === 'Clean up tutorial items?', 'boot fetches the catalogue before interpreting the finished step');
    assert(w.saved().step === 2, 'the final step is not clamped while loading the catalogue');
  });

  await test('Lessons restores an interruption from its original branch', async () => {
    const saved = {lessonId: '01', step: 1, sandboxBranch: 'tutorial-owned',
      activeBranch: 'tutorial-child', created: [{type: 'branch', name: 'tutorial-child'}]};
    const w = makeWorld({state: null, saved, branch: 'main'});
    assert(await w.ctx.maybeStartTutorial() === false, 'boot stays on the chosen branch');
    assert(w.saved().sandboxBranch === 'tutorial-owned', 'boot preserves sandbox ownership');
    await w.ctx.openTutorialMenu();
    assert(w.btn('Continue 01 · First fn — step 2/2'), 'Lessons offers the saved step');
    w.btn('End lesson & clean up').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.includes('switchToBranch tutorial-owned'),
      'cleanup returns to the owned sandbox before deleting namespace versions');
    assert(w.saved().activeBranch === 'tutorial-child', 'restoring context preserves the last step branch');
    assert(w.saved().created[0].name === 'tutorial-child', 'restoring context preserves the ledger');
    assert(w.saved().phase === 'cleanup', 'the cleanup intent crosses the branch reload');
  });

  await test('an in-place sibling tutorial resumes cleanup on main after cancel and reload', async () => {
    const created = [
      {type: 'branch', name: 'tutorial-sandbox', id: 'base-id', 'base-branch-id': 'main-id'},
      {type: 'branch', name: 'tutorial-branch', id: 'source-id', 'base-branch-id': 'base-id'},
      {type: 'branch', name: 'tutorial-merge-target', id: 'target-id', 'base-branch-id': 'base-id'},
    ];
    const saved = {lessonId: '01', step: 1, activeBranch: 'tutorial-merge-target',
      cleanupBranch: 'main', created};
    const w = makeWorld({state: saved, branch: 'tutorial-merge-target'});
    await w.ctx._tourEnd();
    assert(w.calls.includes('switchToBranch main'), 'Escape first returns to the explicit cleanup root');
    assert(w.saved().phase === 'cleanup', 'cleanup intent survives the branch reload');
    assert(JSON.stringify(w.saved().created) === JSON.stringify(created), 'all exact UUID ownership survives');
    assert(w.pop() === null, 'nothing is deleted while still on the merged target');
    const reloaded = makeWorld({state: null, saved: w.saved(), branch: 'main'});
    await reloaded.ctx.maybeStartTutorial();
    assert(reloaded.title() === 'Clean up tutorial items?', 'reload offers the unfinished lesson cleanup');
    assert(!reloaded.next(), 'cancel does not mark the lesson finished');
    reloaded.btn('Delete them').click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert(reloaded.calls.includes('deleteCreated tutorial-sandbox,tutorial-branch,tutorial-merge-target'),
      'cleanup receives the persisted complete ledger');
  });

  await test('Cancel in Lessons preserves a session stored on another branch', async () => {
    const saved = {lessonId: '01', step: 1, activeBranch: 'work', created: []};
    const w = makeWorld({state: null, saved, branch: 'main'});
    await w.ctx.openTutorialMenu();
    w.btn('Cancel').click();
    assert(w.pop() === null, 'Cancel dismisses the catalogue');
    assert(JSON.stringify(w.saved()) === JSON.stringify(saved), 'Cancel leaves the session resumable');
  });

  await test('an explicitly requested lesson branch switch still resumes automatically', async () => {
    const saved = {lessonId: '01', step: 1, activeBranch: 'main', created: []};
    const w = makeWorld({state: null, saved, branch: 'lesson-branch'});
    vm.runInContext('_tourLessons.lessons[0].steps[1].check = {kind: "on-branch", name: "lesson-branch"};', w.ctx);
    w.ctx.startTutorial = async (_id, _step, _created, session) => {
      w.calls.push('resume ' + session.activeBranch);
      return true;
    };
    assert(await w.ctx.maybeStartTutorial(), 'the branch lesson resumes after its requested reload');
    assert(w.calls.includes('resume lesson-branch'), 'the new active branch is passed to the engine');
  });

  await test('a tutorial-prefixed retained branch grants no rollback ownership', async () => {
    const w = makeWorld({state: null, branch: 'tutorial-kept'});
    w.ctx._tourRenderStep = () => {};
    w.ctx._tourArm = () => {};
    await w.ctx.startTutorial('01');
    assert(!w.saved().sandboxBranch, 'starting a lesson does not claim the retained branch');
    await w.ctx._tourEnd();
    assert(!w.btn('Delete branch & return'), 'ending the lesson never offers the retained branch for deletion');
    assert(!w.calls.some((c) => /^DELETE /.test(c)), 'the retained branch is untouched');
  });

  await test('legacy branch metadata restores context but never proves ownership', async () => {
    const saved = {lessonId: '01', step: 1, branch: 'tutorial-legacy-kept',
      created: [{type: 'fn', name: 'lesson-owned'}]};
    const w = makeWorld({state: null, saved, branch: 'tutorial-legacy-kept'});
    w.ctx._tourRenderStep = () => {};
    w.ctx._tourArm = () => {};
    assert(await w.ctx.maybeStartTutorial(), 'the legacy lesson still resumes on its recorded branch');
    assert(w.saved().created[0].name === 'lesson-owned', 'its ledger remains available');
    await w.ctx._tourEnd();
    assert(w.title() === 'Clean up tutorial items?', 'legacy cleanup uses the existing per-item confirmation');
    assert(!w.btn('Delete branch & return'), 'a legacy branch field cannot offer rollback');
    w.btn('Delete them').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.includes('deleteCreated lesson-owned'), 'only recorded lesson work is cleaned');
    assert(!w.calls.some((c) => /^DELETE \/api\/branches\//.test(c)), 'the retained legacy branch is never deleted');
  });

  await test('sandbox ownership survives resuming on a lesson child branch', async () => {
    const saved = {lessonId: '01', step: 1, sandboxBranch: 'tutorial-owned',
      activeBranch: 'child', created: [{type: 'branch', name: 'child'}]};
    const w = makeWorld({state: null, saved, branch: 'child'});
    w.ctx._tourRenderStep = () => {};
    w.ctx._tourArm = () => {};
    assert(await w.ctx.maybeStartTutorial(), 'a reload on the active child resumes');
    assert(w.saved().sandboxBranch === 'tutorial-owned', 'the child does not replace sandbox ownership');
    assert(w.saved().created[0].name === 'child', 'the child cleanup ledger survives');
  });

  await test('choosing another lesson cannot replace a refused cleanup ledger', async () => {
    const failedItems = [{type: 'fn', name: 'owned'}];
    const w = makeWorld({state: {lessonId: '01', step: 1, activeBranch: 'main', created: failedItems},
      failedItems});
    await w.ctx.openTutorialMenu();
    w.btn('02 · Slots').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.saved().lessonId === '01' && w.saved().nextLessonId === '02',
      'the pending lesson remains the current session');
    w.btn('Delete them').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(!w.calls.includes('startIsolated 02'), 'refused cleanup does not launch the chosen lesson');
    assert(w.saved().created[0].name === 'owned', 'refused cleanup retains its original ledger');
    failedItems.length = 0;
    w.btn('Delete them').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.calls.includes('startIsolated 02'), 'a successful retry launches the requested lesson');
  });

  await test('a missing original branch cannot turn recovery into a name sweep on main', async () => {
    const saved = {lessonId: '01', step: 1, activeBranch: 'deleted-work',
      created: [{type: 'fn', name: 'same-name-on-main'}]};
    const w = makeWorld({state: null, saved, branch: 'main', availableBranches: []});
    await w.ctx.openTutorialMenu();
    w.btn('End lesson & clean up').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.title() === 'Tutorial branch unavailable', 'the recovery dialog explains the missing context');
    assert(!w.calls.some((c) => /^deleteCreated|^DELETE|^switchToBranch/.test(c)),
      'recovery neither writes on main nor reloads into a deleted branch');
    w.btn('Cancel').click();
    assert(w.saved().created[0].name === 'same-name-on-main', 'Cancel retains the ledger for a later recovery');
    assert(w.pop() === null, 'Cancel dismisses the blocked recovery dialog');
  });

  for (const changed of [{accountId: 'other', orgId: 'org'},
    {accountId: 'owner', orgId: 'other-org'}, null]) {
    await test('cleanup requires the original account and org: ' + JSON.stringify(changed), async () => {
      const state = {lessonId: '01', step: 1, activeBranch: 'main',
        principal: changed, created: [{type: 'fn', name: 'same-name'}]};
      const w = makeWorld({state});
      w.ctx.gdAccount = {id: 'owner'};
      w.ctx.graphdenCurrentOrg = 'org';
      await w.ctx._tourEnd();
      assert(w.title() === 'Tutorial context changed', 'mismatched or unknown provenance is explained');
      assert(!w.calls.some((c) => /^deleteCreated|^DELETE/.test(c)), 'no ledger names are deleted in the new principal context');
      w.btn('Keep & close').click();
      assert(w.saved() === null && w.pop() === null, 'Keep closes the session without changing graph data');
    });
  }

  await test('a cookie account change after opening cleanup is rechecked before deletion', async () => {
    const w = makeWorld({state: {lessonId: '01', step: 1, activeBranch: 'main',
      principal: {accountId: 'owner', orgId: null}, created: [{type: 'fn', name: 'same-name'}]}});
    w.ctx.gdAccount = {id: 'owner'};
    await w.ctx._tourEnd();
    assert(w.btn('Delete them'), 'the original account can see its cleanup offer');
    w.ctx.gdAccountsReady = Promise.resolve(true);
    w.ctx.fetch = async () => ({ok: true, json: async () => ({account: {id: 'different'}})});
    w.btn('Delete them').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.title() === 'Tutorial context changed', 'the fresh account probe blocks cleanup');
    assert(!w.calls.some((c) => /^deleteCreated/.test(c)), 'the stale dialog cannot write as the new account');
  });

  await test('a fresh organization header is checked before a stale dialog can delete', async () => {
    const w = makeWorld({state: {lessonId: '01', step: 1, activeBranch: 'main',
      principal: {accountId: 'owner', orgId: 'original-org'}, created: [{type: 'fn', name: 'same-name'}]}});
    w.ctx.gdAccount = {id: 'owner'};
    w.ctx.graphdenCurrentOrg = 'original-org';
    w.ctx.API.api_graph_entities = '/api/graph/entities';
    let activeOrg = 'original-org';
    w.ctx.authFetch = async () => ({ok: true, headers: {get: () => activeOrg}});
    await w.ctx._tourEnd();
    assert(w.btn('Delete them'), 'a matching real org header confirms cleanup context');
    activeOrg = 'other-org';
    w.btn('Delete them').click();
    await new Promise((r) => setTimeout(r, 0));
    assert(w.title() === 'Tutorial context changed', 'a same-origin org switch blocks the old dialog');
    assert(!w.calls.some((c) => /^deleteCreated/.test(c)), 'no names are deleted in the new organization');
  });

  for (const confirmedOrg of ['demo-org', null]) {
    await test('an accountless tenant requires a confirmed org: ' + confirmedOrg, async () => {
      const w = makeWorld({state: {lessonId: '01', step: 1, activeBranch: 'main',
        principal: {accountId: null, orgId: 'demo-org'}, created: [{type: 'fn', name: 'demo-created'}]}});
      w.ctx.graphdenCurrentOrg = 'demo-org';
      w.ctx.gdAccountsReady = Promise.resolve(true);
      w.ctx.fetch = async () => ({ok: false, status: 401,
        json: async () => ({error: 'unauthenticated'})});
      w.ctx.API.api_graph_entities = '/api/graph/entities';
      w.ctx.authFetch = async () => ({ok: true, headers: {get: () => confirmedOrg}});
      await w.ctx._tourEnd();
      assert(w.title() === (confirmedOrg ? 'Clean up tutorial items?' : 'Tutorial context changed'),
        'demo cleanup is available only when its actual org header confirms the context');
      assert(!w.calls.some((c) => /^deleteCreated/.test(c)), 'rendering the decision never mutates demo data');
    });
  }

  for (const failure of ['401', 'network']) {
    await test('an unconfirmed account blocks deletion: ' + failure, async () => {
      const w = makeWorld({state: {lessonId: '01', step: 1, activeBranch: 'main',
        principal: {accountId: 'owner', orgId: null}, created: [{type: 'fn', name: 'owned'}]}});
      w.ctx.gdAccount = {id: 'owner'};
      w.ctx.gdAccountsReady = Promise.resolve(true);
      w.ctx.fetch = async () => {
        if (failure === 'network') throw new Error('offline');
        return {ok: false, status: 401, json: async () => ({error: 'unauthenticated'})};
      };
      await w.ctx._tourEnd();
      assert(w.title() === 'Tutorial context changed', 'the stale remembered account is insufficient');
      assert(!w.calls.some((c) => /^deleteCreated/.test(c)), 'no mutation occurs after failed account confirmation');
    });
  }

  await test('scratch rollback keeps the whole ledger when its app removal is unconfirmed', async () => {
    const app = {type: 'app-route', name: 'handler', id: 'app-id', receipt: 'pending'};
    const w = makeWorld({branch: 'tutorial-owned',
      state: finishedOn('01', {sandboxBranch: 'tutorial-owned', created: [app]})});
    w.ctx._tourDeleteAppRoutes = async () => [app];
    await w.ctx._tourEnd();
    w.btn('Delete branch & return').click();
    await new Promise(resolve => setTimeout(resolve, 0));
    assert(!w.calls.some(call => call.startsWith('DELETE /api/branches/')), 'unconfirmed app removal keeps the sandbox');
    assert(w.saved().created[0].id === 'app-id', 'exact app receipt survives retry');
    assert(w.btn('Delete branch & return'), 'the app cleanup failure remains retryable');
  });

  await test('scratch rollback removes its app before deleting the captured branch', async () => {
    const app = {type: 'app-route', name: 'handler', id: 'app-id', receipt: 'created'};
    const w = makeWorld({branch: 'tutorial-owned',
      branchRows: [{id: 'sandbox-id', name: 'tutorial-owned', 'base-branch-id': 'main-id'}],
      state: finishedOn('01', {sandboxBranch: 'tutorial-owned', sandboxBranchId: 'sandbox-id',
        sandboxBaseBranchId: 'main-id', created: [app]})});
    w.ctx._tourDeleteAppRoutes = async () => {
      w.calls.push('remove-app app-id');
      app.receipt = 'removed';
      return [];
    };
    await w.ctx._tourEnd();
    w.btn('Delete branch & return').click();
    await new Promise(resolve => setTimeout(resolve, 0));
    const appIndex = w.calls.indexOf('remove-app app-id');
    const branchIndex = w.calls.indexOf('DELETE /api/branches/sandbox-id');
    assert(appIndex >= 0 && branchIndex > appIndex, 'exact app removal precedes branch deletion');
  });

  await test('scratch rollback waits for exact service stop before deleting branches', async () => {
    const service = {type: 'service', name: 'worker', id: 'service-id', receipt: 'created'};
    const w = makeWorld({branch: 'tutorial-owned',
      state: finishedOn('01', {sandboxBranch: 'tutorial-owned', created: [service]})});
    w.ctx._tourCleanupServices = async () => [service];
    await w.ctx._tourEnd();
    w.btn('Delete branch & return').click();
    await new Promise(resolve => setTimeout(resolve, 0));
    assert(!w.calls.some(call => call.startsWith('DELETE /api/branches/')), 'unconfirmed stop keeps the sandbox');
    assert(w.saved().created[0].id === 'service-id', 'exact service receipt survives retry');
    assert(w.btn('Delete branch & return'), 'cleanup can be retried after reconciler stops');
  });

  await test('scratch cleanup unpins before deletion and retains unknown pins for retry', async () => {
    const pin = {type: 'package-install', name: 'own-package', receipt: 'pending', 'branch-id': 'sandbox-id'};
    const w = makeWorld({branch: 'tutorial-owned',
      state: finishedOn('01', {sandboxBranch: 'tutorial-owned', created: [pin]})});
    await w.ctx._tourEnd();
    w.btn('Delete branch & return').click();
    await new Promise(resolve => setTimeout(resolve, 0));
    assert(!w.calls.some(call => call.startsWith('DELETE /api/branches/')), 'unknown pin keeps its branch available');
    assert(w.saved().created[0].receipt === 'pending', 'the original ambiguous pin remains in saved cleanup');
    assert(w.btn('Delete branch & return'), 'the retry action stays available');
  });

  await test('registry cleanup after exact sandbox deletion routes through main and survives reload', async () => {
    const release = {type: 'package-version', id: 'release-id', name: 'owned', version: '1.0.0', receipt: 'created'};
    const w = makeWorld({branch: 'tutorial-owned', failedItems: [release],
      branchRows: [{id: 'sandbox-id', name: 'tutorial-owned', 'base-branch-id': 'main-id'}],
      state: finishedOn('01', {sandboxBranch: 'tutorial-owned', sandboxBranchId: 'sandbox-id',
        sandboxBaseBranchId: 'main-id', created: [release]})});
    await w.ctx._tourEnd();
    w.btn('Delete branch & return').click();
    await new Promise(resolve => setTimeout(resolve, 0));
    assert(w.calls.includes('DELETE /api/branches/sandbox-id'), 'delete the captured UUID, not a mutable name');
    assert(w.ctx.__cleanupOptions.headers['X-Graphden-Branch'] === 'main', 'remaining artifacts use a live route');
    const saved = w.saved();
    assert(saved.sandboxBranch === null && saved.cleanupBranch === 'main' && saved.phase === 'cleanup',
      'the deleted sandbox is no longer used for recovery');
    assert(saved.created[0].id === 'release-id', 'failed exact release cleanup remains owned');
    const resumed = makeWorld({state: null, saved, branch: 'main', failedItems: [release]});
    await resumed.ctx.maybeStartTutorial();
    assert(resumed.title() === 'Clean up tutorial items?', 'Lessons restores the remaining cleanup on main');
  });

  console.log('');
  console.log(failures ? '✗ ' + failures + ' failed, ' + passes + ' passed'
                       : '✓ ' + passes + ' assertions passed');
  process.exit(failures ? 1 : 0);
})();
