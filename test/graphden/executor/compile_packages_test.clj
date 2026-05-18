(ns ^:integration graphden.executor.compile-packages-test
  "Compile + execute the executor against the REAL package graph.

   The `graphden.executor.compile*` namespaces carry deep branches —
   env-binding collection, free-arg/rename propagation, sequence-ref
   resolution, HOF wrapping — that the synthetic two-fn fixtures in
   `compile-test` / `compile/*-test` don't reach. The `examples.*`
   packages were written precisely to exercise those features, so the
   highest-fidelity coverage is: boot the full package sync, let
   `cr/rebuild!` compile every fn (covers the compile-time branches),
   then execute a spread of `ex-*` fns (covers the runtime branches).

   Fixture boots `:dev` config up to `:exec/compiled-registry` — the
   whole executor minus the HTTP server."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


(def ^:dynamic *container* nil)
(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  (fn [f]
    (pth/clean-database-fast! *container*)
    (let [cfg    (pth/get-container-config *container*)
          config (-> (sys/read-config :dev)
                     (assoc-in [:db/postgres :jdbc-url] (:jdbc-url cfg))
                     (assoc-in [:db/postgres :username] (:username cfg))
                     (assoc-in [:db/postgres :password] (:password cfg)))
          ;; :exec/compiled-registry pulls in storage → base-fns →
          ;; fn-entities → context → cr/rebuild!. cr/rebuild! runs
          ;; `compile-all` over the whole graph.
          system (ig/init config [:exec/compiled-registry])]
      (binding [*context* (:exec/context system)
                *storage* (:db/versioned system)]
        (try (f) (finally (ig/halt! system)))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(defn- run
  ([nm] (run nm {}))
  ([nm args] (exec/execute *context* (fn-id nm) args)))


;; ============================================================================
;; compile-all over the real graph — sanity
;; ============================================================================

(deftest compiled-registry-built-test
  (testing "every reachable fn compiled into the registry at startup"
    (is (some? (fn-id "ex-greeting")) "example package synced")
    (is (some? (fn-id "web-server")) "app package synced")))


;; ============================================================================
;; examples.basics — literals, fn-refs, the keyword-literal escape
;; ============================================================================

(deftest basics-test
  (testing "literal value, ref chains, literal-keyword escape"
    (is (= "hello, graphden" (run "ex-greeting")))
    (is (= 15 (run "ex-greeting-len")))
    (is (= "HELLO, GRAPHDEN" (run "ex-greeting-upper")))
    (is (= :ok (run "ex-status-keyword")))
    (is (= "123" (run "ex-str-of-three")))))


;; ============================================================================
;; examples.collections — mixed sequence items, stacked assoc, count
;; ============================================================================

(deftest collections-test
  (testing "sequence slots with interleaved literals + fn-refs"
    (is (= [1 3 5] (vec (run "ex-mixed-list"))))
    (is (= [0 1 3 5] (vec (run "ex-prepended"))))
    (is (= 4 (run "ex-count-prepended")))
    (is (= 113 (run "ex-sum-mixed")))
    (is (= {"version" "1.0" "status" "ok"} (run "ex-status-map")))))


;; ============================================================================
;; examples.free-args — propagation + `{:as}` rename, shared free args
;; ============================================================================

(deftest free-args-test
  (testing "an unbound inner arg propagates up as the caller's free arg"
    (is (= "HI!" (run "ex-shout" {:string "hi"}))))

  (testing "`{:as}` renames the propagated free arg"
    (is (= "Hello, BOB!" (run "ex-greet" {:name "bob"}))))

  (testing "one free arg reused by two ref sites flows to both"
    (is (= "BOB & BOB" (run "ex-double-greet" {:name "bob"}))))

  (testing "two distinct rename targets surface two independent free args"
    (is (= "A meets B" (run "ex-pair-greet" {:first "a" :second "b"})))))


;; ============================================================================
;; examples.hof — map / filter, eager + the partial-bound predicate
;; ============================================================================

(deftest hof-test
  (testing "eager :map hof-wraps the fn-ref into a one-arg callable"
    (is (= ["ALPHA" "BETA" "GAMMA"] (vec (run "ex-upper-each")))))

  (testing ":filter with a built-in predicate"
    (is (= [1 2 3] (vec (run "ex-only-some")))))

  (testing ":filter with a partial-bound (free-arg) predicate template"
    (is (= ["alpha" "apple"] (vec (run "ex-starts-with-a")))))

  (testing ":map without :coll yields a transducer object"
    (is (fn? (run "ex-upper-xf")))
    (is (fn? (run "ex-pipeline-xf")))))


;; ============================================================================
;; examples.reduce-pattern — :reduce with the `[acc item]` pair callable
;; ============================================================================

(deftest reduce-pattern-test
  (testing ":reduce folds with a single-arg reducer over `[acc item]`"
    (is (= 15 (run "ex-sum-vec")))
    (is (= 120 (run "ex-product-vec"))))

  (testing ":reduce over a caller-supplied collection (free `:coll`)"
    (is (= 6 (run "ex-sum-of" {:coll [1 2 3]})))))


;; ============================================================================
;; examples.regression — env-bindings + sequence-typed ref slots
;; ============================================================================

(deftest regression-examples-test
  (testing "a value bound on a ref-reached slot flows through augment-env"
    ;; `ex-outer` → … → `_ex-list-of-one`, where `:item1` (renamed,
    ;; reached only via the `:coll` fn-ref) is fixed to \"first\" by an
    ;; ancestor. That's an env-binding — the runtime merges it into the
    ;; closure via `augment-env`.
    (is (= [["first" "second"]]
           (mapv vec (run "ex-outer" {:item2 "second"})))))

  (testing "a :sequence-typed slot bound to a single fn-ref resolves"
    (is (= "abc" (run "ex-regression-str-via-ref")))))


;; ============================================================================
;; ref env-bindings — compiling/executing the real router
;; ============================================================================

(deftest router-ref-env-bindings-test
  (testing "`_router` compiles + executes to a callable"
    ;; text-error-router's MI parents (r404/r405/r500) bind the
    ;; default-handler response slots — substitution-context bindings
    ;; that surface as `:ref`-kind env-bindings, exercising
    ;; augment-env's :ref branch + make-ref-entry.
    (is (fn? (run "_router")))))


;; ============================================================================
;; :cond / :case execution + executor laziness (short-circuit)
;; ============================================================================

(deftest cond-case-execution-test
  (testing ":cond multi-branch dispatch over a free arg"
    (is (= "neg"  (run "ex-sign" {:n -3})))
    (is (= "zero" (run "ex-sign" {:n 0})))
    (is (= "pos"  (run "ex-sign" {:n 7}))))
  (testing ":case exact-match dispatch + default"
    (is (= "Active"  (run "ex-status-label" {:status "active"})))
    (is (= "Unknown" (run "ex-status-label" {:status "no-such"})))))


(deftest lazy-short-circuit-test
  (testing ":cond / :and / :or / :case never evaluate an un-taken branch
            — each example hides a :throw there; reaching it would raise
            `examples/laziness-violated` and fail this test"
    (is (= "safe"    (run "ex-lazy-cond")))
    (is (false?      (run "ex-lazy-and")))
    (is (true?       (run "ex-lazy-or")))
    (is (= "matched" (run "ex-lazy-case")))))
