(ns graphden.executor.compile-runtime
  "Public entry points for the compiled executor.

   Bridges between the compile-time registry (produced by
   `graphden.executor.compile/compile-all`) and the executor's public API
   (`execute`, `make-*-arg-callable`).

   Since the legacy queue was retired, this namespace IS the executor —
   `exec/` public API delegates here. The registry is rebuilt on demand
   when missing (test paths that create contexts directly without going
   through the system-level `:exec/compiled-registry` init-key)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.compile :as compile]
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.runtime :as rt]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


;; =============================================================================
;; Registry lifecycle
;; =============================================================================

(defn- read-graph
  "Read the slot/fn-slot/binding model entities from storage. Bundled
   so `rebuild!` and the on-demand free-arg resolver share the same
   query shape."
  [storage]
  {:fns        (sp/query-entities storage :fn {})
   :slots      (sp/query-entities storage :slot {})
   :fn-slots   (sp/query-entities storage :fn-slot {})
   :bindings   (sp/query-entities storage :binding {})
   :list-items (sp/query-entities storage :binding-list-item {})})


(defn- type-row-role
  "Classify a fn-row into one of the type roles (per the schema role
   table) — `:record`, `:refinement`, `:list`, `:union`, `:variant`,
   `:fn-type`, `:base-fn`, `:composed`, or `:primitive`. Used by
   `register-type-aliases-from-db!` to pick the right alias body shape.

   Unions, variants, AND structural fn-types share the storage shape
   — all three stash their payload in `:constraint`
   (`[:union T1 T2 …]` / `[:variant tag1 T1 tag2 T2 …]` /
   `[:fn args ret]`). The leading keyword discriminates."
  [fn-row has-slots?]
  (cond
    (seq (:parent-ids fn-row))     :composed
    (some? (:impl-hash fn-row))    :base-fn
    (some? (:base-fn-id fn-row))   :refinement
    (some? (:element-fn-id fn-row)) :list
    (and (vector? (:constraint fn-row))
         (= :union (first (:constraint fn-row)))) :union
    (and (vector? (:constraint fn-row))
         (= :variant (first (:constraint fn-row)))) :variant
    (and (vector? (:constraint fn-row))
         (= :fn (first (:constraint fn-row)))) :fn-type
    has-slots?                     :record
    :else                          :primitive))


(defn register-type-aliases-from-db!
  "Walk type-rows in the just-loaded graph and register them as type-
   aliases — the runtime equivalent of system/core's
   `register-type-aliases!` which only sees package data. Called from
   `rebuild!` AND from CRUD impls so types CREATED VIA API become
   resolvable to the type-checker without a server restart.

   Mirrors the EDN-side body shapes:
     record-type    → `{slot-name slot-type-name …}`
     refinement     → `[:refine base-name constraint]`
     list           → `[:list element-name]`

   Phase 7 — registration goes through `types/register-type-aliases-
   batch`, which extends the validation view with every pending name
   before checking bodies. Mutual / self-recursive types (`{:children
   [:list :tree]}`, `Person↔Address`) register in a single pass
   without the legacy fixed-point loop. Genuinely malformed entries
   (dangling refs to names that aren't in the batch and aren't
   registered yet) are collected per-row and logged."
  [{:keys [fns slots fn-slots]}]
  (let [fn-by-id   (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        slots-by-fn (group-by :fn-id fn-slots)
        name-by-id (fn [id] (some-> (get fn-by-id id) :name keyword))
        candidates
        (keep
          (fn [f]
            (when-let [nm (some-> (:name f) keyword)]
              (let [own-slots (->> (get slots-by-fn (:id f) [])
                                   (sort-by :position)
                                   (keep #(get slot-by-id (:slot-id %))))
                    role (type-row-role f (seq own-slots))
                    body (case role
                           :record
                           (into {}
                                 (keep (fn [s]
                                         (when-let [tn (name-by-id (:type-fn-id s))]
                                           [(keyword (:name s)) tn])))
                                 own-slots)

                           :refinement
                           (when-let [base (name-by-id (:base-fn-id f))]
                             [:refine base (or (:constraint f) [:any])])

                           :list
                           (when-let [elem (name-by-id (:element-fn-id f))]
                             [:list elem])

                           :union
                           ;; Constraint already shaped as [:union T1 T2 …]
                           ;; by parse-union; re-register verbatim so
                           ;; resolve-alias sees the same form the EDN
                           ;; path produces.
                           (:constraint f)

                           :variant
                           ;; Desugar to the union-of-pinned-records
                           ;; form so the alias-registry stores the
                           ;; same structural type the EDN path
                           ;; produces (see types/desugar-variant).
                           ;; The constraint payload is
                           ;; `[:variant tag1 T1 tag2 T2 …]`; we strip
                           ;; the leading `:variant` and let the
                           ;; helper rebuild the union. Names embedded
                           ;; in `Tᵢ` resolve through the batch's
                           ;; pre-extended `*alias-view*`, so mutual
                           ;; refs across newly-saved type-rows work
                           ;; in a single pass — same as records.
                           (types/desugar-variant (:constraint f))

                           :fn-type
                           ;; `[:fn args ret]` — already in canonical
                           ;; structural form, register verbatim. The
                           ;; row has no name when it came from an
                           ;; inline reference; alias entry only
                           ;; lands when the `:name` keyword exists
                           ;; (the `keep` over `(:name f)` above
                           ;; filters anonymous rows out anyway).
                           (:constraint f)

                           nil)]
                (when body [nm body]))))
          fns)
        {:keys [failed]} (types/register-type-aliases-batch candidates)]
    (doseq [{:keys [nm reason]} failed]
      (log/warn (str "register-type-aliases-from-db!: skipped " (pr-str nm)
                     " — " reason)))))


(defn refresh-type-registries-from-storage!
  "Light-weight equivalent of `rebuild!` that ONLY refreshes type
   registries (aliases + rich-types snapshot of current DB type-rows)
   — does NOT recompile fn closures.

   CRUD impls call this AFTER mutation so editor reads (`/api/types`,
   pickers) see the new/modified types immediately. The compiled
   closures are also stale at that point but they're rebuilt
   on-demand by the next `execute` (via `registry`'s lazy fallback)."
  [ctx]
  (let [storage (:storage ctx)
        graph (read-graph storage)]
    (register-type-aliases-from-db! graph)
    graph))


(defn- prime-always-fresh!
  "Refresh the global always-fresh fn-id set from `:effects` in the
   rich-types registry. Pulled out of `rebuild!` so `delta-recompile!`
   can call it too — the set is consulted on every per-call cache
   read, so it must reflect the current graph after partial recompiles."
  [fns]
  (let [rich ((requiring-resolve 'graphden.executor.registry.core/rich-types-snapshot))
        always-fresh-categories #{:time :random}
        always-fresh-fn-ids
        (into #{}
              (keep (fn [f]
                      (when-let [nm (:name f)]
                        (let [eff (:effects (get rich (keyword nm)))]
                          (when (and eff (some always-fresh-categories eff))
                            (:id f))))))
              fns)]
    (compile/set-always-fresh-fn-ids! always-fresh-fn-ids)))


(defn- prime-graph-cache!
  "Mirror the just-loaded raw entities into `:graph-cache` so reads
   from layout / `/api/graph/entities` / `/api/types` go through the
   same snapshot the compiler used."
  [ctx graph]
  (when-let [graph-cache (:graph-cache ctx)]
    (reset! graph-cache (select-keys graph [:fns :slots :fn-slots
                                            :bindings :list-items]))))


(defn- prime-compile-deps!
  "Capture the freshly-built reverse-dependency index on `ctx`. Used
   by `delta-recompile!` to compute the blast radius of subsequent
   mutations."
  [ctx graph]
  (when-let [holder (:compile-deps ctx)]
    (reset! holder (compile/build-reverse-deps graph))))


(defn rebuild!
  "Rebuild the compiled registry in `ctx` from whatever the slot/binding
   tables currently hold. Also primes `:graph-cache` and `:compile-deps`
   with the same data so read-heavy consumers (layout API, editor) and
   the delta-invalidation path stay in sync. Call at startup and on
   full invalidation."
  [ctx]
  (let [storage (:storage ctx)
        graph (read-graph storage)
        _ (register-type-aliases-from-db! graph)
        base-fns (:base-fns ctx)]
    (prime-always-fresh! (:fns graph))
    (let [compiled (compile/compile-all (assoc graph :base-fns base-fns) ctx)]
      (reset! (:compiled-registry ctx) compiled)
      (prime-graph-cache! ctx graph)
      (prime-compile-deps! ctx graph)
      compiled)))


(defn delta-recompile!
  "Recompile only the fns whose closures depend on `changed-fn-ids` —
   the inverse-closure under the reverse-deps index built by
   `rebuild!`. Bound to one `read-graph` and N `compile-fn` calls
   where N is the blast radius (typically a handful, not the whole
   registry).

   Falls back to a full `rebuild!` when the reverse-deps index is
   missing (cold start) or when `changed-fn-ids` is empty / nil — the
   caller asked for invalidation but didn't say what changed, so the
   safe move is to drop everything.

   Type-aliases are always re-registered (cheap, global), and
   entries for deleted fn-ids are dissoc'd from the registry so a
   stale closure can't outlive its row."
  [ctx changed-fn-ids]
  (let [holder (:compiled-registry ctx)
        deps   (some-> (:compile-deps ctx) deref)]
    (cond
      (or (nil? holder) (nil? @holder) (nil? deps) (empty? changed-fn-ids))
      (rebuild! ctx)

      :else
      (let [storage (:storage ctx)
            graph (read-graph storage)
            base-fns (:base-fns ctx)
            _ (register-type-aliases-from-db! graph)
            fns-map (if (map? (:fns graph))
                      (:fns graph)
                      (into {} (map (juxt :id identity)) (:fns graph)))
            _ (prime-always-fresh! (vals fns-map))
            blast (compile/transitive-blast deps changed-fn-ids)
            lookups (assoc (l/build-lookups graph) :base-fns base-fns)
            compilable? (fn [f]
                          (let [root (l/root-fn (:id f) (:fn-map lookups) lookups)
                                root-name (some-> (:name root) keyword)]
                            (and root-name (contains? base-fns root-name))))
            new-entries (into {}
                              (keep (fn [fn-id]
                                      (when-let [f (get fns-map fn-id)]
                                        (when (compilable? f)
                                          [fn-id (compile/compile-fn fn-id lookups ctx)]))))
                              blast)
            cleaned (into {}
                          (filter (fn [[k _]] (contains? fns-map k)))
                          @holder)]
        (reset! holder (merge cleaned new-entries))
        (prime-graph-cache! ctx graph)
        (prime-compile-deps! ctx graph)
        @holder))))


(defn registry
  "Return the current compiled registry from `ctx`, rebuilding on-demand
   when missing. Tests that skip the `:exec/compiled-registry` init-key
   still get a working executor via this fallback — the cost is a single
   compile pass on first execution."
  [ctx]
  (when-let [holder (:compiled-registry ctx)]
    (or @holder (rebuild! ctx))))


;; =============================================================================
;; Arg-name resolution
;; =============================================================================

(defn free-arg-ext-names
  "Ordered vector of external names for fn-id's free args reachable
   through its ref-chain. Used to shape HOF callables when the caller
   didn't pick a specific arg name."
  [ctx fn-id]
  (let [storage (:storage ctx)
        graph (read-graph storage)
        lookups (assoc (l/build-lookups graph)
                       :base-fns (:base-fns ctx))]
    (mapv :ext-name
          (filter #(= :free (:kind %))
                  (b/collect-bindings fn-id lookups)))))


;; =============================================================================
;; Execute
;; =============================================================================

(defn execute
  "Invoke `fn-id` via the compiled registry. `named-args` is a `{arg-name
   value}` map using the outermost external arg names (rename-aware).

   HOF impls that deref a `:fn`-type arg end up with a callable (from
   `rt/hof-callable`) rather than a UUID and hand it back in through
   this same entry point. For single-entry args the value is unwrapped
   from the map; for empty or multi-entry args the whole map is passed
   through."
  [ctx fn-id named-args]
  (if (fn? fn-id)
    (let [args (or named-args {})]
      (if (= 1 (count args))
        (fn-id (first (vals args)))
        (fn-id args)))
    (let [reg (registry ctx)
          closure (get reg fn-id)]
      (when-not closure
        (throw (ex-info (str "Function not found: " fn-id)
                        {:type :execution-error/fn-not-found
                         :fn-id fn-id})))
      (closure reg (or named-args {})))))


(defn- query-fn-by-name
  "Storage schemas vary on whether `fn.name` is stored as text or enum
   (package-loader goes through a keyword codec). Try both shapes and
   swallow validation errors so either works."
  [storage fn-name]
  (letfn [(try-one
            [value]
            (try
              (first (sp/query-entities storage :fn {:name value}))
              (catch clojure.lang.ExceptionInfo e
                (when-not (= :validation-error/type-mismatch
                             (:type (ex-data e)))
                  (throw e))
                nil)))]
    (or (try-one fn-name)
        (try-one (keyword fn-name)))))


(defn execute-by-name
  [ctx fn-name named-args]
  (let [match (query-fn-by-name (:storage ctx) fn-name)]
    (when-not match
      (throw (ex-info (str "Function '" fn-name "' not found")
                      {:type :execution-error/fn-not-found
                       :fn-name fn-name})))
    (execute ctx (:id match) named-args)))


;; =============================================================================
;; HOF callable helpers
;; =============================================================================

(defn make-single-arg-callable
  "Build a callable over `fn-id`. Mirrors `compile/hof-wrap`'s
   leftover-logic: 0 free args → variadic ignore; 1 free arg →
   single-arg callable (item bound to that name); 2+ → map-callable
   (caller passes `{name value}` map matching the target's free-arg
   names). The compiler picks no names — author and caller agree.

   If `fn-id` is already a callable (e.g. a compile-produced wrap
   result handed to a helper that calls this), returns it as-is.

   This entry point has no `outer-free-args` to subtract — it builds
   a top-level callable. So `leftover` here is the full set of
   free-arg names of the target."
  [ctx fn-id]
  (if (fn? fn-id)
    fn-id
    (let [reg (registry ctx)
          closure (get reg fn-id)
          free-names (free-arg-ext-names ctx fn-id)]
      (when-not closure
        (throw (ex-info (str "Function not found: " fn-id)
                        {:type :execution-error/fn-not-found
                         :fn-id fn-id})))
      (case (count free-names)
        0 (fn [& _] (closure reg {}))
        1 (let [n (first free-names)]
            (fn [item] (closure reg {n item})))
        (fn [m] (closure reg m))))))


;; Re-export — so this namespace is the canonical entry surface.
(def thunk rt/thunk)
(def resolve-arg rt/resolve-arg)
