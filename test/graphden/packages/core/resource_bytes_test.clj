(ns ^:integration graphden.packages.core.resource-bytes-test
  "`:read-resource-bytes` — the binary sibling of `:read-resource-or-nil`,
   what lets a graph route serve a shipped image. Pins the three things
   that matter: the bytes are the resource's bytes, a missing path is nil
   (a route can 404), and it is an `:io` read the cloud's request-level
   gate refuses — a tenant must not be able to pull the jar's config out
   through it."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (t))))


(deftest read-resource-bytes
  (let [{:keys [ctx all-name->id]} *bootstrap*
        id (get all-name->id :read-resource-bytes)
        run (fn [c path] (exec/execute-with-named-args c id {:path path}))]
    (testing "a shipped resource comes back as its exact bytes"
      (let [expected (with-open [in (io/input-stream (io/resource "logback.xml"))]
                       (java.io.InputStream/.readAllBytes in))
            actual (run ctx "logback.xml")]
        (is (bytes? actual))
        (is (java.util.Arrays/equals ^bytes expected ^bytes actual))))
    (testing "a path that does not resolve is nil, not an exception"
      (is (nil? (run ctx "landing/does-not-exist.png"))))
    (testing "it is an :io read — refused under the cloud's request-level gate"
      (let [e (try (run (assoc ctx :allowed-effects cr/cloud-request-allowed-effects) "logback.xml")
                   (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
        (is (= :execution/forbidden-effect (:type e)))
        (is (= :io (:effect e)))))))
