const http = require('http');

// Test the exact user scenario via direct API calls:
// 1. metrics-route and api-entities-route both expanded to level 2 (shows method-map)
// 2. Then expand method-map (by its originalFnId) to level 1
// 3. Check if method-map nodes merge

const metricsRouteId = '5079fbc4-76bb-4d77-88b7-ba77add388bf';
const apiEntitiesRouteId = '21164217-585a-4335-8ade-da58f255c6ce';
const methodMapFnId = 'fcf50cce-d05f-4f5e-b495-3166bf8e9c15';  // Known method-map fn-id

function makeRequest(rootId, expansions) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify({
      'root-id': rootId,
      'expansions': expansions
    });

    const req = http.request({
      hostname: 'localhost',
      port: 9002,
      path: '/api/graph/layout',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data)
      }
    }, (res) => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch (e) {
          reject(e);
        }
      });
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

async function main() {
  console.log('=== Test: Two routes with method-map expansion ===\n');

  // Simulate the browser state where BOTH routes are expanded
  // In the browser, this happens when user has both routes visible and expands each

  // First, let's understand the node IDs that would be generated
  // When metrics-route is expanded to level 2:
  //   - method-map node ID: fn-{metricsRouteId}_{methodMapFnId}
  // When api-entities-route is expanded to level 2:
  //   - method-map node ID: fn-{apiEntitiesRouteId}_{methodMapFnId}

  // The problem: when user expands method-map by clicking its + button,
  // the browser calls setExpansionLevel(methodMapFnId, 1)
  // This uses the CANONICAL fn-id, not the structural node ID!

  // Step 1: metrics-route expanded to level 2
  console.log('=== Step 1: metrics-route at level 2 ===');
  let result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 2
  });

  let methodMapNodes = result.nodes.filter(n =>
    n.data.label && n.data.label.startsWith('method-map'));
  console.log('method-map nodes:', methodMapNodes.length);
  methodMapNodes.forEach(n => console.log('  ID:', n.data.id));

  // Step 2: Now simulate what happens when method-map is expanded
  // The browser uses originalFnId (methodMapFnId) for expansion
  console.log('\n=== Step 2: metrics-route + method-map expanded ===');
  result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 2,
    ['fn-' + methodMapFnId]: 1  // This is what browser sends!
  });

  methodMapNodes = result.nodes.filter(n =>
    n.data.label && n.data.label.startsWith('method-map'));
  console.log('method-map nodes:', methodMapNodes.length);
  methodMapNodes.forEach(n => console.log('  ID:', n.data.id));

  // Step 3: Check if expanding the STRUCTURAL node ID works differently
  console.log('\n=== Step 3: Using structural node ID for expansion ===');
  const structuralMethodMapId = 'fn-' + metricsRouteId + '_' + methodMapFnId;
  result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 2,
    [structuralMethodMapId]: 1  // Using structural ID
  });

  methodMapNodes = result.nodes.filter(n =>
    n.data.label && n.data.label.startsWith('method-map'));
  console.log('method-map nodes:', methodMapNodes.length);
  methodMapNodes.forEach(n => console.log('  ID:', n.data.id));

  // Step 4: Now the key test - what if we have TWO different expansion roots?
  // This simulates editor-routes showing both metrics-route and api-entities-route
  // We need to use editor-routes as root and expand both children

  console.log('\n=== Step 4: Simulating editor-routes with both routes expanded ===');
  // This is tricky - in the browser the state would be:
  // - Selected: editor-routes
  // - expansions: {
  //     editor-routes: some_level,
  //     metrics-route: 2,  (reached via editor-routes expansion)
  //     api-entities-route: 2  (reached via editor-routes expansion)
  //   }

  // But we need to find the correct expansion keys for this...
  // Let's try using editor-routes as root with deeply nested expansion

  const editorRoutesId = '1d3a1a71-a92a-4cc0-aae3-6aed58fb789d';

  // First find the structure
  result = await makeRequest(editorRoutesId, {
    ['fn-' + editorRoutesId]: 5  // Deep expansion
  });

  console.log('Looking for metrics-route and api-entities-route...');
  const metricsNode = result.nodes.find(n =>
    n.data.label && n.data.label.startsWith('metrics-route'));
  const apiEntitiesNode = result.nodes.find(n =>
    n.data.label && n.data.label.startsWith('api-entities-route'));

  if (metricsNode) console.log('metrics-route node ID:', metricsNode.data.id);
  if (apiEntitiesNode) console.log('api-entities-route node ID:', apiEntitiesNode.data.id);

  // Now expand those nodes
  if (metricsNode && apiEntitiesNode) {
    console.log('\n=== Step 5: Expand both routes from editor-routes context ===');
    result = await makeRequest(editorRoutesId, {
      ['fn-' + editorRoutesId]: 5,
      [metricsNode.data.id]: 2,
      [apiEntitiesNode.data.id]: 2
    });

    methodMapNodes = result.nodes.filter(n =>
      n.data.label && n.data.label.startsWith('method-map'));
    console.log('method-map nodes:', methodMapNodes.length);
    methodMapNodes.forEach(n => console.log('  ID:', n.data.id));

    if (methodMapNodes.length >= 1) {
      console.log('\n=== Step 6: Expand first method-map ===');
      const firstMethodMapId = methodMapNodes[0].data.id;

      result = await makeRequest(editorRoutesId, {
        ['fn-' + editorRoutesId]: 5,
        [metricsNode.data.id]: 2,
        [apiEntitiesNode.data.id]: 2,
        [firstMethodMapId]: 1
      });

      methodMapNodes = result.nodes.filter(n =>
        n.data.label && n.data.label.startsWith('method-map'));
      console.log('method-map nodes AFTER expanding one:', methodMapNodes.length);
      methodMapNodes.forEach(n => console.log('  ID:', n.data.id));

      console.log('\n=== VERIFICATION ===');
      if (methodMapNodes.length === 2) {
        console.log('PASS: Still have 2 separate method-map nodes');
      } else if (methodMapNodes.length === 1) {
        console.log('FAIL: method-map nodes MERGED into 1!');
      }
    }
  } else {
    console.log('Could not find both routes in editor-routes expansion');
  }
}

main().catch(console.error);
