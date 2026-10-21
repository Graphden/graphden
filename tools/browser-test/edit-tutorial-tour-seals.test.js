// Lesson 07 — optional, required and sealed through the real tutorial UI.
// Its own exact-owned branch keeps this long walkthrough below the runner's
// per-file timeout and lets a failure retry only this lesson.

const {chromium} = require('playwright');
const {assert, newContext, api: nodeApi} = require('./edit-test-helpers');
const {cleanupRecordedTutorialBranches, waitTourTitle, clickTourButton,
  filterAndSelect, extendViaRowActions, bindPlaceholderOn, runViaRowActions,
  setSealsViaBadge, settleTourRing, finishAndDelete, tourWhere} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (dialog) => { dialog.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-seals — lesson 07');
  let failed = false;
  const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  const ownedBranches = [];
  let ownedBranch;
  const api = (page, method, path, body, headers) => {
    assert(ownedBranch?.id, 'Node API uses the exact owned fixture branch');
    return nodeApi(page, method, path, body, {...headers, 'X-Graphden-Branch': ownedBranch.id});
  };
  try {
    const name = 'tutorial-seals-' + process.pid + '-' + Date.now().toString(36);
    const created = await nodeApi(page, 'POST', '/api/branches', {name, 'base-branch-id': 'main'});
    assert(created.ok === true && created.branch?.id && created.branch.name === name
      && created.branch['base-branch-id'], 'fresh fixture branch has a canonical successful POST receipt');
    ownedBranch = created.branch;
    ownedBranches.push({...ownedBranch, type: 'branch'});
    const lessonUrl = id => BASE + '/?branch=' + encodeURIComponent(ownedBranch.name) + '&tutorial=' + id;
    // ---------- Lesson 07 — optional, required and sealed ----------
    await page.goto(lessonUrl('07'));
    await waitTourTitle(page, 'Three decisions beside a value', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 07 Next');
    await waitTourTitle(page, 'Find subs');
    await filterAndSelect(page, 'subs', 'subs');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'tutorial-cut', 'subs');
    await waitTourTitle(page, 'Optional, on the label', 150000);
    await page.waitForSelector('.edge-label-overlay[data-arg-name="end"] .seal-badge', {timeout: 60000});
    await settleTourRing(page, 20000);
    const optional = await page.evaluate(() => ({
      dimmed: document.querySelector('.placeholder-binder[data-fn-name="tutorial-cut"][data-arg-name="end"]')
        ?.classList.contains('is-optional'),
      badge: document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge')?.dataset.seal,
    }));
    assert(optional.dimmed === true && optional.badge === 'optional',
      'the optional slot is dimmed and its badge says so (got: ' + JSON.stringify(optional) + ')');
    await page.locator('.edge-label-overlay[data-arg-name="end"] .seal-badge').click();
    await waitTourTitle(page, 'Close the restrictions without changing them', 150000);
    const restrictions = page.locator('.arg-value-edit-popover[aria-label="Seals on end"]');
    assert(!await restrictions.locator('input[data-seal="required"]').isChecked()
      && !await restrictions.locator('input[data-seal="terminal"]').isChecked(),
    'both restrictions start unchecked');
    await restrictions.getByRole('button', {name: 'Cancel', exact: true}).click();
    await waitTourTitle(page, 'Bind the string', 150000);
    assert(await restrictions.count() === 0, 'the restrictions close before literal binding');
    await bindPlaceholderOn(page, 'tutorial-cut', 'string', 'literal', 'graphden');
    await waitTourTitle(page, 'And the start', 150000);
    await bindPlaceholderOn(page, 'tutorial-cut', 'start', 'literal', '5');
    await waitTourTitle(page, 'Run without it', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, 'den', 150000);
    const den = await page.evaluate(() =>
      (document.querySelector('.execute-result-host .execute-result-pane')?.textContent || '').trim());
    assert(/den/.test(den), 'the optional :end defaulted to the string\'s end: ' + den);
    assert(await clickTourButton(page, 'Next'), 'lesson 07 den Next');
    await waitTourTitle(page, 'Seal it', 150000);
    await setSealsViaBadge(page, 'end', {terminal: true}, 'tutorial-cut');
    await waitTourTitle(page, 'Extend the sealed fn', 150000);
    await extendViaRowActions(page, 'tutorial-cut-more', 'tutorial-cut');
    await waitTourTitle(page, 'A lock where the + would be', 150000);
    await page.waitForSelector('.placeholder-sealed[data-arg-name="end"] .seal-ghost', {timeout: 60000});
    await settleTourRing(page, 20000);
    const sealedChild = await page.evaluate(() => ({
      ghost: document.querySelector('.placeholder-sealed[data-arg-name="end"] .seal-ghost')?.title || '',
      finalGone: !document.querySelector('.placeholder-binder[data-arg-name="string"]')
        && !document.querySelector('.placeholder-binder[data-arg-name="start"]'),
    }));
    assert(/Sealed in tutorial-cut/.test(sealedChild.ghost) && sealedChild.finalGone,
      'the child sees a lock on :end and no + for the final values (got: ' + JSON.stringify(sealedChild) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 07 lock Next');
    await waitTourTitle(page, 'Go to the source', 150000);
    await page.click('.node-overlay[data-fn-name="tutorial-cut-more"] .ancestor-line[data-level="1"] button.more-actions-trigger');
    await page.click('.row-actions-popover [data-action="inheritance-intent"]');
    await page.waitForSelector('.inheritance-intent-popover [data-action="inheritance-source"]');
    await page.click('.inheritance-intent-popover [data-action="inheritance-source"]');
    await waitTourTitle(page, 'Require it instead', 150000);
    await setSealsViaBadge(page, 'end', {terminal: false, required: true}, 'tutorial-cut');
    await waitTourTitle(page, 'See it from the child', 150000);
    await filterAndSelect(page, 'tutorial-cut-more', 'tutorial-cut-more');
    await waitTourTitle(page, 'The + is back — and solid', 150000);
    await page.waitForSelector('.placeholder-binder[data-fn-name="tutorial-cut-more"][data-arg-name="end"]',
      {timeout: 60000});
    await settleTourRing(page, 20000);
    const ratchet = await page.evaluate(() => ({
      dimmed: document.querySelector('.placeholder-binder[data-fn-name="tutorial-cut-more"][data-arg-name="end"]')
        ?.classList.contains('is-optional'),
      ghost: !!document.querySelector('.placeholder-sealed[data-arg-name="end"]'),
      badge: document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge')?.dataset.seal,
    }));
    assert(ratchet.dimmed === false && !ratchet.ghost && ratchet.badge === 'required',
      'the + is back and solid — required since the parent (got: ' + JSON.stringify(ratchet) + ')');
    assert(await clickTourButton(page, 'Next'), 'lesson 07 ratchet Next');
    await waitTourTitle(page, 'Seal the source again', 150000);
    await page.click('.node-overlay[data-fn-name="tutorial-cut-more"] .ancestor-line[data-level="1"] button.more-actions-trigger');
    await page.click('.row-actions-popover [data-action="inheritance-intent"]');
    await page.waitForSelector('.inheritance-intent-popover [data-action="inheritance-source"]');
    await page.click('.inheritance-intent-popover [data-action="inheritance-source"]');
    await page.waitForSelector('.node-overlay[data-fn-name="tutorial-cut"]');
    await setSealsViaBadge(page, 'end', {terminal: true}, 'tutorial-cut');
    await waitTourTitle(page, 'Return to the child', 150000);
    await filterAndSelect(page, 'tutorial-cut-more', 'tutorial-cut-more');
    await waitTourTitle(page, 'Create a variation for the child', 150000);
    const sealBeforeVariation = await page.evaluate(fixtureBranch => {
      const fn = _tourFindFn('tutorial-cut');
      const own = (lookups.bindingsByFn.get(fn.id) || []).find(binding =>
        lookups.slotMap.get(binding['slot-id'])?.name === 'end');
      const branchName = new URL(location.href).searchParams.get('branch');
      const branchId = [fixtureBranch.id, fixtureBranch.name].includes(branchName) ? fixtureBranch.id : null;
      return { fnId: fn.id, bindingId: own?.id, branchName, branchId,
        terminal: own?.terminal, required: own?.required,
        slot: lookups.slotMap.get(own?.['slot-id']) };
    }, ownedBranch);
    assert(sealBeforeVariation.terminal === true && sealBeforeVariation.required === true,
      'original source actually resealed while requiredness remains true');
    await page.click('.node-overlay[data-fn-name="tutorial-cut-more"] .ancestor-line[data-level="1"] button.more-actions-trigger');
    await page.click('.row-actions-popover [data-action="inheritance-intent"]');
    await page.waitForSelector('.inheritance-intent-popover [data-variation-name]');
    await page.fill('.inheritance-intent-popover [data-variation-name]', '_tutorial-cut-local');
    await page.click('.inheritance-intent-popover [data-action="inheritance-variation"]');
    await waitTourTitle(page, 'Lift only the copied seal', 150000);
    await page.waitForSelector('.node-overlay[data-fn-name="_tutorial-cut-local"]');
    await setSealsViaBadge(page, 'end', {terminal: false}, '_tutorial-cut-local');
    await waitTourTitle(page, 'Read the changed child', 150000);
    const liftedCopy = await page.evaluate(() => {
      const copy = _tourFindFn('_tutorial-cut-local');
      const own = (lookups.bindingsByFn.get(copy.id) || []).find(binding =>
        lookups.slotMap.get(binding['slot-id'])?.name === 'end');
      return { terminal: own?.terminal, required: own?.required,
        slot: lookups.slotMap.get(own?.['slot-id']) };
    });
    assert(!liftedCopy.terminal && liftedCopy.required === sealBeforeVariation.required
      && JSON.stringify(liftedCopy.slot) === JSON.stringify(sealBeforeVariation.slot),
    'copy own seal is off; binding requiredness and shared slot declaration are unchanged');
    assert(sealBeforeVariation.bindingId && (!sealBeforeVariation.branchName || sealBeforeVariation.branchId),
      'original source has exact binding and branch identity receipts');
    const preservedSource = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id='
      + encodeURIComponent(sealBeforeVariation.fnId), undefined, sealBeforeVariation.branchId
      ? {'X-Graphden-Branch': sealBeforeVariation.branchId} : undefined);
    const preservedSeal = preservedSource.bindings?.find(binding => binding.id === sealBeforeVariation.bindingId
      && binding['fn-id'] === sealBeforeVariation.fnId);
    assert(preservedSeal?.terminal === true && preservedSeal.required === sealBeforeVariation.required,
      'fresh exact original source binding retains its own seal and requiredness');
    await filterAndSelect(page, 'tutorial-cut-more', 'tutorial-cut-more');
    await waitTourTitle(page, 'Inspect ancestor choices', 150000);
    await page.waitForSelector('.placeholder-binder[data-fn-name="tutorial-cut-more"][data-arg-name="end"]');
    await page.click('.node-overlay[data-fn-name="tutorial-cut-more"] .ancestor-line[data-level="1"] button.more-actions-trigger');
    await page.click('.row-actions-popover [data-action="inheritance-intent"]');
    await page.waitForSelector('.inheritance-intent-popover [data-action="inheritance-ancestors"]');
    await page.click('.inheritance-intent-popover [data-action="inheritance-ancestors"]');
    await page.waitForSelector('.fn-picker-popover[aria-label="Choose ancestor"]');
    await page.click('.fn-picker-cancel-row button');
    assert(await clickTourButton(page, 'Next'), 'lesson 07 ancestor inspect Next');
    await waitTourTitle(page, 'The original stays sealed', 150000);
    await filterAndSelect(page, 'tutorial-cut', 'tutorial-cut');
    await waitTourTitle(page, 'Optional, required, sealed', 150000);
    // Selection advances the tour before its asynchronous layout paints.
    await page.waitForFunction(id => selectedFnId === id && !graph.animating
      && document.querySelector('.node-overlay[data-fn-name="tutorial-cut"]')
      && document.querySelector('.edge-label-overlay[data-arg-name="end"] .seal-badge[data-seal~="terminal"]'),
    sealBeforeVariation.fnId, {timeout: 30000, polling: 100});
    assert(await page.$('.edge-label-overlay[data-arg-name="end"] .seal-badge[data-seal~="terminal"]'),
      'original source own seal is preserved');
    await finishAndDelete(page);
    console.log('  lesson 07: walked + cleaned (optional run, seal, lock on the child, ratchet)');

    console.log('PASS lesson 07');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour at failure:', await tourWhere(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-seals-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-seals-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    try { await cleanupRecordedTutorialBranches(page, ownedBranches); }
    catch (_) { failed = true; console.error('FAIL: exact owned seals branch cleanup refused'); }
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
