(ns ^:integration graphden.clients.vault-test
  "Integration tests for `graphden.clients.vault` against a real
   OpenBao container — covers the HTTP path that the unit tests in
   `secrets_test.clj` mock out via `with-redefs`.

   Container lifecycle is namespace-scoped: one OpenBao (dev mode,
   in-memory KV v2, root token `root`) starts on first test and
   stops in the `:once` fixture's teardown. ~1.5 s startup; the
   five tests amortise that easily.

   Why a dedicated container instead of sharing the PG one: vault
   is a wholly separate dependency, and these tests don't touch
   graphden's DB at all. Keeping it isolated also means the rest of
   the suite is unaffected when this file fails (e.g. when OpenBao
   image upstream changes)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.vault :as vault]
    [org.httpkit.client :as httpkit])
  (:import
    (org.testcontainers.containers
      GenericContainer)
    (org.testcontainers.containers.wait.strategy
      HttpWaitStrategy
      Wait)))


(def ^:private ^:dynamic *vault* nil)
(def ^:private ^:dynamic *client* nil)


(defn- start-openbao!
  "Spin up an OpenBao dev-mode container. Dev mode: in-memory KV v2,
   single root token (`root`), auto-unsealed at startup. The wait
   strategy hits `/v1/sys/health`, which OpenBao replies 200 to once
   the listener binds AND the dev-root-token is installed."
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


(defn- container-fixture
  [f]
  (let [container (start-openbao!)
        host (GenericContainer/.getHost container)
        port (GenericContainer/.getMappedPort container (Integer/valueOf 8200))
        client {:address (str "http://" host ":" port)
                :token "root"}]
    (try
      (binding [*vault* container
                *client* client]
        (f))
      (finally
        (GenericContainer/.stop container)))))


(use-fixtures :once container-fixture)


;; Each test scopes its paths under a fresh `(random-uuid)` prefix so
;; reruns of the same NS (cloverage instrumentation, repeated kaocha
;; invocations, `bb ci` running tests + coverage in adjacent JVMs that
;; somehow shared a vault image layer) can't bump KV v2 version
;; counters from a prior pass into this one. The deftest-local prefix
;; also keeps deftests inside the SAME ns from colliding on each other's
;; keys when the parallel plugin schedules them concurrently in the
;; future.
(defn- scoped-path
  [tag]
  (str tag "/" (random-uuid)))


;; ============================================================================
;; KV v2 round-trip — happy path
;; ============================================================================

(deftest put-get-roundtrip-test
  (let [p (scoped-path "rt")]
    (testing "put-secret returns version 1 for a fresh path"
      (is (= 1 (vault/put-secret *client* p "first-value"))))

    (testing "get-secret returns the value verbatim"
      (is (= "first-value" (vault/get-secret *client* p))))

    (testing "put again at the same path returns version 2"
      (is (= 2 (vault/put-secret *client* p "second-value"))))

    (testing "get-secret returns the latest version"
      (is (= "second-value" (vault/get-secret *client* p))))))


;; ============================================================================
;; delete-secret hard-deletes every version
;; ============================================================================

(deftest delete-secret-test
  (let [p (scoped-path "del")]
    (vault/put-secret *client* p "doomed")
    (is (= "doomed" (vault/get-secret *client* p)))

    (testing "delete-secret returns nil (204 No Content)"
      (is (nil? (vault/delete-secret *client* p))))

    (testing "subsequent get-secret raises :vault/lookup-failed with 404"
      (try
        (vault/get-secret *client* p)
        (is false "expected vault/lookup-failed")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :vault/lookup-failed (:type d)))
            (is (= 404 (:status d)))))))

    (testing "deleting an already-deleted path is idempotent"
      (is (nil? (vault/delete-secret *client* p))))))


(deftest get-secret-missing-value-does-not-leak-test
  ;; When the vault value isn't the expected string, the thrown ex-data must
  ;; NOT embed the raw parsed payload — for a KV v2 read that payload IS the
  ;; secret material, and this ex-data is persisted verbatim into an
  ;; API-readable execution `:error-data`.
  (with-redefs [httpkit/get
                (fn [_url _opts]
                  (atom {:status 200
                         :body "{\"data\":{\"data\":{\"value\":{\"leaked\":\"TOP-SECRET\"}}}}"}))]
    (let [ex (try (vault/get-secret {:address "http://x" :token "t"} "some/path")
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :vault/lookup-failed (:type (ex-data ex))))
      (is (not (contains? (ex-data ex) :raw))
          "raw parsed payload must not be in ex-data")
      (is (not (re-find #"TOP-SECRET" (pr-str (ex-data ex))))
          "the secret value must not appear anywhere in the ex-data"))))


;; ============================================================================
;; Metadata
;; ============================================================================

(deftest metadata-roundtrip-test
  (let [p (scoped-path "md")]
    (vault/put-secret *client* p "v")
    (vault/put-metadata *client* p {:description "hello"
                                    :owner "alice"})

    (testing "get-metadata returns custom_metadata plus version info"
      (let [m (vault/get-metadata *client* p)]
        (is (= 1 (:current_version m)))
        (is (string? (:created_time m)))
        (is (= {:description "hello" :owner "alice"} (:custom_metadata m)))))))


;; ============================================================================
;; Error paths
;; ============================================================================

(deftest missing-path-test
  (testing "get-secret on a missing path raises :vault/lookup-failed with 404"
    (try
      (vault/get-secret *client* (scoped-path "missing"))
      (is false "expected vault/lookup-failed")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :vault/lookup-failed (:type d)))
          (is (= 404 (:status d))))))))


(deftest bad-token-test
  (testing "bad token raises :vault/lookup-failed with 403"
    (let [bad-client (assoc *client* :token "wrong-token")]
      (try
        (vault/get-secret bad-client (scoped-path "bad-tok"))
        (is false "expected vault/lookup-failed")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :vault/lookup-failed (:type d)))
            ;; OpenBao replies 403 (forbidden) on bad token; some
            ;; vault forks return 400 for the same case. Accept either.
            (is (#{400 403} (:status d)))))))))
