# ADR — the inherited-rename SURFACE contract

Status: ACCEPTED (2026-08-27). Closes the residual gap recorded in
[RUNTIME_SLOT_ID_REFACTOR § 9](../RUNTIME_SLOT_ID_REFACTOR.md).

## The contract

> **The public name of a free slot is its closest exposure along the
> fn's inheritance chain** — a rename-view slot wins over its source
> at the same fn, a closer fn's exposure wins across fns, and the
> resolution is transitive through rename-of-rename chains.

"Public" means every place a HUMAN or an external CALLER meets the
name: the canvas edge label, the Run-form field, the fn-def `:args`
key, the `/api/execute` args map, `execute-with-named-args`, MCP.

A rename-view slot is inherited through the `parent-ids` closure like
any other slot, so a descendant of `:wrap-custom-script` (which
renames `content → body`) exposes `?body` — everywhere.

## Who already implemented it (before this ADR)

The contract was not invented here — three of the four layers had
independently converged on it:

| Layer | Implementation | Notes |
|---|---|---|
| Editor canvas | layout pipeline edge naming | shows `?body` on descendants |
| Run form / services guard / test runner | `compile.surface/public-free-entries` (since 2026-09-06; was `crud.fn-execution.lookup/free-args-via`) | the executor's public surface: name-walker membership + `surface-entries` renames, one hole per slot identity (a binding on either end of a rename, env-bindings included, closes it), plus HOF closure captures |
| fn-def parser | `records.slot-resolution/scalar-over-positional-hit` | bindings by the renamed name land on the anchor slot the editor writes |
| Executor boundary | `l/rename-for-slot` for ROOT slots only | positional/env-deep emissions kept the SOURCE name |

The executor's surface walker (`deep-free-ext-names` /
`deep-free-ext-entries`) was the odd one out for non-root slots: a
free-`body` descendant advertised `content`, `execute-with-named-args`
REJECTED `:body`, and the `/api/execute` path (whose arg form and
validation use the crud map, i.e. `body`) accepted the arg and then
silently dropped it at the executor boundary.

## The design decision

**Inherited renames are applied at the PUBLIC BOUNDARY as a
post-processing view over the walker's output — the walker itself and
every internal consumer stay on raw per-fid naming.**

The walker emits `{ext-name, slot-id}` entries; the boundary maps each
entry's slot-id through the chain-rename resolution
(`l/chain-rename-for-slot` — the closest-first, transitive walk
`rename-for-slot` already used) and presents the renamed name, while
still ACCEPTING the raw name (both route the caller's value to the
same slot-id in `translate-named-args`).

Consumers by side of the boundary:

- **Renamed view** (`cr/free-arg-ext-names`, `translate-named-args`'s
  accepted keys, `execute-with-named-args` validation): what callers
  and UIs see/say.
- **Raw walk, untouched** (`hof-lambda-params`,
  `alpha-equiv-lambda-params`, `build-ref-renames`,
  `build-hof-translation`, `cache-projection-frees`, env-binding
  writes): the compile-time wiring. `deep-free-ext-entries` itself is
  NOT modified — the boundary uses a separate accessor.

## Why not push the renames into the walk

Three attempts did exactly that and each broke working platform
classifications (see RUNTIME_SLOT_ID_REFACTOR § 9): the walk's
`translate` step encodes a deliberately PER-FID scoping
(bridged-vs-sibling ref-rename discrimination); widening the rename
map inside the walk re-shades surfaces the HOF alpha-equivalence and
lambda-param inference depend on (`_shape-secret-base`'s two
positional `{:as :fn-row}` views were the canonical casualty). The
walker's names are WIRING; the boundary's names are PRESENTATION —
conflating them is what caused the split-brain in the first place.

## Compatibility

- Raw (source) names remain accepted at every boundary — `{:content
  v}` keeps working next to `{:body v}` — so no existing caller,
  test, or stored composition changes behaviour.
- Root-slot surfaces are unchanged by construction
  (`rename-for-slot` already applied the chain there; the mapping is
  idempotent on them).
- Declared `:lambda-params` may use either the renamed or the raw
  name: validation accepts both, and a renamed declaration is
  canonicalized to its raw equivalent so the runtime keys stay on the
  wiring names.
