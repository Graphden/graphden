const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  const result = await page.evaluate(() => {
    // Get inheritance chain for editor-routes
    const fn = graphData.fns.find(f => f.name === 'editor-routes');
    if (!fn) return { error: 'not found' };

    const chain = getInheritanceChain(fn.id);
    const chainNames = chain.map(id => lookups.fnMap.get(id)?.name);

    // Check args of each fn in chain that reference delete-entity-route
    const argsInfo = chain.map(fnId => {
      const args = lookups.argsByFn.get(fnId) || [];
      const refArgs = args.filter(a => a['ref-id']).map(a => {
        const refFn = lookups.fnMap.get(a['ref-id']);
        return {
          argName: a.name || lookups.argMap.get(a['source-id'])?.name,
          refName: refFn?.name
        };
      }).filter(a => a.refName === 'delete-entity-route');
      return {
        fnName: lookups.fnMap.get(fnId)?.name,
        deleteEntityRefs: refArgs
      };
    });

    return {
      chainNames,
      argsInfo
    };
  });

  console.log('Inheritance chain:', result.chainNames);
  console.log('\nWhich fn references delete-entity-route:');
  result.argsInfo.forEach(info => {
    if (info.deleteEntityRefs.length > 0) {
      console.log('  ' + info.fnName + ':', JSON.stringify(info.deleteEntityRefs));
    }
  });

  await browser.close();
})();
