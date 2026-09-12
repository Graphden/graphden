// Unit tests for editor-tour-picker.js — the lesson catalogue and the reading
// history behind it.
//
// Three things here are only observable from a test that can SEE the rendered
// list, and none of them is something a lesson walk would notice:
//
//   * the ✓ is the reader's own claim, so it can be taken back — one row at a
//     time, or all of them. Both write through `_tourWriteDone`; an empty
//     history REMOVES the key rather than storing `"[]"`, so a browser that
//     never took the tour and one that cleared it read the same.
//   * a done row carries a second control. It is a SIBLING of the lesson
//     button, never a child: a button inside a button is invalid markup the
//     keyboard cannot reach, and the e2e guards read `list.children` for the
//     row shape.
//   * `_tourNextUp` is what the end-of-lesson dialog offers. It must skip
//     lessons this session cannot run, tell "the next one" from "the next one
//     you haven't done", and never offer the same lesson twice under two
//     labels.
//
// Run:  node tools/runtime-test/tour-picker.test.js
// Exit: 0 on pass, 1 on failure.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const source = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor',
            'editor-tour-picker.js'),
  'utf8');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; return; }
  failures++;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  return Promise.resolve()
    .then(fn)
    .catch((e) => { failures++; console.error('  ✗ threw: ' + e.stack); });
}

// --- the world the catalogue renders into -----------------------------------
//
// Everything the picker reaches for that lives in editor-tour.js (the popover
// elements, the button builder, the in-flight tour state) is a stub here; what
// is under test is the catalogue itself.

const LESSONS = {
  lessons: [
    { id: '01', chapter: 'Basics', title: 'First fn', steps: [{}] },
    { id: '02', chapter: 'Basics', title: 'Slots', steps: [{}] },
    { id: '03', chapter: 'Basics', title: 'Free args', steps: [{}] },
    { id: '04', chapter: 'Org', title: 'Invites', steps: [{}], requires: 'manage-users' },
    { id: '05', chapter: 'Org', title: 'Wrap up', steps: [{}] },
  ],
};

function makeWorld(opts) {
  const o = Object.assign({ done: [], caps: [], lessons: LESSONS }, opts);
  const store = new Map();
  if (o.done.length) store.set('graphden.tour.done', JSON.stringify(o.done));
  const document = createDocument();
  const pop = document.createElement('div');
  document.body.appendChild(pop);
  const said = [];
  const started = [];
  const toasts = [];
  const ctx = {
    console,
    JSON,
    Set,
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k),
    },
    document,
    _tourLessons: o.lessons,
    _tourState: null,
    _tourLoadState: () => null,
    _tourFetchLessons: async () => o.lessons,
    _tourEnsureEls: () => ({ pop }),
    _tourSpotHide: () => {},
    _tourNarrow: () => false,
    _tourResume: () => {},
    _tourSay: (m) => said.push(m),
    gdToast: (m) => toasts.push(m),
    startTutorialIsolated: (id) => started.push(id),
    _tourBtn: (label, cls, onClick) => {
      const b = document.createElement('button');
      b.className = 'gd-tour-btn' + (cls ? ' ' + cls : '');
      b.textContent = label;
      b.addEventListener('click', onClick);
      return b;
    },
  };
  ctx.window = ctx;
  ctx.window.graphdenHasCap = (cap) => o.caps.includes(cap);
  vm.createContext(ctx);
  vm.runInContext(source, ctx);
  return {
    ctx, pop, said, started, toasts,
    stored: () => {
      const raw = store.get('graphden.tour.done');
      return raw === undefined ? null : JSON.parse(raw);
    },
    rows: () => pop.querySelector('div.gd-tour-lesson-list').children
      .filter((c) => c.classList.contains('gd-tour-lesson-row')),
    row: (id) => pop.querySelector('div.gd-tour-lesson-list')
      .querySelector('[data-lesson-id="' + id + '"]'),
    foot: () => pop.querySelector('div.gd-tour-picker-foot'),
    footBtn: (label) => pop.querySelector('div.gd-tour-picker-foot').children
      .find((c) => c.textContent.trim() === label) || null,
  };
}

// --- the reading history ----------------------------------------------------

(async () => {
  await test('the history is written as a list, and an empty one is no key at all', () => {
    const w = makeWorld();
    w.ctx._tourMarkDone('01');
    w.ctx._tourMarkDone('03');
    assert(JSON.stringify(w.stored()) === '["01","03"]',
      'marking appends (got: ' + JSON.stringify(w.stored()) + ')');
    assert(w.ctx._tourUnmarkDone('01') === true, 'un-marking a done lesson reports it');
    assert(JSON.stringify(w.stored()) === '["03"]',
      'and removes only that one (got: ' + JSON.stringify(w.stored()) + ')');
    assert(w.ctx._tourUnmarkDone('01') === false,
      'un-marking what is not marked reports nothing happened');
    assert(w.ctx._tourClearDone() === 1, 'clearing answers how many marks went');
    assert(w.stored() === null,
      'an empty history REMOVES the key — a browser that cleared reads like one'
      + ' that never took the tour (got: ' + JSON.stringify(w.stored()) + ')');
    assert(w.ctx._tourDoneSet().size === 0, 'and the set reads back empty');
  });

  // --- what to offer next ---------------------------------------------------

  await test('next-up knows the next lesson from the next UNDONE one', () => {
    const plain = makeWorld({ caps: ['manage-users'] }).ctx;
    let up = plain._tourNextUp('01');
    assert(up.next?.id === '02', 'the following lesson is the obvious next');
    assert(up.unfinished === null,
      'and when it is unread there is nothing else to offer (got: '
      + JSON.stringify(up.unfinished) + ')');

    // 02 read already, 03 not: both are worth a button, under different names.
    const skipped = makeWorld({ done: ['02'], caps: ['manage-users'] }).ctx;
    up = skipped._tourNextUp('01');
    assert(up.next?.id === '02', 'the next one is still the next one');
    assert(up.unfinished?.id === '03',
      'and the first unread one is offered beside it (got: '
      + JSON.stringify(up.unfinished) + ')');

    // A lesson this session cannot run is not a next move.
    const noCaps = makeWorld({ caps: [] }).ctx;
    up = noCaps._tourNextUp('03');
    assert(up.next?.id === '05',
      'a locked lesson is skipped over (got: ' + JSON.stringify(up.next) + ')');
    const withCaps = makeWorld({ caps: ['manage-users'] }).ctx;
    assert(withCaps._tourNextUp('03').next?.id === '04',
      'and offered where the session HAS what it needs');
  });

  await test('at the end it looks backwards rather than going quiet', () => {
    const gap = makeWorld({ done: ['05'], caps: ['manage-users'] }).ctx;
    const up = gap._tourNextUp('05');
    assert(up.next === null, 'nothing follows the last lesson');
    assert(up.unfinished?.id === '01',
      'so the earliest unread one is offered instead (got: '
      + JSON.stringify(up.unfinished) + ')');

    const allDone = makeWorld({
      done: ['01', '02', '03', '04', '05'], caps: ['manage-users'],
    }).ctx;
    const none = allDone._tourNextUp('05');
    assert(none.next === null && none.unfinished === null,
      'with everything read there is nothing to offer, and no empty section');
  });

  // --- the rendered catalogue -----------------------------------------------

  await test('a done row carries an un-mark control; an unread one does not', async () => {
    const w = makeWorld({ done: ['02'] });
    await w.ctx.openTutorialMenu();
    assert(w.rows().length === 5, 'every lesson gets a row (got: ' + w.rows().length + ')');
    assert(w.rows().every((r) => r.children[0].tagName === 'BUTTON'),
      'the lesson button is the row\'s first child');
    assert(/✓ done/.test(w.row('02').textContent), 'the read lesson is marked');
    assert(!!w.row('02').querySelector('button.gd-tour-unmark'),
      'and carries the control that takes the mark off');
    assert(w.row('02').querySelector('button.gd-tour-unmark').parentNode
           === w.row('02'),
      'as a SIBLING of the lesson button, not nested inside it');
    assert(/not done/.test(
      w.row('02').querySelector('button.gd-tour-unmark').getAttribute('aria-label') || ''),
      'labelled for a screen reader, not just an arrow glyph');
    assert(!w.row('01').querySelector('button.gd-tour-unmark'),
      'an unread lesson has nothing to un-mark');
  });

  await test('un-marking one lesson leaves the rest of the history alone', async () => {
    const w = makeWorld({ done: ['01', '02'] });
    await w.ctx.openTutorialMenu();
    w.row('02').querySelector('button.gd-tour-unmark').click();
    assert(JSON.stringify(w.stored()) === '["01"]',
      'only that lesson loses its ✓ (got: ' + JSON.stringify(w.stored()) + ')');
    assert(!/✓ done/.test(w.row('02').textContent),
      'the row re-renders without the mark');
    assert(!w.row('02').querySelector('button.gd-tour-unmark'),
      'and without the control, which now has nothing to undo');
    assert(/✓ done/.test(w.row('01').textContent), 'the other row is untouched');
    assert(w.said.some((m) => /02/.test(m) && /not done/.test(m)),
      'the change is announced — nothing else on screen moves (got: '
      + JSON.stringify(w.said) + ')');
    assert(w.footBtn('Clear progress (1)'),
      'and the footer\'s count follows (got: '
      + JSON.stringify(w.foot().children.map((c) => c.textContent)) + ')');
  });

  await test('clearing everything asks first, and can be backed out of', async () => {
    const w = makeWorld({ done: ['01', '02', '03'] });
    await w.ctx.openTutorialMenu();
    const clear = w.footBtn('Clear progress (3)');
    assert(clear, 'the footer offers the bulk clear, with its count');
    clear.click();
    assert(!w.footBtn('Clear progress (3)'), 'which becomes a confirmation in place');
    assert(/Clear 3 ✓ marks\?/.test(w.foot().textContent),
      'that says how much is about to go (got: ' + w.foot().textContent + ')');
    w.footBtn('Keep them').click();
    assert(JSON.stringify(w.stored()) === '["01","02","03"]',
      'backing out changes nothing');
    assert(w.footBtn('Clear progress (3)'), 'and restores the footer');

    w.footBtn('Clear progress (3)').click();
    w.footBtn('Clear').click();
    assert(w.stored() === null, 'confirming clears the history');
    assert(w.rows().every((r) => !/✓ done/.test(r.textContent)),
      'every row loses its mark');
    assert(w.rows().every((r) => !r.querySelector('button.gd-tour-unmark')),
      'and its un-mark control');
    assert(!w.footBtn('Clear progress (0)') && !w.footBtn('Clear progress (3)'),
      'the offer itself disappears — there is nothing left to clear');
    assert(w.footBtn('Cancel'), 'Cancel stays, at any moment');
  });

  await test('the footer offers no clear when nothing was ever read', async () => {
    const w = makeWorld();
    await w.ctx.openTutorialMenu();
    assert(w.foot().children.length === 1 && w.footBtn('Cancel'),
      'a first-time reader sees Cancel and nothing else (got: '
      + JSON.stringify(w.foot().children.map((c) => c.textContent)) + ')');
  });

  await test('a lesson this session cannot run stays listed, disabled, in its row',
             async () => {
    const w = makeWorld({ done: ['04'] });
    await w.ctx.openTutorialMenu();
    const locked = w.row('04').children[0];
    assert(locked.disabled === true, 'the lesson button is disabled');
    assert(/needs manage-users/.test(w.row('04').textContent),
      'with the reason on the row (got: ' + w.row('04').textContent + ')');
    // A ✓ it can no longer re-read is still the reader's to take back.
    assert(!!w.row('04').querySelector('button.gd-tour-unmark'),
      'and a mark on it is still removable');
    w.row('04').children[0].click();
    assert(w.started.length === 0, 'clicking a locked row starts nothing');
  });

  console.log('');
  console.log(failures ? '✗ ' + failures + ' failed, ' + passes + ' passed'
                       : '✓ ' + passes + ' assertions passed');
  process.exit(failures ? 1 : 0);
})();
