(ns graphden.system.core
  "Integrant init-key implementations for all system components.

   Component dependency graph:
   :db/schema        → (pure function, no deps)
   :db/postgres      → [:db/schema]
   :db/versioned     → [:db/postgres]
   :app/packages     → (pure, loads package definitions)
   :exec/base-fns    → [:db/versioned, :app/packages]
   :exec/fn-entities → [:db/versioned, :exec/base-fns, :app/packages]
   :exec/context     → [:db/versioned]
   :http/server      → [:exec/context, :exec/fn-entities, :app/packages]"
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.registry.interface :as registry]
    [graphden.packages.loader :as pkg]
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as postgres]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check :as types-check]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


;; =============================================================================
;; Schema (pure, no lifecycle)
;; =============================================================================

(defmethod ig/init-key :db/schema [_ _]
  (log/info "Building schema...")
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (ds/build)))


;; =============================================================================
;; Storage (unified initialization)
;; =============================================================================

(defn- init-storage!
  "Unified storage initialization.
   Creates storage using create-fn, initializes with schema, seeds traits."
  [storage-name create-fn {:keys [jdbc-url username password pool-size schema]}]
  (log/info (str "Connecting to " storage-name ":") jdbc-url)
  (let [storage (-> (create-fn {:jdbc-url jdbc-url
                                :username username
                                :password password
                                :pool-size pool-size})
                    (sp/initialize-with-cleanup! schema))]
    (vts/seed-traits! storage)
    (log/info (str storage-name " initialized"))
    storage))


(defn- halt-storage!
  "Unified storage shutdown."
  [storage-name storage]
  (log/info (str "Closing " storage-name "..."))
  (sp/close storage))


(defmethod ig/init-key :db/postgres [_ opts]
  (init-storage! "PostgreSQL" postgres/create-storage opts))


(defmethod ig/halt-key! :db/postgres [_ storage]
  (halt-storage! "PostgreSQL storage" storage))


;; =============================================================================
;; Versioned Storage Decorator
;; =============================================================================

(defmethod ig/init-key :db/versioned [_ {:keys [base-storage]}]
  (log/info "Enabling versioning...")
  (let [versioned (vs/wrap-with-versioning base-storage)]
    (log/info "Branch:" (vs/current-branch-id versioned))
    versioned))


;; No halt needed - base storage handles cleanup


;; =============================================================================
;; Package Loading
;; =============================================================================

(defmethod ig/init-key :app/packages [_ {:keys [package-names]}]
  (log/info "Loading packages:" package-names)
  (let [packages (pkg/load-packages package-names)]
    (log/info "Packages loaded:" (count (:packages packages)) "packages,"
              (count (:base-fn-defs packages)) "base-fns,"
              (count (:fn-defs packages)) "fn-defs")
    packages))


;; =============================================================================
;; Base Functions Registry
;; =============================================================================

(defn- compute-all-fn-name-ids
  "Pre-compute deterministic fn-ids for every named def across the
   loaded packages — base-fns + composed fn-defs (incl. `:fn-type`
   declarations) combined. Threaded into both syncs so cross-module
   references (e.g. a base-fn's `:return-type` pointing at a type-row
   in another module) resolve.

   `:fn-type` declarations get the standard `(fn-id ns name)`
   deterministic UUID — they now produce real fn-rows whose
   `:constraint` carries the structural `[:fn args ret]` shape
   (mirrors how unions / variants stash their payload). Pre-fix this
   path aliased them to `primitive-fn-id :fn`, leaving every
   `:return-type :http-server-handle`-style reference pointing at the
   bare-`:fn` row and erasing the structural shape from storage."
  [packages]
  (let [base-pairs (keep (fn [[fn-name fn-def]]
                           (when fn-name
                             [fn-name (records/fn-id (:namespace fn-def) fn-name)]))
                         (:base-fn-defs packages))
        fn-def-pairs (keep (fn [fd]
                             (when-let [n (:name fd)]
                               [n (records/fn-id (:namespace fd) n)]))
                           (:fn-defs packages))]
    (into {} (concat base-pairs fn-def-pairs))))


(defn- register-type-aliases!
  "Walk every fn-def that declares a structural type (refinement,
   record, list, union, fn-type) and register it as a type-alias so
   the type-checker's `resolve-alias` can expand the keyword when it
   appears as a `:type` reference in another fn-def. Without this,
   `:http-server :args {:port :port}` would store the bare keyword
   and a downstream literal like `{:port 8080}` would trigger a
   bogus `:int ⊆ :port` primitive subtype check.

   Two passes — the second resolves now that all top-level names
   are known. This lets `:ring-handler` reference `:ring-request`
   regardless of declaration order in fns.edn.

   Registration tries each alias even if some fail validation
   (e.g. references an unknown type) — the second pass usually
   resolves those. Genuine errors surface later through the
   type-checker on first use."
  [fn-defs]
  (let [alias-body
        (fn [fd]
          (cond
            (:refine fd)
            (let [{:keys [base constraint]} (:refine fd)]
              (when base [:refine base (or constraint [:any])]))

            (and (:type fd) (map? (:type fd)))
            (:type fd)

            (:list fd)
            [:list (:list fd)]

            (:union fd)
            (into [:union] (:union fd))

            ;; `:variant [:tag1 T1 :tag2 T2 …]` desugars to a union of
            ;; tag-pinned records (see types/desugar-variant). Without
            ;; this branch the EDN-declared `:result-text`,
            ;; `:result-int`, `:validation` aliases never reached
            ;; `register-type-alias!` and the type-checker treated
            ;; them as unknown keywords — defeating the whole point
            ;; of a variant declaration.
            (:variant fd)
            (types/desugar-variant (:variant fd))

            (:fn-type fd)
            (let [[args ret] (:fn-type fd)]
              [:fn (or args {}) ret])))
        candidates (for [fd fn-defs
                         :when (:name fd)
                         :let [body (alias-body fd)]
                         :when body]
                     [(:name fd) body])
        try-once
        (fn [pending]
          ;; Returns the subset of [name body] pairs whose validation
          ;; still fails — caller iterates until fixed point.
          (reduce
            (fn [still-pending [nm body]]
              (try (types/register-type-alias! nm body)
                   still-pending
                   (catch Exception _
                     (conj still-pending [nm body]))))
            []
            pending))]
    ;; Iterate to fixed point — each pass widens `aliases-snapshot`,
    ;; which `well-formed?` consults for inner-keyword refs. Bound
    ;; the loop count so a true cycle (or a body referencing an
    ;; unknown type) terminates instead of spinning.
    (loop [pending candidates, iter 0]
      (let [next-pending (try-once pending)]
        (cond
          (empty? next-pending) nil
          (or (= (count next-pending) (count pending))
              (>= iter 8))
          (doseq [[nm _] next-pending]
            (log/warn "register-type-alias! failed for" nm
                      "— body references an unknown type"))
          :else (recur next-pending (inc iter)))))))


(defmethod ig/init-key :exec/base-fns [_ {:keys [storage packages]}]
  (log/info "Registering base functions...")
  (let [base-fn-defs (:base-fn-defs packages)
        ;; Sync namespace entities first (creates ns hierarchy in DB)
        ns-id-map (pkg/sync-namespaces! storage (:namespaces packages)
                                        (:ns-descriptions packages))
        ;; Full name→id map covering base-fns + composed fn-defs so
        ;; either sync can resolve a reference into the other set.
        all-name->id (compute-all-fn-name-ids packages)]
    (registry/sync-primitives! storage)
    ;; Register refinement type-aliases BEFORE base-fn rich-type
    ;; recording so `:http-server :args {:port :port}` stores the
    ;; structural `[:refine :int …]` form, not the bare keyword.
    (register-type-aliases! (:fn-defs packages))
    (registry/register-base-fns! base-fn-defs)
    (registry/sync-defs-to-storage! storage base-fn-defs ns-id-map all-name->id)
    (log/info "Base functions registered:" (count base-fn-defs))
    {:status :registered
     :ns-id-map ns-id-map
     :all-name->id all-name->id}))


;; No halt needed - registry is global state


;; =============================================================================
;; Fn Entities
;; =============================================================================

(defmethod ig/init-key :exec/fn-entities [_ {:keys [storage packages base-fns]}]
  (log/info "Creating fn entities...")
  (let [fn-defs (:fn-defs packages)
        ns-id-map (or (:ns-id-map base-fns) {})
        extra-name->id (or (:all-name->id base-fns) {})
        ;; Hand the base-fn defs into the composed-fn sync so the slot
        ;; resolver sees their `:args` declarations — without these,
        ;; bindings on slots owned by base-fns wouldn't resolve.
        extra-defs (into {}
                         (keep (fn [[fn-name fn-def]]
                                 (when fn-name
                                   [fn-name (assoc fn-def :name fn-name)])))
                         (:base-fn-defs packages))
        fns (fn-composition/sync-fns-to-storage! storage fn-defs ns-id-map
                                                 extra-name->id extra-defs)]
    ;; Snapshot composed fn-defs into the in-memory rich-type registry
    ;; so the editor's `:effects` strip and arg-type hints can resolve
    ;; their declared shape. Two passes:
    ;;
    ;; 1. Seed each fn-def's declared shape (return + args + declared
    ;;    `:effects`). Without this, `check-all-defs!` would fail to
    ;;    resolve refs to peer fn-defs whose entries don't exist yet.
    ;; 2. Run the full type-checker so `:effects` propagate transitively
    ;;    through every parent + ref edge — that's what powers the
    ;;    editor's effects-strip showing the union of every category
    ;;    a fn-def TRANSITIVELY pulls in. Wrap in try/catch: a single
    ;;    fn-def's type-mismatch shouldn't block server startup.
    ;; Refinement aliases are registered earlier in `:exec/base-fns` so
    ;; base-fn arg types (`:port`, `:user-port`, …) resolve to their
    ;; structural form during the base-fn rich-type pass.
    ;; record-rich-types! validates arg `:type` declarations — those
    ;; only exist on base-fn-style fn-defs (rare here; composed fn-defs
    ;; use `:args` for parent BINDINGS, not declarations). Try-each so a
    ;; few mis-shaped entries don't kill the seed pass; check-all-defs!
    ;; below recovers the proper computed types via type-inference anyway.
    (doseq [fd fn-defs]
      (when-let [fn-name (:name fd)]
        (try (registry-core/record-rich-types! fn-name fd)
             (catch Exception _))))
    ;; Type-check in dependency (topological) order: every fn-def is
    ;; checked AFTER the parents and refs it reads, so a SINGLE sweep
    ;; reaches the fixpoint — `check-fn-def!` always sees its
    ;; dependencies' final rich-types, never a stale seed. This is
    ;; what eliminates the order-dependent under-convergence a fixed
    ;; pass count over arbitrary order suffered (a deep chain it
    ;; couldn't propagate, leaving composed fn-defs absent or
    ;; mis-typed). A fn-def that throws here is genuinely absent from
    ;; the rich-type registry — the editor would miss its effect strip
    ;; / computed return — so it's logged. The per-fn-def try/catch
    ;; keeps one mismatch from aborting the rest of the sweep.
    (doseq [fd (deps/topological-sort fn-defs)]
      (try (types-check/check-fn-def! fd)
           (catch Exception e
             (log/warn "Type-check failed for fn-def" (:name fd) "—"
                       (ex-message e)))))
    (log/info "Fn entities created:" (count fns))
    fns))


;; =============================================================================
;; Executor Context
;; =============================================================================

(defmethod ig/init-key :exec/context [_ {:keys [storage]}]
  (log/info "Creating executor context...")
  (exec/create-context {:storage storage}))


;; =============================================================================
;; Compiled Registry (compile-at-startup executor)
;; =============================================================================
;;
;; Walks every fn entity in storage and compiles each into a Clojure
;; closure of shape `(fn [all-fns free-args] result)`. Stored in the
;; context's `:compiled-registry` atom for the hot path (HTTP handlers).

(defmethod ig/init-key :exec/compiled-registry [_ {:keys [context]}]
  (log/info "Building compiled registry...")
  (let [registry (cr/rebuild! context)]
    (log/info "Compiled registry built:" (count registry) "fns")
    registry))


;; =============================================================================
;; HTTP Server (executed via compile-at-startup registry)
;; =============================================================================

(defmethod ig/init-key :http/server [_ {:keys [context packages port]}]
  (let [startup-fn-name (:startup-fn packages)]
    (log/info "Starting HTTP server via" startup-fn-name "on port" port "...")
    (let [server (cr/execute-by-name context (name startup-fn-name) nil)]
      (log/info "HTTP server started on port" port)
      server)))


(defmethod ig/halt-key! :http/server [_ server]
  (log/info "Stopping HTTP server...")
  (when server
    ;; http-kit server is a function - calling it stops the server
    (server))
  (log/info "HTTP server stopped"))


(defmethod ig/suspend-key! :http/server [_ server]
  ;; Same as halt for HTTP server
  (when server (server)))


(defmethod ig/resume-key :http/server [k opts _ _]
  ;; Restart server with new context
  (ig/init-key k opts))
