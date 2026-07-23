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
    [clojure.tools.logging :as log]
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

;; Internal shape: ONE atom holding BOTH the id-keyed entries and the
;; name index — `{:by-id {fn-id → entry} :by-name {fn-name → fn-id}}`.
;; fn-id is the entity's IDENTITY (per-namespace names are only unique
;; within their namespace — a bare-name key silently clobbered
;; same-named fns across namespaces/orgs); the name index serves the
;; type-checker and public boundaries, whose native currency is the
;; authored name. One atom (not two) so the parallel-test isolation
;; override machinery (`*rich-types-override*` below) keeps working
;; unchanged on an opaque snapshot.
(defonce ^:private rich-types-registry (atom {:by-id {} :by-name {}}))


(defn- empty-registry
  []
  {:by-id {} :by-name {}})


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


;; §4 Risk-2 (rich-types): the NAME INDEX is still bare-name-keyed, so two
;; orgs' same-named composed fns collide there (last-write-wins on the
;; index; the id-keyed entries themselves never collide — org fns have
;; distinct ids). This per-org slice `{org → {:by-id … :by-name …}}` keeps
;; each org's own name resolution. Populated by `record-rich-types-raw!`
;; (the type-checker's tenant-fn writes) under the ambient org;
;; `rich-type-of` prefers it for a tenant and falls back to the
;; org-agnostic global — so the compile / public path is UNCHANGED (it never
;; has a tenant org bound), only tenant type-checks/reads gain precedence.
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


(defn- index-keys-for
  "The `:by-name` index keys one entry claims: the bare name always,
   plus the `:ns.path/name` qualified form when the namespace is
   known. Under per-ns duplicates the BARE key is last-write-wins and
   only qualified lookups are precise — ambiguous bare refs are
   rewritten to qualified form at parse entry, and the editor's crud
   path reconstructs qualified names, so precise resolution always
   has the qualified key available."
  [fn-name ns-path]
  (cond-> [fn-name]
    ns-path (conj (keyword ns-path (name fn-name)))))


(defn fn-def-registry-id
  "fn-id for a name-plus-def registry write: an explicit `:fn-id` on the
   def wins (the crud path threads the ROW id — editor fns have random
   ids); else the name index's existing id when the name is already
   registered (so a re-record — sync refresh, test stub — OVERWRITES the
   live entry instead of forking a second one under a derived id); else
   the deterministic `records/fn-id(namespace, name)` — the exact id the
   records parser mints for this def's storage row on the sync path."
  [fn-name fn-def]
  (or (:fn-id fn-def)
      (get-in (rich-types-view) [:by-name fn-name])
      (records/fn-id (:namespace fn-def) fn-name)))


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
   dispatching a multimethod on the fn name.

   Arities: the 2-arity derives the fn-id — reusing the name index's
   existing id when the name is already registered (test-stub overwrite
   + editor-fn re-record land on the row's id), else the deterministic
   `records/fn-id(namespace, name)` the sync path's storage rows use.
   Editor-created fns have RANDOM row ids — their write path threads
   the id explicitly via the 3-arity."
  ([fn-name fn-def]
   (record-rich-types! (fn-def-registry-id fn-name fn-def) fn-name fn-def))
  ([fn-id fn-name fn-def]
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
                   ;; `:taint-propagate?` — checker applies marker-taint
                   ;; propagation centrally on this base-fn's results.
                   (:taint-propagate? fn-def)  (assoc :taint-propagate? true)
                   ;; `:lazy-seq-args` — slot names where each ITEM in the
                   ;; seq slot's list arrives as a `delay`, so a consumer
                   ;; like `:cond` can step past an unforced item.
                   ;;
                   ;; Scalar lazy slots DON'T need a marker — every `:ref`
                   ;; binding compiles to a delay by default; Clojure's
                   ;; native `if`/`and`/`or` short-circuit on un-read args.
                   (:lazy-seq-args fn-def)    (assoc :lazy-seq-args
                                                     (:lazy-seq-args fn-def))
                   ;; `:lambda-params` — the fn-def's EXPLICIT ordered
                   ;; call-site parameter list for HOF use. Overrides the
                   ;; wrap-arity inference in
                   ;; `compile.renames/hof-lambda-params`; `[]` means
                   ;; "everything captured" (a handler chain that takes
                   ;; no per-call value).
                   (contains? fn-def :lambda-params)
                   (assoc :lambda-params (vec (:lambda-params fn-def)))
                   ;; `:compile-time-value?` — base-fn is evaluated ONCE at
                   ;; compile time and its result baked as `(constantly …)`
                   ;; into the closure (compile_eager reads this via the
                   ;; root base-fn's rich-type). Backs `:cell`'s persistent
                   ;; atom.
                   (:compile-time-value? fn-def) (assoc :compile-time-value? true)
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
                               (let [v (rich-types-view)]
                                 (get-in v [:by-id (get-in v [:by-name p])
                                            :branch-local?])))
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
              (let [existing (get-in reg [:by-id fn-id])
                    final-effects (if (and (empty? raw-effects)
                                           (seq (:effects existing)))
                                    (:effects existing)
                                    raw-effects)]
                (reduce (fn [r k] (assoc-in r [:by-name k] fn-id))
                        (assoc-in reg [:by-id fn-id]
                                  (cond-> (assoc (build final-effects)
                                                 :fn-id fn-id :name fn-name)
                                    (:namespace fn-def)
                                    (assoc :namespace (:namespace fn-def))))
                        (index-keys-for fn-name (:namespace fn-def)))))))))


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
   matches the absent-key default everywhere else.

   Arities mirror `record-rich-types!` — the 2-arity reuses the name
   index's existing id or derives the namespace-less deterministic id
   (test stubs); production callers holding a row thread the id."
  ([fn-name rich-type-map]
   (record-rich-types-raw! (fn-def-registry-id fn-name rich-type-map)
                           fn-name rich-type-map))
  ([fn-id fn-name rich-type-map]
   (let [new-reg (swap! (target-rich-types-atom)
                        (fn [reg]
                          (let [existing (get-in reg [:by-id fn-id])
                                carry-bl? (or (true? (:branch-local? rich-type-map))
                                              (true? (:branch-local? existing)))]
                            (reduce (fn [r k] (assoc-in r [:by-name k] fn-id))
                                    (assoc-in reg [:by-id fn-id]
                                              (cond-> (-> rich-type-map
                                                          (update :effects #(or % #{}))
                                                          (assoc :fn-id fn-id :name fn-name))
                                                carry-bl? (assoc :branch-local? true)))
                                    (index-keys-for fn-name (:namespace rich-type-map))))))]
     ;; §4 Risk-2: mirror the entry into a TENANT's slice so its same-named
     ;; composed fn doesn't read another org's signature from the bare-name
     ;; global name index. Public / sync / compile writes are NOT mirrored
     ;; (they own the global), so the per-org slice never goes stale against
     ;; record-rich-types!.
     (when-let [org (current-tenant-org)]
       (swap! (target-per-org-rich-atom)
              (fn [m]
                (-> m
                    (assoc-in [org :by-id fn-id] (get-in new-reg [:by-id fn-id]))
                    (assoc-in [org :by-name fn-name] fn-id)))))
     nil)))


(defn rich-type-of-id
  "Registry entry by the fn's IDENTITY — the primary lookup for every
   caller that holds a row / fn-map entry (executor compile, persist
   redaction, crud validation). Never ambiguous: per-namespace names may
   repeat, ids can't. Tenant precedence mirrors `rich-type-of`."
  ([fn-id]
   (or (when-let [org (current-tenant-org)]
         (get-in @(target-per-org-rich-atom) [org :by-id fn-id]))
       (get-in (rich-types-view) [:by-id fn-id])))
  ([fn-id arg-name]
   (get-in (rich-type-of-id fn-id) [:args arg-name])))


(defn rich-type-of
  ;; §4 Risk-2: a tenant prefers its OWN per-org entry; the compile / public
  ;; path (no tenant org bound) and single-tenant fall through to the global —
  ;; UNCHANGED behavior, plus tenant precedence. O(1) gets, no merge.
  ;;
  ;; NAME-keyed convenience over the name index — the type-checker's and
  ;; public boundaries' native currency is the authored name. Resolution
  ;; goes name → id → entry; callers that already hold the id should use
  ;; `rich-type-of-id` directly.
  ([fn-name]
   (or (when-let [org (current-tenant-org)]
         (let [slice (get @(target-per-org-rich-atom) org)]
           (get-in slice [:by-id (get-in slice [:by-name fn-name])])))
       (let [v (rich-types-view)]
         (get-in v [:by-id (get-in v [:by-name fn-name])]))))
  ([fn-name arg-name]
   (get-in (rich-type-of fn-name) [:args arg-name])))


(defn root-base-fn-name
  "Walk a fn's `:primary-parent` chain to the base-fn at the root of its
   inheritance. Lets a rule/narrowing dispatch on the BASE fn's name even
   when the immediate parent is a composed fn-def (e.g. `:_jvm-section
   :parent :assoc-empty` still benefits from the `:assoc` rule). `seen`
   guards an unexpected cycle (registration-order bugs, manual rich-type
   tampering). Returns the root name, or `nil`/the input unchanged when
   the ref is unknown or already a root.

   Single source of truth for the three former copies (types.check,
   types.check.narrowing, core/logic/impls) — it lives here because the
   walk needs only `rich-type-of`, so every caller can reach it without a
   type-checker dependency (which would cycle: types.check → the rule)."
  [fn-name]
  (loop [cur fn-name, seen #{}]
    (if (or (nil? cur) (contains? seen cur))
      cur
      (if-let [parent (:primary-parent (rich-type-of cur))]
        (recur parent (conj seen cur))
        cur))))


(defn- signature-owner?
  "Does this registry entry narrow returns via its DECLARED signature —
   i.e. the return type carries a type variable the checker's
   `signature-return` fallback resolves from actuals? (`[:list a]`,
   `a`, `[:union a b]` …)."
  [entry]
  (boolean (types/type-any? types/type-var? (:return entry))))


(def ^:private stale-id-rescue-warned (atom #{}))


(defn rich-type-of-id-or-stale-name
  "Registry entry by ID, with the STALE-IDENTITY rescue: long-lived
   DBs hold fn identity rows abandoned by historical namespace moves /
   renames (a new deterministic id is minted; the old row and any
   resolved refs to it survive un-tombstoned). Such an id has NO
   registry entry — but its NAME, when unambiguous, identifies the
   CURRENT fn carrying the same authored contract. Falls back to the
   name view (nil for per-ns-ambiguous bares), warn-once per id with
   the repoint-or-tombstone prescription. Same rescue the
   lambda-params reader uses; shared here so every silent consumer
   (produces-callable? / lazy-seq-args / compile-time-value?) degrades
   LOUDLY-and-correctly instead of silently-and-wrongly."
  [fn-id row-name]
  (or (rich-type-of-id fn-id)
      (when row-name
        (when-let [entry (rich-type-of (keyword row-name))]
          (when-not (contains? @stale-id-rescue-warned fn-id)
            (swap! stale-id-rescue-warned conj fn-id)
            (log/warn "rich-type resolved by NAME for a stale identity row — repoint or tombstone the legacy fn row"
                      {:fn-id fn-id :name row-name}))
          entry))))


(defn rule-owner-of
  "Name (string, no leading colon) of the base-fn that computed
   `fn-name`'s return type — `root-base-fn-name` over the entry's
   `:primary-parent`, iff that root's entry carries a hand
   `:return-type-rule` OR a var-carrying declared signature (the
   checker's `signature-return` fallback). nil for unknown names,
   base-fns (no `:primary-parent`), and chains whose root has neither
   (a fully concrete declaration — nothing to explain). Rules and
   signatures live only on base-fns (the roots), so \"first ancestor
   with a rule\" ≡ \"root with a rule\". Consumers: the
   `:rule-owner-of-name` base-fn behind `/partials/return-type-rule`,
   and the layout strip-facts pass that tells the editor whether to
   render the `↳` badge."
  [fn-name]
  (when fn-name
    (when-let [pp (:primary-parent (rich-type-of (keyword fn-name)))]
      (let [root (root-base-fn-name pp)
            entry (rich-type-of root)]
        (when (or (:return-type-rule entry) (signature-owner? entry))
          (name root))))))


;; Memoized per registry-value identity: the types_api layer caches
;; derived structures keyed on the snapshot's IDENTITY, so the view must
;; be a stable object between registry writes (rebuilding per call would
;; silently disable that cache).
(def ^:private name-view-cache (atom nil))


(defn rich-types-snapshot
  "NAME-keyed view `{fn-name → entry}` of the registry — the public
   read surface for iterating consumers (types API, sidebar roles,
   always-fresh scan). Entries carry `:fn-id` and `:name`. Built from
   the id-keyed truth via the name index; memoized per registry value."
  []
  (let [reg (rich-types-view)
        [cached-reg cached-view] @name-view-cache]
    (if (identical? cached-reg reg)
      cached-view
      (let [entries (vals (:by-id reg))
            bare-counts (frequencies (keep :name entries))
            view (into {}
                       (keep (fn [e]
                               (when-let [nm (:name e)]
                                 (if (> (get bare-counts nm 0) 1)
                                   ;; per-ns duplicate — only the
                                   ;; qualified key is unambiguous;
                                   ;; entries without a known ns keep
                                   ;; the bare key (last-write).
                                   (if-let [ns-path (:namespace e)]
                                     [(keyword ns-path (name nm)) e]
                                     [nm e])
                                   [nm e]))))
                       entries)]
        (reset! name-view-cache [reg view])
        view))))


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
  (let [drop-entry (fn [reg]
                     (let [id (get-in reg [:by-name fn-name])
                           id (when-not (= id :graphden.packages.records.types/ambiguous)
                                id)
                           entry (when id (get-in reg [:by-id id]))]
                       (reduce (fn [r k] (update r :by-name dissoc k))
                               (update reg :by-id dissoc id)
                               (if entry
                                 (index-keys-for (:name entry) (:namespace entry))
                                 [fn-name]))))]
    (swap! (target-rich-types-atom) drop-entry)
    (when-let [org (current-tenant-org)]
      (swap! (target-per-org-rich-atom) update org
             (fn [slice] (when slice (drop-entry slice))))))
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
   returned a closure where an Associative was expected.

   Accepts either the internal blob (from `snapshot-for-isolation`) or
   the NAME-keyed view (from `rich-types-snapshot`) — the latter is
   rebuilt into the blob via each entry's `:fn-id`."
  [snapshot]
  (reset! (target-rich-types-atom)
          (cond
            (nil? snapshot) (empty-registry)
            (contains? snapshot :by-id) snapshot
            :else (reduce-kv (fn [reg nm entry]
                               (let [id (or (:fn-id entry)
                                            (records/fn-id nil nm))]
                                 (-> reg
                                     (assoc-in [:by-id id]
                                               (assoc entry :fn-id id :name nm))
                                     (assoc-in [:by-name nm] id))))
                             (empty-registry)
                             snapshot))))


(defn fn-names-with-tag
  "Return the set of fn-NAMES (keywords) declared with `tag` in their
   `:tags`. Used by policy callers (admin-only-vault gate) to discover
   tagged base-fns declaratively instead of hardcoding names."
  [tag]
  (->> (vals (:by-id (rich-types-view)))
       (keep (fn [info]
               (when (contains? (:tags info) tag) (:name info))))
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
                (:union fn-def) (:variant fn-def) (:marker fn-def))
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
