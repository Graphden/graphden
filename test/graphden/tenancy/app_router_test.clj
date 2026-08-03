(ns graphden.tenancy.app-router-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.app-router :as app]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.subdomain :as subdomain]))


(defn- org-storage
  "Fake storage: `query-entities :org {:name n}` → the row for n (with its
   handler-fn-id, possibly nil) when present, else []."
  [name->handler]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (when (= en :org)
        (let [n (:name where)]
          (when (contains? name->handler n)
            [{:name n :handler-fn-id (get name->handler n)}]))))

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
        storage (org-storage {"acme" handler-id "beta" nil})
        resolver (subdomain/identity-org-resolver)]
    (testing "tenant subdomain → its org + handler"
      (is (= {:org "acme" :handler-fn-id handler-id}
             (app/app-handler-target storage (req "acme.graphden.app") resolver "graphden.app" nil))))
    (testing "org exists but no handler configured → nil handler-fn-id (→ 404)"
      (is (= {:org "beta" :handler-fn-id nil}
             (app/app-handler-target storage (req "beta.graphden.app") resolver "graphden.app" nil))))
    (testing "apex (no subdomain) → nil → not an app request"
      (is (nil? (app/app-handler-target storage (req "graphden.app") resolver "graphden.app" nil))))
    (testing "unknown subdomain → org named but no row → nil handler-fn-id"
      (is (= {:org "ghost" :handler-fn-id nil}
             (app/app-handler-target storage (req "ghost.graphden.app") resolver "graphden.app" nil))))))


(defn- app-route-storage
  "Fake storage answering `query-entities :app-route {:org o :label l}` from an
   `{[org label] handler-fn-id}` map (Track C — named apps)."
  [key->handler]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (when (= en :app-route)
        (when-let [h (get key->handler [(:org where) (:label where)])]
          [{:org (:org where) :label (:label where) :handler-fn-id h}])))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest app-handler-target-two-level-named-apps
  ;; Track C: <label>.<org>.base resolves to the org's named app via :app-route,
  ;; NOT the org's default :handler-fn-id.
  (let [shop-fn (random-uuid)
        storage (app-route-storage {["acme" "shop"] shop-fn})
        resolver (subdomain/identity-org-resolver)]
    (testing "two-level host → {:org :label :handler-fn-id} from :app-route"
      (is (= {:org "acme" :label "shop" :handler-fn-id shop-fn}
             (app/app-handler-target storage (req "shop.acme.graphden.app") resolver "graphden.app" nil))))
    (testing "an org's unconfigured label → nil handler (→ 404), still an app request"
      (is (= {:org "acme" :label "docs" :handler-fn-id nil}
             (app/app-handler-target storage (req "docs.acme.graphden.app") resolver "graphden.app" nil))))
    (testing "the single-level org host stays the legacy default-app path (no :label)"
      (is (nil? (:label (app/app-handler-target (org-storage {"acme" (random-uuid)})
                                                (req "acme.graphden.app") resolver "graphden.app" nil)))))))


(deftest make-app-router-serves-named-app-404-when-unconfigured
  (let [shop-fn (random-uuid)
        storage (app-route-storage {["acme" "shop"] shop-fn})
        ar (app/make-app-router (subdomain/identity-org-resolver) "graphden.app" nil)
        ctx {:storage storage}]
    (testing "a two-level host with no :app-route row → 404 (app request, not editor)"
      (is (= 404 (:status (ar ctx (req "gone.acme.graphden.app"))))))))


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
        storage (org-storage {"acme" handler-id "beta" nil})
        ar (app/make-app-router (subdomain/identity-org-resolver) "graphden.app" nil)
        ctx {:storage storage}]
    (testing "apex → nil → dispatch falls through to editor/API"
      (is (nil? (ar ctx (req "graphden.app")))))
    (testing "org with no handler → 404 (it's an app request, don't fall through)"
      (is (= 404 (:status (ar ctx (req "beta.graphden.app"))))))))


;; ============================================================================
;; Shard routing. A pod compiles only `:executor-orgs` (see
;; `compile-runtime/org-in-shard?`), so a request for an org it doesn't hold
;; must say "wrong pod" rather than pretend the app isn't deployed.
;; ============================================================================

(deftest make-app-router-misdirected-when-org-not-in-shard
  (let [handler-id (random-uuid)
        storage (org-storage {"acme" handler-id "beta" handler-id})
        ar (app/make-app-router (subdomain/identity-org-resolver) "graphden.app" nil)]
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

    (testing "an unconfigured org in our shard still reads as 404, not 421"
      (let [s (org-storage {"beta" nil})
            ctx {:storage s :executor-orgs #{"beta"}}]
        (is (= 404 (:status (ar ctx (req "beta.graphden.app")))))))))


(deftest make-app-router-forward-hops-before-421
  ;; T2.6: a misdirected request consults the `:fleet-forward` seam BEFORE
  ;; 421'ing — if the org's cell is placed elsewhere the request is proxied
  ;; there; only a nil seam result falls through to the 421 backstop.
  (let [handler-id (random-uuid)
        storage (org-storage {"acme" handler-id "beta" handler-id})
        ar (app/make-app-router (subdomain/identity-org-resolver) "graphden.app" nil)
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
;; executor); a BYO executor pod serves it.
;; ============================================================================

(defn- org-storage-with-mode
  "Fake storage whose `:org` rows carry `:execution-mode`. `name->mode` maps
   org → \"hosted\"/\"byo\" (all orgs also get a dummy handler so the app path
   reaches the byo check, not the not-configured branch)."
  [name->mode]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (when (= en :org)
        (let [n (:name where)]
          (when (contains? name->mode n)
            [{:name n :handler-fn-id (random-uuid) :execution-mode (get name->mode n)}]))))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest make-app-router-421s-byo-org-on-a-hosted-pod
  (tc/invalidate-byo-cache!)
  (let [storage (org-storage-with-mode {"acmebyo" "byo" "acmehosted" "hosted"})
        ar (app/make-app-router (subdomain/identity-org-resolver) "graphden.app" nil)]
    (testing "hosted pod (no :byo-executor?) → 421 for a :byo org"
      (let [ctx {:storage storage}]
        (is (= 421 (:status (ar ctx (req "acmebyo.graphden.app")))))))
    (testing "hosted pod serves a :hosted org normally (not 421)"
      (let [ctx {:storage storage}]
        (is (not= 421 (:status (ar ctx (req "acmehosted.graphden.app")))))))
    (testing "a BYO executor pod serves its :byo org"
      (let [ctx {:storage storage :byo-executor? true}]
        (is (not= 421 (:status (ar ctx (req "acmebyo.graphden.app")))))))
    (tc/invalidate-byo-cache!)))
