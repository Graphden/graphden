(ns graphden.web.errors-doc-test
  "Drift guard: every explicitly-mapped error type in
   `graphden.web.errors/status-for-type` must appear in
   docs/ERROR_CODES.md's status section, and the mapper's family
   fallbacks + safe-body behavior stay pinned."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.web.errors :as errors]))


(deftest every-mapped-type-is-documented
  (let [doc (slurp "docs/ERROR_CODES.md")]
    (doseq [t (keys errors/status-for-type)]
      (is (or (str/includes? doc (str t))
              (str/includes? doc (str (namespace t) "/*"))
              ;; execute reasons are documented prose-style
              (= "execution" (namespace t)))
          (str t " missing from ERROR_CODES.md status section")))))


(deftest status-mapping-behavior
  (testing "explicit types"
    (is (= 404 (errors/status-for :not-found)))
    (is (= 409 (errors/status-for :merge-conflict)))
    (is (= 409 (errors/status-for :constraint-violation/fn-name-collision)))
    (is (= 429 (errors/status-for :execution/over-capacity)))
    (is (= 429 (errors/status-for :quota/entity-limit)))
    (is (= 413 (errors/status-for :execution/args-too-large)))
    (is (= 503 (errors/status-for :vault/not-configured))))
  (testing "family fallbacks cover new members automatically"
    (is (= 400 (errors/status-for :packages/whatever-new)))
    (is (= 400 (errors/status-for :type-check/rejected)))
    (is (= 403 (errors/status-for :capability/secret-leaf-restricted))))
  (testing "unknown → 500"
    (is (= 500 (errors/status-for :totally/unknown)))
    (is (= 500 (errors/status-for nil)))))


(deftest safe-body-withholds-internal-messages
  (testing "author-facing family passes the message"
    (let [b (errors/safe-error-body :packages/ambiguous-ref "qualify it")]
      (is (= "qualify it" (:message b)))
      (is (nil? (:ref b)))))
  (testing "internal type gets an opaque ref, message withheld"
    (let [b (errors/safe-error-body :org/scoped-storage "SELECT boom FROM secret")]
      (is (some? (:ref b)))
      (is (not (str/includes? (:message b) "SELECT")))))
  (testing "the :quota family is author-facing — the row-cap message reaches the user"
    (let [b (errors/safe-error-body :quota/entity-limit "You've reached your plan's function limit.")]
      (is (nil? (:ref b)))
      (is (= "You've reached your plan's function limit." (:message b)))))
  (testing "absent type gets an opaque ref"
    (is (some? (:ref (errors/safe-error-body nil "raw jdbc text"))))))
