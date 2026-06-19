(ns ^:integration graphden.integration.http-server-service-lifecycle-test
  "End-to-end acceptance test for HTTP-server-class services through
   the supervisor path.

   Why this test exists: `cron_schedule_service_test.clj` proves the
   reconciler lifecycle for a CRON service (long-lived loop ticking
   on schedule). HTTP servers are the OTHER long-lived service shape
   the supervisor must handle — start once, hold the stopper
   handle, unwind on disable. They have different operational shape:
   no per-tick observable progress, just \"the listener is up vs not\".

   Without this sentinel, a regression of the reconciler's stopper-
   capture branch (e.g. only persisting the cron-style stopper path)
   would only surface in production after the next reconcile of a
   real `:web-server` row.

   The test uses a stubbed start-fn (`:_http-svc-stub-start`) that
   mirrors `:http-server`'s contract — returns a 0-arg stopper
   callable, declares `:process` (required for `:service` eligibility
   per `:_create-service-no-process-rej`). This avoids the port-
   binding flakiness of a real http-kit run inside an integration
   test while still exercising the entire reconciler path: start /
   stopper-capture / running-atom-entry / stop-on-disable / atom-
   drained."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.interface :as registry]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.system.interface :as sys]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(deftest ^:slow http-server-service-lifecycle-through-reconciler-test
  ;; Same shape as `cron-service-lifecycle-through-reconciler-test`
  ;; but for the HTTP-server class of service:
  ;;
  ;;   1. Bring up full system (no reconciler — we drive it manually)
  ;;   2. Register `:_http-svc-stub-start` that:
  ;;        - records `:started` in `lifecycle-events`
  ;;        - returns a 0-arg closure that records `:stopped` when
  ;;          invoked (mirrors `:http-server` returning an
  ;;          http-server-handle that's a 0-arg stop callable)
  ;;        - declares `:process` (service-eligibility precondition)
  ;;   3. Sync the base-fn into storage
  ;;   4. Create a `:service` row pointing at it (enabled? true)
  ;;   5. `recon/reconcile-once!` → reconciler classifies as :to-start,
  ;;      invokes the executor, captures the stopper from the return
  ;;   6. Assert the start was recorded AND the running-atom carries
  ;;      the stopper callable
  ;;   7. Disable the :service row + reconcile → :to-stop branch,
  ;;      stopper invoked, running-atom drained
  ;;   8. Assert the stop was recorded
  (let [container-cfg (th/get-container-config *container*)
        lifecycle-events (atom [])
        stub-start (fn [_args _ctx]
                     (swap! lifecycle-events conj :started)
                     ;; The stop callable. Reconciler stores it under
                     ;; `:stopper` and invokes it as `(stopper)` on
                     ;; the :to-stop branch.
                     (fn []
                       (swap! lifecycle-events conj :stopped)
                       nil))
        system (sys/start-with-overrides!
                 :dev
                 [:db/schema
                  :db/postgres
                  :db/versioned
                  :app/packages
                  :exec/base-fns
                  :exec/fn-entities
                  :exec/context
                  :exec/compiled-registry]
                 {:db/postgres (select-keys container-cfg
                                            [:jdbc-url :username :password])
                  :exec/base-fns {:extra-base-fns
                                  {:_http-svc-stub-start stub-start}}
                  :app/packages {:package-names
                                 ["core" "storage" "web" "app"]}})]
    (try
      (let [storage (:db/versioned system)
            context (:exec/context system)
            ;; Snapshot the stub into the storage-side rich-types so
            ;; the service-row's `:fn-id` can resolve to it. The
            ;; declared shape mirrors `:http-server`'s service-
            ;; eligibility contract: 0 args, `:process` effect,
            ;; returns the stopper callable (typed `:any` for the
            ;; stub since `:http-server-handle` is a domain alias).
            _ (registry/sync-defs-to-storage!
                storage
                {:_http-svc-stub-start {:args {}
                                        :return-type :any
                                        :effects #{:process}}})
            stub-fn (first (sp/query-entities storage :fn
                                              {:name "_http-svc-stub-start"}))
            _ (is (some? stub-fn)
                  "stub base-fn synced as :fn storage row")
            ;; Force compile so the reconciler's executor lookup
            ;; finds the freshly-synced fn-id.
            _ ((requiring-resolve 'graphden.executor.compile-runtime/rebuild!)
               context)
            service-row (sp/create-entity storage :service
                                          {:fn-id (:id stub-fn)
                                           :enabled? true
                                           :restart-policy :always})
            running (atom {})]
        (testing "reconcile-once! starts the http-server-class service"
          (let [r (recon/reconcile-once! context running)]
            (is (= [(:id service-row)] (:started r))
                (str "reconciler classified the row as :to-start; got=" (pr-str r)))
            (is (= 1 (count @running))
                (str "running atom has the entry; got=" (count @running)))
            (let [entry (-> @running vals first)]
              (is (fn? (:stopper entry))
                  "stopper-thunk captured from the stub's return value")
              (is (inst? (:started-at entry))
                  "started-at timestamp recorded for the running entry"))))
        (testing "the stub's `:started` side effect was observed"
          ;; Sanity: the reconciler ACTUALLY ran the start-fn — not
          ;; just bookkept the :to-start classification.
          (is (= [:started] @lifecycle-events)
              (str "lifecycle-events show :started; got=" (pr-str @lifecycle-events))))
        (testing "disabling the :service row + reconcile-once! stops it"
          (sp/update-entity storage :service (:id service-row)
                            {:enabled? false})
          (let [r (recon/reconcile-once! context running)]
            (is (= [(:id service-row)] (:stopped r))
                (str "reconciler classified the row as :to-stop; got=" (pr-str r)))
            (is (zero? (count @running))
                "running atom drained after stop")))
        (testing "the stopper callable was invoked"
          ;; Closes the regression: stopper captured but never
          ;; called. Without this assertion, a reconciler that
          ;; classified :to-stop, removed the row from running-
          ;; atom, but FORGOT to invoke the stopper would pass the
          ;; previous testing block — and would silently leak the
          ;; http-server's network listener / thread.
          (is (= [:started :stopped] @lifecycle-events)
              (str "stopper was invoked after the row was disabled; "
                   "lifecycle-events=" (pr-str @lifecycle-events)))))
      (finally (sys/stop! system)))))
