// Per-rule narrator smoke tests — `ruleNarrators` is the
// prose-template dispatch table in editor-provenance-popover.js that
// feeds the "Inputs" section of the return-type rule popover. The
// coverage assertion below trips when a new return-rule is registered
// without a matching narrator template.
//
// Run:  node type-system-ui-narrators.test.js

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('type-system-ui-narrators — per-rule prose templates');
  try {
    // ruleNarrators — per-rule prose templates spot-check.
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

    console.log('✓ all narrators verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
