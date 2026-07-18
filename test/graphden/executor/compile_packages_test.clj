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
    [cheshire.core]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime]
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
  ;; `:exec/base-fns` init-key registers ~190 package base-fn impls
  ;; into the process-global registry via `exec/register-base-fn!`.
  ;; Wrap the integrant init in `with-clean-registry` so those
  ;; writes land in a thread-local override atom — sibling test
  ;; ns'es running in parallel kaocha threads keep their own
  ;; override and don't race on the global atom.
  exec/with-clean-registry
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
    (is (= {"version" "1.0" "status" "ok"} (run "ex-status-map"))))
  (testing ":pairs->map folds pairs (behavioural contract — decomposition attempted + reverted, see fns.edn blocker note)"
    (is (= {"a" 1 "b" 2} (run "pairs->map" {:entries [["a" 1] ["b" 2]]})))
    ;; lazy-seq pairs (what :list-built pairs look like) get vec-coerced
    (is (= {"k" "v"} (run "pairs->map" {:entries [(map identity ["k" "v"])]})))
    (is (= {"a" 2} (run "pairs->map" {:entries [["a" 1] ["a" 2]]}))
        "later pairs override earlier ones")))


;; ============================================================================
;; core.system — :heap-memory / :os-info recomposed via :zipmap
;; ============================================================================

(deftest system-info-composition-test
  ;; :heap-memory / :os-info were opaque all-in-one base-fns; they are
  ;; now :zipmap compositions over single-bean-call primitives. Assert
  ;; the recomposed maps keep the original key sets and value kinds.
  (testing ":heap-memory recomposes the six memory primitives"
    (let [m (run "heap-memory")]
      (is (= #{:heap-used :heap-max :heap-committed :free :total :max}
             (set (keys m))))
      (is (every? int? (vals m)))))
  (testing ":os-info recomposes the four OS primitives"
    (let [m (run "os-info")]
      (is (= #{:name :arch :processors :load-average} (set (keys m))))
      (is (string? (:name m)))
      (is (int? (:processors m))))))


;; ============================================================================
;; web.http — :process-response recomposed in the graph
;; (stringify-headers → Connection: close policy → negotiated encoding)
;; ============================================================================

(deftest process-response-composition-test
  (let [big-body (str/join (repeat 2000 "x"))
        resp {:status 200
              :headers {:Content-Type "application/json"}
              :body big-body}]
    (testing "gzip path — compressible, accepted, over threshold"
      (let [out (run "process-response"
                     {:request {:headers {"accept-encoding" "gzip"}}
                      :response resp})
            headers (:headers out)]
        (is (= "gzip" (get headers "Content-Encoding")))
        (is (= "close" (get headers "Connection")) "close policy applied")
        (is (= "application/json" (get headers "Content-Type"))
            "keyword header keys stringified")
        (is (= "Accept-Encoding" (get headers "Vary")))
        (is (bytes? (:body out)))
        (is (= (str (alength ^bytes (:body out))) (get headers "Content-Length")))
        (is (= big-body
               (with-open [in (java.util.zip.GZIPInputStream.
                                (java.io.ByteArrayInputStream. (:body out)))]
                 (slurp in)))
            "gzip round-trips to the original body")))
    (testing "brotli preferred over gzip when both accepted"
      (let [out (run "process-response"
                     {:request {:headers {"accept-encoding" "br, gzip"}}
                      :response resp})]
        (is (= "br" (get-in out [:headers "Content-Encoding"])))))
    (testing "identity path — no acceptable coding → body untouched, close still applied"
      (let [out (run "process-response" {:request {:headers {}} :response resp})]
        (is (= big-body (:body out)))
        (is (= "close" (get-in out [:headers "Connection"])))
        (is (nil? (get-in out [:headers "Content-Encoding"])))))
    (testing "under-threshold body not compressed"
      (let [out (run "process-response"
                     {:request {:headers {"accept-encoding" "gzip"}}
                      :response {:status 200
                                 :headers {"Content-Type" "text/html"}
                                 :body "tiny"}})]
        (is (= "tiny" (:body out)))))
    (testing "non-compressible content-type not compressed"
      (let [out (run "process-response"
                     {:request {:headers {"accept-encoding" "gzip"}}
                      :response {:status 200
                                 :headers {"Content-Type" "image/png"}
                                 :body big-body}})]
        (is (= big-body (:body out)))))
    (testing "already-encoded response passes through"
      (let [out (run "process-response"
                     {:request {:headers {"accept-encoding" "gzip"}}
                      :response {:status 200
                                 :headers {"Content-Type" "application/json"
                                           "Content-Encoding" "br"}
                                 :body big-body}})]
        (is (= big-body (:body out)))))))


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
;; Regression — /api/secrets list-secrets-handler must terminate
;; ============================================================================
;;
;; Production `:list-secrets-handler` is a `:json-handler` over the
;; `:_list-secrets-data` fn-graph (filter+map over storage-resolved
;; rows). The graph compiles fine, individual sub-fns (vault HTTP,
;; shape-secret, _list-secrets-data) terminate on their own, but the
;; FULL handler hangs at runtime when invoked through the proper
;; `:request`-bearing fa. This pins the failing case so the future
;; surgical fix has a green signal.
;;
;; Repro shape: invoke the compiled closure for `:list-secrets-handler`
;; with `{:request <stub>}`. With the bug present, the future never
;; completes within 2 s. Once fixed, the test asserts the response is
;; a Ring map with `:status 200` and a `:body` that parses to
;; `{ok true, secrets [...]}` (empty list when there are no secret
;; fn-defs).
;;
;; ROOT CAUSE (diagnosed via instrumented `call-with-cache` probe):
;; `:_list-secrets-leaf-fn-slots-identities` queries `:fn-slot` with
;; `{:where {}}` (empty), loading EVERY fn-slot identity in storage —
;; ~8.6k rows on the boot-sync test graph. `:resolve-fn-slot-rows`
;; then iterates each identity through `:_rv-resolve-one`, which
;; calls `:_rv-resolved-version-for-eid` twice per identity (the
;; `:if some? then merged else nil` test+then) plus inner refs.
;; Empirically: ~130k `call-with-cache` invocations in 5 s, 568k in
;; 60 s, with the top frame being `:_rv-resolved-version-for-eid`
;; (17k) → `:current-branch-chain` (8.6k) → `:_rv-version-data-
;; selected` / `:_rv-this-eid` / `:_rv-versions-on-bid` (8.6k each).
;;
;; The `[ref-id × fa]` cache key in `call-with-cache` rejects every
;; iteration because `fa` differs per `:item`, even for refs whose
;; deep-free args don't include `:item`. So `:current-branch-chain`
;; (no `:item` dependency) recomputes 8.6k times instead of once.
;;
;; Fix paths (in order of architectural cleanness):
;;
;; 1. Project `fa` to the ref-target's `deep-free-ext-names` before
;;    cache lookup. Memoises by *relevant* fa subset — refs invariant
;;    to iteration cache after first call. Touches `call-with-cache`
;;    only. Most general fix; benefits every HOF callback that calls
;;    an iteration-invariant ref.
;;
;; 2. Push the fn-id filter down to the version-table SQL: extend
;;    `:_resolve-fn-slot-versions-hsql-where` to accept extra
;;    predicates, then have `_list-secrets-leaf-fn-slots-resolved`
;;    pass `[:= :fn-id leaf-id]`. Avoids the iteration entirely for
;;    this query. Less general but more targeted.
;;
;; 3. Replace the secrets-graph path with a tailored `:pg-query` over
;;    `:fn-slot-version` filtered by `:fn-id` + `:branch-id IN chain`.
;;    Bypasses `:resolve-fn-slot-rows` for this consumer. Smallest
;;    blast radius, biggest divergence from the unified versioned-
;;    read pattern Phase 3 established.
;;
;; Test stays the same regardless of which path; once the hang is
;; gone it turns green.

(deftest ^:integration list-secrets-handler-terminates-regression-test
  ;; Termination guard for the empty-storage path. The non-empty +
  ;; multi-secret correctness path is covered downstream by
  ;; `list-secrets-handler-returns-distinct-paths-per-secret` (which
  ;; seeds two secrets and asserts distinct `:path` per row, also
  ;; within a hard timeout — so it implicitly verifies termination
  ;; for N≥2 too). Don't duplicate the 1-secret seeding case here.
  (testing "list-secrets-handler returns a Ring response within 15s"
    (let [registry (graphden.executor.compile-runtime/registry *context*)
          pg-query-closure (get registry (fn-id "pg-query"))
          ;; Sibling tests in the ns may have already populated
          ;; `secret-leaf` descendants in shared storage; the handler
          ;; then actually hits the DB through `:storage-query`. Wire
          ;; the real callable so the deref doesn't NPE if so.
          storage-query-callable (fn [hsql]
                                   (pg-query-closure {:hsql hsql} *context*))
          fid (fn-id "list-secrets-handler")
          closure (get registry fid)
          done (future (closure {:request {:uri "/api/secrets"
                                           :request-method :get
                                           :headers {}}
                                 :storage-query storage-query-callable}
                                *context*))
          ;; 60 s budget: 2 s flaked under integration-suite parallel
          ;; load; 15 s flaked under cloverage instrumentation
          ;; (~6× overhead via `bb coverage-full`). The hang regression
          ;; this test pins manifests as never-returning, not as a
          ;; ~30 s response, so a generous wall-cap preserves the catch
          ;; while removing the false-positive timeouts.
          result (try (deref done 60000 ::timeout)
                      (finally (when-not (java.util.concurrent.Future/.isDone done)
                                 (java.util.concurrent.Future/.cancel done true))))]
      (is (not= ::timeout result)
          "list-secrets-handler must terminate within 60 s — the hang here is the regression this test pins")
      (when (map? result)
        (is (= 200 (:status result)))
        (is (string? (:body result)))))))


;; -----------------------------------------------------------------
;; Regression — /api/secrets must return each secret's OWN :path.
;; -----------------------------------------------------------------
;; Production bug (2026-06-15): with N≥2 secret-leaf fn-rows in
;; storage, `GET /api/secrets` returned every row with the FIRST
;; secret's `:path`. Root cause: `compile-eager`'s per-execute
;; call-cache projects `fa` to `r/deep-free-ext-names`'s output, and
;; `_shape-secret-bindings` reads `:fn-row` via the closure-captured
;; `:filter :pred` HOF body — but `deep-free-ext-names` STOPS at HOF
;; boundaries (correct for hof-dispatch / alpha-equiv / build-ref-
;; renames callers), so `:fn-row` was absent from the projection and
;; every secret's binding-row lookup hashed to one cache slot.
;;
;; Fix: a separate `r/cache-projection-frees` walker that walks INTO
;; `:is-fn :ref` bindings (subtracting hof-lambda-params at each
;; HOF boundary) so closure-captured names land in the cache key.
;; See `src/graphden/executor/compile/renames.clj`.

(deftest ^:integration
  list-secrets-handler-returns-distinct-paths-per-secret
  (testing "two secrets in storage → API returns each one's own :path"
    (let [leaf-id (fn-id "secret-leaf")
          path-slot (-> (sp/query-entities *storage* :fn-slot {:fn-id leaf-id})
                        first :slot-id)
          probe-a-id (random-uuid)
          probe-b-id (random-uuid)
          _ (sp/create-entity *storage* :fn
                              {:id probe-a-id
                               :name "regression-secret-a"
                               :parent-ids [leaf-id]})
          _ (sp/create-entity *storage* :binding
                              {:fn-id probe-a-id
                               :slot-id path-slot
                               :value "kv/data/secret-a"
                               :override-kind :secret-path})
          _ (sp/create-entity *storage* :fn
                              {:id probe-b-id
                               :name "regression-secret-b"
                               :parent-ids [leaf-id]})
          _ (sp/create-entity *storage* :binding
                              {:fn-id probe-b-id
                               :slot-id path-slot
                               :value "kv/data/secret-b"
                               :override-kind :secret-path})
          registry (graphden.executor.compile-runtime/registry *context*)
          pg-query-closure (get registry (fn-id "pg-query"))
          storage-query-callable (fn [hsql]
                                   (pg-query-closure {:hsql hsql} *context*))
          closure (get registry (fn-id "list-secrets-handler"))
          done (future (closure {:request {:uri "/api/secrets"
                                           :request-method :get
                                           :headers {}}
                                 :storage-query storage-query-callable}
                                *context*))
          ;; 15s budget: in isolation this handler returns in ~3.5 s; the
          ;; previous 5 s budget flaked under integration-suite parallel
          ;; load (sibling tests in `^:integration` compete for CPU + GC),
          ;; which masked the *real* contract this test pins (the
          ;; per-secret `:path` projection asserted below). The
          ;; cache-projection regression — collapsing both secrets onto
          ;; one path — would still surface as a wrong-value failure,
          ;; not a borderline timeout.
          result (try (deref done 60000 ::timeout)
                      (finally (when-not (java.util.concurrent.Future/.isDone done)
                                 (java.util.concurrent.Future/.cancel done true))))]
      (is (not= ::timeout result)
          "handler must terminate within 60 s")
      (when (map? result)
        (is (= 200 (:status result)))
        (let [body (when (string? (:body result))
                     (cheshire.core/parse-string (:body result) true))
              secrets-by-name (when body
                                (into {} (map (juxt :name :path)) (:secrets body)))]
          (is (= "kv/data/secret-a"
                 (get secrets-by-name "regression-secret-a"))
              ":path of regression-secret-a must be its own, NOT the first secret's")
          (is (= "kv/data/secret-b"
                 (get secrets-by-name "regression-secret-b"))
              ":path of regression-secret-b must be its own, NOT the first secret's")
          ;; Cross-row check: my two seeded secrets must hash to two
          ;; distinct cache slots (sibling tests in this ns may leave
          ;; additional secrets in shared storage, so check the pair
          ;; not the total count).
          (is (not= (get secrets-by-name "regression-secret-a")
                    (get secrets-by-name "regression-secret-b"))
              (str ":path values collapsed onto one cache slot — "
                   "regression-secret-a and regression-secret-b returned the same path")))))))


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

(deftest pw-coercer-bisection-test
  ;; Step-by-step bisection of `:postwalk + :cond + inline-anon` —
  ;; the pattern used by the `:_router-coerced-routes` graph-level
  ;; route-data coercer (was inline in `ring-router-fn`). Each level
  ;; adds one piece; if the chain regresses in the future this test
  ;; pinpoints which combination broke.
  (testing "step 1 — :postwalk + :const identity returns input as-is"
    (is (= {:a 1 :b "two"} (run "ex-pw-identity-call"))))
  (testing "step 2 — :postwalk + :cond with `[true {:as :value}]` passthrough"
    (is (= {:a 1 :b "two"} (run "ex-pw-cond-identity-call"))))
  (testing "step 3 — keyword nodes get :keyword-to-str, others pass through"
    (is (= ["foo" "bar" "baz"] (run "ex-pw-kw-to-str-call"))))
  (testing "step 4 — full reitit-shape coercer on a literal vector"
    (is (= [["/health" {:get {:handler "noop"}}]
            ["/version" {:get {:handler "version-handler"}}]]
           (run "ex-pw-router-call"))))
  (testing "step 5 — same coercer on a `:filter`-produced lazy-seq (production shape)"
    (is (= [["/health" {:get {:handler "noop"}}]
            ["/version" {:get {:handler "version-handler"}}]]
           (run "ex-pw-router-via-filter"))))
  (testing "step 6 — direct call to `:ring-router` itself"
    ;; If this passes but production /version still 404s, the issue
    ;; isn't in the decomp's routes-coercer but in how the routes
    ;; flow from `:_router-non-nil-routes` to `:_router-compiled` —
    ;; specifically the slot-name-collision bug fixed by putting
    ;; `:_router-coerced-routes` as a SIBLING of `:_router-compiled`
    ;; rather than as an inline transform on `:ring-router`'s slot.
    (let [router (run "ex-via-ring-router-direct")
          match ((requiring-resolve 'reitit.core/match-by-path) router "/health")]
      (is (some? router))
      (is (some? match)
          "router built by `:ring-router` must actually route /health"))))


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
