// A queue can become dead while Organization is closed. Showing the surface
// must replace the cached queue body and process the fresh load trigger.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-sidebar-ops.js'), 'utf8');
const replacements = [];
const processed = [];
let queuesAvailable = true;
const old = {replaceWith: fresh => replacements.push(fresh)};
const sections = new Map(['assets', 'queues'].map(key => [key, {
  querySelector: () => old,
}]));
const host = {querySelector: selector => sections.get(
  /data-section="([^"]+)"/.exec(selector)?.[1])};
const build = key => () => ({querySelector: () => ({key, load: true})});
const context = vm.createContext({
  window: {htmx: {process: fresh => processed.push(fresh)}},
  document: {getElementById: id => id === 'gd-operate-panels' ? host : null},
  buildAssetsSection: build('assets'),
  buildQueuesSection: () => queuesAvailable ? build('queues')() : null,
});
vm.runInContext(source, context);
context.window.reloadDynamicOpsSections();
assert.deepEqual(replacements.map(row => row.key), ['assets', 'queues']);
assert.deepEqual(processed, replacements, 'process the newly connected bodies');
assert(replacements.every(row => row.load), 'fresh bodies retain the lazy-load contract');
const firstQueue = replacements[1];
context.window.reloadDynamicOpsSections();
assert.notEqual(replacements[3], firstQueue, 'reopening loads another fresh queue body');
queuesAvailable = false;
context.window.reloadDynamicOpsSections();
assert.equal(replacements.length, 5, 'unavailable queue builder leaves its section untouched');
context.window.htmx = null;
context.window.reloadDynamicOpsSections();
assert.equal(replacements.length, 5, 'missing HTMX leaves the mounted bodies intact');
console.log('PASS Operate live panels: refresh queue/asset bodies on every show');
