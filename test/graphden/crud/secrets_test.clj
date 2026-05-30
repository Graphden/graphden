(ns ^:integration graphden.crud.secrets-test
  "Tests for `graphden.crud.secrets` — admin Secrets CRUD orchestrators
   over OpenBao + graphden storage.

   Vault is mocked via `with-redefs` over `graphden.clients.vault` so
   the tests don't need a live OpenBao; an atom-backed in-memory store
   captures the OpenBao state and lets assertions inspect it. The
   graphden side hits the real shared PG container — same convention
   as `branches-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.vault :as vault]
    [graphden.crud.entities :as crud-entities]
    [graphden.crud.secrets :as secrets]
    [graphden.executor.context :as ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


;; ============================================================================
;; In-memory vault mock — captures puts so tests can inspect what
;; landed in OpenBao, and refuses double-deletes / missing-path reads
;; the same way real OpenBao would.
;; ============================================================================

(defn- fresh-vault
  []
  (atom {:values {} :metadata {}}))


(defn- with-fake-vault*
  [fake-state body-fn]
  (with-redefs [vault/get-secret      (fn [_client path]
                                        (or (get-in @fake-state [:values path])
                                            (throw (ex-info "no path"
                                                            {:type :vault/lookup-failed :path path}))))
                vault/put-secret      (fn [_client path value]
                                        (swap! fake-state assoc-in [:values path] value)
                                        (count (swap! fake-state update :versions
                                                      (fnil conj []) [path value])))
                vault/delete-secret   (fn [_client path]
                                        (swap! fake-state
                                               (fn [s]
                                                 (-> s
                                                     (update :values dissoc path)
                                                     (update :metadata dissoc path))))
                                        nil)
                vault/get-metadata    (fn [_client path]
                                        (or (get-in @fake-state [:metadata path])
                                            (throw (ex-info "no metadata"
                                                            {:type :vault/lookup-failed :path path}))))
                vault/put-metadata    (fn [_client path metadata]
                                        (swap! fake-state assoc-in [:metadata path]
                                               {:custom_metadata metadata
                                                :current_version 1
                                                :created_time "2026-05-28T00:00:00Z"
                                                :updated_time "2026-05-28T00:00:00Z"})
                                        nil)]
    (body-fn)))


(defmacro with-fake-vault
  [state & body]
  `(with-fake-vault* ~state (fn [] ~@body)))


;; ============================================================================
;; Fixture helpers
;; ============================================================================

(defn- test-ctx
  "Build an ExecutionContext over `storage` with a stub vault client
   (the actual `:vault/client` integrant shape — the with-redefs above
   intercepts before it's used)."
  [storage]
  (-> (ctx/create-context {:storage storage :base-fns {}})
      (assoc :vault {:address "http://fake-vault" :token "fake"})))


(defn- seed-vault-get!
  "Seed BOTH secret base-fns (`vault-get` legacy + `secret-leaf`
   Followup-4) so `crud.secrets/create-secret` finds an owner for
   `parent-ids` AND `validation/secret-path-rej` finds a slot whose
   rich-type carries the `:secret` marker (without the marker the
   F-4 binding fails validation). Returns
   `{:vault-get :path-slot :secret-leaf :secret-leaf-slot}` so test
   bodies can refer to either shape."
  [storage]
  (let [vg (setup/create-base-fn! storage "vault-get" :text)
        ps (setup/create-slot! storage "path" :text)
        sl (setup/create-base-fn! storage "secret-leaf" :text)
        sl-slot (setup/create-slot! storage "in" :text)]
    (setup/attach-slot! storage (:id vg) (:id ps) 0)
    (setup/attach-slot! storage (:id sl) (:id sl-slot) 0)
    ;; Register the marker so the validation gate sees `[:secret :text]`.
    (registry/record-rich-types! :secret-leaf
                                 {:args {:in {:type [:secret :text]}}
                                  :return-type [:secret :text]
                                  :effects #{:io}})
    {:vault-get vg :path-slot ps
     :secret-leaf sl :secret-leaf-slot sl-slot}))


;; ============================================================================
;; list-secrets
;; ============================================================================

(deftest list-secrets-empty-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault (fresh-vault)
        (testing "no secret-shaped fn-defs → empty list"
          (let [{:keys [ok secrets]} (secrets/list-secrets c)]
            (is ok)
            (is (= [] secrets)))))
      (finally (sp/close storage)))))


(deftest list-secrets-shape-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault vault-state
        (let [{:keys [ok secret]} (secrets/create-secret
                                    c {:name "_db-password"
                                       :path "user-db/password"
                                       :value "hunter2"
                                       :description "user-db pwd"})]
          (is ok)
          (is (string? (:id secret)))
          (is (= "_db-password" (:name secret)))
          (is (= "user-db/password" (:path secret)))
          (is (= "user-db pwd" (:description secret))))
        (testing "list returns one secret with metadata from vault, NO value"
          (let [{:keys [ok secrets]} (secrets/list-secrets c)]
            (is ok)
            (is (= 1 (count secrets)))
            (let [s (first secrets)]
              (is (= "_db-password" (:name s)))
              (is (= "user-db/password" (:path s)))
              (is (= 1 (:version s)))
              (is (= "user-db pwd" (:description s)))
              ;; The wire shape carries metadata but never the value.
              (is (not (contains? s :value)))))))
      (finally (sp/close storage)))))


(deftest list-secrets-filters-mi-children-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (let [{:keys [vault-get]} (seed-vault-get! storage)
            other (setup/create-base-fn! storage "other-base" :text)]
        ;; Hand-craft a fn-row with TWO parents (vault-get + something
        ;; else) — it's not an admin-managed secret, must not appear
        ;; in the list.
        (sp/create-entity storage :fn
                          {:name "mi-child"
                           :parent-ids [(:id vault-get) (:id other)]})
        (with-fake-vault (fresh-vault)
          (testing "MI child of vault-get does NOT appear as a secret"
            (let [{:keys [secrets]} (secrets/list-secrets c)]
              (is (= [] secrets))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; create-secret
;; ============================================================================

(deftest create-secret-happy-path-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)]
    (try
      ;; After Followup-4's editor-UX switch, create-secret writes
      ;; `parent :secret-leaf` + `:override-kind :secret-path`
      ;; binding on the leaf's `:in` slot. The legacy
      ;; `parent :vault-get` shape remains readable by `list-secrets`
      ;; but isn't produced by the admin path anymore.
      (let [{:keys [secret-leaf secret-leaf-slot]} (seed-vault-get! storage)]
        (with-fake-vault vault-state
          (let [{:keys [ok secret]} (secrets/create-secret
                                      c {:name "_test"
                                         :path "test/value"
                                         :value "v1"})]
            (is ok)
            (testing "vault state contains the new value + metadata"
              (is (= "v1" (get-in @vault-state [:values "test/value"]))))
            (testing "graphden state has a fn-row with parent=[secret-leaf]"
              (let [fn-id (parse-uuid (:id secret))
                    fn-row (sp/read-entity storage :fn fn-id)
                    bindings (sp/query-entities storage :binding {:fn-id fn-id})]
                (is (some? fn-row))
                (is (= "_test" (:name fn-row)))
                (is (= [(:id secret-leaf)] (vec (:parent-ids fn-row))))
                (testing ":override-kind :secret-path binding on the leaf slot"
                  (is (= 1 (count bindings)))
                  (is (= "test/value" (:value (first bindings))))
                  (is (= :secret-path (:override-kind (first bindings))))
                  (is (= (:id secret-leaf-slot) (:slot-id (first bindings))))))))))
      (finally (sp/close storage)))))


(deftest create-secret-rolls-back-on-graphden-failure-test
  ;; Vault-put succeeds, graphden-binding-write fails mid-create —
  ;; the orchestrator must `vault-delete` so the OpenBao value
  ;; doesn't outlive the (failed) fn-row.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)
        orig-create-entity crud-entities/create-entity]
    (try
      (seed-vault-get! storage)
      (with-fake-vault vault-state
        (with-redefs [crud-entities/create-entity
                      (fn [entity-type data ctx]
                        (if (= entity-type :binding)
                          (throw (ex-info "simulated binding-write failure"
                                          {:type :test/simulated}))
                          (orig-create-entity entity-type data ctx)))]
          (let [{:keys [ok error]} (secrets/create-secret
                                     c {:name "_rollback-pwd"
                                        :path "rollback/pwd"
                                        :value "leaked?"})]
            (testing "create returns :ok false with the underlying error"
              (is (not ok))
              (is (re-find #"simulated binding-write failure" error)))))

        (testing "vault state has NO value at the path — rolled back"
          (is (nil? (get-in @vault-state [:values "rollback/pwd"]))))

        (testing "graphden has NO fn-row for the rolled-back secret"
          (is (empty? (sp/query-entities storage :fn {:name "_rollback-pwd"})))))
      (finally (sp/close storage)))))


(deftest create-secret-rejections-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault (fresh-vault)
        (testing "blank name → :error"
          (let [{:keys [ok error]} (secrets/create-secret
                                     c {:name "" :path "p" :value "v"})]
            (is (not ok))
            (is (re-find #"name" error))))

        (testing "blank path → :error"
          (let [{:keys [ok error]} (secrets/create-secret
                                     c {:name "n" :path "" :value "v"})]
            (is (not ok))
            (is (re-find #"path" error))))

        (testing "missing value → :error"
          (let [{:keys [ok error]} (secrets/create-secret
                                     c {:name "n" :path "p"})]
            (is (not ok))
            (is (re-find #"value" error))))

        (testing "duplicate name → :reason :name-taken"
          (secrets/create-secret c {:name "dupe" :path "p1" :value "v"})
          (let [{:keys [ok reason]} (secrets/create-secret
                                      c {:name "dupe" :path "p2" :value "v"})]
            (is (not ok))
            (is (= :name-taken reason)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; delete-secret
;; ============================================================================

(deftest delete-secret-happy-path-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault vault-state
        (let [{:keys [secret]} (secrets/create-secret
                                 c {:name "_to-delete"
                                    :path "to/delete"
                                    :value "x"})
              fn-id (parse-uuid (:id secret))]
          (testing "delete removes graphden row + vault state"
            (let [{:keys [ok]} (secrets/delete-secret c (:id secret))]
              (is ok)
              (is (nil? (sp/read-entity storage :fn fn-id)))
              (is (nil? (get-in @vault-state [:values "to/delete"])))))))
      (finally (sp/close storage)))))


(deftest delete-secret-not-found-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault (fresh-vault)
        (testing "missing id → :reason :not-found"
          (let [{:keys [ok reason]} (secrets/delete-secret c (str (random-uuid)))]
            (is (not ok))
            (is (= :not-found reason)))))
      (finally (sp/close storage)))))


(deftest delete-secret-rejects-non-secret-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (seed-vault-get! storage)
      (let [non-secret (setup/create-base-fn! storage "not-a-secret" :text)]
        (with-fake-vault (fresh-vault)
          (testing "deleting a non-secret fn through /api/secrets is refused"
            (let [{:keys [ok reason]} (secrets/delete-secret c (str (:id non-secret)))]
              (is (not ok))
              (is (= :not-a-secret reason))))))
      (finally (sp/close storage)))))


(deftest delete-secret-in-use-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault vault-state
        ;; Create the secret first.
        (let [{:keys [secret]} (secrets/create-secret
                                 c {:name "_used-pwd"
                                    :path "used/pwd"
                                    :value "v"})
              secret-id (parse-uuid (:id secret))
              ;; Build a dependent: another fn-def whose binding's
              ;; ref-fn-id targets the secret.
              consumer-slot (setup/create-slot! storage "input" :text)
              consumer-base (setup/create-base-fn! storage "consumer-base" :text)
              _ (setup/attach-slot! storage (:id consumer-base) (:id consumer-slot) 0)
              consumer (setup/create-composed-fn! storage "consumer" (:id consumer-base))]
          (setup/bind-ref! storage (:id consumer) (:id consumer-slot) secret-id)
          (testing "secret-in-use → 409-shape + usages list"
            (let [{:keys [ok reason usages]} (secrets/delete-secret c (:id secret))]
              (is (not ok))
              (is (= :secret-in-use reason))
              (is (seq usages))
              (is (some #(= "consumer" (:name %)) usages))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; rotate-secret
;; ============================================================================

(deftest rotate-secret-happy-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault vault-state
        (let [{:keys [secret]} (secrets/create-secret
                                 c {:name "_rot"
                                    :path "rot/key"
                                    :value "v1"})
              {:keys [ok]} (secrets/rotate-secret
                             c (:id secret) {:value "v2"})]
          (is ok)
          (testing "vault state now holds the new value at the same path"
            (is (= "v2" (get-in @vault-state [:values "rot/key"]))))
          (testing "graphden path-binding is unchanged (path didn't move)"
            (let [bindings (sp/query-entities storage :binding
                                              {:fn-id (parse-uuid (:id secret))})]
              (is (= "rot/key" (:value (first bindings))))))))
      (finally (sp/close storage)))))


(deftest rotate-secret-not-found-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault (fresh-vault)
        (let [{:keys [ok reason]} (secrets/rotate-secret
                                    c (str (random-uuid)) {:value "x"})]
          (is (not ok))
          (is (= :not-found reason))))
      (finally (sp/close storage)))))


(deftest rotate-secret-rejects-blank-value-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault vault-state
        (let [{:keys [secret]} (secrets/create-secret
                                 c {:name "_r" :path "r/k" :value "v1"})
              {:keys [ok error]} (secrets/rotate-secret
                                   c (:id secret) {})]
          (is (not ok))
          (is (re-find #"value" error))))
      (finally (sp/close storage)))))


;; ============================================================================
;; migrate-to-secret-leaf (Followup-A7)
;; ============================================================================

(defn- seed-legacy-vault-get-secret!
  "Hand-craft a legacy `:vault-get`-shaped secret: parent=[:vault-get]
   + plain value-binding on the `:path` slot (no `:override-kind`).
   Bypasses `crud-entities/create-entity`'s capability gate via
   `sp/create-entity` direct."
  [storage vault-get vault-get-slot nm path]
  (let [fn-id (java.util.UUID/randomUUID)]
    (sp/create-entity storage :fn
                      {:id fn-id
                       :name nm
                       :parent-ids [(:id vault-get)]})
    (sp/create-entity storage :binding
                      {:id (java.util.UUID/randomUUID)
                       :fn-id fn-id
                       :slot-id (:id vault-get-slot)
                       :value path})
    fn-id))


(deftest migrate-to-secret-leaf-happy-path-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (let [{:keys [vault-get path-slot secret-leaf secret-leaf-slot]} (seed-vault-get! storage)
            legacy-id (seed-legacy-vault-get-secret! storage vault-get path-slot
                                                     "_legacy-db-password"
                                                     "legacy/db/password")]
        (with-fake-vault (fresh-vault)
          (testing "list-secrets surfaces it with :shape \"vault-get\" before migration"
            (let [{:keys [secrets]} (secrets/list-secrets c)
                  s (first (filter #(= "_legacy-db-password" (:name %)) secrets))]
              (is (some? s))
              (is (= "vault-get" (:shape s)))))
          (testing "migration succeeds and returns the preserved path"
            (let [{:keys [ok path]} (secrets/migrate-to-secret-leaf c (str legacy-id))]
              (is ok)
              (is (= "legacy/db/password" path))))
          (testing "fn-row now has parent=[:secret-leaf]"
            (let [fn-row (sp/read-entity storage :fn legacy-id)]
              (is (= [(:id secret-leaf)] (vec (:parent-ids fn-row))))))
          (testing "binding now lives on :in slot with :override-kind :secret-path"
            (let [bs (sp/query-entities storage :binding {:fn-id legacy-id})]
              (is (= 1 (count bs)))
              (let [b (first bs)]
                (is (= (:id secret-leaf-slot) (:slot-id b)))
                (is (= :secret-path (:override-kind b)))
                (is (= "legacy/db/password" (:value b))))))
          (testing "post-migration list-secrets surfaces :shape \"secret-leaf\""
            (let [{:keys [secrets]} (secrets/list-secrets c)
                  s (first (filter #(= "_legacy-db-password" (:name %)) secrets))]
              (is (some? s))
              (is (= "secret-leaf" (:shape s)))))))
      (finally (sp/close storage)))))


(deftest migrate-to-secret-leaf-rejects-non-legacy-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        vault-state (fresh-vault)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault vault-state
        (let [{:keys [secret]} (secrets/create-secret
                                 c {:name "_already-leaf" :path "a/p" :value "v"})
              {:keys [ok reason]} (secrets/migrate-to-secret-leaf c (:id secret))]
          (testing "migrating an already-:secret-leaf-shaped secret is rejected"
            (is (not ok))
            (is (= :not-legacy-shape reason)))))
      (finally (sp/close storage)))))


(deftest migrate-to-secret-leaf-not-found-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (seed-vault-get! storage)
      (with-fake-vault (fresh-vault)
        (let [{:keys [ok reason]} (secrets/migrate-to-secret-leaf c (str (random-uuid)))]
          (is (not ok))
          (is (= :not-found reason))))
      (finally (sp/close storage)))))
