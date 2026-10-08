// Finite-host walkthrough helpers. Cleanup records proposed UUIDs before the
// POST response, so a lost response cannot leave an untracked publication.
const {assert} = require('./edit-test-helpers');
const {
  waitTourTitle, clickTourButton, filterAndSelect, extendViaRowActions,
  bindPlaceholderOn, openRowActionsFor,
} = require('./tutorial-tour-helpers');

function trackPublications(page) {
  const ids = new Set();
  const record = request => {
    if (request.method() !== 'POST' || new URL(request.url()).pathname !== '/api/http-host') return;
    const id = request.postDataJSON()?.['create-id'];
    if (id) ids.add(id);
  };
  page.on('request', record);
  return async () => {
    page.off('request', record);
    for (const id of ids) {
      const removed = await page.evaluate(async exactId => {
        const r = await window.authFetch('/api/http-host/' + encodeURIComponent(exactId), {method: 'DELETE'});
        const body = await r.json();
        return r.ok && body.ok === true;
      }, id);
      assert(removed, 'exact publication cleanup succeeded: ' + id);
    }
  };
}

async function openHttpHost(page, name) {
  await openRowActionsFor(page, name, 90000, {root: true});
  const button = '.row-actions-popover [data-action="http-host"]';
  await page.waitForSelector(button, {state: 'visible', timeout: 15000});
  await page.dispatchEvent(button, 'click');
  await page.waitForSelector('.http-host-popover.visible', {timeout: 15000});
}

async function createPublication(page, name, body) {
  await waitTourTitle(page, 'Open text-ok-response');
  await filterAndSelect(page, 'text-ok-response', 'text-ok-response');
  await waitTourTitle(page, 'Make your own handler');
  await extendViaRowActions(page, name, 'text-ok-response');
  await waitTourTitle(page, 'Set the response', 150000);
  await bindPlaceholderOn(page, name, 'body', 'literal', body);
  await waitTourTitle(page, 'Open HTTP publication');
  await openHttpHost(page, name);
  await waitTourTitle(page, 'Publish for 30 minutes');
  const publish = '.http-host-popover.visible [data-http-host-publish]';
  await page.waitForSelector(publish + ':not([disabled])', {timeout: 15000});
  await page.dispatchEvent(publish, 'click');
  const link = '.http-host-popover.visible [data-http-host-url]:not([hidden])';
  await page.waitForSelector(link, {timeout: 30000});
  const url = await page.getAttribute(link, 'href');
  const address = new URL(url);
  assert(['http:', 'https:'].includes(address.protocol) && !address.username && !address.password,
    'publication uses a configured HTTP/TLS origin');
  if (process.env.GRAPHDEN_PREVIEW_TEST_TLS === '1') {
    assert(address.protocol === 'https:' && address.hostname.endsWith('.gdcloud-candidate.localhost'),
      'isolated candidate publication uses its actual HTTPS apps origin');
  }
  return url;
}

async function openPublicResponse(page, url, expected, clickLink = false) {
  let external;
  if (clickLink) {
    [external] = await Promise.all([
      page.waitForEvent('popup'),
      page.dispatchEvent('.http-host-popover.visible [data-http-host-url]', 'click'),
    ]);
    await external.waitForLoadState('domcontentloaded');
  } else {
    external = await page.context().newPage();
    const response = await external.goto(url);
    assert(response.status() === 200, 'real public request succeeded');
  }
  assert((await external.locator('body').innerText()).trim() === expected,
    'public tab received the handler response: ' + expected);
  return external;
}

async function stopPublication(page, name, external) {
  await filterAndSelect(page, name, name);
  await waitTourTitle(page, 'Revoke the public URL');
  await openHttpHost(page, name);
  const stop = '.http-host-popover.visible [data-http-host-stop]:not([disabled])';
  await page.waitForSelector(stop, {timeout: 15000});
  await page.dispatchEvent(stop, 'click');
  await waitTourTitle(page, 'Verify the URL stopped');
  const response = await external.reload();
  assert(response.status() === 404, 'same public URL stops executing after Stop');
}

async function walkLesson35(page, base, finishAndDelete) {
  await page.goto(base + '/?tutorial=35');
  await waitTourTitle(page, 'One request, one execution', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 35 introduction');
  const url = await createPublication(page, 'tutorial-http-answer', 'hello HTTP');
  await waitTourTitle(page, 'Make a real HTTP request');
  const external = await openPublicResponse(page, url, 'hello HTTP', true);
  try {
    assert(await clickTourButton(page, 'Next'), 'lesson 35 response read');
    await stopPublication(page, 'tutorial-http-answer', external);
    await finishAndDelete(page);
  } finally {
    await external.close();
  }
}

module.exports = {trackPublications, createPublication, openPublicResponse, stopPublication, walkLesson35};
