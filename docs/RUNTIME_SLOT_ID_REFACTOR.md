## Runtime slot-id-keyed refactor (#104)

Branch: `refactor/slot-id-keyed-runtime`. Forked from `dca0e740`.

### 1. Problem

`:body` and similar names collide in the runtime free-args map (`fa`) when two semantically distinct slots reachable from one fn-def happen to share an ext-name. Concrete repro from `app/page/fns.edn`:

```
;; :html-page-handler :parent :html-ok-response
;;                    :args {:body :html-page-rendered}
;; (binds Ring HTTP body to rendered HTML string)
;;
;; Inside :html-page-rendered, an inline anon :parent :html-page
;; surfaces a free :body slot (HTML <body> content, hiccup tree).
```

Both slots' ext-name is `:body`. Caller passes `{:body [:p "ok"]}`. Test fails:

```
expected: <body><p>ok</p></body>
actual:   <body>&lt;html lang=&quot;en&quot;&gt;...&lt;title&gt;X&lt;/title&gt;...&lt;/html&gt;</body>
```

The `<body>` tag receives an HTML-escaped rendered page string instead of caller's hiccup. The two slots collide through `fa[:body]` and the wrong value flows to the inner consumer.

**Workaround today:** `:as :page-body` rename forces caller to pass `:page-body`; inner inline anon's slot exposes as `:page-body` (different ext-name), no collision. Inline comment in `app/page/fns.edn` documents the workaround.

### 2. Root cause

Storage and parser hold full slot-id identity (binding row stores `slot-id` UUID, `:body` resolved at sync time). **Runtime fa is name-keyed**, dropping identity. Two distinct slots with the same name collide.

| Layer | Identity model |
|---|---|
| `fns.edn` (author input) | name-keyed (human-readable) |
| Parser/sync | resolves names → slot-id; stores IDs |
| Storage (DB rows) | slot-id, fn-id, binding fields all UUIDs |
| Runtime fa | **name-keyed map** ← bug surface |
| `arg-builder` | reads `(get fa ext-name)` |
| `env-builder` | writes `(assoc fa env-name v)` |
| `apply-rename-aliases` | copies by name chain |
| `build-ref-renames` | name → name translation |
| HOF closure capture | by name |

### 3. Architectural conclusion

The name → ID resolution boundary belongs at **sync time** (parser writes binding rows with slot-id) and at the **public API boundary** (`execute-by-name` translates user's name-keyed args to slot-id-keyed fa). Everything past sync should be slot-id-keyed.

This matches CLAUDE.md principle: *Slots are global identities (one-shot creation, immutable). Bindings overlay them per (fn, slot) pair.* Storage realizes this; runtime currently doesn't.

### 4. Target architecture

```
┌─────────────────────────────────────────────────────────────┐
│ AUTHOR INPUT (fns.edn)              — name-keyed            │
│   {:body :html-page-rendered}                                │
│                          │ parser resolves                   │
│                          ▼                                   │
│ STORAGE (DB rows)                   — slot-id-keyed          │
│   binding{fn-id, slot-id, ref-fn-id}                         │
│                          │                                   │
│                          ▼                                   │
│ PUBLIC API ENTRY (execute-by-name, make-single-arg-callable) │
│   {:body val} → translate via walker → {<slot-uuid> val}     │
│   (ambiguity → :execution-error/ambiguous-arg-name)          │
│                          │                                   │
│                          ▼                                   │
│ RUNTIME fa                          — slot-id-keyed          │
│   {<slot-uuid-1> val-1, <slot-uuid-2> val-2, …}              │
│                                                              │
│   arg-builder       → (get fa <slot-uuid>)                  │
│   env-builder       → (assoc fa <slot-uuid> v)              │
│   per-ref translate → {callee-sid → caller-sid} per ref     │
│   HOF closure capture → translation via ref-fn-id linkage   │
│   cache-projection  → set of slot-uuid                      │
│                          │                                   │
│                          ▼                                   │
│ PUBLIC API EXIT / EDITOR DISPLAY    — translate back to names│
│   slot-id-keyed result → name-keyed for human consumption    │
└─────────────────────────────────────────────────────────────┘
```

### 5. Why incremental dual-key failed (old branch, 2026-06-24)

The dropped `refactor/slot-id-runtime-fa` tried to write slot-id keys ALONGSIDE name keys, so old name readers and new slot-id readers could coexist during transition. Three problems killed it:

1. **arg-builder's `bnd.slot-id` is chain-leaf** (base-fn root slot id). Chain-leaf slot-ids are **GLOBAL through base-fn** — every fn whose root is `:get` shares the same UUID for `:m`/`:k`/`:default`. Caller's value written at chain-leaf pollutes all unrelated consumers using that base-fn.

2. **Closest-own-rename slot-id (Phase 0.5 walker) is LOCAL** to a fn — caller's enrichment under that doesn't match inner reader's chain-leaf.

3. **Per-ref translation is required** to keep each fn's slot-id namespace separate. Without it, naive shared slot-id space collides.

Conclusion: no incremental dual-key bridging works. The cutover must flip the whole runtime to slot-id-keyed at once.

### 6. Implementation plan

Multi-week, single branch. Each phase ends green for the full test suite — no half-states merged into popovers/develop.

**Phase 1 — Walker (foundation)**

Rewrite from scratch (old branch's walker was tied to incremental approach):

- `deep-free-ext-entries fn-id lookups` → `[{:ext-name :slot-id} …]`
  - One entry per surface slot of fn-id's free args
  - slot-id is the **chain-leaf base-fn slot id** (the one inner consumer's `bnd.slot-id` actually uses)
  - Multiple entries with same ext-name allowed (NOT deduped) — public-boundary code decides what to do with ambiguity
- Memoised in lookups; tested with synthetic graphs

Acceptance: walker tests green; `bb test` green (no behaviour change yet — walker has no consumer).

**Phase 2 — Public API translator**

- `make-single-arg-callable` and friends: caller's `{name → val}` translated to `{name → val, slot-id → val, …}` via walker entries.
- Translator writes the value under the ext-name AND under EVERY chain-leaf slot-id the walker reports for that name. Most production fn-graphs have ONE caller-name reaching N inner consumers (each `(:get :coll :the-name)` inside a ref-tree contributes its own root-slot id) — that is NOT a collision; the caller's value legitimately fans out to all of them. Writing all matching slot-ids saves Phase 3 from inventing per-call propagation while readers are still name-keyed.
- **Original "throw on ambiguity" plan was wrong** — the walker can't distinguish "same name shared by N consumers" (which is intentional and ubiquitous) from "two semantically different slots that happen to share a name" (the #104 collision) without runtime context. The real collision will surface in Phase 5/6 when `:as` workarounds drop and slot-id-keyed readers can route distinctly.
- Runtime fa **starts** slot-id-keyed (multi-slot-id flavour above).
- Inner readers still by name — translator ALSO writes the name key. Transitional dual-key, only at public boundary, NOT inside runtime.
- The only dual-key bridge; safe because it happens once at entry, doesn't propagate through inner refs.

Acceptance: all tests green; public API behavior is observably unchanged in Phase 2.

**Phase 3 — Per-ref slot-id translation (mostly obsoleted by the transitive walker)**

Original plan: build `{callee-slot-id → caller-slot-id}` per `:ref` binding + `:seq :ref` item, apply at the ref-call boundary; replaces today's name-keyed `build-ref-renames`.

Outcome: the transitive walker shipped in Phase 1 (`deep-free-ext-entries`) emits CHAIN-LEAF slot-ids for every surface free arg, including ones reached by walking into ref-targets. Chain-leaf slot-ids are invariant — when F refs R, F's walker entries for R-surface use the SAME slot-ids R's own walker would emit. The Phase 2 boundary translator writes the value under those slot-ids directly. So a per-ref slot-id translation table is structurally always identity-skipped — it has nothing to copy.

This contrasts with the legacy name-keyed `build-ref-renames`, which IS load-bearing today: names CHANGE through renames, so caller's `fa[:outer]` has to be copied to callee's `fa[:inner]` at each ref call. Slot-ids don't change — the walker's `slot-id` field for an entry is the consumer's `bnd.slot-id` regardless of whose ext-name it surfaces under.

What landed:

- `build-ref-slot-renames callee-fn-id caller-fn-id lookups` exists in `renames.clj` with tests covering the empty / identity-skip / no-coverage branches. It's the structural reference for Phase 4 to read if any future codepath needs slot translation outside the boundary translator.
- No runtime wiring — every code path that calls a child closure has its fa populated by the boundary translator first (via `cr/execute` or `make-single-arg-callable`'s inner closure), so per-ref translation is dead weight.
- Today's name-keyed `build-ref-renames` stays in place until Phase 4 retires it alongside the name-keyed reader cutover.

Acceptance: all tests green; runtime behavior unchanged; the helper is in place for Phase 4 if needed.

**Phase 4 — Rename-aware slot-id readers (hybrid fa)**

Implemented 2026-06-25 (commit `b446f3c7`).

First attempt at Phase 4 used the chain-leaf slot-id as the reader's `fa` key. That failed because chain-leaf ids are GLOBAL through their base-fn — every fn composed on `:assoc` shares the same UUID for `:value`, every `:get` shares the same `:m`/`:k`/`:default`. The `:image` test (with two inline-anon `:assoc` calls each with their own `{:as :src}` / `{:as :alt}` renames) and `ex-pair-greet` cleanly surfaced the bug.

**Insight: rename-aware slot-ids already exist in storage.** Phase 6c made the parser create a NEW slot row on each renaming fn with `:source-slot-id` chaining back to the chain-leaf. Each `{:as :name}` rename is a distinct slot-id. Two inline-anons of the same `:assoc` with different `:as` renames each have their OWN rename slot id — distinct, no collision.

The runtime just wasn't reading at those slot-ids — it was indexing by the chain-leaf. Phase 4 wires in `l/effective-reader-slot-id fn-id slot-id lookups` — walks the reader's inheritance chain looking for the closest own-slot whose `:source-slot-id` chain transitively reaches the chain-leaf. Found → use that rename-slot id; not found → fall back to chain-leaf. Used by:

- Walker emits the rename-aware id per entry.
- `arg-builder :free` reads `fa[effective-reader-slot-id]` with name fallback.
- Seq positional `{:as :name}` items read the owner's rename slot id with name fallback.

**Hybrid `fa`**: the boundary translator writes slot-id keys for caller args; env-builder, hof-wrap lambda-args, and `build-ref-renames` slow path continue to write name keys. Readers prefer slot-id and fall back to name. This is the architecture, not a transitional kludge — the two key spaces serve different needs:

- **slot-id keys** distinguish structural ambiguity at the BOUNDARY: caller-side names that could land in two different consumers (the #104 collision class).
- **name keys** cover DYNAMIC writes: lambda-args, env-bindings, chain-rename copies. There's no structural ambiguity in these — just a value flowing under one name, set per call.

Tests verifying the #104 collision class:

- `:image` — two inline-anon `:assoc` calls of `{:as :src}` / `{:as :alt}` on `:value`. Each anon's rename slot now has a unique id; caller's `:src` and `:alt` land in different fa cells.
- `ex-pair-greet` `{:first "A" :second "B"}` → "A meets B". Two renames on the shared base-fn slot resolve to distinct cells.

**What didn't change at Phase 4** — `app/page/fns.edn`'s `:as :page-body` rename stayed because Phase 4 alone couldn't close the runtime collision; only the boundary translator and rename-aware readers shipped. Phase 5 (HOF wrap-time slot-id translation) + parser disambiguation (commit `38c3fc6e`) together let the workaround drop. See § Phase 5 for the runtime-side mechanism.

Acceptance: bb test 1591 / 6334 green; the `:image`, `ex-pair-greet`, `refinements-test` synthetic cases that exposed the collision class all pass.

**Phase 5 — HOF wrap-time slot-id translation (conservative)**

Implemented 2026-06-25.

Diagnosis from the Phase 4 page-body bug attempt: removing the `:as :page-body` rename surfaces #104 at runtime even though parser-side disambiguation (commit `38c3fc6e`) routes the bindings to the right slots at sync time. Mechanism:

1. Caller invokes `:html-page-route` with `:body [:p "ok"]`.
2. Walker for `:html-page-route` stops at the `:handler` HOF boundary — `translate-named-args` writes only the name key, no slot-id matches reach the deeper page-body slot.
3. Inside `:html-page-handler`, the `:body :html-page-rendered` binding is an env-binding (Ring-body rename slot is non-root). `env-builder` writes `fa[:body] = <thunk>` BY NAME, overwriting the caller's `[:p "ok"]`.
4. The deep `seq-item-builder` at `:_html-body-children-head`'s positional `:body` slot misses its slot-id key and falls back to `fa[:body]` — hits the env-binding thunk, forces it, renders the HTML page string into the inner `<body>` tag (escaped).

Three coordinated pieces close it:

1. **`hof-wrap` accepts a translation table** built by `r/build-hof-translation`. At wrap time (inside the HOF arg-builder), `apply-hof-translation` copies `fa[ext-name]` → `fa[R-slot-id]` for every R-side surface entry whose ext-name is NOT a lambda-param. Wired at BOTH HOF call sites — `arg-builder`'s `:ref :is-fn` case AND `env-arg-builder`'s `:ref :is-fn` case. This lets caller args past F's HOF surface (e.g. `:body` for the deep page-body slot when F's walker stops at the outer `:handler`) land under R's slot-id namespace BEFORE R's inner `env-builder` fires.
2. **Lambda-params excluded from translation** — lambda values come from the per-call `lambda-args` merge after the wrap-time `fa*` capture. Pre-translating them would freeze the wrap-time value across iterations and break collection HOFs (`:filter` / `:map` `:item`).
3. **Thunks skipped at copy** (commit `dcc11101`). An outer non-HOF `env-binding` writes `rt/thunk` under `fa[name]` whose body calls `call-with-cache` on a ref. Copying such a thunk under R's slot-id key would let R's inner reader find it via the Phase 4 slot-id path, force it, and re-enter `call-with-cache` on the SAME ref-id BEFORE the parent's cache stores — infinite recursion. The `:types-candidates` chain hit this (env-bindings `:validation` / `:parsed` are deferred thunks; the inner `:map` / `:filter` HOFs trip the loop). Caller-supplied free args are plain values and copy as before; env-binding deferred values stay on their existing name-fallback path. The thunk-skip surfaced only in `bb rebuild`'s smoke test (HTTP graph chain), not in `bb test` (which calls the Clojure helper). Coverage gap tracked separately.

The page-body fix works because: at the outer `:html-page-route` level, the `:handler` env-binding is a HOF that wraps `:html-page-handler`. The HOF env-builder fires AT WRAP-CONSTRUCTION TIME (when the route is built) and captures fa-ref. When the wrapped callable is later INVOKED (a request hits), `apply-hof-translation` runs against the captured fa — caller's `:body` is still under the name key (`:html-page-handler`'s own Ring-body env-binding hasn't fired yet — it lives one level deeper). Translation copies `fa[:body]` → `fa[<page-body-sid>]`. Now when the inner `:html-page-handler` chain runs, its Ring-body env-binding overwrites `fa[:body]` with a thunk, but the page-body slot's reader uses its own slot-id — `fa[<page-body-sid>]` still has caller's value. Collision dodged.

What DIDN'T flip:

- `env-builder` still writes by **name only** (today's behaviour). The earlier attempt to dual-write slot-id alongside the name caused stack overflows in `:list-fn-versions`-style chains: when an env-binding's value is a thunk that calls `call-with-cache` on a ref, the slot-id write makes inner readers find the same thunk via slot-id key instead of falling back to name; the `force-value` re-enters the chain. Single-write (name) keeps the existing semantics intact while HOF translation handles the cross-boundary slot-id propagation.
- Cross-fn slot-id rename cascades (e.g. `:method-map :handler` → `:assoc-handler :handler`) — still use name-fallback via `apply-rename-aliases`. Translating them to slot-ids needs env-builder slot-id writes + readers without name fallback, which the stack-overflow result above shows we can't ship as a one-shot change.

`build-hof-translation` signature deliberately accepts `f-fn-id` even though the conservative scope doesn't use it — the Phase 5 extension would compute `F-slot-id → R-slot-id` entries and the caller already has the value.

Acceptance: `bb test` green; `app/page/fns.edn` `:as :page-body` removed (Phase 6 for this workaround landed alongside).

**Phase 5 extension (deferred) — env-builder slot-id only + cross-fn rename slot-id translation**

The slot-id-only `env-builder` change is what frees the runtime from the name-key shadow that still requires `:as :page-body`-style workarounds in some patterns. Blocking pieces:

- HOF translation must populate cross-fn rename slots too (F-side rename slot id → R-side rename slot id matched by ext-name).
- Readers must drop name fallback so `env-builder`'s slot-id write is the only path.
- `apply-rename-aliases` becomes redundant in the cross-fn case.

This is the path the original Phase 5 doc described. Held in reserve.

**Phase 6 — Remove workarounds**

- `app/page/fns.edn`: `:as :page-body` → `:as :body` ✓ (landed with Phase 5 conservative)
- Audit other `:as` workarounds from feedback memories:
  - `feedback_optional_slot_free_arg_leak`
  - `feedback_callable_slot_inline_literal`
  - `feedback_assoc_slot_named_map`
- Tests + e2e + demo verify `:body`-named slots work without workaround

Acceptance: all workarounds removed; `bb ci` + e2e green.

**Phase 7 — Editor**

Editor surface still by name (human display). Walker entries' name + slot-id available for:

- arg-overlay rendering (one chip per surface free arg, by name; tooltip shows slot-id if requested)
- value-form `/api/value-form` continues to take arg-name on input (translates via walker)

Acceptance: no editor regression in visual baselines.

**Phase 8 — Cleanup**

- Remove transitional dual-key write at public API boundary (Phase 2's bridge)
- Remove name-based helpers in `compile/renames.clj` that are no longer used
- Document new architecture in `docs/ARCHITECTURE.md`

### 7. Risks

- **Performance**: per-ref translation adds compile-time work + small runtime map merge per ref call. Mitigation: cache translation maps in `lookups`; benchmark before/after.
- **Cache hit rate**: cache key changes shape (slot-id keys instead of names). Same correctness, possibly different hit pattern. Mitigation: benchmark.
- **HOF callable handlers from outside (e.g. middleware passing `{:request req :next-handler h}`)**: external callers use names. Phase 5 must accept name-keyed map at HOF entry boundary, translate inside. Same as Phase 2 public boundary.
- **Editor frontend**: continues to use names; verify `/api/value-form` and similar still work.
- **Hidden name-reads in base-fn impls**: `defbase` impls receive `(name → value)` map. Phase 4's slot-id-keyed fa must be translated back to name-keyed at impl invocation (impls don't change). Audit for any impl reading by raw name not in declared args.

### 8. Definition of done

- [x] Phases 1–5 (conservative) landed (commits `21b02a65` walker → `d08c2f68` translator → `ff5b02b3` slot-id renames helper → `b446f3c7` rename-aware readers → `38c3fc6e` parser disambiguation → `ac390c32` HOF wrap-time translation)
- [x] `bb test` green (1594 / 6340 / 0)
- [ ] `bb ci` green (run before merging)
- [x] page_test passes with `:body` (no `:as :page-body`)
- [ ] All e2e tests green (deferred to PR validation)
- [ ] Performance benchmarks not regressed > 10% (deferred to PR validation)
- [x] Workaround sweep (`:where {:value {}}` defensive pins + redundant scalar `:const` wraps) — commit `c49f577c`
- [ ] Phase 5 extension (env-builder slot-id-only + cross-fn rename slot-id translation + readers drop name fallback) — held in reserve
- [ ] Phase 8 cleanup (drop transitional dual-key write at public API boundary; drop name-based helpers in `compile/renames.clj`) — blocked on Phase 5 extension
- [ ] `feedback_104_*` memory updated to "closed — page-body workaround removed; runtime is HOF-translation-bridged past sync"
- [ ] `docs/ARCHITECTURE.md` section on executor updated to reflect hybrid (slot-id + name) runtime fa

### 9. Out of scope

- Editor UI shows names only (no slot-id columns / chips)
- fns.edn syntax unchanged
- Sync validator warnings about author-time collisions (deferred — separate concern, not blocking this refactor)
- Multi-rename slots, parser changes — none
- Public API signature changes — none (callers continue to pass names; translation is internal)
