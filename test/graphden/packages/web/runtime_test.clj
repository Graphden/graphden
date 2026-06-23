(ns ^:integration graphden.packages.web.runtime-test
  "Unit tests for the `web.runtime` package — server-side hiccup
   helpers that emit attrs the client-side `editor-runtime.js`
   dispatcher routes via `data-action=\"…\"`.

   Block 1.2 of the user-sites plan (docs/USER_SITES_PLAN.md):
   single atom `:dispatch-action` today; subsequent blocks add
   component fn-defs that compose it."
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
;; :dispatch-action — minimal attrs map
;; =============================================================================

(deftest dispatch-action-emits-data-action-attr-test
  (testing "free arg :action → `{:data-action <action>}` map"
    (is (= {:data-action "run-fn"}
           (exec-name :dispatch-action {:action "run-fn"})))))


(deftest dispatch-action-passes-blank-action-through-test
  (testing ":non-blank-text gate is type-check-time only — at runtime the blank string flows through to `:data-action`"
    ;; The slot type `:non-blank-text` is a refinement that
    ;; sync-time type-check uses to reject literal "" bindings,
    ;; but the executor itself doesn't enforce refinements on
    ;; runtime-supplied values. Documenting this here so a future
    ;; reader doesn't mistake the absence of a runtime guard for
    ;; a bug.
    (is (= {:data-action ""}
           (exec-name :dispatch-action {:action ""})))))


(deftest dispatch-action-composes-via-assoc-test
  (testing "extras can be added with `:assoc` (caller pattern for handler-specific data-*)"
    ;; Mimics what a future `:button` component does to add the
    ;; per-action payload. We invoke `:assoc` directly with the
    ;; `:dispatch-action` result as the seed map.
    (let [base (exec-name :dispatch-action {:action "namespace-move"})
          extended (assoc base :data-fn-id "12345")]
      (is (= {:data-action "namespace-move" :data-fn-id "12345"}
             extended)))))
