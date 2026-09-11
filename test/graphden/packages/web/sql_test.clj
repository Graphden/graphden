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
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.test-infra.impls :as impls]))


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
