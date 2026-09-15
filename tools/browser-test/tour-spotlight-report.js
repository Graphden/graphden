// Print a spotlight audit (see `installSpotlightAudit` in
// tutorial-tour-helpers.js) per step — what the tour's ring actually sat on
// at every stage of every lesson a walk drove.
//
//   GRAPHDEN_TOUR_AUDIT=/tmp/audit node edit-tutorial-tour-ux.test.js
//   node tour-spotlight-report.js /tmp/audit            # every record
//   node tour-spotlight-report.js /tmp/audit 15         # one lesson
//   node tour-spotlight-report.js --gate /tmp/a /tmp/b  # the verdict, exit 1 on a red
//
// Read it for: `AMBIGUOUS×N` (the ring landed on "the first match"),
// `NO-ELEMENT` (a target the step names, nothing on screen to ring at that
// moment), `POPOVER-COVERS-TARGET` (the step popover covers what it points
// at), and an `el.card` that is not the card the step's text names.
//
// `--gate` is what run-edit-tests.sh runs over every lesson walk of a suite
// run: a step is NEVER-RINGED when it names a target (its own, or a
// `:targets` stage) and no sample of the whole walk ever found an element
// for it — the reader would sit through that step with no ring at all,
// which is exactly the drift an e2e walk cannot notice (it clicks by its own
// selectors). A NO-ELEMENT record on its own is NOT a red: a step whose
// target lives in a menu the reader is told to open starts ringless and is
// ringed the moment the menu opens. AMBIGUOUS and POPOVER-COVERS-TARGET stay
// report-only — they are worth reading, not worth a red gate.
const fs = require('node:fs');
const path = require('node:path');

function loadRecords(dirs) {
  const out = [];
  for (const dir of dirs) {
    let names;
    try { names = fs.readdirSync(dir); } catch (_) { continue; }
    for (const name of names.filter((n) => /^spotlight-audit.*\.json$/.test(n)).sort()) {
      try { out.push(...JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8'))); } catch (_) { /* a half-written file */ }
    }
  }
  return out;
}

function flagsOf(r) {
  const flags = [];
  if (r.matches > 1) flags.push('AMBIGUOUS×' + r.matches);
  if ((r.target || r.eff) && !r.el) flags.push('NO-ELEMENT');
  if (r.popOverTarget) flags.push('POPOVER-COVERS-TARGET');
  return flags;
}

// Per step (lesson × index × title): did it ever name a target, and was that
// target ever on screen while the ring held?
function analyze(records) {
  const steps = new Map();
  for (const r of records) {
    const key = r.lesson + '|' + r.step + '|' + r.title;
    let s = steps.get(key);
    if (!s) { s = { lesson: r.lesson, step: r.step, title: r.title, wanted: [], ringed: false }; steps.set(key, s); }
    const want = r.eff || r.target;
    if (want && !s.wanted.includes(want)) s.wanted.push(want);
    if (r.el) s.ringed = true;
  }
  const neverRinged = Array.from(steps.values()).filter((s) => s.wanted.length && !s.ringed);
  return { steps: Array.from(steps.values()), neverRinged, flagged: records.filter((r) => flagsOf(r).length).length };
}

function describe(r) {
  const el = r.el;
  return el ? (el.tag + (el.id ? '#' + el.id : '') + (el.cls ? '.' + el.cls.split(' ')[0] : '')
    + (el.card ? ' @' + el.card : '') + (el.within ? ' in ' + el.within : '')
    + (el.text ? ' "' + el.text.slice(0, 32) + '"' : '')) : (r.centered ? '(centered, no target)' : '(none)');
}

function main(argv) {
  const gate = argv.includes('--gate');
  const rest = argv.filter((a) => a !== '--gate');
  const dirs = rest.filter((a) => !/^\d+$/.test(a));
  const onlyLesson = rest.find((a) => /^\d+$/.test(a)) || null;
  if (!dirs.length) {
    console.error('usage: node tour-spotlight-report.js [--gate] <audit-dir>... [lesson]');
    return 2;
  }
  const records = loadRecords(dirs).filter((r) => !onlyLesson || String(r.lesson) === String(onlyLesson));
  if (!gate) {
    let last = null;
    for (const r of records) {
      const head = 'lesson ' + r.lesson + ' · step ' + r.step + ' "' + r.title + '"';
      if (head !== last) { console.log('\n## ' + head); last = head; }
      const flags = flagsOf(r);
      console.log('  ' + (flags.length ? flags.join(' ') + '  ' : '') + (r.eff || '—') + '\n      → ' + describe(r)
        + (r.under && r.el && r.under.text !== r.el.text ? '\n      under ring: ' + r.under.tag + ' "' + (r.under.text || '').slice(0, 32) + '"' : ''));
    }
  }
  const a = analyze(records);
  console.log((gate ? '' : '\n') + records.length + ' ring changes over ' + a.steps.length + ' steps, ' + a.flagged + ' flagged records, '
    + a.neverRinged.length + ' step(s) never ringed');
  for (const s of a.neverRinged) {
    console.log('  NEVER-RINGED  lesson ' + s.lesson + ' · step ' + s.step + ' "' + s.title + '" — wanted: ' + s.wanted.join(' | '));
  }
  if (gate && !records.length) {
    console.log('  (no audit records — no lesson walk wrote to ' + dirs.join(', ') + ')');
    return 0;
  }
  return gate && a.neverRinged.length ? 1 : 0;
}

module.exports = { loadRecords, analyze, flagsOf };
if (require.main === module) process.exit(main(process.argv.slice(2)));
