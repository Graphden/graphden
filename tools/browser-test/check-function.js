const puppeteer = require('puppeteer');

// This script checks what the sortChildrenByPriority function looks like in browser

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1800, height: 1200 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  const result = await page.evaluate(() => {
    // Get the function as string
    const fnString = sortChildrenByPriority.toString();

    // Also check if getNodeType exists and what it returns
    const getNodeTypeString = typeof getNodeType === 'function' ? getNodeType.toString() : 'NOT FOUND';

    // Test sorting manually
    const testChildIds = ['arg-test', 'fn-test'];
    const testNodeDataMap = new Map([
      ['arg-test', { type: 'arg' }],
      ['fn-test', { type: 'fn' }]
    ]);

    // Call the function
    try {
      const sorted = sortChildrenByPriority(
        testChildIds,
        testNodeDataMap,
        { sharedNodes: new Set() },
        'test-parent',
        new Map()
      );
      return {
        fnString: fnString.substring(0, 500),
        getNodeTypeString: getNodeTypeString.substring(0, 300),
        testResult: sorted
      };
    } catch (e) {
      return {
        fnString: fnString.substring(0, 500),
        getNodeTypeString: getNodeTypeString.substring(0, 300),
        error: e.message
      };
    }
  });

  console.log('=== sortChildrenByPriority function ===');
  console.log(result.fnString);
  console.log('\n=== getNodeType function ===');
  console.log(result.getNodeTypeString);
  console.log('\n=== Test result ===');
  if (result.error) {
    console.log('Error:', result.error);
  } else {
    console.log('Input: [arg-test, fn-test]');
    console.log('Output:', result.testResult);
  }

  await browser.close();
})();
