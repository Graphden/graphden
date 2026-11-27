(ns graphden.input.source-named-types
  "Frozen evidence for named source declarations used by the offline checker."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.types :as records]
    [graphden.packages.type-declaration :as declaration]
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.types.expansion :as expansion]))


(defn- reject!
  [code id]
  (throw (ex-info "Named type declaration lacks matching source and row evidence"
                  {:type :input/source-snapshot :code code :fn-id id})))


(defn- union-body
  [members id type-id]
  (when-not (and (vector? members) (seq members)
                 (every? (partial format/collection-type? #(or (types/primitive? %) (uuid? (type-id %)))) members))
    (reject! :named-union-evidence-required id))
  (into [:union] members))


(defn- normalize-form
  [kind form name->id module-ns]
  (let [resolve-ref #(parse/normalize-ref % name->id module-ns)]
    (case kind
      :list (if (and (keyword? form) (not (types/primitive? form))) (resolve-ref form) form)
      :union (if (vector? form) (mapv #(rebase/rewrite-type-references % resolve-ref) form) form)
      :map (if (map? form) (update-vals form #(rebase/rewrite-type-references % resolve-ref)) form)
      form)))


(defn- declaration-kind
  [definition]
  (some #(when (contains? definition %) %) [:union :map :type :refine :list]))


(defn- field-membership?
  [rows owner position field type-id]
  (let [slot-id (ids/slot-id owner field)
        member-id (ids/fn-slot-id owner slot-id)
        slot (get rows slot-id)
        member (get rows member-id)]
    (and (uuid? type-id) (= :slot (:kind slot)) (= (name field) (:name slot))
         (= type-id (:type-fn-id slot)) (true? (:required slot))
         (nil? (:description slot)) (nil? (:source-slot-id slot))
         (= :fn-slot (:kind member)) (= owner (:fn-id member))
         (= slot-id (:slot-id member)) (= position (:position member)))))


(defn- record-body
  [id form row children type-id]
  (let [by-id (into {} (map (juxt :id identity)) children)]
    (when-not (and (map? form) (seq form) (every? simple-keyword? (keys form))
                   (map? row) (nil? (:constraint row)) (empty? (:parent-ids row))
                   (not-any? row [:base-fn-id :element-fn-id :return-type-fn-id :input-type-fn-id
                                  :anonymous-hash :expects-effects :lambda-params])
                   (= (* 2 (count form)) (count children) (count by-id)))
      (reject! :named-record-evidence-required id))
    (doseq [[position [field type]] (map-indexed vector form)]
      (when-not (field-membership? by-id id position field (type-id type))
        (reject! :named-record-evidence-required id)))
    form))


(defn- refinement-body
  [id {:keys [base constraint] :as form} row children]
  (let [base-id (if (= [:list :any] base)
                  (ids/primitive-fn-id :sequence)
                  (get (ids/primitive-fn-ids) base))
        by-id (into {} (map (juxt :id identity)) children)]
    (when-not (and (or (= #{:base} (set (keys form)))
                       (and (= #{:base :constraint} (set (keys form))) (vector? constraint)))
                   (= 2 (count children) (count by-id)) (uuid? base-id)
                   (= base-id (:base-fn-id row)) (migration/same-edn? constraint (:constraint row))
                   (empty? (:parent-ids row)) (nil? (:return-type-fn-id row))
                   (nil? (:element-fn-id row)) (nil? (:anonymous-hash row))
                   (field-membership? by-id id 0 :value base-id))
      (reject! :named-refinement-evidence-required id))
    (declaration/type-row-alias-body {:refine form})))


(defn- list-body
  [id element row children type-id]
  (let [element-id (when (keyword? element) (type-id element))
        by-id (into {} (map (juxt :id identity)) children)]
    (when-not (and (uuid? element-id) (= element-id (:element-fn-id row))
                   (nil? (:constraint row)) (empty? (:parent-ids row))
                   (not-any? row [:base-fn-id :return-type-fn-id :input-type-fn-id
                                  :anonymous-hash :expects-effects :lambda-params])
                   (= 2 (count children) (count by-id))
                   (field-membership? by-id id 0 :items (ids/primitive-fn-id :sequence)))
      (reject! :named-list-evidence-required id))
    [:list element]))


(defn named-id-index
  "Index named types once per prepared source snapshot. Binding proposals
   may change arguments, while the names and declaration kinds stay fixed."
  [name->id declarations]
  (into {} (filter (fn [[_ id]] (declaration-kind (get declarations id)))) name->id))


(defn lookup
  "Build a memoized reader of verified named type declarations. Resolve only
   through the package's prepared names, checking the original and row by UUID."
  [{:keys [prepared declarations named-type-ids]} plan-entry]
  (let [name->id (:name->id prepared)
        named-ids (if named-type-ids @named-type-ids (named-id-index name->id declarations))
        type-id (fn [type]
                  (when (format/collection-type? #(or (types/primitive? %) (uuid? (get named-ids %))) type)
                    (let [id (if (keyword? type)
                               (or (get (ids/primitive-fn-ids) type) (get named-ids type))
                               (records/resolve-type-ref type named-ids))]
                      (when (and (uuid? id) (descriptor/stored-source-type? type id named-ids)) id))))]
    (memoize
      (fn [type]
        (let [id (get name->id type)
              definition (get declarations id)
              kind (declaration-kind definition)]
          (when kind
            (let [{:keys [verification v2 source]} (plan-entry id)
                  form (get-in v2 [:source kind])
                  normalized-form (normalize-form kind form name->id (:namespace definition))
                  [row & children :as rows] (:declaration-records v2)
                  body (case kind
                         :type (record-body id form row children type-id)
                         :refine (refinement-body id form row children)
                         :list (list-body id normalized-form row children type-id)
                         :union (union-body normalized-form id type-id)
                         :map (let [{:keys [key value]} normalized-form]
                                (when-not (and (map? normalized-form) (= #{:key :value} (set (keys normalized-form)))
                                               (every? #(and (keyword? %) (uuid? (type-id %))) [key value]))
                                  (reject! :named-dictionary-evidence-required id))
                                [:map key value]))
                  declared-name (:name definition)
                  ns-name (:namespace definition)
                  qualified (keyword ns-name (name declared-name))]
              (when-not (and (= :authored-source-equivalent verification) (contains? #{declared-name qualified} type)
                             (= id (ids/fn-id ns-name declared-name) (:id row)) (= :fn (:kind row))
                             (= ns-name (:namespace source) (:namespace-id row)) (= declared-name (:name source))
                             (= (name declared-name) (:name row))
                             (migration/same-edn? form (get source kind)) (migration/same-edn? normalized-form (get definition kind))
                             (case kind
                               :type (= (vec (keys form)) (vec (keys (:type source))) (vec (keys (:type definition))))
                               (:refine :list) true
                               (and (empty? children) (migration/same-edn? body (:constraint row)))))
                (reject! (case kind :type :named-record-evidence-required :union :named-union-evidence-required
                               :map :named-dictionary-evidence-required :refine :named-refinement-evidence-required
                               :list :named-list-evidence-required) id))
              {:id id :kind kind :body body :row row :rows rows
               :origin (merge {:namespace ns-name :name declared-name kind form} (select-keys source [:source-file]))})))))))


(defn- nested-type-names
  [type]
  (filter keyword? (tree-seq (comp seq shapes/child-types) shapes/child-types type)))


(defn- rule-aliases
  [frozen aliases]
  (reduce-kv (fn [frozen type-alias qualified]
               (let [id (get-in frozen [:alias-ids qualified])
                     prior (get-in frozen [:alias-ids type-alias])]
                 (when-not (and (simple-keyword? type-alias) (qualified-keyword? qualified) (uuid? id)
                                (or (nil? prior) (= id prior)))
                   (reject! :rule-type-source-required id))
                 (-> frozen (assoc-in [:aliases type-alias] (get-in frozen [:aliases qualified]))
                     (assoc-in [:alias-ids type-alias] id))))
             frozen aliases))


(defn- union-constraints
  [frozen]
  (keep (fn [[id origin]]
          (when (:union origin)
            (get-in frozen [:fns id :constraint])))
        (:type-origins frozen)))


(defn evidence
  ([entries resolve-named] (evidence entries resolve-named {}))
  ([entries resolve-named aliases] (evidence entries resolve-named aliases []))
  ([entries resolve-named aliases rule-types]
   (let [forms (concat rule-types
                       (mapcat (fn [{:keys [contract source configurations]}]
                                 (concat [(:return-type source)] (map :type (:fields contract))
                                         (keep :type (vals configurations))))
                               (vals entries)))]
     (loop [pending (into (vec (mapcat nested-type-names forms)) (vals aliases))
            seen #{}
            frozen {:aliases {} :alias-ids {} :fns {} :type-rows {} :type-origins {}}]
       (if-let [name (peek pending)]
         (if-let [{:keys [id body row rows origin]} (when-not (contains? seen name) (resolve-named name))]
           (recur (into (pop pending) (nested-type-names body)) (conj seen name)
                  (cond-> (-> frozen (assoc-in [:aliases name] body) (assoc-in [:alias-ids name] id)
                              (assoc-in [:fns id] row) (assoc-in [:type-rows id] rows) (assoc-in [:type-origins id] origin))
                    (or (map? body) (and (:refine origin) (nil? (:constraint row))))
                    (assoc-in [:type-bodies id] body)
                    (map? body)
                    (update :source-field-types merge
                            (into {} (for [field rows :when (= :slot (:kind field))
                                           :let [type (get body (keyword (:name field)))]
                                           :when (not (keyword? type))]
                                       [[(:id field) (:type-fn-id field)] type])))))
           (recur (pop pending) (conj seen name) frozen))
         (let [frozen (rule-aliases frozen aliases)]
           (context/call-with-types frozen
                                    #(doseq [[id origin] (:type-origins frozen)
                                             :when (or (:union origin) (:map origin))]
                                       (expansion/assert-finite! (get-in frozen [:fns id :constraint]))))
           frozen))))))


(defn checked-expression
  "Check known literal shapes against frozen unions and records. A collection
   hole cannot stand for an unrelated union member and a partial map literal
   cannot supply an absent required field. Preserve prior obligations."
  [ctx check]
  (let [origins (vals (:type-origins ctx))
        unions (context/call-with-types ctx
                                        #(into #{} (map types/resolve-alias) (union-constraints ctx)))
        records (into #{} (keep :type) origins)]
    (if (and (empty? records) (empty? unions))
      check
      (let [mismatch (memoize (fn [expected]
                                (condp types/type-any? expected
                                  (fn [type] (some #(= type (types/resolve-alias %)) records)) :named-record-mismatch
                                  unions :named-union-mismatch
                                  nil)))]
        (fn [{:keys [expression expected actual address] :as request}]
          (let [proof (check request)
                expected (types/resolve-alias expected)
                reason (mismatch expected)]
            (cond
              (or (nil? actual) (nil? reason)
                  (if (= :value (:kind expression))
                    (empty? (literals/diff-value-against-type (:value expression) expected))
                    (types/subtype? actual expected))) proof
              (and (not= :value (:kind expression))
                   (types/type-any? #(or (#{:any :jsonb} %) (types/type-var? %)) actual))
              (assoc proof :status :deferred :reason :runtime-type-obligation)
              :else (throw (ex-info "Input does not match the frozen source type"
                                    {:type :types/check-failed :reason reason :address address})))))))))
