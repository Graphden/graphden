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


(defn lookup
  "Build a memoized reader of verified named type declarations. Resolve only
   through the package's prepared names, checking the original and row by UUID."
  [{:keys [prepared declarations]} plan-entry]
  (memoize
    (fn [type]
      (let [id (get (:name->id prepared) type)
            definition (get declarations id)
            kind (cond (:union definition) :union (:map definition) :map)]
        (when kind
          (let [{:keys [verification v2 source]} (plan-entry id)
                form (get-in v2 [:source kind])
                body (case kind
                       :union (union-body form id)
                       :map (let [{:keys [key value]} form]
                              (when-not (every? types/primitive? [key value]) (reject! :named-dictionary-evidence-required id))
                              [:map key value]))
                [row & more] (:declaration-records v2)
                declared-name (:name definition)
                ns-name (:namespace definition)
                qualified (keyword ns-name (name declared-name))]
            (when-not (and (= :authored-source-equivalent verification) (contains? #{declared-name qualified} type)
                           (= id (ids/fn-id ns-name declared-name) (:id row)) (= :fn (:kind row)) (empty? more)
                           (= ns-name (:namespace source) (:namespace-id row)) (= declared-name (:name source))
                           (= (name declared-name) (:name row))
                           (migration/same-edn? form (get source kind)) (migration/same-edn? form (get definition kind))
                           (migration/same-edn? body (:constraint row)))
              (reject! (if (= :union kind) :named-union-evidence-required :named-dictionary-evidence-required) id))
            {:id id :kind kind :body body :row row
             :origin (merge {:namespace ns-name :name declared-name kind form} (select-keys source [:source-file]))}))))))


(defn evidence
  [entries resolve-named]
  (let [forms (mapcat (fn [{:keys [contract source]}] (cons (:return-type source) (map :type (:fields contract)))) (vals entries))
        names (distinct (filter keyword? (mapcat #(if (vector? %) (tree-seq vector? rest %) [%]) forms)))]
    (reduce (fn [frozen name]
              (if-let [{:keys [id body row origin]} (resolve-named name)]
                (-> frozen (assoc-in [:aliases name] body) (assoc-in [:alias-ids name] id)
                    (assoc-in [:fns id] row) (assoc-in [:type-origins id] origin))
                frozen))
            {:aliases {} :alias-ids {} :fns {} :type-origins {}} names)))


(defn checked-expression
  "For a frozen source union, a hole inside one collection member cannot
   authorize an unrelated outer shape. Keep the existing directional proof
   and its obligations; check known literals against the actual members."
  [ctx check]
  (let [unions (into #{} (comp (map :union) (filter some?) (map types/make-union)) (vals (:type-origins ctx)))]
    (if (empty? unions)
      check
      (let [affected? (memoize (partial types/type-any? unions))]
        (fn [{:keys [expression expected actual address] :as request}]
          (let [proof (check request)
                expected (types/resolve-alias expected)]
            (cond
              (or (nil? actual) (not (affected? expected))
                  (if (= :value (:kind expression))
                    (empty? (literals/diff-value-against-type (:value expression) expected))
                    (types/subtype? actual expected))) proof
              (and (not= :value (:kind expression))
                   (types/type-any? #(or (#{:any :jsonb} %) (types/type-var? %)) actual))
              (assoc proof :status :deferred :reason :runtime-type-obligation)
              :else (throw (ex-info "Input is outside the frozen source union"
                                    {:type :types/check-failed :reason :named-union-mismatch :address address})))))))))
