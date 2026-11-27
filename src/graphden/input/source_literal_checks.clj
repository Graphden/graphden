(ns graphden.input.source-literal-checks
  "Replay deferred reads of a type variable bound by an authored scoped literal."
  (:require
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]))


(defn- field-read
  [[scope path :as address] expected-type]
  {:id [address :value-type :field-read] :address address :scope scope :path path
   :kind :value-type :trigger :field-read :expected-type expected-type})


(defn- entry-at
  [graph [[_ path] :as address]]
  (get-in graph [:scopes path :bindings address]))


(defn- proof-at
  [graph address]
  ((juxt :status :reason) (get-in graph [:proofs address])))


(defn- checked-literal
  [before after {:keys [row address public-path]}]
  (when (and (uuid? (:id row)) (true? (:value-present row)) (= "value" (:expression-kind row)))
    (let [original (get-in before [:public-input :routes public-path :completion-type])
          entry (entry-at after address)
          actual (get-in entry [:reference :actual-type])
          proof (proof-at after address)]
      (when (and (types/type-var? original)
                 (= address (get-in before [:public-input :routes public-path :address]))
                 (nil? (entry-at before address)) (= (:id row) (:binding-id entry))
                 (= {:kind :value :value (:value row)} (:expression entry))
                 (= :literal (get-in entry [:reference :mode]))
                 (= actual (:expected-type entry) (literals/classify-literal (:value row))
                    (types/resolve (:substitution after) original))
                 (#{[:checked nil] [:deferred :runtime-type-obligation]} proof))
        [address {:type actual :deferred? (= :deferred (first proof))}]))))


(defn- replayed-read
  [before after literals {:keys [address expected-type] :as check}]
  (let [old (entry-at before address)
        new (entry-at after address)
        source (get-in old [:reference :source])
        type (:type (get literals source))]
    (if (and type (types/type-var? expected-type)
             (= check (field-read address expected-type))
             (= [:deferred :runtime-type-obligation] (proof-at before address))
             (uuid? (:binding-id old)) (= (:binding-id old) (:binding-id new))
             (= :projection (get-in old [:expression :kind]))
             (= source ((juxt :scope :path) (:expression old))) (= (:expression old) (:expression new))
             (= :projection (get-in old [:reference :mode]) (get-in new [:reference :mode]))
             (= source (get-in new [:reference :source]))
             (= expected-type (:expected-type old) (get-in old [:reference :actual-type]))
             (= type (:expected-type new) (get-in new [:reference :actual-type])
                (types/resolve (:substitution after) expected-type)))
      (case (proof-at after address)
        [:checked nil] nil
        [:deferred :runtime-type-obligation] (field-read address type)
        check)
      check)))


(defn replay
  "Preserve every check except a direct, unchanged projection of a newly
   supplied literal. Retain a deferred read at its exact resolved type and
   discharge a read only when the checker now proves it. Include the actual
   literal field's own deferred validation when required."
  [before after certificates]
  (let [literals (into {} (keep (partial checked-literal before after)) certificates)]
    (if (empty? literals)
      (set (:obligations before))
      (into (into #{} (keep (partial replayed-read before after literals)) (:obligations before))
            (keep (fn [[address {:keys [type deferred?]}]] (when deferred? (field-read address type)))) literals))))
