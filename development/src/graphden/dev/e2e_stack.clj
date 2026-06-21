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
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.clients.vault :as vault]
    [org.httpkit.client :as http])
  (:import
    (com.github.dockerjava.api.command
      CreateContainerCmd)
    (com.github.dockerjava.api.model
      ExposedPort
      HostConfig
      PortBinding
      Ports$Binding
      RestartPolicy)
    (java.net
      ServerSocket)
    (org.testcontainers.containers
      GenericContainer
      Network
      PostgreSQLContainer)
    (org.testcontainers.containers.output
      Slf4jLogConsumer)
    (org.testcontainers.containers.wait.strategy
      HttpWaitStrategy
      Wait)))


(defn- pick-free-host-port!
  "Pre-allocate a host port by opening a `ServerSocket`, reading the
   OS-assigned port, then closing. The port is now free again but the
   number is ours to publish.

   Why: Docker's auto-assign port (`-p 0:8080` shape) picks a NEW host
   port on every container restart. Testcontainers caches the original
   `getMappedPort()` value at `start()` — after restart the host
   `localhost:<old-port>` is dead, the test runner's cached
   `GRAPHDEN_URL` is stale, every subsequent request gets connection-
   refused. By pinning the host port to a value WE chose, the mapping
   survives `--restart=on-failure` because Docker uses the same
   explicit binding when it re-creates the publish.

   The brief window between socket close and Docker bind is a race
   risk — another process could grab the same port. In practice the
   window is microseconds and the test JVM has no other actor competing
   for it; acceptable for a dev/CI e2e stack."
  []
  (with-open [sock (ServerSocket. 0)]
    (ServerSocket/.getLocalPort sock)))


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


(defn- apply-restart-and-memory!
  "Docker-side recovery + safety belt + stable host-port pinning.
   Installed via `withCreateContainerCmdModifier` so it lands on the
   Docker create call rather than relying on Testcontainers' default
   no-restart policy.

   - `restart=on-failure:3` — if the executor's main JVM exits
     non-zero (NumberFormatException-class crashes, OOM-kills, etc.),
     Docker brings it back up. Combined with run-edit-tests.sh's
     `wait_for_server` 90s poll, a restart-window fault recovers
     transparently within ≤ 1 cascade-cap step.

   - `--memory 5g` — bounds the JVM container. With
     `MaxRAMPercentage=75` (Dockerfile) the JVM gets ~3.75GB heap.
     Lower caps trip the in-memory accumulation that the editor's
     background polling + sustained CRUD churn drive across a 47-
     test suite; 5GB pushes the OOM ceiling far enough that
     `edit-namespace-move` (which races on a mid-test JVM-restart
     window — see project_api_graph_entities_oom_leak) lands on the
     happy path. 5GB chosen over 6GB because the dev host runs an
     always-on :9002 graphden-executor + Postgres in parallel; 6GB
     starts trading with host swap. ExitOnOutOfMemoryError +
     restart-policy is still the safety net for the extreme case.

   - `--publish <host-port>:8080` — pins the host-side port so it
     SURVIVES `--restart=on-failure`. Without this, Docker
     auto-assigns a new host port on every restart (verified
     empirically 2026-06-20: pre-restart 38675, post-restart 38676);
     Testcontainers' cached `getMappedPort()` then points at a dead
     mapping and the test runner cascades on connection-refused
     after the very recovery the restart-policy was supposed to
     provide.

   IMPORTANT: mutate Testcontainers' already-built `HostConfig`
   rather than replacing it — Testcontainers populates network /
   port-bindings / volumes there during create-cmd assembly, and a
   fresh `HostConfig/newHostConfig` would wipe them."
  [^CreateContainerCmd cmd ^long host-port]
  (let [host-cfg (or (CreateContainerCmd/.getHostConfig cmd)
                     (HostConfig/newHostConfig))]
    (HostConfig/.withRestartPolicy
      host-cfg (RestartPolicy/onFailureRestart (int 3)))
    (HostConfig/.withMemory
      host-cfg (long (* 5 1024 1024 1024)))
    (HostConfig/.withPortBindings
      host-cfg
      (into-array PortBinding
                  [(PortBinding.
                     (Ports$Binding/bindPort (int host-port))
                     (ExposedPort/tcp 8080))]))
    (CreateContainerCmd/.withHostConfig cmd host-cfg)))


(defn- start-executor!
  "Graphden executor — reuses the `graphden-executor:latest` image
   the demo build produced (so this stack picks up the latest code
   without a second build step). All `JDBC_URL` / `VAULT_ADDR` /
   `:user-postgres` references use the network aliases set above.

   `withLogConsumer` ships container stdout/stderr to SLF4J live, so
   a mid-suite crash leaves a stack-trace in the orchestrator's log
   stream — not just in the last 500 lines captured at teardown.
   `withCreateContainerCmdModifier` installs a Docker restart policy
   + memory cap + pinned host port (see `apply-restart-and-memory!`).
   `host-port` is pre-picked by the caller and survives restart."
  [^Network network auth-token host-port]
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
    (GenericContainer/.withLogConsumer
      (-> (Slf4jLogConsumer.
            (org.slf4j.LoggerFactory/getLogger "executor-container"))
          (Slf4jLogConsumer/.withPrefix "exec")))
    (GenericContainer/.withCreateContainerCmdModifier
      (reify java.util.function.Consumer
        (accept [_ cmd] (apply-restart-and-memory! cmd host-port))))
    (GenericContainer/.waitingFor
      (-> (Wait/forHttp "/health")
          (HttpWaitStrategy/.forStatusCode 200)
          (HttpWaitStrategy/.withStartupTimeout
            (java.time.Duration/ofSeconds 90))))
    (GenericContainer/.start)))


;; =============================================================================
;; Orchestration
;; =============================================================================

(defn- start-health-heartbeat!
  "Background thread that pokes `<url>/health` every 10s and logs the
   outcome. Goal: pin down the WALL-CLOCK moment the executor stops
   responding mid-suite, so the surrounding bash output (which test
   was running, which assertion fired last) frames the timeline.

   Lives until `stop` (returned) is invoked. Bounded by interrupt;
   no try-deref-block. Best-effort — any exception is swallowed and
   the heartbeat keeps trying."
  [url]
  (let [stop? (atom false)
        thread (Thread.
                 ^Runnable
                 (fn []
                   (while (not @stop?)
                     (let [t0 (System/currentTimeMillis)]
                       (try
                         (let [{:keys [status error]}
                               @(http/get (str url "/health")
                                          {:timeout 5000})
                               dt (- (System/currentTimeMillis) t0)]
                           (cond
                             error
                             (log/warn (format "♥ /health FAIL after %dms: %s"
                                               dt (Throwable/.getMessage error)))
                             (not= 200 status)
                             (log/warn (format "♥ /health %d after %dms" status dt))
                             (>= dt 1000)
                             (log/info (format "♥ /health 200 (slow, %dms)" dt))))
                         (catch Exception e
                           (log/warn e "heartbeat probe threw"))))
                     (try (Thread/sleep 10000)
                          (catch InterruptedException _ (reset! stop? true)))))
                 "e2e-health-heartbeat")]
    (Thread/.setDaemon thread true)
    (Thread/.start thread)
    (fn []
      (reset! stop? true)
      (Thread/.interrupt thread))))


(defn- capture-executor-logs!
  "On teardown, dump the executor container's stdout/stderr tail to
   the orchestrator's log so a mid-suite crash leaves a forensic
   trail. Without this, the testcontainer's logs vanish with the
   container — and a `bb test-e2e` failure surfaces only as
   'server unhealthy after 90s' in the runner, with no JVM
   stacktrace.

   Best-effort: log fetch can throw if the container is already
   dead; swallowed so teardown still proceeds."
  [containers]
  (doseq [^GenericContainer c containers]
    (try
      (when (= "graphden-executor:latest"
               (GenericContainer/.getDockerImageName c))
        (let [logs (GenericContainer/.getLogs c)
              tail (->> (str/split-lines (or logs ""))
                        (take-last 500)
                        (str/join "\n"))]
          (log/info (str "╭─ executor container logs (last 500 lines) ─╮\n"
                         tail
                         "\n╰─ end executor logs ─╯"))))
      (catch Exception _))))


(defn- stop-all!
  "Stop every container in reverse start order. Captures executor
   logs first so a mid-suite crash leaves a stacktrace in the
   orchestrator's stdout. Each `.stop` call is best-effort: the
   goal is graceful teardown, but Ryuk is the safety net — if we
   throw on the way out, every container still dies when this JVM
   exits."
  [containers]
  (capture-executor-logs! containers)
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
            host-port (pick-free-host-port!)
            executor (start-executor! network auth-token host-port)
            _ (swap! containers conj executor)
            host (GenericContainer/.getHost executor)
            ;; Use the pinned host-port directly — `getMappedPort`
            ;; would return the SAME value here, but reading it back
            ;; from the container is an indirection that breaks on
            ;; restart (Docker's iptables rule for the auto-port is
            ;; what survives, and getMappedPort returns the bound
            ;; value at start time). With pinning, host-port IS the
            ;; binding, before AND after restart.
            port (int host-port)
            url (str "http://" host ":" port)
            stop-heartbeat! (start-health-heartbeat! url)]
        (println (str "▶ stack ready (" url "); running " script))
        (try
          (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
                                    (into-array String ["bash" script]))
                env (ProcessBuilder/.environment pb)]
            (java.util.Map/.put env "GRAPHDEN_URL" url)
            (java.util.Map/.put env "AUTH_TOKEN" auth-token)
            (ProcessBuilder/.inheritIO pb)
            (let [proc (ProcessBuilder/.start pb)]
              (Process/.waitFor proc)))
          (finally (stop-heartbeat!))))
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
