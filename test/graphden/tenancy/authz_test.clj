(ns graphden.tenancy.authz-test
  "Per-target-namespace write enforcement (PLATFORM_PLAN §4.2 refinement)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.authz :as authz]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.grant :as grant]))


(defn- ns-store
  "Minimal storage exposing `:ns` read-entity from a {ns-id → {:name
   :parent-id}} tree."
  [rows]
  (reify sp/StorageCRUD
    (read-entity [_ entity-name id] (when (= entity-name :ns) (get rows id)))

    (create-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-entities [_ _ _] nil)

    (query-entities [_ _ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(def ^:private store
  (ns-store {"acme" {:name "acme" :parent-id nil}
             "team" {:name "team" :parent-id "acme"}}))


(deftest namespace-path-walks-the-ns-tree
  (is (= "acme" (authz/namespace-path store "acme")))
  (is (= "acme.team" (authz/namespace-path store "team")))
  (is (= "" (authz/namespace-path store nil)) "nil → root")
  (is (= "" (authz/namespace-path store "missing")) "unresolvable → root"))


(deftest writable?-checks-grant-against-the-resolved-path
  (let [grants (grant/static-grant-store
                 [{:subject "alice" :capability :write :namespace "acme"}
                  {:subject "bob" :capability :write :namespace "acme.team"}])]
    (testing "a parent-namespace grant covers descendants"
      (is (authz/writable? grants store {:user "alice"} "acme"))
      (is (authz/writable? grants store {:user "alice"} "team")))
    (testing "a sub-namespace grant does NOT cover the parent"
      (is (authz/writable? grants store {:user "bob"} "team"))
      (is (not (authz/writable? grants store {:user "bob"} "acme"))))
    (testing "no :user → denied"
      (is (not (authz/writable? grants store {} "acme"))))))


(deftest authorize-writer-gates-tenant-fn-writes
  (let [grants (grant/static-grant-store
                 [{:subject "bob" :capability :write :namespace "acme.team"}])
        guard (authz/authorize-writer grants store)]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob"}]
                   (testing "write to a granted namespace passes"
                     (is (nil? (guard :fn {:namespace-id "team"} nil))))
                   (testing "write to an ungranted namespace throws :authz/forbidden"
                     (is (thrown? clojure.lang.ExceptionInfo (guard :fn {:namespace-id "acme"} nil)))
                     (is (= :authz/forbidden
                            (try (guard :fn {:namespace-id "acme"} nil)
                                 (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
                   (testing "a :fn write without a namespace, or a binding with no resolvable
                             fn-id, isn't gated here (storage rejects an invalid write)"
                     (is (nil? (guard :fn {:name "x"} nil)))
                     (is (nil? (guard :binding {:slot-id "s"} nil))))))
    (testing "platform / admin (public org) is never gated"
      (tc/with-org tc/public-org
                   (binding [tc/*current-principal* {:user "nobody"}]
                     (is (nil? (guard :fn {:namespace-id "acme"} nil))))))))


(defn- bind-store
  "Storage resolving :ns, :fn, and :binding read-entity (for binding-write gating)."
  [ns-rows fn-rows binding-rows]
  (reify sp/StorageCRUD
    (read-entity
      [_ entity-name id]
      (case entity-name
        :ns (get ns-rows id)
        :fn (get fn-rows id)
        :binding (get binding-rows id)
        nil))

    (create-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-entities [_ _ _] nil)

    (query-entities [_ _ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest authorize-writer-gates-tenant-binding-writes
  ;; §4.3 R2 Step 2: :binding writes/deletes need :write on the OWNING fn's
  ;; namespace, resolved via data:fn-id (create) or by reading the row (update
  ;; / delete). Granted ns passes; ungranted throws :authz/forbidden.
  (let [storage (bind-store
                  {"acme" {:name "acme" :parent-id nil}
                   "team" {:name "team" :parent-id "acme"}}
                  {"f-team" {:namespace-id "team"}
                   "f-acme" {:namespace-id "acme"}}
                  {"b-team" {:fn-id "f-team"}
                   "b-acme" {:fn-id "f-acme"}})
        grants (grant/static-grant-store
                 [{:subject "bob" :capability :write :namespace "acme.team"}])
        guard (authz/authorize-writer grants storage)]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob"}]
                   (testing "CREATE (data carries :fn-id) — granted ns passes, ungranted denied"
                     (is (nil? (guard :binding {:fn-id "f-team" :slot-id "s" :value 1} nil)))
                     (is (thrown? clojure.lang.ExceptionInfo
                           (guard :binding {:fn-id "f-acme" :slot-id "s"} nil))))
                   (testing "value-only UPDATE — ns resolved by reading the binding by id"
                     (is (nil? (guard :binding {:value 9} "b-team")))
                     (is (thrown? clojure.lang.ExceptionInfo (guard :binding {:value 9} "b-acme"))))
                   (testing "DELETE (data nil) — ns resolved by reading the binding by id"
                     (is (thrown? clojure.lang.ExceptionInfo (guard :binding nil "b-acme"))))))
    (testing "platform (public org) writes bindings freely"
      (tc/with-org tc/public-org
                   (binding [tc/*current-principal* {:user "x"}]
                     (is (nil? (guard :binding {:fn-id "f-acme"} nil))))))))


;; --- per-namespace execute (the :execute-guard seam) ---

(defn- fn+ns-store
  "Storage exposing :ns and :fn read-entity."
  [ns-rows fn-rows]
  (reify sp/StorageCRUD
    (read-entity
      [_ entity-name id]
      (case entity-name
        :ns (get ns-rows id)
        :fn (get fn-rows id)
        nil))

    (create-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-entities [_ _ _] nil)

    (query-entities [_ _ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest authorize-executor-gates-by-the-fns-namespace
  (let [storage (fn+ns-store
                  {"acme" {:name "acme" :parent-id nil}
                   "team" {:name "team" :parent-id "acme"}}
                  {"f-team" {:namespace-id "team"}
                   "f-acme" {:namespace-id "acme"}})
        grants (grant/static-grant-store
                 [{:subject "bob" :capability :execute :namespace "acme.team"}])
        guard (authz/authorize-executor grants)
        ctx {:storage storage}]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob"}]
                   (testing "execute of a fn in a granted namespace passes"
                     (is (nil? (guard ctx "f-team"))))
                   (testing "...but not one in an ungranted namespace"
                     (is (thrown? clojure.lang.ExceptionInfo (guard ctx "f-acme"))))))
    (testing "platform/admin (public org) skips"
      (tc/with-org tc/public-org
                   (binding [tc/*current-principal* {:user "bob"}]
                     (is (nil? (guard ctx "f-acme"))))))
    (testing "system execution (no principal) skips"
      (tc/with-org "acme"
                   (is (nil? (guard ctx "f-acme")))))))


(deftest execute-consults-the-guard-once-at-top-level
  (let [calls (atom 0)
        ctx {:execute-guard (fn [_ctx _fn-id] (swap! calls inc))}
        inner (fn [_] :inner)
        outer (fn [_] (cr/execute ctx inner {:x 1}) :outer)]
    (is (= :outer (cr/execute ctx outer {:y 1})))
    (is (= 1 @calls) "the guard fires once, not on the recursive sub-execute")))


(deftest execute-guard-denial-propagates-and-no-guard-runs-normally
  (testing "a denying guard's throw propagates (→ 403 via the request-scope bridge)"
    (let [ctx {:execute-guard (fn [_ _] (throw (ex-info "no" {:type :authz/forbidden})))}]
      (is (thrown? clojure.lang.ExceptionInfo (cr/execute ctx (fn [_] :ran) {})))))
  (testing "no guard → execute runs unchanged"
    (is (= :ran (cr/execute {} (fn [_] :ran) {})))))
