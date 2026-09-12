// The devtour page test — the ONE browser test in this directory that needs no
// graphden stack: docs/devtour/index.html is a standalone file:// page. It runs
// from `bb devtour-page` (in `bb ci`), NOT from run-edit-tests.sh, whose glob is
// `edit-*.test.js`.
const path = require('path');
const { chromium } = require('playwright');
const PAGE = path.resolve(__dirname, '../../docs/devtour/index.html');
const URL = 'file://' + PAGE;
const fails = [];
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails.push(m); };
(async () => {
  const b = await chromium.launch({ args: ['--no-sandbox'] });
  const p = await b.newPage();
  const errs = [];
  p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  p.on('pageerror', e => errs.push('pageerror: ' + e.message));
  await p.goto(URL);
  await p.waitForTimeout(300);

  ok(await p.locator('#map .blk').count() === 14, 'map shows 14 blocks');
  ok((await p.locator('#map .steps li').count()) === 228, '228 steps in map');
  ok((await p.locator('.intro').innerText()).includes('228'), 'intro shows budget');

  // spine navigation + hash deep link
  await p.keyboard.press('ArrowRight');
  await p.waitForTimeout(150);
  ok(p.url().endsWith('#executor/execute'), 'hash is readable: ' + p.url().split('#')[1]);
  ok((await p.locator('#crumb').innerText()).includes('execute'), 'crumb names the step');
  ok((await p.locator('.fhead').innerText()).includes('interface.clj:'), 'file:line in card head');
  await p.keyboard.press('ArrowRight');
  await p.waitForTimeout(150);
  ok(decodeURIComponent(p.url()).endsWith('#executor/execute~2'), 'duplicate name key ~2');
  // browser back
  await p.goBack(); await p.waitForTimeout(150);
  ok(decodeURIComponent(p.url()).endsWith('#executor/execute'), 'browser Back walks the path');
  // Back button in footer
  await p.click('#next'); await p.waitForTimeout(120);
  await p.click('#back'); await p.waitForTimeout(150);
  ok(decodeURIComponent(p.url()).endsWith('#executor/execute'), 'footer Back = history.back');

  // progress
  const seen = await p.evaluate(() => JSON.parse(localStorage.getItem('devtour:v1:seen') || '[]').length);
  ok(seen >= 2, 'progress persisted: ' + seen + ' steps');
  ok((await p.locator('#pnum').innerText()).includes('/228'), 'progress counter rendered');
  ok(await p.locator('#map .steps li.seen').count() >= 2, 'ticks in map');

  // deep link straight into a step
  await p.goto(URL + '#types/subtype%3F'); await p.waitForTimeout(250);
  ok((await p.locator('#crumb').innerText()).includes('subtype?'), 'deep link opens a step');
  const folded = await p.locator('#foldbtn').innerText();
  ok(/show all \d+ lines/.test(folded), 'long form folded: ' + folded);
  const shortLines = await p.locator('pre.code .ln').count();
  await p.click('#foldbtn'); await p.waitForTimeout(150);
  const fullLines = await p.locator('pre.code .ln').count();
  ok(fullLines > shortLines && shortLines === 40, `fold expands ${shortLines} -> ${fullLines}`);

  // see also + referenced from
  await p.goto(URL + '#executor/create-context'); await p.waitForTimeout(200);
  const links = await p.locator('.links').innerText();
  ok(links.includes('referenced from'), 'backlinks shown');
  ok(links.includes('same file'), 'same-file steps shown');
  const sib = p.locator('.links .grp').last().locator('a').first();
  const sibName = await sib.innerText();
  await sib.click(); await p.waitForTimeout(200);
  ok((await p.locator('#crumb').innerText()).includes(sibName),
     'same-file chip navigates: ' + sibName);

  // search
  await p.keyboard.press('/'); await p.waitForTimeout(120);
  await p.fill('#q', 'call-cache'); await p.waitForTimeout(200);
  const n = await p.locator('#res li[data-gi]').count();
  ok(n > 0, 'search finds prose/code hits: ' + n);
  await p.keyboard.press('Enter'); await p.waitForTimeout(200);
  ok(await p.locator('#find').isHidden(), 'search closes on Enter');
  ok((await p.locator('#crumb').innerText()).length > 0, 'search result opened a step');

  // help + theme
  await p.keyboard.press('?'); await p.waitForTimeout(120);
  ok(await p.locator('#help').isVisible(), 'help overlay opens');
  await p.keyboard.press('Escape'); await p.waitForTimeout(120);
  ok(await p.locator('#help').isHidden(), 'Escape closes overlay');
  const t0 = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
  await p.click('#btn-theme'); await p.waitForTimeout(120);
  const t1 = await p.evaluate(() => document.documentElement.dataset.theme);
  const bg1 = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
  ok(t1 === 'dark' && bg1 !== t0, 'theme toggle flips the palette: ' + t0 + ' -> ' + bg1);
  await p.click('#btn-theme'); await p.waitForTimeout(120);
  const bg2 = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
  ok(await p.evaluate(() => document.documentElement.dataset.theme) === 'light'
     && bg2 === 'rgb(251, 252, 253)', 'toggles back to light: ' + bg2);

  // emacs link
  const href = await p.locator('.fhead a.act').first().getAttribute('href');
  ok(/^org-protocol:\/\/devtour\?file=.*&line=\d+$/.test(href), 'emacs link: ' + href);
  const gh = await p.locator('.fhead a.act').nth(1).getAttribute('href');
  ok(/^https:\/\/github.com\/Graphden\/graphden\/blob\/develop\/.*#L\d+-L\d+$/.test(gh), 'github link: ' + gh);

  // reset asks first — playwright dismisses dialogs by default, so progress survives
  await p.click('#btn-clear'); await p.waitForTimeout(150);
  ok(!(await p.locator('#pnum').innerText()).startsWith('0/'), 'reset is confirmed, not instant');
  p.once('dialog', d => d.accept());
  await p.click('#btn-clear'); await p.waitForTimeout(200);
  ok((await p.locator('#pnum').innerText()).startsWith('0/'), 'confirmed reset clears progress');

  ok(errs.length === 0, 'no console errors' + (errs.length ? ': ' + errs.join(' | ') : ''));
  await b.close();
  console.log(fails.length ? `\n${fails.length} FAILED` : '\nALL PASS');
  process.exit(fails.length ? 1 : 0);
})();
