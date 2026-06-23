(ns ^:integration graphden.packages.web.components-test
  "Unit tests for the `web.components` starter component library —
   Block 2 of the user-sites plan (docs/USER_SITES_PLAN.md).

   Each component is a fn-def over `:hiccup`; assertions here pin the
   composed hiccup output so future renames / parent-swaps that change
   the rendered shape fail loudly."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  exec/with-clean-registry
  (fn [f]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (f))))


(defn- exec-name
  [nm args]
  (let [{:keys [ctx storage all-name->id]} *bootstrap*
        fn-id (get all-name->id nm)]
    (when-not fn-id
      (throw (ex-info (str "No fn-id for " nm) {:nm nm})))
    (setup/exec-with-storage ctx storage fn-id args)))


;; =============================================================================
;; :button — plain label, with attrs, and DSL-composed click handler
;; =============================================================================

(deftest button-label-only-renders-bare-button-test
  (testing "no :attrs binding → hiccup [:button \"label\"]; :hiccup impl drops nil attrs entirely so the output is a 2-vec"
    (is (= [:button "Submit"]
           (exec-name :button {:label "Submit"})))))


(deftest button-with-attrs-merges-into-element-test
  (testing "caller-supplied :attrs flows into the hiccup attrs slot"
    (is (= [:button {:class "primary"} "Save"]
           (exec-name :button {:label "Save"
                               :attrs {:class "primary"}})))))


(deftest button-composed-with-dispatch-action-test
  (testing "the v0 DSL composition pattern: `:dispatch-action` as :attrs source emits data-action attribute"
    ;; This is the production composition shape — caller builds attrs
    ;; via the runtime DSL and passes them straight into :button.
    (let [attrs (exec-name :dispatch-action {:action "run-fn"})]
      (is (= [:button {:data-action "run-fn"} "Run"]
             (exec-name :button {:label "Run" :attrs attrs}))))))
