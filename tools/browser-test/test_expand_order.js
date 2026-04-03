const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox']
  });

  const page = await browser.newPage();

  await page.goto('http://localhost:9002/#editor-routes', { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 2000));

  // Find both routes
  const routeIds = await page.evaluate(() => {
    const result = {};
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('entity-form-create-route')) {
        result.create = n.data('originalFnId');
      }
      if (lbl.includes('entity-form-edit-route')) {
        result.edit = n.data('originalFnId');
      }
    });
    return result;
  });

  console.log('entity-form-create-route:', routeIds.create);
  console.log('entity-form-edit-route:', routeIds.edit);

  // Before expand - show children order
  console.log('\n=== BEFORE EXPAND ===');
  let state = await page.evaluate((ids) => {
    const getChildren = (fnId) => {
      const nodeId = 'fn-' + fnId;
      const children = [];
      cy.edges().forEach(e => {
        if (e.source().id() === nodeId) {
          children.push({
            argName: e.data('argName'),
            label: (e.target().data('label') || '').substring(0, 30).replace(/\n/g, '|')
          });
        }
      });
      return children;
    };
    return {
      create: getChildren(ids.create),
      edit: getChildren(ids.edit)
    };
  }, routeIds);

  console.log('entity-form-create-route children:');
  state.create.forEach((c, i) => console.log('  ' + i + '. ' + c.argName + ': ' + c.label));
  console.log('entity-form-edit-route children:');
  state.edit.forEach((c, i) => console.log('  ' + i + '. ' + c.argName + ': ' + c.label));

  // Expand entity-form-create-route to level 2
  console.log('\n=== EXPAND entity-form-create-route TO LEVEL 2 ===');
  await page.evaluate((fnId) => {
    setExpansionLevel(fnId, 2);
  }, routeIds.create);
  await new Promise(r => setTimeout(r, 1500));

  state = await page.evaluate((ids) => {
    const getChildren = (fnId) => {
      const nodeId = 'fn-' + fnId;
      const children = [];
      cy.edges().forEach(e => {
        if (e.source().id() === nodeId) {
          children.push({
            argName: e.data('argName'),
            label: (e.target().data('label') || '').substring(0, 30).replace(/\n/g, '|'),
            targetId: e.target().id()
          });
        }
      });
      return children;
    };
    
    // Find entity-form-handler position
    let handlerPos = null;
    cy.nodes().forEach(n => {
      const lbl = n.data('label') || '';
      if (lbl.includes('entity-form-handler')) {
        const pos = n.position();
        handlerPos = { x: Math.round(pos.x), y: Math.round(pos.y) };
      }
    });
    
    return {
      create: getChildren(ids.create),
      edit: getChildren(ids.edit),
      handlerPos
    };
  }, routeIds);

  console.log('entity-form-create-route children:');
  state.create.forEach((c, i) => console.log('  ' + i + '. ' + c.argName + ': ' + c.label));
  console.log('entity-form-edit-route children:');
  state.edit.forEach((c, i) => console.log('  ' + i + '. ' + c.argName + ': ' + c.label));
  console.log('entity-form-handler position:', state.handlerPos);

  // Show positions of edit-route children
  const positions = await page.evaluate((editId) => {
    const nodeId = 'fn-' + editId;
    const children = [];
    cy.edges().forEach(e => {
      if (e.source().id() === nodeId) {
        const target = e.target();
        const pos = target.position();
        children.push({
          argName: e.data('argName'),
          label: (target.data('label') || '').substring(0, 25).replace(/\n/g, '|'),
          y: Math.round(pos.y)
        });
      }
    });
    return children.sort((a, b) => a.y - b.y);
  }, routeIds.edit);

  console.log('\nentity-form-edit-route children sorted by Y:');
  positions.forEach(c => console.log('  y=' + c.y + ': ' + c.argName + ' - ' + c.label));

  await browser.close();
})();
