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


(def ^:private request-slot (ids/slot-id query-id :request))
(def ^:private request-type (ids/fn-id "web.ring-adapter" :ring-request-shape))


(def ^:private holder
  (let [id (migration/derived-id :query-request query-id request-slot request-type)]
    {:namespace "web.crud" :name (keyword (str "_request-" id)) :parent :core.logic/const
     :args {:value {:as :request :type :ring-request-shape}}}))


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


(defn- reviewed-targets
  [graph]
  (when-let [rows (closure-rows graph query-id)]
    (when (= "01f40c0422914248794ee93d02af105ce5c7f8e0b595390190ab3f376e2c664a"
             (migration/fingerprint (group-by :kind (map #(dissoc % :description) rows))))
      (into #{} (comp (filter #(= :fn (:kind %))) (map :id)) rows))))


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
               (contains? #{{} {:request {:as :request}}} (dissoc (:args original) :param-name)))
      (let [targets (reviewed-targets graph)
            writer (literal/descriptor graph owner :param-name)
            reads (mapv (fn [name]
                          (into {} (map (juxt :ext-name :slot-id))
                                (surface/surface-entries (ids/fn-id "web.crud" name) graph))) branches)
            names (mapv :param-name reads)
            requests (mapv :request reads)]
        (when (and targets (= value (:value writer)) (= #{:request} (:accepted writer))
                   (= [(first names)] (:written writer)) (= (subvec names 1) (:prefer-incoming writer))
                   (= 6 (count (set (concat names requests))))
                   (= (mapv (juxt :ext-name :slot-id) (:boundary writer))
                      (vec (mapcat (fn [r] [[:request (:request r)] [:param-name (:param-name r)]]) reads))))
          (when (= {:id request-slot :type-fn-id request-type :required true :source-slot-id (first requests)}
                   (select-keys (get-in graph [:slot-map request-slot]) [:id :type-fn-id :required :source-slot-id]))
            (assoc writer :reads reads :request-type-id request-type :request-slot-id request-slot
                   :return-type-id return-id :original-targets (conj targets owner))))))))


(defn- expand-owner
  [original value]
  (let [choose (keyword "web.crud" (name (:name chooser)))
        request {:ref (keyword "web.crud" (name (:name holder))) :type :ring-request-shape}
        label {:value value}
        middleware (fn [key]
                     {:parent :core.collections/get-in
                      :args {:map request :path {:append [{:value :query-params} key] :closed true} :default nil}})]
    (assoc original :parent choose :return-type :nullable-text
           :args {:lookup-first (middleware label)
                  :lookup-next {:parent choose
                                :args {:lookup-first (middleware {:parent :core.strings/str-to-keyword :args {:string label}})
                                       :lookup-next {:parent :web.crud/_query-param-raw
                                                     :args {:query-string {:parent :core.collections/get
                                                                           :args {:coll request :key {:value :query-string} :default nil}}
                                                            :param-name label}}}}
                  :request {:type :ring-request-shape}})))


(defn expansion
  "Recompute the named V1 query from its original public contract. Both
   closed lookup paths were fixed in these exact branch invocations. The
   original parameter writer is local; the only public field is a required
   Ring request. Each get-in still evaluates its own ordered path."
  [graph owner original roots visible]
  (when-let [proof (descriptor graph owner original)]
    (when-let [donors (query-views/donors roots visible)]
      (let [helper-ids (mapv #(ids/fn-id (:namespace %) (:name %)) [chooser holder])]
        (when (and (every? visible (:original-targets proof)) (not-any? visible helper-ids) (not-any? roots helper-ids))
          {:source (expand-owner original (:value proof))
           :proof (update proof :original-targets into donors) :helpers [chooser holder] :donors donors})))))
