(ns graphden.packages.manifest-test
  "Tests for the executor-packages.edn manifest reader."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.manifest :as manifest]))


(deftest package-names-test
  (is (= [] (manifest/package-names nil)))
  (is (= [] (manifest/package-names {:packages []})))
  (is (= ["telegram" "s3"]
         (manifest/package-names {:packages [{:name "telegram" :lib 'a/b :coord {}}
                                             {:name "s3" :lib 'c/d :coord {}}]}))))


(deftest extra-deps-test
  (is (= {} (manifest/extra-deps nil)))
  (is (= {'com.acme/telegram {:git/url "u" :git/sha "s"}}
         (manifest/extra-deps {:packages [{:name "telegram" :lib 'com.acme/telegram
                                           :coord {:git/url "u" :git/sha "s"}}]})))
  (testing "a name-only entry (already on the classpath) contributes no coord"
    (is (= {} (manifest/extra-deps {:packages [{:name "x"}]})))))


(deftest read-manifest-absent-returns-nil
  (is (nil? (manifest/read-manifest "nonexistent-manifest-xyz.edn"))))


(deftest read-manifest-ships-empty
  ;; The committed resources/executor-packages.edn lists no external packages.
  (is (= {:packages []} (manifest/read-manifest))))
