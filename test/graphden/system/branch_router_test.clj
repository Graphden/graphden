(ns graphden.system.branch-router-test
  "Tests for `graphden.system.branch-router`.

   Parallel-safe (un-pinned 2026-08-04): the resolution stubs that
   used to be process-global `with-redefs` of `br/resolve-branch-id`
   / `br/resolve-branch-id-uncached` now go through the per-thread
   seams `br/*resolve-branch-id-override*` (dispatch suite) and
   `br/*resolve-uncached-override*` (ref-cache / TOCTOU suite) via
   `binding` — sibling NS-threads resolving real branch refs never
   see this NS's scripted resolutions.

   - Pure-function (UNIT): extract-branch-ref + dispatch (with stubbed
     resolve-branch-id), invalidate, LRU eviction (testing the
     private `evict-lru-if-full`). 16 of 17 deftests fall here — they
     don't touch storage, they stub resolution via the seams. Tagged
     at the deftest level only for the one integration test below.
   - Integration: full create-router → dispatch chain against a real
     PG-backed storage with a compilable test fn, asserting the
     per-branch ctx ends up bound to the right branch."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.schemas :as schemas]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture))


(deftest extract-branch-ref-header-wins
  (testing "X-Graphden-Branch header wins when present"
    (let [req {:headers {"x-graphden-branch" "feature-a"}
               :query-string "branch=feature-b"}]
      (is (= "feature-a" (br/extract-branch-ref req))))))


(deftest extract-branch-ref-query-fallback
  (testing "?branch=name when header is absent"
    (let [req {:headers {}
               :query-string "branch=feature-x"}]
      (is (= "feature-x" (br/extract-branch-ref req)))))

  (testing "URL-decoded query value"
    (let [req {:headers {}
               :query-string "branch=feature%2Fa"}]
      (is (= "feature/a" (br/extract-branch-ref req)))))

  (testing "query param is the second pair"
    (let [req {:headers {}
               :query-string "foo=1&branch=feat&other=2"}]
      (is (= "feat" (br/extract-branch-ref req))))))


(deftest extract-branch-ref-nil-default
  (testing "neither source set → nil (router falls back to default)"
    (is (nil? (br/extract-branch-ref {:headers {} :query-string ""})))
    (is (nil? (br/extract-branch-ref {:headers {} :query-string nil}))))

  (testing "blank header value → nil"
    (is (nil? (br/extract-branch-ref
                {:headers {"x-graphden-branch" "   "} :query-string nil}))))

  (testing "blank query value → nil"
    (is (nil? (br/extract-branch-ref
                {:headers {} :query-string "branch=  "})))))


(deftest extract-branch-ref-trims-whitespace
  (testing "leading / trailing whitespace stripped"
    (let [req {:headers {"x-graphden-branch" "  feat  "}}]
      (is (= "feat" (br/extract-branch-ref req))))))


;; =============================================================================
;; dispatch — request → branch-id → handler. Stubs resolution via the
;; per-thread `br/*resolve-branch-id-override*` seam so we don't need a
;; full PG fixture; the STORAGE side is exercised by
;; versioning.storage.core-test and the end-to-end manual checks in the
;; feat/versioning PR notes.
;; =============================================================================

(def ^:private default-id (random-uuid))
(def ^:private feature-id (random-uuid))


(defn- stub-resolutions
  "Build a `*resolve-branch-id-override*` fn: nil/\"main\" → default-id,
   anything else looked up in the `{branch-ref → branch-id}` map."
  [resolutions]
  (fn [_ branch-ref]
    (cond
      (or (nil? branch-ref) (= "main" branch-ref)) default-id
      :else                                        (get resolutions branch-ref))))


(defn- fake-router
  "Build a BranchRouter pointed at fake ctx/handler atoms so we can
   verify dispatch's routing without spinning up storage. `handlers`
   pre-seeds the atom."
  [handlers]
  (br/->BranchRouter nil default-id (atom (or handlers {})) :stub-fn-id))


(deftest dispatch-falls-back-to-default-branch
  (testing "no header / query → default-branch handler invoked"
    (binding [br/*resolve-branch-id-override* (stub-resolutions {})]
      (let [calls (atom [])
            router (fake-router {default-id
                                 {:handler (fn [req]
                                             (swap! calls conj [:default req])
                                             {:status 200 :body "main"})}})
            resp (br/dispatch router {:headers {} :query-string nil})]
        (is (= 200 (:status resp)))
        (is (= "main" (:body resp)))
        (is (= 1 (count @calls)))))))


(deftest dispatch-livez-is-a-registry-independent-liveness-probe
  (testing "/livez short-circuits to a static 200 BEFORE any handler / registry
            access — a pod mid-full-recompile (which stalls /health) still
            answers liveness, so the probe can't kill a busy-but-alive pod"
    (let [handler-fired (atom false)
          ;; Every branch handler THROWS: if dispatch reached the
          ;; branch-resolution / registry chain, this test would blow up.
          router (fake-router {default-id
                               {:handler (fn [_]
                                           (reset! handler-fired true)
                                           (throw (ex-info "registry gate reached" {})))}})
          resp (br/dispatch router {:uri "/livez" :headers {} :query-string nil})]
      (is (= 200 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"])))
      (is (re-find #"\"status\":\"alive\"" (:body resp)))
      (is (false? @handler-fired)
          "/livez must not reach the branch handler / compiled registry"))
    (testing "a non-/livez path still runs the normal dispatch chain"
      (binding [br/*resolve-branch-id-override* (stub-resolutions {})]
        (let [router (fake-router {default-id
                                   {:handler (fn [_] {:status 200 :body "served"})}})
              resp (br/dispatch router {:uri "/api/graph/entities"
                                        :headers {} :query-string nil})]
          (is (= "served" (:body resp))
              "ordinary routes go through branch resolution as before"))))))


(deftest dispatch-routes-by-header
  (testing "X-Graphden-Branch hits the corresponding per-branch handler"
    (binding [br/*resolve-branch-id-override*
              (stub-resolutions {"feature-a" feature-id})]
      (let [calls (atom [])
            router (br/->BranchRouter nil default-id
                                      (atom {default-id
                                             {:handler (fn [_]
                                                         (swap! calls conj :default)
                                                         {:status 200 :body "main"})}
                                             feature-id
                                             {:handler (fn [_]
                                                         (swap! calls conj :feature)
                                                         {:status 200 :body "feat"})}})
                                      :stub-fn-id)
            resp (br/dispatch router {:headers {"x-graphden-branch" "feature-a"}
                                      :query-string nil})]
        (is (= 200 (:status resp)))
        (is (= "feat" (:body resp)))
        (is (= [:feature] @calls)
            "default handler must NOT fire when feature is selected")))))


(deftest dispatch-consults-fleet-command-first
  (testing "a fleet-command that returns a response short-circuits before the
            app-router AND before branch resolution (docs/FLEET_RFC.md §6.3)"
    (let [branch-fired (atom false)
          base-ctx {:fleet-command (fn [_ctx _req] {:status 218 :body "cell-cmd"})
                    :app-router (fn [_ _] {:status 200 :body "app"})}
          router (br/->BranchRouter
                   base-ctx default-id
                   (atom {default-id {:handler (fn [_]
                                                 (reset! branch-fired true)
                                                 {:status 200 :body "editor"})}})
                   :stub)
          resp (br/dispatch router {:headers {} :query-string nil})]
      (is (= 218 (:status resp)) "the fleet-command response wins")
      (is (= "cell-cmd" (:body resp)))
      (is (false? @branch-fired) "neither app-router nor the branch handler ran"))))


(deftest dispatch-falls-through-when-fleet-command-nil
  (testing "fleet-command nil → app-router is consulted next"
    (let [base-ctx {:fleet-command (fn [_ _] nil)
                    :app-router (fn [_ _] {:status 200 :body "app"})}
          router (br/->BranchRouter base-ctx default-id
                                    (atom {default-id {:handler (constantly {:status 500})}})
                                    :stub)
          resp (br/dispatch router {:headers {} :query-string nil})]
      (is (= "app" (:body resp)) "a nil fleet-command result does not swallow the request"))))


(deftest dispatch-rejects-unknown-branch
  (testing "explicit ref that doesn't resolve → 400 JSON, default NOT called"
    (binding [br/*resolve-branch-id-override* (stub-resolutions {})]
      (let [calls (atom [])
            router (br/->BranchRouter nil default-id
                                      (atom {default-id
                                             {:handler (fn [_]
                                                         (swap! calls conj :default)
                                                         {:status 200 :body "main"})}})
                                      :stub-fn-id)
            resp (br/dispatch router {:headers {"x-graphden-branch" "no-such-branch"}
                                      :query-string nil})]
        (is (= 400 (:status resp)))
        (is (re-find #"Unknown branch: no-such-branch" (:body resp)))
        (is (empty? @calls)
            "the dispatcher must short-circuit before any handler fires")))))


(deftest dispatch-unknown-branch-body-is-valid-json
  ;; The unknown-branch 400 reflects the user-controlled branch-ref; a raw
  ;; string-concat let a `"` inject arbitrary keys into the response
  ;; envelope. It must now be a properly JSON-encoded string.
  (testing "a branch-ref containing quotes stays contained in the :error string"
    (binding [br/*resolve-branch-id-override* (fn [_ _] nil)]
      (let [router (br/->BranchRouter nil default-id (atom {}) :stub-fn-id)
            evil "\",\"admin\":true,\"x\":\""
            resp (br/dispatch router {:headers {"x-graphden-branch" evil}
                                      :query-string nil})
            parsed (json/parse-string (:body resp) true)]
        (is (= 400 (:status resp)))
        (is (false? (:ok parsed)))
        (is (nil? (:admin parsed))
            "the injected key must NOT surface at the top level")
        (is (re-find #"Unknown branch:" (:error parsed))
            "the raw ref stays inside the escaped :error string")))))


(deftest dispatch-prefers-header-over-query
  (testing "header wins even when both are set"
    (binding [br/*resolve-branch-id-override*
              (stub-resolutions {"from-header" feature-id})]
      (let [calls (atom [])
            router (br/->BranchRouter nil default-id
                                      (atom {default-id
                                             {:handler (fn [_]
                                                         (swap! calls conj :default)
                                                         {})}
                                             feature-id
                                             {:handler (fn [_]
                                                         (swap! calls conj :feature)
                                                         {:status 200 :body "from-header"})}})
                                      :stub-fn-id)
            resp (br/dispatch router {:headers {"x-graphden-branch" "from-header"}
                                      :query-string "branch=from-query"})]
        (is (= "from-header" (:body resp)))
        (is (= [:feature] @calls))))))


(deftest invalidate-drops-cached-entry
  (testing "invalidate! removes a per-branch entry; invalidate-all! drops everything"
    (let [router (br/->BranchRouter nil default-id
                                    (atom {default-id  {:handler :default-h}
                                           feature-id  {:handler :feature-h}})
                                    :stub-fn-id)
          handlers (:handlers router)]
      (br/invalidate! router feature-id)
      (is (= #{default-id} (set (keys @handlers))))

      (br/invalidate-all! router)
      (is (empty? @handlers)))))


;; =============================================================================
;; LRU eviction — pure-function tests of `evict-lru-if-full`. Tests the
;; bounded-cache policy without needing the full router. The helper is
;; private so we reach it via #'.
;; =============================================================================

(defn- entry
  [last-used]
  {:handler :stub :last-used last-used})


(deftest lru-no-eviction-below-cap
  (let [m {:a (entry 1) :b (entry 2)}
        out (#'br/evict-lru-if-full m 4 :default :c)]
    (is (= m out)
        "size 2, cap 4 → nothing evicted")))


(deftest lru-evicts-oldest-non-default
  (let [m {:default (entry 0) :a (entry 5) :b (entry 1) :c (entry 3)}
        out (#'br/evict-lru-if-full m 4 :default :new)]
    (is (= 3 (count out)))
    (is (contains? out :default) "default-branch entry is pinned")
    (is (not (contains? out :b)) "oldest non-default :b evicted (last-used 1)")
    (is (contains? out :a) ":a kept (last-used 5)")
    (is (contains? out :c) ":c kept (last-used 3)")))


(deftest lru-skips-when-replacing-existing-entry
  (let [m {:default (entry 0) :a (entry 5) :b (entry 1)}
        ;; Adding :a when :a already exists is a replace, not a new
        ;; entry — no eviction.
        out (#'br/evict-lru-if-full m 3 :default :a)]
    (is (= 3 (count out)))
    (is (contains? out :b)
        "replacement doesn't fill any new slot, so nothing is evicted")))


(deftest lru-handles-missing-last-used
  (let [m {:default (entry 0) :a {:handler :stub} :b (entry 5)}
        out (#'br/evict-lru-if-full m 3 :default :new)]
    (is (not (contains? out :a))
        "entry without :last-used sorts as 0 → evicted first")))


(deftest lru-no-evictable-entries-keeps-everything
  (let [m {:default (entry 0)}
        out (#'br/evict-lru-if-full m 1 :default :new)]
    (is (= m out)
        "only the pinned default entry exists → no eligible eviction target")))


;; =============================================================================
;; ref-cache + invalidate! semantics. Stubs the UNCACHED read via the
;; per-thread `br/*resolve-uncached-override*` seam to count calls,
;; then verifies the cache cuts subsequent reads.
;; =============================================================================

(deftest ref-cache-skips-redundant-uncached-lookups
  (let [calls (atom 0)]
    (binding [br/*resolve-uncached-override*
              (fn [_ branch-ref]
                (swap! calls inc)
                (case branch-ref
                  "main" default-id
                  "feature-a" feature-id
                  nil))]
      (let [router (-> (br/->BranchRouter nil default-id
                                          (atom {default-id {:handler :h}})
                                          :stub)
                       (assoc :ref-cache (atom {})))]
        (br/resolve-branch-id router "feature-a")
        (br/resolve-branch-id router "feature-a")
        (br/resolve-branch-id router "feature-a")
        (is (= 2 @calls)
            "a cache MISS costs two reads (resolve + TOCTOU recheck);
             subsequent lookups hit the cache and skip storage entirely")

        (testing "invalidate! drops the ref-cache entry for that branch"
          (br/invalidate! router feature-id)
          (br/resolve-branch-id router "feature-a")
          (is (= 4 @calls)
              "post-invalidate, the next lookup goes back to uncached
               (again resolve + recheck)"))

        (testing "unresolved refs are NOT cached — a typo never sticks"
          (br/resolve-branch-id router "no-such")
          (br/resolve-branch-id router "no-such")
          (is (= 6 @calls)
              "each unresolved call re-queries once (no recheck on a
               miss that resolves to nothing) — calls went from 4 → 6"))))))


(deftest ref-cache-toctou-recheck-drops-dead-entry
  ;; The race docs/VERSIONING.md § Known gaps used to describe: a branch
  ;; deleted between resolve's uncached DB read and its cache write left
  ;; a dead id cached (the delete's value-sweep had already run). The
  ;; post-assoc recheck closes it: the deletion is seen, the entry is
  ;; dropped, and the caller gets nil instead of the dead id.
  (let [phase (atom :alive)]
    (binding [br/*resolve-uncached-override*
              (fn [_ _]
                (when (= :alive @phase)
                  (reset! phase :deleted)
                  feature-id))]
      (let [router (-> (br/->BranchRouter nil default-id (atom {}) :stub)
                       (assoc :ref-cache (atom {})))]
        (is (nil? (br/resolve-branch-id router "doomed"))
            "the recheck saw the concurrent delete → nil, not the dead id")
        (is (empty? @(:ref-cache router))
            "the dead id must not be retained in the ref-cache")))))


(deftest ref-cache-toctou-recheck-follows-recreate
  ;; delete + re-create with the same name racing the first resolution:
  ;; the recheck returns the NEW id — the stale first read is neither
  ;; cached nor returned.
  (let [new-id (random-uuid)
        first-read? (atom true)]
    (binding [br/*resolve-uncached-override*
              (fn [_ _]
                (if @first-read?
                  (do (reset! first-read? false) feature-id)
                  new-id))]
      (let [router (-> (br/->BranchRouter nil default-id (atom {}) :stub)
                       (assoc :ref-cache (atom {})))]
        (is (= new-id (br/resolve-branch-id router "reborn"))
            "the recheck's id wins over the pre-delete read")
        (is (empty? @(:ref-cache router))
            "the ambiguous first id is dropped; the next call re-resolves
             and caches the stable id")))))


(deftest invalidate-all-clears-ref-cache
  (let [router (-> (br/->BranchRouter nil default-id (atom {}) :stub)
                   (assoc :ref-cache (atom {"foo" feature-id "bar" feature-id})))]
    (br/invalidate-all! router)
    (is (empty? @(:ref-cache router)))))


;; =============================================================================
;; End-to-end: full create-router → dispatch chain against a real
;; PG-backed storage. The header → per-branch ctx → storage-on-branch
;; integration is the load-bearing contract the rest of the wire
;; assumes — pure-function tests can't exercise it because they stub
;; out the storage path. Test pattern mirrors the heavy fixture from
;; `graphden.crud.fn-execution-test`.
;; =============================================================================

(defn- create-versioned-storage!
  "Fresh PG storage wrapped in versioning, pointed at the 'main'
   branch. Mirrors what `:db/versioned` builds in prod."
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        storage (pg/create-storage (pth/get-container-config container))]
    (sp/initialize storage (schemas/full-schema))
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning storage "main")))


;; =============================================================================
;; Active-router singleton — round-trip set! / current / clear!. Pure
;; atom op, no router state required.
;; =============================================================================

(deftest active-router-singleton-roundtrip-test
  (testing "set-active-router! → current-router → clear-active-router!"
    (let [fake-router (br/->BranchRouter nil default-id (atom {}) :stub-fn-id)]
      ;; Start clean (sibling tests in THIS NS may have left state —
      ;; the parallel plugin's `*active-router-override*` binding
      ;; isolates us from other NSes but not from same-NS carry-over).
      (br/clear-active-router!)
      (is (nil? (br/current-router)) "clean precondition")

      (br/set-active-router! fake-router)
      (is (identical? fake-router (br/current-router))
          "current returns whatever was last set")

      (br/clear-active-router!)
      (is (nil? (br/current-router))
          "after clear, current returns nil"))))


(deftest ^:integration dispatch-routes-to-per-branch-ctx-end-to-end-test
  ;; Closes the gap noted in docs/VERSIONING.md § Known gaps. The
  ;; full chain (header → branch resolution → per-branch ctx build →
  ;; per-branch storage) is exercised against a real PG. The fake
  ;; handler installed on the test branch's cached entry records
  ;; which storage instance it was invoked against — proving the
  ;; dispatch flowed through to a ctx whose VersionedStorage is
  ;; pointed at the right branch.
  (let [storage (create-versioned-storage!)
        ;; Register a no-op base-fn so `:exec/compiled-registry`
        ;; (via rebuild!) has something to compile when build-and-
        ;; cache! fires.
        _ (exec/register-base-fn! :noop-handler (fn [_args _ctx] {:status 200}))
        ;; Create a placeholder fn-row that `create-router` will
        ;; look up by name. Doesn't need to actually be runnable —
        ;; we replace the cached handler with our own atom-capturer.
        noop-fn (sp/create-entity storage :fn {:name "noop-handler"
                                               :parent-ids []})
        base-ctx (ctx/create-context {:storage storage
                                      :base-fns (exec/get-default-registry)})
        feature (vs/create-branch! storage "e2e-feature")
        captured (atom [])]
    (try
      (let [router (br/create-router base-ctx "noop-handler")]
        (testing "fixture sanity: default-branch entry seeded, default ctx points at main"
          (let [default-bid (:default-branch-id router)
                default-entry (get @(:handlers router) default-bid)]
            (is (some? default-entry))
            (is (= default-bid (vs/current-branch-id
                                 (:storage (:ctx default-entry)))))))

        (testing "dispatch on a request without header → default branch handler"
          ;; Swap the default handler with a capturer so we can verify
          ;; it fires (without actually running the compiled fn-graph).
          (swap! (:handlers router) assoc-in
                 [(:default-branch-id router) :handler]
                 (fn [_]
                   (swap! captured conj :default)
                   {:status 200 :body "main"}))
          (br/dispatch router {:headers {} :query-string nil})
          (is (= [:default] @captured)))

        (testing "dispatch with X-Graphden-Branch header → per-branch ctx built lazily"
          (reset! captured [])
          ;; Pre-seed the feature ctx's handler with a capturer so
          ;; rebuild! doesn't have to compile the (placeholder) fn-
          ;; graph. The router's `build-and-cache!` would normally
          ;; build the closure that calls the compiled handler; we
          ;; bypass that one step by swapping the cached entry after
          ;; the ctx is constructed.
          (let [feature-ctx (br/ctx-for router (:id feature))]
            (testing "the lazy-built ctx is bound to the feature branch"
              (is (= (:id feature)
                     (vs/current-branch-id (:storage feature-ctx)))))
            (testing "ctx entry now exists in the handlers atom"
              (is (contains? @(:handlers router) (:id feature)))))

          (swap! (:handlers router) assoc-in
                 [(:id feature) :handler]
                 (fn [req]
                   (swap! captured conj [:feature
                                         (get-in req [:headers "x-graphden-branch"])])
                   {:status 200 :body "feat"}))
          (let [resp (br/dispatch router {:headers {"x-graphden-branch" "e2e-feature"}
                                          :query-string nil})]
            (is (= 200 (:status resp)))
            (is (= "feat" (:body resp)))
            (is (= [[:feature "e2e-feature"]] @captured))))

        (testing "writes through the per-branch ctx land on the right branch only"
          (let [feature-ctx (br/ctx-for router (:id feature))
                main-ctx (br/ctx-for router (:default-branch-id router))
                ;; Create a fn through the per-branch ctx — should
                ;; show up on feature but NOT on main's resolved view.
                created (sp/create-entity (:storage feature-ctx) :fn
                                          {:name "e2e-feature-only"
                                           :parent-ids []})
                feat-fns (set (map :id (sp/query-entities (:storage feature-ctx)
                                                          :fn {})))
                main-fns (set (map :id (sp/query-entities (:storage main-ctx)
                                                          :fn {})))]
            (is (contains? feat-fns (:id created))
                "the new fn must surface on the feature branch")
            (is (not (contains? main-fns (:id created)))
                "main must NOT see the branch-local creation — the per-branch
                 storage instance kept the write isolated"))))
      (finally (sp/close storage)))
    (testing "test cleanup: ensure noop-fn was created (sanity for the fixture)"
      (is (some? noop-fn)))))


(declare branch-delta-build-executes-overrides-impl!)


(deftest ^:integration branch-delta-build-executes-overrides-test
  ;; Isolated registry + rich-types: this test registers `:add` and compiles a
  ;; composed fn, which would otherwise leave that fn's rich-type in the process-
  ;; global registry and corrupt a sibling NS (`branches-graph-test`) that compiles
  ;; its OWN `:add` — surfaced as a resolve-arg NPE only under the parallel gate.
  (exec/with-clean-registry
    (fn []
      (exec/with-isolated-rich-types
        (fn []
          (branch-delta-build-executes-overrides-impl!))))))


(defn- branch-delta-build-executes-overrides-impl!
  []
  ;; A branch that DIVERGES from its base (own version rows) used to rebuild the
  ;; whole compiled graph on its first ctx build — ~57s, executor-blocking, hit
  ;; whenever the branch's ctx had been evicted from the LRU. The build now
  ;; delta-compiles: it reuses the base's closures for every unchanged fn and
  ;; recompiles ONLY the fns this branch overrides.
  ;;
  ;; The risk of a delta is CORRECTNESS: the branch must run ITS overridden
  ;; definition, not the base closure it copied. So bind the composed `add` to
  ;; 1+2 on main, override it to 10+2 on a feature branch, force the branch ctx to
  ;; build through the delta path, and assert each side computes its own value.
  (let [storage (create-versioned-storage!)
        _ (exec/register-base-fn! :noop-handler (fn [_args _ctx] {:status 200}))
        _ (sp/create-entity storage :fn {:name "noop-handler" :parent-ids []})
        {:keys [composed-fn slot-a slot-b]} (setup/setup-add-function! storage)
        cid (:id composed-fn)
        a-binding (setup/bind-value! storage cid (:id slot-a) 1)
        _ (setup/bind-value! storage cid (:id slot-b) 2)
        base-ctx (ctx/create-context {:storage storage
                                      :base-fns (exec/get-default-registry)})]
    (try
      (testing "main computes 1 + 2 = 3 (and warms the base registry)"
        (is (= 3 (exec/execute base-ctx cid {}))))
      (let [feat (vs/create-branch! storage "delta-feat")
            branch-storage (vs/->VersionedStorage (vs/unwrap storage) (:id feat))
            ;; Override slot-a to 10 on the feature branch — a binding-version row
            ;; on `feat`, which makes the branch diverge (own content).
            _ (sp/update-entity branch-storage :binding (:id a-binding)
                                {:value 10 :value-present true})
            router (br/create-router base-ctx "noop-handler")
            feat-ctx (br/ctx-for router (:id feat))]
        (testing "the branch ctx delta-built (base registry was warm, branch had own fns)"
          (is (contains? @(:handlers router) (:id feat))))
        (testing "the branch runs ITS override: 10 + 2 = 12"
          (is (= 12 (exec/execute feat-ctx cid {}))))
        (testing "and main still computes 3 — the delta did not corrupt the base"
          (is (= 3 (exec/execute base-ctx cid {})))))
      (finally (sp/close storage)))))


;; =============================================================================
;; Ctx-build diagnostics recompute (error-tolerance)
;; =============================================================================

(deftest ^:integration ctx-build-recheck-repopulates-user-diagnostics-test
  ;; Simulates the post-restart state: the derived diagnostics store is
  ;; EMPTY while an editor-authored fn (random id) is broken in storage.
  ;; The ctx-build recompute must re-record it; a package-derived
  ;; sibling (deterministic uuid-v5(ns, name) id) must be SKIPPED —
  ;; the boot sync sweep owns those. Runs the private worker
  ;; synchronously (the production hook fires it on a future with
  ;; conveyed bindings, so the store semantics are identical).
  (exec/with-isolated-rich-types
    (fn []
      (binding [diag/*diagnostics-override* (atom {})]
        (let [vstorage (setup/create-versioned-test-storage)]
          (try
            (let [branch-id (vs/current-branch-id vstorage)
                  base (setup/create-base-fn! vstorage "rchk-base" :int)
                  slot (setup/create-slot! vstorage "n" :int)
                  _ (setup/attach-slot! vstorage (:id base) (:id slot) 0)
                  _ (registry/record-rich-types-raw!
                      :rchk-base {:return :int :args {:n :int} :effects #{}})
                  ;; EDITOR-authored: random id ≠ deterministic derivation.
                  broken (sp/create-entity vstorage :fn
                                           {:id (random-uuid)
                                            :name "rchk-user-broken"
                                            :parent-ids [(:id base)]})
                  _ (setup/bind-value! vstorage (:id broken) (:id slot) "not-an-int")
                  ;; PACKAGE-authored sibling: id IS uuid-v5(ns-path, name),
                  ;; equally broken — out of scope for the recompute.
                  ns-row (sp/create-entity vstorage :ns {:name "pkgroot"})
                  det-id (records/fn-id "pkgroot" :rchk-pkg-broken)
                  _ (sp/create-entity vstorage :fn
                                      {:id det-id
                                       :name "rchk-pkg-broken"
                                       :namespace-id (:id ns-row)
                                       :parent-ids [(:id base)]})
                  _ (setup/bind-value! vstorage det-id (:id slot) "also-bad")]
              (is (nil? (diag/errors-for-fn branch-id (:id broken)))
                  "post-restart baseline: nothing recorded")
              (#'br/recheck-user-fns! {:storage vstorage} branch-id)
              (is (seq (diag/errors-for-fn branch-id (:id broken)))
                  "editor-authored broken fn re-recorded by the ctx-build recheck")
              (is (nil? (diag/errors-for-fn branch-id det-id))
                  "package-derived id skipped — the boot sweep owns those"))
            (finally (sp/close (vs/unwrap vstorage)))))))))
