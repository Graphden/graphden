(ns graphden.schema-malli.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema-malli.core :as sut]
    [graphden.schema.interface :as schema]
    [integrant.core :as ig]))


(def test-schemas
  {:user [:map
          [:id :keyword]
          [:name :string]
          [:age :int]
          [:active :boolean]]
   :with-optional [:map
                   [:required :string]
                   [:optional {:optional true} :string]]
   :with-maybe [:map
                [:value [:maybe :string]]]
   :with-vector [:map
                 [:items [:vector :string]]]
   :with-sequential [:map
                     [:items [:sequential :string]]]
   :with-double [:map
                 [:score :double]]
   :with-any [:map
              [:data :any]]
   :nested-map [:map
                [:data :map]]
   :non-map-schema :string})


(defn- create-test-provider
  ([] (create-test-provider {}))
  ([opts]
   (sut/create-provider
     (merge {:schemas test-schemas
             :relations {:user->posts :one-to-many}
             :derived-queries #{:full-tree}}
            opts))))


;; === validate ===

(deftest validate-valid-data
  (let [provider (create-test-provider)]
    (testing "Valid data returns valid? true"
      (let [result (schema/validate* provider :user
                                     {:id :user-1
                                      :name "Alice"
                                      :age 30
                                      :active true})]
        (is (:valid? result))
        (is (nil? (:errors result)))))))


(deftest validate-invalid-data
  (let [provider (create-test-provider)]
    (testing "Invalid data returns errors"
      (let [result (schema/validate* provider :user
                                     {:id "not-keyword"
                                      :name 123
                                      :age "not-int"})]
        (is (not (:valid? result)))
        (is (some? (:errors result)))))))


(deftest validate-unknown-schema
  (let [provider (create-test-provider)]
    (testing "Unknown schema returns error"
      (let [result (schema/validate* provider :unknown {:foo :bar})]
        (is (not (:valid? result)))
        (is (= ["Unknown schema: :unknown"] (:errors result)))))))


;; === coerce ===

(deftest coerce-transforms-data
  (let [provider (create-test-provider)]
    (testing "Coerces string values to proper types"
      (let [result (schema/coerce* provider :user
                                   {:id :user-1
                                    :name "Alice"
                                    :age "30"
                                    :active "true"
                                    :extra-field "ignored"})]
        (is (= 30 (:age result)))
        (is (true? (:active result)))
        (is (nil? (:extra-field result)))))))


(deftest coerce-unknown-schema
  (let [provider (create-test-provider)]
    (testing "Unknown schema returns data unchanged"
      (let [data {:foo :bar}
            result (schema/coerce* provider :unknown data)]
        (is (= data result))))))


;; === get-fields ===

(deftest get-fields-basic-types
  (let [provider (create-test-provider)]
    (testing "Extracts field info correctly"
      (let [fields (schema/get-fields* provider :user)
            field-map (into {} (map (juxt :name identity) fields))]
        (is (= 4 (count fields)))
        (is (= :keyword (:type (:id field-map))))
        (is (= :string (:type (:name field-map))))
        (is (= :int (:type (:age field-map))))
        (is (= :boolean (:type (:active field-map))))))))


(deftest get-fields-optional
  (let [provider (create-test-provider)]
    (testing "Detects optional fields"
      (let [fields (schema/get-fields* provider :with-optional)
            field-map (into {} (map (juxt :name identity) fields))]
        (is (not (:optional? (:required field-map))))
        (is (:optional? (:optional field-map)))))))


(deftest get-fields-maybe
  (let [provider (create-test-provider)]
    (testing "Maybe fields are optional with inner type"
      (let [fields (schema/get-fields* provider :with-maybe)
            value-field (first fields)]
        (is (= :value (:name value-field)))
        (is (= :string (:type value-field)))
        (is (:optional? value-field))))))


(deftest get-fields-vector
  (let [provider (create-test-provider)]
    (testing "Vector type detection"
      (let [fields (schema/get-fields* provider :with-vector)
            items-field (first fields)]
        (is (= :vector (:type items-field)))))))


(deftest get-fields-double
  (let [provider (create-test-provider)]
    (testing "Double type detection"
      (let [fields (schema/get-fields* provider :with-double)
            score-field (first fields)]
        (is (= :double (:type score-field)))))))


(deftest get-fields-nested-map
  (let [provider (create-test-provider)]
    (testing "Nested map type detection"
      (let [fields (schema/get-fields* provider :nested-map)
            data-field (first fields)]
        (is (= :map (:type data-field)))))))


(deftest get-fields-unknown-schema
  (let [provider (create-test-provider)]
    (testing "Unknown schema returns nil"
      (is (nil? (schema/get-fields* provider :unknown))))))


;; === get-relations / get-derived-queries ===

(deftest get-relations-returns-configured
  (let [provider (create-test-provider)]
    (testing "Returns configured relations"
      (is (= {:user->posts :one-to-many}
             (schema/get-relations* provider))))))


(deftest get-derived-queries-returns-configured
  (let [provider (create-test-provider)]
    (testing "Returns configured derived queries"
      (is (= #{:full-tree}
             (schema/get-derived-queries* provider))))))


;; === create-provider defaults ===

(deftest create-provider-with-defaults
  (testing "Uses empty defaults for relations and derived-queries"
    (let [provider (sut/create-provider {:schemas {:test [:map]}})]
      (is (= {} (schema/get-relations* provider)))
      (is (= #{} (schema/get-derived-queries* provider))))))


;; === Additional type coverage ===

(deftest get-fields-sequential
  (let [provider (create-test-provider)]
    (testing "Sequential type maps to vector"
      (let [fields (schema/get-fields* provider :with-sequential)
            items-field (first fields)]
        (is (= :vector (:type items-field)))))))


(deftest get-fields-any
  (let [provider (create-test-provider)]
    (testing "Any type detection"
      (let [fields (schema/get-fields* provider :with-any)
            data-field (first fields)]
        (is (= :any (:type data-field)))))))


(deftest get-fields-non-map-schema
  (let [provider (create-test-provider)]
    (testing "Non-map schema returns nil"
      (is (nil? (schema/get-fields* provider :non-map-schema))))))


;; === Integrant ===

(deftest integrant-init-creates-provider
  (testing "ig/init-key creates provider"
    (let [provider (ig/init-key ::sut/provider
                                {:schemas {:test [:map [:id :keyword]]}
                                 :relations {:a :b}
                                 :derived-queries #{:q1}})]
      (is (instance? graphden.schema_malli.core.MalliSchemaProvider provider))
      (is (= {:a :b} (schema/get-relations* provider)))
      (is (= #{:q1} (schema/get-derived-queries* provider))))))
