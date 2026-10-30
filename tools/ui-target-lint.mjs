import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { parse } from 'acorn';

const targetName = /\bapp\.ui-[A-Za-z0-9_-]+[/.:]/;
const uuid = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i;

function* javascriptFiles(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) yield* javascriptFiles(path);
    else if (entry.isFile() && entry.name.endsWith('.js')) yield path;
  }
}

function literals(source) {
  const root = parse(source, { ecmaVersion: 'latest', sourceType: 'module', locations: true });
  const pending = [root];
  const found = [];
  while (pending.length) {
    const node = pending.pop();
    if (!node || typeof node !== 'object') continue;
    if (node.type === 'Literal' && typeof node.value === 'string') found.push(node);
    if (node.type === 'TemplateElement') {
      found.push({ value: node.value.cooked ?? node.value.raw, loc: node.loc });
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) pending.push(...value);
      else if (value && typeof value === 'object' && value.type) pending.push(value);
    }
  }
  return found;
}

const roots = process.argv.slice(2);
const directories = roots.length ? roots : [
  'resources/packages/app/editor',
  'resources/packages/app/ui-preview',
];
const failures = [];
for (const directory of directories) {
  if (!statSync(directory, { throwIfNoEntry: false })?.isDirectory()) continue;
  for (const file of javascriptFiles(directory)) {
    // Generated graph plans contain stable function IDs by design. Their source
    // fns.edn files are checked by the EDN half of this lint.
    if (file === 'resources/packages/app/ui-preview/builtin-plans.js') continue;
    for (const node of literals(readFileSync(file, 'utf8'))) {
      if (targetName.test(node.value) || uuid.test(node.value)) {
        failures.push(`${relative('.', file)}:${node.loc.start.line}: hardcoded UI target literal`);
      }
    }
  }
}
for (const failure of failures) console.error(failure);
if (failures.length) process.exitCode = 1;
else console.log('UI JavaScript target literals: OK');
