(ns graphden.test-infra.openbao
  "A real OpenBao for vault-backed tests: dev mode, in-memory KV v2,
   root token `root`, auto-unsealed. ~1.5 s startup — namespace-scoped
   via `container-fixture`, which binds `*client*` to the
   `{:address :token}` map `graphden.clients.vault` takes."
  (:import
    (org.testcontainers.containers
      GenericContainer)
    (org.testcontainers.containers.wait.strategy
      HttpWaitStrategy
      Wait)))


(def ^:dynamic *client*
  "The vault client map for the running container (bound by the fixture)."
  nil)


(defn- start!
  "Start the container. The wait strategy hits `/v1/sys/health`, which
   OpenBao answers 200 once the listener binds AND the dev root token is
   installed."
  []
  (doto (GenericContainer. "quay.io/openbao/openbao:latest")
    (GenericContainer/.withCommand
      (into-array String ["server" "-dev"
                          "-dev-root-token-id=root"
                          "-dev-listen-address=0.0.0.0:8200"]))
    (GenericContainer/.withExposedPorts
      (into-array Integer [(Integer/valueOf 8200)]))
    (GenericContainer/.waitingFor
      (-> (Wait/forHttp "/v1/sys/health")
          (HttpWaitStrategy/.forStatusCode 200)))
    (GenericContainer/.start)))


(defn container-fixture
  "`:once` fixture: one OpenBao for the namespace, `*client*` bound."
  [f]
  (let [container (start!)
        host (GenericContainer/.getHost container)
        port (GenericContainer/.getMappedPort container (Integer/valueOf 8200))]
    (try
      (binding [*client* {:address (str "http://" host ":" port) :token "root"}]
        (f))
      (finally
        (GenericContainer/.stop container)))))
