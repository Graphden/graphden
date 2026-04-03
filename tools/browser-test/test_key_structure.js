const puppeteer = require('puppeteer');

// Analyze key argument structure
(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Find api-entities-route and analyze arg structure
  const structure = await page.evaluate(() => {
    // Find api-entities-route
    let routeId = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('api-entities-route')) {
        routeId = n.data('originalFnId');
      }
    });

    const chain = getInheritanceChain(routeId);
    const result = [];

    // For each fn in chain, show its args
    chain.forEach((fnId, level) => {
      const fn = lookups.fnMap.get(fnId);
      const args = lookups.argsByFn.get(fnId) || [];
      
      const argInfo = args.map(arg => {
        const sourceArg = arg['source-id'] ? lookups.argMap.get(arg['source-id']) : null;
        return {
          id: arg.id.substring(0, 8),
          name: arg.name,
          sourceId: arg['source-id'] ? arg['source-id'].substring(0, 8) : null,
          sourceName: sourceArg ? sourceArg.name : null,
          hasValue: arg.value !== undefined && arg.value !== null,
          value: arg.value,
          hasRef: !!arg['ref-id'],
          refName: arg['ref-id'] ? (lookups.fnMap.get(arg['ref-id']) || {}).name : null
        };
      });

      result.push({
        level,
        fnName: fn ? fn.name : '?',
        args: argInfo
      });
    });

    return result;
  });

  console.log('=== ARGUMENT STRUCTURE ===\n');
  structure.forEach(item => {
    console.log('Level ' + item.level + ': ' + item.fnName);
    item.args.forEach(arg => {
      let desc = '  - ' + (arg.name || '(inherited from ' + arg.sourceName + ')');
      if (arg.sourceId) desc += ' [source: ' + arg.sourceId + ']';
      if (arg.hasValue) desc += ' = ' + JSON.stringify(arg.value);
      if (arg.hasRef) desc += ' -> ' + arg.refName;
      console.log(desc);
    });
    console.log('');
  });

  await browser.close();
})();
