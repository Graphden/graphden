// Print a spotlight audit (see `installSpotlightAudit` in
// tutorial-tour-helpers.js) per step — what the tour's ring actually sat on
// at every stage of every lesson a walk drove.
//
//   GRAPHDEN_TOUR_AUDIT=/tmp/audit node edit-tutorial-tour-ux.test.js
//   node tour-spotlight-report.js /tmp/audit            # every record
//   node tour-spotlight-report.js /tmp/audit 15         # one lesson
//
// Read it for: `matches > 1` (an ambiguous selector — the ring landed on
// "the first one"), `el: null` while the step has a target (nothing on
// screen to ring), `popOverTarget` (the step popover covers what it points
// at), and an `el.card` that is not the card the step's text names.
const fs = require('node:fs');
const path = require('node:path');

const dir = process.argv[2];
const onlyLesson = process.argv[3] || null;
if (!dir) {
  console.error('usage: node tour-spotlight-report.js <audit-dir> [lesson]');
  process.exit(2);
}
const records = JSON.parse(fs.readFileSync(path.join(dir, 'spotlight-audit.json'), 'utf8'));
let last = null;
let flagged = 0;
for (const r of records) {
  if (onlyLesson && String(r.lesson) !== String(onlyLesson)) continue;
  const head = 'lesson ' + r.lesson + ' · step ' + r.step + ' "' + r.title + '"';
  if (head !== last) { console.log('\n## ' + head); last = head; }
  const flags = [];
  if (r.matches > 1) flags.push('AMBIGUOUS×' + r.matches);
  if (r.target && !r.el) flags.push('NO-ELEMENT');
  if (r.popOverTarget) flags.push('POPOVER-COVERS-TARGET');
  if (flags.length) flagged += 1;
  const el = r.el ? (r.el.tag + (r.el.id ? '#' + r.el.id : '') + (r.el.cls ? '.' + r.el.cls.split(' ')[0] : '')
    + (r.el.card ? ' @' + r.el.card : '') + (r.el.within ? ' in ' + r.el.within : '')
    + (r.el.text ? ' "' + r.el.text.slice(0, 32) + '"' : '')) : (r.centered ? '(centered, no target)' : '(none)');
  console.log('  ' + (flags.length ? flags.join(' ') + '  ' : '') + (r.eff || '—') + '\n      → ' + el
    + (r.under && r.el && r.under.text !== r.el.text ? '\n      under ring: ' + r.under.tag + ' "' + (r.under.text || '').slice(0, 32) + '"' : ''));
}
console.log('\n' + records.length + ' ring changes, ' + flagged + ' flagged');
