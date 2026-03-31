const puppeteer = require('puppeteer');

// Debug the crossings after multi-expand

(async () => {
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2400, height: 1600 });
  await page.setCacheEnabled(false);
  await page.goto('http://example.com:9002/#editor-routes');
  await new Promise(r => setTimeout(r, 4000));

  // Find and click on list-10-9
  await page.evaluate(() => {
    const node = cy.nodes().filter(n => n.data('label')?.includes('list-10-9'))[0];
    if (node) node.emit('tap');
  });
  await new Promise(r => setTimeout(r, 500));

  // Expand 5 levels
  for (let i = 0; i < 5; i++) {
    await page.evaluate(() => {
      const overlays = document.querySelectorAll('.node-overlay');
      for (const overlay of overlays) {
        if (overlay.style.display !== 'none') {
          const lines = Array.from(overlay.querySelectorAll('.ancestor-line'));
          for (const line of lines) {
            const level = parseInt(line.dataset.level) || 0;
            const isBold = line.style.fontWeight === 'bold';
            if (level > 0 && !isBold) {
              line.click();
              return;
            }
          }
        }
      }
    });
    await new Promise(r => setTimeout(r, 1000));
  }

  // Debug specific crossings
  const result = await page.evaluate(() => {
    const elements = {
      nodes: cy.nodes().map(n => ({ data: n.data() })),
      edges: cy.edges().map(e => ({ data: e.data() }))
    };
    const layout = layoutGraph(elements);
    const { gridPos, matrix } = layout;
    const { nodeGrid, vEdge } = matrix;

    const crossings = [];

    for (let r = 0; r < nodeGrid.length; r++) {
      const rowLen = Math.max(
        (nodeGrid[r] || []).length,
        (vEdge[r] || []).length
      );
      for (let c = 0; c < rowLen; c++) {
        const nodeId = nodeGrid[r] && nodeGrid[r][c];
        const hasV = vEdge[r] && vEdge[r][c];

        if (nodeId && hasV) {
          const label = cy.$(`#${nodeId}`).data('label')?.split('\n')[0];

          // Find where the vEdge comes from (trace upward)
          let vEdgeSource = null;
          for (let checkRow = r - 1; checkRow >= 0; checkRow--) {
            if (vEdge[checkRow] && vEdge[checkRow][c]) {
              // Check if there's a node at this row that could be the source
              const srcNode = nodeGrid[checkRow] && nodeGrid[checkRow][c];
              if (srcNode) {
                vEdgeSource = {
                  row: checkRow,
                  col: c,
                  label: cy.$(`#${srcNode}`).data('label')?.split('\n')[0]
                };
                break;
              }
            } else {
              break; // vEdge stopped, look for node above
            }
          }

          // Find where the vEdge goes to (trace downward)
          let vEdgeTarget = null;
          for (let checkRow = r + 1; checkRow < nodeGrid.length; checkRow++) {
            if (vEdge[checkRow] && vEdge[checkRow][c]) {
              const tgtNode = nodeGrid[checkRow] && nodeGrid[checkRow][c];
              if (tgtNode) {
                vEdgeTarget = {
                  row: checkRow,
                  col: c,
                  label: cy.$(`#${tgtNode}`).data('label')?.split('\n')[0]
                };
                break;
              }
            } else {
              // Check if there's a node at this row
              const tgtNode = nodeGrid[checkRow] && nodeGrid[checkRow][c];
              if (tgtNode) {
                vEdgeTarget = {
                  row: checkRow,
                  col: c,
                  label: cy.$(`#${tgtNode}`).data('label')?.split('\n')[0]
                };
              }
              break;
            }
          }

          crossings.push({
            nodeLabel: label,
            nodeRow: r,
            nodeCol: c,
            vEdgeSource,
            vEdgeTarget
          });
        }
      }
    }

    // Also get the grid visualization for column 4 where most crossings are
    const col4Info = [];
    for (let r = 0; r < Math.min(20, nodeGrid.length); r++) {
      const nodeId = nodeGrid[r] && nodeGrid[r][4];
      const hasV = vEdge[r] && vEdge[r][4];
      const label = nodeId ? cy.$(`#${nodeId}`).data('label')?.split('\n')[0]?.substring(0, 20) : null;
      col4Info.push({
        row: r,
        node: label,
        vEdge: hasV
      });
    }

    return { crossings, col4Info };
  });

  console.log('=== Node-on-Edge Crossings Debug ===\n');
  result.crossings.forEach(c => {
    console.log(`Node "${c.nodeLabel}" at (${c.nodeRow}, ${c.nodeCol})`);
    if (c.vEdgeSource) {
      console.log(`  vEdge from: "${c.vEdgeSource.label}" at row ${c.vEdgeSource.row}`);
    }
    if (c.vEdgeTarget) {
      console.log(`  vEdge to: "${c.vEdgeTarget.label}" at row ${c.vEdgeTarget.row}`);
    }
    console.log('');
  });

  console.log('=== Column 4 State ===');
  result.col4Info.forEach(info => {
    const nodeStr = info.node ? `[${info.node}]` : '[ ]';
    const vEdgeStr = info.vEdge ? '|' : ' ';
    console.log(`Row ${info.row.toString().padStart(2)}: ${nodeStr.padEnd(25)} ${vEdgeStr}`);
  });

  await browser.close();
})();
