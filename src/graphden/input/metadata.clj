(ns graphden.input.metadata
  "Monotonic field policy overlays over the shared resolved descriptor."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.variables :as variables]
    [graphden.types.core :as types]))


(defn- prefix?
  [a b]
  (and (<= (count a) (count b)) (= a (subvec b 0 (count a)))))


(defn- validate-seals!
  [ctx rows]
  (let [ancestors (memoize #(set (inheritance/declaration-order ctx %)))]
    (doseq [seal (filter #(true? (:terminal %)) rows)
            row rows
            :let [seal-use (or (:origin-use-path seal) [])
                  row-use (or (:origin-use-path row) [])
                  later? (if (= seal-use row-use)
                           (and (not= (:origin-fn-id seal) (:origin-fn-id row))
                                (contains? (ancestors (:origin-fn-id row)) (:origin-fn-id seal)))
                           (prefix? row-use seal-use))]
            :when (and later?
                       (or (prefix? (:target-path seal) (:target-path row))
                           (prefix? (:target-path row) (:target-path seal))))]
      (context/reject! :terminal-seal {:binding-id (:id row) :seal-id (:id seal)}))))


(defn- narrow-type
  [ctx node row scope check-expression substitution]
  (if-let [type-id (:type-override-fn-id row)]
    (let [override (variables/descriptor-in-scope scope (descriptor/resolve-descriptor ctx type-id))
          expected (types/resolve substitution (:type node))
          actual (:type override)
          address [scope (:target-path row)]
          variables? (or (types/type-any? types/type-var? expected)
                         (types/type-any? types/type-var? actual))]
      (when (and (not variables?) (not (types/subtype? actual expected)))
        (context/reject! :type-widening {:address address}))
      {:node (merge (dissoc node :fields :alternatives) override)
       :proof (if check-expression
                (check-expression {:address address :expected expected :actual actual
                                   :expression {:kind :type} :substitution substitution})
                {:status :deferred :reason :type-checker-required :substitution substitution})})
    {:node node :proof {:status :checked :substitution substitution}}))


(defn apply-overlays
  "Apply declared and inherited metadata before checking occupancy. Required
   flags only strengthen; type overrides use existing subtype/unification
   primitives. Terminal rows lock overlapping paths against other origins."
  [ctx contract rows {:keys [scope declaration-order check-expression]}]
  (validate-seals! ctx rows)
  (let [rank (zipmap declaration-order (range))
        ordered (sort-by (juxt #(get rank (:origin-fn-id %) Long/MAX_VALUE)
                               #(count (:target-path %)) (comp str :id)) rows)]
    (reduce
      (fn [{:keys [contract substitution] :as result} row]
        (doseq [flag [:required :terminal]]
          (when-not (or (nil? (get row flag)) (boolean? (get row flag)))
            (context/reject! :invalid-field-policy {:binding-id (:id row) :policy flag})))
        (let [path (:target-path row)
              node (descriptor/node-at contract path)
              _ (when (and (:required node) (false? (:required row)))
                  (context/reject! :required-widening {:address [scope path]}))
              {:keys [node proof]} (narrow-type ctx node row scope check-expression substitution)
              _ (when-not (contains? #{:checked :deferred} (:status proof))
                  (context/reject! :invalid-checker-result {:address [scope path]}))
              updated (merge node (into {} (filter (comp some? val))
                                        (select-keys row [:required :terminal :description])))]
          (-> result
              (assoc :contract (descriptor/update-node contract path (constantly updated))
                     :substitution (:substitution proof))
              (cond-> (:type-override-fn-id row) (assoc-in [:proofs (:id row)] (assoc proof :address [scope path] :binding-id (:id row)))))))
      {:contract contract :substitution {} :proofs {}} ordered)))
