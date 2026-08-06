(ns graphden.packages.web.vault-impls-test
  "Unit tests for the `web.vault` base-fn impls — the operator-only gate on
   the RAW vault ops (P3, 2026-08-07). A restricted (tenant) graph execution
   must not read/write arbitrary secret paths against the JVM-wide platform
   token (the KV namespace is flat). Slurp+eval the impls via the loader's
   `load-module-impls`, then poke each `(fn [__args ctx])` directly — no full
   bootstrap."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.vault :as vault]
    [graphden.executor.compile-runtime :as cr]))


(def ^:dynamic *impls* nil)


(defn- load-vault-impls-fixture
  [f]
  (binding [*impls* ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                     "web" "vault")]
    (f)))


(use-fixtures :once load-vault-impls-fixture)


(defn- err-type
  "The `:type` of the ExceptionInfo `thunk` throws, or nil if it doesn't."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(def ^:private raw-ops
  "The path-taking raw vault base-fns + a minimal args map for each."
  {:vault-get          {:path "victim-org/secret"}
   :vault-put          {:path "victim-org/secret" :value "pwned"}
   :vault-delete       {:path "victim-org/secret"}
   :vault-metadata-get {:path "victim-org/secret"}
   :vault-metadata-put {:path "victim-org/secret" :metadata {}}})


(deftest raw-vault-ops-are-operator-only-for-tenants
  (testing "a restricted (tenant) execution — even a paid tier carrying :network
            — is REFUSED with :vault/operator-only before any vault call"
    (binding [cr/*allowed-effects* #{:db :state :time :random :network}]
      (doseq [[op args] raw-ops]
        (is (= :vault/operator-only
               (err-type #((get *impls* op) args {})))
            (str (name op) " must refuse a restricted ctx")))))

  (testing "an UNRESTRICTED (platform) execution proceeds PAST the operator gate
            (no vault client wired → it fails at require-client!, proving the
            operator gate did NOT fire)"
    (binding [cr/*allowed-effects* nil]
      (with-redefs [vault/active-client (atom nil)]
        (doseq [[op args] raw-ops]
          (is (not= :vault/operator-only
                    (err-type #((get *impls* op) args {})))
              (str (name op) " must NOT be operator-gated when unrestricted")))))))


(deftest secret-leaf-is-not-operator-gated
  ;; `:secret-leaf` reads an ALREADY-derefed value whose vault path was bound at
  ;; COMPILE time from an operator-authored `:secret` — never tenant-arbitrary —
  ;; so it is a pure passthrough even inside a restricted execution.
  (binding [cr/*allowed-effects* #{:db :network}]
    (is (= "the-secret" ((:secret-leaf *impls*) {:in "the-secret"} {}))
        "secret-leaf passes its value through under a restricted ctx")))
