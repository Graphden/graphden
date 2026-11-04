(ns graphden.packages.web.sql-test
  "Unit tests for `web.sql`'s restricted-path guard — the branch that
   decides whether a tenant's outbound JDBC call is checked and capped
   before it dials.

   `:sql-exec` / `:sql-query` themselves need a live database and are
   covered by the integration path; what is decided WITHOUT a socket is
   the guard: on the restricted (tenant) path the target is SSRF-checked
   and the per-org outbound rate is charged, and the return value is
   what turns the statement timeout and the row cap on."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.sql-pool :as sql-pool]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.test-infra.impls :as impls]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(use-fixtures :once (impls/impls-fixture "web" "sql"))


(defn- priv
  [sym]
  (let [v (ns-resolve 'graphden.packages.web.sql.impls sym)]
    (assert v (str "no such var: " sym))
    @v))


(deftest guard-restricted!-is-a-noop-on-the-platform-path
  (let [f (priv 'guard-restricted!)]
    (testing "no `*allowed-effects*` binding — the unrestricted platform ctx"
      ;; The platform's own JDBC targets (the executor's database) must not
      ;; be SSRF-checked against themselves, so the guard answers false and
      ;; touches nothing — including a URL the restricted path would refuse.
      (is (false? (f "jdbc:postgresql://localhost:5432/graphden"))))))


(deftest guard-restricted!-checks-the-target-on-the-tenant-path
  (let [f (priv 'guard-restricted!)]
    (testing "a loopback target is refused for a RESTRICTED caller"
      (binding [cr/*allowed-effects* #{:db :network}]
        (let [thrown (try (f "jdbc:postgresql://localhost:5432/graphden") nil
                          (catch Exception e e))]
          (is (some? thrown) "a tenant must not reach a loopback database")
          (is (= "egress" (namespace (:type (ex-data thrown))))
              (str "expected an :egress/* refusal, got " (pr-str (ex-data thrown)))))))
    (testing "an unparseable target is refused rather than dialed"
      (binding [cr/*allowed-effects* #{}]
        (is (thrown? Exception (f "not-a-jdbc-url")))))))


(deftest restricted-managed-sql-requires-the-scope-seam
  (let [manager (sql-pool/create-manager {:sweep-ms 60000})
        with-datasource (priv 'with-datasource)]
    (try
      (let [failure (try (with-datasource {:sql-pool-manager manager} true
                           "jdbc:postgresql://localhost:5432/test" "u" "p" identity)
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (= :sql/missing-scope (:type (ex-data failure)))))
      (finally (sql-pool/close! manager)))))


(deftest snapshot-storage-does-not-erase-the-pool-branch-key
  (let [scope-of (priv 'scope-of)
        ctx {:sql-branch-id "branch-a" :storage {}
             :sql-pool-scope (constantly {:org "acme" :principal [:user "alice"]})}]
    (is (= {:org "acme" :principal [:user "alice"] :branch "branch-a"}
           (scope-of ctx true)))))


(deftest public-sql-columns-require-an-explicit-bounded-allowlist
  (let [keys-of (priv 'public-column-keys)]
    (is (= [:status :created_at] (keys-of ["status" "created_at"])))
    (doseq [columns [nil [] ["status" "status"] [""] [(str/join (repeat 129 "x"))]
                     (repeat 65 "x")]]
      (is (= :sql/invalid-public-columns
             (:type (ex-data
                      (try (keys-of columns) nil
                           (catch clojure.lang.ExceptionInfo e e)))))))))


(deftest public-sql-output-is-an-explicit-plain-result-boundary
  (let [defs (:base-fn-defs (loader/load-packages ["web"]))
        public-type (:return-type (get defs :sql-query-public))]
    (registry/record-rich-types! :sql-query-public (get defs :sql-query-public))
    (is (= [:list :keyword-map] public-type))
    (is (types/subtype? public-type :jsonb)
        "the published rows can enter a plain JSON handler")
    (is (= public-type
           (check/rule-return :sql-query-public
                              {:password {:type [:secret :text]}
                               :params {:type [:list :jsonb]}}
                              public-type))
        "the credential is consumed without marking allowlisted output")
    (is (not (types/subtype? [:list [:secret :text]] [:list :jsonb]))
        "a nested secret cannot enter the query parameter slot")))
