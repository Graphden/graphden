(ns graphden.fn-composition.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging]
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
          ;; Capture log messages
          logged-messages (atom [])
          _ (with-redefs [clojure.tools.logging/log*
                          (fn [_logger level _throwable message]
                            (swap! logged-messages conj {:level level :message message}))]
              (fn-composition/sync-fns-to-storage! storage fn-composition-data))]

      (testing "logs warning about order"
        (is (= 1 (count @logged-messages)))
        (let [{:keys [level message]} (first @logged-messages)]
          (is (= :warn level))
          (is (str/includes? message "not in dependency order"))
          (is (str/includes? message "Suggested order")))))))


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

  (testing "extracts call-site ref from :fn-name> keyword"
    (is (= [:my-fn :call-site :my-fn] (#'core/extract-fn-ref :my-fn>))))

  (testing "extracts call-site with custom name"
    (is (= [:my-fn :call-site :custom] (#'core/extract-fn-ref :my-fn>custom))))

  (testing "returns nil for literals"
    (is (nil? (#'core/extract-fn-ref 42)))
    (is (nil? (#'core/extract-fn-ref "string")))
    (is (nil? (#'core/extract-fn-ref {:map "value"})))))


(deftest call-site-test
  (testing "creates call-site entities for :fn-name> args"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:const {:args {:x :any}
                                                :return-type :fn
                                                :impl (fn [_ _] (fn [_] nil))}}
                                       {:assoc-fn {:args {:m :jsonb :k :text :v :any}
                                                   :return-type :jsonb
                                                   :impl (fn [_ _] {})}}])
          ;; Define fns with call-site ref
          fn-composition-data [{:name :handler-fn
                                :parent :const
                                :args {:x {:status 200}}}
                               {:name :handler-map
                                :parent :assoc-fn
                                :args {:m {}
                                       :k "handler"
                                       :v :handler-fn>}}]  ; This should create call-site
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)
          handler-fn-id (:handler-fn result)
          ;; Verify call-site was created
          frvs (sp/query-entities storage :call-site {:fn-id handler-fn-id})]
      (is (= 1 (count frvs)))
      (is (= "handler-fn" (:name (first frvs))))))

  (testing "deduplicates call-sites with same name"
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
          ;; Should have only ONE call-site (deduplicated)
          frvs (sp/query-entities storage :call-site {:fn-id handler-fn-id})]
      (is (= 1 (count frvs)))))

  (testing "creates separate call-sites with different names"
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
          ;; Should have TWO call-sites with different names
          frvs (sp/query-entities storage :call-site {:fn-id handler-fn-id})]
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


;; === resolve-fn-id edge case tests ===

(deftest resolve-fn-id-from-storage-test
  (testing "resolves fn by name from storage when not in created-fns"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :fn}
                                                  :return-type :any
                                                  :impl (fn [_ _] nil)}}
                                       {:const {:args {:x :any}
                                                :return-type :int
                                                :impl (fn [_ _] 42)}}])
          ;; First, create an fn entity directly in storage
          fn-schema-id (registry/fn-schema-uuid :const)
          _ (sp/create-entity storage :fn
                              {:name "existing-fn"
                               :fn-schema-id fn-schema-id})
          ;; Now sync a new fn that references the existing one
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :wrapper-fn
                                                        :parent :base-fn
                                                        :args {:ref :existing-fn}}])]
      ;; Should succeed - existing-fn is resolved from storage
      (is (uuid? (:wrapper-fn result))))))


;; === parse-fn-result-ref edge case tests ===

(deftest parse-fn-result-ref-edge-cases-test
  (testing "handles empty string after >"
    ;; :fn> should default result name to fn name
    (is (= [:fn :fn] (#'core/parse-fn-result-ref :fn>))))

  (testing "handles spaces in result name"
    ;; This is a valid but unusual case
    (is (= [:fn (keyword "name with space")]
           (#'core/parse-fn-result-ref (keyword "fn>name with space")))))

  (testing "handles multiple > characters"
    ;; Only splits on first >
    (is (= [:fn :a>b] (#'core/parse-fn-result-ref :fn>a>b)))))


;; === extract-dependencies edge case tests ===

(deftest extract-dependencies-test
  (testing "extracts dependencies from fn-def args"
    (let [fn-def {:name :my-fn
                  :parent :base
                  :args {:a :other-fn   ; fn ref
                         :b :third-fn>  ; call-site ref
                         :c 42}}        ; literal (ignored)
          fn-names #{:other-fn :third-fn}
          deps (#'core/extract-dependencies fn-def fn-names)]
      (is (= #{:other-fn :third-fn} deps))))

  (testing "ignores refs to fns not in the set"
    (let [fn-def {:name :my-fn
                  :parent :base
                  :args {:a :external-fn}}  ; not in fn-names set
          fn-names #{:other-fn}
          deps (#'core/extract-dependencies fn-def fn-names)]
      (is (empty? deps))))

  (testing "handles empty args"
    (let [fn-def {:name :my-fn :parent :base}
          deps (#'core/extract-dependencies fn-def #{})]
      (is (empty? deps)))))


;; === build-dependency-graph test ===

(deftest build-dependency-graph-test
  (testing "builds correct dependency graph"
    (let [fn-defs [{:name :a :parent :base :args {:x :b>}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base}]
          graph (#'core/build-dependency-graph fn-defs)]
      (is (= {:a #{:b} :b #{:c} :c #{}} graph)))))


;; === topological-sort edge cases ===

(deftest topological-sort-edge-cases-test
  (testing "handles single element"
    (let [fn-defs [{:name :single :parent :base}]
          sorted (#'core/topological-sort fn-defs)]
      (is (= [:single] (mapv :name sorted)))))

  (testing "handles independent elements (no dependencies)"
    (let [fn-defs [{:name :a :parent :base}
                   {:name :b :parent :base}
                   {:name :c :parent :base}]
          sorted (#'core/topological-sort fn-defs)]
      ;; Order doesn't matter for independent elements
      (is (= #{:a :b :c} (set (mapv :name sorted))))))

  (testing "throws on self-reference cycle"
    (let [fn-defs [{:name :self-ref :parent :base :args {:x :self-ref>}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (#'core/topological-sort fn-defs)))))

  (testing "throws on three-way cycle"
    (let [fn-defs [{:name :a :parent :base :args {:x :b>}}
                   {:name :b :parent :base :args {:x :c>}}
                   {:name :c :parent :base :args {:x :a>}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (#'core/topological-sort fn-defs))))))


;; === call-site edge cases ===

(deftest call-site-unresolved-test
  (testing "throws when call-site references non-existent fn"
    (let [storage (gsm/create-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :any}
                                                  :return-type :any
                                                  :impl (fn [_ _] nil)}}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :broken
                                                   :parent :base-fn
                                                   :args {:ref :nonexistent>}}]))))))
