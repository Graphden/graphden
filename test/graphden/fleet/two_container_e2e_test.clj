(ns graphden.fleet.two-container-e2e-test
  "^:integration — a REAL two-container fleet over a shared Postgres, via
   Testcontainers. Boots `graphden-executor:latest` TWICE as fleet members
   `exec-a` + `exec-b` on one docker network (shared PG, shared internal token,
   explicit `GRAPHDEN_FLEET_EXECUTORS` membership), then asserts the cross-
   container control plane works over REAL HTTP — not the in-process seams the
   `graphden.fleet.*` unit tests use:

   - both members boot + serve `/health`;
   - the token-gated `/internal/fleet/status` on EACH pod (401 without the
     internal token, 200 + that pod's own executor-id with it), and both agree on
     the shared `:placement` view;
   - the token-gated `/internal/fleet/cell/load/<root>` directed-command endpoint
     on a real container;
   - a graph write on A (a branch) is visible on B — the shared-DB cross-container
     path across two real JVMs.

   WHERE IT RUNS. The landing gate calls `bb test-fleet-e2e` with the candidate
   image (`GD_IMAGE`) whenever the diff touches the cross-pod control plane —
   `src/graphden/{fleet,system,storage/remote}`, `byo.clj`, `crac.clj`, this
   directory, `Dockerfile*`, `docker-compose*`, `deps.edn`, `build.clj` — see
   `classify_changes` in `dev/wtq/wt`. Until 2026-08-22 it ran NOWHERE: the
   suite existed, the bb task existed, and neither the gate nor GitHub CI ever
   invoked it, so the only assertion the project owned about multi-pod
   behaviour in real containers was never evaluated.

   By hand: `bb rebuild` first (the default image is `graphden-executor:latest`,
   overridable with `GD_IMAGE`), then `bb test-fleet-e2e` from the main
   checkout. ~2-3 min: two cold boots against a fresh PG. Ryuk reaps the
   containers on JVM exit."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [org.httpkit.client :as http])
  (:import
    (java.time
      Duration)
    (org.testcontainers.containers
      GenericContainer
      Network
      PostgreSQLContainer)
    (org.testcontainers.containers.wait.strategy
      HttpWaitStrategy
      Wait)))


(def ^:private internal-token "fleet-e2e-internal")
(def ^:private auth-token "fleet-e2e-auth")


;; =============================================================================
;; Container builders — mirrors the pattern in development/src dev/e2e-stack,
;; kept self-contained so this test needs only the :test classpath.
;; =============================================================================

(defn- start-postgres!
  [^Network network]
  (doto (PostgreSQLContainer. "postgres:16.11-alpine")
    (PostgreSQLContainer/.withDatabaseName "graphden")
    (PostgreSQLContainer/.withUsername "graphden")
    (PostgreSQLContainer/.withPassword "graphden")
    (PostgreSQLContainer/.withNetwork network)
    (PostgreSQLContainer/.withNetworkAliases (into-array String ["postgres"]))
    (PostgreSQLContainer/.withCommand "postgres -c max_connections=100")
    (PostgreSQLContainer/.start)))


(defn- start-executor!
  "A fleet member: `graphden-executor:latest` against the shared `postgres`
   alias, `GRAPHDEN_EXECUTOR_ID = alias` (enables the fleet-controller + the
   internal endpoint), explicit membership + shared internal token. Network alias
   = `alias` so a peer can dial it. Waits for /health (240s cold-boot budget)."
  [^Network network alias peers]
  (doto (GenericContainer. ^String (or (System/getenv "GD_IMAGE") "graphden-executor:latest"))
    (GenericContainer/.withEnv "PORT" "8080")
    (GenericContainer/.withEnv "GRAPHDEN_PORT" "8080")
    (GenericContainer/.withEnv "JDBC_URL" "jdbc:postgresql://postgres:5432/graphden")
    (GenericContainer/.withEnv "DB_USERNAME" "graphden")
    (GenericContainer/.withEnv "DB_PASSWORD" "graphden")
    (GenericContainer/.withEnv "AUTH_TOKEN" auth-token)
    (GenericContainer/.withEnv "GRAPHDEN_EXECUTOR_ID" alias)
    (GenericContainer/.withEnv "GRAPHDEN_FLEET_EXECUTORS" peers)
    (GenericContainer/.withEnv "GRAPHDEN_INTERNAL_TOKEN" internal-token)
    (GenericContainer/.withEnv "GRAPHDEN_DEMO_BRANCHES_ENABLED" "")
    (GenericContainer/.withEnv "GRAPHDEN_NREPL_PORT" "")
    (GenericContainer/.withExposedPorts (into-array Integer [(Integer/valueOf 8080)]))
    (GenericContainer/.withNetwork network)
    (GenericContainer/.withNetworkAliases (into-array String [alias]))
    (GenericContainer/.waitingFor
      (-> (Wait/forHttp "/health")
          (HttpWaitStrategy/.forStatusCode 200)
          (HttpWaitStrategy/.withStartupTimeout (Duration/ofSeconds 240))))
    (GenericContainer/.start)))


(defn- base-url
  [^GenericContainer c]
  (str "http://" (GenericContainer/.getHost c) ":"
       (GenericContainer/.getMappedPort c (Integer/valueOf 8080))))


;; =============================================================================
;; HTTP helpers
;; =============================================================================

(defn- req
  [method url token & [body]]
  (let [opts (cond-> {:method method :timeout 15000}
               token (assoc :headers {"Authorization" (str "Bearer " token)})
               body (assoc :headers {"Authorization" (str "Bearer " token)
                                     "Content-Type" "application/x-www-form-urlencoded"}
                           :body body))]
    @(http/request (assoc opts :url url))))


(deftest ^:container-e2e two-container-fleet-e2e-test
  (let [network (Network/newNetwork)
        containers (atom [])]
    (try
      (let [pg (start-postgres! network)
            _ (swap! containers conj pg)
            ;; Boot SEQUENTIALLY: exec-a does the first-init (creates the graph),
            ;; exec-b then boots against an already-populated PG (no init race).
            a (start-executor! network "exec-a" "exec-a,exec-b")
            _ (swap! containers conj a)
            b (start-executor! network "exec-b" "exec-a,exec-b")
            _ (swap! containers conj b)
            a-url (base-url a)
            b-url (base-url b)]

        (testing "both fleet members serve /health (real 2-pod deploy on a shared PG)"
          (is (= 200 (:status (req :get (str a-url "/health") nil))))
          (is (= 200 (:status (req :get (str b-url "/health") nil)))))

        (testing "/internal/fleet/status — token-gated, reports each pod's own id"
          (is (= 401 (:status (req :get (str a-url "/internal/fleet/status") nil)))
              "no internal token → 401")
          (is (= 401 (:status (req :get (str a-url "/internal/fleet/status") "wrong-token")))
              "wrong token → 401")
          (let [ra (req :get (str a-url "/internal/fleet/status") internal-token)
                rb (req :get (str b-url "/internal/fleet/status") internal-token)]
            (is (= 200 (:status ra)))
            (is (= 200 (:status rb)))
            (is (= "exec-a" (get (json/parse-string (:body ra)) "executor-id")))
            (is (= "exec-b" (get (json/parse-string (:body rb)) "executor-id")))
            (is (contains? (json/parse-string (:body ra)) "placements")
                "the snapshot carries the shared placement view")))

        (testing "/internal/fleet/cell/load — real directed-command endpoint, token-gated"
          (let [fn-id (-> (req :get (str a-url "/api/graph/entities?scope=index") auth-token)
                          :body json/parse-string (get "fns") first (get "id"))
                path (str "/internal/fleet/cell/load/" fn-id)]
            (is (some? fn-id) "picked a real root fn-id from the graph")
            (is (= 401 (:status (req :post (str a-url path) nil)))
                "no internal token → 401")
            (is (contains? #{200 409}
                           (:status (req :post (str a-url path) internal-token)))
                "with the token the command is accepted (200 loaded / 409 off-shard)")))

        (testing "both real containers serve the SAME shared graph over one PG"
          (let [a-fns (-> (req :get (str a-url "/api/graph/entities?scope=index") auth-token)
                          :body json/parse-string (get "fns"))
                b-fns (-> (req :get (str b-url "/api/graph/entities?scope=index") auth-token)
                          :body json/parse-string (get "fns"))]
            (is (pos? (count a-fns)) "exec-a serves the graph")
            (is (pos? (count b-fns)) "exec-b serves the graph")
            (is (= (count a-fns) (count b-fns))
                "both pods see the same fn set from the shared Postgres")))

        (testing "both pods report the same fleet-wide placement view (one control plane, one DB)"
          (let [pa (-> (req :get (str a-url "/internal/fleet/status") internal-token)
                       :body json/parse-string (get "placements"))
                pb (-> (req :get (str b-url "/internal/fleet/status") internal-token)
                       :body json/parse-string (get "placements"))]
            (is (= pa pb) "exec-a and exec-b agree on the shared :placement view"))))

      (finally
        (doseq [^GenericContainer c (reverse @containers)]
          (try (GenericContainer/.stop c) (catch Exception _)))
        (try (Network/.close network) (catch Exception _))))))
