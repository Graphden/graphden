(ns graphden.cache-postgres.unit-test
  "Unit tests for cache-postgres (no DB required)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cache-protocol.interface :as cache]))


(deftest create-cache-test
  (testing "creates PostgresCache instance"
    (let [mock-ds (reify javax.sql.DataSource)]
      (is (some? (cache-pg/create-cache mock-ds)))
      (is (cache/cached-storage? (cache-pg/create-cache mock-ds))))))
