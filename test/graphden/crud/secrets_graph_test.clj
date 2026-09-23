(ns ^:integration graphden.crud.secrets-graph-test
  "The admin Secrets endpoints driven through their PRODUCTION graph
   handlers (`app/secrets/fns.edn`) the way a request reaches them — the
   branch router (`br/dispatch`) over the `[core web app]` golden clone.
   These scenarios used to run against Clojure orchestrator copies in
   `crud.secrets` that production never called.

   Through the router, not `setup/via-graph`: the listing / delete /
   rotate chains resolve versioned rows inside per-row HOF bodies, and
   those reach `:storage-query` only through the fn-def binding at
   `:web-server` — `via-graph`'s call-site injection leaves them a nil
   callable as soon as there is a real secret row to shape.

   Vault is faked through the THREAD-LOCAL `vault/*impl-override*` seam
   and an atom-backed store the assertions inspect; the ctx carries a
   stub `:vault` client, so nothing touches the process-global
   `vault/active-client`. The graphden-write failure injection binds
   `crud-entities/*create-entity-override*` — also thread-local, so the
   NS stays parallel-safe."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.clients.vault :as vault]
    [graphden.crud.entities :as crud-entities]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.tenancy.context :as tctx]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:private ^:dynamic *router* nil)


(def ^:private ^:dynamic *storage* nil)


(def ^:private token "secrets-graph-test-token")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  (fn [t]
    ;; The swept rich-types BEFORE the bootstrap — without them the
    ;; router's `_router` HOF compiles as a shape-callable (see
    ;; `execute-http-test`).
    (reset! registry/*rich-types-override*
            (sb/ensure-swept-rich-types! ["core" "web" "app"]))
    (let [{:keys [storage]} (setup/bootstrap-crud-graph-from-golden!*
                              (str (ns-name *ns*)) ["core" "web" "app"])
          ctx (-> (exec/create-context
                    {:storage storage
                     :auth-provider (auth/single-token-provider token)})
                  (assoc :vault {:address "http://fake-vault" :token "fake"}))
          _ (cr/rebuild! ctx)]
      (try
        (binding [*router* (br/create-router ctx "_app-ring-response")
                  *storage* storage]
          (t))
        (finally (sp/close storage))))))


;; ============================================================================
;; In-memory vault
;; ============================================================================

(defn- fresh-vault
  []
  (atom {:values {} :metadata {}}))


(defn- with-fake-vault*
  [state body-fn]
  (binding [vault/*impl-override*
            {:get-secret    (fn [_client path]
                              (or (get-in @state [:values path])
                                  (throw (ex-info "no path"
                                                  {:type :vault/lookup-failed :path path}))))
             :put-secret    (fn [_client path value]
                              (swap! state assoc-in [:values path] value)
                              (count (swap! state update :versions
                                            (fnil conj []) [path value])))
             :delete-secret (fn [_client path]
                              (swap! state #(-> %
                                                (update :values dissoc path)
                                                (update :metadata dissoc path)))
                              nil)
             :get-metadata  (fn [_client path]
                              (or (get-in @state [:metadata path])
                                  (throw (ex-info "no metadata"
                                                  {:type :vault/lookup-failed :path path}))))
             :put-metadata  (fn [_client path metadata]
                              (swap! state assoc-in [:metadata path]
                                     {:custom_metadata metadata :current_version 1})
                              nil)}]
    (body-fn)))


(defmacro ^:private with-fake-vault
  [state & body]
  `(with-fake-vault* ~state (fn [] ~@body)))


;; ============================================================================
;; Request helpers — each returns the decoded JSON envelope
;; ============================================================================

(defn- uniq
  [stem]
  (str stem "-" (random-uuid)))


(defn- request!
  "Dispatch through the branch router; the decoded JSON envelope."
  ([method uri] (request! method uri nil))
  ([method uri body]
   (let [resp (br/dispatch *router*
                           (cond-> {:request-method method
                                    :uri uri
                                    :headers {"content-type" "application/json"
                                              "authorization" (str "Bearer " token)}
                                    :query-string nil}
                             body (assoc :body (cheshire/generate-string body))))]
     (cheshire/parse-string (str (:body resp)) true))))


(defn- list-secrets
  []
  (request! :get "/api/secrets"))


(defn- create-secret!
  [body]
  (request! :post "/api/secrets" body))


(defn- delete-secret!
  [id]
  (request! :delete (str "/api/secrets/" id)))


(defn- rotate-secret!
  [id body]
  (request! :put (str "/api/secrets/" id "/value") body))


(defn- create-inline-binding!
  [body]
  (request! :post "/api/secret-bindings" body))


(defn- rotate-inline-binding!
  [binding-id body]
  (request! :put (str "/api/secret-bindings/" binding-id) body))


(defn- storage
  []
  *storage*)


(defn- secret-leaf
  "The golden graph's `:secret-leaf` base-fn row (web.vault)."
  []
  (first (filter :return-type-fn-id
                 (sp/query-entities (storage) :fn {:name "secret-leaf"}))))


;; ============================================================================
;; list
;; ============================================================================

(deftest list-secrets-shape-test
  (with-fake-vault (fresh-vault)
    (let [nm (uniq "_db-password")
          created (create-secret! {:name nm :path (str "user-db/" nm)
                                   :value "hunter2" :description "user-db pwd"})
          _ (is (:ok created) (str created))
          {:keys [ok secrets]} (list-secrets)
          s (first (filter #(= nm (:name %)) secrets))]
      (is ok)
      (testing "the created secret is listed with its path + description"
        (is (= {:id (get-in created [:secret :id]) :path (str "user-db/" nm)
                :description "user-db pwd" :shape "secret-leaf"}
               (select-keys s [:id :path :description :shape]))))
      (testing "the wire shape never carries the value"
        (is (not (contains? s :value)))))))


(deftest list-secrets-filters-mi-children-test
  (let [other (setup/create-base-fn! (storage) (uniq "other-base") :text)
        nm (uniq "mi-child")]
    ;; Two parents (secret-leaf + something else) — not an admin secret.
    (sp/create-entity (storage) :fn {:name nm
                                     :parent-ids [(:id (secret-leaf)) (:id other)]})
    (with-fake-vault (fresh-vault)
      (is (empty? (filter #(= nm (:name %)) (:secrets (list-secrets))))))))


;; ============================================================================
;; create
;; ============================================================================

(deftest create-secret-happy-path-test
  (let [vault-state (fresh-vault)
        nm (uniq "_test")
        path (str "test/" nm)]
    (with-fake-vault vault-state
      (let [{:keys [ok secret]} (create-secret! {:name nm :path path :value "v1"})
            fn-id (parse-uuid (:id secret))
            fn-row (sp/read-entity (storage) :fn fn-id)
            bindings (sp/query-entities (storage) :binding {:fn-id fn-id})
            leaf (secret-leaf)
            leaf-slot-ids (set (map :slot-id (sp/query-entities (storage) :fn-slot
                                                                {:fn-id (:id leaf)})))]
        (is ok)
        (testing "vault holds the value"
          (is (= "v1" (get-in @vault-state [:values path]))))
        (testing "the fn row inherits exactly [secret-leaf]"
          (is (= nm (:name fn-row)))
          (is (= [(:id leaf)] (vec (:parent-ids fn-row)))))
        (testing "one :vault-get RESOLVER binding on the leaf slot carries the path"
          (is (= 1 (count bindings)))
          (is (= path (:value (first bindings))))
          (is (some? (:resolver-fn-id (first bindings))))
          (is (contains? leaf-slot-ids (:slot-id (first bindings)))))))))


(deftest create-secret-rolls-back-on-graphden-failure-test
  ;; Storage fn row lands, the binding write fails — the journal replay
  ;; must delete the fn row and leave nothing in vault.
  (let [vault-state (fresh-vault)
        nm (uniq "_rollback-pwd")
        path (str "rollback/" nm)]
    (with-fake-vault vault-state
      (binding [crud-entities/*create-entity-override*
                (fn [entity-type data ctx]
                  (if (= entity-type :binding)
                    (throw (ex-info "simulated binding-write failure" {:type :test/simulated}))
                    (binding [crud-entities/*create-entity-override* nil]
                      (crud-entities/create-entity entity-type data ctx))))]
        (let [{:keys [ok error]} (create-secret! {:name nm :path path :value "leaked?"})]
          (testing "the envelope is :ok false with the underlying error"
            (is (false? ok))
            (is (re-find #"simulated binding-write failure" (str error)))))))
    (testing "vault holds nothing at the path"
      (is (nil? (get-in @vault-state [:values path]))))
    (testing "no fn row survives the rollback"
      (is (empty? (sp/query-entities (storage) :fn {:name nm}))))))


(deftest create-secret-rejections-test
  (with-fake-vault (fresh-vault)
    (testing "blank name"
      (let [{:keys [ok error]} (create-secret! {:name "" :path "p" :value "v"})]
        (is (false? ok))
        (is (re-find #"name" (str error)))))
    (testing "blank path"
      (let [{:keys [ok error]} (create-secret! {:name (uniq "n") :path "" :value "v"})]
        (is (false? ok))
        (is (re-find #"path" (str error)))))
    (testing "missing value"
      (let [{:keys [ok error]} (create-secret! {:name (uniq "n") :path "p"})]
        (is (false? ok))
        (is (re-find #"value" (str error)))))
    (testing "duplicate name → reason name-taken"
      (let [nm (uniq "dupe")]
        (is (:ok (create-secret! {:name nm :path (str nm "/1") :value "v"})))
        (let [{:keys [ok reason]} (create-secret! {:name nm :path (str nm "/2") :value "v"})]
          (is (false? ok))
          (is (= "name-taken" reason)))))))


;; ============================================================================
;; delete
;; ============================================================================

(deftest delete-secret-happy-path-test
  (let [vault-state (fresh-vault)
        nm (uniq "_to-delete")
        path (str "to/" nm)]
    (with-fake-vault vault-state
      (let [{:keys [secret]} (create-secret! {:name nm :path path :value "x"})
            fn-id (parse-uuid (:id secret))]
        (is (:ok (delete-secret! fn-id)))
        (testing "the graphden row is gone"
          (is (nil? (sp/read-entity (storage) :fn fn-id))))
        (testing "the vault value is gone"
          (is (nil? (get-in @vault-state [:values path]))))))))


(deftest delete-secret-rejections-test
  (with-fake-vault (fresh-vault)
    (testing "unknown id → not-found"
      (is (= "not-found" (:reason (delete-secret! (random-uuid))))))
    (testing "a non-secret fn is refused"
      (let [non-secret (setup/create-base-fn! (storage) (uniq "not-a-secret") :text)]
        (is (= "not-a-secret" (:reason (delete-secret! (:id non-secret)))))))))


(deftest delete-secret-in-use-test
  (with-fake-vault (fresh-vault)
    (let [nm (uniq "_used-pwd")
          {:keys [secret]} (create-secret! {:name nm :path (str "used/" nm) :value "v"})
          secret-id (parse-uuid (:id secret))
          consumer-slot (setup/create-slot! (storage) "input" :text)
          consumer-base (setup/create-base-fn! (storage) (uniq "consumer-base") :text)
          _ (setup/attach-slot! (storage) (:id consumer-base) (:id consumer-slot) 0)
          consumer-name (uniq "consumer")
          consumer (setup/create-composed-fn! (storage) consumer-name (:id consumer-base))]
      (setup/bind-ref! (storage) (:id consumer) (:id consumer-slot) secret-id)
      (let [{:keys [ok reason usages]} (delete-secret! secret-id)]
        (is (false? ok))
        (is (= "secret-in-use" reason))
        (testing "the dependent is named in the usages list"
          (is (some #(= consumer-name (:name %)) usages)))
        (testing "the secret row is kept"
          (is (some? (sp/read-entity (storage) :fn secret-id))))))))


;; ============================================================================
;; rotate
;; ============================================================================

(deftest rotate-secret-happy-test
  (let [vault-state (fresh-vault)
        nm (uniq "_rot")
        path (str "rot/" nm)]
    (with-fake-vault vault-state
      (let [{:keys [secret]} (create-secret! {:name nm :path path :value "v1"})]
        (let [res (rotate-secret! (:id secret) {:value "v2"})]
          (is (:ok res))
          (testing "the response names the secret's own path"
            (is (= path (:path res)))))
        (testing "vault holds the new value at the same path"
          (is (= "v2" (get-in @vault-state [:values path]))))
        (testing "the path binding did not move"
          (is (= path (:value (first (sp/query-entities (storage) :binding
                                                        {:fn-id (parse-uuid (:id secret))}))))))))))


(deftest rotate-secret-rejects-non-owner-tenant-test
  ;; A PUBLIC secret is read-visible to every tenant but rotate writes
  ;; vault directly (no storage write-guard / RLS) — it must be refused.
  (let [vault-state (fresh-vault)
        nm (uniq "_shared")
        path (str "shared/" nm)]
    (with-fake-vault vault-state
      (let [{:keys [secret]} (create-secret! {:name nm :path path :value "v1"})
            {:keys [ok reason]} (tctx/with-org "tenant-x"
                                               (rotate-secret! (:id secret) {:value "v2"}))]
        (is (false? ok))
        (is (= "forbidden" reason))
        (testing "the vault value is untouched"
          (is (= "v1" (get-in @vault-state [:values path]))))))))


(deftest rotate-secret-rejections-test
  (with-fake-vault (fresh-vault)
    (testing "unknown id → not-found"
      (is (= "not-found" (:reason (rotate-secret! (random-uuid) {:value "x"})))))
    (testing "a missing value is refused"
      (let [nm (uniq "_r")
            {:keys [secret]} (create-secret! {:name nm :path (str "r/" nm) :value "v1"})
            {:keys [ok error]} (rotate-secret! (:id secret) {})]
        (is (false? ok))
        (is (re-find #"value" (str error)))))))


;; ============================================================================
;; inline secret bindings — the card's Bind secret form, and its rotate
;; ============================================================================

(defn- seed-secret-slot-owner!
  "A base-fn with one `[:secret :text]` slot (the `:sql-exec/:password`
   shape) — the resolver gate accepts a `:vault-get` binding on it."
  []
  (let [owner (setup/create-base-fn! (storage) (uniq "vf-db") :int)
        slot (setup/create-slot! (storage) "password" :text)]
    (setup/attach-slot! (storage) (:id owner) (:id slot) 0)
    (registry/record-rich-types! (:id owner) (keyword (:name owner))
                                 {:args {:password {:type [:secret :text]}}
                                  :return-type :int
                                  :effects #{:db}})
    {:owner owner :slot slot}))


(deftest rotate-inline-binding-happy-test
  (let [vault-state (fresh-vault)
        {:keys [owner slot]} (seed-secret-slot-owner!)
        path (uniq "db/password")]
    (with-fake-vault vault-state
      (let [{:keys [ok binding] :as created}
            (create-inline-binding! {:fn-id (str (:id owner)) :slot-id (str (:id slot))
                                     :path path :value "v1"})
            _ (is ok (str "inline bind lands: " created))
            res (rotate-inline-binding! (:id binding) {:value "v2"})]
        (is (:ok res) (str res))
        (is (= path (:path res)))
        (testing "vault holds the new value at the same path"
          (is (= "v2" (get-in @vault-state [:values path]))))
        (testing "the binding still points at the path"
          (is (= path (:value (sp/read-entity (storage) :binding
                                              (parse-uuid (:id binding)))))))))))


(deftest rotate-inline-binding-rejections-test
  (let [vault-state (fresh-vault)
        {:keys [owner slot]} (seed-secret-slot-owner!)
        plain (setup/create-slot! (storage) "sql" :text)
        _ (setup/attach-slot! (storage) (:id owner) (:id plain) 1)
        lit (sp/create-entity (storage) :binding {:fn-id (:id owner) :slot-id (:id plain)
                                                  :value "select 1"})]
    (with-fake-vault vault-state
      (testing "unknown binding → not-found"
        (is (= "not-found" (:reason (rotate-inline-binding! (random-uuid) {:value "x"})))))
      (testing "a literal binding is not a secret"
        (is (= "not-a-secret" (:reason (rotate-inline-binding! (:id lit) {:value "x"})))))
      (testing "a missing value is refused before any vault write"
        (let [path (uniq "db/pw2")
              {:keys [binding]} (create-inline-binding!
                                  {:fn-id (str (:id owner)) :slot-id (str (:id slot))
                                   :path path :value "v1"})
              res (rotate-inline-binding! (:id binding) {})]
          (is (false? (:ok res)))
          (is (= "v1" (get-in @vault-state [:values path]))))))))
