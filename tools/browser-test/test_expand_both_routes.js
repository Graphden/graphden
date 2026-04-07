const http = require('http');

// User's scenario:
// 1. Start with two routes: metrics-route and api-entities-route
// 2. Expand "route" on both of them (this shows method-map as a ref)
// 3. Then expand assoc-empty on ONE of the method-map nodes
// 4. Verify that assoc-handler nodes are separate (not merged)

// Known IDs from previous query
const metricsRouteId = '5079fbc4-76bb-4d77-88b7-ba77add388bf';
const apiEntitiesRouteId = '21164217-585a-4335-8ade-da58f255c6ce';

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
  // Step 1: Get initial graph for metrics-route at level 2 (shows deeper refs)
  console.log('=== Step 1: Expand metrics-route to level 2 ===');
  let result = await makeRequest(metricsRouteId, { ['fn-' + metricsRouteId]: 2 });

  console.log('All nodes at level 2:');
  result.nodes.forEach(n => {
    console.log('  ' + n.data.label?.split('\n')[0] + ' (id: ' + n.data.id.substring(0, 50) + '...)');
  });

  // Find method-map node - it's a ref from route
  const methodMapNode = result.nodes.find(n =>
    n.data.label && n.data.label.startsWith('method-map'));

  if (!methodMapNode) {
    console.log('\nNo method-map at level 2. Trying level 3...');
    result = await makeRequest(metricsRouteId, { ['fn-' + metricsRouteId]: 3 });

    console.log('All nodes at level 3:');
    result.nodes.forEach(n => {
      console.log('  ' + n.data.label?.split('\n')[0] + ' (id: ' + n.data.id.substring(0, 50) + '...)');
    });
  }

  const methodMapNodeAfter = result.nodes.find(n =>
    n.data.label && n.data.label.startsWith('method-map'));

  if (!methodMapNodeAfter) {
    console.log('\nERROR: method-map node not found even at level 3');
    return;
  }

  const methodMapId = methodMapNodeAfter.data.originalFnId;
  console.log('\nmethod-map fn-id:', methodMapId);

  // Step 2: Now test both routes
  // Expand metrics-route to level 3 (shows method-map)
  // Then expand method-map's assoc-empty (shows assoc-handler)

  console.log('\n=== Step 2: Expand metrics-route to level 3, then assoc-empty in method-map ===');

  // First check what we get at level 3
  result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 3
  });

  // Find assoc-empty in method-map inheritance chain
  const assocEmptyNode = result.nodes.find(n =>
    n.data.label && n.data.label.startsWith('assoc-empty'));

  if (assocEmptyNode) {
    console.log('Found assoc-empty:', assocEmptyNode.data.id);
  }

  // Now expand method-map's internal structure
  console.log('\n=== Step 3: Expand method-map to show assoc-handler ===');
  // method-map node ID from metrics-route expansion
  const methodMapNodeId = methodMapNodeAfter.data.id;

  result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 3,
    [methodMapNodeId]: 1  // Expand method-map
  });

  console.log('Nodes after expanding method-map:');
  result.nodes.filter(n => n.data.type === 'fn').forEach(n => {
    console.log('  ' + n.data.label?.split('\n')[0] + ' (id: ' + n.data.id.substring(0, 50) + '...)');
  });

  // Check for assoc-handler
  const assocHandlerNodes = result.nodes.filter(n =>
    n.data.label && n.data.label.startsWith('assoc-handler'));
  console.log('\nassoc-handler nodes:', assocHandlerNodes.length);

  // Check key edges
  const keyEdges = result.edges.filter(e => e.data.argName === 'key');
  console.log('Edges with argName "key":');
  keyEdges.forEach(e => {
    const srcNode = result.nodes.find(n => n.data.id === e.data.source);
    const tgtNode = result.nodes.find(n => n.data.id === e.data.target);
    console.log('  ' + (srcNode?.data.label?.split('\n')[0] || e.data.source));
    console.log('    --[key]--> ' + (tgtNode?.data.label || e.data.target));
  });

  // Step 4: Test with api-entities-route AND metrics-route both
  console.log('\n=== Step 4: Test api-entities-route with same expansion ===');

  result = await makeRequest(apiEntitiesRouteId, {
    ['fn-' + apiEntitiesRouteId]: 3
  });

  const apiMethodMapNode = result.nodes.find(n =>
    n.data.label && n.data.label.startsWith('method-map'));

  if (apiMethodMapNode) {
    console.log('api-entities-route method-map node ID:', apiMethodMapNode.data.id);
    console.log('metrics-route method-map node ID:', methodMapNodeId);

    if (apiMethodMapNode.data.id !== methodMapNodeId) {
      console.log('\nPASS: method-map nodes have DIFFERENT IDs (not merged)');
    } else {
      console.log('\nFAIL: method-map nodes have SAME ID (incorrectly merged)');
    }
  }

  // Now expand method-map on api-entities-route too
  const apiMethodMapNodeId = apiMethodMapNode?.data.id;
  if (apiMethodMapNodeId) {
    result = await makeRequest(apiEntitiesRouteId, {
      ['fn-' + apiEntitiesRouteId]: 3,
      [apiMethodMapNodeId]: 1
    });

    const apiAssocHandlerNodes = result.nodes.filter(n =>
      n.data.label && n.data.label.startsWith('assoc-handler'));
    console.log('\napi-entities-route assoc-handler nodes:', apiAssocHandlerNodes.length);
    apiAssocHandlerNodes.forEach(n => {
      console.log('  ID:', n.data.id);
    });

    const apiKeyEdges = result.edges.filter(e => e.data.argName === 'key');
    console.log('Edges with argName "key":');
    apiKeyEdges.forEach(e => {
      const srcNode = result.nodes.find(n => n.data.id === e.data.source);
      const tgtNode = result.nodes.find(n => n.data.id === e.data.target);
      console.log('  ' + (srcNode?.data.label?.split('\n')[0] || e.data.source));
      console.log('    --[key]--> ' + (tgtNode?.data.label || e.data.target));
    });
  }

  console.log('\n=== SUMMARY ===');
  console.log('Both routes should have their own separate method-map and assoc-handler nodes');
  console.log('Each assoc-handler should show key: "get" only once');
}

main().catch(console.error);
