(ns graphden.input.inheritance
  "Compose declared input identities and authored bindings across parents.
   No referenced free-input lifting or persistent descriptor mutation occurs."
  (:require
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.descriptor :as descriptor]
    [graphden.types.core :as types]))


(defn declaration-order
  "Stable ancestor-first declaration IDs, with shared ancestors visited once."
  [ctx root]
  (let [visited (volatile! #{})
        ordered (volatile! [])]
    (letfn [(visit
              [id path]
              (when (or (contains? path id) (>= (count path) 128))
                (context/reject! :cyclic-inheritance {:fn-id id}))
              (when-not (contains? @visited id)
                (let [row (or (get-in ctx [:fns id])
                              (context/reject! :missing-parent {:fn-id id}))]
                  (doseq [parent (:parent-ids row)] (visit parent (conj path id))))
                (vswap! visited conj id)
                (vswap! ordered conj id)))]
      (visit root #{})
      @ordered)))


(defn- declaration
  [ctx fn-id]
  (let [row (get-in ctx [:fns fn-id])]
    (if (:input-type-fn-id row)
      (descriptor/resolve-contract ctx fn-id)
      ;; Record type parents supply their declared fields. This preserves
      ;; the existing record auto-builder/type-parent boundary without
      ;; inventing a second input type row for the same record.
      (if (and (not (:return-type-fn-id row)) (empty? (:parent-ids row))
               (contains? (:members ctx) fn-id))
        (input/input-contract (descriptor/resolve-descriptor ctx fn-id))
        (context/reject! :missing-input-contract {:fn-id fn-id})))))


(defn- add-field
  [{:keys [by-id by-name] :as acc} field]
  (let [id (:id field) name (:name field)]
    (when (and (contains? by-name name) (not= id (get by-name name)))
      (context/reject! :ambiguous-input-name {:field-id id}))
    (if-let [prior (get by-id id)]
      (do
        (when-not (= prior field)
          (context/reject! :inherited-field-conflict {:field-id id}))
        acc)
      (-> acc (assoc-in [:by-id id] field) (assoc-in [:by-name name] id)
          (update :fields conj field)))))


(defn- preserve-wrappers
  [declared body]
  (cond
    (types/refine-type? declared)
    [:refine (preserve-wrappers (types/refine-base declared) body) (types/refine-constraint declared)]
    (types/marker-type? declared)
    [(types/marker-tag declared) (preserve-wrappers (types/marker-inner declared) body)]
    (types/record-type? declared) body
    :else (context/reject! :ambiguous-record-input {})))


(defn declared-view
  "Returns the derived declared contract and origin-preserving authored rows
   visible through parent identity. A diamond visits a declaration once.
   Row :origin-fn-id retains ownership; :fn-id is rebased only in this analysis
   view. The returned rows must never be used as a migration/write payload."
  [ctx fn-id]
  (context/call-with-types
    ctx
    (fn []
      (let [order (declaration-order ctx fn-id)
            contracts (mapv #(declaration ctx %) order)
            fields (:fields (reduce add-field {:by-id {} :by-name {} :fields []}
                                    (mapcat :fields contracts)))
            structural (into {} (map (juxt :name :type)) fields)
            rich (reduce #(preserve-wrappers %2 %1) structural (distinct (map :type contracts)))
            visible (set order)
            bindings (into []
                           (comp (filter #(contains? visible (:fn-id %)))
                                 (map #(assoc % :origin-fn-id (:fn-id %) :fn-id fn-id)))
                           (sort-by (comp str :id) (vals (:bindings ctx))))]
        {:contract (input/input-contract {:type rich :fields fields
                                          :declaration-type-ids (mapv :type-id contracts)})
         :declaration-order order :bindings bindings}))))
