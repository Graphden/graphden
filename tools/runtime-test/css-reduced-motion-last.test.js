'use strict';

// editor-styles.css — the `prefers-reduced-motion: reduce` block must be the
// LAST rule in the file (docs/ACCESSIBILITY.md § Motion). It overrides at
// equal specificity, so a rule appended after it (the λ / 📍 styles once
// were) silently wins over the reduced-motion reset again.

const fs = require('node:fs');
const path = require('node:path');

const css = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-styles.css'), 'utf8');

// Comments stripped, the last top-level block found by brace depth.
const src = css.replace(/\/\*[\s\S]*?\*\//g, '');
let depth = 0;
let lastStart = -1;
let blockStart = 0;
for (let i = 0; i < src.length; i++) {
  if (src[i] === '{') { if (depth === 0) lastStart = blockStart; depth += 1; }
  else if (src[i] === '}') { depth -= 1; if (depth === 0) blockStart = i + 1; }
}
const tail = src.slice(lastStart).trim();
const trailing = src.slice(blockStart).trim();
if (!/^@media\s*\(prefers-reduced-motion:\s*reduce\)/.test(tail) || trailing) {
  console.error('✗ the last rule in editor-styles.css is not the reduced-motion block: '
                + tail.slice(0, 80).replace(/\s+/g, ' '));
  process.exit(1);
}
console.log('✓ reduced-motion block is last');
