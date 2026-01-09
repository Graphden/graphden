(ns graphden.cache-protocol.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-protocol.interface :as cache]))


(deftest cached-storage?-test
  (testing "returns false for non-implementing objects"
    (is (not (cache/cached-storage? {})))
    (is (not (cache/cached-storage? nil)))
    (is (not (cache/cached-storage? "string")))))


(deftest protocol-methods-exist-test
  (testing "CacheStorage protocol has expected methods"
    ;; Verify protocol is defined with expected methods
    ;; by checking the protocol var exists
    (is (some? cache/CacheStorage))
    (is (var? #'cache/get-cached-graph))
    (is (var? #'cache/cache-exists?))
    (is (var? #'cache/save-cache!))
    (is (var? #'cache/delete-cache!))
    (is (var? #'cache/find-caches-by-fn-dep))
    (is (var? #'cache/find-caches-by-fn-schema-dep))
    (is (var? #'cache/find-caches-by-arg-schema-dep))))


;; === validate-graph! tests ===

(deftest validate-graph!-test
  (testing "accepts valid graph"
    (let [valid-graph {:fns {}
                       :fn-schemas {}
                       :arg-schemas {}}]
      (is (true? (cache/validate-graph! valid-graph)))))

  (testing "accepts graph with data"
    (let [fn-id (random-uuid)
          schema-id (random-uuid)
          arg-id (random-uuid)
          graph {:fns {fn-id {:id fn-id :name "test"}}
                 :fn-schemas {schema-id {:id schema-id}}
                 :arg-schemas {arg-id {:id arg-id}}}]
      (is (true? (cache/validate-graph! graph)))))

  (testing "throws when graph is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph must be a map"
          (cache/validate-graph! nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph must be a map"
          (cache/validate-graph! [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph must be a map"
          (cache/validate-graph! "string"))))

  (testing "throws when :fns is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph :fns must be a map"
          (cache/validate-graph! {:fns nil :fn-schemas {} :arg-schemas {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph :fns must be a map"
          (cache/validate-graph! {:fns [] :fn-schemas {} :arg-schemas {}}))))

  (testing "throws when :fn-schemas is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph :fn-schemas must be a map"
          (cache/validate-graph! {:fns {} :fn-schemas nil :arg-schemas {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph :fn-schemas must be a map"
          (cache/validate-graph! {:fns {} :fn-schemas "bad" :arg-schemas {}}))))

  (testing "throws when :arg-schemas is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph :arg-schemas must be a map"
          (cache/validate-graph! {:fns {} :fn-schemas {} :arg-schemas nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Graph :arg-schemas must be a map"
          (cache/validate-graph! {:fns {} :fn-schemas {} :arg-schemas 123}))))

  (testing "error includes type and details"
    (try
      (cache/validate-graph! "not-a-map")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-graph (:type (ex-data e))))
        (is (= "not-a-map" (:value (ex-data e))))))))


;; === validate-dependencies! tests ===

(deftest validate-dependencies!-test
  (testing "accepts valid dependencies"
    (let [valid-deps {:fn-ids {}
                      :fn-schema-ids {}
                      :arg-schema-ids {}}]
      (is (true? (cache/validate-dependencies! valid-deps)))))

  (testing "accepts dependencies with data"
    (let [fn-id (random-uuid)
          schema-id (random-uuid)
          arg-id (random-uuid)
          deps {:fn-ids {fn-id 1}
                :fn-schema-ids {schema-id 2}
                :arg-schema-ids {arg-id 3}}]
      (is (true? (cache/validate-dependencies! deps)))))

  (testing "throws when dependencies is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies must be a map"
          (cache/validate-dependencies! nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies must be a map"
          (cache/validate-dependencies! [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies must be a map"
          (cache/validate-dependencies! "string"))))

  (testing "throws when :fn-ids is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies :fn-ids must be a map"
          (cache/validate-dependencies! {:fn-ids nil :fn-schema-ids {} :arg-schema-ids {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies :fn-ids must be a map"
          (cache/validate-dependencies! {:fn-ids [] :fn-schema-ids {} :arg-schema-ids {}}))))

  (testing "throws when :fn-schema-ids is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies :fn-schema-ids must be a map"
          (cache/validate-dependencies! {:fn-ids {} :fn-schema-ids nil :arg-schema-ids {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies :fn-schema-ids must be a map"
          (cache/validate-dependencies! {:fn-ids {} :fn-schema-ids 42 :arg-schema-ids {}}))))

  (testing "throws when :arg-schema-ids is not a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies :arg-schema-ids must be a map"
          (cache/validate-dependencies! {:fn-ids {} :fn-schema-ids {} :arg-schema-ids nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dependencies :arg-schema-ids must be a map"
          (cache/validate-dependencies! {:fn-ids {} :fn-schema-ids {} :arg-schema-ids "bad"}))))

  (testing "error includes type and key details"
    (try
      (cache/validate-dependencies! {:fn-ids {} :fn-schema-ids "invalid" :arg-schema-ids {}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-dependencies (:type (ex-data e))))
        (is (= :fn-schema-ids (:key (ex-data e))))
        (is (= "invalid" (:value (ex-data e))))))))


;; === validate-uuid! tests ===

(deftest validate-uuid!-test
  (testing "accepts valid UUIDs"
    (is (true? (cache/validate-uuid! (random-uuid) "test-param")))
    (is (true? (cache/validate-uuid! #uuid "550e8400-e29b-41d4-a716-446655440000" "fn-id"))))

  (testing "throws for non-UUID values"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-id must be a UUID"
          (cache/validate-uuid! nil "fn-id")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cache-id must be a UUID"
          (cache/validate-uuid! "not-a-uuid" "cache-id")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dep-id must be a UUID"
          (cache/validate-uuid! 12345 "dep-id")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-id must be a UUID"
          (cache/validate-uuid! {} "arg-id"))))

  (testing "error includes param name and value"
    (try
      (cache/validate-uuid! "bad-value" "my-param")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-uuid (:type (ex-data e))))
        (is (= "my-param" (:param (ex-data e))))
        (is (= "bad-value" (:value (ex-data e))))))))
