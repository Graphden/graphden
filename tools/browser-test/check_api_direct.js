const http = require('http');

// We need to get the root ID from the database first
// Let's make a layout request and see what we get

const makeRequest = (body) => new Promise((resolve, reject) => {
  const data = JSON.stringify(body);
  const options = {
    hostname: 'localhost',
    port: 9002,
    path: '/api/graph/layout',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': data.length
    }
  };

  const req = http.request(options, (res) => {
    let responseData = '';
    res.on('data', (chunk) => responseData += chunk);
    res.on('end', () => {
      try {
        resolve(JSON.parse(responseData));
      } catch (e) {
        resolve({ error: responseData });
      }
    });
  });

  req.on('error', reject);
  req.write(data);
  req.end();
});

// First, let's query entities to find web-server ID
const queryEntities = () => new Promise((resolve, reject) => {
  const data = JSON.stringify({});
  const options = {
    hostname: 'localhost',
    port: 9002,
    path: '/api/entities',
    method: 'GET',
    headers: {
      'Content-Type': 'application/json'
    }
  };

  const req = http.request(options, (res) => {
    let responseData = '';
    res.on('data', (chunk) => responseData += chunk);
    res.on('end', () => {
      try {
        resolve(JSON.parse(responseData));
      } catch (e) {
        resolve({ error: responseData });
      }
    });
  });

  req.on('error', reject);
  req.end();
});

(async () => {
  try {
    console.log('Fetching entities...');
    const entities = await queryEntities();

    if (entities.error) {
      console.log('Error fetching entities:', entities.error.substring(0, 500));
      return;
    }

    // Find web-server
    const webServer = (entities.fns || []).find(f => f.name === 'web-server');
    const createRoute = (entities.fns || []).find(f => f.name === 'entity-form-create-route');

    if (!webServer) {
      console.log('web-server not found');
      console.log('Available fns:', (entities.fns || []).slice(0, 10).map(f => f.name));
      return;
    }

    console.log('web-server ID:', webServer.id);
    console.log('create-route ID:', createRoute?.id);

    // Make layout request
    console.log('\nFetching layout...');
    const expansions = {};
    if (createRoute) {
      expansions[createRoute.id] = 2;
    }

    const layout = await makeRequest({
      'root-id': webServer.id,
      expansions: expansions
    });

    if (layout.error) {
      console.log('Error:', layout.error);
      return;
    }

    // Find create-route node
    const createRouteNode = (layout.nodes || []).find(n =>
      (n.data?.label || '').includes('entity-form-create-route')
    );

    if (createRouteNode) {
      console.log('\n=== CREATE-ROUTE ===');
      console.log('ID:', createRouteNode.data.id);

      const pos = layout['grid-pos']?.[createRouteNode.data.id];
      console.log('Position: row=' + pos?.row + ', col=' + pos?.col);

      // Find children
      const children = (layout.edges || [])
        .filter(e => e.data?.source === createRouteNode.data.id)
        .map(e => {
          const tgt = (layout.nodes || []).find(n => n.data?.id === e.data?.target);
          const childPos = layout['grid-pos']?.[e.data?.target];
          return {
            argName: e.data?.argName || 'N/A',
            label: (tgt?.data?.label || '').substring(0, 35).replace(/\n/g, '|'),
            type: tgt?.data?.type,
            row: childPos?.row,
            col: childPos?.col
          };
        })
        .sort((a, b) => (a.row ?? 999) - (b.row ?? 999) || (a.col ?? 999) - (b.col ?? 999));

      console.log('\nChildren (sorted by row):');
      children.forEach(c => {
        console.log(`  [row=${c.row}, col=${c.col}] ${c.argName}: ${c.label} (${c.type})`);
      });
    }

    // Find shared nodes
    const incomingCount = {};
    for (const e of (layout.edges || [])) {
      const target = e.data?.target;
      if (target) {
        incomingCount[target] = (incomingCount[target] || 0) + 1;
      }
    }

    console.log('\n=== SHARED NODES ===');
    for (const [nodeId, count] of Object.entries(incomingCount)) {
      if (count > 1) {
        const node = (layout.nodes || []).find(n => n.data?.id === nodeId);
        const parents = (layout.edges || [])
          .filter(e => e.data?.target === nodeId)
          .map(e => {
            const src = (layout.nodes || []).find(n => n.data?.id === e.data?.source);
            const srcPos = layout['grid-pos']?.[e.data?.source];
            return {
              label: (src?.data?.label || '').substring(0, 25).replace(/\n/g, '|'),
              row: srcPos?.row
            };
          })
          .sort((a, b) => (a.row ?? 0) - (b.row ?? 0));

        console.log((node?.data?.label || '').substring(0, 30).replace(/\n/g, '|') + ':');
        parents.forEach(p => console.log(`  [row=${p.row}] ${p.label}`));
      }
    }

  } catch (e) {
    console.log('Error:', e);
  }
})();
