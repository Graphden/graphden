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
    [clojure.math :as math]
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
  (doto (PostgreSQLContainer. "postgres:16.11-alpine")
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
  (doto (PostgreSQLContainer. "postgres:16.11-alpine")
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
  (doto (GenericContainer. "quay.io/openbao/openbao:2.5.4")
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


(def ^:private executor-image
  "The image this stack boots.

   Defaults to `graphden-executor:latest`, which is what a local `bb rebuild`
   produces. The landing gate overrides it with `GD_IMAGE`, because it must test
   a CANDIDATE build — the one it just made from the merged tree — without
   touching the canonical tag. That tag names the last SUCCESSFULLY LANDED
   build: the demo instance is deployed from it, so a gate that failed must not
   be able to leave its code sitting there. (It could, and it did: a red gate
   left its image tagged `:latest`, and the next gate's deploy served that
   un-landed code to the demo. `bb verify` caught it.)"
  (or (System/getenv "GD_IMAGE") "graphden-executor:latest"))


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

   - `--memory 3g` — bounds the JVM container HARD. With
     `MaxRAMPercentage=75` (Dockerfile) the JVM gets ~2.25GB heap.
     COUNTERINTUITIVELY, a TIGHTER cap is better, not looser. The
     dev host has 11GB RAM total but runs in parallel: e2e
     testcontainer JVM + Chrome headless (Playwright) + the
     always-on :9002 graphden-executor + its Postgres + dev tooling
     + the test orchestrator JVM. Total demand can exceed host
     RAM. When that happens the kernel fires the GLOBAL OOM-killer
     (CONSTRAINT_NONE in dmesg — NOT cgroup-bound), randomly
     killing the biggest tenants — including our JVM AND Chrome.
     Previously we bumped to 5GB thinking more headroom meant
     more stability; instead it let the JVM grow until it competed
     with Chrome and tripped the host kill. 3GB keeps the JVM
     small enough that it stays UNDER the global pressure threshold
     even when Chrome is loaded. ExitOnOutOfMemoryError +
     restart-policy handles the rare JVM-side OOM cleanly.

   - `--memory-swap 3g` (== `--memory`) — DISABLES container swap. The 3GB
     cap alone still let the kernel page the JVM's idle heap out under host
     cache pressure; when the heavy type-editing test cluster then hit the
     executor it faulted that heap back from swap — a multi-second stall that
     timed out the test's 15s `waitForFunction` (root-caused from the gate
     resource timeline: `swap_used ~2.3GB` during e2e, mem still available).
     Equal memory/memory-swap pins the heap in RAM; a genuine >3GB burst OOMs
     cleanly via ExitOnOutOfMemoryError + restart — a discrete, recoverable
     event, not a swap-stall flake.

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
      host-cfg (long (* 3 1024 1024 1024)))
    ;; == --memory ⇒ no container swap: the executor heap stays resident and
    ;; never swap-faults mid-request under host cache pressure (see the
    ;; --memory-swap docstring bullet above).
    (HostConfig/.withMemorySwap
      host-cfg (long (* 3 1024 1024 1024)))
    (HostConfig/.withPortBindings
      host-cfg
      (into-array PortBinding
                  [(PortBinding.
                     (Ports$Binding/bindPort (int host-port))
                     (ExposedPort/tcp 8080))]))
    (CreateContainerCmd/.withHostConfig cmd host-cfg)))


(defn- start-executor!
  "Graphden executor — boots `executor-image` (see above): the canonical
   `graphden-executor:latest` locally, or the gate's candidate build via GD_IMAGE. All `JDBC_URL` / `VAULT_ADDR` /
   `:user-postgres` references use the network aliases set above.

   `withLogConsumer` ships container stdout/stderr to SLF4J live, so
   a mid-suite crash leaves a stack-trace in the orchestrator's log
   stream — not just in the last 500 lines captured at teardown.
   `withCreateContainerCmdModifier` installs a Docker restart policy
   + memory cap + pinned host port (see `apply-restart-and-memory!`).
   `host-port` is pre-picked by the caller and survives restart."
  [^Network network auth-token host-port]
  (doto (GenericContainer. ^String executor-image)
    (GenericContainer/.withEnv "PORT" "8080")
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
          ;; Cold-boot creates ALL fn-entities from scratch against a
          ;; fresh (empty) testcontainers Postgres — sync + boot
          ;; type-check sweep measured ~150s at 4665 entities (round-3)
          ;; — THEN eager-compiles the registry before /health flips
          ;; 200. That's inherently slower than a demo restart (which
          ;; reuses persisted rows) and GROWS with the graph: 240s was
          ;; sized at ~3313 entities and the round-3 graph blew past it
          ;; (gate run 20260814-120135 timed out mid registry-compile
          ;; on an otherwise quiet host). 420s = the measured boot plus
          ;; the same ~contention margin the old bound carried; the
          ;; wait polls, so a healthy boot still returns early.
          (HttpWaitStrategy/.withStartupTimeout
            (java.time.Duration/ofSeconds 420))))
    (GenericContainer/.start)))


;; =============================================================================
;; Orchestration
;; =============================================================================

(defn- container-mem-snapshot
  "Best-effort `docker stats` snapshot for `container-id`. Returns
   a `{:mem-mb :limit-mb :mem-pct}` map or nil. Used by the
   heartbeat thread to track memory growth across the suite — if
   the container climbs steadily toward its limit, the eventual
   cgroup SIGKILL surfaces with context instead of as a silent
   restart."
  [container-id]
  (try
    (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
              (into-array String
                          ["docker" "stats" "--no-stream" "--format"
                           "{{.MemUsage}}|{{.MemPerc}}" container-id]))
          proc (ProcessBuilder/.start pb)
          out (slurp (Process/.getInputStream proc))]
      (Process/.waitFor proc)
      (when-let [m (re-find #"^([\d.]+)(\w+) / ([\d.]+)(\w+)\|([\d.]+)%"
                            (or out ""))]
        (let [to-mb (fn [n unit]
                      (let [v (Double/parseDouble n)]
                        (case unit
                          ("KiB" "KB") (/ v 1024.0)
                          ("MiB" "MB") v
                          ("GiB" "GB") (* v 1024.0)
                          v)))]
          {:mem-mb (math/round (to-mb (nth m 1) (nth m 2)))
           :limit-mb (math/round (to-mb (nth m 3) (nth m 4)))
           :mem-pct (Double/parseDouble (nth m 5))})))
    (catch Exception _)))


(defn- start-health-heartbeat!
  "Background thread that pokes `<url>/health` every 10s and logs the
   outcome. Goal: pin down the WALL-CLOCK moment the executor stops
   responding mid-suite, so the surrounding bash output (which test
   was running, which assertion fired last) frames the timeline.

   Also reports executor container memory every 5th tick (~50s) so
   the run shows whether the JVM is climbing toward its cgroup
   limit. Without this, a cgroup SIGKILL (no JVM-side log) is
   indistinguishable from a slow recovery.

   Lives until `stop` (returned) is invoked. Bounded by interrupt;
   no try-deref-block. Best-effort — any exception is swallowed and
   the heartbeat keeps trying."
  [url container-id]
  (let [stop? (atom false)
        tick (atom 0)
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
                     (when (and container-id (zero? (mod @tick 5)))
                       (when-let [s (container-mem-snapshot container-id)]
                         (log/info (format "💾 mem %dMB / %dMB (%.1f%%)"
                                           (:mem-mb s) (:limit-mb s) (:mem-pct s)))))
                     (swap! tick inc)
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

   Writes the FULL container log to `/tmp/e2e-executor-full.log`
   (rotated if it exists). The Docker log buffer preserves output
   from ALL JVM incarnations across restart-policy bounces, so this
   captures every Starting Graphden line, every Terminating message,
   AND any cgroup OOM-killer signature (which has no Java-side
   logging at all because SIGKILL bypasses shutdown hooks).
   Stdout also shows the last 200 lines as before for at-a-glance.

   Also extracts /tmp/heap-dump.hprof from the container if one
   exists — JVM writes this on `HeapDumpOnOutOfMemoryError`
   (Dockerfile CMD). The first OOM in a run is the interesting one;
   subsequent restarts will overwrite the file inside the container,
   so we extract immediately at teardown to capture whichever was
   last.

   Best-effort: log fetch can throw if the container is already
   dead; swallowed so teardown still proceeds."
  [containers]
  (doseq [^GenericContainer c containers]
    (try
      (when (= executor-image
               (GenericContainer/.getDockerImageName c))
        ;; Heap dump extraction (best-effort, before logs)
        (try
          (let [container-id (GenericContainer/.getContainerId c)
                dump-host "/tmp/e2e-executor-heap-dump.hprof"
                pb (ProcessBuilder. ^"[Ljava.lang.String;"
                    (into-array String
                                ["docker" "cp"
                                 (str container-id ":/tmp/heap-dump.hprof")
                                 dump-host]))
                proc (ProcessBuilder/.start pb)]
            (when (zero? (Process/.waitFor proc))
              (let [size-mb (-> (java.io.File. dump-host)
                                java.io.File/.length
                                (/ (* 1024.0 1024.0))
                                math/round)]
                (log/info (format "🔥 heap dump extracted: %s (%dMB) — analyze with `jhat` or VisualVM"
                                  dump-host size-mb)))))
          (catch Exception _))
        (let [logs (or (GenericContainer/.getLogs c) "")
              dump-file "/tmp/e2e-executor-full.log"
              lines (str/split-lines logs)
              starts (count (filter #(re-find #"Starting Graphden Executor Runtime" %) lines))
              terminatings (count (filter #(re-find #"Terminating due to" %) lines))]
          (spit dump-file logs)
          (log/info (str "executor lifetime: " starts " startup(s), "
                         terminatings " JVM-side Terminating message(s). "
                         "If startups > terminatings + 1, the missing exits were "
                         "SIGKILL (cgroup OOM-killer or similar — no Java log)."))
          (log/info (str "Full log: " dump-file " (" (count lines) " lines)"))
          (let [tail (->> lines (take-last 200) (str/join "\n"))]
            (log/info (str "╭─ executor container logs (last 200 lines) ─╮\n"
                           tail
                           "\n╰─ end executor logs ─╯")))))
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
            container-id (GenericContainer/.getContainerId executor)
            stop-heartbeat! (start-health-heartbeat! url container-id)]
        (println (str "▶ stack ready (" url "); running " script))
        (try
          (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
                    (into-array String ["bash" script]))
                env (ProcessBuilder/.environment pb)]
            (java.util.Map/.put env "GRAPHDEN_URL" url)
            (java.util.Map/.put env "AUTH_TOKEN" auth-token)
            ;; run-edit-tests.sh sleeps SWEEP_DELAY (default 2s) between files so the
            ;; DEMO container (:9002, restart:unless-stopped) can GC before its
            ;; restart policy bounces it mid-sweep. This ISOLATED stack has its own
            ;; 3 GB executor with restart:on-failure — it doesn't bounce, and two
            ;; full 55-file runs at 0 were clean (536s / 540s, vs ~640s with 2s),
            ;; so drop the ~110s of dead sleep here. An explicit SWEEP_DELAY still
            ;; wins (it flows through inheritIO), for chasing a load-related flake.
            (when-not (System/getenv "SWEEP_DELAY")
              (java.util.Map/.put env "SWEEP_DELAY" "0"))
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
        ;; not-empty: AUTH_TOKEN="" (e.g. a caller splicing "${AUTH_TOKEN:-}")
        ;; must fall back too — an empty admin token boots a stack whose
        ;; wrong-password path never says "wrong password", failing
        ;; edit-auth-login deterministically 5/5 (observed 2026-08-23).
        token (or (not-empty (System/getenv "AUTH_TOKEN")) "e2e-isolated")
        exit (run-suite! script token)]
    (shutdown-agents)
    (System/exit (int exit))))
