(ns graphden.packages.manifest-test
  "Tests for the executor-packages.edn manifest reader."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.loader :as loader]
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


(deftest read-manifest-lists-mathx-demo
  ;; The committed resources/executor-packages.edn lists the `mathx` demo —
  ;; an EXTERNAL Type-2 (impl+fns) package that proves the manifest loading
  ;; path end-to-end (its :local/root coord is also in deps.edn so the
  ;; classpath resolves it).
  (is (contains? (set (manifest/package-names (manifest/read-manifest)))
                 "mathx")))


(deftest external-mathx-package-loads-impl-and-fn-def
  ;; The full Type-2 proof at the loader layer: an out-of-tree package
  ;; (external-packages/mathx, on the classpath via deps.edn :local/root)
  ;; contributes BOTH a base-fn impl (:gcd) and a composed fn-def
  ;; (:gcd-with-12) through the same loader path as the built-ins.
  (let [{:keys [base-fn-defs fn-defs]} (loader/load-packages ["core" "mathx"])]
    (testing "the external base-fn impl registered"
      (is (contains? base-fn-defs :gcd))
      (is (fn? (:impl (get base-fn-defs :gcd)))))
    (testing "the external composed fn-def loaded"
      (is (some #(= :gcd-with-12 (:name %)) fn-defs)))))
