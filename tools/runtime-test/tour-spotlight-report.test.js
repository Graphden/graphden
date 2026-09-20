// tour-spotlight-report.js — the gate verdict over spotlight-audit records:
// a step is NEVER-RINGED only when it names a target and no sample of the
// walk ever found an element for it. A step that starts ringless (its
// target lives in a menu the reader opens) and is ringed later is fine; a
// step with no target at all (an intro card) is never counted.
//
// Run:  node tools/runtime-test/tour-spotlight-report.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { analyze, flagsOf, loadRecords } = require('../browser-test/tour-spotlight-report.js');

let failures = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}
const rec = (o) => ({ lesson: '15', step: 2, title: 'Open the menu', target: '.more', eff: '.more', el: null, matches: 0, popOverTarget: false, centered: false, ...o });

console.log(' a step ringless at first and ringed once its menu opens is not a red');
{
  const a = analyze([rec({ el: null }), rec({ eff: '.menu-item', el: { tag: 'button' }, matches: 1 })]);
  assert(a.neverRinged.length === 0, 'ringed later → not flagged');
  assert(a.steps.length === 1 && a.steps[0].wanted.join(',') === '.more,.menu-item', 'both stages recorded as wanted');
  assert(flagsOf(rec({ el: null })).includes('NO-ELEMENT'), 'the ringless record itself still reads NO-ELEMENT in the listing');
}

console.log(' a step whose target never appears is NEVER-RINGED');
{
  const a = analyze([rec({ el: null }), rec({ el: null, eff: null, target: '.more', centered: true })]);
  assert(a.neverRinged.length === 1 && a.neverRinged[0].step === 2, 'flagged once for the step');
  assert(a.neverRinged[0].wanted.includes('.more'), 'names the selector it wanted');
}

console.log(' an intro card with no target is never counted');
{
  const a = analyze([rec({ step: 1, title: 'Welcome', target: null, eff: null, el: null, centered: true })]);
  assert(a.neverRinged.length === 0, 'no target → nothing to ring → not flagged');
}

console.log(' steps are keyed per lesson and index, and a second walk of the same step counts too');
{
  const a = analyze([
    rec({ lesson: '15', step: 3, title: 'Run it', el: null }),
    rec({ lesson: '16', step: 3, title: 'Run it', el: { tag: 'button' } }),
    rec({ lesson: '15', step: 3, title: 'Run it', el: { tag: 'button' } }),
  ]);
  assert(a.steps.length === 2, 'two steps (two lessons)');
  assert(a.neverRinged.length === 0, 'lesson 18 step 3 was ringed on its second walk');
}

console.log(' flags: ambiguous and popover-covers-target are listed, not gated');
{
  const r = rec({ el: { tag: 'button' }, matches: 3, popOverTarget: true });
  const f = flagsOf(r);
  assert(f.includes('AMBIGUOUS×3') && f.includes('POPOVER-COVERS-TARGET') && !f.includes('NO-ELEMENT'), 'flags read ' + f.join(' '));
  assert(analyze([r]).neverRinged.length === 0, 'neither flag is a never-ringed verdict');
}

console.log(' loadRecords reads every per-page file of every directory, and skips a half-written one');
{
  const d1 = fs.mkdtempSync(path.join(os.tmpdir(), 'spot-a-'));
  const d2 = fs.mkdtempSync(path.join(os.tmpdir(), 'spot-b-'));
  fs.writeFileSync(path.join(d1, 'spotlight-audit-11-1.json'), JSON.stringify([rec({ step: 1 })]));
  fs.writeFileSync(path.join(d1, 'spotlight-audit-11-2.json'), JSON.stringify([rec({ step: 2 })]));
  fs.writeFileSync(path.join(d1, 'notes.txt'), 'ignored');
  fs.writeFileSync(path.join(d2, 'spotlight-audit-12-1.json'), '[{"lesson":');
  fs.writeFileSync(path.join(d2, 'spotlight-audit.json'), JSON.stringify([rec({ step: 3 })]));
  const rs = loadRecords([d1, d2, path.join(d2, 'missing')]);
  assert(rs.length === 3 && rs.map((r) => r.step).join(',') === '1,2,3', 'three records in file order (got ' + rs.map((r) => r.step) + ')');
}

console.log(passes + ' passed, ' + failures + ' failed');
process.exit(failures ? 1 : 0);
