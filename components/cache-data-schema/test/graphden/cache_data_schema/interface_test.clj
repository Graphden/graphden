(ns graphden.cache-data-schema.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-data-schema.interface :as cds]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.core :as malli]))


(deftest build-schema-test
  (testing "builds schema with graph and cache entities"
    (let [builder (malli/create-builder)
          schema (cds/build-schema builder)]

      (testing "includes graph entities"
        (is (some #{:fn} (ds/entities schema)))
        (is (some #{:fn-schema} (ds/entities schema)))
        (is (some #{:arg-schema} (ds/entities schema)))
        (is (some #{:arg-value} (ds/entities schema)))
        (is (some #{:fn-result-value} (ds/entities schema))))

      (testing "includes cache entities"
        (is (some #{:cached-fn} (ds/entities schema)))
        (is (some #{:cached-fn-schema} (ds/entities schema)))
        (is (some #{:cached-arg-schema} (ds/entities schema)))
        (is (some #{:cached-merged-arg} (ds/entities schema)))
        (is (some #{:cache-fn-dep} (ds/entities schema)))
        (is (some #{:cache-fn-schema-dep} (ds/entities schema)))
        (is (some #{:cache-arg-schema-dep} (ds/entities schema)))
        (is (some #{:cache-fn-result-value-dep} (ds/entities schema))))

      (testing "includes value-kind enum"
        (is (contains? (ds/enums schema) :value-kind))))))


(deftest cached-fn-entity-test
  (testing "cached-fn entity has correct fields"
    (let [schema (cds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :cached-fn)]
      (is (contains? fields :cache-id))
      (is (contains? fields :fn-id))
      (is (contains? fields :name))
      (is (contains? fields :fn-schema-id))

      (testing "cache-id is ref to fn"
        (is (= :ref (:type (:cache-id fields))))
        (is (= :fn (:ref-entity (:cache-id fields))))))))


(deftest cached-merged-arg-entity-test
  (testing "cached-merged-arg entity has union value field"
    (let [schema (cds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :cached-merged-arg)
          value-field (:value fields)]
      (is (= :union (:type value-field)))
      (is (vector? (:variants value-field)))
      (is (pos? (count (:variants value-field)))))))


(deftest cache-dep-entities-test
  (testing "dependency entities have ref-count field"
    (let [schema (cds/build-schema (malli/create-builder))]

      (testing "cache-fn-dep"
        (let [fields (ds/entity-fields schema :cache-fn-dep)]
          (is (= :int (:type (:ref-count fields))))
          (is (= :ref (:type (:cache-id fields))))
          (is (= :ref (:type (:dep-fn-id fields))))))

      (testing "cache-fn-schema-dep"
        (let [fields (ds/entity-fields schema :cache-fn-schema-dep)]
          (is (= :int (:type (:ref-count fields))))
          (is (= :ref (:type (:dep-fn-schema-id fields))))))

      (testing "cache-arg-schema-dep"
        (let [fields (ds/entity-fields schema :cache-arg-schema-dep)]
          (is (= :int (:type (:ref-count fields))))
          (is (= :ref (:type (:dep-arg-schema-id fields))))))

      (testing "cache-fn-result-value-dep"
        (let [fields (ds/entity-fields schema :cache-fn-result-value-dep)]
          (is (= :int (:type (:ref-count fields))))
          (is (= :ref (:type (:dep-fn-result-value-id fields)))))))))


(deftest cache-entities-constant-test
  (testing "cache-entities contains all cache entity names"
    (is (= #{:cached-fn
             :cached-fn-schema
             :cached-arg-schema
             :cached-merged-arg
             :cache-fn-dep
             :cache-fn-schema-dep
             :cache-arg-schema-dep
             :cache-fn-result-value-dep}
           cds/cache-entities))))


(deftest extend-builder-test
  (testing "extend-builder returns builder, not schema"
    (let [builder (-> (malli/create-builder)
                      (cds/extend-builder))]
      ;; Should be able to add more entities
      (is (satisfies? ds/DataSchemaBuilder builder)))))
