(ns graphden.accounts.core-test
  "Integration tests for the open accounts model against a real Postgres — the
   `:account` / `:identity` / `:session` schema, password signup/login, session
   mint/authenticate/revoke, and the account-linking primitive with its
   conflict guard. Tagged `:integration` (spins the shared container)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.accounts.account-schema :as account-schema]
    [graphden.accounts.core :as accounts]
    [graphden.accounts.identity-schema :as identity-schema]
    [graphden.accounts.session-schema :as session-schema]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.shared-container :as sc]))


(def ^:dynamic *container* nil)


(def ^:private storage-atom (atom nil))


(defn- accounts-schema
  []
  (-> (mds/create-builder)
      (account-schema/extend-builder)
      (identity-schema/extend-builder)
      (session-schema/extend-builder)
      (ds/build)))


(defn- with-schema
  [f]
  (let [s (pg/create-storage (sc/get-config))]
    (sp/initialize s (accounts-schema))
    (reset! storage-atom s)
    (f)))


(use-fixtures :once
  (sc/shared-container-fixture #'*container*)
  with-schema)


(defn- storage
  []
  @storage-atom)


;; ---------------------------------------------------------------------------

(deftest ^:integration password-signup-and-login
  (testing "signup creates account + identity + an authenticating session"
    (let [{:keys [account account-id token]}
          (accounts/password-signup! (storage)
                                     {:email "Alice@Example.com" :password "s3cret-pw"
                                      :display-name "Alice"})]
      (is (some? account-id))
      (is (= "active" (:status account)))
      (is (nil? (:primary-email account))
          "email is NOT promoted to primary-email until verified (Phase 1)")
      (is (= account-id (str (:id (accounts/authenticate-token (storage) token))))
          "the minted token authenticates back to the same account")
      (testing "the password identity is stored normalized + unverified, hash not plaintext"
        (let [ident (accounts/find-identity (storage) "password" "alice@example.com")]
          (is (some? ident))
          (is (false? (:email-verified? ident)))
          (is (not= "s3cret-pw" (:secret-data ident)))))))

  (testing "login verifies the password (case-insensitive email) and mints a fresh session"
    (let [{:keys [token account-id]}
          (accounts/password-login! (storage) {:email "alice@EXAMPLE.com" :password "s3cret-pw"})]
      (is (some? token))
      (is (= account-id (str (:id (accounts/authenticate-token (storage) token)))))))

  (testing "wrong password / unknown email fail closed"
    (is (nil? (accounts/password-login! (storage) {:email "alice@example.com" :password "wrong"})))
    (is (nil? (accounts/password-login! (storage) {:email "nobody@example.com" :password "x"}))))

  (testing "duplicate signup on the same email is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
          (accounts/password-signup! (storage)
                                     {:email "alice@example.com" :password "another"})))))


(deftest ^:integration session-lifecycle
  (let [{:keys [account-id]} (accounts/password-signup! (storage)
                                                        {:email "bob@example.com" :password "pw-bob-123"})]
    (testing "a live session authenticates; revoking it kills exactly that session"
      (let [t1 (accounts/mint-session! (storage) account-id)
            t2 (accounts/mint-session! (storage) account-id)]
        (is (some? (accounts/authenticate-token (storage) t1)))
        (is (some? (accounts/authenticate-token (storage) t2)))
        (accounts/revoke-token! (storage) t1)
        (is (nil? (accounts/authenticate-token (storage) t1)) "revoked token is dead")
        (is (some? (accounts/authenticate-token (storage) t2)) "the other session survives")))
    (testing "an expired session does not authenticate"
      (let [expired (accounts/mint-session! (storage) account-id {:ttl-ms -1})]
        (is (nil? (accounts/authenticate-token (storage) expired)))))
    (testing "revoke-all clears every session for the account"
      (let [t (accounts/mint-session! (storage) account-id)]
        (is (some? (accounts/authenticate-token (storage) t)))
        (accounts/revoke-all-for-account! (storage) account-id)
        (is (nil? (accounts/authenticate-token (storage) t)))))
    (testing "scopes round-trip: stored on mint, surfaced parsed on authenticate"
      (let [scoped (accounts/mint-session! (storage) account-id
                                           {:kind "api" :ttl-ms nil
                                            :scopes "write execute merge"})
            acct (accounts/authenticate-token (storage) scoped)]
        (is (= "api" (:token-kind acct)))
        (is (= #{:write :execute :merge} (:token-scopes acct))))
      (let [unscoped (accounts/mint-session! (storage) account-id {:kind "api" :ttl-ms nil})
            acct (accounts/authenticate-token (storage) unscoped)]
        (is (nil? (:token-scopes acct)) "nil scopes = unscoped (legacy)"))
      (let [cookie-like (accounts/mint-session! (storage) account-id)
            acct (accounts/authenticate-token (storage) cookie-like)]
        (is (nil? (:token-kind acct)) "browser session carries no token-kind")))))


(deftest parse-scopes-shapes
  (is (= #{:write :execute} (accounts/parse-scopes "write execute")))
  (is (= #{:write} (accounts/parse-scopes "  write  ")))
  (is (nil? (accounts/parse-scopes nil)))
  (is (nil? (accounts/parse-scopes "")))
  (is (nil? (accounts/parse-scopes "   "))))


(deftest ^:integration suspended-account-cannot-authenticate
  (let [{:keys [account token]}
        (accounts/password-signup! (storage) {:email "carol@example.com" :password "pw-carol-1"})]
    (is (some? (accounts/authenticate-token (storage) token)))
    (sp/update-entity (storage) :account (:id account) (assoc account :status "suspended"))
    (is (nil? (accounts/authenticate-token (storage) token))
        "a live session on a suspended account fails closed")))


(deftest ^:integration account-linking
  (let [{:keys [account-id]} (accounts/password-signup! (storage)
                                                        {:email "dave@example.com" :password "pw-dave-99"})]
    (testing "a fresh provider identity links to the account and resolves"
      (accounts/link-identity! (storage) account-id
                               {:provider "github" :subject "gh-12345"
                                :email "dave@example.com" :email-verified? true})
      (let [ident (accounts/find-identity (storage) "github" "gh-12345")]
        (is (= account-id (:account-id ident)))
        (is (true? (:email-verified? ident)))))
    (testing "linking the same identity to the SAME account is idempotent"
      (is (some? (accounts/link-identity! (storage) account-id
                                          {:provider "github" :subject "gh-12345"}))))
    (testing "linking an identity already owned by ANOTHER account is rejected"
      (let [{other-id :account-id} (accounts/password-signup! (storage)
                                                              {:email "erin@example.com" :password "pw-erin-77"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another account"
              (accounts/link-identity! (storage) other-id
                                       {:provider "github" :subject "gh-12345"})))))
    (testing "an account owns exactly its linked identities"
      (is (= #{"password" "github"}
             (set (map :provider (accounts/identities-for-account (storage) account-id))))))))


(deftest ^:integration accounts-of-batches-and-matches-account-of
  (let [mk #(accounts/create-account! (storage) {:display-name % :primary-email (str % "@example.com")})
        a (mk "batch-a")
        b (mk "batch-b")
        ids [(str (:id a)) (str (:id b))]]
    (testing "batch result row-for-row equals the per-id reads"
      (is (= {(str (:id a)) (accounts/account-of (storage) (str (:id a)))
              (str (:id b)) (accounts/account-of (storage) (str (:id b)))}
             (accounts/accounts-of (storage) ids))))
    (testing "unknown / nil / duplicate ids are tolerated and absent from the result"
      (is (= #{(str (:id a))}
             (set (keys (accounts/accounts-of
                          (storage)
                          [nil (str (:id a)) (str (:id a))
                           (str (random-uuid)) "not-a-uuid"]))))))))


(deftest ^:integration preview-capsule-roundtrip
  (let [{:keys [account-id]}
        (accounts/password-signup! (storage)
                                   {:email "pv-capsule@example.com"
                                    :password "s3cret-pw"
                                    :display-name "PV"})
        fn-id (random-uuid)
        branch-id (random-uuid)
        token (accounts/mint-preview-token! (storage) account-id
                                            "acme" fn-id branch-id)]
    (testing "the capsule resolves to its exact (org, fn, branch) grant"
      (let [g (accounts/preview-grant-by-token (storage) token)]
        (is (= "acme" (:org g)))
        (is (= (str fn-id) (:fn-id g)))
        (is (= (str branch-id) (:branch-id g)))
        (is (= account-id (str (:account-id g))))))
    (testing "a capsule NEVER authenticates as a session (kind gate)"
      (is (nil? (accounts/authenticate-token (storage) token))))
    (testing "an ordinary session is NOT a preview grant (kind gate, other way)"
      (let [session (accounts/mint-session! (storage) account-id)]
        (is (nil? (accounts/preview-grant-by-token (storage) session)))))
    (testing "unknown / blank tokens fail closed"
      (is (nil? (accounts/preview-grant-by-token (storage) "nope")))
      (is (nil? (accounts/preview-grant-by-token (storage) ""))))
    (testing "revocation kills the capsule"
      (accounts/revoke-token! (storage) token)
      (is (nil? (accounts/preview-grant-by-token (storage) token))))))
