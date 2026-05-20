// DOM-building smoke tests for the type-resolution surfaces:
//   appendClosedEnumSection / appendEffectConstraintSection /
//   appendPopoverSection / appendResolutionSection (incl. multi-
//   override visualization + onNavigate clickable wiring).
//
// Each test constructs a synthetic prov object, calls the renderer
// into a detached <div>, and asserts on the resulting DOM. No live
// editor state is required beyond the bundled JS.
//
// Run:  node type-system-ui-resolution.test.js

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('type-system-ui-resolution — DOM rendering of the provenance popover');
  try {
    // appendClosedEnumSection — DOM rendering of allowed values.
    const enumDom = await page.evaluate(() => {
      const host = document.createElement('div');
      appendClosedEnumSection(host, {
        base: 'keyword',
        members: [
          { value: ':red',   label: ':red' },
          { value: ':green', label: ':green' },
        ],
      });
      return {
        sectionCount: host.querySelectorAll('.provenance-popover-enum').length,
        chipCount:    host.querySelectorAll('.provenance-popover-enum-chip').length,
        head:         host.querySelector('.type-inline-resolution-head')?.textContent,
      };
    });
    assert(enumDom.sectionCount === 1,
           'appendClosedEnumSection creates one wrapper section');
    assert(enumDom.chipCount === 2,
           'two members → two chips rendered');
    assert(enumDom.head === 'Allowed values',
           'section header reads "Allowed values"');

    // appendEffectConstraintSection — DOM rendering of slot-effect chip row.
    const effectDom = await page.evaluate(() => {
      const host1 = document.createElement('div');
      appendEffectConstraintSection(host1, new Set([])); // pure
      const host2 = document.createElement('div');
      appendEffectConstraintSection(host2, new Set(['env', 'db']));
      return {
        pureLabel: host1.querySelector('.type-inline-effects-pure')?.textContent,
        chipCount: host2.querySelectorAll('.effects-chip').length,
      };
    });
    assert(effectDom.pureLabel === 'pure',
           'empty set → "pure" pill');
    assert(effectDom.chipCount === 2,
           'concrete set of two effects → two chips');

    // appendPopoverSection — shared header + body helper.
    const sectionDom = await page.evaluate(() => {
      const host = document.createElement('div');
      const body = document.createElement('span');
      body.textContent = 'body content';
      appendPopoverSection(host, 'Header Label', body, 'test-class');
      const sect = host.querySelector('.test-class');
      return {
        hasSection: !!sect,
        headText: sect?.querySelector('.type-inline-resolution-head')?.textContent,
        bodyText: sect?.querySelector('span')?.textContent,
      };
    });
    assert(sectionDom.hasSection,
           'appendPopoverSection emits a wrapper with the provided class');
    assert(sectionDom.headText === 'Header Label',
           'header text passes through verbatim');
    assert(sectionDom.bodyText === 'body content',
           'body element is appended');

    // appendResolutionSection — multi-override inheritance chain.
    //
    // Backend hands the chain to the UI in closer-first order; the
    // renderer must mark the first entry as `✓ (chosen)` and any
    // subsequent ones as `↳ (also by)` so the closer-fn-wins decision
    // becomes visible in MI scenarios. Single-entry case stays
    // unadorned.
    const multiResolutionDom = await page.evaluate(() => {
      const host = document.createElement('div');
      // Synthetic prov — two ancestor overrides, no own-fn override,
      // no ref-return. Both override-fn-ids are fake (won't resolve
      // via lookups); the renderer falls back to `—` for the type
      // column, but the marker/tag emission is what we're verifying.
      const prov = {
        winner: 'unified',
        tiers: [
          { key: 'override',   label: 'Binding type-override',
            type: null, source: null },
          { key: 'unified',    label: 'Backward-unified return type',
            type: 'positive-int',
            source: { fnName: 'child-fn', fnId: 'fake-child' } },
          { key: 'ref-return', label: 'Bound fn return type',
            type: null, source: null },
          { key: 'slot',       label: 'Slot declaration',
            type: 'int',
            source: { fnName: 'root-base', fnId: 'fake-root' } },
        ],
        inheritanceChain: [
          { fnId: 'fake-parent-a', fnName: 'parent-a',
            overrideFnId: 'fake-override-a' },
          { fnId: 'fake-parent-b', fnName: 'parent-b',
            overrideFnId: 'fake-override-b' },
        ],
      };
      appendResolutionSection(host, prov);
      const links = host.querySelectorAll('.type-inline-resolution-chain-link');
      const winnerRow = host.querySelector('.type-inline-resolution-chain-winner');
      const alsoRows  = host.querySelectorAll('.type-inline-resolution-chain-also');
      const tags = Array.from(host.querySelectorAll('.type-inline-resolution-chain-tag'))
                        .map(t => t.textContent);
      const marks = Array.from(host.querySelectorAll(
                      '.type-inline-resolution-chain-link .type-inline-resolution-mark'))
                        .map(m => m.textContent);
      return { linkCount: links.length, hasWinner: !!winnerRow,
               alsoCount: alsoRows.length, tags, marks };
    });
    assert(multiResolutionDom.linkCount === 2,
           'two inheritance-chain entries render two chain rows');
    assert(multiResolutionDom.hasWinner,
           'first chain entry gets the .type-inline-resolution-chain-winner class');
    assert(multiResolutionDom.alsoCount === 1,
           'second chain entry gets the .type-inline-resolution-chain-also class');
    assert(multiResolutionDom.tags.length === 2
           && multiResolutionDom.tags[0] === '(chosen)'
           && multiResolutionDom.tags[1] === '(also by)',
           'multi-override emits "(chosen)" then "(also by)" tags');
    assert(multiResolutionDom.marks[0] === '✓' && multiResolutionDom.marks[1] === '↳',
           'multi-override marker is ✓ for winner, ↳ for shadowed');

    // Single-override case — no winner/also classes, no tag chips.
    const singleResolutionDom = await page.evaluate(() => {
      const host = document.createElement('div');
      appendResolutionSection(host, {
        winner: 'slot',
        tiers: [
          { key: 'override', label: 'Binding type-override', type: null, source: null },
          { key: 'unified',  label: 'Backward-unified return type', type: null, source: null },
          { key: 'ref-return', label: 'Bound fn return type', type: null, source: null },
          { key: 'slot', label: 'Slot declaration', type: 'int',
            source: { fnName: 'root-base', fnId: 'fake-root' } },
        ],
        inheritanceChain: [
          { fnId: 'fake-parent-a', fnName: 'parent-a',
            overrideFnId: 'fake-override-a' },
        ],
      });
      return {
        winnerCount: host.querySelectorAll('.type-inline-resolution-chain-winner').length,
        alsoCount:   host.querySelectorAll('.type-inline-resolution-chain-also').length,
        tagCount:    host.querySelectorAll('.type-inline-resolution-chain-tag').length,
      };
    });
    assert(singleResolutionDom.winnerCount === 0
           && singleResolutionDom.alsoCount === 0
           && singleResolutionDom.tagCount === 0,
           'single-override chain stays unadorned (no chosen/also markers)');

    // appendResolutionSection — onNavigate makes BOTH the source-fn
    // label AND the type-row name in the type column clickable.
    // Verifies the navigation wiring (B3.2) end-to-end via a spy.
    const navResult = await page.evaluate(() => {
      // Pre-load a synthetic type-row entry so the type cell can
      // resolve its name to a fn-id and become a link.
      if (typeof lookups === 'undefined' || !lookups) {
        return { ok: false, reason: 'no lookups global' };
      }
      const priorByName = lookups.fnByName;
      const fakeId = '00000000-0000-0000-0000-0000000000aa';
      const fakeFnByName = new Map(priorByName || []);
      fakeFnByName.set('positive-int', { id: fakeId, name: 'positive-int' });
      lookups.fnByName = fakeFnByName;
      try {
        const host = document.createElement('div');
        const calls = [];
        const prov = {
          winner: 'override',
          tiers: [
            { key: 'override', label: 'Binding type-override',
              type: 'positive-int',
              source: { fnName: 'caller-fn', fnId: 'fake-caller' } },
            { key: 'unified', label: 'Backward-unified return type',
              type: null, source: null },
            { key: 'ref-return', label: 'Bound fn return type',
              type: null, source: null },
            { key: 'slot', label: 'Slot declaration',
              type: 'int', source: { fnName: 'root-base', fnId: 'fake-root' } },
          ],
          inheritanceChain: [],
        };
        appendResolutionSection(host, prov, {
          onNavigate: (id) => calls.push(id),
        });
        // Source-fn name links
        const fnLinks = host.querySelectorAll(
          'a.type-inline-resolution-link.type-inline-resolution-label');
        // Type-cell link for `:positive-int` (matched via fnByName mock)
        const typeLinks = host.querySelectorAll(
          'span.type-inline-resolution-type.type-inline-resolution-link');
        // Click each kind once
        if (fnLinks[0]) fnLinks[0].click();
        if (typeLinks[0]) typeLinks[0].click();
        return {
          ok: true,
          fnLinkCount: fnLinks.length,
          typeLinkCount: typeLinks.length,
          calls,
        };
      } finally {
        // Restore the original lookups so later tests aren't poisoned.
        lookups.fnByName = priorByName;
      }
    });
    assert(navResult.ok, 'navigation test bootstrap succeeded');
    assert(navResult.fnLinkCount >= 1,
           'at least one source-fn renders as a clickable link');
    assert(navResult.typeLinkCount === 1,
           'type-cell for a known type-row renders as a clickable link');
    assert(navResult.calls.includes('fake-caller'),
           'clicking source-fn link invokes onNavigate(fnId)');
    assert(navResult.calls.includes('00000000-0000-0000-0000-0000000000aa'),
           'clicking type-row cell invokes onNavigate(typeFnId)');

    console.log('✓ all resolution-renderer assertions verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
