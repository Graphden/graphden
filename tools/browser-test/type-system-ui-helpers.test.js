// Smoke tests for the new type-system UI helpers landed in commits
// 55930708 and following:
//
//   refinementChain          (editor-overlay-type-expand.js)
//   buildRefinementChainSection
//   typeKindLabel
//   closedEnumOf             (editor-literal-types.js)
//   ruleNarrators            (editor-provenance-popover.js)
//   appendClosedEnumSection
//   appendEffectConstraintSection
//   appendPopoverSection
//
// All run via `page.evaluate` against a live editor at GRAPHDEN_URL.
// No DB writes — the helpers are pure functions. Exit 0 on PASS, 1
// on FAIL (any assertion in this file).
//
// Run from this directory:  node type-system-ui-helpers.test.js

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('type-system-ui-helpers — pure helpers via page.evaluate');
  try {
    // 1. refinementChain — walks `:refine` links + primitive supers.
    const chainResult = await page.evaluate(() => {
      return {
        // user-port is `[:refine :int [:and [:>= 1024] [:<= 65535]]]`
        // — one refine link, then primitive super `:int ⊂ :numeric`.
        // Expected: 3 steps (user-port, int, numeric).
        userPort: refinementChain('user-port'),
        // Plain primitive — only `:int ⊂ :numeric` (2 steps).
        plainInt: refinementChain('int'),
        // Non-refinement non-numeric primitive — 1 step.
        bool: refinementChain('bool'),
      };
    });
    assert(chainResult.userPort.steps.length === 3,
           'refinementChain(":user-port") → 3 steps (user-port, int, numeric)');
    assert(chainResult.userPort.steps[0].name === 'user-port',
           'first step is :user-port');
    assert(chainResult.userPort.steps[2].name === 'numeric',
           'last step is :numeric (via primitive-super walk)');
    assert(chainResult.plainInt.steps.length === 2,
           ':int chain has 2 steps (int, numeric)');
    assert(chainResult.bool.steps.length === 1,
           ':bool chain is single-step (no super, no refine)');

    // 2. typeKindLabel — kind tag for structural types.
    const kindLabels = await page.evaluate(() => ({
      map:   typeKindLabel(['map', 'keyword', 'text']),
      list:  typeKindLabel(['list', 'int']),
      tuple: typeKindLabel(['tuple', 'int', 'text']),
      fn:    typeKindLabel(['fn', { a: 'int' }, 'bool']),
      refine: typeKindLabel(['refine', 'int', ['>', 0]]),
      union:  typeKindLabel(['union', 'int', 'null']),
      record: typeKindLabel({ name: 'text', age: 'int' }),
      prim:   typeKindLabel('int'),
      nil:    typeKindLabel(null),
    }));
    assert(kindLabels.map === 'Map', 'typeKindLabel for [:map …] → "Map"');
    assert(kindLabels.list === 'List', 'typeKindLabel for [:list …] → "List"');
    assert(kindLabels.tuple === 'Tuple', 'typeKindLabel for [:tuple …] → "Tuple"');
    assert(kindLabels.fn === 'Function', 'typeKindLabel for [:fn …] → "Function"');
    assert(kindLabels.refine === 'Refinement',
           'typeKindLabel for [:refine …] → "Refinement"');
    assert(kindLabels.union === 'Union', 'typeKindLabel for [:union …] → "Union"');
    assert(kindLabels.record === 'Record', 'typeKindLabel for record → "Record"');
    assert(kindLabels.prim === null, 'typeKindLabel for primitive → null');
    assert(kindLabels.nil === null, 'typeKindLabel for null → null');

    // 3. closedEnumOf — detect closed-enum refinement.
    const enumResults = await page.evaluate(() => ({
      keywordEnum: closedEnumOf(['refine', 'keyword', ['in', ['ok', 'err']]]),
      intRange:    closedEnumOf(['refine', 'int', ['>', 0]]),
      plainRefine: closedEnumOf(['refine', 'text', ['not=', '']]),
      nonRefine:   closedEnumOf(['list', 'int']),
    }));
    assert(enumResults.keywordEnum && enumResults.keywordEnum.members.length === 2,
           'closedEnumOf finds [:refine :keyword [:in [...]]] members');
    assert(enumResults.keywordEnum.members[0].label.startsWith(':'),
           'closed-enum members of :keyword base render with leading `:`');
    assert(enumResults.intRange === null,
           'closedEnumOf returns null for non-`:in` refinement');
    assert(enumResults.plainRefine === null,
           'closedEnumOf returns null for [:not= …] refinement');
    assert(enumResults.nonRefine === null,
           'closedEnumOf returns null for non-refinement types');

    // 4. ruleNarrators — per-rule prose templates.
    const narratorResults = await page.evaluate(() => {
      const r = ruleNarrators;
      return {
        // :assoc with literal key → narrative names the field + value type
        assoc: r.assoc({
          map:   { type: {}, value: {} },
          key:   { type: 'keyword', value: 'name' },
          value: { type: 'int', value: 42 },
        }),
        // :assoc with computed key → degrades to :jsonb message
        assocComputed: r.assoc({
          map: { type: {}, value: {} },
          key: { type: 'keyword', ref: 'compute-key' },
        }),
        // :get with literal key
        get: r.get({
          coll: { type: { name: 'text' } },
          key:  { type: 'keyword', value: 'name' },
        }, 'text'),
        // :coalesce
        coalesce: r.coalesce({
          value:   { type: ['union', 'null', 'int'] },
          default: { type: 'int' },
        }, 'int'),
        // :if
        if: r.if({}, ['union', 'int', 'text']),
      };
    });
    assert(narratorResults.assoc && narratorResults.assoc.includes('name'),
           ':assoc narrator names the literal key');
    assert(narratorResults.assoc.includes('field'),
           ':assoc narrator mentions field-add semantics');
    assert(narratorResults.assocComputed && narratorResults.assocComputed.includes('jsonb'),
           ':assoc narrator with computed key warns about :jsonb degradation');
    assert(narratorResults.get && narratorResults.get.includes('name'),
           ':get narrator names the looked-up field');
    assert(narratorResults.coalesce && narratorResults.coalesce.includes('null'),
           ':coalesce narrator mentions null-stripping');
    assert(narratorResults.if && narratorResults.if.includes('union'),
           ':if narrator mentions the branch union');

    // Coverage check — every return-rule registered in core/* impls
    // SHOULD have a narrator. The set below mirrors the FULL roster
    // from collections/system/logic/arithmetic. A future rule added
    // without a narrator will trip this assertion.
    const expectedNarrators = await page.evaluate(() => {
      const known = [
        'assoc','dissoc','get','get-in','assoc-in','update-in',
        'conj','first','rest','cons','list','merge','into',
        'range','repeat','keys','vals',
        'take','drop','reverse','sort','distinct','concat',
        'case','cond','coalesce','if','invoke','const','identity',
        'add','sub','mul','mod','neg','abs',
      ];
      return known.filter(name => typeof ruleNarrators[name] !== 'function');
    });
    assert(expectedNarrators.length === 0,
           `every known return-rule has a narrator (missing: ${expectedNarrators.join(', ') || 'none'})`);

    // Spot-check every new narrator introduced in Wave 3 — list-
    // preserving HOFs + arithmetic. Each fixture exercises the rule's
    // signature shape: ret carries the post-rule type, bindings are
    // mostly empty (the narrators read primarily from ret).
    const newNarratorResults = await page.evaluate(() => ({
      take:     ruleNarrators.take({}, ['list', 'int']),
      drop:     ruleNarrators.drop({}, ['list', 'int']),
      reverse:  ruleNarrators.reverse({}, ['list', 'text']),
      sort:     ruleNarrators.sort({}, ['list', 'int']),
      distinct: ruleNarrators.distinct({}, ['list', 'int']),
      concat:   ruleNarrators.concat({}, ['list', 'int']),
      add:      ruleNarrators.add({}, 'int'),
      sub:      ruleNarrators.sub({}, 'int'),
      mul:      ruleNarrators.mul({}, 'int'),
      mod:      ruleNarrators.mod({}, 'int'),
      neg:      ruleNarrators.neg({}, 'numeric'),
      abs:      ruleNarrators.abs({}, 'numeric'),
    }));
    // List-preserving HOFs — each mentions element-type preservation.
    assert(newNarratorResults.take.includes(':count'),
           ':take narrator mentions the :count parameter');
    assert(newNarratorResults.drop.includes(':count'),
           ':drop narrator mentions the :count parameter');
    assert(newNarratorResults.reverse.includes('element type'),
           ':reverse narrator notes element-type preservation');
    assert(newNarratorResults.sort.includes('element type'),
           ':sort narrator notes element-type preservation');
    assert(newNarratorResults.distinct.includes('element type'),
           ':distinct narrator notes element-type preservation');
    assert(newNarratorResults.concat.includes("LUB"),
           ':concat narrator mentions LUB across inputs');
    // Arithmetic — each names the int/numeric widening + refinement
    // non-propagation caveat.
    for (const op of ['add', 'sub', 'mul']) {
      assert(newNarratorResults[op].includes(':numeric'),
             `:${op} narrator mentions :numeric widening`);
      assert(newNarratorResults[op].includes('Refinement constraints'),
             `:${op} narrator mentions refinement non-propagation`);
    }
    assert(newNarratorResults.mod.includes(':numeric'),
           ':mod narrator mentions :numeric widening');
    assert(newNarratorResults.neg.includes('arithmetic'),
           ':neg narrator notes refinement-constraint non-propagation');
    assert(newNarratorResults.abs.includes('arithmetic'),
           ':abs narrator notes refinement-constraint non-propagation');

    // 5. appendClosedEnumSection — DOM rendering of allowed values.
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

    // 6. appendEffectConstraintSection — DOM rendering of slot-effect chip row.
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

    // 7. appendPopoverSection — shared header + body helper.
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

    // 8. appendResolutionSection — multi-override inheritance chain.
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

    // 9. appendResolutionSection — onNavigate makes BOTH the source-fn
    //    label AND the type-row name in the type column clickable.
    //    Verifies the navigation wiring (B3.2) end-to-end via a spy.
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

    // 10. Constraint span tooltip — refinement chip carries
    //     formatTypeHumanReadable as the title so terse
    //     `(>= 1024) (<= 65535)` translates to natural language on hover.
    const constraintTitleResult = await page.evaluate(() => {
      // Direct check via formatTypeHumanReadable (the helper plumbed
      // into createTypeChip's constraint span). End-to-end "tooltip
      // appears on the actual chip" would require a real arg-overlay
      // with binding state — out of scope for a pure helper smoke.
      // Friendly-name short-circuit: `:positive-int` → "positive integer".
      const friendly = formatTypeHumanReadable(['refine', 'int', ['>', 0]]);
      // Generic fallback for arbitrary numeric range.
      const generic  = formatTypeHumanReadable(['refine', 'int',
                                                  ['and', ['>=', 1024], ['<=', 65535]]]);
      // Closed enum membership
      const enumPhrase = formatTypeHumanReadable(['refine', 'keyword',
                                                  ['in', ['get', 'post']]]);
      return { friendly, generic, enumPhrase };
    });
    assert(constraintTitleResult.friendly === 'positive integer',
           'refinementFriendlyName short-circuits :int (> 0) → "positive integer"');
    assert(constraintTitleResult.generic.includes('1024')
           && constraintTitleResult.generic.includes('65535')
           && constraintTitleResult.generic.includes('and'),
           'compound refinement renders "...and..." with literals visible');
    assert(constraintTitleResult.enumPhrase.includes('one of'),
           'closed-enum refinement is verbalised as "one of …"');

    // 11. shortTypeLabel — the canonical compact label used by every
    //     resolution row. Pin its behaviour explicitly so the move
    //     into editor-literal-types.js (consolidation B2.1) doesn't
    //     silently drift.
    const shortLabelResult = await page.evaluate(() => ({
      none:    shortTypeLabel(null),
      prim:    shortTypeLabel('int'),
      refine:  shortTypeLabel(['refine', 'int', ['>', 0]]),
      list:    shortTypeLabel(['list', 'text']),
      map:     shortTypeLabel(['map', 'keyword', 'int']),
      tuple:   shortTypeLabel(['tuple', 'int', 'text']),
      union:   shortTypeLabel(['union', 'int', 'null']),
      fn:      shortTypeLabel(['fn', { x: 'int' }, 'bool', []]),
      record:  shortTypeLabel({ name: 'text' }),
    }));
    assert(shortLabelResult.none   === 'any',       'null type renders as "any"');
    assert(shortLabelResult.prim   === 'int',       'primitive passes through');
    assert(shortLabelResult.refine === 'int',       'refinement elides constraint, shows base');
    assert(shortLabelResult.list   === '[text]',    'list rendered with brackets');
    assert(shortLabelResult.map    === '{keyword→int}', 'map rendered with arrow');
    assert(shortLabelResult.tuple  === '(int,text)', 'tuple rendered comma-separated');
    assert(shortLabelResult.union  === 'union',     'union collapsed to single token');
    assert(shortLabelResult.fn     === 'fn',        'fn-type collapsed to single token');
    assert(shortLabelResult.record === 'record',    'record collapsed to single token');

    console.log('✓ all type-system UI helpers verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
