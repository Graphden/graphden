(ns graphden.malli-data-schema.coverage-test
  "Forms coverage tests for malli-data-schema.
   These tests exercise loops and branches multiple times to increase coverage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.malli-data-schema.test-helpers :refer [uuid]]
    [graphden.malli-data-schema.validators :as validators]))


(deftest many-entities-with-refs-forms-coverage-test
  (testing "schema with many entities referencing each other increases ref validation coverage"
    (let [schema (-> (mds/create-builder)
                     ;; Create a chain of entities referencing each other
                     (ds/add-entity :entity-a (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :entity-b (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}})
                     (ds/add-entity :entity-c (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}
                                     :ref-b {:uuid (uuid) :type :ref :ref-entity :entity-b}})
                     (ds/add-entity :entity-d (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}
                                     :ref-b {:uuid (uuid) :type :ref :ref-entity :entity-b}
                                     :ref-c {:uuid (uuid) :type :ref :ref-entity :entity-c}})
                     (ds/add-entity :entity-e (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :ref-a {:uuid (uuid) :type :ref :ref-entity :entity-a}
                                     :ref-b {:uuid (uuid) :type :ref :ref-entity :entity-b}
                                     :ref-c {:uuid (uuid) :type :ref :ref-entity :entity-c}
                                     :ref-d {:uuid (uuid) :type :ref :ref-entity :entity-d}})
                     ds/build)]
      (is (= 5 (count (ds/entities schema))))
      (is (= 5 (count (ds/entity-fields schema :entity-e)))))))


(deftest many-entities-with-enums-forms-coverage-test
  (testing "schema with many enum fields increases enum validation coverage"
    (let [schema (-> (mds/create-builder)
                     ;; Multiple enums
                     (ds/add-enum :status (uuid)
                                  [{:uuid (uuid) :value :active}
                                   {:uuid (uuid) :value :inactive}
                                   {:uuid (uuid) :value :pending}])
                     (ds/add-enum :priority (uuid)
                                  [{:uuid (uuid) :value :low}
                                   {:uuid (uuid) :value :medium}
                                   {:uuid (uuid) :value :high}
                                   {:uuid (uuid) :value :critical}])
                     (ds/add-enum :category (uuid)
                                  [{:uuid (uuid) :value :work}
                                   {:uuid (uuid) :value :personal}
                                   {:uuid (uuid) :value :other}])
                     ;; Entities with multiple enum fields
                     (ds/add-entity :task (uuid)
                                    {:title {:uuid (uuid) :type :text}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}
                                     :priority {:uuid (uuid) :type :enum :enum-name :priority}
                                     :category {:uuid (uuid) :type :enum :enum-name :category}})
                     (ds/add-entity :project (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}
                                     :priority {:uuid (uuid) :type :enum :enum-name :priority}})
                     (ds/add-entity :milestone (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}
                                     :category {:uuid (uuid) :type :enum :enum-name :category}})
                     ds/build)]
      (is (= 3 (count (ds/entities schema))))
      (is (= 3 (count (ds/enums schema)))))))


(deftest unions-with-refs-and-enums-forms-coverage-test
  (testing "unions with both ref and enum variants cover both case branches"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :type-a (uuid)
                                  [{:uuid (uuid) :value :opt1}
                                   {:uuid (uuid) :value :opt2}])
                     (ds/add-enum :type-b (uuid)
                                  [{:uuid (uuid) :value :val1}
                                   {:uuid (uuid) :value :val2}])
                     (ds/add-entity :target-x (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :target-y (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :target-z (uuid)
                                    {:name {:uuid (uuid) :type :text}})
                     ;; Entity with union containing both refs and enums
                     (ds/add-entity :mixed-union (uuid)
                                    {:field1 {:uuid (uuid)
                                              :type :union
                                              :variants [{:type :ref :ref-entity :target-x}
                                                         {:type :ref :ref-entity :target-y}
                                                         {:type :enum :enum-name :type-a}
                                                         {:type :text}]}
                                     :field2 {:uuid (uuid)
                                              :type :union
                                              :variants [{:type :ref :ref-entity :target-z}
                                                         {:type :enum :enum-name :type-b}
                                                         {:type :int}]}})
                     ;; Another entity with different union combinations
                     (ds/add-entity :another-mixed (uuid)
                                    {:data {:uuid (uuid)
                                            :type :union
                                            :variants [{:type :ref :ref-entity :target-x}
                                                       {:type :ref :ref-entity :target-y}
                                                       {:type :ref :ref-entity :target-z}
                                                       {:type :enum :enum-name :type-a}
                                                       {:type :enum :enum-name :type-b}]}})
                     ds/build)]
      (is (= 5 (count (ds/entities schema))))
      (is (= 4 (count (:variants (:field1 (ds/entity-fields schema :mixed-union))))))
      (is (= 5 (count (:variants (:data (ds/entity-fields schema :another-mixed)))))))))


(deftest many-fields-per-entity-forms-coverage-test
  (testing "entities with many fields increase field validation loop coverage"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :wide-entity (uuid)
                                    {:field01 {:uuid (uuid) :type :text}
                                     :field02 {:uuid (uuid) :type :text}
                                     :field03 {:uuid (uuid) :type :int}
                                     :field04 {:uuid (uuid) :type :int}
                                     :field05 {:uuid (uuid) :type :bool}
                                     :field06 {:uuid (uuid) :type :bool}
                                     :field07 {:uuid (uuid) :type :uuid}
                                     :field08 {:uuid (uuid) :type :uuid}
                                     :field09 {:uuid (uuid) :type :numeric}
                                     :field10 {:uuid (uuid) :type :numeric}
                                     :field11 {:uuid (uuid) :type :timestamptz}
                                     :field12 {:uuid (uuid) :type :jsonb}
                                     :field13 {:uuid (uuid) :type :bytes}
                                     :field14 {:uuid (uuid) :type :text :nullable? true}
                                     :field15 {:uuid (uuid) :type :int :nullable? true}})
                     ds/build)]
      (is (= 15 (count (ds/entity-fields schema :wide-entity)))))))


(deftest many-constraints-forms-coverage-test
  (testing "multiple constraints on multiple entities increase constraint validation coverage"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user (uuid)
                                    {:username {:uuid (uuid) :type :text}
                                     :email {:uuid (uuid) :type :text}
                                     :phone {:uuid (uuid) :type :text :nullable? true}
                                     :external-id {:uuid (uuid) :type :text :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:username]})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     (ds/add-constraint :user {:type :unique :fields [:phone]})
                     (ds/add-constraint :user {:type :unique :fields [:external-id]})
                     (ds/add-constraint :user {:type :unique :fields [:username :email]})
                     (ds/add-entity :order (uuid)
                                    {:order-num {:uuid (uuid) :type :text}
                                     :user-id {:uuid (uuid) :type :ref :ref-entity :user}
                                     :status {:uuid (uuid) :type :text}
                                     :date {:uuid (uuid) :type :timestamptz}})
                     (ds/add-constraint :order {:type :unique :fields [:order-num]})
                     (ds/add-constraint :order {:type :unique :fields [:user-id :order-num]})
                     (ds/add-constraint :order {:type :unique :fields [:user-id :date]})
                     ds/build)]
      (is (= 5 (count (ds/entity-constraints schema :user))))
      (is (= 3 (count (ds/entity-constraints schema :order)))))))


(deftest large-enum-forms-coverage-test
  (testing "enum with many values increases enum value validation coverage"
    (let [enum-values (vec (for [i (range 15)]
                             {:uuid (uuid) :value (keyword (str "value-" i))}))
          schema (-> (mds/create-builder)
                     (ds/add-enum :large-enum (uuid) enum-values)
                     (ds/add-entity :item (uuid)
                                    {:category {:uuid (uuid) :type :enum :enum-name :large-enum}})
                     ds/build)]
      (is (= 15 (count (:values (get (ds/enums schema) :large-enum))))))))


(deftest complex-schema-forms-coverage-test
  (testing "complex schema with all features exercises all validation paths"
    (let [schema (-> (mds/create-builder)
                     ;; Multiple enums with multiple values
                     (ds/add-enum :status (uuid)
                                  [{:uuid (uuid) :value :draft}
                                   {:uuid (uuid) :value :pending}
                                   {:uuid (uuid) :value :active}
                                   {:uuid (uuid) :value :archived}])
                     (ds/add-enum :role (uuid)
                                  [{:uuid (uuid) :value :admin}
                                   {:uuid (uuid) :value :editor}
                                   {:uuid (uuid) :value :viewer}])
                     ;; Base entities
                     (ds/add-entity :user (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :email {:uuid (uuid) :type :text}
                                     :role {:uuid (uuid) :type :enum :enum-name :role}
                                     :metadata {:uuid (uuid) :type :jsonb :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ;; Entity with refs
                     (ds/add-entity :document (uuid)
                                    {:title {:uuid (uuid) :type :text}
                                     :content {:uuid (uuid) :type :text}
                                     :author-id {:uuid (uuid) :type :ref :ref-entity :user}
                                     :reviewer-id {:uuid (uuid) :type :ref :ref-entity :user :nullable? true}
                                     :status {:uuid (uuid) :type :enum :enum-name :status}})
                     (ds/add-constraint :document {:type :unique :fields [:title :author-id]})
                     ;; Entity with union
                     (ds/add-entity :comment (uuid)
                                    {:text {:uuid (uuid) :type :text}
                                     :author-id {:uuid (uuid) :type :ref :ref-entity :user}
                                     :target {:uuid (uuid)
                                              :type :union
                                              :variants [{:type :ref :ref-entity :document}
                                                         {:type :ref :ref-entity :user}]}})
                     ;; Entity with self-reference
                     (ds/add-entity :folder (uuid)
                                    {:name {:uuid (uuid) :type :text}
                                     :parent-id {:uuid (uuid) :type :ref :ref-entity :folder :nullable? true}
                                     :owner-id {:uuid (uuid) :type :ref :ref-entity :user}})
                     (ds/add-constraint :folder {:type :unique :fields [:name :parent-id]})
                     ds/build)]
      (is (= 4 (count (ds/entities schema))))
      (is (= 2 (count (ds/enums schema))))
      (is (= 1 (count (ds/entity-constraints schema :user))))
      (is (= 1 (count (ds/entity-constraints schema :document))))
      (is (= 1 (count (ds/entity-constraints schema :folder)))))))


(deftest unknown-enum-reference-test
  (testing "field referencing non-existent enum throws descriptive error"
    ;; Exception thrown by validate-refs during build
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown enum reference"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:status {:uuid (uuid)
                                       :type :enum
                                       :enum-name :nonexistent-enum}})
              ds/build)))))


(deftest validate-single-ref-unknown-type-test
  (testing "validate-single-ref with unknown ref-type throws"
    (let [validate-single-ref-fn #'validators/validate-single-ref]
      ;; case without default throws IllegalArgumentException for unknown keys
      (is (thrown? IllegalArgumentException
            (validate-single-ref-fn :entity :field :unknown-type :ref-name {} {}))))))
