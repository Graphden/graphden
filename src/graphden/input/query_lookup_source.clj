(ns graphden.input.query-lookup-source
  "Source and native authority of the original three-way Ring query lookup."
  (:require
    [graphden.executor.compile.surface :as surface]
    [graphden.input.legacy-literal-routes :as literal]
    [graphden.input.migration :as migration]
    [graphden.input.query-lookup-views :as query-views]
    [graphden.packages.records.ids :as ids]))


(def ^:private query-id (ids/fn-id "web.crud" :query-param))
(def ^:private return-id (ids/fn-id "core.refinements" :nullable-text))


(def ^:private chooser
  (let [native (ids/fn-id "core.logic" :coalesce)
        operands (map #(ids/slot-id native %) [:value :default])
        name (keyword (str "_coalesce-" (apply migration/derived-id :coalesce-projected-any native operands)))]
    {:namespace "web.crud" :name name :parent :core.logic/coalesce
     :args {:value {:as :lookup-first :type :any} :default {:as :lookup-next :type :any}}}))


(defn- closure-rows
  [graph root]
  (loop [todo [root] seen #{} result []]
    (if-let [id (peek todo)]
      (if (contains? seen id)
        (recur (pop todo) seen result)
        (let [owner (get-in graph [:fn-map id])
              members (get-in graph [:fn-slots-by-fn id])
              slots (map #(get-in graph [:slot-map (:slot-id %)]) members)
              bindings (get-in graph [:bindings-by-fn id])
              items (mapcat #(get-in graph [:items-by-binding (:id %)]) bindings)
              references (keep :ref-fn-id (concat bindings items))
              resolvers (keep :resolver-fn-id bindings)]
          (when (and owner (every? some? slots))
            (recur (into (pop todo) (concat (:parent-ids owner) references resolvers))
                   (conj seen id) (into result (concat [owner] members slots bindings items))))))
      result)))


(defn- shape-id
  [graph]
  (when-let [rows (closure-rows graph query-id)]
    (migration/fingerprint (group-by :kind (map #(dissoc % :description) rows)))))


(def ^:private branches
  [:_query-param-string-key :_query-param-keyword-key :_query-param-fallback])


(defn descriptor
  "Require the original resolved DAG and a direct literal writer. Return
   physical read identities in actual lookup order. The graph is trusted
   package preparation, never data from the proposed V2 module."
  [graph owner original]
  (let [source (get-in original [:args :param-name])
        value (if (string? source) source (:value source))]
    (when (and (= [query-id] (get-in graph [:fn-map owner :parent-ids]))
               (= return-id (get-in graph [:fn-map query-id :return-type-fn-id]))
               (nil? (get-in graph [:fn-map owner :return-type-fn-id]))
               (every? #{:name :namespace :parent :args :source-file :description :source-line} (keys original))
               (string? value) (or (= value source) (= {:value value} source))
               (contains? #{:query-param :web.crud/query-param} (:parent original))
               (contains? #{{} {:request {:as :request}}} (dissoc (:args original) :param-name))
               (= "01f40c0422914248794ee93d02af105ce5c7f8e0b595390190ab3f376e2c664a" (shape-id graph)))
      (let [writer (literal/descriptor graph owner :param-name)
            reads (mapv (fn [name]
                          (into {} (map (juxt :ext-name :slot-id))
                                (surface/surface-entries (ids/fn-id "web.crud" name) graph))) branches)
            names (mapv :param-name reads)
            requests (mapv :request reads)]
        (when (and (= value (:value writer)) (= #{:request} (:accepted writer))
                   (= [(first names)] (:written writer)) (= (subvec names 1) (:prefer-incoming writer))
                   (= 6 (count (set (concat names requests))))
                   (= (mapv (juxt :ext-name :slot-id) (:boundary writer))
                      (vec (mapcat (fn [r] [[:request (:request r)] [:param-name (:param-name r)]]) reads))))
          (assoc writer :reads reads :return-type-id return-id))))))


(defn- expand-owner
  [original {:keys [value]} views]
  (let [[lookup lookup-in to-keyword query-string] (map :ref views)
        request {:as :request :type :any}
        lookup-args (fn [coll key default] {:parent lookup :args {:coll coll :key key :default default}})
        name-input (lookup-args {:as :slots :type [:map :keyword :any]} {:value :param-name} {:value value})
        middleware (fn [key] {:parent lookup-in :args {:map request :path [{:value :query-params} key] :default nil}})
        choose (keyword "web.crud" (name (:name chooser)))]
    (assoc original :parent choose :return-type :nullable-text
           :args {:lookup-first (middleware {:value value})
                  :lookup-next {:parent choose
                                :args {:lookup-first (middleware {:parent to-keyword :args {:string name-input}})
                                       :lookup-next {:parent query-string
                                                     :args {:query-string (lookup-args request {:value :query-string} nil)
                                                            :param-name name-input}}}}})))


(defn expansion
  "Recompute the canonical proposal and its original-source witness using
   visible checked-in donor ABIs. A staged file supplies neither the four
   donor UUIDs nor their implementations. Helpers have derived identities."
  [graph owner original roots visible]
  (when-let [proof (descriptor graph owner original)]
    (when-let [views (query-views/views roots visible)]
      (let [chooser-id (ids/fn-id (:namespace chooser) (:name chooser))]
        (when (and (contains? visible owner)
                   (contains? visible (ids/fn-id "core.logic" :coalesce))
                   (not (contains? visible chooser-id)) (not (contains? roots chooser-id)))
          {:source (expand-owner original proof views) :proof proof :chooser chooser
           :views views})))))
