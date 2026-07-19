(ns ^:integration graphden.packages.app.editor-shell-partials-test
  "Executes the editor's shell partials end-to-end against a golden-DB
   bootstrap: `/partials/execute-popover` (`:_partial-xp-handler` —
   free-arg scaffold from the backend's own `:free-arg-slot-map`,
   replacing the JS `freeArgsOf` re-derivation) and
   `/partials/type-name-datalist` (`:_partial-tnd-handler` — the
   create-type name autocomplete, replacing the client-assembled
   datalist + hand-copied primitives)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  (fn [f]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (reset! registry-core/*rich-types-override*
              (sb/ensure-swept-rich-types! ["core" "web" "app"]))
      (f))))


(defn- render-shell
  [fn-name]
  (let [{:keys [ctx storage]} *bootstrap*
        fn-id (get (:all-name->id *bootstrap*) fn-name)
        handler-id (get (:all-name->id *bootstrap*) :_partial-xp-handler)
        resp (setup/exec-with-storage ctx storage handler-id
                                      {:request {:query-params
                                                 {"fn-id" (str fn-id)}}})]
    (is (= 200 (:status resp)))
    (:body resp)))


(deftest pure-fn-with-free-args
  (let [body (render-shell :add)]
    (testing "header names the fn"
      (is (str/includes? body "Run :add")))
    (testing "free-arg host from the backend's free-arg-slot-map"
      (is (str/includes? body "data-slot-name=\"nums\""))
      (is (re-find #"data-slot-id=\"[0-9a-f-]{36}\"" body)))
    (testing "pure fn: no effects banner, Run enabled, persist unlocked"
      (is (not (str/includes? body "execute-effects-warning")))
      (is (not (str/includes? body "execute-confirm-checkbox")))
      (is (not (re-find #"execute-run-btn\"[^>]*disabled" body))))))


(deftest effectful-fn-shell
  ;; `:get-entity` declares `:effects #{:db}` — banner + confirm gate
  ;; + locked persist.
  (let [body (render-shell :get-entity)]
    (is (str/includes? body "execute-effects-warning"))
    (is (str/includes? body "effects-chip-db"))
    (is (str/includes? body "execute-confirm-checkbox"))
    (is (str/includes? body "disabled"))
    (is (str/includes? body "execute-option-label-locked"))))


(deftest type-name-datalist
  (let [{:keys [ctx storage]} *bootstrap*
        handler-id (get (:all-name->id *bootstrap*) :_partial-tnd-handler)
        resp (setup/exec-with-storage ctx storage handler-id {:request {}})
        body (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? body "id=\"type-create-typename-list\""))
    (testing "primitives present with the primitive label"
      (is (str/includes? body "value=\"int\""))
      (is (str/includes? body "label=\"primitive\"")))
    (testing "a named refinement type-row present with its kind"
      (is (str/includes? body "value=\"positive-int\""))
      (is (str/includes? body "label=\"refinement\"")))))


(deftest row-actions-service-block-reason-server-computed
  ;; The root-row context's ⚙ disabled-with-reason state comes from
  ;; `:service-blocking-free-args` INSIDE the partial now — no
  ;; `service-blocked-reason` query param. Regression guard for the
  ;; window where the client's deleted `freeArgsOf` mirror left the
  ;; reason permanently null.
  (let [{:keys [ctx storage]} *bootstrap*
        handler-id (get (:all-name->id *bootstrap*) :_partial-row-actions-handler)
        render (fn [fn-name]
                 (:body (setup/exec-with-storage
                          ctx storage handler-id
                          {:request {:query-params
                                     {"fn-id" (str (get (:all-name->id *bootstrap*) fn-name))
                                      "context" "root-row"}}})))]
    ;; hiccup escapes the apostrophe (`Can&apos;t`) — match past it.
    (testing "fn with service-blocking free args → disabled + reason"
      (let [body (render :add)]
        (is (str/includes? body "make a service — fn has free args: :nums"))
        (is (str/includes? body "action-icon-disabled"))))
    (testing "startable fn → enabled, no reason"
      (let [body (render :web-server)]
        (is (not (str/includes? body "make a service")))))))


(deftest compatible-type-options-partial
  ;; One server-side subtype? sweep replaces the editor's per-name
  ;; /api/types/compatible fan-out. expected=:int → primitives mode
  ;; includes :int itself; type-row mode lists only named narrowings
  ;; (e.g. :positive-int, :port); `current` is excluded server-side.
  (let [{:keys [ctx storage]} *bootstrap*
        handler-id (get (:all-name->id *bootstrap*) :_partial-cto-handler)
        render (fn [params]
                 (:body (setup/exec-with-storage
                          ctx storage handler-id
                          {:request {:query-params params}})))]
    (testing "primitives included on demand"
      (let [body (render {"expected" "\"int\"" "primitives" "true"})]
        (is (str/includes? body "value=\"int\""))
        (is (str/includes? body "value=\"positive-int\""))
        (is (not (str/includes? body "value=\"text\"")))))
    (testing "type-rows only by default + current excluded"
      (let [body (render {"expected" "\"int\"" "current" "positive-int"})]
        (is (not (str/includes? body "value=\"int\"")))
        (is (not (str/includes? body "value=\"positive-int\"")))
        (is (str/includes? body "value=\"port\""))))
    (testing "no compatible types and no current → placeholder"
      (let [body (render {"expected" "\"never\""})]
        (is (str/includes? body "no compatible types"))))))


(deftest static-scaffold-parts
  (let [body (render-shell :add)]
    (doseq [marker ["execute-popover-header" "execute-history-toggle"
                    "execute-history-host" "execute-popover-body"
                    "execute-options-row" "execute-action-bar"
                    "execute-run-btn" "execute-cancel-btn"
                    "execute-result-host" "execute-popover-close"]]
      (is (str/includes? body marker) (str marker " present")))))
