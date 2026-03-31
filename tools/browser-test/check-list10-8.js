const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  const result = await page.evaluate(() => {
    // Find list-10-8
    const fn = graphData.fns.find(f => f.name === 'list-10-8');
    if (!fn) return { error: 'list-10-8 not found' };

    const chain = getInheritanceChain(fn.id);
    const chainNames = chain.map(id => lookups.fnMap.get(id)?.name);

    // Check direct args of list-10-8
    const args = lookups.argsByFn.get(fn.id) || [];
    const argInfo = args.map(a => ({
      name: a.name,
      sourceId: a['source-id'],
      sourceName: a['source-id'] ? lookups.argMap.get(a['source-id'])?.name : null,
      refId: a['ref-id'],
      refName: a['ref-id'] ? lookups.fnMap.get(a['ref-id'])?.name : null,
      value: a.value
    }));

    return {
      fnId: fn.id,
      chainNames,
      args: argInfo
    };
  });

  console.log('list-10-8 inheritance chain:', result.chainNames);
  console.log('\nlist-10-8 args:');
  result.args.forEach(a => {
    console.log('  ' + JSON.stringify(a));
  });

  await browser.close();
})();
