// Native lesson25: Appearance creation, canonical typed values, local/shared
// dependencies, graph-owned keyboard behavior and exact receipt cleanup.
// GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-ui-components.test.js
'use strict';
const {chromium} = require('playwright');
const {newContext} = require('./edit-test-helpers');
const {walkUIComponentsLesson} = require('./tutorial-ui-components-helpers');
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('dialog', dialog => dialog.accept());
  try {
    await walkUIComponentsLesson(page);
    if (errors.length) throw new Error('Console errors: ' + errors.join('\n'));
  } catch (error) {
    await page.screenshot({path: '/tmp/graphden-personal-ui-components-failure.png'}).catch(() => {});
    console.error('FAIL personal UI component walkthrough; error details withheld to avoid DOM dumps');
    process.exitCode = 1;
  } finally { await browser.close(); }
})();
