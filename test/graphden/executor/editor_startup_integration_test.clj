(ns ^:integration ^:serial graphden.executor.editor-startup-integration-test
  "The editor working set in one frozen runtime, including after another
   test has reloaded packages since the shared type sweep was cached."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.checked-source-memo :as checker-memo]
    [graphden.executor.checked-source-runtime :as checker-runtime]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.executor.source-recipe-store :as recipes]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.sync :as sync]
    [graphden.storage.postgres.core :as pg]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.test-infra.shared-bootstrap :as bootstrap]
    [graphden.test-infra.ui-operation-api :as ui-api]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as versioned]))


(def ^:dynamic *context* nil)


(defn- frozen-runtime-fixture
  [f]
  (let [packages ["core" "web" "app"]
        ;; Preserve the full-suite precondition: an earlier sweep exists.
        ;; Its callback objects must NOT become this frozen runtime's seed.
        _ (bootstrap/ensure-swept-rich-types! packages)
        {:keys [db-config]} (bootstrap/ensure-ns-database-from-golden!
                              "graphden.executor.editor-startup-integration-test" packages)
        storage (versioned/wrap-with-versioning (pg/create-storage db-config) "main")]
    (try
      ;; Loading packages replaces native Var roots. Capture signatures with
      ;; a fresh sweep, then build the context/cache from that SAME generation.
      ;; Ordinary golden consumers keep their inexpensive shared sweep.
      (sync/bootstrap-from-packages! storage packages {:skip-type-check? false})
      (let [ctx (exec/create-context {:storage storage})]
        (runtime/rebuild! ctx)
        (binding [*context* ctx] (f)))
      (finally (setup/close-graph! {:storage storage})))))


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  frozen-runtime-fixture)


;; Drain even when a worker throws a JVM Error; never turn that error into
;; success. Rethrow it after every other worker has actually unwound.
^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/catch-throwable]}
(defn- drain-workers
  [workers]
  (let [outcomes (mapv (fn [worker]
                         (try {:value @worker}
                              (catch Throwable error {:error error}))) workers)]
    (if-let [error (some :error outcomes)]
      (throw error)
      (mapv :value outcomes))))


(deftest failed-request-still-drains-the-other-requests
  (let [drained? (atom false)
        failed (reify clojure.lang.IDeref
                 (deref [_] (throw (ex-info "Request failed" {}))))
        other (reify clojure.lang.IDeref
                (deref [_] (reset! drained? true) :done))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Request failed"
          (drain-workers [failed other])))
    (is @drained?)))


(defn- export-editor-startup
  [ctx]
  (let [inputs [nil
                {:components ["inspector" "explorer" "canvas"]}
                {:components ["canvas-values" "canvas-inheritance" "canvas-metadata"]}
                {:components ["canvas-type-structure" "canvas-bindings"]}]
        workers (mapv (fn [input]
                        (future
                          (recheck/call-with-ctx-slices
                            ctx
                            #(true? (:ok (if input
                                           (ui-api/export-resolved ctx input)
                                           (ui-api/export-current ctx))))))) inputs)]
    ;; Drain every request before a checker override or fixture can unwind.
    (drain-workers workers)))


(deftest warm-editor-startup-retains-root-and-every-component-family
  (binding [br/*active-router-override* (atom nil)]
    (let [cache (checker-runtime/create-cache)
          ctx (assoc *context* :checker-cache cache)
          checked (atom 0) checker check/check-fn-def!]
      (try
        (epoch/seed-watermark! (:storage ctx))
        ;; Previous visitors leave family registrations after payload eviction.
        ;; A new editor must still retain its working set before their TTL ends.
        (doseq [index (range (get-in cache [:store :options :max-families]))]
          (recipes/create-family! (:store cache) (str "previous-editor-" index)
                                  (+ ((:clock-ms cache)) (:ttl-ms cache))))
        ;; Cold concurrent reservations may decline a builder. A second pass
        ;; completes admission; the following whole-editor load must stay warm.
        (dotimes [_ 2] (is (every? true? (export-editor-startup ctx))))
        (let [result (with-redefs [check/check-fn-def!
                                   (fn [& args] (swap! checked inc) (apply checker args))]
                       (export-editor-startup ctx))]
          (is (every? true? result))
          (is (zero? @checked) "Different UI roles must not evict the root on every reload")
          (is (= {:pending 0 :leases 0}
                 (select-keys (checker-memo/stats cache) [:pending :leases]))))
        (finally (checker-memo/retire! cache))))))
