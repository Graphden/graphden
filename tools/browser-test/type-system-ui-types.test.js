// Pure type-helper smoke tests — refinement walks, kind labels,
// closed-enum detection, formatTypeHumanReadable, shortTypeLabel.
//
// These exercise the read-only helpers in
// `editor-literal-types.js` + `editor-overlay-type-expand.js` that
// the rest of the editor builds on. No DOM construction is asserted
// here — see `type-system-ui-resolution.test.js` for that.
//
// All run via `page.evaluate` against a live editor at GRAPHDEN_URL.
// No DB writes — the helpers are pure functions.
//
// Run:  node type-system-ui-types.test.js

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('type-system-ui-types — pure helpers via page.evaluate');
  try {
    // refinementChain — walks `:refine` links + primitive supers.
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

    // typeKindLabel — kind tag for structural types.
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

    // closedEnumOf — detect closed-enum refinement.
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

    // formatTypeHumanReadable — natural-language rendering of refinements
    // (used as the constraint-chip's hover-title via createTypeChip).
    const constraintTitleResult = await page.evaluate(() => {
      const friendly = formatTypeHumanReadable(['refine', 'int', ['>', 0]]);
      const generic  = formatTypeHumanReadable(['refine', 'int',
                                                  ['and', ['>=', 1024], ['<=', 65535]]]);
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

    // shortTypeLabel — canonical compact label used by every
    // resolution row + the inline-expand mini-chips. Pin behaviour
    // explicitly so the move into editor-literal-types.js doesn't
    // silently drift.
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

    console.log('✓ all type-helpers verified');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
