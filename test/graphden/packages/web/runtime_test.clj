(ns ^:integration graphden.packages.web.runtime-test
  "Unit tests for the `web.runtime` package — server-side hiccup
   helpers that emit attrs the client-side `editor-runtime.js`
   dispatcher routes via `data-action=\"…\"`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.graph-harness :as gh]))


(def ^:dynamic *container* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  (gh/graph-fixture (str (ns-name *ns*))))


;; =============================================================================
;; :dispatch-action — minimal attrs map
;; =============================================================================

(deftest dispatch-action-emits-data-action-attr-test
  (testing "free arg :action → `{:data-action <action>}` map"
    (is (= {:data-action "run-fn"}
           (gh/exec-name :dispatch-action {:action "run-fn"})))))


(deftest dispatch-action-passes-blank-action-through-test
  (testing ":non-blank-text gate is type-check-time only — at runtime the blank string flows through to `:data-action`"
    ;; The slot type `:non-blank-text` is a refinement that
    ;; sync-time type-check uses to reject literal "" bindings,
    ;; but the executor itself doesn't enforce refinements on
    ;; runtime-supplied values. Documenting this here so a future
    ;; reader doesn't mistake the absence of a runtime guard for
    ;; a bug.
    (is (= {:data-action ""}
           (gh/exec-name :dispatch-action {:action ""})))))


(deftest dispatch-action-composes-via-assoc-test
  (testing "extras can be added with `:assoc` (caller pattern for handler-specific data-*)"
    ;; Mimics what a future `:button` component does to add the
    ;; per-action payload. We invoke `:assoc` directly with the
    ;; `:dispatch-action` result as the seed map.
    (let [base (gh/exec-name :dispatch-action {:action "namespace-move"})
          extended (assoc base :data-fn-id "12345")]
      (is (= {:data-action "namespace-move" :data-fn-id "12345"}
             extended)))))


;; =============================================================================
;; :dispatch-custom — escape hatch DSL
;; =============================================================================

(deftest dispatch-custom-emits-action-and-handler-body-test
  (testing "free arg :body → `{:data-action \"custom\" :data-custom-handler <body>}`"
    (is (= {:data-action "custom"
            :data-custom-handler "btn.title = 'hi';"}
           (gh/exec-name :dispatch-custom {:body "btn.title = 'hi';"})))))


(deftest dispatch-custom-accepts-empty-body-test
  (testing ":js-source has no narrowing constraint — empty body flows through"
    ;; v0 design: the runtime's custom-handler is a no-op on
    ;; empty `data-custom-handler`. Documenting here that the
    ;; server side doesn't pre-reject empty bodies.
    (is (= {:data-action "custom" :data-custom-handler ""}
           (gh/exec-name :dispatch-custom {:body ""})))))
