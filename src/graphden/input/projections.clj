(ns graphden.input.projections
  "Typed field routes and demand propagation over the derived use-scope graph."
  (:require
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.descriptor :as descriptor]))


(defn- prefix?
  [a b]
  (and (<= (count a) (count b)) (= a (subvec b 0 (count a)))))


(defn- contract-at
  [graph [root path]]
  (or (when (= root (:fn-id graph))
        (get-in graph [:scopes path :configured-contract]))
      (context/reject! :unknown-projection-scope {:scope [root path]})))


(defn- entries
  [graph]
  (into {} (mapcat (comp :bindings val)) (:scopes graph)))


(defn- source-bindings
  [all [scope path]]
  (into #{}
        (keep (fn [[[other-scope other-path :as address] _]]
                (when (and (= scope other-scope)
                           (or (prefix? path other-path) (prefix? other-path path))) address))) all))


(defn- reject-value-cycles!
  [graph routes]
  (let [all (into (entries graph) (map #(vector (:from %) {})) (:input-routes graph))
        projection-edges (into {} (map (fn [{:keys [from source]}]
                                         [from (source-bindings all source)])) routes)
        edges (reduce (fn [edges {:keys [from to]}]
                        (update edges from (fnil into #{})
                                (filter #(= to (first %)) (keys all))))
                      projection-edges (:dependencies graph))
        roots (reverse (sort-by context/address-sort-key (keys edges)))]
    (loop [pending (mapv #(vector % false) roots) active #{} visited #{}]
      (when-let [[address exit?] (peek pending)]
        (cond
          exit? (recur (pop pending) (disj active address) (conj visited address))
          (contains? visited address) (recur (pop pending) active visited)
          (contains? active address) (context/reject! :projection-cycle {:address address})
          :else
          (recur (into (conj (pop pending) [address true])
                       (map #(vector % false))
                       (reverse (sort-by context/address-sort-key (get edges address))))
                 (conj active address) visited))))))


(defn prepare
  "Resolve every projection to an existing typed source address. Dependency
   cycles include paths that cross evaluated reference uses. No source value
   is read and no shared declaration is modified."
  [graph]
  (let [routes (into []
                     (keep (fn [[address {:keys [expression]}]]
                             (when (= :projection (:kind expression))
                               (let [source [(:scope expression) (:path expression)]
                                     source-contract (contract-at graph (first source))
                                     target (descriptor/node-at (contract-at graph (first address))
                                                                (second address))]
                                 {:from address :source source
                                  :actual-type (descriptor/read-type-at source-contract (second source))
                                  :required? (or (empty? (second address)) (:required target))}))))
                     (sort-by (comp context/address-sort-key key) (entries graph)))]
    (let [input-routes (mapv (fn [{:keys [source] :as route}]
                               (assoc route :actual-type
                                      (descriptor/read-type-at (contract-at graph (first source)) (second source))))
                             (:input-routes graph))
          graph (assoc graph :input-routes input-routes)]
      (reject-value-cycles! graph (concat routes input-routes))
      (reduce (fn [result {:keys [from source actual-type]}]
                (assoc-in result [:scopes (second (first from)) :bindings from :reference]
                          {:mode :projection :actual-type actual-type :source source}))
              (assoc graph :projections routes) routes))))


(defn- required-sources
  [routes]
  (loop [pending (mapv :source (filter :required? routes)) seen #{}]
    (if-let [[scope path :as address] (peek pending)]
      (if (contains? seen address)
        (recur (pop pending) seen)
        (let [next-sources (keep (fn [{[target-scope target-path] :from :keys [source]}]
                                   (when (and (= scope target-scope)
                                              (or (prefix? path target-path) (prefix? target-path path)))
                                     source)) routes)]
          (recur (into (pop pending) next-sources) (conj seen address))))
      seen)))


(defn apply-demands
  "A required projected value requires its source, including optional record
   ancestors. Existing source occupancy still wins. Chained routes propagate
   demand without marking an unrelated reference use required."
  [graph]
  (let [demands (group-by (comp second first) (required-sources (concat (:projections graph) (:input-routes graph))))
        scopes (into {}
                     (map (fn [[path node]]
                            (let [contract (reduce (fn [contract [_ field-path]]
                                                     (reduce (fn [contract length]
                                                               (descriptor/update-node
                                                                 contract (subvec field-path 0 length)
                                                                 #(assoc % :required true)))
                                                             contract (range 1 (inc (count field-path)))))
                                                   (:configured-contract node) (get demands path))
                                  scope [(:fn-id graph) path]
                                  remaining (input/remaining-input contract (:bindings node) scope)
                                  visible? (fn [field-path]
                                             (not-any? (fn [[hidden-scope hidden-path]]
                                                         (and (= scope hidden-scope) (prefix? hidden-path field-path)))
                                                       (:hidden-input-addresses graph)))]
                              [path (assoc node :remaining-contract (:descriptor remaining)
                                           :required-addresses (into [] (comp (filter visible?) (map #(vector scope %))) (:missing remaining)))])))
                     (:scopes graph))]
    (assoc graph :scopes scopes
           :required-addresses (into [] (mapcat (comp :required-addresses val))
                                     (sort-by (comp (partial mapv str) key) scopes)))))
