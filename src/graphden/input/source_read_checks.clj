(ns graphden.input.source-read-checks
  "Static evidence for field-read checks changed by scoped bindings."
  (:require
    [graphden.input.context :as context]
    [graphden.types.core :as types]
    [graphden.types.validator :as validator]))


(defn field-read?
  "Match the frozen identity, address and trigger of a field-read obligation."
  [{:keys [id address scope path kind trigger]}]
  (and (= [:value-type :field-read] [kind trigger])
       (= address [scope path]) (= id [address kind trigger])))


(defn binding-at
  "Look up a binding by its complete scope and field path."
  [graph [[_ scope] _ :as address]]
  (get-in graph [:scopes scope :bindings address]))


(defn value-use
  "Return the use scope of an unchanged value reference in both graphs."
  [before after address]
  (let [[[owner path]] address
        old (binding-at before address)
        new (binding-at after address)
        {:keys [kind fn-id]} (:expression new)]
    (when (and (= :ref kind) (uuid? fn-id) (uuid? (:binding-id new))
               (= (select-keys old [:binding-id :origin-fn-id :expression])
                  (select-keys new [:binding-id :origin-fn-id :expression]))
               (= :value (get-in old [:reference :mode]) (get-in new [:reference :mode])))
      (some (fn [{:keys [to] :as edge}]
              (when (and (= address (:from edge)) (= :value (:mode edge)) (= fn-id (:target-fn-id edge))
                         (= (:binding-id new) (:binding-id edge)) (= [owner (conj path (:binding-id new))] to)
                         (some #{edge} (:dependencies before))
                         (= fn-id (get-in before [:scopes (second to) :fn-id]) (get-in after [:scopes (second to) :fn-id])))
                to)) (:dependencies after)))))


(defn installed-literal?
  "Match the certified literal row to its installed binding and expression."
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


(defn installed-scalar-type
  "The frozen scalar type of an unchanged source value at its installed row."
  [after {:keys [address row] :as certificate}]
  (let [type (get-in (binding-at after address) [:reference :actual-type])]
    (when (and (installed-literal? after certificate) (types/primitive? type) (not= :any type)
               (validates? type (:value row)))
      type)))


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
              [owner prefix] (value-use before after address)
              actual (get-in new [:reference :actual-type])]
          (and (vector? prefix) (= [:deferred :runtime-type-obligation]
                                   ((juxt :status :reason) (get-in before [:proofs address])))
               (= expected-type (:expected-type old) (:expected-type new))
               (= actual (get-in after [:scopes prefix :signature :return])) (some? actual)
               (not (types/type-any? types/type-var? actual)) (not (types/type-any? types/type-var? expected-type))
               (some (fn [{[[root path]] :address :as c}]
                       (and (= owner root) (<= (count prefix) (count path))
                            (= prefix (subvec path 0 (count prefix))) (installed-literal? after c))) certificates)
               (context/call-with-types snapshot #(types/subtype? actual expected-type)))))))


(defn- projection-read-types?
  [original projected certificate old current]
  (let [prior (:expected-type old)
        required (:expected-type current)
        previous (:reference original)
        reference (:reference projected)]
    (and (= old (assoc current :expected-type prior))
         (= (select-keys original [:binding-id :origin-fn-id :expression]) (select-keys projected [:binding-id :origin-fn-id :expression]))
         (if-let [open (:checked-open certificate)]
           (and (types/type-var? prior) (= prior (:original open) (:expected-type original) (:actual-type previous))
                (= :any required (:type open) (:expected-type projected) (:actual-type reference))
                (= (dissoc previous :actual-type) (dissoc reference :actual-type)))
           (and (= :any prior (:expected-type original) (:expected-type projected))
                (not= :any required) (= previous reference))))))


(defn- installed-projection-reference
  [after by-address projected expected-type]
  (let [{:keys [scope path kind]} (:expression projected)
        source [scope path]
        {:keys [row] :as certificate} (get by-address source)
        bound (binding-at after source)
        use-scope [(first scope) (conj (second scope) (:id row))]
        target (:ref-fn-id row)
        edge {:from source :to use-scope :binding-id (:id row) :target-fn-id target :mode :value}]
    (when (and (uuid? (:binding-id projected)) (= :projection kind (get-in projected [:reference :mode]))
               (= source (get-in projected [:reference :source]))
               certificate (uuid? target)
               (= (:id row) (:binding-id bound))
               (= :value (get-in bound [:reference :mode])) (= {:kind :ref :fn-id target} (:expression bound))
               (some #{edge} (:dependencies after))
               (= target (get-in after [:scopes (second use-scope) :fn-id]))
               (= expected-type (get-in bound [:reference :actual-type]) (get-in after [:scopes (second use-scope) :signature :return])))
      [target use-scope])))


(defn projected-reference
  "Match a deferred projection to its supplied value reference. The checker
   must corroborate both uses before transporting the read."
  [before after by-address old {:keys [address expected-type] :as current}]
  (let [original (binding-at before address)
        projected (binding-at after address)
        certificate (get by-address ((juxt :scope :path) (:expression projected)))
        reason (fn [graph] ((juxt :status :reason) (get-in graph [:proofs address])))]
    (when (and (field-read? current) (projection-read-types? original projected certificate old current)
               (= [:deferred :runtime-type-obligation] (reason before) (reason after)))
      (installed-projection-reference after by-address projected expected-type))))


(defn resolved-projection-reference
  "Identify the exact newly supplied reference that solved an open read."
  [before after by-address {:keys [address expected-type additional-types] :as check}]
  (let [original (binding-at before address)
        projected (binding-at after address)
        required (:expected-type projected)
        previous (:reference original)
        reference (:reference projected)]
    (when (and (field-read? check) (empty? additional-types)
               (= [:deferred :runtime-type-obligation] ((juxt :status :reason) (get-in before [:proofs address])))
               (= :checked (get-in after [:proofs address :status]))
               (types/type-var? expected-type)
               (= expected-type (:expected-type original) (:actual-type previous)
                  (types/resolve (:substitution before) expected-type))
               (= (select-keys original [:binding-id :origin-fn-id :expression])
                  (select-keys projected [:binding-id :origin-fn-id :expression]))
               (= (dissoc previous :actual-type) (dissoc reference :actual-type))
               (some? required) (not= :any required) (not (types/type-any? types/type-var? required))
               (= required (:actual-type reference) (types/resolve (:substitution after) expected-type)))
      (when-let [[target] (installed-projection-reference after by-address projected required)]
        [target required]))))
