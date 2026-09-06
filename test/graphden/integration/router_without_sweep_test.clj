(ns ^:integration graphden.integration.router-without-sweep-test
  "The branch router serves a request off a graph whose rich-types were
   never swept — the golden clone as it is built (`:skip-type-check?`).
   Before `bindings/effective-return-type` the seed's `:any` for a
   composed fn-def without its own `:return-type` made
   `ref-produces-callable?` say no for `_router`, so `:router-result`'s
   `:func` was `hof-wrap`ped and `dispatch` answered with a FUNCTION
   inside `update-in` (ClassCastException). An editor-authored fn sits
   in the same window between its write and its post-write check."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.system.branch-router :as br]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [t]
    ;; Deliberately NO `with-isolated-rich-types` + sweep overlay: the
    ;; registry holds only what the package seed declared.
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
         (try
           (binding [*graph* graph] (t))
           (finally (setup/close-graph! graph)))))))


(deftest router-answers-off-an-unswept-registry
  (let [router (br/create-router (:ctx *graph*) "_app-ring-response")
        ;; A DB-backed route (`/version` wants the build-hashes resource an
        ;; uberjar carries; the test classpath has none).
        resp (br/dispatch router {:request-method :get :uri "/api/branches" :headers {}})]
    (testing "the ring chain returns a response map, not the router callable"
      (is (map? resp))
      (is (= 200 (:status resp)))
      (is (string? (:body resp))))))
