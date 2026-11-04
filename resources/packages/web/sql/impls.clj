(ns graphden.packages.web.sql.impls
  "Raw JDBC base functions with executor-owned, bounded external pools.
   The user-facing query/exec signature remains a JDBC URL and credentials;
   pool ownership and credential generations are internal.

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
    [clojure.string :as str]
    [graphden.clients.egress :as egress]
    [graphden.clients.sql-pool :as sql-pool]
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


(defn- scope-of
  [ctx restricted?]
  (let [provider (:sql-pool-scope ctx)
        scope (when provider (provider ctx))]
    (when (and restricted? (nil? provider))
      (throw (ex-info "External SQL pool scope unavailable"
                      {:type :sql/missing-scope})))
    (when (and scope
               (not (and (map? scope) (some? (:org scope))
                         (some? (:principal scope)))))
      (throw (ex-info "Invalid external SQL pool scope"
                      {:type :sql/invalid-scope})))
    (when (or scope (nil? provider))
      (assoc (or scope {:org :platform :principal :system})
             :branch (or (:sql-branch-id ctx)
                         (:branch-id (:storage ctx))
                         (:branch (:storage ctx)) :default)))))


(defn- with-datasource
  [ctx restricted? url user password f]
  (let [manager (:sql-pool-manager ctx)
        scope (when manager (scope-of ctx restricted?))]
    (if (and manager scope (str/starts-with? url "jdbc:postgresql:"))
      (sql-pool/with-connection manager scope url user password f)
      ;; Anonymous callers, bare test contexts and non-PostgreSQL drivers use
      ;; a one-shot connection, so session state cannot cross callers.
      (with-open [conn (jdbc/get-connection (datasource url user password))]
        (f conn)))))


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
        ;; `params` arrives from the fn-graph as a Clojure vector
        ;; (or seq) — JDBC wants a `[sql & params]` vector for the
        ;; first arg to `execute-one!`.
        stmt (into [sql] (or params []))
        ;; A restricted tenant statement runs under a query timeout so a slow
        ;; DML/DDL can't pin the shared executor.
        opts (cond-> {:return-keys false}
               restricted? (assoc :timeout restricted-timeout-sec))
        result (with-datasource ctx restricted? url user password
                 #(jdbc/execute-one! % stmt opts))]
    ;; DDL / DML responses both come back as `{:next.jdbc/update-count N}`
    ;; (DDL is 0). Surface the int so downstream graph code can branch
    ;; on it without record-shape acrobatics.
    (long (or (:next.jdbc/update-count result) 0))))


(defn- query-rows
  [ctx url user password sql params]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (let [restricted? (guard-restricted! url)
        stmt (into [sql] (or params []))
        opts (cond-> {:builder-fn rs/as-unqualified-maps}
               restricted? (assoc :timeout restricted-timeout-sec
                                  :max-rows restricted-max-rows))]
    (with-datasource ctx restricted? url user password
      #(vec (jdbc/execute! % stmt opts)))))


(defbase sql-query
  [url user password sql params]
  (query-rows ctx url user password sql params))


(defn- public-column-keys
  "Require an explicit, bounded allowlist. For nonempty results, an absent or
   misspelled column is refused rather than returning an incomplete row."
  [columns]
  (let [columns (when (sequential? columns) (vec (take 65 columns)))]
    (when-not (and (<= 1 (count columns) 64)
                   (every? #(and (string? %) (<= 1 (count %) 128)
                                 (not (str/blank? %))) columns)
                   (= (count columns) (count (distinct columns))))
      (throw (ex-info "Expected 1–64 distinct public SQL column names"
                      {:type :sql/invalid-public-columns})))
    (mapv keyword columns)))


(defbase sql-query-public
  "Explicitly publish only named columns from a caller-owned SQL result.
   The graph author chooses the public fields; all other fields are discarded
   before this plain result leaves the trusted JDBC boundary."
  [url user password sql params public-columns]
  (let [keys (public-column-keys public-columns)
        rows (query-rows ctx url user password sql params)]
    (mapv (fn [row]
            (when-not (every? #(contains? row %) keys)
              (throw (ex-info "Public SQL column missing from result"
                              {:type :sql/public-column-missing})))
            (select-keys row keys))
          rows)))


(def impls
  {:sql-exec {:impl sql-exec :taint-propagate? true}
   :sql-query {:impl sql-query :taint-propagate? true}
   ;; Deliberate declassification boundary: only explicitly listed columns
   ;; leave the JDBC call, while the credential slot remains secret-aware.
   :sql-query-public {:impl sql-query-public}})
