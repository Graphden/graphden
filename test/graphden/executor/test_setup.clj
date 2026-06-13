(ns graphden.executor.test-setup
  "Shared test setup for executor tests in the slot/fn-slot/binding model.

   Helpers create fn rows, slot rows, fn-slot junctions, and binding
   rows directly via the storage protocol. Higher-level helpers like
   `setup-add-function!` synthesise a small example graph end-to-end."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as rt]
    [graphden.packages.records :as records]
    [graphden.packages.records.ids :as ids]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.core :as sys]
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.versioning.storage.core :as vs]))


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


(defn create-test-storage
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))
        schema (gds/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    ;; Pre-seed the 14 primitive fn-rows so slot.type-fn-id refs resolve.
    ;; `boot-primitive-records` returns tagged records (`:kind :fn`); strip
    ;; the tag before storage upsert.
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    storage))


(defn- full-schema
  "Schema covering the executor minimum (graph + traits) plus
   versioned + executions + services. Same combination the production
   storage init runs through; tests touching `:branch` / `:execution`
   / `:service` rows need every layer."
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (ds/build)))


(defn create-versioned-test-storage
  "Like `create-test-storage` but initialises the full schema and
   wraps the base storage with `VersionedStorage` on the `main`
   branch — the same shape production runs under. Tests that drive
   branches / executions / services CRUD need this; pure-fn-graph
   tests should stay on `create-test-storage` (lighter)."
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))]
    (sp/initialize storage (full-schema))
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning storage "main")))


;; ============================================================================
;; Test helpers — slot/fn-slot/binding model
;; ============================================================================

(def primitive-fn-ids (records/primitive-fn-ids))


(defn create-base-fn!
  "Creates a base-fn row (impl-hash set, no parent-ids). Returns the
   created fn record."
  ([storage fn-name]
   (create-base-fn! storage fn-name nil))
  ([storage fn-name return-type-keyword]
   (sp/create-entity storage :fn
                     {:name fn-name
                      :parent-ids nil
                      :impl-hash "test-stub-hash"
                      :return-type-fn-id (when return-type-keyword
                                           (get primitive-fn-ids return-type-keyword))})))


(defn create-composed-fn!
  "Creates a composed fn-row inheriting from `parent-id`."
  [storage fn-name parent-id]
  (sp/create-entity storage :fn
                    {:name fn-name
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
                         :impl-hash nil
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
  "Creates a value-binding for `slot-id` on `fn-id`."
  [storage fn-id slot-id value]
  (sp/create-entity storage :binding
                    {:fn-id fn-id
                     :slot-id slot-id
                     :value value
                     :override-kind :fixed}))


(defn bind-ref!
  "Creates a ref-binding for `slot-id` on `fn-id` pointing at
   `target-fn-id`."
  [storage fn-id slot-id target-fn-id]
  (sp/create-entity storage :binding
                    {:fn-id fn-id
                     :slot-id slot-id
                     :ref-fn-id target-fn-id
                     :override-kind :fixed}))


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
   (let [bootstrap (sys/bootstrap-from-packages! storage package-names
                                                 {:skip-type-check? true})
         ctx (exec/create-context {:storage storage})]
     (cr/rebuild! ctx)
     (assoc bootstrap :storage storage :ctx ctx))))


(defn bootstrap-crud-graph-from-golden!
  "Fast bootstrap via the shared golden DB + `CREATE DATABASE …
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
  ([]
   (bootstrap-crud-graph-from-golden! (str (ns-name *ns*))
                                      ["core" "web" "app"]))
  ([ns-ident]
   (bootstrap-crud-graph-from-golden! ns-ident ["core" "web" "app"]))
  ([ns-ident package-names]
   (let [{:keys [db-config bootstrap]}
         (sb/ensure-ns-database-from-golden! ns-ident package-names)
         storage (pg/create-storage db-config)
         versioned (vs/wrap-with-versioning storage "main")
         ctx (exec/create-context {:storage versioned})]
     (cr/rebuild! ctx)
     (assoc bootstrap :storage versioned :ctx ctx))))


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
