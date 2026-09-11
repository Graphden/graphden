(ns graphden.packages.app.secrets-impls-test
  "Unit tests for the `app/secrets` package's impls — the two arms of
   the secret write path that can be reached without OpenBao: the
   rotate OWNERSHIP guard and the shared rollback envelope.

   Both are security decisions:

   - `:_rotate-secret-not-owned?` is the ONLY thing standing between a
     tenant and the value of a secret it can merely SEE. Rotate writes
     vault directly, so it bypasses the storage write-guard + RLS that
     `:delete` goes through.
   - `:_apply-secret-rollback` builds the failure envelope that reaches
     the API caller. It must drop `:body` from the exception data —
     a vault error's ex-data carries the raw OpenBao HTTP response.

   Every assertion below binds `tc/*current-org*`: unbound means the
   PLATFORM tier, where every ownership check is a no-op and an
   authorization test passes vacuously.

   The three vault+storage impls (`:_apply-create-secret-body`,
   `:_apply-inline-bind-body`, and the rollback's REPLAY of vault
   entries) need a live OpenBao and are covered by
   `graphden.crud.secrets-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "app" "secrets"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays.
   `delay` is a macro, so the map cannot be built with `update-vals`."
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(defn- not-owned?
  [org fn-row]
  (binding [tc/*current-org* org]
    (call :_rotate-secret-not-owned? {:fn-row fn-row})))


;; =============================================================================
;; :_rotate-secret-not-owned? — the C9 ownership guard
;; =============================================================================

(deftest rotate-guard-refuses-a-secret-another-org-owns
  (testing "a tenant rotating another org's secret is refused"
    (is (true? (not-owned? "acme" {:id (random-uuid) :org-id "globex"})))))


(deftest rotate-guard-refuses-a-shared-public-secret
  (testing "a PUBLIC secret is readable by every tenant but rotatable by none"
    ;; This is the arm the guard exists for: an org-scoped read makes
    ;; another tenant's PRIVATE secret invisible already (→ not-found).
    ;; A public/shared row is visible, and rotate mutates vault
    ;; directly — without this branch any tenant could overwrite the
    ;; value every other tenant depends on.
    (is (true? (not-owned? "acme" {:id (random-uuid) :org-id nil})))
    (is (true? (not-owned? "acme" {:id (random-uuid) :org-id "public"})))))


(deftest rotate-guard-allows-an-org-its-own-secret
  (testing "the owning tenant passes the guard"
    (is (false? (not-owned? "acme" {:id (random-uuid) :org-id "acme"})))))


(deftest rotate-guard-is-inert-on-the-platform-tier
  (testing "single-tenant / operator context may rotate anything"
    ;; Unbound (= \"public\") is the single-tenant executor: there is no
    ;; tenant boundary to enforce, and refusing here would break every
    ;; self-hosted rotation.
    (is (false? (not-owned? tc/public-org {:id (random-uuid) :org-id "globex"})))))


(deftest rotate-guard-returns-a-boolean-not-a-truthy-row
  (testing "a missing fn-row answers false, and the answer is always boolean"
    ;; The graph's `:cond` branches on this value; a nil/row leak would
    ;; still work by accident today and break the moment the predicate
    ;; is used anywhere that compares to `false`. The not-found case has
    ;; its own earlier guard, so this arm must NOT claim a refusal.
    (is (false? (not-owned? "acme" nil)))
    (is (boolean? (not-owned? "acme" {:org-id "acme"})))))


;; =============================================================================
;; :_apply-secret-rollback — the failure envelope
;; =============================================================================

(defn- rollback
  [journal ex]
  (call :_apply-secret-rollback
        {:journal (atom journal) :exception ex}
        ;; Storage is only reached for `:storage-delete` journal
        ;; entries; `require-storage` demands the key regardless.
        {:storage ::stub-storage}))


(deftest rollback-envelope-drops-the-vault-response-body
  (testing "ex-data reaches the caller with :body stripped"
    ;; A vault error's ex-data carries the raw OpenBao HTTP response
    ;; text — internal noise, and a secret-echo vector if a proxy
    ;; mangles it. The rest of the ex-data is deliberately kept: it is
    ;; what tells the user WHICH step failed.
    (let [r (rollback [] (ex-info "vault put failed"
                                  {:type :vault/write-failed
                                   :status 403
                                   :body "{\"errors\":[\"permission denied\"]}"}))]
      (is (false? (:ok r)))
      (is (= "vault put failed" (:error r)))
      (is (nil? (get-in r [:data :body])))
      (is (= :vault/write-failed (get-in r [:data :type])))
      (is (= 403 (get-in r [:data :status]))))))


(deftest rollback-envelope-of-a-plain-exception-carries-no-data-key
  (testing "a non-ExceptionInfo yields {:ok false :error} only"
    ;; `(ex-data e)` is nil for a plain exception; emitting `:data nil`
    ;; would put a meaningless null in the JSON response body.
    (let [r (rollback [] (RuntimeException. "boom"))]
      (is (= {:ok false :error "boom"} r))
      (is (not (contains? r :data))))))


(deftest rollback-step-failure-does-not-mask-the-original-error
  (testing "an undo step that throws is swallowed; the caller still sees the cause"
    ;; Re-throwing from the compensation path would replace the real
    ;; failure with a rollback failure, and the operator would never
    ;; learn why the write failed. The stub storage cannot delete, so
    ;; this journal entry throws inside the replay.
    (let [r (rollback [[:storage-delete :fn (random-uuid)]]
                      (ex-info "original cause" {:type :secrets/create-failed}))]
      (is (false? (:ok r)))
      (is (= "original cause" (:error r)))
      (is (= :secrets/create-failed (get-in r [:data :type]))))))
