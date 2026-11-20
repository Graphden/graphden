(ns graphden.input.source-named-types
  "Frozen evidence for named source declarations used by the offline checker."
  (:require
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]))


(defn- reject!
  [code id]
  (throw (ex-info "Named type declaration lacks matching source and row evidence"
                  {:type :input/source-snapshot :code code :fn-id id})))


(defn- union-body
  [members id]
  (when-not (and (vector? members) (seq members)
                 (every? (partial format/collection-type? types/primitive?) members))
    (reject! :named-union-evidence-required id))
  (into [:union] members))


(defn- record-body
  [id form row children]
  (let [by-id (into {} (map (juxt :id identity)) children)
        primitive-ids (ids/primitive-fn-ids)]
    (when-not (and (map? form) (seq form) (every? simple-keyword? (keys form))
                   (map? row) (nil? (:constraint row)) (empty? (:parent-ids row))
                   (not-any? row [:base-fn-id :element-fn-id :return-type-fn-id :input-type-fn-id
                                  :anonymous-hash :expects-effects :lambda-params])
                   (= (* 2 (count form)) (count children) (count by-id)))
      (reject! :named-record-evidence-required id))
    (doseq [[position [field type]] (map-indexed vector form)]
      (let [slot-id (ids/slot-id id field)
            member-id (ids/fn-slot-id id slot-id)
            slot (by-id slot-id)
            member (by-id member-id)]
        (when-not (and (contains? primitive-ids type)
                       (= :slot (:kind slot)) (= (name field) (:name slot))
                       (= (primitive-ids type) (:type-fn-id slot)) (true? (:required slot))
                       (nil? (:description slot)) (nil? (:source-slot-id slot))
                       (= :fn-slot (:kind member)) (= id (:fn-id member))
                       (= slot-id (:slot-id member)) (= position (:position member)))
          (reject! :named-record-evidence-required id))))
    form))


(defn lookup
  "Build a memoized reader of verified named type declarations. Resolve only
   through the package's prepared names, checking the original and row by UUID."
  [{:keys [prepared declarations]} plan-entry]
  (memoize
    (fn [type]
      (let [id (get (:name->id prepared) type)
            definition (get declarations id)
            kind (cond (:union definition) :union (:map definition) :map (:type definition) :type)]
        (when kind
          (let [{:keys [verification v2 source]} (plan-entry id)
                form (get-in v2 [:source kind])
                [row & children :as rows] (:declaration-records v2)
                body (case kind
                       :type (record-body id form row children)
                       :union (union-body form id)
                       :map (let [{:keys [key value]} form]
                              (when-not (every? types/primitive? [key value]) (reject! :named-dictionary-evidence-required id))
                              [:map key value]))
                declared-name (:name definition)
                ns-name (:namespace definition)
                qualified (keyword ns-name (name declared-name))]
            (when-not (and (= :authored-source-equivalent verification) (contains? #{declared-name qualified} type)
                           (= id (ids/fn-id ns-name declared-name) (:id row)) (= :fn (:kind row))
                           (= ns-name (:namespace source) (:namespace-id row)) (= declared-name (:name source))
                           (= (name declared-name) (:name row))
                           (migration/same-edn? form (get source kind)) (migration/same-edn? form (get definition kind))
                           (if (= :type kind)
                             (= (vec (keys form)) (vec (keys (:type source))) (vec (keys (:type definition))))
                             (and (empty? children) (migration/same-edn? body (:constraint row)))))
              (reject! (case kind :type :named-record-evidence-required :union :named-union-evidence-required
                             :map :named-dictionary-evidence-required) id))
            {:id id :kind kind :body body :row row :rows rows
             :origin (merge {:namespace ns-name :name declared-name kind form} (select-keys source [:source-file]))}))))))


(defn evidence
  [entries resolve-named]
  (let [forms (mapcat (fn [{:keys [contract source]}] (cons (:return-type source) (map :type (:fields contract)))) (vals entries))
        names (distinct (filter keyword? (mapcat #(if (vector? %) (tree-seq vector? rest %) [%]) forms)))]
    (reduce (fn [frozen name]
              (if-let [{:keys [id body row rows origin]} (resolve-named name)]
                (-> frozen (assoc-in [:aliases name] body) (assoc-in [:alias-ids name] id)
                    (assoc-in [:fns id] row) (assoc-in [:type-rows id] rows) (assoc-in [:type-origins id] origin))
                frozen))
            {:aliases {} :alias-ids {} :fns {} :type-rows {} :type-origins {}} names)))


(defn checked-expression
  "Check known literal shapes against frozen unions and records. A collection
   hole cannot stand for an unrelated union member and a partial map literal
   cannot supply an absent required field. Preserve prior obligations."
  [ctx check]
  (let [origins (vals (:type-origins ctx))
        unions (into #{} (comp (keep :union) (map types/make-union)) origins)
        records (into #{} (keep :type) origins)]
    (if (and (empty? records) (empty? unions))
      check
      (let [mismatch (memoize (fn [expected]
                                (cond (types/type-any? records expected) :named-record-mismatch
                                      (types/type-any? unions expected) :named-union-mismatch)))]
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
