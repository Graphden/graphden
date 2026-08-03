(ns graphden.tenancy.app-router-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.app-router :as app]
    [graphden.tenancy.context :as tc]))


(defn- app-storage
  "Fake storage: `query-entities :app-route {:label L}` → the GLOBAL route row
   for L (`{:org :label :handler-fn-id}`) from `{label {:org … :handler-fn-id …}}`,
   or [] when unrouted (Track C model A — flat apps-domain namespace)."
  [label->route]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (when (= en :app-route)
        (when-let [r (get label->route (:label where))]
          [(assoc r :label (:label where))])))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(defn- req
  [host]
  {:headers {"host" host}})


(deftest app-handler-target-test
  (let [handler-id (random-uuid)
        storage (app-storage {"acme" {:org "acme-org" :handler-fn-id handler-id}
                              "beta" {:org "beta-org" :handler-fn-id nil}})]
    (testing "an apps-domain label → the global route's org + handler"
      (is (= {:org "acme-org" :label "acme" :handler-fn-id handler-id}
             (app/app-handler-target storage (req "acme.graphden.app") "graphden.app" nil))))
    (testing "a routed label with no handler → nil handler-fn-id (→ 404)"
      (is (= {:org "beta-org" :label "beta" :handler-fn-id nil}
             (app/app-handler-target storage (req "beta.graphden.app") "graphden.app" nil))))
    (testing "an UNROUTED apps-domain label → still an app request, nil org+handler (→ 404)"
      (is (= {:org nil :label "ghost" :handler-fn-id nil}
             (app/app-handler-target storage (req "ghost.graphden.app") "graphden.app" nil))))
    (testing "the apps-domain apex is not an app request → nil (→ editor/API)"
      (is (nil? (app/app-handler-target storage (req "graphden.app") "graphden.app" nil))))
    (testing "an editor subdomain on graphden.dev is NOT an app → nil (→ editor)"
      (is (nil? (app/app-handler-target storage (req "acme.graphden.dev") "graphden.app" nil))))))


(deftest run-with-timeout-test
  (testing "a fast thunk returns its value"
    (is (= :done (cr/run-with-timeout 1000 (fn [] :done)))))
  (testing "an overrunning thunk → ::timeout (request bounded)"
    (is (= :graphden.executor.compile-runtime/timeout
           (cr/run-with-timeout 30 (fn [] (Thread/sleep 2000) :done)))))
  (testing "a throwing thunk → ::error"
    (is (= :graphden.executor.compile-runtime/error
           (cr/run-with-timeout 1000 (fn [] (throw (RuntimeException. "boom"))))))))


(deftest make-app-router-non-execution-paths
  (let [handler-id (random-uuid)
        storage (app-storage {"acme" {:org "acme-org" :handler-fn-id handler-id}})
        ar (app/make-app-router "graphden.app" nil)
        ctx {:storage storage}]
    (testing "apex → nil → dispatch falls through to editor/API"
      (is (nil? (ar ctx (req "graphden.app")))))
    (testing "an unrouted apps-domain label → 404 (it's an app request, don't fall through)"
      (is (= 404 (:status (ar ctx (req "gone.graphden.app"))))))))


;; ============================================================================
;; Shard routing. A pod compiles only `:executor-orgs` (see
;; `compile-runtime/org-in-shard?`), so a request for an org it doesn't hold
;; must say "wrong pod" rather than pretend the app isn't deployed. The org is
;; the app-route's owner.
;; ============================================================================

(deftest make-app-router-misdirected-when-org-not-in-shard
  (let [handler-id (random-uuid)
        storage (app-storage {"acme" {:org "acme" :handler-fn-id handler-id}
                              "beta" {:org "beta" :handler-fn-id handler-id}})
        ar (app/make-app-router "graphden.app" nil)]
    (testing "no shard configured → every org is ours (self-hosted default)"
      (let [ctx {:storage storage}]
        (is (not= 421 (:status (ar ctx (req "acme.graphden.app")))))))

    (testing "org outside this pod's shard → 421, not 404"
      (let [ctx {:storage storage :executor-orgs #{"public" "acme"}}]
        (is (= 421 (:status (ar ctx (req "beta.graphden.app")))))))

    (testing "an org we DO hold is not misdirected"
      (let [ctx {:storage storage :executor-orgs #{"public" "acme"}}]
        (is (not= 421 (:status (ar ctx (req "acme.graphden.app")))))))

    (testing "a shard predicate works the same as a set"
      (let [ctx {:storage storage :executor-orgs (fn [o] (= "acme" o))}]
        (is (= 421 (:status (ar ctx (req "beta.graphden.app")))))
        (is (not= 421 (:status (ar ctx (req "acme.graphden.app")))))))

    (testing "an unconfigured route in our shard still reads as 404, not 421"
      (let [s (app-storage {"beta" {:org "beta" :handler-fn-id nil}})
            ctx {:storage s :executor-orgs #{"beta"}}]
        (is (= 404 (:status (ar ctx (req "beta.graphden.app")))))))))


(deftest make-app-router-forward-hops-before-421
  ;; T2.6: a misdirected request consults the `:fleet-forward` seam BEFORE
  ;; 421'ing — if the org's cell is placed elsewhere the request is proxied
  ;; there; only a nil seam result falls through to the 421 backstop.
  (let [handler-id (random-uuid)
        storage (app-storage {"acme" {:org "acme" :handler-fn-id handler-id}
                              "beta" {:org "beta" :handler-fn-id handler-id}})
        ar (app/make-app-router "graphden.app" nil)
        forwarded {:status 200 :headers {"X-Served-By" "holder"} :body "forwarded"}]
    (testing "misdirected + a seam that finds a holder → forwards, not 421"
      (let [ctx {:storage storage :executor-orgs #{"public" "acme"}
                 :fleet-forward (fn [_req org entry]
                                  (when (and (= org "beta") (= entry handler-id)) forwarded))}]
        (is (= forwarded (ar ctx (req "beta.graphden.app")))
            "beta's request proxied to its holder with the handler-fn-id as the cell entry")))

    (testing "seam returns nil (no placement / byo org) → 421 backstop"
      (let [ctx {:storage storage :executor-orgs #{"public" "acme"}
                 :fleet-forward (fn [_ _ _] nil)}]
        (is (= 421 (:status (ar ctx (req "beta.graphden.app")))))))

    (testing "no seam wired (single-tenant / self-hosted) → 421 as before"
      (let [ctx {:storage storage :executor-orgs #{"public" "acme"}}]
        (is (= 421 (:status (ar ctx (req "beta.graphden.app")))))))

    (testing "an org we DO hold is served locally — the seam is never consulted"
      (let [consulted (atom false)
            ctx {:storage storage :executor-orgs #{"public" "acme"}
                 :fleet-forward (fn [_ _ _] (reset! consulted true) nil)}]
        (is (not= 421 (:status (ar ctx (req "acme.graphden.app")))))
        (is (false? @consulted) "held org never hits the forward path")))))


;; ============================================================================
;; BYO refusal — a hosted pod 421s a :byo org (it runs on the customer's own
;; executor); a BYO executor pod serves it. The byo check reads the app owner's
;; `:org.execution-mode`, so the fake storage serves both :app-route + :org.
;; ============================================================================

(defn- app-storage-with-mode
  "Fake storage: `:app-route` by label → `{:org :handler-fn-id}`, AND `:org` by
   name → `{:execution-mode}` (from `name->mode`), so the byo check resolves."
  [label->org name->mode]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (cond
        (= en :app-route)
        (when-let [o (get label->org (:label where))]
          [{:org o :label (:label where) :handler-fn-id (random-uuid)}])

        (= en :org)
        (let [n (:name where)]
          (when (contains? name->mode n)
            [{:name n :execution-mode (get name->mode n)}]))))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest make-app-router-421s-byo-org-on-a-hosted-pod
  (tc/invalidate-byo-cache!)
  (let [storage (app-storage-with-mode {"byoapp" "acmebyo" "hostedapp" "acmehosted"}
                                       {"acmebyo" "byo" "acmehosted" "hosted"})
        ar (app/make-app-router "graphden.app" nil)]
    (testing "hosted pod (no :byo-executor?) → 421 for a :byo org's app"
      (let [ctx {:storage storage}]
        (is (= 421 (:status (ar ctx (req "byoapp.graphden.app")))))))
    (testing "hosted pod serves a :hosted org's app normally (not 421)"
      (let [ctx {:storage storage}]
        (is (not= 421 (:status (ar ctx (req "hostedapp.graphden.app")))))))
    (testing "a BYO executor pod serves its :byo org's app"
      (let [ctx {:storage storage :byo-executor? true}]
        (is (not= 421 (:status (ar ctx (req "byoapp.graphden.app")))))))
    (tc/invalidate-byo-cache!)))
