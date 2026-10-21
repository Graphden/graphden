// Tutorial catalogue, editions, Escape, mobile, lens, and funnel.
// Each suite owns its browser context and cleans tutorial fixtures.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {
  hardCleanup, waitTourTitle, clickTourButton, tourTitle, tourWhere,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-picker — Tutorial catalogue, editions, Escape, mobile, lens, and funnel.');
  const startedAt = Date.now();
  const checkpoint = (label) => console.log('  ' + label + ' (' + Math.round((Date.now() - startedAt) / 1000) + 's)');
  let failed = false;
  try {
    await hardCleanup(page);
    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    // ---------- picker: a lesson this session cannot run is LOCKED --------
    // The org lessons drive panels that need `manage-users` / `manage-grants`.
    // On this stack (no tenancy addon) the capability probe is absent, so the
    // picker must list them disabled with the reason on the row — a catalogue
    // entry, not a dead end.
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof window.openTutorialMenu === 'function',
      null, {timeout: 60000, polling: 200});
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 20000});
    // A lesson row is a CONTAINER since the ✓ became removable: the lesson
    // button plus, once read, the control that takes the mark off. The state
    // under test is the button's.
    const locked = await page.evaluate(() => {
      const list = document.querySelector('.gd-tour-lesson-list');
      const row = Array.from(list.children).find((c) => /^27 ·/.test(c.textContent.trim()));
      return row ? {text: row.textContent.trim(),
                    disabled: row.querySelector('.gd-tour-btn').disabled === true,
                    chapter: !!Array.from(list.children).find(
                      (c) => c.className.includes('gd-tour-chapter')
                          && c.textContent.trim() === 'Your organization')}
                 : null;
    });
    assert(locked, 'lesson 27 is listed in the picker');
    assert(locked.chapter, 'its chapter heading is rendered');
    assert(locked.disabled, 'it is disabled where the capability is missing');
    assert(/needs manage-users/.test(locked.text),
      'the row says what it needs (got: ' + locked.text + ')');
    // A `:requires` value can also be a named CONDITION rather than a
    // capability. On this stack there is no tenancy addon, so the cross-org
    // lesson must read as needing an organization — and say so in those
    // words, not as a capability name.
    const orgLocked = await page.evaluate(() => {
      const list = document.querySelector('.gd-tour-lesson-list');
      const row = Array.from(list.children).find((c) => /^33 ·/.test(c.textContent.trim()));
      return row ? {text: row.textContent.trim(),
                    disabled: row.querySelector('.gd-tour-btn').disabled === true} : null;
    });
    assert(orgLocked, 'lesson 33 is listed');
    assert(orgLocked.disabled, 'it is disabled without organizations');
    assert(/needs an organization/.test(orgLocked.text),
      'the row names the CONDITION, not a capability (got: ' + orgLocked.text + ')');
    // The mirror case: a condition that HOLDS here. This stack is
    // single-tenant, so the Assets panel exists and lesson 25 must be
    // OFFERED, not locked — an inverted `assets` signal would hide the
    // lesson from the only sessions that can run it.
    const assetsLesson = await page.evaluate(() => {
      const list = document.querySelector('.gd-tour-lesson-list');
      const row = Array.from(list.children).find((c) => /^25 ·/.test(c.textContent.trim()));
      return row ? {text: row.textContent.trim(),
                    disabled: row.querySelector('.gd-tour-btn').disabled === true} : null;
    });
    assert(assetsLesson, 'lesson 25 is listed');
    assert(!assetsLesson.disabled,
      'lesson 25 is offered on a single-tenant stack (got: ' + assetsLesson.text + ')');
    checkpoint('picker: org lessons locked, the assets lesson offered');

    // ---------- The catalogue as a catalogue ----------
    // Twenty-five lessons in five chapters is taller than a laptop viewport.
    // Three affordances make it usable — resume, filter, done marks — and each
    // has already broken once in a way no lesson walk would notice: the panel
    // ran off the bottom with its Cancel unreachable (a flex column that
    // squeezed its rows instead of scrolling), and opened mid-lesson it kept
    // the previous step's anchored position.
    await page.evaluate(() => {
      localStorage.setItem('graphden.tour.done', JSON.stringify(['01']));
      localStorage.setItem('graphden.tour',
        JSON.stringify({lessonId: '04', step: 2, created: []}));
    });
    await page.reload();
    // Script availability precedes initGraph's async saved-lesson restore.
    // Open the catalogue only after that restore has published its real step.
    await waitTourTitle(page, 'Make the first child', 60000);
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 30000});
    const cat = await page.evaluate(() => {
      const pop = document.querySelector('#gd-tour-pop');
      const list = document.querySelector('.gd-tour-lesson-list');
      const foot = document.querySelector('.gd-tour-picker-foot');
      const r = pop.getBoundingClientRect();
      const fr = foot.getBoundingClientRect();
      const filter = document.querySelector('.gd-tour-filter');
      filter.value = 'branch';
      filter.dispatchEvent(new Event('input'));
      // Chapter headings are children too — count the lesson ROWS.
      const filtered = Array.from(list.children)
        .filter((c) => c.classList.contains('gd-tour-lesson-row'))
        .map((c) => c.textContent.trim());
      filter.value = '';
      filter.dispatchEvent(new Event('input'));
      const done = Array.from(list.children)
        .find((c) => /^01 ·/.test(c.textContent.trim()));
      return {
        resume: document.querySelector('.gd-tour-btn-resume')?.textContent.trim() || null,
        fitsViewport: r.top >= 0 && r.bottom <= window.innerHeight,
        listScrolls: list.scrollHeight > list.clientHeight + 4,
        cancelReachable: fr.bottom <= window.innerHeight && fr.top >= 0,
        filtered,
        doneMarked: !!done && /done/.test(done.textContent),
        chapters: Array.from(document.querySelectorAll('.gd-tour-chapter'))
          .map((c) => c.textContent.trim()),
        // The text-only lessons are rows too (anchors, not start buttons),
        // every toured row carries one quiet ↗ with its summary in the
        // tooltip, rather than another link and paragraph beneath the row.
        textRows: Array.from(list.querySelectorAll('.gd-tour-lesson-row-text a.gd-tour-btn-text'))
          .map((a) => [a.textContent.trim().slice(0, 2), a.getAttribute('href')]),
        readBtns: list.querySelectorAll('.gd-tour-lesson-row:not(.gd-tour-lesson-row-text) .gd-tour-read-btn').length,
        tourRows: list.querySelectorAll('.gd-tour-lesson-row:not(.gd-tour-lesson-row-text)').length,
        doneReadTitle: done?.querySelector('.gd-tour-read-btn')?.title || null,
        doneReadDescription: done?.querySelector('.gd-tour-read-btn')
          ?.getAttribute('aria-description') || null,
        addsTotal: list.querySelectorAll('.gd-tour-read').length,
        textCount: document.querySelector('.gd-tour-count-text')?.textContent || null,
      };
    });
    assert(cat.textRows.length === 2
      && cat.textRows.every(([id, href]) => /\/tutorial\/\d\d-[a-z0-9-]+$/.test(href || ''))
      && cat.textRows.map(([id]) => id).join(',') === '31,34',
      'the two external setup lessons open their written instructions (got: '
      + JSON.stringify(cat.textRows) + ')');
    assert(cat.readBtns === cat.tourRows && cat.tourRows > 30,
      'every toured row carries a ↗ to its written lesson (' + cat.readBtns + '/' + cat.tourRows + ')');
    assert(/^Read the written lesson 01/.test(cat.doneReadTitle || '')
      && /In the text: /.test(cat.doneReadTitle),
      'the written lesson summary is available in its tooltip');
    assert(/In the text: /.test(cat.doneReadDescription || ''),
      'the same summary is accessible on focus');
    assert(cat.addsTotal === 0,
      'the catalogue has no duplicate written links or expanded summaries');
    assert(cat.textCount === '2 text only',
      'the header counts the text-only lessons apart (got: ' + cat.textCount + ')');
    assert(/^Continue 04 · Slots and bindings — step 3\//.test(cat.resume || ''),
      'a paused lesson resumes from where it stopped (got: ' + cat.resume + ')');
    assert(cat.fitsViewport, 'the catalogue fits the window');
    assert(cat.listScrolls, 'and the LIST scrolls rather than squeezing its rows');
    assert(cat.cancelReachable, 'Cancel stays reachable at any scroll position');
    assert(cat.filtered.length === 1 && /^23 · Branches/.test(cat.filtered[0]),
      'the filter narrows to one lesson (got: ' + JSON.stringify(cat.filtered) + ')');
    assert(cat.doneMarked, 'a finished lesson is marked done');
    assert(cat.chapters.length === new Set(cat.chapters).size,
      'each chapter heading appears once (got: ' + cat.chapters.join(', ') + ')');
    checkpoint('picker: resume + filter + done marks, list scrolls, Cancel pinned');

    // ---------- The ✓ is the reader's, so it comes back off ----------------
    // A lesson skipped step-by-step to see the end, or read so long ago it
    // needs re-reading, is marked done and should not be. Both ways out —
    // one row, or the whole history — go through the SAME localStorage the
    // marks live in, and the unit test (tools/runtime-test/tour-picker.test.js)
    // pins their bookkeeping; what only a browser can show is that the
    // controls are really there, really clickable, and that the list agrees
    // with storage afterwards.
    await page.evaluate(() => {
      localStorage.setItem('graphden.tour.done', JSON.stringify(['01', '02', '03']));
      localStorage.removeItem('graphden.tour');
    });
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof window.openTutorialMenu === 'function',
      null, {timeout: 60000, polling: 200});
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 30000});
    const unmarked = await page.evaluate(() => {
      const row = document.querySelector('[data-lesson-id="02"]');
      const undo = row.querySelector('.gd-tour-unmark');
      const label = undo?.getAttribute('aria-label') || '';
      undo.click();
      const after = document.querySelector('[data-lesson-id="02"]');
      return {
        label,
        stillMarked: /✓ done/.test(after.textContent),
        controlGone: !after.querySelector('.gd-tour-unmark'),
        neighbourKept: /✓ done/.test(
          document.querySelector('[data-lesson-id="01"]').textContent),
        stored: JSON.parse(localStorage.getItem('graphden.tour.done') || '{}'),
      };
    });
    assert(/not done/.test(unmarked.label),
      'the un-mark control is labelled for a screen reader (got: '
      + unmarked.label + ')');
    assert(!unmarked.stillMarked && unmarked.controlGone,
      'clicking it takes the ✓ off that row');
    assert(unmarked.neighbourKept, 'and leaves the rows around it alone');
    // The history was seeded in its pre-edition LIST shape, keyed by number;
    // the first write carries it over to `{slug: edition}` — the lesson's
    // slug is its identity, the number its position (editions 2026-09-19,
    // slugs 2026-09-20).
    assert(JSON.stringify(unmarked.stored) === '{"fn-defs":1,"parents-and-inheritance":1}',
      'the stored history agrees, in the slug-keyed edition shape (got: '
      + JSON.stringify(unmarked.stored) + ')');

    const cleared = await page.evaluate(() => {
      const footBtn = (label) => Array.from(
        document.querySelectorAll('.gd-tour-picker-foot .gd-tour-btn'))
        .find((b) => b.textContent.trim() === label) || null;
      footBtn('Clear progress (2)').click();
      const asked = document.querySelector('.gd-tour-picker-foot').textContent;
      footBtn('Keep them').click();
      const afterKeep = JSON.parse(localStorage.getItem('graphden.tour.done') || '{}');
      footBtn('Clear progress (2)').click();
      footBtn('Clear').click();
      return {
        asked,
        afterKeep,
        stored: localStorage.getItem('graphden.tour.done'),
        marksLeft: document.querySelectorAll('.gd-tour-lesson-row-done').length,
        offerGone: !footBtn('Clear progress (0)') && !footBtn('Clear progress (2)'),
        cancelKept: !!footBtn('Cancel'),
      };
    });
    assert(/Clear 2 ✓ marks\?/.test(cleared.asked),
      'the bulk clear asks first, and says how much goes (got: '
      + cleared.asked + ')');
    assert(JSON.stringify(cleared.afterKeep) === '{"fn-defs":1,"parents-and-inheritance":1}',
      'backing out of it changes nothing (got: '
      + JSON.stringify(cleared.afterKeep) + ')');
    assert(cleared.stored === null,
      'confirming clears the key outright — a cleared browser reads like one'
      + ' that never took the tour (got: ' + cleared.stored + ')');
    assert(cleared.marksLeft === 0, 'and every row loses its ✓');
    assert(cleared.offerGone, 'the offer goes with them — nothing left to clear');
    assert(cleared.cancelKept, 'Cancel stays reachable throughout');
    await page.evaluate(() => {
      localStorage.removeItem('graphden.tour.done');
      localStorage.removeItem('graphden.tour');
    });
    checkpoint('picker: one ✓ removable, the whole history clearable behind a confirm');

    // ---------- Editions: the menu count, the chips, the counters ----------
    // A lesson's `:version` is bumped when its flow changes; the reader's ✓
    // records the edition they finished. What only a browser can show: the
    // count really lands on the account menu's row, the chips really sit on
    // the rows, and the header's counters agree with storage. The last look
    // listed 01–03 at their shipped editions, so every other lesson is "new".
    // Only 02 changes after that look; later shipped edition bumps must not
    // accidentally turn 01 into a second updated lesson in this fixture.
    await page.evaluate(async () => {
      const ls = await _tourFetchLessons();
      const edition = (id) => _tourVersionOf(ls.lessons.find((lesson) => lesson.id === id));
      localStorage.setItem('graphden.tour.done', JSON.stringify({'01': edition('01'), '02': edition('02')}));
      localStorage.setItem('graphden.tour.seen', JSON.stringify({
        '01': edition('01'), '02': edition('02'), '03': edition('03'),
      }));
      localStorage.removeItem('graphden.tour');
    });
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof window.openTutorialMenu === 'function',
      null, {timeout: 60000, polling: 200});
    const staged = await page.evaluate(async () => {
      const ls = await _tourFetchLessons();
      const changed = ls.lessons.find((lesson) => lesson.id === '02');
      changed.version = _tourVersionOf(changed) + 1;
      return ls.lessons.length;
    });
    await page.click('#auth-lock-btn');
    await page.waitForFunction(
      () => !document.getElementById('auth-popover')?.classList.contains('hidden'),
      null, {timeout: 5000});
    await page.waitForSelector('#auth-popover .auth-menu-count', {timeout: 5000});
    const menuCount = await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('#auth-popover .auth-menu-item'))
        .find((b) => b.dataset.item === 'Interactive tutorial');
      const c = row?.querySelector('.auth-menu-count');
      return {onRow: !!c, text: c?.textContent, title: c?.title || ''};
    });
    assert(menuCount.onRow, 'the count sits on the Interactive tutorial row');
    assert(menuCount.text === String(staged - 3 + 1),
      'and counts the lessons not listed last time plus the finished one that moved'
      + ' (got: ' + menuCount.text + ', expected ' + (staged - 3 + 1) + ')');
    assert(/new/.test(menuCount.title) && /1 updated/.test(menuCount.title),
      'its tooltip says which is which (got: ' + menuCount.title + ')');
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 30000});
    const chipped = await page.evaluate(() => {
      const counts = Array.from(document.querySelectorAll('.gd-tour-counts .gd-tour-count'))
        .map((c) => c.textContent);
      const row = (id) => document.querySelector('[data-lesson-id="' + id + '"]');
      return {
        counts,
        updated02: !!row('02').querySelector('.gd-tour-lesson-badge-updated'),
        stillDone02: /✓ done/.test(row('02').textContent),
        plain01: !row('01').querySelector('.gd-tour-lesson-badge'),
        plain03: !row('03').querySelector('.gd-tour-lesson-badge'),
        new04: !!row('04').querySelector('.gd-tour-lesson-badge-new'),
        newRows: document.querySelectorAll('.gd-tour-lesson-row-new').length,
        seen: Object.keys(JSON.parse(localStorage.getItem('graphden.tour.seen') || '{}')).length,
      };
    });
    assert(chipped.updated02 && chipped.stillDone02,
      'the finished lesson whose edition moved is chipped "updated" and keeps its ✓');
    assert(chipped.plain01 && chipped.plain03,
      'a lesson finished at its edition, or unread and unchanged, carries no chip');
    assert(chipped.new04 && chipped.newRows === staged - 3,
      'every lesson the last look did not list is chipped "new" (got: '
      + chipped.newRows + ' of ' + (staged - 3) + ')');
    assert(chipped.counts[0] === '2 done' && chipped.counts[2] === staged + ' lessons',
      'the header counts done and listed (got: ' + JSON.stringify(chipped.counts) + ')');
    assert(/^\d+ available$/.test(chipped.counts[1] || ''),
      'and what this session can run (got: ' + JSON.stringify(chipped.counts) + ')');
    assert(chipped.counts.includes((staged - 3) + ' new') && chipped.counts.includes('1 updated'),
      'plus the news (got: ' + JSON.stringify(chipped.counts) + ')');
    assert(chipped.seen === staged,
      'opening the catalogue records the look — the menu goes quiet next time'
      + ' (got: ' + chipped.seen + ' seen)');
    await page.evaluate(() => {
      localStorage.removeItem('graphden.tour.done');
      localStorage.removeItem('graphden.tour.seen');
      localStorage.removeItem('graphden.tour');
    });
    checkpoint('editions: menu count, new/updated chips, done/available/total counters');

    // ---------- Escape belongs to whatever is on TOP ----------
    // Every lesson tells the reader to open something — a picker, a panel, a
    // dialog — and Escape is how people close things. The tour also ends on
    // Escape, so "dismissed the panel" must not read as "quit the lesson".
    // It regressed once already: the Packages panel closes through the shared
    // popover helper and was missing from the tour's list of dismissible
    // surfaces, so closing it killed lesson 32 mid-walk.
    await page.goto(BASE + '/?tutorial=32');
    await waitTourTitle(page, 'Sharing more than one fn', 150000);
    await page.waitForSelector('#gd-pkg-chip', {timeout: 30000});
    await page.evaluate(() => document.getElementById('gd-pkg-chip').click());
    await page.waitForSelector('[data-packages-panel]', {timeout: 30000});
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#gd-pkg-pop'),
      null, {timeout: 10000, polling: 200});
    assert(await tourTitle(page) === 'Sharing more than one fn',
      'the tour survives closing a panel with Escape (got: ' + await tourTitle(page) + ')');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 15000, polling: 200});
    checkpoint('escape: closes the panel first, ends the tour only when alone');

    // ---------- On a phone the tour is a bottom sheet ----------
    // A 360px popover on a 390px screen lands on the Explorer, which is where
    // the early lessons tell the reader to click — lesson 01 dead-ended at
    // "click +" because + was underneath it. Checked here at BOTH sizes in
    // one process; the whole suite can also be run at a phone viewport with
    // GRAPHDEN_VIEWPORT=390x844.
    // Explicit sizes on BOTH sides: this file also runs under
    // GRAPHDEN_VIEWPORT=390x844, and "restore whatever we started at" then
    // asserts desktop behaviour at a phone size.
    const desktop = {width: 1400, height: 900};
    await page.setViewportSize({width: 390, height: 844});
    await page.goto(BASE + '/?tutorial=01');
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    const phone = await page.evaluate(() => {
      const pop = document.querySelector('#gd-tour-pop');
      const r = pop.getBoundingClientRect();
      const list = document.querySelector('#entity-list')?.getBoundingClientRect();
      return {
        sheet: pop.classList.contains('gd-tour-sheet'),
        fullWidth: Math.round(r.width) === window.innerWidth,
        atBottom: Math.abs(r.bottom - window.innerHeight) <= 1,
        leavesRoom: !!list && r.top > list.top,
        reservesScroll: getComputedStyle(document.querySelector('#side-menu'))
          .paddingBottom !== '0px',
      };
    });
    assert(phone.sheet && phone.fullWidth && phone.atBottom,
      'the step docks to the bottom edge, full width (got: ' + JSON.stringify(phone) + ')');
    assert(phone.leavesRoom, 'and leaves the panel the lesson points at visible');
    assert(phone.reservesScroll,
      'the Explorer reserves the sheet height so a row under it can scroll clear');
    await page.evaluate(() => window.openTutorialMenu());
    await page.waitForSelector('.gd-tour-lesson-list', {timeout: 15000});
    assert(await page.evaluate(() =>
      document.querySelector('#gd-tour-pop').classList.contains('gd-tour-sheet')),
      'the catalogue is a sheet too');
    await page.setViewportSize(desktop);
    await page.goto(BASE + '/?tutorial=01');
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    assert(!await page.evaluate(() =>
      document.querySelector('#gd-tour-pop').classList.contains('gd-tour-sheet')),
      'and a desktop window keeps the anchored popover');
    await page.evaluate(() => { localStorage.removeItem('graphden.tour'); });
    checkpoint('mobile: step + catalogue dock as a sheet, desktop unchanged');

    // ---------- A lens the reader left on must not dead-end a step ----------
    // The Explorer's kind lens hides rows; a step waiting on a fn the lens
    // hides waits forever, under a popover that says "advances
    // automatically when done". Same trap the collapsed-Explorer arm
    // already handles, and only discoverable by walking it as a person —
    // a guard clicks rows by selector, hidden or not.
    // The sections above leave a lesson resumable in localStorage; on a
    // plain `/` it would resume and clear the very lens this probe sets
    // (that clearing IS the behaviour under test — but only for lesson
    // 05, below). Drop it first, and click the chip only once the tree
    // exists (a pre-graph toggle has nothing to flip).
    await page.goto(BASE + '/');
    await page.evaluate(() => {
      localStorage.removeItem('graphden.tour');
      // Lesson 17's walk switched the ✓ tests filter on and it persists; with
      // the lesson's tests namespace cleaned up, that filter hides EVERY row.
      localStorage.removeItem('graphden.explorer.filters');
    });
    await page.goto(BASE + '/');
    await page.waitForSelector('.kind-toggle', {timeout: 60000});
    await page.waitForSelector('#entity-list .ns-header', {timeout: 60000});
    await page.evaluate(() => {
      const tests = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((c) => /tests/.test(c.textContent));
      tests.click();
    });
    await page.waitForFunction(() => {
      const all = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((c) => /all/.test(c.textContent));
      return all && all.getAttribute('aria-pressed') === 'false';
    }, null, {timeout: 15000, polling: 200});
    await page.goto(BASE + '/?tutorial=08');
    await waitTourTitle(page, 'Types are fn-rows too', 150000);
    assert(await clickTourButton(page, 'Next'), 'lens probe: opening Next');
    await waitTourTitle(page, 'Find str-len', 30000);
    await page.fill('input[placeholder="Filter..."]', 'str-len');
    await page.waitForFunction(() => {
      const row = Array.from(document.querySelectorAll('#entity-list .entity-item'))
        .find((e) => e.querySelector('.name')?.textContent.trim() === 'str-len');
      return row && !row.hasAttribute('hidden');
    }, null, {timeout: 30000, polling: 300});
    const lensAfter = await page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('.kind-toggle'))
        .find((c) => /all/.test(c.textContent));
      return all.getAttribute('aria-pressed');
    });
    assert(lensAfter === 'true',
      'the tour cleared the lens that was hiding the step\'s fn (all=' + lensAfter + ')');
    await page.evaluate(() => { localStorage.removeItem('graphden.tour'); });
    checkpoint('lens: a kind lens hiding the step\'s fn is cleared, once');

    // ---------- The funnel actually moves ----------
    // 25 lessons and no way to know where a reader stops was the gap; the
    // tour posts three events per lesson into the counters `/metrics`
    // already exposes. A client that reports nothing looks exactly like a
    // tutorial nobody runs, so assert the number moves.
    const metricsBefore = await api(page, 'GET', '/metrics');
    const startedBefore = metricsBefore?.counters?.['tour-started-01'] || 0;
    await page.goto(BASE + '/?tutorial=01');
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    assert(await clickTourButton(page, 'Next'), 'funnel probe: first Next');
    // Both counter POSTs are fire-and-forget from the page; poll /metrics
    // until they land instead of betting 1.5s that they have.
    let metricsAfter = null;
    for (const deadline = Date.now() + 30000; Date.now() < deadline;) {
      metricsAfter = await api(page, 'GET', '/metrics');
      if ((metricsAfter?.counters?.['tour-started-01'] || 0) > startedBefore
          && (metricsAfter?.counters?.['tour-step-01-1'] || 0) >= 1) break;
      await new Promise((r) => setTimeout(r, 100));
    }
    const startedAfter = metricsAfter?.counters?.['tour-started-01'] || 0;
    const stepAfter = metricsAfter?.counters?.['tour-step-01-1'] || 0;
    assert(startedAfter === startedBefore + 1,
      'starting lesson 01 counted one start (' + startedBefore + ' → ' + startedAfter + ')');
    assert(stepAfter >= 1,
      'and the advance counted its step bucket (tour-step-01-1 = ' + stepAfter + ')');
    await page.evaluate(() => { localStorage.removeItem('graphden.tour'); });
    checkpoint('funnel: started + step counters moved on /metrics');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-picker-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-picker-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
