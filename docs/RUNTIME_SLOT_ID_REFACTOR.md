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

**Phase 4 — Reader/writer cutover**

- `arg-builder :free`: `(get fa bnd.slot-id)` (no name fallback)
- `arg-builder :seq` :as: `(get fa <as-name's slot-id at owner>)`
- `env-builder`: `(assoc fa env-bnd.slot-id v)` only
- `apply-rename-aliases`: remove (chain copying belongs at parser, not runtime — Phase 3's per-ref translation handles inter-fn slot mapping)
- Cache-projection: `cache-projection-frees` returns `Set<slot-id>`; `fa-key-for-cache` uses slot-id keys

Acceptance: name-keyed reads/writes fully gone from runtime; all tests green.

**Phase 5 — HOF closure capture**

HOF F → R binding: F's HOF binding row stores `ref-fn-id = R-id`. Compile-time:
- Get R's walker entries (closure-captured slot-ids R will read)
- Match by ext-name to F's walker entries
- Build `{R-slot-id → F-slot-id}` translation

Runtime: `hof-wrap` applies translation when constructing R's fa from F's fa + lambda-args.

Acceptance: HOF chains work, `make-shape-callable` lambda-args under slot-id keys.

**Phase 6 — Remove workarounds**

- `app/page/fns.edn`: `:as :page-body` → `:as :body`
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

- [ ] All phases landed
- [ ] `bb test` green
- [ ] `bb ci` green
- [ ] page_test passes with `:body` (no `:as :page-body`)
- [ ] All e2e tests green
- [ ] Performance benchmarks not regressed > 10%
- [ ] `feedback_104_*` memory updated to "closed — runtime is slot-id-keyed past sync"
- [ ] `docs/ARCHITECTURE.md` section on executor updated to reflect slot-id-keyed runtime
- [ ] All `:as :<workaround>` from feedback memories removed where the slot-id flip lets them go

### 9. Out of scope

- Editor UI shows names only (no slot-id columns / chips)
- fns.edn syntax unchanged
- Sync validator warnings about author-time collisions (deferred — separate concern, not blocking this refactor)
- Multi-rename slots, parser changes — none
- Public API signature changes — none (callers continue to pass names; translation is internal)
