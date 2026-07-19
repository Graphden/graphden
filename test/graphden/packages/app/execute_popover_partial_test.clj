(ns ^:integration graphden.packages.app.execute-popover-partial-test
  "Executes the `/partials/execute-popover` graph chain end-to-end —
   `:_partial-xp-handler` against a golden-DB bootstrap. The shell's
   free-arg scaffold comes from the backend's own
   `:free-arg-slot-map`, replacing the JS `freeArgsOf` re-derivation."
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


(deftest static-scaffold-parts
  (let [body (render-shell :add)]
    (doseq [marker ["execute-popover-header" "execute-history-toggle"
                    "execute-history-host" "execute-popover-body"
                    "execute-options-row" "execute-action-bar"
                    "execute-run-btn" "execute-cancel-btn"
                    "execute-result-host" "execute-popover-close"]]
      (is (str/includes? body marker) (str marker " present")))))
