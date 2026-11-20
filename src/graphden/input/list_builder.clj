(ns graphden.input.list-builder
  "Ordered list fragments for the offline source migration. Item references
   retain their own occurrences and the consumer's existing delivery policy."
  (:require
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]))


(defn- reject!
  [code]
  (throw (ex-info "Cannot lower list construction" {:type :input/list-builder :code code})))


(defn- fragment-id
  [binding-id]
  (ids/uuid-v5 binding-id "list-builder"))


(defn- parse-item
  [binding-id name->id position form]
  (when-not (and (vector? form) (= 2 (count form)))
    (reject! :invalid-list-item))
  (let [[op payload] form
        id (ids/binding-list-item-id binding-id position)]
    (case op
      :value {:id id :position position :kind :value :value payload}
      :ref (let [ref-id (get name->id payload)]
             (when-not (and (keyword? payload) (uuid? ref-id))
               (reject! :unresolved-list-item-ref))
             {:id id :position position :kind :ref :fn-id ref-id})
      (reject! :invalid-list-item))))


(defn parse-fragment
  "Parse an explicit builder at a particular binding occurrence. A fragment's
   continuation belongs only to this occurrence, even for identical contents."
  [binding-id form name->id]
  (when-not (and (uuid? binding-id) (vector? form) (= 2 (count form)) (= :list-builder (first form)))
    (reject! :invalid-list-builder))
  (let [{:keys [items tail] :as spec} (second form)
        builder-id (fragment-id binding-id)]
    (when-not (and (map? spec) (= #{:items :tail} (set (keys spec)))
                   (vector? items) (contains? #{:open :closed} tail))
      (reject! :invalid-list-builder))
    {:binding-id binding-id :builder-id builder-id :tail tail
     :tail-id (ids/slot-id builder-id :tail)
     :items (into [] (map-indexed (partial parse-item binding-id name->id)) items)}))


(defn emit-fragment
  "Emit structured source. Every reference must resolve in the frozen index."
  [{:keys [tail items]} id->name]
  [:list-builder
   {:tail tail
    :items (mapv (fn [{:keys [kind value fn-id]}]
                   (case kind
                     :ref [:ref (or (get id->name fn-id) (reject! :unresolved-list-item-ref))]
                     :value [:value value]
                     (reject! :invalid-list-item)))
                 items)}])


(defn- source-item
  [name->id value]
  (cond
    (and (keyword? value) (contains? name->id value)) [:ref value]
    (not (map? value)) [:value value]
    (= #{:value} (set (keys value))) [:value (:value value)]
    (= #{:ref} (set (keys value))) [:ref (:ref value)]
    (some #(contains? value %) [:as :parent :parents :ref :value]) (reject! :unsupported-list-item-origin)
    :else [:value value]))


(defn compiler-items
  "Build the ordered item descriptors accepted by the existing JVM sequence
   compiler. It owns demand, delay delivery, and the call cache for refs.
   An authored literal map containing :as stays data on that compiler path."
  [{:keys [binding-id items]}]
  (when-not (and (uuid? binding-id) (vector? items)) (reject! :invalid-list-builder))
  (into []
        (map-indexed
          (fn [position {:keys [id kind value fn-id] :as item}]
            (when-not (and (= position (:position item)) (= id (ids/binding-list-item-id binding-id position))
                           (case kind
                             :ref (and (uuid? fn-id) (not (contains? item :value)))
                             :value (and (contains? item :value) (nil? fn-id))
                             false))
              (reject! :list-item-origin-mismatch))
            {:kind :binding-list-item :id id :binding-id binding-id :position position
             :value value :ref-fn-id fn-id :literal (when (and (map? value) (:as value)) true)}))
        items))


(defn- check-item!
  [actual expected]
  (when (and (map? (:value actual)) (:as (:value actual)) (not (:literal actual)))
    (reject! :positional-list-input-required))
  (when-not (migration/same-edn? expected (select-keys actual (keys expected)))
    (reject! :list-item-origin-mismatch)))


(defn source-form
  "Convert a legacy append's authored items to explicit expressions. The
   caller must establish that a bare vector was parsed as a list binding."
  [source name->id]
  (let [bare? (vector? source)
        items (if bare? source (:append source))]
    (when-not (and (vector? items)
                   (or bare? (and (map? source) (every? #{:append :closed} (keys source))
                                  (or (not (contains? source :closed)) (boolean? (:closed source))))))
      (reject! :unsupported-list-binding))
    [:list-builder {:items (mapv (partial source-item name->id) items)
                    :tail (if (:closed source) :closed :open)}]))


(defn source-fragment
  "Compare an original source append with its actual prepared binding and
   item rows. The caller resolves references in the definition's namespace.
   Bare vectors must have been classified as appends by the legacy parser."
  [{:keys [id fn-id slot-id list-append list-closed] :as binding} item-rows source name->id]
  (let [form (source-form source name->id)]
    (when-not (and (= :binding (:kind binding)) (uuid? fn-id) (uuid? slot-id)
                   (= id (ids/binding-id fn-id slot-id)) (true? list-append)
                   (not-any? some? (map binding [:value :ref-fn-id :resolver-fn-id :type-override-fn-id
                                                 :override-kind :required :terminal :description]))
                   (not (:value-present binding))
                   (= (boolean list-closed) (= :closed (get-in form [1 :tail]))))
      (reject! :unsupported-list-binding))
    (let [fragment (parse-fragment id form name->id)
          ordered (sort-by :position item-rows)]
      (when-not (= (count (:items fragment)) (count ordered)) (reject! :list-item-count-mismatch))
      (doseq [[actual expected] (map vector ordered (compiler-items fragment))]
        (check-item! actual expected))
      (assoc fragment :owner-id fn-id :target-id slot-id))))


(defn link-fragments
  "Lower a verified, single-parent chain in ancestor-first inheritance order.
   Descendants bind the previous builder's optional continuation at its use
   scope. A closed tail binds an explicit empty list in the generated builder.
   Item occurrences stay ordered inside their own builder."
  [fragments]
  (loop [remaining (seq fragments) path [] prior nil bindings []]
    (if-let [{:keys [owner-id target-id binding-id builder-id tail tail-id] :as current} (first remaining)]
      (do
        (when-not (and (uuid? owner-id) (uuid? target-id) (= binding-id (ids/binding-id owner-id target-id))
                       (= builder-id (fragment-id binding-id)) (= tail-id (ids/slot-id builder-id :tail))
                       (contains? #{:open :closed} tail) (not (some #{binding-id} path))
                       (or (nil? prior) (= target-id (:target-id prior))))
          (reject! :invalid-list-chain))
        (when (= :closed (:tail prior)) (reject! :closed-list-continuation))
        (let [edge (rows/normalize {:kind :input-binding :id binding-id :fn-id owner-id
                                    :scope-path path :target-path [(or (:tail-id prior) target-id)]
                                    :expression-kind "ref" :ref-fn-id builder-id :value-present false})
              bindings (cond-> (conj bindings edge)
                         (= :closed tail)
                         (conj (rows/normalize {:kind :input-binding :id (ids/binding-id builder-id tail-id)
                                                :fn-id builder-id :scope-path [] :target-path [tail-id]
                                                :expression-kind "value" :value [] :value-present true})))]
          (recur (next remaining) (conj path binding-id) current bindings)))
      {:bindings bindings :open-continuation (when (= :open (:tail prior)) [path [(:tail-id prior)]])})))
