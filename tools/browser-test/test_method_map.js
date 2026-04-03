const puppeteer = require('puppeteer');

// Analyze method-map structure
(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  const structure = await page.evaluate(() => {
    // Find method-map
    let methodMapId = null;
    lookups.fnMap.forEach((fn, id) => {
      if (fn.name === 'method-map') {
        methodMapId = id;
      }
    });

    if (!methodMapId) return { error: 'method-map not found' };

    const chain = getInheritanceChain(methodMapId);
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

  console.log('=== METHOD-MAP STRUCTURE ===\n');
  if (structure.error) {
    console.log(structure.error);
  } else {
    structure.forEach(item => {
      console.log('Level ' + item.level + ': ' + item.fnName + ' (' + item.fnId + ')');
      item.args.forEach(arg => {
        let desc = '  - ' + (arg.name || '(inherited)') + ' (' + arg.id + ')';
        if (arg.sourceId) desc += ' [source: ' + arg.sourceId + ']';
        if (arg.hasValue) desc += ' = ' + JSON.stringify(arg.value);
        if (arg.hasRef) desc += ' -> ' + arg.refName;
        console.log(desc);
      });
      console.log('');
    });
  }

  await browser.close();
})();
