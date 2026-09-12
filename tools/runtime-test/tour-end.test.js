// Unit tests for the END of a lesson — `_tourEnd` in editor-tour.js, with the
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
    location: { href: 'http://x/', pathname: '/', search: '', hash: '' },
    history: { replaceState: () => {} },
    API: { api_branches_ref: (b) => '/api/branches/' + b, api_branches: '/api/branches' },
    authFetch: async (url, init) => {
      calls.push(((init && init.method) || 'GET') + ' ' + url);
      return { ok: true, json: async () => ({ ok: true }) };
    },
    switchToBranch: (b) => calls.push('switchToBranch ' + b),
    gdToast: (m) => calls.push('toast ' + m),
    // The cleanup module is NOT loaded — these are its seams, recorded.
    _tourSurvivors: async (created) => (o.survivors || created),
    _tourDeleteCreated: async (created) => {
      calls.push('deleteCreated ' + created.map((c) => c.name).join(','));
      return { failed: [] };
    },
    _tourDeleteCreatedBranches: async () => { calls.push('deleteBranches'); return []; },
    _tourDeleteNamespaces: async () => { calls.push('deleteNamespaces'); return []; },
  };
  ctx.window = ctx;
  ctx.window.innerWidth = 1400;
  ctx.window.graphdenHasCap = () => true;
  ctx.window.gdAnnounce = (m) => calls.push('announce ' + m);
  vm.createContext(ctx);
  vm.runInContext(read('editor-tour.js'), ctx);
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
    created: [] }, extra || {});

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

  console.log('');
  console.log(failures ? '✗ ' + failures + ' failed, ' + passes + ' passed'
                       : '✓ ' + passes + ' assertions passed');
  process.exit(failures ? 1 : 0);
})();
