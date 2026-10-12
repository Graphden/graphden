(ns graphden.executor.source-contract
  "Exact structural contracts for closed trusted graph compositions. Comparing
   an authorized snapshot grants no source access or execution capability."
  (:require
    [graphden.schema.graph.schema :as schema]
    [graphden.types.check.provenance :as provenance]))


(def ^:private fields
  {:fns (conj (disj (set (keys schema/fn-fields)) :description :org-id) :id)
   :slots #{:id :name :type-fn-id :required :source-slot-id}
   :fn-slots (conj (disj (set (keys schema/fn-slot-fields)) :description :org-id) :id)
   :bindings (conj (disj (set (keys schema/binding-fields)) :description :org-id) :id)
   :list-items (conj (disj (set (keys schema/binding-list-item-fields)) :org-id) :id)})


(defn- structural-row
  [kind row]
  ;; Storage supplies nullable columns omitted by the package parser. Keeping
  ;; every structural column also makes a newly introduced column fail closed.
  (into {} (map (fn [field] [field (get row field)])) (get fields kind)))


(defn- owned-rows
  [graph owner-ids]
  (let [junctions (filter #(contains? owner-ids (:fn-id %)) (:fn-slots graph))
        slot-ids (into #{} (map :slot-id) junctions)
        bindings (filter #(contains? owner-ids (:fn-id %)) (:bindings graph))
        binding-ids (into #{} (map :id) bindings)]
    {:fns (filter #(contains? owner-ids (:id %)) (:fns graph))
     :slots (filter #(contains? slot-ids (:id %)) (:slots graph))
     :fn-slots junctions
     :bindings bindings
     :list-items (filter #(contains? binding-ids (:binding-id %)) (:list-items graph))}))


(defn matches?
  "Compare every own structural row of exact trusted identities. The caller
   must independently prove package/public provenance and authorize source
   before supplying rows; this contract is not an authorization policy."
  [expected actual owner-ids]
  (let [expected (owned-rows expected owner-ids)
        actual (owned-rows actual owner-ids)]
    (and (seq owner-ids)
         (= owner-ids (set (map :id (:fns expected))))
         (= owner-ids (set (map :id (:fns actual))))
         (every? (fn [kind]
                   (let [normalize #(mapv (partial structural-row kind) (get % kind))
                         expected-rows (normalize expected)
                         actual-rows (normalize actual)]
                     (and (= (count expected-rows) (count actual-rows))
                          (= (set expected-rows) (set actual-rows)))))
                 (keys fields)))))


(defn fingerprints
  "Index exact composed-function source digests in linear grouping passes.
   Namespace identities must already be resolved; parser/storage metadata is
   ignored, while every own structural row and literal remains significant."
  [graph]
  (let [functions (group-by :id (:fns graph))
        slots (group-by :id (:slots graph))
        junctions (group-by :fn-id (:fn-slots graph))
        bindings (group-by :fn-id (:bindings graph))
        items (group-by :binding-id (:list-items graph))]
    (into {}
          (keep
            (fn [[id fn-rows]]
              (when (some #(seq (:parent-ids %)) fn-rows)
                (let [own-junctions (get junctions id)
                      own-bindings (get bindings id)
                      own {:fns fn-rows
                           :slots (mapcat #(get slots %) (set (map :slot-id own-junctions)))
                           :fn-slots own-junctions
                           :bindings own-bindings
                           :list-items (mapcat #(get items (:id %)) own-bindings)}]
                  [id (provenance/fingerprint-data
                        (into {}
                              (map (fn [kind]
                                     [kind (->> (get own kind)
                                                (map (partial structural-row kind))
                                                (sort-by (comp str :id))
                                                vec)]))
                              (keys fields)))]))))
          functions)))
