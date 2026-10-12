(ns graphden.executor.structural-projection
  "Finite structural graph projection. Identity values retain source evidence;
   no graph implementation, resolver or dynamic callable is executed."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.packages.records.ids :as ids]))


(defrecord Identity
  [id])


(defn identity-value?
  [value]
  (instance? Identity value))


(def ^:private primitive-operations
  (into {}
        (map (fn [[path operation]] [(ids/fn-id path operation) operation]))
        [["core.logic" :const]
         ["core.collections" :list] ["core.collections" :zipmap]
         ["core.collections" :concat] ["core.collections" :get]
         ["core.collections" :into] ["core.collections" :merge]
         ["core.hof" :map]]))


(defn- reject!
  []
  (throw (ex-info "Graph cannot be structurally projected"
                  {:type :browser-plan/unsupported :reason :invalid-configuration})))


(defn- bounded
  [collection]
  (when-not (or (map? collection) (vector? collection)) (reject!))
  (when (> (count collection) 64) (reject!))
  collection)


(defn- unique-map
  [pairs]
  (reduce (fn [result pair]
            (when-not (and (vector? pair) (= 2 (count pair))) (reject!))
            (let [[key value] pair]
              ;; Construction is a supported subset of ordinary into: duplicate
              ;; keys refuse. Intentional overriding is explicit ordinary merge.
              (when (contains? result key) (reject!))
              (bounded (assoc result key value)))) {} pairs))


(defn- project*
  "Project checked, already-authorized source through finite constructors.
   `literal?` is the caller's fixed data contract, never client input. This
   function performs no storage reads. Each callback gets a fresh call frame;
   its cache cannot reuse another item's argument-dependent result."
  [graph fn-id literal? record-contracts]
  (let [index (lookups/build-lookups graph)
        steps (atom 0)
        const-id (ids/fn-id "core.logic" :const)
        const-value (ids/slot-id const-id :value)]
    ;; These mutually recursive readers share one global work/depth budget.
    ;; Frames isolate argument-dependent caches and active dependency paths.
    (letfn [(frame
              [names slots]
              {:names names :slots slots :cache (atom {}) :visiting (atom #{})})
            (literal
              [value]
              (when-not (literal? value) (reject!))
              value)
            (item
              [row current depth]
              (cond
                (:ref-fn-id row) (visit (:ref-fn-id row) current depth)
                (contains? row :value) (literal (:value row))
                :else (reject!)))
            (read-argument
              [{:keys [slot-id ext-name]} id current]
              (let [reader (lookups/effective-reader-slot-id id slot-id index)
                    by-slot (find (:slots current) reader)
                    by-name (find (:names current) ext-name)]
                (if-let [found (or by-slot by-name)] (val found) (reject!))))
            (argument
              [{:keys [kind value ref-id items slot-id] :as binding} id root current depth]
              (when (or (:is-fn binding) (:produces-callable? binding)) (reject!))
              (case kind
                :value (literal value)
                :ref (visit ref-id current depth)
                :seq (do
                       (when (> (count items) 64) (reject!))
                       (mapv #(item % current depth) items))
                :free (read-argument binding id current)
                :fn-ref (if (and (uuid? ref-id)
                                 (or (and (= root const-id) (= slot-id const-value))
                                     (contains? (get-in record-contracts [root :fields]) slot-id)))
                          (->Identity ref-id) (reject!))
                (reject!)))
            (callback
              [binding id current]
              (when-not (and (= :ref (:kind binding)) (:is-fn binding)
                             (not (:produces-callable? binding))) (reject!))
              (let [target (:ref-id binding)
                    params (renames/hof-lambda-params target (:slot-id binding) binding id index)]
                (when-not (= 1 (count params)) (reject!))
                {:target target :parameter (first params)
                 :translation (renames/build-hof-translation target params index)
                 :current current}))
            (mapped
              [closure collection depth]
              (when-not (vector? collection) (reject!))
              (bounded collection)
              (mapv (fn [value]
                      (let [{:keys [target parameter translation current]} closure
                            names (assoc (:names current) parameter value)
                            slots (reduce-kv (fn [result slot name]
                                               (if (= parameter name) (assoc result slot value) result))
                                             (:slots current) translation)]
                        (visit target (frame names slots) depth))) collection))
            (operation
              [op args depth]
              (case op
                :const (:value args)
                :record args
                :list (bounded (:items args))
                :get (let [{:keys [coll key default]} args]
                       (when-not (or (nil? coll) (map? coll) (vector? coll)) (reject!))
                       (get coll key default))
                :zipmap (let [{map-keys :keys map-values :vals} args]
                          (when-not (and (vector? map-keys) (vector? map-values)
                                         (= (count map-keys) (count map-values))) (reject!))
                          (bounded map-keys)
                          (unique-map (mapv vector map-keys map-values)))
                :concat (let [colls (:colls args)]
                          (when-not (and (vector? colls) (every? vector? colls)
                                         (<= (reduce + 0 (map count colls)) 64)) (reject!))
                          (into [] cat colls))
                :map (mapped (:func args) (:coll args) depth)
                :into (let [{:keys [to from]} args]
                        (when-not (and (map? to) (empty? to) (vector? from)) (reject!))
                        (unique-map from))
                :merge (let [maps (:maps args)]
                         (when-not (and (vector? maps) (every? #(or (nil? %) (map? %)) maps)) (reject!))
                         (reduce #(bounded (merge %1 %2)) {} maps))
                (reject!)))
            (visit
              [id current depth]
              (when (or (> (swap! steps inc) 4096) (>= depth 64)
                        (contains? @(:visiting current) id)) (reject!))
              (if-let [cached (find @(:cache current) id)]
                (val cached)
                (let [root-id (:id (lookups/root-fn id (:fn-map index) index))
                      record-contract (get record-contracts root-id)
                      op (or (get primitive-operations root-id) (when record-contract :record))
                      chain (lookups/inheritance-chain* id index)
                      classified (bindings/collect-bindings id index)]
                  (when (or (nil? op)
                            (seq (bindings/collect-env-bindings id index))
                            (some :resolver-fn-id (mapcat #(get-in index [:bindings-by-fn %]) chain))) (reject!))
                  (when record-contract
                    (let [fields (:fields record-contract)]
                      (when-not (and (map? fields)
                                     (= (count fields) (count classified))
                                     (= (set (keys fields)) (set (map :slot-id classified)))
                                     (every? (fn [{:keys [kind slot-id base-name ext-name]}]
                                               (let [{field-name :name type-id :type-fn-id} (get fields slot-id)]
                                                 (and (= :fn-ref kind)
                                                      (= field-name base-name ext-name)
                                                      (= ids/fn-ref-type-id type-id)
                                                      (= type-id (get-in index [:slot-map slot-id :type-fn-id])))))
                                             classified)) (reject!))))
                  (swap! (:visiting current) conj id)
                  (let [args (into {} (map (fn [binding]
                                             [(:base-name binding)
                                              (if (and (= op :map) (= :func (:base-name binding)))
                                                (callback binding id current)
                                                (argument binding id root-id current (inc depth)))])) classified)
                        result (operation op args (inc depth))]
                    (swap! (:visiting current) disj id)
                    (swap! (:cache current) assoc id result)
                    result))))]
      (visit fn-id (frame {} {}) 0))))


(defn project
  "Project already-authorized and checked immutable source. Optional record
   contracts describe fixed server-owned constructor fields, never grants,
   callbacks or request data. Only compiler-classified fn-ref fields emit
   identity values. Literal data is constrained by the caller's fixed contract."
  ([graph fn-id literal?]
   (project* graph fn-id literal? {}))
  ([graph fn-id literal? record-contracts]
   (project* graph fn-id literal? record-contracts)))
