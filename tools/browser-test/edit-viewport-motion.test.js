// Real-editor motion: reduced motion, superseding navigation, and input priority.
// Read-only. Restores the viewport; no fixed FPS or wall-clock completion sleep.
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  let original;
  try {
    await page.waitForFunction(() => typeof animateViewportTo === 'function'
      && typeof _cancelViewportAnimation === 'function', null, {timeout: 60000});
    original = await page.evaluate(() => ({zoom: viewport.zoom, ...viewport.pan}));
    await page.emulateMedia({reducedMotion: 'reduce'});
    const reduced = await page.evaluate(() => {
      const centre = viewportCentreScreen();
      animateViewportTo({x: 100, y: 200}, 1000);
      return _viewportAnimation === null
        && viewport.pan.x === centre.x - 100 * viewport.zoom
        && viewport.pan.y === centre.y - 200 * viewport.zoom;
    });
    assert(reduced, 'reduced-motion navigation reaches its target without scheduling frames');
    await page.emulateMedia({reducedMotion: 'no-preference'});
    const superseded = await page.evaluate(async () => {
      animateViewportTo({x: 500, y: 500}, 1000);
      await new Promise(requestAnimationFrame);
      animateViewportTo({x: 100, y: 100}, 120);
      const started = performance.now();
      while (_viewportAnimation && performance.now() - started < 5000) {
        await new Promise(requestAnimationFrame);
      }
      const centre = viewportCentreScreen();
      return _viewportAnimation === null
        && Math.abs(viewport.pan.x - (centre.x - 100 * viewport.zoom)) < 0.01
        && Math.abs(viewport.pan.y - (centre.y - 100 * viewport.zoom)) < 0.01;
    });
    assert(superseded, 'the second navigation owns the final viewport');
    await page.evaluate(() => animateViewportTo({x: 900, y: 900}, 1000));
    const surface = await page.locator('#graph-surface').boundingBox();
    await page.mouse.move(surface.x + surface.width / 2, surface.y + surface.height / 2);
    await page.mouse.wheel(0, 100);
    await page.waitForFunction(() => _viewportAnimation === null);
    const interrupted = await page.evaluate(async () => {
      const stopped = {x: viewport.pan.x, y: viewport.pan.y, zoom: viewport.zoom};
      for (let i = 0; i < 8; i++) await new Promise(requestAnimationFrame);
      return _viewportAnimation === null && viewport.pan.x === stopped.x
        && viewport.pan.y === stopped.y && viewport.zoom === stopped.zoom;
    });
    assert(interrupted, 'wheel input cancels navigation and the old frames do not undo it');
    assert(errors.length === 0, 'no browser errors: ' + errors.join('; '));
  } finally {
    if (original) await page.evaluate(({zoom, x, y}) => setViewportTransform(zoom, x, y), original).catch(() => {});
    await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
