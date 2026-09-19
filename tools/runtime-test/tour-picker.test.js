// Unit tests for editor-tour-picker.js — the lesson catalogue and the reading
// history behind it.
//
// Three things here are only observable from a test that can SEE the rendered
// list, and none of them is something a lesson walk would notice:
//
//   * the ✓ is the reader's own claim, so it can be taken back — one row at a
//     time, or all of them. Both write through `_tourWriteDone`; an empty
//     history REMOVES the key rather than storing `"{}"`, so a browser that
//     never took the tour and one that cleared it read the same.
//   * the history records the EDITION finished (`{id: version}`), and still
//     reads the list it used to be. A lesson whose `:version` moved on after
//     the reader finished it is "updated" — chipped on its row, counted in
//     the account menu — and a lesson their last look at the catalogue did
//     not list is "new". The first look sets the baseline and announces
//     nothing.
//   * the catalogue's header counts what the reader has done, what this
//     session can run, and what is listed — and follows every un-mark.
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
  // `done` as a list is the pre-edition shape; as an object it is the current
  // `{id: version}` one — the catalogue must read both.
  const doneEmpty = Array.isArray(o.done) ? !o.done.length : !Object.keys(o.done).length;
  if (!doneEmpty) store.set('graphden.tour.done', JSON.stringify(o.done));
  if (o.seen) store.set('graphden.tour.seen', JSON.stringify(o.seen));
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
    // editor-tour-spot.js's centring helper — the catalogue calls it instead
    // of clearing the step's inline position itself (2026-09-17).
    _tourCenterPop: (p) => { p.classList.add('gd-tour-centered'); p.style.left = ''; p.style.top = ''; },
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
    seen: () => {
      const raw = store.get('graphden.tour.seen');
      return raw === undefined ? null : JSON.parse(raw);
    },
    counts: () => (pop.querySelector('div.gd-tour-counts') || { children: [] }).children
      .map((c) => c.textContent),
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
  await test('the history records the edition finished, and an empty one is no key at all', () => {
    const w = makeWorld();
    w.ctx._tourMarkDone('01', 1);
    w.ctx._tourMarkDone('03', 2);
    assert(JSON.stringify(w.stored()) === '{"01":1,"03":2}',
      'marking records id → edition (got: ' + JSON.stringify(w.stored()) + ')');
    w.ctx._tourMarkDone('05');
    assert(w.stored()['05'] === 1, 'an edition nobody named is 1');
    assert(w.ctx._tourUnmarkDone('01') === true, 'un-marking a done lesson reports it');
    assert(JSON.stringify(w.stored()) === '{"03":2,"05":1}',
      'and removes only that one (got: ' + JSON.stringify(w.stored()) + ')');
    assert(w.ctx._tourUnmarkDone('05') === true, 'un-marking the other');
    assert(w.ctx._tourUnmarkDone('01') === false,
      'un-marking what is not marked reports nothing happened');
    assert(w.ctx._tourClearDone() === 1, 'clearing answers how many marks went');
    assert(w.stored() === null,
      'an empty history REMOVES the key — a browser that cleared reads like one'
      + ' that never took the tour (got: ' + JSON.stringify(w.stored()) + ')');
    assert(w.ctx._tourDoneSet().size === 0, 'and the set reads back empty');
  });

  await test('the pre-edition list is still read, as edition 1, and rewritten on the next change', () => {
    const w = makeWorld({ done: ['01', '03'] });
    assert([...w.ctx._tourDoneSet()].join() === '01,03',
      'a list of ids reads as done (got: ' + [...w.ctx._tourDoneSet()].join() + ')');
    assert(w.ctx._tourDoneMap().get('03') === 1,
      'finished when 1 was the only edition there was');
    w.ctx._tourMarkDone('02', 1);
    assert(JSON.stringify(w.stored()) === '{"01":1,"03":1,"02":1}',
      'the next write carries everything over in the new shape (got: '
      + JSON.stringify(w.stored()) + ')');
  });

  // --- editions and news ---------------------------------------------------

  const VERSIONED = {
    lessons: [
      { id: '01', chapter: 'Basics', title: 'First fn', steps: [{}], version: 1 },
      { id: '02', chapter: 'Basics', title: 'Slots', steps: [{}], version: 2 },
      { id: '03', chapter: 'Basics', title: 'Free args', steps: [{}], version: 1 },
      { id: '04', chapter: 'Org', title: 'Invites', steps: [{}], requires: 'manage-users' },
      { id: '05', chapter: 'Org', title: 'Wrap up', steps: [{}], version: 1 },
    ],
  };

  await test('a lesson finished at an older edition is stale; one finished at the current is not', () => {
    const w = makeWorld({ lessons: VERSIONED, done: { '01': 1, '02': 1, '03': 1 } });
    const by = (id) => VERSIONED.lessons.find((l) => l.id === id);
    assert(w.ctx._tourVersionOf(by('02')) === 2 && w.ctx._tourVersionOf(by('04')) === 1,
      'the edition is :version, 1 when the script says nothing');
    assert(w.ctx._tourStale(by('02')) === true, '02 moved to 2 after a finish at 1');
    assert(w.ctx._tourStale(by('01')) === false, '01 is where it was finished');
    assert(w.ctx._tourStale(by('05')) === false, 'an unread lesson is not stale, whatever its edition');
    // The pre-edition list reads as 1 everywhere — so lesson 02 is stale for
    // a reader who finished it before editions existed, which is the truth.
    const legacy = makeWorld({ lessons: VERSIONED, done: ['02'] });
    assert(legacy.ctx._tourStale(by('02')) === true,
      'a list-shaped history finished 02 at edition 1');
  });

  await test('the first look sets the baseline and is not news', () => {
    const w = makeWorld({ lessons: VERSIONED });
    const news = w.ctx._tourNews(VERSIONED);
    assert(news.count === 0 && !news.fresh.length && !news.updated.length,
      'nothing is new to someone who has never looked (got: ' + JSON.stringify(news) + ')');
    assert(JSON.stringify(w.seen()) === '{"01":1,"02":2,"03":1,"04":1,"05":1}',
      'and the catalogue as it stands becomes the baseline (got: '
      + JSON.stringify(w.seen()) + ')');
  });

  await test('news = lessons not listed last time + finished lessons whose edition moved', () => {
    // Last look: 01 at 1, 02 at 1, 03 at 1 — no 04, no 05. Finished 02 (at 1)
    // and 03 (at 1). Now 02 is at 2, 04 and 05 are listed.
    const w = makeWorld({
      lessons: VERSIONED,
      seen: { '01': 1, '02': 1, '03': 1 },
      done: { '02': 1, '03': 1, '05': 1 },
    });
    const news = w.ctx._tourNews(VERSIONED);
    assert(JSON.stringify(news.fresh) === '["04"]',
      '04 is new — 05 is not listed last time either, but the reader already'
      + ' finished it (got: ' + JSON.stringify(news.fresh) + ')');
    assert(JSON.stringify(news.updated) === '["02"]',
      '02 moved to edition 2 after the reader finished 1 (got: '
      + JSON.stringify(news.updated) + ')');
    assert(news.count === 2, 'the menu count is both together (got: ' + news.count + ')');
    assert(JSON.stringify(w.seen()) === '{"01":1,"02":1,"03":1}',
      'asking does not record a look — only opening the catalogue does');

    // An UNREAD lesson whose edition moved is nobody's news.
    const unread = makeWorld({ lessons: VERSIONED, seen: { '01': 1, '02': 1, '03': 1, '04': 1, '05': 1 } });
    const quiet = unread.ctx._tourNews(VERSIONED);
    assert(quiet.count === 0,
      'a bump on a lesson the reader never finished counts for nothing (got: '
      + JSON.stringify(quiet) + ')');

    // Once the reader has SEEN the bump listed, it stops counting — the row
    // keeps its chip (the ✓ is still stale), the menu goes quiet.
    const looked = makeWorld({ lessons: VERSIONED, seen: { '01': 1, '02': 2, '03': 1, '04': 1, '05': 1 }, done: { '02': 1 } });
    assert(looked.ctx._tourNews(VERSIONED).count === 0,
      'a bump already seen in the catalogue is no longer news');
    assert(looked.ctx._tourStale(VERSIONED.lessons[1]) === true,
      'but the lesson is still stale until finished again');
  });

  await test('gdTourNews answers the menu from the fetched scripts', async () => {
    const w = makeWorld({ lessons: VERSIONED, seen: { '01': 1 } });
    const news = await w.ctx.gdTourNews();
    assert(news.count === 4 && JSON.stringify(news.fresh) === '["02","03","04","05"]',
      'everything listed since the baseline is new (got: ' + JSON.stringify(news) + ')');
  });

  await test('the catalogue chips new and updated rows, records the look, and counts', async () => {
    const w = makeWorld({
      lessons: VERSIONED, caps: ['manage-users'],
      seen: { '01': 1, '02': 1, '03': 1, '04': 1 },
      done: { '01': 1, '02': 1 },
    });
    await w.ctx.openTutorialMenu();
    assert(w.row('05').classList.contains('gd-tour-lesson-row-new')
           && /new/.test(w.row('05').querySelector('.gd-tour-lesson-badge-new')?.textContent || ''),
      'the lesson the last look did not list is chipped "new"');
    assert(w.row('02').classList.contains('gd-tour-lesson-row-updated')
           && /updated/.test(w.row('02').querySelector('.gd-tour-lesson-badge-updated')?.textContent || ''),
      'the lesson finished at an older edition is chipped "updated"');
    assert(/✓ done/.test(w.row('02').textContent) && !!w.row('02').querySelector('button.gd-tour-unmark'),
      'and stays done — the ✓ and its un-mark control are still there');
    assert(!w.row('01').querySelector('.gd-tour-lesson-badge')
           && !w.row('03').querySelector('.gd-tour-lesson-badge'),
      'a lesson finished at its current edition, or unread and unchanged, carries no chip');
    assert(JSON.stringify(w.seen()) === '{"01":1,"02":2,"03":1,"04":1,"05":1}',
      'opening the catalogue records the look (got: ' + JSON.stringify(w.seen()) + ')');
    assert(JSON.stringify(w.counts()) === '["2 done","5 available","5 lessons","1 new","1 updated"]',
      'the header counts done / available / listed, plus what is news (got: '
      + JSON.stringify(w.counts()) + ')');

    // A second look: the same catalogue is no longer news — the "new" chip is
    // gone, the "updated" one stays with the stale ✓.
    await w.ctx.openTutorialMenu();
    assert(!w.row('05').querySelector('.gd-tour-lesson-badge'),
      'seen once, a new lesson is just a lesson');
    assert(!!w.row('02').querySelector('.gd-tour-lesson-badge-updated'),
      'a stale ✓ is chipped until the lesson is finished again');
    assert(JSON.stringify(w.counts()) === '["2 done","5 available","5 lessons","1 updated"]',
      'and the header says so (got: ' + JSON.stringify(w.counts()) + ')');
  });

  await test('the counts follow the session\'s capabilities and the reader\'s un-marks', async () => {
    const w = makeWorld({ done: { '01': 1, '02': 1 } });
    await w.ctx.openTutorialMenu();
    assert(JSON.stringify(w.counts()) === '["2 done","4 available","5 lessons"]',
      'a locked lesson is listed but not available (got: ' + JSON.stringify(w.counts()) + ')');
    w.row('02').querySelector('button.gd-tour-unmark').click();
    assert(w.counts()[0] === '1 done',
      'un-marking one lesson moves the count (got: ' + JSON.stringify(w.counts()) + ')');
    w.footBtn('Clear progress (1)').click();
    w.footBtn('Clear').click();
    assert(w.counts()[0] === '0 done',
      'clearing the history zeroes it (got: ' + JSON.stringify(w.counts()) + ')');
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
    assert(JSON.stringify(w.stored()) === '{"01":1}',
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
      'backing out changes nothing — not even the shape of a pre-edition history');
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
