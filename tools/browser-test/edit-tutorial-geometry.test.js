// Temporal geometry regression against the real editor, using a manual lesson
// so completion polling cannot conceal a late spotlight update. No CRUD.
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    await page.waitForFunction(() => typeof _tourStartPositioning === 'function'
      && typeof openShellMenu === 'function', null, {timeout: 60000});
    const version = await page.evaluate(async () => {
      const version = await fetch('/version').then((response) => response.json());
      return {running: version.frontend.slice(0, 12), page: window.BUILD_HASH};
    });
    assert(version.page === version.running, 'browser runs the current frontend build');
    await page.evaluate(async () => {
      await _tourFetchLessons();
      window.geometryOriginalLessons = _tourLessons;
      window.geometryOriginalPosition = _tourPosition;
      window.geometryOriginalTick = _tourTick;
      window.geometryCalls = 0;
      window.geometryTicks = 0;
      _tourTick = () => { window.geometryTicks++; window.geometryOriginalTick(); };
      _tourPosition = () => { window.geometryCalls++; window.geometryOriginalPosition(); };
      const style = document.createElement('style');
      style.id = 'geometry-fixture-style';
      style.textContent = '#geometry-fixture { position: fixed; left: 450px; top: 220px; width: 100px; height: 40px; transition: transform 240ms linear 80ms; } @media (prefers-reduced-motion: reduce) { #geometry-fixture { transition: none; } }';
      document.head.appendChild(style);
      const target = document.createElement('button');
      target.id = 'geometry-fixture';
      target.textContent = 'Geometry fixture';
      document.body.appendChild(target);
      _tourLessons = {lessons: [{id: 'geometry', title: 'Geometry', steps: [{
        title: 'Follow the control', body: 'Manual geometry regression',
        target: '#geometry-fixture', targets: ['#geometry-fixture', '.auth-menu-item'],
        check: {kind: 'manual'},
      }]}]};
      _tourState = {lessonId: 'geometry', step: 0, created: []};
      _tourRenderStep();
      _tourArm();
      window.geometryBaselineListeners = _viewportListeners.length - 1;
    });
    const frames = async (count = 4) => page.evaluate(async (n) => {
      for (let i = 0; i < n; i++) await new Promise(requestAnimationFrame);
    }, count);
    const aligned = async (selector) => page.evaluate((sel) => {
      const target = document.querySelector(sel).getBoundingClientRect();
      const ring = document.getElementById('gd-tour-spot').getBoundingClientRect();
      return Math.max(Math.abs(ring.left - target.left + 6), Math.abs(ring.top - target.top + 6));
    }, selector);
    await frames();
    assert(await aligned('#geometry-fixture') <= 1, 'initial spotlight aligns');
    const switched = await page.evaluate(async () => {
      clearInterval(_tourTimer);
      _tourTimer = null;
      const ticks = window.geometryTicks;
      openShellMenu();
      for (let i = 0; i < 4; i++) await new Promise(requestAnimationFrame);
      return window.geometryTicks === ticks;
    });
    assert(await aligned('.auth-menu-item') <= 1 && switched,
      'real account menu takes the spotlight within four frames without a completion poll');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.getElementById('auth-popover').classList.contains('hidden'),
      null, {timeout: 10000});
    await frames();
    assert(await aligned('#geometry-fixture') <= 1, 'Escape returns spotlight when the menu finishes closing');
    const motion = await page.evaluate(async () => {
      const target = document.getElementById('geometry-fixture');
      target.style.transform = 'translateX(120px)';
      const samples = [];
      const start = performance.now();
      while (performance.now() - start < 420) {
        await new Promise(requestAnimationFrame);
        const t = target.getBoundingClientRect();
        const r = document.getElementById('gd-tour-spot').getBoundingClientRect();
        samples.push({x: t.left, error: Math.abs(r.left - t.left + 6)});
      }
      return {maxExcess: Math.max(...samples.slice(3).map((sample, i) =>
        sample.error - Math.abs(sample.x - samples[i + 2].x))),
        intermediate: samples.filter((sample) => sample.x > 451 && sample.x < 569).length};
    });
    assert(motion.intermediate > 0 && motion.maxExcess <= 2 && await aligned('#geometry-fixture') <= 1,
      'delayed CSS transition follows within one observed frame (excess ' + motion.maxExcess.toFixed(1) + 'px)');
    await page.emulateMedia({reducedMotion: 'reduce'});
    await page.evaluate(() => { document.getElementById('geometry-fixture').style.transform = 'translateX(40px)'; });
    await frames();
    assert(await aligned('#geometry-fixture') <= 1, 'reduced motion reaches the same final geometry');
    await page.evaluate(() => {
      const target = document.getElementById('geometry-fixture');
      const scroller = document.createElement('div');
      scroller.id = 'geometry-scroll';
      scroller.style.cssText = 'position:fixed;left:450px;top:220px;height:100px;width:150px;overflow:auto';
      const spacer = document.createElement('div');
      spacer.style.height = '600px';
      target.style.position = 'relative';
      target.style.left = '0px';
      target.style.top = '0px';
      target.style.marginTop = '50px';
      scroller.append(target, spacer);
      document.body.appendChild(scroller);
    });
    await frames();
    await page.evaluate(() => { document.getElementById('geometry-scroll').scrollTop = 20; });
    await frames();
    assert(await aligned('#geometry-fixture') <= 1, 'nested scroll updates the ring within four frames');
    await page.evaluate(() => {
      const target = document.getElementById('geometry-fixture');
      target.style.position = '';
      target.style.left = '';
      target.style.top = '';
      target.style.marginTop = '';
      document.body.appendChild(target);
      document.getElementById('geometry-scroll').remove();
    });
    await frames();
    await page.evaluate(() => { document.getElementById('geometry-fixture').style.left = '-500px'; });
    await frames();
    assert(await page.evaluate(() => !_tourEls.spot.classList.contains('gd-tour-visible')),
      'horizontally offscreen target hides the ring');
    await page.evaluate(() => { document.getElementById('geometry-fixture').style.left = '450px'; });
    await frames();
    await page.evaluate(() => _tourArm());
    await page.waitForFunction(() => {
      const now = performance.now();
      if (window.geometryIdleCalls !== window.geometryCalls) {
        window.geometryIdleCalls = window.geometryCalls;
        window.geometryIdleSince = now;
      }
      return now - window.geometryIdleSince >= 200;
    }, null, {timeout: 10000});
    const idleStart = await page.evaluate(() => ({calls: window.geometryCalls, ticks: window.geometryTicks}));
    await page.waitForFunction((ticks) => window.geometryTicks >= ticks + 2,
      idleStart.ticks, {timeout: 10000, polling: 50});
    const idle = await page.evaluate((before) => ({
      calls: window.geometryCalls - before.calls, ticks: window.geometryTicks - before.ticks,
    }), idleStart);
    assert(idle.calls === 0 && idle.ticks >= 2, 'completion polling remains live without idle geometry scans: ' + JSON.stringify(idle));
    await page.evaluate(() => selectFnByName('const'));
    await page.waitForSelector('.node-overlay', {timeout: 60000});
    await page.waitForFunction(() => !graph.animating, null, {timeout: 30000});
    await page.evaluate(() => {
      const step = _tourStep();
      step.target = '.node-overlay';
      step.targets = ['.node-overlay'];
      _tourRenderStep();
      setViewportPan(viewport.pan.x + 24, viewport.pan.y + 16);
    });
    await frames();
    assert(await aligned('.node-overlay') <= 1, 'actual graph viewport pan updates the spotlight');
    await page.evaluate(() => { window.geometryBaselineListeners = _viewportListeners.length - 1; });
    await page.screenshot({path: '/tmp/graphden-tour-geometry.png'});
    await page.evaluate(() => _tourPause());
    await frames();
    const paused = await page.evaluate(async () => {
      const before = window.geometryCalls;
      document.getElementById('geometry-fixture').style.transform = 'translateX(80px)';
      window.dispatchEvent(new Event('resize'));
      await new Promise((resolve) => setTimeout(resolve, 100));
      return {calls: window.geometryCalls - before, stopped: _tourStopPositioning === null,
        listeners: _viewportListeners.length === window.geometryBaselineListeners};
    });
    assert(paused.calls === 0 && paused.stopped && paused.listeners,
      'pause cancels frames, observers and viewport subscription');
    await page.evaluate(() => { for (let i = 0; i < 10; i++) { _tourResume(); _tourPause(); } });
    assert(await page.evaluate(() => _viewportListeners.length === window.geometryBaselineListeners),
      'ten reopen cycles retain no viewport subscription');
    await page.evaluate(async () => { _tourResume(); await openTutorialMenu(); });
    await frames();
    assert(await page.evaluate(() => _tourStopPositioning === null
      && !_tourEls.spot.classList.contains('gd-tour-visible')),
      'catalogue stops geometry without reviving the spotlight');
    await page.evaluate(() => _tourResume());
    await page.evaluate(() => _tourEnd());
    assert(await page.evaluate(() => _tourStopPositioning === null), 'lesson end stops geometry before async cleanup');
    assert(errors.length === 0, 'no browser errors: ' + errors.join('; '));
  } finally {
    await page.evaluate(() => {
      _tourTeardown();
      if (window.geometryOriginalPosition) _tourPosition = window.geometryOriginalPosition;
      if (window.geometryOriginalTick) _tourTick = window.geometryOriginalTick;
      if (window.geometryOriginalLessons) _tourLessons = window.geometryOriginalLessons;
      document.getElementById('geometry-fixture')?.remove();
      document.getElementById('geometry-fixture-style')?.remove();
    }).catch(() => {});
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
