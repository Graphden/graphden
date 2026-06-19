(ns graphden.dev.e2e-stack
  "Ephemeral container stack for the Playwright browser-test suite.

   Why this exists: e2e tests mutate live state (create secrets,
   branches, fn-defs). Run them against the always-up demo on :9002
   and that demo accumulates test detritus across every invocation.
   This namespace spins up a parallel, ISOLATED stack — its own
   PG/openbao/executor — runs the suite, tears it down. The demo
   stays clean.

   Why Testcontainers over a sibling `docker-compose.e2e.yml`:
   - **Cleanup is bulletproof.** Ryuk (the testcontainers reaper)
     kills every container if this JVM dies for any reason, including
     SIGKILL. A `(finally (down))` only fires if the Clojure code
     reaches it.
   - **Zero port collisions.** `getMappedPort` allocates fresh host
     ports each run; parallel e2e on the same host coexists.
   - **Same primitives as `bb test-integration`.** The integration
     suite already uses `PostgreSQLContainer` and `GenericContainer`
     for its fixtures; readers learn one container pattern.
   - **No YAML drift.** A `docker-compose.e2e.yml` mirroring the
     demo's compose has to be kept in sync as the demo grows env
     vars / depends-on edges. Here those constants live next to the
     code that consumes them.

   Entry point: `(-main \"tools/browser-test/run-edit-tests.sh\")` —
   invoked by `bb test-e2e`. Brings up the stack, sets
   `GRAPHDEN_URL` / `AUTH_TOKEN` for the runner, streams stdout
   live, exits with the runner's exit code, then stops every
   container in reverse start order (Ryuk is the safety net)."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [graphden.clients.vault :as vault]
    [org.httpkit.client :as http])
  (:import
    (org.testcontainers.containers
      GenericContainer
      Network
      PostgreSQLContainer)
    (org.testcontainers.containers.wait.strategy
      HttpWaitStrategy
      Wait)))


;; =============================================================================
;; Container builders — each returns a STARTED container.
;; =============================================================================

(defn- start-postgres!
  "Graphden's main PostgreSQL — same image + creds as the demo's
   `graphden-postgres` so the executor's compile-time defaults
   resolve. Aliased on the network as `postgres` (matches
   `JDBC_URL=jdbc:postgresql://postgres:5432/graphden`)."
  [^Network network]
  (doto (PostgreSQLContainer. "postgres:16-alpine")
    (PostgreSQLContainer/.withDatabaseName "graphden")
    (PostgreSQLContainer/.withUsername "graphden")
    (PostgreSQLContainer/.withPassword "graphden")
    (PostgreSQLContainer/.withNetwork network)
    (PostgreSQLContainer/.withNetworkAliases (into-array String ["postgres"]))
    (PostgreSQLContainer/.withCommand "postgres -c max_connections=100")
    (PostgreSQLContainer/.start)))


(defn- start-user-postgres!
  "Tutorial userdb — simulates a tenant DB the executor's fn-graph
   reaches via `:sql-query` / vault-pulled creds. Aliased as
   `user-postgres` to match the openbao seed entries."
  [^Network network]
  (doto (PostgreSQLContainer. "postgres:16-alpine")
    (PostgreSQLContainer/.withDatabaseName "userdb")
    (PostgreSQLContainer/.withUsername "userapp")
    (PostgreSQLContainer/.withPassword "userpass")
    (PostgreSQLContainer/.withNetwork network)
    (PostgreSQLContainer/.withNetworkAliases (into-array String ["user-postgres"]))
    (PostgreSQLContainer/.start)))


(defn- start-openbao!
  "OpenBao dev mode — in-memory KV v2, single root token. Mirrors
   the demo's `graphden-openbao` service down to the wait-strategy."
  [^Network network]
  (doto (GenericContainer. "quay.io/openbao/openbao:latest")
    (GenericContainer/.withCommand
      (into-array String ["server" "-dev"
                          "-dev-root-token-id=root"
                          "-dev-listen-address=0.0.0.0:8200"]))
    (GenericContainer/.withEnv "BAO_DEV_ROOT_TOKEN_ID" "root")
    (GenericContainer/.withEnv "BAO_DEV_LISTEN_ADDRESS" "0.0.0.0:8200")
    (GenericContainer/.withExposedPorts
      (into-array Integer [(Integer/valueOf 8200)]))
    (GenericContainer/.withNetwork network)
    (GenericContainer/.withNetworkAliases (into-array String ["openbao"]))
    (GenericContainer/.waitingFor
      (-> (Wait/forHttp "/v1/sys/health")
          (HttpWaitStrategy/.forStatusCode 200)))
    (GenericContainer/.start)))


(defn- seed-openbao!
  "Mirror the demo's `openbao-seed` one-shot — enable the KV v2
   mount and write the four user-db / api-token / history-port keys
   that tutorial fn-defs read at runtime. Idempotent: KV v2 writes
   overwrite, so a second invocation is harmless. Hits the HOST-side
   mapped port (the seed runs from the test JVM, not from inside
   the docker network)."
  [^GenericContainer openbao]
  (let [host (GenericContainer/.getHost openbao)
        port (GenericContainer/.getMappedPort openbao
                                              (Integer/valueOf 8200))
        addr (str "http://" host ":" port)
        client {:address addr :token "root"}]
    ;; Mount KV v2 at /kv. 400 if already mounted (idempotent re-run);
    ;; ignore failures here, they don't block the seed.
    @(http/post (str addr "/v1/sys/mounts/kv")
                {:headers {"X-Vault-Token" "root"
                           "Content-Type" "application/json"}
                 :body (json/generate-string {:type "kv"
                                              :options {:version "2"}})})
    (doseq [[path value]
            [["user-db/url" "jdbc:postgresql://user-postgres:5432/userdb"]
             ["user-db/user" "userapp"]
             ["user-db/password" "userpass"]
             ["api/token" "fake-token-abc123"]
             ["history-port" "8081"]]]
      (vault/put-secret client path value))))


(defn- start-executor!
  "Graphden executor — reuses the `graphden-executor:latest` image
   the demo build produced (so this stack picks up the latest code
   without a second build step). All `JDBC_URL` / `VAULT_ADDR` /
   `:user-postgres` references use the network aliases set above."
  [^Network network auth-token]
  (doto (GenericContainer. "graphden-executor:latest")
    (GenericContainer/.withEnv "PORT" "8080")
    (GenericContainer/.withEnv "STORAGE_TYPE" "postgres")
    (GenericContainer/.withEnv "JDBC_URL"
                               "jdbc:postgresql://postgres:5432/graphden")
    (GenericContainer/.withEnv "DB_USERNAME" "graphden")
    (GenericContainer/.withEnv "DB_PASSWORD" "graphden")
    (GenericContainer/.withEnv "DB_POOL_SIZE" "10")
    (GenericContainer/.withEnv "AUTH_TOKEN" auth-token)
    ;; Demo branches OFF — e2e suite seeds its own branches.
    (GenericContainer/.withEnv "GRAPHDEN_DEMO_BRANCHES_ENABLED" "")
    (GenericContainer/.withEnv "VAULT_ADDR" "http://openbao:8200")
    (GenericContainer/.withEnv "VAULT_TOKEN" "root")
    ;; nREPL off — no debugging surface needed, cuts startup.
    (GenericContainer/.withEnv "GRAPHDEN_NREPL_PORT" "")
    (GenericContainer/.withExposedPorts
      (into-array Integer [(Integer/valueOf 8080)]))
    (GenericContainer/.withNetwork network)
    (GenericContainer/.withNetworkAliases (into-array String ["executor"]))
    (GenericContainer/.waitingFor
      (-> (Wait/forHttp "/health")
          (HttpWaitStrategy/.forStatusCode 200)
          (HttpWaitStrategy/.withStartupTimeout
            (java.time.Duration/ofSeconds 90))))
    (GenericContainer/.start)))


;; =============================================================================
;; Orchestration
;; =============================================================================

(defn- stop-all!
  "Stop every container in reverse start order. Each `.stop` call
   is best-effort: the goal is graceful teardown, but Ryuk is the
   safety net — if we throw on the way out, every container still
   dies when this JVM exits."
  [containers]
  (doseq [^GenericContainer c (reverse containers)]
    (try (GenericContainer/.stop c)
         (catch Exception e
           (log/warn e "container stop failed")))))


(defn run-suite!
  "Bring up the stack, run `script` (a shell path) with
   `GRAPHDEN_URL` / `AUTH_TOKEN` set in its env, return the script's
   exit code. Containers stop in `finally`; Ryuk handles the
   unhappy path."
  [script auth-token]
  (let [network (Network/newNetwork)
        containers (atom [])]
    (try
      (println "↑ booting isolated e2e stack via testcontainers...")
      (let [pg (start-postgres! network)
            _ (swap! containers conj pg)
            user-pg (start-user-postgres! network)
            _ (swap! containers conj user-pg)
            openbao (start-openbao! network)
            _ (swap! containers conj openbao)
            _ (seed-openbao! openbao)
            executor (start-executor! network auth-token)
            _ (swap! containers conj executor)
            host (GenericContainer/.getHost executor)
            port (GenericContainer/.getMappedPort executor
                                                  (Integer/valueOf 8080))
            url (str "http://" host ":" port)]
        (println (str "▶ stack ready (" url "); running " script))
        (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
                                  (into-array String ["bash" script]))
              env (ProcessBuilder/.environment pb)]
          (java.util.Map/.put env "GRAPHDEN_URL" url)
          (java.util.Map/.put env "AUTH_TOKEN" auth-token)
          (ProcessBuilder/.inheritIO pb)
          (let [proc (ProcessBuilder/.start pb)]
            (Process/.waitFor proc))))
      (finally
        (println "↓ stopping e2e stack")
        (stop-all! @containers)
        (try (Network/.close network)
             (catch Exception _))))))


(defn -main
  "CLI entry — `clojure -M:dev -m graphden.dev.e2e-stack <script>`.
   Bridges to `run-suite!` and translates its exit code into the
   process exit code so callers (`bb test-e2e`) can chain on it."
  [& args]
  (let [script (or (first args) "tools/browser-test/run-edit-tests.sh")
        token (or (System/getenv "AUTH_TOKEN") "e2e-isolated")
        exit (run-suite! script token)]
    (shutdown-agents)
    (System/exit (int exit))))
