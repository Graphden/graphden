// Accessibility invariants, swept across the editor's main states.
//
// The other a11y suites pin BEHAVIOUR (focus enters a dialog, arrows walk
// the graph). This one is the broad net: it walks the live DOM and fails on
// the structural mistakes that are easy to introduce anywhere and invisible
// until someone with a screen reader hits them.
//
// Why not axe-core: it is MPL-2.0, and this repo's license gate allows only
// the MIT/BSD/Apache/ISC family — adding it would turn `bb license-check`
// red. The rules below are the subset of axe that actually fires on an app
// like this one, implemented directly. The a11y MCP (which runs axe out of
// process, outside the dependency tree) stays available for ad-hoc scans.
//
// Each rule states what it would catch, because a failure here has to be
// actionable by someone who did not write the rule.
//
// Read-only.
//
// Run:  node edit-a11y-audit.test.js

const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

const PROBE_FN = 'web-server';

// The rules run inside the page. Kept as one function so a state can be
// audited with a single evaluate().
const AUDIT = () => {
  const problems = [];
  const add = (rule, el, detail) => {
    const path = el.tagName.toLowerCase()
      + (el.id ? '#' + el.id : '')
      + (el.className && typeof el.className === 'string'
         ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '');
    problems.push(`${rule}: <${path}> ${detail}`);
  };
  const visible = (el) => el.offsetParent !== null
    || getComputedStyle(el).position === 'fixed';
  const accName = (el) => (
    el.getAttribute('aria-label')
    || (el.getAttribute('aria-labelledby')
        && document.getElementById(el.getAttribute('aria-labelledby'))?.textContent)
    || el.textContent
    || el.title
    || ''
  ).trim();

  // 1. An interactive control with no accessible name is announced as
  //    "button" and nothing else — the user cannot tell what it does.
  for (const el of document.querySelectorAll('button, a[href], [role="button"]')) {
    if (!visible(el)) continue;
    if (el.getAttribute('aria-hidden') === 'true') continue;
    if (!accName(el)) add('control-has-name', el, 'no text, aria-label or title');
  }

  // 2. Same for dialogs: an unnamed dialog opens and says "dialog".
  for (const el of document.querySelectorAll('[role="dialog"]')) {
    if (!visible(el)) continue;
    if (!el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby')) {
      add('dialog-has-name', el, 'no aria-label / aria-labelledby');
    }
  }

  // 3. aria-hidden over something focusable is the worst of both worlds: the
  //    screen reader skips it, Tab still lands on it, and the user is
  //    stranded on a control that does not exist as far as they are told.
  for (const hidden of document.querySelectorAll('[aria-hidden="true"]')) {
    const focusable = hidden.querySelectorAll(
      'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])');
    for (const f of focusable) {
      if (visible(f)) add('aria-hidden-focus', hidden, 'contains a focusable ' + f.tagName);
    }
  }

  // 4. A form field with no label is announced by its type alone.
  for (const el of document.querySelectorAll('input:not([type="hidden"]), select, textarea')) {
    if (!visible(el)) continue;
    const labelled = el.getAttribute('aria-label')
      || el.getAttribute('aria-labelledby')
      || el.getAttribute('title')
      || (el.id && document.querySelector(`label[for="${CSS.escape(el.id)}"]`))
      || el.closest('label');
    if (!labelled) add('field-has-label', el, 'no label, aria-label or title');
  }

  // 5. Duplicate ids break every aria-* reference that points at them —
  //    aria-labelledby and aria-controls resolve to the first match.
  const seen = new Map();
  for (const el of document.querySelectorAll('[id]')) {
    seen.set(el.id, (seen.get(el.id) || 0) + 1);
  }
  for (const [id, n] of seen) {
    if (n > 1) problems.push(`duplicate-id: #${id} appears ${n} times`);
  }

  // 6. Images need alt (empty alt is fine and means decorative).
  for (const el of document.querySelectorAll('img')) {
    if (!visible(el)) continue;
    if (el.getAttribute('alt') === null) add('img-has-alt', el, 'no alt attribute');
  }

  // 7. A landmark to jump to, and a first heading.
  if (!document.querySelector('main, [role="main"]')) {
    problems.push('landmark-main: the page has no <main>');
  }

  return problems;
};

// Contrast of the editor's own text, computed from the live styles. Catches
// a design-token edit that drops a surface under WCAG AA without anyone
// opening the page.
const CONTRAST = () => {
  const parse = (c) => {
    const m = c.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
    return m ? {r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4]} : null;
  };
  const lum = ({r, g, b}) => {
    const f = (v) => {
      const s = v / 255;
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  const ratio = (a, b) => {
    const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
    return (x + 0.05) / (y + 0.05);
  };
  // Walk up for the first opaque background — the usual case is a
  // transparent element on a painted panel.
  const bgOf = (el) => {
    for (let n = el; n; n = n.parentElement) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c.a > 0.9) return c;
    }
    return parse(getComputedStyle(document.body).backgroundColor);
  };

  const bad = [];
  const nodes = [...document.querySelectorAll(
    '#side-menu *, #gd-insp *, #gd-ctxbar *, .node-overlay *')].slice(0, 400);
  for (const el of nodes) {
    if (el.children.length > 0) continue;               // only leaf text
    const text = (el.textContent || '').trim();
    if (text.length < 2) continue;
    if (el.offsetParent === null) continue;
    const cs = getComputedStyle(el);
    const fg = parse(cs.color);
    const bg = bgOf(el);
    if (!fg || !bg || fg.a < 0.9) continue;
    const size = parseFloat(cs.fontSize);
    const bold = (parseInt(cs.fontWeight, 10) || 400) >= 700;
    // WCAG AA: 3:1 for large text (>=24px, or >=18.66px bold), else 4.5:1.
    const large = size >= 24 || (bold && size >= 18.66);
    const need = large ? 3 : 4.5;
    const r = ratio(fg, bg);
    if (r < need) {
      bad.push(`${text.slice(0, 24)} — ${r.toFixed(2)}:1 (needs ${need}:1, ${size}px)`);
    }
  }
  return bad;
};

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium);
  console.log('edit-a11y-audit — structural invariants across the editor');

  try {
    await page.goto('about:blank');
    await page.goto(BASE + '/#' + PROBE_FN);
    await page.waitForFunction(() => graphReady() && !graph.animating,
                               null, {timeout: 20000, polling: 100});

    // ── State 1: the shell with a graph loaded ───────────────────────────
    const shell = await page.evaluate(AUDIT);
    assert(shell.length === 0,
           'shell + loaded graph is clean:\n    ' + shell.join('\n    '));

    // ── State 2: the Explorer expanded ──────────────────────────────────
    await page.evaluate(() => {
      document.querySelectorAll('#entity-list .ns-header[aria-expanded="false"]')
        .forEach((h, i) => { if (i < 3) h.click(); });
    });
    await page.waitForTimeout(800);
    const expanded = await page.evaluate(AUDIT);
    assert(expanded.length === 0,
           'expanded Explorer is clean:\n    ' + expanded.join('\n    '));

    // ── State 3: a dialog open ──────────────────────────────────────────
    const opened = await page.evaluate(() => {
      const anchor = document.querySelector('.node-overlay button, .placeholder-binder');
      if (!anchor) return false;
      anchor.focus();
      if (typeof openFnPicker === 'function') {
        openFnPicker({anchorEl: anchor, onPick: () => {}, onCancel: () => {}});
        return !!document.querySelector('.fn-picker-popover');
      }
      return false;
    });
    if (opened) {
      await page.waitForTimeout(600);
      const withDialog = await page.evaluate(AUDIT);
      assert(withDialog.length === 0,
             'an open dialog is clean:\n    ' + withDialog.join('\n    '));
      await page.keyboard.press('Escape');
    } else {
      console.log('  – dialog state skipped: no anchor to open a picker from');
    }

    // ── State 4: the shortcut surfaces ──────────────────────────────────
    await page.evaluate(() => document.getElementById('graph-container').focus());
    await page.keyboard.press('?');
    await page.waitForTimeout(500);
    const sheet = await page.evaluate(AUDIT);
    assert(sheet.length === 0,
           'the cheatsheet is clean:\n    ' + sheet.join('\n    '));
    await page.keyboard.press('Escape');

    // ── Contrast ────────────────────────────────────────────────────────
    const contrast = await page.evaluate(CONTRAST);
    assert(contrast.length === 0,
           'every text sample meets WCAG AA contrast:\n    ' + contrast.join('\n    '));

    console.log('a11y-audit — PASS');
  } finally {
    await browser.close();
  }
})();
