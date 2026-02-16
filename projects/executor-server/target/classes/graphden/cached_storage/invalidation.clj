(ns graphden.cached-storage.invalidation
  "Declarative cache invalidation rule registry.

   Provides a registry of invalidation rules that are matched by entity type
   and event type (:create, :update, :delete). Rules are registered at startup
   and executed when mutations occur.

   Rule format:
   {:entity-type :fn
    :on-event    :create
    :handler     (fn [ctx] ...)}

   Context map passed to handlers:
   - :base-storage   — underlying storage (StorageCRUD)
   - :cache-storage  — cache implementation
   - :entity-name    — entity type keyword
   - :result         — created/updated record (create/update only)
   - :id             — entity id (update/delete only)
   - :data           — update data map (update only)
   - :old-record     — record before update (update only, when available)
   - :record         — record before deletion (delete only)"
  (:require
    [clojure.tools.logging :as log]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


;; === Registry ===

(def ^:private registry
  "Atom holding a vector of invalidation rules."
  (atom []))


(defn register-rule!
  "Registers an invalidation rule.
   Rule must have :entity-type, :on-event, and :handler keys."
  [rule]
  {:pre [(:entity-type rule) (:on-event rule) (:handler rule)]}
  (swap! registry conj rule)
  nil)


(defn get-rules
  "Returns all registered rules."
  []
  @registry)


(defn reset-registry!
  "Clears all registered rules. Returns previous rules."
  []
  (let [prev @registry]
    (reset! registry [])
    prev))


(defn has-strategy?
  "Returns true if any rule matches entity-type and on-event."
  [entity-type on-event]
  (some #(and (= (:entity-type %) entity-type)
              (= (:on-event %) on-event))
        @registry))


(defn process-invalidation!
  "Finds and executes all matching rules for the given entity-type and event.
   ctx is the context map passed to each handler."
  [entity-type on-event ctx]
  (doseq [rule @registry
          :when (and (= (:entity-type rule) entity-type)
                     (= (:on-event rule) on-event))]
    ((:handler rule) ctx)))


;; === Cache helpers ===

(defn- try-parse-uuid
  "Attempts to parse value as UUID.
   Returns UUID if value is already a UUID or a valid UUID string.
   Returns nil for non-UUID values."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try
                  (java.util.UUID/fromString v)
                  (catch IllegalArgumentException _ nil))
    :else nil))


(defn- extract-fn-ref-id
  "Extracts fn-id from a cached value in union format.
   Returns UUID if value is {:kind :fn-ref :fn-id <uuid>}, nil otherwise.
   Also handles raw UUIDs and UUID strings for backward compatibility."
  [value]
  (cond
    ;; Union format: {:kind :fn-ref :fn-id uuid}
    (and (map? value) (= :fn-ref (:kind value)))
    (try-parse-uuid (:fn-id value))

    ;; Raw UUID or UUID string (backward compat)
    :else
    (try-parse-uuid value)))


(defn extract-call-site-ids-from-resolved-args
  "Extracts potential call-site IDs from cached resolved-args.
   Scans all values and returns UUIDs that might be call-site references.

   Values in resolved-args are in union format:
   - {:kind :fn-ref :fn-id <uuid>} for function/call-site references
   - {:kind :literal :value <any>} for literal values

   Note: This cannot distinguish call-site refs from fn refs just from values.
   The caller should use read-entities on :call-site to verify which are actual call-sites.

   resolved-args format: {fn-id -> {arg-schema-id -> value}}"
  [resolved-args]
  (->> resolved-args
       vals                           ; {arg-schema-id -> value} maps
       (mapcat vals)                  ; all values
       (keep extract-fn-ref-id)       ; extract fn-ref UUIDs
       set))


(defn compute-dependencies
  "Computes dependency counts from an execution graph.
   Returns {:fn-ids {fn-id -> count}
            :fn-schema-ids {schema-id -> count}
            :arg-schema-ids {arg-schema-id -> count}
            :call-site-ids {call-site-id -> count}}"
  [graph]
  {:fn-ids (frequencies (keys (:fns graph)))
   :fn-schema-ids (frequencies (keys (:fn-schemas graph)))
   :arg-schema-ids (frequencies (keys (:arg-schemas graph)))
   :call-site-ids (frequencies (keys (:call-sites graph)))})


(defn rebuild-cache!
  "Rebuilds cache for a single fn-id using base storage's resolve-execution-graph."
  [base-storage cache-storage fn-id]
  (log/debug "Rebuilding cache" {:fn-id fn-id})
  (let [graph (sp/resolve-execution-graph base-storage fn-id)
        deps (compute-dependencies graph)]
    (cache/save-cache! cache-storage fn-id graph deps)
    (log/debug "Cache rebuilt" {:fn-id fn-id
                                :fns-count (count (:fns graph))
                                :fn-schemas-count (count (:fn-schemas graph))})))


(defn- batch-delete-caches!
  "Deletes multiple caches. Returns the set of deleted cache-ids."
  [cache-storage cache-ids]
  (doseq [cache-id cache-ids]
    (cache/delete-cache! cache-storage cache-id))
  cache-ids)


(defn- batch-rebuild-existing-caches!
  "Rebuilds caches only for fns that still exist in storage.
   Uses parallel reads to check existence, then sequential rebuilds.
   Returns the set of cache-ids that were rebuilt."
  [base-storage cache-storage cache-ids]
  (when (seq cache-ids)
    (let [existing-fns (sp/read-entities base-storage :fn (vec cache-ids))
          existing-fn-ids (set (keys existing-fns))]
      (when (seq existing-fn-ids)
        (log/debug "Rebuilding caches for existing fns"
                   {:total (count cache-ids)
                    :existing (count existing-fn-ids)})
        (doseq [cache-id existing-fn-ids]
          (rebuild-cache! base-storage cache-storage cache-id)))
      existing-fn-ids)))


(defn invalidate-dependents!
  "Invalidates all caches returned by find-fn and rebuilds them if the fn exists.
   find-fn should be a function that takes cache-storage and returns a set of cache-ids."
  [base-storage cache-storage find-fn]
  (let [dependent-cache-ids (find-fn cache-storage)]
    (when (seq dependent-cache-ids)
      (log/debug "Invalidating dependent caches" {:count (count dependent-cache-ids)
                                                  :cache-ids dependent-cache-ids})
      (batch-delete-caches! cache-storage dependent-cache-ids)
      (batch-rebuild-existing-caches! base-storage cache-storage dependent-cache-ids))))


(defn invalidate-fn-and-dependents!
  "Invalidates cache for fn-id and all caches that depend on it."
  [base-storage cache-storage fn-id]
  (log/debug "Invalidating fn and dependents" {:fn-id fn-id})
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-fn-dep % fn-id))
  (when (sp/read-entity base-storage :fn fn-id)
    (rebuild-cache! base-storage cache-storage fn-id)))


(def ^:private dep-type->find-fn
  "Maps dependency type keyword to CacheStorage lookup function."
  {:fn-schema cache/find-caches-by-fn-schema-dep
   :arg-schema cache/find-caches-by-arg-schema-dep
   :call-site  cache/find-caches-by-call-site-dep})


(defn invalidate-entity-dependents!
  "Invalidates all caches that depend on the given entity.
   dep-type is one of :fn-schema, :arg-schema, :call-site."
  [base-storage cache-storage dep-type dep-id]
  (let [find-fn (get dep-type->find-fn dep-type)]
    (assert find-fn (str "Unknown dep-type: " dep-type))
    (log/debug "Invalidating dependents" {:dep-type dep-type :dep-id dep-id})
    (invalidate-dependents! base-storage cache-storage
                            #(find-fn % dep-id))))


;; === Default rules ===

(defn- register-rules!
  "Registers the same handler for multiple events on one entity type."
  [entity-type events handler]
  (doseq [event events]
    (register-rule! {:entity-type entity-type
                     :on-event event
                     :handler handler})))


(defn register-default-rules!
  "Registers the default invalidation rules for all graph entities.
   Call this once at startup or when creating a CachedStorage."
  []
  ;; :fn
  (register-rule!
    {:entity-type :fn
     :on-event :create
     :handler (fn [{:keys [base-storage cache-storage result]}]
                (rebuild-cache! base-storage cache-storage (:id result)))})

  (register-rule!
    {:entity-type :fn
     :on-event :update
     :handler (fn [{:keys [base-storage cache-storage id data old-record]}]
                (when (and (contains? data :fn-schema-id)
                           (not= (:fn-schema-id old-record) (:fn-schema-id data)))
                  (invalidate-fn-and-dependents! base-storage cache-storage id)))})

  (register-rule!
    {:entity-type :fn
     :on-event :delete
     :handler (fn [{:keys [base-storage cache-storage id]}]
                (cache/delete-cache! cache-storage id)
                (invalidate-fn-and-dependents! base-storage cache-storage id))})

  ;; :arg-value
  (register-rule!
    {:entity-type :arg-value
     :on-event :create
     :handler (fn [_ctx] nil)})

  (register-rules!
    :arg-value [:update :delete]
    (fn [{:keys [base-storage cache-storage id]}]
      (let [fn-args (sp/query-entities base-storage :fn-arg {:arg-value-id id})]
        (doseq [fn-arg fn-args]
          (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id fn-arg))))))

  ;; :fn-arg
  (register-rules!
    :fn-arg [:create :update]
    (fn [{:keys [base-storage cache-storage result]}]
      (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id result))))

  (register-rule!
    {:entity-type :fn-arg
     :on-event :delete
     :handler (fn [{:keys [base-storage cache-storage record]}]
                (when record
                  (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id record))))})

  ;; :fn-schema — update and delete both invalidate dependents
  (register-rules!
    :fn-schema [:update :delete]
    (fn [{:keys [base-storage cache-storage id]}]
      (invalidate-entity-dependents! base-storage cache-storage :fn-schema id)))

  ;; :arg-schema — update and delete both invalidate dependents
  (register-rules!
    :arg-schema [:update :delete]
    (fn [{:keys [base-storage cache-storage id]}]
      (invalidate-entity-dependents! base-storage cache-storage :arg-schema id)))

  ;; :call-site — all events invalidate dependents
  (register-rule!
    {:entity-type :call-site
     :on-event :create
     :handler (fn [{:keys [base-storage cache-storage result]}]
                (invalidate-entity-dependents! base-storage cache-storage :call-site (:id result)))})

  (register-rules!
    :call-site [:update :delete]
    (fn [{:keys [base-storage cache-storage id]}]
      (invalidate-entity-dependents! base-storage cache-storage :call-site id)))

  ;; :call-site-arg — invalidate parent call-site's dependents
  (register-rules!
    :call-site-arg [:create :update]
    (fn [{:keys [base-storage cache-storage result]}]
      (invalidate-entity-dependents! base-storage cache-storage :call-site (:call-site-id result))))

  (register-rule!
    {:entity-type :call-site-arg
     :on-event :delete
     :handler (fn [{:keys [base-storage cache-storage record]}]
                (when record
                  (invalidate-entity-dependents! base-storage cache-storage :call-site (:call-site-id record))))}))


;; Register default rules on namespace load (idempotent via defonce)
(defonce ^:private _defaults-registered
  (do (register-default-rules!) true))
