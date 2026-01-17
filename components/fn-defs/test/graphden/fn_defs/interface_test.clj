(ns graphden.fn-defs.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.fn-defs.interface :as fn-defs]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


(deftest sync-fns-to-storage!-test
  (testing "syncs fn definitions to storage"
    (let [storage (gsm/create-storage)
          ;; First sync base-fns so we have fn-schemas
          _ (registry/initialize-all! storage
                                      [{:test-base-fn
                                        {:args {:a :int :b :int}
                                         :return-type :int
                                         :impl (fn [_ _] 42)}}
                                       {:other-base-fn
                                        {:args {:x :fn}
                                         :return-type :any
                                         :impl (fn [_ _] nil)}}])
          ;; Define fns
          fn-defs-data [{:name :my-fn
                         :parent :test-base-fn
                         :args {:a 1 :b 2}}
                        {:name :wrapper-fn
                         :parent :other-base-fn
                         :args {:x :my-fn}}]
          ;; Sync
          result (fn-defs/sync-fns-to-storage! storage fn-defs-data)]

      (testing "returns map of fn-name -> fn-id"
        (is (map? result))
        (is (= #{:my-fn :wrapper-fn} (set (keys result))))
        (is (uuid? (:my-fn result)))
        (is (uuid? (:wrapper-fn result))))

      (testing "creates fn entities in storage"
        (let [my-fn-id (:my-fn result)
              wrapper-fn-id (:wrapper-fn result)]
          (is (some? (graphden.storage-protocol.interface/read-entity storage :fn my-fn-id)))
          (is (some? (graphden.storage-protocol.interface/read-entity storage :fn wrapper-fn-id)))))))

  (testing "validates definitions"
    (let [storage (gsm/create-storage)]
      (testing "throws on missing :name"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :name"
              (fn-defs/sync-fns-to-storage! storage [{:parent :foo}]))))

      (testing "throws on missing :parent"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :parent"
              (fn-defs/sync-fns-to-storage! storage [{:name :foo}]))))

      (testing "throws on duplicate names"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
              (fn-defs/sync-fns-to-storage! storage
                                            [{:name :foo :parent :bar}
                                             {:name :foo :parent :baz}]))))))

  (testing "throws on unresolved parent"
    (let [storage (gsm/create-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (fn-defs/sync-fns-to-storage! storage
                                          [{:name :foo :parent :nonexistent}])))))

  (testing "handles empty definitions"
    (let [storage (gsm/create-storage)
          result (fn-defs/sync-fns-to-storage! storage [])]
      (is (= {} result)))))


(deftest topological-sort-test
  (testing "sorts by dependencies and warns on wrong order"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-a {:args {} :return-type :int :impl (fn [_ _] 1)}
                                        :base-b {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])
          ;; Wrong order: wrapper depends on target, but defined first
          fn-defs-data [{:name :wrapper :parent :base-b :args {:ref :target}}
                        {:name :target :parent :base-a}]
          ;; Should still work (with warning)
          output (with-out-str
                   (fn-defs/sync-fns-to-storage! storage fn-defs-data))]

      (testing "prints warning about order"
        (is (clojure.string/includes? output "WARNING"))
        (is (clojure.string/includes? output "Suggested order"))))))


(deftest circular-dependency-test
  (testing "throws on circular dependencies"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])
          ;; A -> B -> A
          fn-defs-data [{:name :fn-a :parent :base-fn :args {:ref :fn-b}}
                        {:name :fn-b :parent :base-fn :args {:ref :fn-a}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (fn-defs/sync-fns-to-storage! storage fn-defs-data))))))
