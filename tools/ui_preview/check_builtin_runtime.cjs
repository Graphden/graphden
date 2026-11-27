'use strict';

const { createHash } = require('node:crypto');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const assets = resolve(__dirname, '../../resources/packages/app/ui-preview');
const runtime = readFileSync(resolve(assets, 'browser-runtime.js'));
const artifact = JSON.parse(readFileSync(resolve(assets, 'builtin-plans.json'), 'utf8'));
const digest = createHash('sha256').update(runtime).digest('hex');

if (artifact.runtimeSha256 !== digest) {
  console.error('Builtin plans have a stale browser runtime fingerprint.');
  console.error('Regenerate with clojure -M:dev tools/ui_preview/generate_builtin_plans.clj');
  process.exitCode = 1;
} else {
  console.log('Builtin plans match the browser runtime fingerprint');
}
