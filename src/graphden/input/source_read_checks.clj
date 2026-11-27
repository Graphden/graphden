(ns graphden.input.source-read-checks
  "Static evidence for field-read checks changed by a scoped literal."
  (:require
    [graphden.input.context :as context]
    [graphden.types.core :as types]
    [graphden.types.validator :as validator]))


(defn- field-read?
  [{:keys [id address scope path kind trigger]}]
  (and (= [:value-type :field-read] [kind trigger])
       (= address [scope path]) (= id [address kind trigger])))


(defn- binding-at
  [graph [[_ scope] _ :as address]]
  (get-in graph [:scopes scope :bindings address]))


(defn- installed-literal?
  [after {:keys [address row]}]
  (let [binding (binding-at after address)]
    (and (= "value" (:expression-kind row)) (true? (:value-present row))
         (= (:id row) (:binding-id binding))
         (= {:kind :value :value (:value row)} (:expression binding))
         (= :literal (get-in binding [:reference :mode])))))


(defn- validates?
  [type value]
  (when (and (some? type) (not (types/type-any? types/type-var? type)))
    (try (validator/validate! type value) true
         (catch clojure.lang.ExceptionInfo _ false))))


(defn literal-read?
  "The installed literal itself passes this exact check. A concrete arm of
   a union is sufficient even if another arm still contains a variable."
  [snapshot after {:keys [address] :as certificate} {:keys [expected-type] :as check}]
  (when (and (= address (:address check)) (field-read? check) (installed-literal? after certificate)
             (= expected-type (:expected-type (binding-at after address))))
    (context/call-with-types snapshot
                             #(some (fn [arm] (validates? arm (get-in certificate [:row :value])))
                                    (or (types/union-members expected-type) [expected-type])))))


(defn discharged?
  "A previously deferred read may become statically checked. Keep its field,
   expression and required type, and verify the refined return of the same
   value reference on the path to an installed literal."
  [snapshot before after certificates {:keys [address expected-type] :as check}]
  (when (and (field-read? check) (= :checked (get-in after [:proofs address :status])))
    (or (some #(literal-read? snapshot after % check) certificates)
        (let [old (binding-at before address)
              new (binding-at after address)
              ref-id (get-in new [:expression :fn-id])
              [owner prefix] (some (fn [edge]
                                     (when (and (= :value (:mode edge)) (= address (:from edge))
                                                (= ref-id (:target-fn-id edge)) (= (:binding-id new) (:binding-id edge))
                                                (some #{edge} (:dependencies before)))
                                       (:to edge))) (:dependencies after))
              actual (get-in new [:reference :actual-type])]
          (and (some? ref-id) (vector? prefix) (= [:deferred :runtime-type-obligation]
                                                  ((juxt :status :reason) (get-in before [:proofs address])))
               (= (select-keys old [:binding-id :expression :expected-type])
                  (select-keys new [:binding-id :expression :expected-type]))
               (= expected-type (:expected-type new)) (= :value (get-in old [:reference :mode]) (get-in new [:reference :mode]))
               (= ref-id (get-in before [:scopes prefix :fn-id]) (get-in after [:scopes prefix :fn-id]))
               (= actual (get-in after [:scopes prefix :signature :return])) (some? actual)
               (not (types/type-any? types/type-var? actual)) (not (types/type-any? types/type-var? expected-type))
               (some (fn [{[[root path]] :address :as c}]
                       (and (= owner root) (<= (count prefix) (count path))
                            (= prefix (subvec path 0 (count prefix))) (installed-literal? after c))) certificates)
               (context/call-with-types snapshot #(types/subtype? actual expected-type)))))))


(defn projected-reference
  "Match an unchanged deferred projection from :any to the supplied value
   reference. The caller still verifies the target's independently inferred
   return before transporting the new check."
  [before after by-address old current]
  (let [{:keys [address expected-type]} current
        original (binding-at before address)
        projected (binding-at after address)
        {:keys [scope path kind]} (:expression projected)
        source [scope path]
        {:keys [row] :as certificate} (get by-address source)
        bound (binding-at after source)
        use-scope [(first scope) (conj (second scope) (:id row))]
        target (:ref-fn-id row)
        edge {:from source :to use-scope :binding-id (:id row) :target-fn-id target :mode :value}
        reason (fn [graph] ((juxt :status :reason) (get-in graph [:proofs address])))]
    (when (and (field-read? current) (= old (assoc current :expected-type :any)) (not= :any expected-type)
               (= [:deferred :runtime-type-obligation] (reason before) (reason after))
               (uuid? (:binding-id projected)) (= :projection kind (get-in projected [:reference :mode]))
               (= (select-keys original [:binding-id :origin-fn-id :expression :expected-type :reference])
                  (select-keys projected [:binding-id :origin-fn-id :expression :expected-type :reference]))
               (= :any (:expected-type projected)) certificate (uuid? target)
               (= (:id row) (:binding-id bound))
               (= :value (get-in bound [:reference :mode])) (= {:kind :ref :fn-id target} (:expression bound))
               (some #{edge} (:dependencies after))
               (= target (get-in after [:scopes (second use-scope) :fn-id]))
               (= expected-type (get-in bound [:reference :actual-type]) (get-in after [:scopes (second use-scope) :signature :return])))
      [target use-scope])))
