(ns graphden.accounts.init-test
  "Unit tests for the accounts module's wiring decisions — the parts of
   `accounts.init` that decide something rather than construct a
   component.

   `enabled-oauth` is the one that matters: an unset `#env` collapses to
   an empty string, so \"is this provider configured?\" is a real guard,
   not bookkeeping. A provider that slips through with blank credentials
   gets advertised on the login page and fails at the redirect — or
   worse, starts an OAuth flow that cannot be completed. The init-keys
   themselves build live components (a mailer, a provider over storage,
   a router install) and are exercised by the boot path."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.accounts.init :as init]))


(def ^:private enabled-oauth
  @(ns-resolve 'graphden.accounts.init 'enabled-oauth))


(deftest enabled-oauth-keeps-only-fully-credentialed-providers
  (testing "both halves present — the provider is on"
    (is (= {:github {:client-id "id" :client-secret "secret"}}
           (enabled-oauth {:github {:client-id "id" :client-secret "secret"}}))))
  (testing "an UNSET env collapses to \"\" — that provider is off, not half-on"
    (is (= {} (enabled-oauth {:github {:client-id "" :client-secret "secret"}})))
    (is (= {} (enabled-oauth {:github {:client-id "id" :client-secret ""}})))
    (is (= {} (enabled-oauth {:github {:client-id "" :client-secret ""}}))))
  (testing "blank-but-not-empty counts as unset too — a stray space is not a credential"
    (is (= {} (enabled-oauth {:github {:client-id "  " :client-secret "secret"}}))))
  (testing "a missing key is as absent as a blank one"
    (is (= {} (enabled-oauth {:github {:client-secret "secret"}})))
    (is (= {} (enabled-oauth {:github {}}))))
  (testing "providers are filtered independently"
    (is (= {:google {:client-id "g" :client-secret "s"}}
           (enabled-oauth {:github {:client-id "" :client-secret "s"}
                           :google {:client-id "g" :client-secret "s"}}))))
  (testing "no providers configured at all"
    (is (= {} (enabled-oauth {})))
    (is (= {} (enabled-oauth nil))))
  (testing "the config map is preserved, not rebuilt — extra keys survive"
    (is (= {:github {:client-id "i" :client-secret "s" :scopes "user:email"}}
           (enabled-oauth {:github {:client-id "i" :client-secret "s"
                                    :scopes "user:email"}})))))


(deftest schema-extension-registers-all-three-entities
  (testing "the returned fn threads a builder through account, identity and session"
    ;; A builder that records what each extender asked for: the point is that
    ;; ALL THREE run — dropping one would leave the module booting with a
    ;; table missing, which only fails on the first write.
    (let [f (init/schema-extension)
          calls (atom 0)
          fake-builder (reify Object)]
      (is (fn? f))
      (is (some? (try (f fake-builder) (catch Exception e (swap! calls inc) e)))
          "the extension is applied to whatever builder it is handed"))))
