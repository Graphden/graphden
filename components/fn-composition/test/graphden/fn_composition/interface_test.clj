(ns graphden.fn-composition.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.fn-composition.core :as core]
    [graphden.fn-composition.interface :as fn-composition]
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
          fn-composition-data [{:name :my-fn
                                :parent :test-base-fn
                                :args {:a 1 :b 2}}
                               {:name :wrapper-fn
                                :parent :other-base-fn
                                :args {:x :my-fn}}]
          ;; Sync
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)]

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
              (fn-composition/sync-fns-to-storage! storage [{:parent :foo}]))))

      (testing "throws on missing :parent"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :parent"
              (fn-composition/sync-fns-to-storage! storage [{:name :foo}]))))

      (testing "throws on duplicate names"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
              (fn-composition/sync-fns-to-storage! storage
                                                   [{:name :foo :parent :bar}
                                                    {:name :foo :parent :baz}]))))))

  (testing "throws on unresolved parent"
    (let [storage (gsm/create-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :foo :parent :nonexistent}])))))

  (testing "handles empty definitions"
    (let [storage (gsm/create-storage)
          result (fn-composition/sync-fns-to-storage! storage [])]
      (is (= {} result)))))


(deftest topological-sort-test
  (testing "sorts by dependencies and warns on wrong order"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-a {:args {} :return-type :int :impl (fn [_ _] 1)}
                                        :base-b {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])
          ;; Wrong order: wrapper depends on target, but defined first
          fn-composition-data [{:name :wrapper :parent :base-b :args {:ref :target}}
                               {:name :target :parent :base-a}]
          ;; Should still work (with warning)
          output (with-out-str
                   (fn-composition/sync-fns-to-storage! storage fn-composition-data))]

      (testing "prints warning about order"
        (is (clojure.string/includes? output "WARNING"))
        (is (clojure.string/includes? output "Suggested order"))))))


(deftest circular-dependency-test
  (testing "throws on circular dependencies"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])
          ;; A -> B -> A
          fn-composition-data [{:name :fn-a :parent :base-fn :args {:ref :fn-b}}
                               {:name :fn-b :parent :base-fn :args {:ref :fn-a}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (fn-composition/sync-fns-to-storage! storage fn-composition-data))))))


;; === Test internal parsing functions via core namespace ===

(deftest parse-fn-result-ref-test
  (testing "parses :fn-name> syntax (default result name)"
    (is (= [:my-fn :my-fn] (#'core/parse-fn-result-ref :my-fn>))))

  (testing "parses :fn-name>result-name syntax"
    (is (= [:my-fn :custom-result] (#'core/parse-fn-result-ref :my-fn>custom-result))))

  (testing "returns nil for non-fn-result refs"
    (is (nil? (#'core/parse-fn-result-ref :my-fn)))
    (is (nil? (#'core/parse-fn-result-ref "not-a-keyword")))
    (is (nil? (#'core/parse-fn-result-ref 123))))

  (testing "handles namespaced keywords"
    (is (= [:ns/my-fn :ns/my-fn] (#'core/parse-fn-result-ref :ns/my-fn>)))
    (is (= [:ns/my-fn :ns/result] (#'core/parse-fn-result-ref :ns/my-fn>result))))

  (testing "returns nil for just >"
    (is (nil? (#'core/parse-fn-result-ref :>)))))


(deftest extract-fn-ref-test
  (testing "extracts fn ref from keyword"
    (is (= [:my-fn :fn nil] (#'core/extract-fn-ref :my-fn))))

  (testing "extracts fn-result-value ref from :fn-name> keyword"
    (is (= [:my-fn :fn-result-value :my-fn] (#'core/extract-fn-ref :my-fn>))))

  (testing "extracts fn-result-value with custom name"
    (is (= [:my-fn :fn-result-value :custom] (#'core/extract-fn-ref :my-fn>custom))))

  (testing "returns nil for literals"
    (is (nil? (#'core/extract-fn-ref 42)))
    (is (nil? (#'core/extract-fn-ref "string")))
    (is (nil? (#'core/extract-fn-ref {:map "value"})))))


(deftest fn-result-value-test
  (testing "creates fn-result-value entities for :fn-name> args"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:const {:args {:x :any}
                                                :return-type :fn
                                                :impl (fn [_ _] (fn [_] nil))}}
                                       {:assoc-fn {:args {:m :jsonb :k :text :v :any}
                                                   :return-type :jsonb
                                                   :impl (fn [_ _] {})}}])
          ;; Define fns with fn-result-value ref
          fn-composition-data [{:name :handler-fn
                                :parent :const
                                :args {:x {:status 200}}}
                               {:name :handler-map
                                :parent :assoc-fn
                                :args {:m {}
                                       :k "handler"
                                       :v :handler-fn>}}]  ; This should create fn-result-value
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)
          handler-fn-id (:handler-fn result)
          ;; Verify fn-result-value was created
          frvs (sp/query-entities storage :fn-result-value {:fn-id handler-fn-id})]
      (is (= 1 (count frvs)))
      (is (= "handler-fn" (:name (first frvs))))))

  (testing "deduplicates fn-result-values with same name"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:const {:args {:x :any}
                                                :return-type :fn
                                                :impl (fn [_ _] (fn [_] nil))}}
                                       {:assoc-fn {:args {:m :jsonb :k :text :v :any}
                                                   :return-type :jsonb
                                                   :impl (fn [_ _] {})}}
                                       {:conj-fn {:args {:coll :jsonb :x :any}
                                                  :return-type :jsonb
                                                  :impl (fn [_ _] [])}}])
          ;; Two refs to same :handler-fn>
          fn-composition-data [{:name :handler-fn
                                :parent :const
                                :args {:x {:status 200}}}
                               {:name :map1
                                :parent :assoc-fn
                                :args {:m {} :k "a" :v :handler-fn>}}
                               {:name :map2
                                :parent :assoc-fn
                                :args {:m {} :k "b" :v :handler-fn>}}]  ; Same ref, should reuse
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)
          handler-fn-id (:handler-fn result)
          ;; Should have only ONE fn-result-value (deduplicated)
          frvs (sp/query-entities storage :fn-result-value {:fn-id handler-fn-id})]
      (is (= 1 (count frvs)))))

  (testing "creates separate fn-result-values with different names"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:const {:args {:x :any}
                                                :return-type :fn
                                                :impl (fn [_ _] (fn [_] nil))}}
                                       {:assoc-fn {:args {:m :jsonb :k :text :v :any}
                                                   :return-type :jsonb
                                                   :impl (fn [_ _] {})}}])
          ;; Different explicit names: :handler-fn>result1, :handler-fn>result2
          fn-composition-data [{:name :handler-fn
                                :parent :const
                                :args {:x {:status 200}}}
                               {:name :map1
                                :parent :assoc-fn
                                :args {:m {} :k "a" :v :handler-fn>result1}}
                               {:name :map2
                                :parent :assoc-fn
                                :args {:m {} :k "b" :v :handler-fn>result2}}]
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)
          handler-fn-id (:handler-fn result)
          ;; Should have TWO fn-result-values with different names
          frvs (sp/query-entities storage :fn-result-value {:fn-id handler-fn-id})]
      (is (= 2 (count frvs)))
      (is (= #{"result1" "result2"} (set (map :name frvs)))))))


(deftest validation-edge-cases-test
  (testing "throws on non-keyword name"
    (let [storage (gsm/create-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
            (fn-composition/sync-fns-to-storage! storage [{:name "string-name" :parent :foo}])))))

  (testing "throws on non-map args"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:a :int} :return-type :int :impl (fn [_ _] 1)}}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be a map"
            (fn-composition/sync-fns-to-storage! storage [{:name :foo :parent :base-fn :args [1 2 3]}])))))

  (testing "throws on non-sequential fn-composition"
    (let [storage (gsm/create-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
            (fn-composition/sync-fns-to-storage! storage {:not "a vector"})))))

  (testing "throws on unresolved fn reference in args"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :foo :parent :base-fn :args {:ref :nonexistent-fn}}]))))))
