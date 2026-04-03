const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  const structure = await page.evaluate(() => {
    // Find metrics-route
    let metricsRouteId = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('metrics-route') && !lbl.includes('|')) {
        metricsRouteId = n.data('originalFnId');
      }
    });

    const chain = getInheritanceChain(metricsRouteId);
    const result = [];

    chain.forEach((fnId, level) => {
      const fn = lookups.fnMap.get(fnId);
      const args = lookups.argsByFn.get(fnId) || [];
      
      result.push({
        level,
        fnName: fn ? fn.name : '?',
        fnId: fnId.substring(0, 8),
        args: args.map(arg => {
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
        })
      });
    });

    return result;
  });

  console.log('=== METRICS-ROUTE CHAIN ===\n');
  structure.forEach(item => {
    console.log('Level ' + item.level + ': ' + item.fnName + ' (' + item.fnId + ')');
    item.args.forEach(arg => {
      let desc = '  - ' + (arg.name || '(inherited)') + ' (' + arg.id + ')';
      if (arg.sourceId) desc += ' [source: ' + arg.sourceId + ']';
      if (arg.hasValue) desc += ' = ' + JSON.stringify(arg.value).substring(0, 20);
      if (arg.hasRef) desc += ' -> ' + arg.refName;
      console.log(desc);
    });
    console.log('');
  });

  // Also show assoc-handler chain
  const assocHandlerStructure = await page.evaluate(() => {
    let assocHandlerId = null;
    lookups.fnMap.forEach((fn, id) => {
      if (fn.name === 'assoc-handler') {
        assocHandlerId = id;
      }
    });

    if (!assocHandlerId) return null;

    const chain = getInheritanceChain(assocHandlerId);
    const result = [];

    chain.forEach((fnId, level) => {
      const fn = lookups.fnMap.get(fnId);
      const args = lookups.argsByFn.get(fnId) || [];
      
      result.push({
        level,
        fnName: fn ? fn.name : '?',
        fnId: fnId.substring(0, 8),
        args: args.map(arg => ({
          id: arg.id.substring(0, 8),
          name: arg.name,
          sourceId: arg['source-id'] ? arg['source-id'].substring(0, 8) : null,
          hasValue: arg.value !== undefined && arg.value !== null,
          value: arg.value,
          hasRef: !!arg['ref-id'],
          refName: arg['ref-id'] ? (lookups.fnMap.get(arg['ref-id']) || {}).name : null
        }))
      });
    });

    return result;
  });

  if (assocHandlerStructure) {
    console.log('=== ASSOC-HANDLER CHAIN ===\n');
    assocHandlerStructure.forEach(item => {
      console.log('Level ' + item.level + ': ' + item.fnName + ' (' + item.fnId + ')');
      item.args.forEach(arg => {
        let desc = '  - ' + (arg.name || '(inherited)') + ' (' + arg.id + ')';
        if (arg.sourceId) desc += ' [source: ' + arg.sourceId + ']';
        if (arg.hasValue) desc += ' = ' + JSON.stringify(arg.value).substring(0, 20);
        if (arg.hasRef) desc += ' -> ' + arg.refName;
        console.log(desc);
      });
      console.log('');
    });
  }

  await browser.close();
})();
