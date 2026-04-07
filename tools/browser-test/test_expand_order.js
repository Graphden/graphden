const http = require('http');

// User's exact scenario:
// 1. Have metrics-route visible (select it)
// 2. Expand "route" on metrics-route - shows method-map
// 3. Expand "assoc-empty" on method-map - shows assoc-handler
// 4. Check: key: "get" should appear ONLY on assoc-handler, NOT on method-map

const metricsRouteId = '5079fbc4-76bb-4d77-88b7-ba77add388bf';

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
  console.log('=== User scenario: expand route, then assoc-empty ===\n');

  // Step 1: Initial state - metrics-route at level 0
  console.log('Step 1: Initial state (level 0)');
  let result = await makeRequest(metricsRouteId, {});
  console.log('Nodes:', result.nodes.map(n => n.data.label?.split('\n')[0]).join(', '));

  // Step 2: Expand metrics-route to level 1 (shows direct refs)
  console.log('\nStep 2: Expand metrics-route to level 1');
  result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 1
  });

  console.log('Nodes:');
  result.nodes.forEach(n => {
    const label = n.data.label?.split('\n')[0] || '(value)';
    console.log('  ' + label + ' (' + n.data.id.substring(0, 40) + '...)');
  });

  // Check for key edges
  let keyEdges = result.edges.filter(e => e.data.argName === 'key');
  console.log('\nEdges with argName "key":');
  keyEdges.forEach(e => {
    const srcNode = result.nodes.find(n => n.data.id === e.data.source);
    const tgtNode = result.nodes.find(n => n.data.id === e.data.target);
    console.log('  ' + (srcNode?.data.label?.split('\n')[0] || e.data.source) +
                ' --[key]--> ' + (tgtNode?.data.label || e.data.target));
  });

  // Step 3: Expand to level 2 (shows method-map)
  console.log('\n\nStep 3: Expand metrics-route to level 2');
  result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 2
  });

  console.log('Nodes:');
  result.nodes.filter(n => n.data.type === 'fn').forEach(n => {
    const label = n.data.label?.split('\n')[0];
    console.log('  ' + label + ' (' + n.data.id.substring(0, 40) + '...)');
  });

  keyEdges = result.edges.filter(e => e.data.argName === 'key');
  console.log('\nEdges with argName "key":');
  if (keyEdges.length === 0) {
    console.log('  (none)');
  }
  keyEdges.forEach(e => {
    const srcNode = result.nodes.find(n => n.data.id === e.data.source);
    const tgtNode = result.nodes.find(n => n.data.id === e.data.target);
    console.log('  ' + (srcNode?.data.label?.split('\n')[0] || e.data.source) +
                ' --[key]--> ' + (tgtNode?.data.label || e.data.target));
  });

  // Find method-map to expand it
  const methodMapNode = result.nodes.find(n =>
    n.data.label && n.data.label.startsWith('method-map'));

  if (!methodMapNode) {
    console.log('ERROR: method-map not found at level 2');
    return;
  }

  // Step 4: Expand method-map to show assoc-handler
  console.log('\n\nStep 4: Expand method-map to level 1 (shows assoc-handler)');
  const methodMapNodeId = methodMapNode.data.id;

  result = await makeRequest(metricsRouteId, {
    ['fn-' + metricsRouteId]: 2,
    [methodMapNodeId]: 1
  });

  console.log('Nodes:');
  result.nodes.filter(n => n.data.type === 'fn').forEach(n => {
    const label = n.data.label?.split('\n')[0];
    console.log('  ' + label + ' (' + n.data.id.substring(0, 40) + '...)');
  });

  keyEdges = result.edges.filter(e => e.data.argName === 'key');
  console.log('\nEdges with argName "key":');
  if (keyEdges.length === 0) {
    console.log('  (none)');
  }
  keyEdges.forEach(e => {
    const srcNode = result.nodes.find(n => n.data.id === e.data.source);
    const tgtNode = result.nodes.find(n => n.data.id === e.data.target);
    console.log('  ' + (srcNode?.data.label?.split('\n')[0] || e.data.source) +
                ' --[key]--> ' + (tgtNode?.data.label || e.data.target));
  });

  // Verification
  console.log('\n=== VERIFICATION ===');

  // Check if method-map has a key edge
  const methodMapKeyEdges = keyEdges.filter(e => {
    const srcNode = result.nodes.find(n => n.data.id === e.data.source);
    return srcNode?.data.label?.startsWith('method-map');
  });

  // Check if assoc-handler has a key edge
  const assocHandlerKeyEdges = keyEdges.filter(e => {
    const srcNode = result.nodes.find(n => n.data.id === e.data.source);
    return srcNode?.data.label?.startsWith('assoc-handler');
  });

  if (methodMapKeyEdges.length === 0 && assocHandlerKeyEdges.length === 1) {
    console.log('PASS: key: "get" shows ONLY on assoc-handler (correct!)');
  } else if (methodMapKeyEdges.length > 0 && assocHandlerKeyEdges.length > 0) {
    console.log('FAIL: key: "get" shows on BOTH method-map AND assoc-handler (duplicate!)');
  } else if (methodMapKeyEdges.length > 0 && assocHandlerKeyEdges.length === 0) {
    console.log('FAIL: key: "get" shows ONLY on method-map (should be on assoc-handler)');
  } else {
    console.log('INFO: No key edges found - need deeper expansion?');
  }
}

main().catch(console.error);
