(ns graphden.web.errors-doc-test
  "Drift guard: every explicitly-mapped error type in
   `graphden.web.errors/status-for-type` must appear in
   docs/ERROR_CODES.md's status section, and the mapper's family
   fallbacks + safe-body behavior stay pinned."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.web.errors :as errors]))


(deftest every-mapped-type-is-documented
  (let [doc (slurp "docs/ERROR_CODES.md")]
    (doseq [t (keys errors/status-for-type)]
      (is (or (str/includes? doc (str t))
              (str/includes? doc (str (namespace t) "/*"))
              ;; execute reasons are documented prose-style
              (= "execution" (namespace t)))
          (str t " missing from ERROR_CODES.md status section")))))


(deftest status-mapping-behavior
  (testing "explicit types"
    (is (= 404 (errors/status-for :not-found)))
    (is (= 409 (errors/status-for :merge-conflict)))
    (is (= 409 (errors/status-for :constraint-violation/fn-name-collision)))
    (is (= 429 (errors/status-for :execution/over-capacity)))
    (is (= 429 (errors/status-for :quota/entity-limit)))
    (is (= 413 (errors/status-for :execution/args-too-large)))
    (is (= 403 (errors/status-for :execution/forbidden-effect))
        "the effect sandbox's refusal is a policy answer, not a 5xx")
    (is (= 503 (errors/status-for :vault/not-configured))))
  (testing "family fallbacks cover new members automatically"
    (is (= 400 (errors/status-for :packages/whatever-new)))
    (is (= 400 (errors/status-for :type-check/rejected)))
    (is (= 403 (errors/status-for :capability/secret-leaf-restricted))))
  (testing "unknown → 500"
    (is (= 500 (errors/status-for :totally/unknown)))
    (is (= 500 (errors/status-for nil)))))


(deftest safe-body-withholds-internal-messages
  (testing "author-facing family passes the message"
    (let [b (errors/safe-error-body :packages/ambiguous-ref "qualify it")]
      (is (= "qualify it" (:message b)))
      (is (nil? (:ref b)))))
  (testing "internal type gets an opaque ref, message withheld"
    (let [b (errors/safe-error-body :org/scoped-storage "SELECT boom FROM secret")]
      (is (some? (:ref b)))
      (is (not (str/includes? (:message b) "SELECT")))))
  (testing "the :quota family is author-facing — the row-cap message reaches the user"
    (let [b (errors/safe-error-body :quota/entity-limit "You've reached your plan's function limit.")]
      (is (nil? (:ref b)))
      (is (= "You've reached your plan's function limit." (:message b)))))
  (testing "absent type gets an opaque ref"
    (is (some? (:ref (errors/safe-error-body nil "raw jdbc text"))))))


(deftest status-for-ex-data-remaps-user-shaped-sql-errors
  ;; The live entity-write path (crud/entities) routes storage errors through
  ;; here: a `42xxx` sql-state (undefined column / syntax — the DB rejecting
  ;; USER-SHAPED input) that would otherwise be an opaque 500 becomes a 400.
  (testing "an unmapped 42xxx storage error → 400 (actionable), not 500"
    (is (= 400 (errors/status-for-ex-data {:sql-state "42703"})))   ; undefined column
    (is (= 400 (errors/status-for-ex-data {:sql-state "42601"}))))  ; syntax error
  (testing "a non-42xxx failure stays 500 (a real outage is NOT the tenant's fault)"
    (is (= 500 (errors/status-for-ex-data {:sql-state "57014"})))   ; query cancelled/timeout
    (is (= 500 (errors/status-for-ex-data {}))))
  (testing "an explicitly-typed error keeps its mapping even with a 42xxx sql-state"
    (is (= 404 (errors/status-for-ex-data {:type :not-found :sql-state "42703"})))))


(deftest boundary-downgrades-user-shaped-sql-errors-to-400
  ;; The TOP boundary (`response-for-throwable`, used by `wrap-error-boundary`)
  ;; must apply the same 42xxx→400 downgrade as the local crud path — a raw
  ;; SQL error from user-shaped input that escapes to the boundary answers
  ;; 400, not 500, WITHOUT leaking the SQL text.
  (testing "a 42xxx storage error at the boundary → 400, opaque message"
    (let [r (errors/response-for-throwable
              (ex-info "ERROR: column \"boom\" does not exist"
                       {:sql-state "42703"}))
          body (json/parse-string (:body r) true)]
      (is (= 400 (:status r)))
      (is (some? (:ref body)) "message withheld behind an opaque ref")
      (is (not (str/includes? (:body r) "boom"))
          "raw SQL text never reaches the client body")))
  (testing "a genuine server fault (no 42xxx) stays 500"
    (let [r (errors/response-for-throwable
              (ex-info "connection reset" {:sql-state "08006"}))]
      (is (= 500 (:status r)))))
  (testing "an explicitly-typed error keeps its status through the boundary"
    (is (= 404 (:status (errors/response-for-throwable
                          (ex-info "gone" {:type :not-found :sql-state "42703"})))))))
