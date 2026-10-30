import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

test('JS AST lint rejects executable target literals and ignores comments', () => {
  const root = mkdtempSync(join(tmpdir(), 'ui-target-lint-'));
  try {
    const file = join(root, 'editor.js');
    writeFileSync(file, '// app.ui-canvas/canvas-view\nexport const target = "app.ui-canvas/canvas-view";\n');
    assert.throws(() => execFileSync(process.execPath, ['tools/ui-target-lint.mjs', root],
      { stdio: 'pipe' }), /hardcoded UI target literal/);
    writeFileSync(file, '// app.ui-canvas/canvas-view\nexport const target = `app.ui-canvas/canvas-view`;\n');
    assert.throws(() => execFileSync(process.execPath, ['tools/ui-target-lint.mjs', root],
      { stdio: 'pipe' }), /hardcoded UI target literal/);
    writeFileSync(file, '// app.ui-canvas/canvas-view\nexport const target = "canvas";\n');
    assert.doesNotThrow(() => execFileSync(process.execPath, ['tools/ui-target-lint.mjs', root],
      { stdio: 'pipe' }));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
