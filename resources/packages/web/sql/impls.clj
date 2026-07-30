(ns graphden.packages.web.sql.impls
  "Raw JDBC base functions. A connection per call — keep it simple,
   tutorial-scale. If pooling is needed later, gate it behind a
   `:sql-with-pool` variant that takes a pool-id and reuses a
   datasource cached in the executor context.

   RESTRICTED (tenant/cloud) execution — `*allowed-effects*` non-nil — is
   guarded the same way tenant HTTP egress is: the target host is SSRF-checked
   (`egress/check-sql-target!` — no internal / platform-DB / rebinding target)
   and rate-capped (`egress/check-egress-rate!`) before connecting, and the
   statement runs under a query TIMEOUT + row CAP so a slow or huge query can't
   pin the shared executor. The PLATFORM ctx (single-tenant / self-host) dials
   unguarded and uncapped. Both limits are env-tunable.

   Effects: `:db` + `:network` — deliberately NOT `:raw-sql`. `:raw-sql` is the
   marker for arbitrary SQL against the PLATFORM Postgres (`storage/pg`), which
   stays cloud-forbidden (cross-tenant). These base-fns dial a caller-supplied
   EXTERNAL datasource, made safe by the guard above (the SSRF resolver +
   platform-DB block mean they can only ever reach a validated-public host that
   is not the platform DB). So they are gated by `:network` alone — a tenant on
   a tier that grants metered `:network` (`free` / `network` / `dedicated`) may
   connect to its OWN database; `anonymous` (no `:network`) cannot."
  (:require
    [graphden.clients.egress :as egress]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def ^:private restricted-timeout-sec
  "Statement timeout (seconds) for a RESTRICTED tenant query — bounds a slow /
   runaway query against the shared executor. `GRAPHDEN_TENANT_SQL_TIMEOUT_SEC`."
  (or (some-> (System/getenv "GRAPHDEN_TENANT_SQL_TIMEOUT_SEC") parse-long) 10))


(def ^:private restricted-max-rows
  "Row cap for a RESTRICTED tenant SELECT (`.setMaxRows`) — bounds the result a
   tenant can pull into shared memory. `GRAPHDEN_TENANT_SQL_MAX_ROWS`."
  (or (some-> (System/getenv "GRAPHDEN_TENANT_SQL_MAX_ROWS") parse-long) 10000))


(defn- datasource
  [url user password]
  (jdbc/get-datasource {:jdbcUrl url :user user :password password}))


(defn- guard-restricted!
  "On the RESTRICTED tenant path (`*allowed-effects*` non-nil), SSRF-check the
   JDBC target host and apply the per-org outbound rate cap before connecting.
   No-op for the unrestricted platform ctx. Returns whether the ctx is
   restricted, so the caller applies the matching timeout / row cap."
  [url]
  (let [restricted? (some? cr/*allowed-effects*)]
    (when restricted?
      (egress/check-sql-target! url)
      (egress/check-egress-rate!))
    restricted?))


(defbase sql-exec
  [url user password sql params]
  (cr/record-effect! :db)
  ;; External JDBC connection to a caller-supplied host — a real outbound
  ;; network effect, so the cloud sandbox can gate it (`:db` alone is
  ;; cloud-allowed and would let a restricted graph open arbitrary sockets).
  (cr/record-effect! :network)
  ;; NOT `:raw-sql` — that marks raw SQL against the PLATFORM Postgres
  ;; (`storage/pg`), which stays cloud-forbidden. This dials a caller-supplied
  ;; EXTERNAL datasource made safe by the guard below (SSRF + platform-DB
  ;; block), so `:network` is the correct gate. See the ns docstring.
  (let [restricted? (guard-restricted! url)
        ds (datasource url user password)
        ;; `params` arrives from the fn-graph as a Clojure vector
        ;; (or seq) — JDBC wants a `[sql & params]` vector for the
        ;; first arg to `execute-one!`.
        stmt (into [sql] (or params []))
        ;; A restricted tenant statement runs under a query timeout so a slow
        ;; DML/DDL can't pin the shared executor.
        opts (cond-> {:return-keys false}
               restricted? (assoc :timeout restricted-timeout-sec))
        result (jdbc/execute-one! ds stmt opts)]
    ;; DDL / DML responses both come back as `{:next.jdbc/update-count N}`
    ;; (DDL is 0). Surface the int so downstream graph code can branch
    ;; on it without record-shape acrobatics.
    (long (or (:next.jdbc/update-count result) 0))))


(defbase sql-query
  [url user password sql params]
  (cr/record-effect! :db)
  ;; External JDBC connection — outbound network (see `sql-exec`).
  (cr/record-effect! :network)
  ;; NOT `:raw-sql` (see `sql-exec` + the ns docstring) — external datasource,
  ;; gated by `:network` and made safe by the egress guard below.
  (let [restricted? (guard-restricted! url)
        ds (datasource url user password)
        stmt (into [sql] (or params []))
        ;; A restricted tenant SELECT runs under a query timeout + a server-side
        ;; row cap (`.setMaxRows`) so it can't pull an unbounded result into the
        ;; shared executor's heap.
        opts (cond-> {:builder-fn rs/as-unqualified-maps}
               restricted? (assoc :timeout restricted-timeout-sec
                                  :max-rows restricted-max-rows))]
    ;; `as-unqualified-maps` gives `{:column value}` rather than
    ;; `{:table/column value}` — easier for downstream `:get` calls.
    ;; Realise into a vector so the lazy result-set doesn't outlive
    ;; the connection scope (next.jdbc closes it on return).
    (vec (jdbc/execute! ds stmt opts))))


(def impls
  {:sql-exec sql-exec
   :sql-query sql-query})
