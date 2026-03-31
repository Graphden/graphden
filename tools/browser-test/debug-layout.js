const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 6000));

  // Check if layoutGraph uses parentTargetCol
  const hasPreAnalysis = await page.evaluate(() => {
    // Check if the function buildMatrix has the preAnalyzeTree function
    const funcStr = buildMatrix.toString();
    return funcStr.includes('preAnalyzeTree');
  });

  console.log('Layout has preAnalyzeTree:', hasPreAnalysis);

  await browser.close();
})();
