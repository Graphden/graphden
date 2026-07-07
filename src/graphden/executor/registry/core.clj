(ns graphden.executor.registry.core
  "Core infrastructure for base function registration + storage sync.

   ## Slot/binding model

   Base functions get synced as fn rows with a return-type (the
   base-fn marker), accompanied by slot/fn-slot rows produced by
   `graphden.packages.records/parse-fn-def`. Type-rows (record /
   refinement / list) take the same path: their role is encoded in the
   fn-row's `:base-fn-id` / `:element-fn-id` / `:constraint` /
   non-empty `:fn-slot` rows.

   This namespace owns:
   - register-base-fns! — Clojure impl registration (executor's atom).
   - sync-defs-to-storage! — `{fn-name → fn-def}` to records, then
     batch upsert via `composition/write-records!`.
   - rich-types-registry — in-memory `{fn-name → {:return :args :effects}}`
     map kept in sync with the source-of-truth fn-defs. The type-checker
     reads from here; storage rows degrade structural types to a single
     primitive `value-kind`."
  (:require
    [graphden.executor.composition.core :as composition]
    [graphden.executor.interface :as exec]
    [graphden.packages.records :as records]
    [graphden.types.core :as types]))


;; =============================================================================
;; Synthesised impls for type-rows (record / refinement / list)
;; =============================================================================

(defn- record-type-impl
  "Synthesised impl for a record-type fn-row. The args map IS the
   record — every field already present as a key. Resolve any delays
   the executor passed and return the assembled map."
  [args _ctx]
  (into {} (map (fn [[k v]] [k (force v)])) args))


(defn- refinement-type-impl
  "Synthesised impl for a refinement-type fn-row. Single arg :value;
   validate against the closed-over constraint, throw on violation,
   pass-through otherwise. `:unknown` results pass through — best-effort."
  [constraint]
  (fn [args _ctx]
    (let [v (force (:value args))
          check-fn (requiring-resolve 'graphden.types.check.literals/literal-satisfies-refinement?)
          result (check-fn v constraint)]
      (when (false? result)
        (throw (ex-info (str "refinement constraint failed: "
                             (pr-str constraint) " on value " (pr-str v))
                        {:type :refinement/violated
                         :constraint constraint
                         :value v})))
      v)))


(defn- list-type-impl
  "Synthesised impl for a list-type fn-row — pass the items through,
   forcing any thunks the executor placed in `:items`. Hoisted to
   a top-level def so every call site shares one identity (otherwise
   each `compute-base-fns-map` invocation creates a fresh closure,
   wobbling the base-fns map's hash across boots and blocking
   `compile-all-templates`' cross-boot template cache)."
  [args _ctx]
  (force (:items args)))


;; Per-constraint cache so `refinement-type-impl` doesn't fabricate a
;; fresh closure on every `compute-base-fns-map` call — same constraint
;; would otherwise hash differently per boot and pollute the
;; templates-cache key.
(def ^:private refinement-impl-cache (atom {}))


(defn- cached-refinement-impl
  [constraint]
  ;; Identity-stable: under concurrent first-touch on the same
  ;; `constraint`, two threads racing through the cache miss would each
  ;; compute their OWN `refinement-type-impl` value (new fn per call),
  ;; and `swap! assoc` would let the SECOND writer overwrite the first.
  ;; Both threads then return DIFFERENT fn references for the same
  ;; constraint — breaking the identity-stable contract the
  ;; `compute-base-fns-map` template cache relies on. Wrap the assoc
  ;; in a `swap!` that no-ops when the key landed during the race so
  ;; whichever thread wins becomes the canonical impl; both readers
  ;; return that same canonical value.
  (or (get @refinement-impl-cache constraint)
      (let [impl (refinement-type-impl constraint)
            after (swap! refinement-impl-cache
                         (fn [m]
                           (if (contains? m constraint)
                             m
                             (assoc m constraint impl))))]
        (get after constraint))))


(defn- synthesised-impl-for
  "Type-row markers `:type {…}` (record), `:refine {…}` (refinement),
   `:list T` (list) get an auto-generated impl. Other fn-defs return
   the user-provided `:impl`.

   Refinement + list impls flow through identity-stable cached
   constructors so the resulting `compute-base-fns-map` output hashes
   the same on repeated invocations — required for
   `compile.compile-all-templates`' template cache to hit."
  [fn-def]
  (cond
    (:type fn-def)   record-type-impl
    (:refine fn-def) (cached-refinement-impl (:constraint (:refine fn-def)))
    (:list fn-def)   list-type-impl
    :else            (:impl fn-def)))


(defn compute-base-fns-map
  "Build `{fn-name → impl}` from a `defs` map of fn-name → fn-def
   without mutating any registry. Type-row markers get a synthesised
   impl (record / refinement / list); fn-defs with no impl AND no
   marker are skipped (anonymous types etc.). Pure data — the
   integrant `:exec/base-fns` init-key uses this to surface the map
   to `:exec/context` as a ref-dep, so ctx's `:base-fns` snapshot
   comes from the pipeline instead of the global atom."
  [defs]
  (into {}
        (keep (fn [[fn-name fn-def]]
                (when-let [impl (synthesised-impl-for fn-def)]
                  [fn-name impl])))
        defs))


(defn register-base-fns!
  "Registers Clojure impls in the executor's global registry. Type-rows
   (no `:impl` key) get a synthesised impl matching their role.

   Kept for direct callers that don't go through the integrant
   pipeline — production / `bb run` flows snapshot the map via
   `compute-base-fns-map` and pass it through ctx instead. The global
   atom still holds the merged result so legacy callers
   (`exec/get-base-fn` by name from REPL etc.) keep working."
  [defs]
  (doseq [[fn-name impl] (compute-base-fns-map defs)]
    (exec/register-base-fn! fn-name impl)))


;; =============================================================================
;; Rich-types registry — in-memory map
;; =============================================================================
;;
;; Storage's `value-kind` enum loses structure (`:fn` instead of
;; `[:fn args ret]`). The rich shape lives here so save-time type
;; checking can do real subtype/unify against the original. Empty
;; until `sync-defs-to-storage!` populates it.

(defonce ^:private rich-types-registry (atom {}))


;; Thread-local override for parallel-test isolation. When `nil`
;; (production + any non-test code path), reads and writes go to the
;; process-global `rich-types-registry`. When bound (by
;; `interface/with-isolated-rich-types` or the kaocha parallel plugin's
;; isolation-vars list), ALL reads and writes go to the override
;; atom — so a test ns gets a private rich-types-registry that's
;; pre-seeded with the global snapshot at bind time.
;;
;; Pre-seed (rather than merge-on-read): the type-checker calls
;; `rich-type-of` deep in the `effective-ref-return` recursion — for
;; a single fn-def check, the read count is O(depth × fn-defs). The
;; earlier "merge global ∪ override on every read" view turned every
;; one of those reads into an O(N) full-registry merge (~1500
;; entries), tipping the smoke_pass_test bootstrap from seconds into
;; a 20-minute GC-thrashing hang. The snapshot-at-bind strategy
;; keeps reads O(1) while still giving each test thread a private
;; view: `with-isolated-rich-types` and the kaocha plugin both call
;; `snapshot-for-isolation` to clone the current global state into a
;; fresh atom, bind that atom under `*rich-types-override*`, and let
;; the test write into it freely.
;;
;; Mirrors the `*registry-override*` pattern that
;; `executor/registry.clj` uses for the base-fn registry — see that
;; file for the design rationale.
(def ^:dynamic *rich-types-override*
  nil)


(defn snapshot-for-isolation
  "Snapshot the current global rich-types-registry. Used by test
   fixtures and the kaocha parallel plugin to pre-populate a fresh
   thread-local override so reads stay O(1) hash lookups instead of
   degrading into per-read O(N) merges of global ∪ override."
  []
  @rich-types-registry)


(defn- target-rich-types-atom
  "Atom that reads and writes land on — override when bound, else the
   global. With the snapshot-at-bind strategy a bound override is
   already pre-populated with the global snapshot, so no fall-through
   is needed."
  []
  (or *rich-types-override* rich-types-registry))


(defn- rich-types-view
  "Snapshot of the active rich-types map — override when bound,
   global otherwise. O(1) deref, no merge."
  []
  @(target-rich-types-atom))


;; §4 Risk-2 (rich-types): the global registry is keyed by BARE fn-name, so two
;; orgs' same-named composed fns collide (last-write-wins). This per-org slice
;; `{org → {fn-name → info}}` keeps each org's own. Populated by
;; `record-rich-types-raw!` (the type-checker's tenant-fn writes) under the
;; ambient org; `rich-type-of` prefers it for a tenant and falls back to the
;; org-agnostic global — so the compile / public path is UNCHANGED (it never has
;; a tenant org bound), only tenant type-checks/reads gain precedence.
(defonce ^:private per-org-rich-types (atom {}))


(def ^:dynamic *per-org-rich-override*
  "Parallel-test isolation, mirrors `*rich-types-override*`."
  nil)


(defn- target-per-org-rich-atom
  []
  (or *per-org-rich-override* per-org-rich-types))


(defn per-org-rich-snapshot-for-isolation
  "Seeder for the isolation override (so a bound override starts from the global
   per-org state, matching `snapshot-for-isolation`)."
  []
  @(target-per-org-rich-atom))


(def ^:private current-org-var (atom nil))


(defn- current-org-scope
  "The ambient tenant org — `tc/current-org` via `resolve` (registry/core can't
   require the tenancy layer; same trick as branch-router's `current-scope`).
   nil when tenancy isn't loaded (single-tenant) → `rich-type-of` falls to the
   global. The var is cached after the first successful resolve."
  []
  (when-let [v (or @current-org-var
                   (reset! current-org-var
                           (resolve 'graphden.tenancy.context/current-org)))]
    (v)))


(def ^:private public-org-var (atom nil))


(defn- current-tenant-org
  "The ambient org IFF it's a real TENANT — not public, not single-tenant
   (nil). Returns nil otherwise, meaning 'use the org-agnostic global'. So the
   per-org slice holds ONLY tenant entries: the compile / public / sync path is
   never mirrored and never reads per-org, keeping it exactly as it was."
  []
  (when-let [org (current-org-scope)]
    (let [public (when-let [v (or @public-org-var
                                  (reset! public-org-var
                                          (resolve 'graphden.tenancy.context/public-org)))]
                   @v)]
      (when (not= org public) org))))


(defn- validate-arg-type!
  [arg-name arg-type]
  ;; Accept primitives, type-vars, structural types — and any other
  ;; keyword (which may name a user-declared refinement / record /
  ;; list / union / variant fn-row). Genuine typos surface at the
  ;; records-parser stage, where unknown keyword references throw
  ;; `:records/unknown-type-ref`.
  (when-not (or (keyword? arg-type)
                (types/well-formed? arg-type))
    (throw (ex-info (str "Unknown arg type: " (pr-str arg-type))
                    {:type :invalid-arg-type
                     :arg-name arg-name
                     :arg-type arg-type}))))


(defn- arg-spec->rich-type
  "Extracts the structural rich-type from an arg-spec. Accepts:
     :int / :fn / …                       primitive keyword
     :positive-int                        type alias / named refinement
     'a                                   type variable
     [:fn args ret] / [:list T] / …       structural
     {:type T :required B :description S} loader's expanded form
     {:k :int}                            inline record"
  [arg-name arg-spec]
  (cond
    (or (keyword? arg-spec) (symbol? arg-spec) (vector? arg-spec))
    (let [resolved (types/resolve-alias arg-spec)]
      (validate-arg-type! arg-name resolved)
      resolved)

    (map? arg-spec)
    (cond
      (contains? arg-spec :type)
      (let [arg-type (types/resolve-alias (:type arg-spec))]
        (validate-arg-type! arg-name arg-type)
        arg-type)

      (types/well-formed? arg-spec)
      arg-spec

      :else
      (throw (ex-info "arg-spec map must contain :type key"
                      {:type :invalid-arg-spec
                       :arg-name arg-name
                       :arg-spec arg-spec})))

    :else
    (throw (ex-info "arg-spec must be a keyword or map with :type"
                    {:type :invalid-arg-spec
                     :arg-name arg-name
                     :arg-spec arg-spec}))))


(defn record-rich-types!
  "Snapshot the structured types for one fn-def into the in-memory
   registry. Idempotent — re-syncing overwrites. Aliases are resolved
   to their structural body so downstream type-check sees the canonical
   shape.

   `:effects` is recorded straight from the fn-def as a set of keyword
   tags (`:db` / `:env` / `:io` / `:network` / `:time` / `:random` /
   `:process` / `:raw-sql`). `:process` is the service-eligibility marker —
   declares 'spawns supervised background work'; required by the
   `:_create-service-no-process-rej` graph guard when admins make a
   fn into a `:service`.
   Every base-fn that produces side effects names a specific category.
   `:description` is propagated so the editor's inline-expand panel can
   surface a human-readable hint without a separate API call.

   `:return-type-rule` / `:slot-types-rule` / `:nav-types-rule` —
   per-base-fn type-rules declared at the base-fn's `impls.clj`
   registration site. When present they ride into the registry entry
   so the type-checker looks them up by base-fn identity instead of
   dispatching a multimethod on the fn name."
  [fn-name fn-def]
  (let [args (:args fn-def)
        ret  (some-> (:return-type fn-def) types/resolve-alias)
        per-arg (into {}
                      (map (fn [[arg-name arg-spec]]
                             [arg-name (or (arg-spec->rich-type arg-name arg-spec) :any)]))
                      args)
        raw-effects (set (:effects fn-def))
        desc (:description fn-def)
        build (fn [final-effects]
                (cond-> {:return (or ret :any)
                         :args   per-arg
                         ;; Always store the computed set, even when empty.
                         ;; compute-effects is total — every fn has a known
                         ;; set, possibly #{} (pure). Gating on (seq effects)
                         ;; collapsed "computed pure" and "no info recorded"
                         ;; into one absent-key state, which forced every
                         ;; consumer downstream to write (or (:effects info)
                         ;; #{}) to recover the pure case. Storing #{}
                         ;; explicitly drops the asymmetry.
                         :effects final-effects}
                  (and desc (seq desc))      (assoc :description desc)
                  (:return-type-rule fn-def) (assoc :return-type-rule
                                                    (:return-type-rule fn-def))
                  (:slot-types-rule fn-def)  (assoc :slot-types-rule
                                                    (:slot-types-rule fn-def))
                  (:nav-types-rule fn-def)   (assoc :nav-types-rule
                                                    (:nav-types-rule fn-def))
                  ;; `:lazy-seq-args` — slot names where each ITEM in the
                  ;; seq slot's list arrives as a `delay`, so a consumer
                  ;; like `:cond` can step past an unforced item.
                  ;;
                  ;; Scalar lazy slots DON'T need a marker — every `:ref`
                  ;; binding compiles to a delay by default; Clojure's
                  ;; native `if`/`and`/`or` short-circuit on un-read args.
                  (:lazy-seq-args fn-def)    (assoc :lazy-seq-args
                                                    (:lazy-seq-args fn-def))
                  ;; `:source-file` / `:source-line` — origin of the EDN entry
                  ;; (tools.reader meta). Stored alongside the rich-type so
                  ;; type-error messages can point at the fn that introduced
                  ;; the offending constraint.
                  (:source-file fn-def)      (assoc :source-file
                                                    (:source-file fn-def))
                  (:source-line fn-def)      (assoc :source-line
                                                    (:source-line fn-def))
                  ;; `:tags` — set of declarative capability / shape markers
                  ;; on the fn-def. Policy callers (e.g. the admin-only-vault
                  ;; capability gate in `crud.secret-shape`) query by tag
                  ;; rather than hardcoding fn-name sets, so adding a new
                  ;; tagged base-fn is a one-line `fns.edn` annotation.
                  (seq (:tags fn-def))       (assoc :tags
                                                    (set (:tags fn-def)))
                  ;; `:branch-local?` — effective (monotonic OR) over
                  ;; own + every parent's stored effective. Topo-sort
                  ;; in sync means parents have been recorded before
                  ;; us, so we can read from the registry directly.
                  ;; ONLY stashed when true — false is the default
                  ;; everywhere else (resolve-version-from-cache,
                  ;; type-checker), so keeping the absent-key
                  ;; convention matches existing patterns and
                  ;; preserves identity assertions in tests.
                  (or (:branch-local? fn-def)
                      (some (fn [p]
                              (get-in (rich-types-view)
                                      [p :branch-local?]))
                            (or (seq (:parents fn-def))
                                (when (:parent fn-def)
                                  [(:parent fn-def)])
                                [])))
                  (assoc :branch-local? true)))]
    ;; `:effects` race resolution: the type-checker computes a fn's
    ;; full effect set (parent inheritance + own-declared) and writes
    ;; it via `record-rich-types-raw!`. Earlier in the same sync we
    ;; wrote a raw `record-rich-types!` entry with only the fn-def's
    ;; OWN-declared effects (often empty for composed fns that inherit
    ;; `:process` from `:future`). If a parallel bootstrap re-runs
    ;; `record-rich-types!` AFTER the type-check has populated
    ;; computed effects, this second raw write would erase them — any
    ;; service-eligibility assertion downstream then sees an empty
    ;; set. Preserve existing non-empty effects when the fn-def
    ;; declares none.
    (swap! (target-rich-types-atom)
           (fn [reg]
             (let [existing (get reg fn-name)
                   final-effects (if (and (empty? raw-effects)
                                          (seq (:effects existing)))
                                   (:effects existing)
                                   raw-effects)]
               (assoc reg fn-name (build final-effects)))))))


(defn effectful-rich-type?
  "True iff the entry's computed effect-set is non-empty. `:effects` is
   always present after record-rich-types! (a pure fn carries `#{}`),
   so `seq` distinguishes pure (`#{}` → falsy) from effectful (any set
   with members → truthy)."
  [info]
  (boolean (seq (:effects info))))


(defn record-rich-types-raw!
  "Stash a pre-computed `{:return … :args …}` map directly. Used by the
   type-checker for composed fn-defs whose computed types come from
   unification.

   Mirrors `record-rich-types!`'s P8 invariant — `:effects` is always
   present, defaulting to `#{}` (computed-pure) when the caller omits
   it. Keeps downstream consumers (`assemble-fn-type`, `effects-
   compatible?`) free of `(or … #{})` fallbacks for raw entries too.

   `:branch-local?` carry-over: when an earlier `record-rich-types!`
   pass stamped the flag, preserve it here so the unification-driven
   raw write doesn't drop the inheritance. Only stashed when true —
   matches the absent-key default everywhere else."
  [fn-name rich-type-map]
  (let [new-reg (swap! (target-rich-types-atom)
                       (fn [reg]
                         (let [existing (get reg fn-name)
                               carry-bl? (or (true? (:branch-local? rich-type-map))
                                             (true? (:branch-local? existing)))]
                           (assoc reg fn-name
                                  (cond-> (update rich-type-map :effects #(or % #{}))
                                    carry-bl? (assoc :branch-local? true))))))]
    ;; §4 Risk-2: mirror the entry into a TENANT's slice so its same-named
    ;; composed fn doesn't read another org's signature from the bare-name
    ;; global. Public / sync / compile writes are NOT mirrored (they own the
    ;; global), so the per-org slice never goes stale against record-rich-types!.
    (when-let [org (current-tenant-org)]
      (swap! (target-per-org-rich-atom) assoc-in [org fn-name] (get new-reg fn-name)))
    nil))


(defn rich-type-of
  ;; §4 Risk-2: a tenant prefers its OWN per-org entry; the compile / public
  ;; path (no tenant org bound) and single-tenant fall through to the global —
  ;; UNCHANGED behavior, plus tenant precedence. O(1) gets, no merge.
  ([fn-name]
   (or (when-let [org (current-tenant-org)]
         (get-in @(target-per-org-rich-atom) [org fn-name]))
       (get (rich-types-view) fn-name)))
  ([fn-name arg-name]
   (get-in (or (when-let [org (current-tenant-org)]
                 (get-in @(target-per-org-rich-atom) [org fn-name]))
               (get (rich-types-view) fn-name))
           [:args arg-name])))


(defn rich-types-snapshot
  []
  (rich-types-view))


(defn unregister-rich-type!
  "Drop the entry for `fn-name`. Counterpart to `record-rich-types!`
   on the delete side — without this the registry grows monotonically
   across the executor's lifetime as fn-defs are created and deleted
   (the entries are small, but tens of thousands of stale entries on
   a long-running prod executor add up to GC pressure).

   No-op if `fn-name` is absent — callers can invoke unconditionally
   on every delete without checking first.

   The thread-local override receives the dissoc when bound; otherwise
   the global atom does. Mirror of `record-rich-types!`'s
   `target-rich-types-atom` write target."
  [fn-name]
  (swap! (target-rich-types-atom) dissoc fn-name)
  (when-let [org (current-tenant-org)]
    (swap! (target-per-org-rich-atom) update org dissoc fn-name))
  nil)


(defn restore-rich-types!
  "Test-only: replace the `rich-types-registry` with `snapshot`. Pair
   with `rich-types-snapshot` to scope ad-hoc `record-rich-types(-raw)!`
   writes to one test:

     (let [snap (rich-types-snapshot)]
       (try
         (record-rich-types-raw! :fake {:args {} :return :int})
         …
         (finally (restore-rich-types! snap))))

   The `defonce` registry is process-global (its private state
   leaks across test ns'es by design — production sync paths
   accumulate fn-defs as they load), so any test that pollutes it
   with synthetic stubs must restore. The
   `execute-http-test` ClassCastException flake was a
   contaminator-leaks-into-compile-eager symptom — a foreign
   `:effects` / `:return` shape on one of the
   service/handler-internal fn names landed a builder that
   returned a closure where an Associative was expected."
  [snapshot]
  (reset! (target-rich-types-atom) (or snapshot {})))


(defn fn-names-with-tag
  "Return the set of fn-NAMES (keywords) declared with `tag` in their
   `:tags`. Used by policy callers (admin-only-vault gate) to discover
   tagged base-fns declaratively instead of hardcoding names."
  [tag]
  (->> (rich-types-view)
       (keep (fn [[fn-name info]]
               (when (contains? (:tags info) tag) fn-name)))
       set))


;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-fn-def!
  [fn-name fn-def]
  (when-not (keyword? fn-name)
    (throw (ex-info "fn-name must be a keyword"
                    {:type :invalid-fn-def
                     :fn-name fn-name})))
  ;; Type-rows (`:type {…}` / `:refine {…}` / `:list T`) carry their
  ;; role explicitly and don't need a separate `:return-type`.
  (when-not (or (:type fn-def) (:refine fn-def) (:list fn-def)
                (:union fn-def) (:variant fn-def))
    (when-not (:return-type fn-def)
      (throw (ex-info "Function definition must include :return-type"
                      {:type :invalid-fn-def
                       :fn-name fn-name})))
    (let [resolved-return (types/resolve-alias (:return-type fn-def))]
      (when-not (or (keyword? resolved-return)
                    (types/well-formed? resolved-return))
        (throw (ex-info (str "Unknown return type: " (pr-str (:return-type fn-def)))
                        {:type :invalid-return-type
                         :fn-name fn-name
                         :return-type (:return-type fn-def)})))))
  ;; Refinement-specific: catch nonsense like `{:base :text :constraint
  ;; [:>= 0]}` at sync time rather than letting it silently land in
  ;; storage and confuse downstream type-checking / runtime narrowing.
  (when-let [refine (:refine fn-def)]
    (let [base (:base refine)
          constraint (:constraint refine)
          check-fn (or (requiring-resolve
                         'graphden.types.check.literals/constraint-compatible-with-base?)
                       (throw (ex-info
                                "constraint-compatible-with-base? unresolved — namespace rename?"
                                {:type :sync/missing-symbol
                                 :symbol 'graphden.types.check.literals/constraint-compatible-with-base?})))]
      (when (and constraint
                 (not (check-fn base constraint)))
        (throw (ex-info (str "Refinement constraint " (pr-str constraint)
                             " uses operators not valid on base " (pr-str base))
                        {:type :invalid-refinement-constraint
                         :fn-name fn-name
                         :base base
                         :constraint constraint})))))
  (doseq [[arg-name arg-spec] (:args fn-def)]
    (arg-spec->rich-type arg-name arg-spec)))


(defn validate-all-defs!
  [defs]
  (doseq [[fn-name fn-def] defs]
    (validate-fn-def! fn-name fn-def)))


;; =============================================================================
;; Storage sync
;; =============================================================================

(defn- def-pair->fn-def-record
  "Convert one `[fn-name fn-def]` from the loader's map into a fn-def
   record the records-parser understands. Loader's normalize-args has
   already expanded args to `{arg-name {:type T :required B}}`."
  [[fn-name fd]]
  (assoc fd :name fn-name))


(defn sync-defs-to-storage!
  "Sync fn-defs to storage via the records-parser pipeline. Both
   base-fn defs ({fn-name → fn-def} from the loader) and type-rows
   declared inline within `fns.edn` flow through here. Idempotent —
   deterministic UUIDs from `records/fn-id`.

   Returns the fn-name→id map for the synced rows so the caller can
   pass it as `extra-name->id` to a downstream composed-fn sync.

   `extra-name->id` (optional) — names already known from prior syncs
   (or pre-computed from peer fn-defs). Used to resolve cross-module
   references like a base-fn's `:return-type` pointing at a type-row
   declared in another module."
  ([storage defs]
   (sync-defs-to-storage! storage defs {} {}))
  ([storage defs ns-id-map]
   (sync-defs-to-storage! storage defs ns-id-map {}))
  ([storage defs ns-id-map extra-name->id]
   (validate-all-defs! defs)
   (doseq [[fn-name fn-def] defs]
     (record-rich-types! fn-name fn-def))
   (let [fn-def-records (mapv def-pair->fn-def-record defs)
         records-list (records/parse-module fn-def-records extra-name->id)
         name->id (composition/write-records! storage records-list ns-id-map)]
     name->id)))


(defn sync-primitives!
  "Pre-seed the 14 primitive fn-rows. Should run once at storage init,
   before any base-fns or composed fn-defs sync. Idempotent."
  [storage]
  (composition/sync-primitives! storage))


;; =============================================================================
;; Re-exports kept for downstream compatibility
;; =============================================================================

(def type->storage-kind types/type->storage-kind)


(defn fn-uuid
  "Deterministic UUID for a globally-named fn — namespace-less. Tests
   call this; production paths use `records/fn-id` with the actual
   namespace path."
  [fn-name]
  (records/fn-id nil fn-name))
