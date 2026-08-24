(ns graphden.executor.test-setup
  "Shared test setup for executor tests in the slot/fn-slot/binding model.

   Helpers create fn rows, slot rows, fn-slot junctions, and binding
   rows directly via the storage protocol. Higher-level helpers like
   `setup-add-function!` synthesise a small example graph end-to-end."
  (:require
    [cheshire.core :as cheshire]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.runtime :as rt]
    [graphden.packages.loader :as loader]
    [graphden.packages.records :as records]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.schemas :as schemas]
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.test-infra.shared-container :as sc]
    [graphden.types.check :as types-check]
    [graphden.versioning.storage.core :as vs])
  (:import
    (java.io
      File)))


;; ============================================================================
;; Impl helper — inline `defbase`-style for test registration
;; ============================================================================

(defmacro fn-impl
  "Build an anonymous base-fn impl whose body references args by name,
   mirroring `defbase` but inline. The symbols `args` and `ctx` are
   bound by the generated fn so HOF impls and lazy impls can reach
   them."
  [arg-syms & body]
  (let [let-bindings (mapcat (fn [s] [s `(rt/resolve-arg ~'args ~(keyword s))]) arg-syms)]
    `(fn [~'args ~'ctx]
       (let [~'ctx ~'ctx
             ~'args ~'args
             ~@let-bindings]
         ~@body))))


;; ============================================================================
;; Container management
;; ============================================================================

(def ^:dynamic *container*
  nil)


(defn create-container-fixture
  []
  (pth/create-container-fixture #'*container*))


(defn create-clean-db-fixture
  []
  (pth/create-clean-db-fixture #'*container*))


(declare full-schema)


(defn create-test-storage
  []
  (pth/clean-database-fast! *container*)
  (let [storage (sc/register-storage!
                  (pg/create-storage (pth/get-container-config *container*)))
        ;; The FULL prod schema (graph + versioned + executions + services +
        ;; packages), not graph-only — so every entity's table exists and
        ;; tenancy scoping tests can exercise any scoped entity
        ;; (`:branch` / `:service` / `:package-install`), not just `:fn`/`:ns`.
        schema (full-schema)]
    (sp/initialize storage schema)
    ;; Pre-seed the 14 primitive fn-rows so slot.type-fn-id refs resolve.
    ;; `boot-primitive-records` returns tagged records (`:kind :fn`); strip
    ;; the tag before storage upsert.
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    storage))


(defn- full-schema
  "Schema covering the executor minimum (graph + traits) plus
   versioned + executions + services + packages. Same combination the
   production storage init runs through; tests touching `:branch` /
   `:execution` / `:service` rows need every layer. Delegates to the
   shared `test-infra.schemas` builder."
  []
  (schemas/full-schema {:packages? true}))


(defn create-versioned-test-storage
  "Like `create-test-storage` but initialises the full schema and
   wraps the base storage with `VersionedStorage` on the `main`
   branch — the same shape production runs under. Tests that drive
   branches / executions / services CRUD need this; pure-fn-graph
   tests should stay on `create-test-storage` (lighter).

   `pool-size` (optional) overrides the default test pool (2) for ONE
   storage. A branch-churning ns (create/policy/propose/merge — each
   merge spawns a post-commit thread and can trigger a graph-epoch-heal,
   all contending the same pool) intermittently gets a borrowed
   connection broken under that contention on a size-2 pool
   (`HikariPool marked broken / Socket closed`, a non-deterministic
   slice of the run erroring). A per-ns bump of a few connections costs
   negligibly against a single storage's footprint and removes the
   contention; the suite-wide default stays 2."
  ([] (create-versioned-test-storage nil))
  ([pool-size]
   (pth/clean-database-fast! *container*)
   (let [cfg (cond-> (pth/get-container-config *container*)
               pool-size (assoc :pool-size pool-size))
         storage (sc/register-storage! (pg/create-storage cfg))]
     (sp/initialize storage (full-schema))
     (sp/upsert-entities storage :fn
                         (mapv #(dissoc % :kind) (records/boot-primitive-records)))
     (vs/wrap-with-versioning storage "main"))))


(defn create-branch-versioned-test-storage
  "Full NON-packages schema + primitives, versioned on a FRESH explicit
   branch. Differs from `create-versioned-test-storage` on two axes its
   two consumers rely on: the branch is a just-created row (not the
   resolved \"main\"), and the packages schema is absent. Was duplicated
   verbatim in closure-capture-test and reconciler-test."
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))]
    (sp/initialize storage (schemas/full-schema))
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (let [branch (sp/create-entity storage :branch
                                   {:name "test-branch"
                                    :created-at (java.time.Instant/now)})]
      (vs/->VersionedStorage storage (:id branch)))))


(defn default-registry-ctx
  "ExecutionContext over `storage` with the full default base-fn
   registry — the common test-ctx shape (was triplicated)."
  [storage]
  (exec-ctx/create-context {:storage storage
                            :base-fns (exec/get-default-registry)}))


;; ============================================================================
;; Test helpers — slot/fn-slot/binding model
;; ============================================================================

(def primitive-fn-ids (records/primitive-fn-ids))


(defn create-base-fn!
  "Creates a base-fn row (return-type-fn-id set — THE base-fn marker —
   no parent-ids). Defaults the return type to the `:any` primitive
   when none is given. Returns the created fn record."
  ([storage fn-name]
   (create-base-fn! storage fn-name nil))
  ([storage fn-name return-type-keyword]
   (sp/create-entity storage :fn
                     ;; Deterministic id (same nil-ns derivation the
                     ;; registry's name-keyed test writes use) so a
                     ;; rich-types stub recorded by NAME and this row
                     ;; agree on the fn's IDENTITY — the id-keyed
                     ;; registry reads (produces-callable?,
                     ;; lazy-seq-args, compile-time-value?, redaction)
                     ;; resolve test fns exactly like synced ones.
                     {:id (records/fn-id nil fn-name)
                      :name fn-name
                      :parent-ids nil
                      :return-type-fn-id (get primitive-fn-ids
                                              (or return-type-keyword :any))})))


(defn create-composed-fn!
  "Creates a composed fn-row inheriting from `parent-id`. Deterministic
   id — see `create-base-fn!`."
  [storage fn-name parent-id]
  (sp/create-entity storage :fn
                    {:id (records/fn-id nil fn-name)
                     :name fn-name
                     :parent-ids [parent-id]}))


(defn- ensure-structural-fn-row!
  "For a structural `[:fn {ARGS} RET]` type form, returns the
   deterministic anonymous fn-id, creating the fn-row (with the
   constraint set) if it doesn't already exist. Mirrors the
   production path in `records.types/inline-fn-type-rows-from-form`
   so test-synthesized HOF slots get the same anon fn-row shape
   production sync would emit."
  [storage shape]
  (let [h (ids/digest-hex "SHA-1" (pr-str shape))
        id (ids/anonymous-fn-id h)]
    ;; Idempotent: only create if absent. Some tests reuse the same
    ;; shape across multiple slots — they'd collide otherwise.
    (when-not (seq (sp/query-entities storage :fn {:id id}))
      (sp/create-entity storage :fn
                        {:id id
                         :name nil
                         :parent-ids nil
                         :anonymous-hash h
                         :constraint shape}))
    id))


(defn create-slot!
  "Creates a slot whose type points at a primitive (by keyword), an
   explicit fn-id (UUID), or a structural type vector. Vector types
   `[:fn {ARGS} RET]` materialise (idempotently) an anonymous fn-row
   carrying the constraint, mirroring the production sync path."
  [storage slot-name type-ref]
  (let [type-fn-id (cond
                     (uuid? type-ref) type-ref
                     (keyword? type-ref) (or (get primitive-fn-ids type-ref) type-ref)
                     (and (vector? type-ref) (= :fn (first type-ref)))
                     (ensure-structural-fn-row! storage type-ref)
                     :else type-ref)]
    (sp/create-entity storage :slot
                      {:name slot-name
                       :type-fn-id type-fn-id})))


(defn attach-slot!
  "Inserts a fn-slot junction at the given position."
  [storage fn-id slot-id position]
  (sp/create-entity storage :fn-slot
                    {:fn-id fn-id
                     :slot-id slot-id
                     :position position}))


(defn bind-value!
  "Creates a value-binding for `slot-id` on `fn-id`. Mirrors the
   parser's value-presence contract: `:value-present true` so
   `compile/bindings/value-binding?` classifies the row as `:value`,
   even when `value` is `nil`."
  [storage fn-id slot-id value]
  (sp/create-entity storage :binding
                    {:fn-id fn-id
                     :slot-id slot-id
                     :value value
                     :value-present true}))


(defn bind-ref!
  "Creates a ref-binding for `slot-id` on `fn-id` pointing at
   `target-fn-id`."
  [storage fn-id slot-id target-fn-id]
  (sp/create-entity storage :binding
                    {:fn-id fn-id
                     :slot-id slot-id
                     :ref-fn-id target-fn-id}))


(defn create-arg!
  "Compatibility helper that bridges legacy `arg`-table call sites.
   Two flavours:

   1. Primary-arg form (no `:source-id`): create a slot owned by
      `fn-id` and attach it via fn-slot. Returns the slot record.

   2. Inherited-arg form (`:source-id` set): treat `fn-id` as a
      composed fn that wants to bind the slot named by `:source-id`'s
      legacy id. Since slot-ids are deterministic from `(parent-fn-id,
      slot-name)`, we recover the slot-id from the source-arg's
      `:fn-id` + `:name` and emit a binding row. `:value` / `:ref-id`
      determine which kind of binding."
  ([storage fn-id opts]
   (create-arg! storage fn-id opts 0))
  ([storage fn-id
    {arg-name :name arg-type :type
     :keys [source-id value ref-id]} position]
   (cond
     ;; Inherited-with-binding form. The caller passes a slot record
     ;; (or its id) as `:source-id`. We recover the slot-id from the
     ;; record and add the corresponding binding row.
     source-id
     (let [;; Look up the source slot by id. The `:source-id` parameter
           ;; in legacy tests was an arg-row id; in the new model
           ;; `setup-add-function!` returns slot records as `:slot-a`/
           ;; `:slot-b`, so source-id IS the slot-id directly.
           slot-id source-id]
       (cond
         (some? value) (bind-value! storage fn-id slot-id value)
         (some? ref-id) (bind-ref! storage fn-id slot-id ref-id)
         :else nil))

     :else
     (let [slot (create-slot! storage arg-name arg-type)]
       (attach-slot! storage fn-id (:id slot) position)
       slot))))


(defn build-fn!
  "Higher-level fixture builder — collapses the
   create-base-fn! / create-slot! / attach-slot! / create-composed-fn! /
   bind-value!/bind-ref! sequence into one call.

   Two shapes:

   1. Base-fn:
        (build-fn! storage {:name \"add\" :return-type :int
                             :slots [{:name \"a\" :type :int}
                                     {:name \"b\" :type :int}]})
      → {:fn <fn-record>
         :slots {\"a\" <slot-record> \"b\" <slot-record>}}

   2. Composed-fn (over a previously built parent map):
        (build-fn! storage {:name \"my-add\"
                             :parent parent
                             :bindings {\"a\" {:value 1}
                                        \"b\" {:value 2}}})
      → {:fn <fn-record>}

   `:parent` is the map returned by a prior `build-fn!` call —
   `bindings` keys are slot-NAMES resolved against `parent`'s slots map.
   `:value V` or `:ref R` (R may be a UUID, fn record, or
   `build-fn!` result map) are the supported binding shapes.

   Callers that need lower-level control still use the granular
   helpers below."
  [storage {fn-name :name :keys [return-type slots parent bindings]}]
  (cond
    parent
    (let [parent-id (or (some-> parent :fn :id) (:id parent))
          parent-slots (or (:slots parent) {})
          fn-rec (create-composed-fn! storage fn-name parent-id)]
      (doseq [[slot-key b] (or bindings {})]
        (let [slot-rec (get parent-slots slot-key)
              slot-id (or (some-> slot-rec :id)
                          (throw (ex-info (str "build-fn!: unknown slot "
                                               (pr-str slot-key))
                                          {:parent-slots (keys parent-slots)
                                           :requested slot-key})))]
          (cond
            (contains? b :value)
            (bind-value! storage (:id fn-rec) slot-id (:value b))

            (contains? b :ref)
            (let [r (:ref b)
                  ref-id (cond
                           (uuid? r) r
                           (some-> r :fn :id) (-> r :fn :id)
                           (some-> r :id) (:id r)
                           :else (throw (ex-info "build-fn!: bad :ref"
                                                 {:ref r})))]
              (bind-ref! storage (:id fn-rec) slot-id ref-id)))))
      {:fn fn-rec})

    :else
    (let [fn-rec (create-base-fn! storage fn-name return-type)
          slots-map (into {}
                          (map-indexed
                            (fn [idx {sn :name st :type}]
                              (let [slot (create-slot! storage sn st)]
                                (attach-slot! storage (:id fn-rec) (:id slot) idx)
                                [sn slot])))
                          (or slots []))]
      {:fn fn-rec :slots slots-map})))


;; ============================================================================
;; Graph-handler bootstrap — for tests that exercise `/api/*`-shaped
;; graph fn-defs (`:process-create-entity` &c.) via the executor.
;;
;; `bootstrap-crud-graph!` runs the production package init chain
;; against a fresh test storage and returns `{:storage :ctx
;; :all-name->id}`. Use from a `:once` fixture so the heavy
;; load+sync cost (~10s) is paid once per test JVM, NOT per deftest.
;;
;; `via-graph` is the test-side counterpart of the Ring handler — it
;; calls `exec/execute-with-named-args` with the request bundled
;; into `{:request <map>}`, the same path the production
;; `:create-entity-handler` Ring handler reaches through its wrap chain.
;; ============================================================================

(defn bootstrap-crud-graph!
  "Run the full production package bootstrap (sync namespaces, register
   base-fn impls, sync base-fn rows, sync fn-defs, snapshot rich-types,
   topological type-check, pre-compile registry) against `storage`.
   Defaults to the full `[\"core\" \"web\" \"app\"]` package set so
   `:process-create-entity` resolves all transitive parents (incl.
   `:assoc-empty` from `app`). The `(cr/rebuild!)` pre-compile step
   matches production's `:exec/compiled-registry` init-key — without
   it, `cr/execute` falls back to on-the-fly compile which may exhibit
   different HOF lambda-param wrapping than the production path.

   Returns `{:storage :ctx :all-name->id :base-fns :fn-rows
   :ns-id-map}`. Heavy (~10s) — call once per test ns from a `:once`
   fixture, share across deftests via a delay/atom."
  ([storage]
   (bootstrap-crud-graph! storage ["core" "web" "app"]))
  ([storage package-names]
   ;; Tests skip the topological type-check sweep (~15 s saved per
   ;; bootstrap). The sweep populates the editor's `:effects` strip
   ;; + computed return-types; tests that exercise the type-API
   ;; endpoints need to run it explicitly. Runtime behaviour
   ;; (compile, execute, lazy semantics, branch routing) doesn't
   ;; depend on the sweep — `record-rich-types!`'s seed pass is
   ;; enough for compile-eager's `ref-produces-callable?`.
   (let [bootstrap (pkg-sync/bootstrap-from-packages! storage package-names
                                                      {:skip-type-check? true})
         ctx (exec/create-context {:storage storage})]
     (cr/rebuild! ctx)
     (assoc bootstrap :storage storage :ctx ctx))))


(defn bootstrap-crud-graph-from-golden!*
  "Implementation of `bootstrap-crud-graph-from-golden!` — call the macro
   instead unless you have a namespace identity to pass explicitly.

   Fast bootstrap via the shared golden DB + `CREATE DATABASE …
   TEMPLATE` clone. ~10× faster than `bootstrap-crud-graph!`: the
   first NS in the JVM pays the ~14 s golden bootstrap, every
   sibling pays only the ~100 ms file-clone + ~1 s ctx rebuild.

   The clone inherits the golden's schema, primitive fn-rows, and
   all synced fn-def rows — no per-NS `sp/initialize`, no per-NS
   `upsert primitives`, no per-NS `bootstrap-from-packages!`. Global
   base-fn registry state was populated during the golden bootstrap
   and is visible process-wide.

   `ns-ident` (defaults to `*ns*`) determines the per-NS DB name on
   the shared cluster. Different NSes get different DBs, so
   parallel runs don't trample each other's CRUD writes. Returns
   the same `{:storage :ctx :all-name->id :base-fns :fn-rows
   :ns-id-map}` shape as `bootstrap-crud-graph!`.

   Use when:
     - Test set uses the default `[\"core\" \"web\" \"app\"]` bundle
       (or any single bundle reused across sibling NSes).
     - The test doesn't `register-base-fn!` outside a
       `with-clean-registry` override (golden's globally-registered
       impls stay reachable through override fallthrough)."
  ([ns-ident]
   (bootstrap-crud-graph-from-golden!* ns-ident ["core" "web" "app"]))
  ([ns-ident package-names]
   (let [{:keys [db-config bootstrap]}
         (sb/ensure-ns-database-from-golden! ns-ident package-names)
         storage (sc/register-storage! (pg/create-storage db-config))
         versioned (vs/wrap-with-versioning storage "main")
         ctx (exec/create-context {:storage versioned})]
     (cr/rebuild! ctx)
     (assoc bootstrap :storage versioned :ctx ctx))))


(defn ensure-build-hashes-fixture
  "`:once` fixture: guarantee `resources/graphden-build-hashes.json` exists.

   Any namespace that renders a page through `:build-hashes` needs it. It is
   gitignored — a fresh checkout and every CI node start without it — and only
   `clojure -T:build` writes the real one, so a test run has to provide a
   placeholder.

   It lives here because it is a PRECONDITION, and a precondition every test
   that needs it must state for itself. It used to be a private helper in
   `regressions.inline-script-sibling-drop-test`, and `packages.app.page-test`
   read the file that fixture happened to leave behind — so page-test passed or
   failed on whether an unrelated namespace had run first. kaocha randomises, so
   that was a coin flip, and it came up tails in a landing gate:

     ERROR in graphden.packages.app.page-test/…-stylesheet-link-test
     Exception: Resource not found: graphden-build-hashes.json

   Reproduced exactly by deleting the file and running page-test alone.

   Only writes when missing, so a real `clojure -T:build` during dev is never
   clobbered."
  [f]
  (let [target (io/file "resources/graphden-build-hashes.json")
        hash64 (str/join (repeat 64 \0))]
    (when-not (File/.exists target)
      (spit target (cheshire/generate-string {"frontend" hash64
                                              "packages" hash64
                                              "backend" hash64}))))
  (f))


(defmacro bootstrap-crud-graph-from-golden!
  "Fast bootstrap via the golden DB + TEMPLATE clone — see
   `bootstrap-crud-graph-from-golden!*`.

   A MACRO, and it has to be, because the namespace identity that names the
   per-NS database must be captured while the TEST FILE is being compiled.

   The 0-arity used to read `(ns-name *ns*)` at call time, and every call site
   is inside a `(fn [t] …)` that kaocha invokes on a worker thread. kaocha binds
   `*ns*` nowhere, so that read returned the thread's root value. Measured:

       at-load        = graphden.scratch.ns-probe-test
       at-fixture-run = user

   All 26 call sites therefore asked for a database called `user` — and
   `ensure-ns-database!`'s idempotency guard skipped the CREATE for all but the
   first, so 26 namespaces silently SHARED ONE DATABASE while believing they
   each had their own. That is why `:fixture/ns-db-clone` read 1 for the unit
   suite and 2 for integration (the second being `swept-rich-types-capture`,
   which passes its identity explicitly and so was never affected).

   Two costs, and the second is the serious one:
     - performance: sister namespaces dogpiled one cold compile (fixed
       independently in `compile-eager/compile-all`);
     - correctness: `registry-test` writes fn rows that `export-test` reads,
       under 8-way parallelism, in randomised order. A latent race held off
       only by luck.

   Expanding `*ns*` here fixes every call site without touching one, and makes
   the trap unrepeatable: there is no longer a run-time `*ns*` read to get wrong.
   The same trap is documented next door — see `shared-container-fixture` in
   `graphden.test-infra.shared-container`, which captures at `use-fixtures` time
   for exactly this reason."
  ([] `(bootstrap-crud-graph-from-golden!* ~(str (ns-name *ns*))
                                           ["core" "web" "app"]))
  ([ns-ident] `(bootstrap-crud-graph-from-golden!* ~ns-ident ["core" "web" "app"]))
  ([ns-ident package-names]
   `(bootstrap-crud-graph-from-golden!* ~ns-ident ~package-names)))


(defn sync-and-invalidate!
  "Sync `fn-defs` to `storage`, then DELTA-invalidate `ctx` on JUST those fns
   (ids looked up by name) rather than a full 1-arity
   `invalidate-graph-cache!`. A full clear drops the whole compiled registry,
   so the next `execute` recompiles all ~2600 golden [core web app] fns (~30 s);
   the delta path recompiles only the synced fns + their dependents (the same
   mechanism the editor CRUD path uses). Drop-in for the common test pattern
   `(sync-fns-to-storage! storage defs)` + `(invalidate-graph-cache! ctx)`."
  [ctx storage fn-defs]
  ;; Sync any NEW namespaces the fn-defs declare first (mirrors
  ;; `materialize-fns!`) — without this, a fn-def carrying a `:namespace` the
  ;; graph hasn't seen lands with a nil namespace-id. Fns without `:namespace`
  ;; contribute nothing, so this is a no-op for the common case.
  (let [ns-id-map (loader/sync-namespaces! storage (into #{} (keep :namespace) fn-defs))
        name->id  (fn-composition/sync-fns-to-storage! storage fn-defs ns-id-map)]
    ;; Record rich-types for the synced defs — mirrors `packages.sync`'s
    ;; seed pass + type-check sweep. Without it a declared `:return-type`
    ;; / `:effects` on a test-synced fn-def is invisible to every id-keyed
    ;; consumer (`:fn-return-type`, redaction, compile-time-value?),
    ;; because the branch-router ctx-rebuild that records editor-authored
    ;; fns in prod doesn't run under the test harness. The seed pass
    ;; covers base-fn-style defs; `check-fn-def!` records COMPOSED defs
    ;; (computed return via `record-result!`). Callers pass defs
    ;; dependencies-first, same as the fixture's own topo order; failures
    ;; are non-fatal exactly like the prod sweep's fault-tolerant loop.
    (binding [types-check/*ref-return-memo* (atom {})]
      (doseq [fd fn-defs]
        (when-let [fn-name (:name fd)]
          (try (registry-core/record-rich-types! fn-name fd)
               (catch Exception _
                 ;; Composed defs carry BINDINGS in :args ({:value …}
                 ;; maps), which the base-fn-style arg validation
                 ;; rejects — re-record without them so a declared
                 ;; :return-type / :effects still lands (prod's full
                 ;; sweep computes these transitively; the harness
                 ;; takes the declaration).
                 (try (registry-core/record-rich-types! fn-name (dissoc fd :args))
                      (catch Exception _ nil))))
          (try (types-check/check-fn-def! fd)
               (catch Exception _ nil)))))
    ;; Invalidate on the ids the sync ACTUALLY wrote — including anonymous
    ;; inline-arg children (`_anon-*`), which a lookup by the DECLARED names
    ;; misses; an un-recompiled anon child throws `:fn-not-found` at its
    ;; parent's first execute.
    (exec-ctx/invalidate-graph-cache! ctx (vec (vals name->id)))))


(defn inject-storage-query
  "Add `:storage-query` to `args` if `fn-id`'s free-arg set declares it.

   Production binds `:storage-query → :pg-query` at `:web-server`.
   Tests that call `exec/execute` directly (without going through the
   web-server / `via-graph`) must satisfy the same propagated free
   arg. Resolve `:pg-query` via `sp/query-entities` against `storage`,
   wrap in a callable via `make-single-arg-callable`, merge into
   `args`. No-op if `fn-id` doesn't propagate `:storage-query`."
  [ctx storage fn-id args]
  (let [valid-args (set (cr/free-arg-ext-names ctx fn-id))]
    (cond-> args
      (contains? valid-args :storage-query)
      (assoc :storage-query
             (let [pg-id (:id (first (sp/query-entities storage :fn {:name "pg-query"})))]
               (exec/make-single-arg-callable ctx pg-id))))))


(defn exec-with-storage
  "`exec/execute` + auto-inject `:storage-query` when the fn-def
   propagates it. Use this in place of `(exec/execute ctx fn-id args)`
   in tests where the target chain hits storage-protocol calls."
  [ctx storage fn-id args]
  (exec/execute ctx fn-id (inject-storage-query ctx storage fn-id args)))


(defn via-graph
  "Execute the named graph fn-def `fn-name` (a keyword, e.g.
   `:process-create-entity`) against `ctx`, passing `request` as the
   `:request` free arg WHEN the fn declares one. Read-only handlers
   like `:list-branches-handler` don't reference the request anywhere
   in their ref-tree (just stream rows out of storage), so threading
   `:request` would trip the executor's `unknown-arg-name` guard. We
   detect that case via `cr/free-arg-ext-names` and omit the key.

   Returns the Ring-shaped response the fn-def produces — same code
   path the production handler reaches."
  [{:keys [ctx all-name->id]} fn-name request]
  (let [fn-id (get all-name->id fn-name)]
    (when-not fn-id
      (throw (ex-info (str "No fn-id for " fn-name " — bootstrap missed it?")
                      {:type :test/missing-fn-id :fn-name fn-name})))
    (let [valid-args (set (cr/free-arg-ext-names ctx fn-id))
          ;; Storage-protocol injection (Block 1 Step 3): production
          ;; binds `:storage-query` at `:web-server`. Tests call
          ;; handlers directly, so they need the same binding
          ;; injected at the test entry point. Compile-produced
          ;; refs to `:fn`-typed slots are passed as IFn callables,
          ;; so we wrap the raw fn-id through
          ;; `make-single-arg-callable` — same shape `hof-callable`
          ;; uses for `:fn`-typed HOF args.
          storage-query-id (get all-name->id :pg-query)
          args (cond-> {}
                 (contains? valid-args :request)
                 (assoc :request request)

                 (and storage-query-id (contains? valid-args :storage-query))
                 (assoc :storage-query (exec/make-single-arg-callable ctx storage-query-id)))]
      (exec/execute-with-named-args ctx fn-id args))))


(defn setup-add-function!
  "Builds a small `:add` example: base-fn `add` with two `:int` slots,
   plus a composed instance with neither bound. Returns a map with
   `:base-fn`, `:slot-a` / `:slot-b` (and `:arg-a` / `:arg-b` aliases
   for legacy callers), `:composed-fn`."
  [storage]
  (exec/register-base-fn!
    :add
    (fn [args _ctx]
      (+ (rt/resolve-arg args :a) (rt/resolve-arg args :b))))
  (let [unique-suffix (str (random-uuid))
        base-fn (create-base-fn! storage "add" :int)
        slot-a (create-slot! storage "a" :int)
        slot-b (create-slot! storage "b" :int)
        _ (attach-slot! storage (:id base-fn) (:id slot-a) 0)
        _ (attach-slot! storage (:id base-fn) (:id slot-b) 1)
        composed-fn (create-composed-fn! storage
                                         (str "my-add-" unique-suffix)
                                         (:id base-fn))]
    {:base-fn base-fn
     :slot-a slot-a :slot-b slot-b
     ;; Legacy aliases — `setup/create-arg!` interprets `:source-id`
     ;; as the slot-id directly, so passing `(:id arg-a)` works.
     :arg-a slot-a :arg-b slot-b
     :composed-fn composed-fn}))
