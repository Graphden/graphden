(ns graphden.tenancy.app-router-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.app-router :as app]
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


(deftest run-with-timeout-test
  (testing "a fast thunk returns its value"
    (is (= :done (app/run-with-timeout 1000 (fn [] :done)))))
  (testing "an overrunning thunk → ::timeout (request bounded)"
    (is (= :graphden.tenancy.app-router/timeout
           (app/run-with-timeout 30 (fn [] (Thread/sleep 2000) :done)))))
  (testing "a throwing thunk → ::error"
    (is (= :graphden.tenancy.app-router/error
           (app/run-with-timeout 1000 (fn [] (throw (RuntimeException. "boom"))))))))


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
