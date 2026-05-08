(ns graphden.schema.graph.schema-test
  "Tests for graph schema with the slot/fn-slot/binding model.

   Six entities: ns, fn, slot, fn-slot, binding, binding-list-item.
   `:fn` is the unified entity for both functions and types — types
   are fn-rows specialised via `base-fn-id`/`element-fn-id`/`constraint`/
   `fn-slot` membership."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.fields.types :as ft]
    [graphden.schema.graph.schema :as graph]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]))


(def schema
  (graph/build-schema (mds/create-builder)))


(deftest entities-test
  (testing "schema contains the six core entities of the slot/binding model"
    (is (= #{:ns :fn :slot :fn-slot :binding :binding-list-item}
           (set (ds/entities schema))))))


(deftest enums-test
  (testing "schema contains value-kind and override-kind enums"
    (let [enums (ds/enums schema)]
      (is (contains? enums :value-kind))
      (is (contains? enums :override-kind))
      (is (= (into #{:null :any :fn} ft/supported-types)
             (set (keys (:values (get enums :value-kind))))))
      (is (= #{:fixed :default}
             (set (keys (:values (get enums :override-kind)))))))))


(deftest fn-entity-fields-test
  (testing "fn has expected fields (no :return-type — replaced by :return-type-fn-id FK)"
    (let [fields (ds/entity-fields schema :fn)]
      (is (= :text (get-in fields [:name :type])))
      (is (true? (get-in fields [:name :nullable?])))
      (is (= :ref (get-in fields [:namespace-id :type])))
      (is (= :ref-many (get-in fields [:parent-ids :type])))
      (is (= :fn (get-in fields [:parent-ids :ref-entity])))
      (is (= :text (get-in fields [:impl-hash :type])))
      (is (true? (get-in fields [:impl-hash :nullable?])))
      (is (= :jsonb (get-in fields [:constraint :type])))
      (is (= :ref (get-in fields [:base-fn-id :type])))
      (is (= :ref (get-in fields [:element-fn-id :type])))
      (is (= :ref (get-in fields [:return-type-fn-id :type])))
      (is (= :text (get-in fields [:anonymous-hash :type]))))))


(deftest slot-entity-fields-test
  (testing "slot has (name, type-fn-id, description)"
    (let [fields (ds/entity-fields schema :slot)]
      (is (= :text (get-in fields [:name :type])))
      (is (= :ref (get-in fields [:type-fn-id :type])))
      (is (= :fn (get-in fields [:type-fn-id :ref-entity])))
      (is (true? (get-in fields [:description :nullable?]))))))


(deftest fn-slot-junction-fields-test
  (testing "fn-slot junction has (fn-id, slot-id, position)"
    (let [fields (ds/entity-fields schema :fn-slot)]
      (is (= :ref (get-in fields [:fn-id :type])))
      (is (= :fn (get-in fields [:fn-id :ref-entity])))
      (is (= :ref (get-in fields [:slot-id :type])))
      (is (= :slot (get-in fields [:slot-id :ref-entity])))
      (is (= :int (get-in fields [:position :type]))))))


(deftest binding-entity-fields-test
  (testing "binding carries override-kind enum + per-level metadata"
    (let [fields (ds/entity-fields schema :binding)]
      (is (= :ref (get-in fields [:fn-id :type])))
      (is (= :ref (get-in fields [:slot-id :type])))
      (is (= :jsonb (get-in fields [:value :type])))
      (is (true? (get-in fields [:value :nullable?])))
      (is (= :ref (get-in fields [:ref-fn-id :type])))
      (is (true? (get-in fields [:ref-fn-id :nullable?])))
      (is (= :enum (get-in fields [:override-kind :type])))
      (is (= :override-kind (get-in fields [:override-kind :enum-name])))
      ;; `:rename-to` was retired in Phase 6e — replaced by
      ;; slot.source-slot-id FK. Field no longer present in spec.
      (is (nil? (get fields :rename-to)))
      (is (= :ref (get-in fields [:type-override-fn-id :type])))
      (is (= :bool (get-in fields [:terminal :type])))
      (is (= :bool (get-in fields [:list-append :type])))
      (is (= :bool (get-in fields [:list-closed :type]))))))


(deftest binding-list-item-fields-test
  (testing "binding-list-item has (binding-id, position, value/ref, literal flag)"
    (let [fields (ds/entity-fields schema :binding-list-item)]
      (is (= :ref (get-in fields [:binding-id :type])))
      (is (= :int (get-in fields [:position :type])))
      (is (= :jsonb (get-in fields [:value :type])))
      (is (= :ref (get-in fields [:ref-fn-id :type])))
      (is (= :bool (get-in fields [:literal :type]))))))


(deftest validation-test
  (testing "valid base-fn (impl-hash set, no parent-ids, no role markers)"
    (is (nil? (ds/validate-entity schema :fn
                                  {:id (random-uuid)
                                   :name "add"
                                   :parent-ids nil
                                   :impl-hash "abc123"}))))

  (testing "valid composed fn-def (parent-ids non-empty, no impl-hash)"
    (is (nil? (ds/validate-entity schema :fn
                                  {:id (random-uuid)
                                   :name "my-add"
                                   :parent-ids [(random-uuid)]}))))

  (testing "valid refinement-type (base-fn-id + constraint, no parent-ids, no impl-hash)"
    ;; The constraint column is jsonb — the in-memory rich-type uses
    ;; keyword-headed vectors like `[:> 0]`, but at the storage layer
    ;; those serialise via the postgres codec. The schema-level
    ;; validator just checks it's well-formed JSON-compatible data.
    (is (nil? (ds/validate-entity schema :fn
                                  {:id (random-uuid)
                                   :name "positive-int"
                                   :base-fn-id (random-uuid)
                                   :constraint {"op" ">" "rhs" 0}}))))

  (testing "valid slot (name + type-fn-id required)"
    (is (nil? (ds/validate-entity schema :slot
                                  {:id (random-uuid)
                                   :name "x"
                                   :type-fn-id (random-uuid)}))))

  (testing "invalid slot — missing type-fn-id"
    (let [result (ds/validate-entity schema :slot
                                     {:id (random-uuid)
                                      :name "x"})]
      (is (some? result))
      (is (contains? (:errors result) :type-fn-id))))

  (testing "valid fn-slot junction"
    (is (nil? (ds/validate-entity schema :fn-slot
                                  {:id (random-uuid)
                                   :fn-id (random-uuid)
                                   :slot-id (random-uuid)
                                   :position 0}))))

  (testing "valid binding with literal value"
    (is (nil? (ds/validate-entity schema :binding
                                  {:id (random-uuid)
                                   :fn-id (random-uuid)
                                   :slot-id (random-uuid)
                                   :value 42
                                   :override-kind :fixed}))))

  (testing "valid binding-list-item"
    (is (nil? (ds/validate-entity schema :binding-list-item
                                  {:id (random-uuid)
                                   :binding-id (random-uuid)
                                   :position 0
                                   :value "item-1"})))))
