const puppeteer = require('puppeteer');

// Analyze route's item2 arg
(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  const structure = await page.evaluate(() => {
    // Find route fn
    let routeId = null;
    lookups.fnMap.forEach((fn, id) => {
      if (fn.name === 'route') {
        routeId = id;
      }
    });

    // Find get-route fn
    let getRouteId = null;
    lookups.fnMap.forEach((fn, id) => {
      if (fn.name === 'get-route') {
        getRouteId = id;
      }
    });

    const routeArgs = lookups.argsByFn.get(routeId) || [];
    const getRouteArgs = lookups.argsByFn.get(getRouteId) || [];

    // Find item2 ref in route
    const item2Arg = routeArgs.find(a => a.name === 'item2' || (lookups.argMap.get(a['source-id']) || {}).name === 'item2');
    const methodMapId = item2Arg ? item2Arg['ref-id'] : null;

    // Find the key arg in get-route that binds to method-map's key
    const keyArg = getRouteArgs.find(a => {
      // This arg should have source-id pointing into method-map's inheritance chain
      if (!a['source-id']) return false;
      // Check if source is in method-map chain
      const sourceArg = lookups.argMap.get(a['source-id']);
      return sourceArg && sourceArg['fn-id'] === methodMapId;
    });

    return {
      routeId: routeId ? routeId.substring(0, 8) : null,
      getRouteId: getRouteId ? getRouteId.substring(0, 8) : null,
      item2Arg: item2Arg ? {
        id: item2Arg.id.substring(0, 8),
        refId: item2Arg['ref-id'] ? item2Arg['ref-id'].substring(0, 8) : null,
        refName: item2Arg['ref-id'] ? (lookups.fnMap.get(item2Arg['ref-id']) || {}).name : null
      } : null,
      keyArg: keyArg ? {
        id: keyArg.id.substring(0, 8),
        sourceId: keyArg['source-id'] ? keyArg['source-id'].substring(0, 8) : null,
        value: keyArg.value
      } : null,
      methodMapId: methodMapId ? methodMapId.substring(0, 8) : null,
      allGetRouteArgs: getRouteArgs.map(a => ({
        id: a.id.substring(0, 8),
        name: a.name,
        sourceId: a['source-id'] ? a['source-id'].substring(0, 8) : null,
        hasValue: a.value !== undefined && a.value !== null,
        value: a.value,
        hasRef: !!a['ref-id']
      }))
    };
  });

  console.log('route ID:', structure.routeId);
  console.log('get-route ID:', structure.getRouteId);
  console.log('method-map ID:', structure.methodMapId);
  console.log('\nitem2 arg in route:', JSON.stringify(structure.item2Arg, null, 2));
  console.log('\nkey arg in get-route:', JSON.stringify(structure.keyArg, null, 2));
  console.log('\nAll get-route args:');
  structure.allGetRouteArgs.forEach(a => {
    let desc = '  - ' + a.id + (a.name ? ' (' + a.name + ')' : '');
    if (a.sourceId) desc += ' [source: ' + a.sourceId + ']';
    if (a.hasValue) desc += ' = ' + JSON.stringify(a.value);
    if (a.hasRef) desc += ' -> ref';
    console.log(desc);
  });

  await browser.close();
})();
