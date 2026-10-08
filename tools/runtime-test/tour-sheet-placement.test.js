const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const viewport = {innerWidth: 390, innerHeight: 844};
const ctx = vm.createContext({window: viewport});
vm.runInContext(fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-tour-spot.js'), 'utf8'), ctx);
const rect = (top, height) => ({left: 0, right: 390, top, bottom: top + height});
function place(target, height = 280) {
  return ctx._tourPickSpot([{left: 0, top: viewport.innerHeight - height},
    {left: 0, top: 0}], viewport.innerWidth, height, target, [], 0);
}
const run = rect(700, 40);
const upper = rect(100, 40);
assert.equal(place(run).top, 0, 'full-width phone lesson moves above the lower Run control');
assert.equal(place(upper).top, 564, 'upper controls keep the lesson at the bottom');
assert.equal(place(rect(350, 40)).top, 564, 'keep the default dock when both edges are clear');
assert.equal(ctx._tourOverlapArea(0, place(run).top, 390, 280, run), 0,
  'the Run hit target has no lesson-card overlap');
viewport.innerHeight = 620;
assert.equal(place(rect(520, 40)).top, 0, 'a shorter phone retains access to lower controls');
assert.equal(place(rect(30, 40)).top, 340, 'a resized upper control chooses the opposite edge');
const tall = rect(100, 420);
assert.equal(place(tall).top, 340, 'an unavoidable overlap chooses the less-covered target area');
const desktop = ctx._tourPickSpot([{left: -50, top: -50}], 100, 100, null, []);
assert.equal(desktop.left, 12);
assert.equal(desktop.top, 12, 'desktop candidate margins retain their existing contract');
console.log('PASS phone lesson placement: lower/upper controls, resize, overlap and desktop margins');
