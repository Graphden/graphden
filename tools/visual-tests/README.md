# Visual-regression tests

Playwright-based screenshot tests for the Graphden editor. Captures
key UI states and diffs each new run against committed baselines.

## Why this exists

`bb biome` catches broken JS, `bb stylelint` catches broken CSS / off-
palette colours. Neither catches **layout regressions** — an overlay
that grows by 4px, a sidebar that loses a separator, a tooltip that
no longer pops above the canvas. Visual diffs catch that.

## Running

The editor must be running at `http://localhost:9002` with `AUTH_TOKEN`
matching `.env`:

```bash
# First time / after intentional UI change → refresh baselines:
bb visual-update

# Normal run → assert against baselines:
bb visual
```

Override the URL or token at the call site:

```bash
GRAPHDEN_URL=http://prod.example.com AUTH_TOKEN=xxx bb visual
```

## Updating baselines

When a test fails because you intentionally changed the UI:

1. Run `bb visual` and inspect the diff in `playwright-report/`
   (`npm run report` from this dir).
2. If the change is intended → `bb visual-update`.
3. Commit the updated PNGs under
   `tests/editor.spec.js-snapshots/`.

## Tolerance

`maxDiffPixelRatio: 0.02` — ~2% of pixels may differ. This is
generous enough to absorb subpixel antialiasing differences between
machines without letting real regressions through. If a test flakes
on a clean run, fix the source of nondeterminism (animation, async
load) rather than raising the tolerance.

## Adding a new state

Edit `tests/editor.spec.js`. Each `test()` block becomes one
baseline. Filename inside `toHaveScreenshot()` is the snapshot name
— make it descriptive (`05-overlay-edit-popover.png`).
