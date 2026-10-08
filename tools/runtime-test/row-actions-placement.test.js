// Real placement functions: a hover menu must leave the pending pointer click
// on its trigger reachable. Tall narrow menus scroll; wide layouts keep sides.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../resources/packages');
const shared = fs.readFileSync(path.join(root, 'web/runtime/graphden-popover.js'), 'utf8');
const rows = fs.readFileSync(path.join(root, 'app/editor/editor-row-actions.js'), 'utf8');
function harness(width, height, zoom) {
  const ctx = vm.createContext({console, window: {innerWidth: width, innerHeight: height},
    document: {addEventListener() {}}, gv: {ready: () => true, zoom: () => zoom}});
  vm.runInContext(shared + '\n' + rows, ctx);
  const el = {style: {}, offsetWidth: 240};
  Object.defineProperty(el, 'offsetHeight', {get: () => el.style.maxHeight
    ? Math.min(600, parseFloat(el.style.maxHeight)) : 600});
  return {ctx, el};
}
for (const zoom of [0.9, 1, 1.1]) {
  for (const top of [10, 95, 180]) {
    const {ctx, el} = harness(390, 240, zoom);
    const rect = {left: 175, right: 195, top, bottom: top + 20, height: 20};
    const card = {getBoundingClientRect: () => ({left: 120, right: 420})};
    const anchor = {getBoundingClientRect: () => rect, closest: () => card};
    ctx.positionRowActionsPopover(el, anchor);
    const placedTop = parseFloat(el.style.top);
    const placedBottom = placedTop + el.offsetHeight * zoom;
    const placedLeft = parseFloat(el.style.left);
    assert(placedBottom <= rect.top || placedTop >= rect.bottom,
      'vertical fallback never covers the trigger at top=' + top + ', zoom=' + zoom);
    assert(placedTop >= 0 && placedBottom <= 240 && placedLeft >= 0
      && placedLeft + el.offsetWidth * zoom <= 390, 'popup stays within the narrow viewport');
    assert.equal(el.style.overflowY, 'auto', 'long menu remains reachable by scrolling');
    assert.equal(el.style.boxSizing, 'border-box', 'height budget includes borders and padding');
    if (top === 180) assert(placedBottom < rect.top, 'near bottom chooses larger space above');
    else assert(placedTop > rect.bottom, 'near top/middle chooses larger space below');
    // Repositioning onto a wide viewport must remove the old scroll budget.
    ctx.window.innerWidth = 1600;
    ctx.window.innerHeight = 900;
    ctx.positionRowActionsPopover(el, anchor);
    assert.equal(el.style.maxHeight, '');
    assert.equal(el.style.overflowY, '');
    assert(parseFloat(el.style.left) > 420, 'wide viewport restores right-of-card placement');
  }
}
console.log('✓ row-actions geometry: narrow top/middle/bottom, zoom, scroll bounds, wide reset');
