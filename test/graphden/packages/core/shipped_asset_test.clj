(ns ^:integration graphden.packages.core.shipped-asset-test
  "`:shipped-asset` — the cached, allow-listed, effect-free read of a
   shipped frontend asset. It exists so that no tenant request path needs
   `:io` to serve the editor; the allow-list is what keeps that from being
   a door to config or sources."
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


(defn- ex-type
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(deftest shipped-asset
  (let [{:keys [ctx all-name->id]} *bootstrap*
        id (get all-name->id :shipped-asset)
        run (fn [c path] (exec/execute-with-named-args c id {:path path}))
        gated (assoc ctx :allowed-effects cr/cloud-request-allowed-effects)]
    (testing "a shipped asset reads as its exact text — under the cloud's request gate"
      (is (= (slurp (io/resource "packages/app/auth-pages/auth.css"))
             (run gated "packages/app/auth-pages/auth.css"))))
    (testing "the allow-list: only asset kinds under packages/, no parent-dir segments"
      (doseq [bad ["cloud/prod.edn"
                   "system-prod.edn"
                   "packages/core/system/impls.clj"
                   "packages/core/system/fns.edn"
                   "packages/../system-prod.edn"
                   "graphden-build-hashes.json"]]
        (is (= :validation-error/shipped-asset-path (ex-type #(run gated bad)))
            (str bad " must be refused"))))
    (testing "a well-shaped path the build does not carry is a clear not-found"
      (is (= :execution-error/resource-not-found
             (ex-type #(run gated "packages/app/editor/does-not-exist.js")))))))
