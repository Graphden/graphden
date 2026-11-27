(ns graphden.input.legacy-use
  "Resolve offline lexical binding rows with the existing executor selectors."
  (:require
    [graphden.executor.compile.bindings :as legacy]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]))


(defn- plain-origin?
  [{:keys [kind value ref-id]} origin]
  (and origin
       (not-any? origin [:type-override-fn-id :required :terminal :resolver-fn-id
                         :list-append :list-closed :override-kind :description])
       (case kind
         :ref (and (not (:value-present origin)) (= ref-id (:ref-fn-id origin)))
         :value (and (true? (:value-present origin)) (= value (:value origin))
                     (nil? (:ref-fn-id origin)))
         false)))


(defn- coalesce-default?
  "Match a fallback whose stored slot belongs to an already bound lookup.
   The legacy environment also routed it to coalesce's free default slot."
  [graph owner writer reader arguments]
  (let [base (ids/fn-id "core.logic" :coalesce)
        [value & other] (remove #{reader} arguments)
        target (:ref-id value)
        field (:env-name writer)]
    (and (= base (:id (lookups/root-fn owner (:fn-map graph) graph)))
         (= :default field (:base-name reader) (:ext-name reader))
         (= (ids/slot-id base :default) (:slot-id reader))
         (= :value (:base-name value) (:ext-name value))
         (= (ids/slot-id base :value) (:slot-id value))
         (= :ref (:kind writer) (:kind value)) (false? (:is-fn value))
         (empty? other) (uuid? target) (uuid? (:ref-id writer))
         (some #(and (= (:slot-id writer) (:slot-id %)) (= :value (:kind %)) (nil? (:value %)))
               (legacy/collect-bindings target graph))
         (not-any? (fn [callee] (contains? (set (renames/cache-projection-frees callee graph)) field))
                   [target (:ref-id writer)]))))


(defn select-binding
  "Select a legacy lexical initializer separately for each final function.
   Return the exact stored row identity and a use-local binding for the
   single supported reader. The caller must still verify typed replay,
   inheritance, and the emitted source before accepting a migration."
  [graph final-id]
  (let [[winner & other-winners] (legacy/collect-env-bindings final-id graph)
        {:keys [slot-id env-name kind is-fn]} winner]
    (when (and winner (empty? other-winners) (#{:ref :value} kind) (not is-fn))
      (let [arguments (legacy/collect-bindings final-id graph)
            [reader & other-readers] (filter #(and (= :free (:kind %)) (= env-name (:ext-name %))) arguments)
            lineage (lookups/inheritance-chain* final-id graph)
            sources (into [] (keep #(get-in graph [:binding-by-fn-slot [% slot-id]])) lineage)
            origin (first sources)
            reader-slot (:slot-id reader)
            routes (lookups/env-binding-route-ids final-id winner arguments
                                                  (lookups/fn-owned-slot-ids final-id graph) graph)]
        (when (and reader (empty? other-readers) (uuid? reader-slot) (not= reader-slot slot-id)
                   (= (hash-set slot-id reader-slot) (set routes))
                   (or (every? #(or (= :value (:kind %)) (= reader %)) arguments)
                       (coalesce-default? graph final-id winner reader arguments))
                   (plain-origin? winner origin)
                   (= (:id origin) (ids/binding-id (:fn-id origin) slot-id)))
          {:stored-slot slot-id
           :stored-owner (:fn-id origin)
           :declarations (mapv #(vector (:fn-id %) env-name) sources)
           :binding (rows/normalize
                      (merge (select-keys origin [:id :fn-id :org-id])
                             {:kind :input-binding :scope-path [] :target-path [reader-slot]}
                             (case kind
                               :ref {:expression-kind "ref" :value-present false :ref-fn-id (:ref-fn-id origin)}
                               :value {:expression-kind "value" :value-present true :value (:value origin)})))})))))
