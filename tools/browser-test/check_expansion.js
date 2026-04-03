const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  // Capture all console messages
  page.on('console', msg => {
    console.log('BROWSER:', msg.text());
  });

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 3000));

  // Get initial expansion levels
  const initialState = await page.evaluate(() => {
    return {
      expansionLevel: Object.fromEntries(expansionLevel),
      nodesCount: cy.nodes().length
    };
  });
  console.log('Initial state:', JSON.stringify(initialState, null, 2));

  // Find create-route and directly call setExpansionLevel
  const result = await page.evaluate(() => {
    const fnId = '35ed3970-9143-4a2d-b322-351080ec31bc';
    const currentLevel = expansionLevel.get(fnId) || 0;
    console.log('Current expansion level for create-route:', currentLevel);

    // Call setExpansionLevel directly
    if (typeof setExpansionLevel === 'function') {
      setExpansionLevel(fnId, currentLevel + 1);
      return { called: true, newLevel: currentLevel + 1 };
    } else {
      return { called: false, error: 'setExpansionLevel not found' };
    }
  });
  console.log('SetExpansionLevel result:', result);

  await new Promise(r => setTimeout(r, 2000));

  // Check state after expansion
  const afterState = await page.evaluate(() => {
    return {
      expansionLevel: Object.fromEntries(expansionLevel),
      nodesCount: cy.nodes().length
    };
  });
  console.log('After expansion state:', JSON.stringify(afterState, null, 2));

  await browser.close();
})();
