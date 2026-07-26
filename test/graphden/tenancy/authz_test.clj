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
                 [{:subject-id "alice" :subject "alice" :capability :write :namespace "acme"}
                  {:subject-id "bob" :subject "bob" :capability :write :namespace "acme.team"}])]
    (testing "a parent-namespace grant covers descendants"
      (is (authz/writable? grants store {:user "alice" :user-id "alice"} "acme"))
      (is (authz/writable? grants store {:user "alice" :user-id "alice"} "team")))
    (testing "a sub-namespace grant does NOT cover the parent"
      (is (authz/writable? grants store {:user "bob" :user-id "bob"} "team"))
      (is (not (authz/writable? grants store {:user "bob" :user-id "bob"} "acme"))))
    (testing "no :user → denied"
      (is (not (authz/writable? grants store {} "acme"))))))


(deftest authorize-writer-gates-tenant-fn-writes
  (let [grants (grant/static-grant-store
                 [{:subject-id "bob" :subject "bob" :capability :write :namespace "acme.team"}])
        guard (authz/authorize-writer grants store)]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob" :user-id "bob"}]
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
                   (binding [tc/*current-principal* {:user "nobody" :user-id "nobody"}]
                     (is (nil? (guard :fn {:namespace-id "acme"} nil))))))))


;; Security-critical (P0 stage 2b): the read-path filter conceals a fn's
;; internal composition from a viewer who neither owns it (own-org) nor holds
;; a :view-impl grant. A leak here = a tenant reading a public/shared fn's
;; parent chain + bindings. `store` above is the ns-store (namespace-path).
(deftest view-impl-filter-hides-non-owned-internals
  (let [graph {:fns        [{:id "own" :org-id "acme"   :parent-ids ["p"] :namespace-id "team"}
                            {:id "pub" :org-id "public" :parent-ids ["q"] :namespace-id "team"}]
               :bindings   [{:id "b-own" :fn-id "own" :value 1}
                            {:id "b-pub" :fn-id "pub" :value 2}]
               :list-items []}
        parents-of (fn [r id] (:parent-ids (first (filter #(= id (:id %)) (:fns r)))))]
    (testing "a tenant sees its OWN org's internals, but not a public/shared fn's"
      (binding [tc/*current-org*       "acme"
                tc/*current-principal* {:user "u" :user-id "u" :org "acme"}]
        (let [r (authz/view-impl-filter nil store graph)]
          (is (= ["p"] (parents-of r "own")) "own-org fn keeps its parent chain")
          (is (= [] (parents-of r "pub")) "public fn's parent chain concealed")
          (is (= #{"b-own"} (into #{} (map :id) (:bindings r)))
              "public fn's bindings dropped, own-org's kept"))))
    (testing "an explicit :view-impl grant reveals a public fn's internals"
      (let [grants (grant/static-grant-store
                     [{:subject-kind "user" :subject-id "u"
                       :capability :view-impl :namespace "acme.team"}])]
        (binding [tc/*current-org*       "acme"
                  tc/*current-principal* {:user "u" :user-id "u" :org "acme"}]
          (is (= ["q"] (parents-of (authz/view-impl-filter grants store graph) "pub"))
              "granted :view-impl on the fn's namespace → internals visible"))))
    (testing "the platform / admin context (public org) sees everything"
      (binding [tc/*current-org*       tc/public-org
                tc/*current-principal* {:user "op" :user-id "op" :org tc/public-org}]
        (is (= graph (authz/view-impl-filter nil store graph)))))
    (testing "a dump with no :fns (tree scope) passes through untouched"
      (binding [tc/*current-org*       "acme"
                tc/*current-principal* {:user "u" :user-id "u" :org "acme"}]
        (is (= {:namespaces [] :counts {}}
               (authz/view-impl-filter nil store {:namespaces [] :counts {}})))))))


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
                 [{:subject-id "bob" :subject "bob" :capability :write :namespace "acme.team"}])
        guard (authz/authorize-writer grants storage)]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob" :user-id "bob"}]
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
                   (binding [tc/*current-principal* {:user "x" :user-id "x"}]
                     (is (nil? (guard :binding {:fn-id "f-acme"} nil))))))))


(deftest authorize-writer-gates-namespace-move-by-both-source-and-target
  ;; A namespace MOVE of an existing fn must carry :write on BOTH the fn's
  ;; current (source) namespace AND the target — otherwise a holder of the
  ;; target namespace alone could pull a fn out of a namespace they were
  ;; deliberately not granted. A CREATE (id nil) has no source to check.
  (let [storage (bind-store
                  {"acme" {:name "acme" :parent-id nil}
                   "nx"   {:name "x" :parent-id "acme"}
                   "ny"   {:name "y" :parent-id "acme"}}
                  {"f-y" {:namespace-id "ny"}}
                  {})
        ;; alice: :write on acme.x only (NOT acme.y).
        alice-grants (grant/static-grant-store
                       [{:subject-id "alice" :subject "alice" :capability :write :namespace "acme.x"}])
        ;; root: :write on all of acme (covers both x and y).
        both-grants  (grant/static-grant-store
                       [{:subject-id "root" :subject "root" :capability :write :namespace "acme"}])]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "alice" :user-id "alice"}]
                   (let [guard (authz/authorize-writer alice-grants storage)]
                     (testing "CREATE into a granted target (no source) passes"
                       (is (nil? (guard :fn {:namespace-id "nx"} nil))))
                     (testing "MOVE into a granted target but OUT of an ungranted source is denied"
                       (is (thrown? clojure.lang.ExceptionInfo
                             (guard :fn {:namespace-id "nx"} "f-y")))
                       (is (= :authz/forbidden
                              (try (guard :fn {:namespace-id "nx"} "f-y")
                                   (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))
                 (binding [tc/*current-principal* {:user "root" :user-id "root"}]
                   (let [guard (authz/authorize-writer both-grants storage)]
                     (testing "MOVE passes when :write is held on both source and target"
                       (is (nil? (guard :fn {:namespace-id "nx"} "f-y")))))))))


(deftest authorize-writer-narrows-by-capability
  ;; §4.3 R2 Step 3: the required cap narrows by the edit — :bind-args for a
  ;; value-only binding tweak, :append-list for list-item writes, :write for
  ;; anything structural. :write / :admin subsume the narrow caps.
  (let [storage (bind-store
                  {"acme" {:name "acme" :parent-id nil}
                   "team" {:name "team" :parent-id "acme"}}
                  {"f-team" {:namespace-id "team"}}
                  {"b-team" {:fn-id "f-team"}})
        guard-of (fn [caps]
                   (authz/authorize-writer
                     (grant/static-grant-store
                       (for [c caps] {:subject-id "u" :subject "u" :capability c :namespace "acme.team"}))
                     storage))]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "u" :user-id "u"}]
                   (testing ":bind-args allows a value-only binding update"
                     (is (nil? ((guard-of [:bind-args]) :binding
                                                        {:value 7 :value-present true} "b-team"))))
                   (testing ":bind-args denies a ref/structure change or a create (need :write)"
                     (is (thrown? clojure.lang.ExceptionInfo
                           ((guard-of [:bind-args]) :binding {:ref-fn-id "g"} "b-team")))
                     (is (thrown? clojure.lang.ExceptionInfo
                           ((guard-of [:bind-args]) :binding
                                                    {:fn-id "f-team" :slot-id "s" :value 1} nil)))
                     ;; behaviour-changing overlays are structural too — rename
                     ;; a free-arg, cap the chain, seal a sequence → need :write.
                     (is (thrown? clojure.lang.ExceptionInfo
                           ((guard-of [:bind-args]) :binding {:rename-to "x"} "b-team")))
                     (is (thrown? clojure.lang.ExceptionInfo
                           ((guard-of [:bind-args]) :binding {:terminal true} "b-team")))
                     (is (thrown? clojure.lang.ExceptionInfo
                           ((guard-of [:bind-args]) :binding {:list-closed true} "b-team"))))
                   (testing ":bind-args does NOT grant :append-list (one-way)"
                     (is (thrown? clojure.lang.ExceptionInfo
                           ((guard-of [:bind-args]) :binding-list-item {:binding-id "b-team"} nil))))
                   (testing ":append-list allows list-item writes but not binding value-edits"
                     (is (nil? ((guard-of [:append-list]) :binding-list-item
                                                          {:binding-id "b-team"} nil)))
                     (is (thrown? clojure.lang.ExceptionInfo
                           ((guard-of [:append-list]) :binding
                                                      {:value 7 :value-present true} "b-team"))))
                   (testing ":write subsumes both narrow caps"
                     (is (nil? ((guard-of [:write]) :binding {:value 7 :value-present true} "b-team")))
                     (is (nil? ((guard-of [:write]) :binding {:ref-fn-id "g"} "b-team")))
                     (is (nil? ((guard-of [:write]) :binding-list-item {:binding-id "b-team"} nil))))))))


(deftest authorize-writer-shares-the-request-grant-memo
  ;; A batch write fires the guard per row; under a bound per-request memo all
  ;; rows share ONE `:grant` query for the same subject (vs one per row without).
  (let [calls (atom 0)
        base (grant/static-grant-store
               [{:subject-id "alice" :subject "alice" :capability :write :namespace "acme"}])
        counting (reify grant/GrantStore
                   (grants-for
                     [_ subject]
                     (swap! calls inc)
                     (grant/grants-for base subject)))
        guard (authz/authorize-writer counting store)]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "alice" :user-id "alice"}]
                   (testing "no request memo → one :grant query per guarded row"
                     (reset! calls 0)
                     (guard :fn {:namespace-id "acme"} nil)
                     (guard :fn {:namespace-id "acme"} nil)
                     (is (= 2 @calls)))
                   (testing "bound per-request memo → one query shared across rows"
                     (reset! calls 0)
                     (binding [grant/*request-grant-store*
                               (grant/memoizing-grant-store counting)]
                       (guard :fn {:namespace-id "acme"} nil)
                       (guard :fn {:namespace-id "acme"} nil))
                     (is (= 1 @calls)))))))


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
                 [{:subject-id "bob" :subject "bob" :capability :execute :namespace "acme.team"}])
        guard (authz/authorize-executor grants)
        ctx {:storage storage}]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob" :user-id "bob"}]
                   (testing "execute of a fn in a granted namespace passes"
                     (is (nil? (guard ctx "f-team"))))
                   (testing "...but not one in an ungranted namespace"
                     (is (thrown? clojure.lang.ExceptionInfo (guard ctx "f-acme"))))))
    (testing "platform/admin (public org) skips"
      (tc/with-org tc/public-org
                   (binding [tc/*current-principal* {:user "bob" :user-id "bob"}]
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


(deftest authorize-writer-gates-fn-delete-and-reparent-by-existing-namespace
  ;; Regression for the escalation where a :fn DELETE / structural update
  ;; (delta carries no :namespace-id) skipped the per-namespace grant entirely,
  ;; letting a :bind-args holder delete/reparent any fn in the org.
  (let [grants (grant/static-grant-store
                 [{:subject-id "bob" :subject "bob" :capability :write :namespace "acme.team"}])
        storage (bind-store {"acme" {:name "acme" :parent-id nil}
                             "team" {:name "team" :parent-id "acme"}}
                            {"f-team" {:namespace-id "team"}
                             "f-acme" {:namespace-id "acme"}}
                            {})
        guard (authz/authorize-writer grants storage)]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob" :user-id "bob"}]
                   (testing "delete of a fn in a GRANTED namespace passes"
                     (is (nil? (guard :fn nil "f-team"))))
                   (testing "delete of a fn in an UNGRANTED namespace → :authz/forbidden"
                     (is (thrown? clojure.lang.ExceptionInfo (guard :fn nil "f-acme")))
                     (is (= :authz/forbidden
                            (try (guard :fn nil "f-acme")
                                 (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
                   (testing "structural update (parent-ids, no :namespace-id) is gated the same"
                     (is (nil? (guard :fn {:parent-ids ["x"]} "f-team")))
                     (is (thrown? clojure.lang.ExceptionInfo (guard :fn {:parent-ids ["x"]} "f-acme"))))
                   (testing "a rootless CREATE (id nil, no namespace) stays ungated"
                     (is (nil? (guard :fn {:name "new"} nil))))))))
