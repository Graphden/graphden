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
  const listeners = new Map();
  const ctx = vm.createContext({console, window: {innerWidth: width, innerHeight: height},
    document: {addEventListener(type, callback) { listeners.set(type, callback); }},
    gv: {ready: () => true, zoom: () => zoom}});
  vm.runInContext(shared + '\n' + rows, ctx);
  const el = {style: {}, offsetWidth: 240};
  Object.defineProperty(el, 'offsetHeight', {get: () => el.style.maxHeight
    ? Math.min(600, parseFloat(el.style.maxHeight)) : 600});
  return {ctx, el, dispatch: type => listeners.get(type)?.()};
}

// Advancing the tutorial releases a pinned menu before the next target can be
// covered. It also cancels a pending async reveal from the previous step.
{
  const {ctx, el, dispatch} = harness(1600, 900, 1);
  const anchor = {isConnected: true, setAttribute() {}};
  ctx.ensureRowActionsPopover = () => el;
  ctx.ensureRowActionsCyHandlers = () => {};
  ctx.positionRowActionsPopover = () => { el.style.display = 'inline-block'; };
  ctx.requestAnimationFrame = callback => callback();
  el.querySelector = () => null;
  ctx.host = el;
  vm.runInContext('rowActionsPopoverEl = host;', ctx);
  ctx.toggleRowActionsPopoverSticky(anchor, () => {});
  assert.equal(el.style.display, 'inline-block');
  dispatch('gd-tour-step-changed');
  assert.equal(el.style.display, 'none');
  assert.equal(vm.runInContext('rowActionsPopoverAnchor', ctx), null);
  assert.equal(vm.runInContext('rowActionsPopoverSticky', ctx), false);

  let complete;
  ctx.toggleRowActionsPopoverSticky(anchor, () => ({then(resolve) { complete = resolve; }}));
  dispatch('gd-tour-step-changed');
  complete();
  assert.equal(el.style.display, 'none', 'late reply cannot restore the previous step menu');
}
for (const zoom of [0.9, 1, 1.1]) {
  for (const top of [10, 95, 180]) {
    const {ctx, el} = harness(390, 240, zoom);
    const rect = {left: 175, right: 195, top, bottom: top + 20, height: 20};
    const card = {getBoundingClientRect: () => ({left: 120, right: 420})};
    const anchor = {isConnected: true, getBoundingClientRect: () => rect, closest: () => card};
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

// A late partial cannot reveal a menu after its owning overlay was removed.
{
  const {ctx, el} = harness(1600, 900, 1);
  let complete;
  const anchor = {isConnected: true, setAttribute() {}, contains: other => other === anchor};
  ctx.anchor = anchor;
  ctx.host = el;
  vm.runInContext('rowActionsPopoverEl = host;', ctx);
  ctx.ensureRowActionsPopover = () => el;
  ctx.ensureRowActionsCyHandlers = () => {};
  ctx.requestAnimationFrame = callback => callback();
  ctx.clearTimeout = () => {};
  ctx.showRowActionsPopover(anchor, () => ({then(resolve) { complete = resolve; }}));
  ctx.dismissRowActionsPopoverWithin(anchor);
  anchor.isConnected = false;
  complete();
  assert.equal(el.style.display, 'none', 'late reply never reveals orphan menu');
  assert.equal(vm.runInContext('rowActionsPopoverAnchor', ctx), null);
  ctx.positionRowActionsPopover(el, anchor);
  assert.equal(el.style.display, 'none', 'detached geometry never positions at viewport origin');
}

// A pinned row menu belongs to its clicked anchor. Merely hovering a second
// row must not make that second row's click look like a toggle-close.
{
  const {ctx, el} = harness(1600, 900, 1);
  const makeAnchor = () => ({isConnected: true, setAttribute() {}});
  const first = makeAnchor(), second = makeAnchor();
  ctx.ensureRowActionsPopover = () => el;
  ctx.ensureRowActionsCyHandlers = () => {};
  ctx.positionRowActionsPopover = () => { el.style.display = 'inline-block'; };
  ctx.requestAnimationFrame = callback => callback();
  el.querySelector = () => null;

  ctx.toggleRowActionsPopoverSticky(first, () => {});
  assert.equal(vm.runInContext('rowActionsPopoverAnchor', ctx), first);
  assert.equal(vm.runInContext('rowActionsPopoverSticky', ctx), true);
  ctx.showRowActionsPopover(second, () => {}); // pointer hover only
  assert.equal(vm.runInContext('rowActionsPopoverAnchor', ctx), first,
    'hover does not steal a pinned menu');
  ctx.toggleRowActionsPopoverSticky(second, () => {}); // user click
  assert.equal(vm.runInContext('rowActionsPopoverAnchor', ctx), second,
    'click moves the pinned menu to the second row');
  assert.equal(vm.runInContext('rowActionsPopoverSticky', ctx), true,
    'the second row remains pinned for its action click');
  assert.equal(el.style.display, 'inline-block');
}
