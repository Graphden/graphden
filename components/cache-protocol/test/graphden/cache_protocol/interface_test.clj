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
