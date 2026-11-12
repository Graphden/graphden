(ns graphden.executor.browser-server-site
  "Bounded witnesses connecting a trusted exported entry to one server site."
  (:require
    [graphden.executor.compile.deps :as deps]
    [graphden.types.check.provenance :as provenance]))


(defn- predecessor-map
  [index roots]
  (loop [pending (into clojure.lang.PersistentQueue/EMPTY roots)
         predecessors (zipmap roots (repeat nil))]
    (if-let [id (peek pending)]
      (let [next-ids (remove #(contains? predecessors %)
                             (filter #(contains? (:fn-map index) %)
                                     (deps/forward-deps-of id index)))]
        (recur (into (pop pending) next-ids)
               (reduce #(assoc %1 %2 id) predecessors next-ids)))
      predecessors)))


(defn- path-to
  [predecessors target]
  (when (contains? predecessors target)
    (loop [id target path []]
      (when (< (count path) 128)
        (if id (recur (get predecessors id) (conj path id)) path)))))


(defn- with-ancestors
  [index ids]
  (loop [pending (set ids) seen #{}]
    (if-let [id (first pending)]
      (recur (into (disj pending id)
                   (remove seen (get-in index [:fn-map id :parent-ids])))
             (conj seen id))
      seen)))


(defn- canonical-rows
  [rows]
  (->> rows
       (reduce (fn [by-id row]
                 (if-let [previous (get by-id (:id row))]
                   (if (= (provenance/fingerprint-data previous) (provenance/fingerprint-data row))
                     by-id
                     (throw (ex-info "Conflicting server witness rows"
                                     {:type :browser-plan/unsupported :reason :server-binding-changed})))
                   (assoc by-id (:id row) row))) {})
       vals
       (sort-by (comp str :id))
       vec))


(defn project
  "Select complete owned rows of witness nodes, including slot declarations.
   The result is canonical raw evidence, not a standalone executable graph."
  [graph ids]
  (let [ids (set ids)
        fn-slots (filterv #(contains? ids (:fn-id %)) (:fn-slots graph))
        bindings (filterv #(contains? ids (:fn-id %)) (:bindings graph))
        slot-ids (set (map :slot-id fn-slots))
        binding-ids (set (map :id bindings))]
    (into {}
          (map (fn [[kind rows]] [kind (canonical-rows rows)]))
          {:fns (filterv #(contains? ids (:id %)) (:fns graph))
           :fn-slots fn-slots
           :bindings bindings
           :slots (filterv #(contains? slot-ids (:id %)) (:slots graph))
           :list-items (filterv #(contains? binding-ids (:binding-id %)) (:list-items graph))})))


(defn fingerprint
  [graph ids anchor target-fingerprint]
  (provenance/fingerprint-data {:graph (project graph ids)
                                :anchor anchor :target target-fingerprint}))


(defn- prepare-indexed
  [graph index predecessors site target anchor]
  (when-let [path (path-to predecessors (first site))]
    (let [ids (with-ancestors index path)]
      (when (and (<= (count ids) 128) (every? #(contains? (:fn-map index) %) ids))
        {:ids (vec (sort-by str ids)) :site site :target-fingerprint (:fingerprint target)
         :evidence (fingerprint graph ids anchor (:fingerprint target))}))))


(defn prepare-all
  "Build one finite predecessor index, then issue bounded selected-site proofs.
   Long/unknown paths retain full recapture; terminal identity rows never prove
   a client route through a private server implementation."
  [graph entries calls targets anchor]
  (if (not= 1 (count entries))
    {}
    (let [index (update (deps/index-graph graph) :fn-map
                        #(apply dissoc % (:identity-target-ids graph)))
          roots (filter #(contains? (:fn-map index) %) (vals entries))
          predecessors (predecessor-map index roots)]
      (into {} (keep (fn [[site target]]
                       (when-let [proof (prepare-indexed graph index predecessors site (get targets target) anchor)]
                         [site proof]))) calls))))


(defn prepare
  "Single-site form for witness verification and tests."
  [graph entries site target anchor]
  (get (prepare-all graph entries {site ::target} {::target target} anchor) site))
