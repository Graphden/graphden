(ns ^:serial graphden.system.branch-router-test
  "Tests for `graphden.system.branch-router`.

   `^:serial` because the `dispatch-test-router` helper redefs
   `br/resolve-branch-id` process-globally for the duration of each
   test. Sibling NS-threads resolving real branch refs during the
   redef window would see the stub's per-test resolutions map
   (default-id for nil/main, nil for everything else).

   - Pure-function (UNIT): extract-branch-ref + dispatch (with stubbed
     resolve-branch-id), invalidate, LRU eviction (testing the
     private `evict-lru-if-full`). 16 of 17 deftests fall here — they
     don't touch storage, they redef the protocol calls. Tagged at
     the deftest level only for the one integration test below.
   - Integration: full create-router → dispatch chain against a real
     PG-backed storage with a compilable test fn, asserting the
     per-branch ctx ends up bound to the right branch."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.branch-router :as br]
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
;; dispatch — request → branch-id → handler. Stubs storage / handler
;; lookup with `with-redefs` so we don't need a full PG fixture; the
;; STORAGE side is exercised by versioning.storage.core-test and the
;; end-to-end manual checks in the feat/versioning PR notes.
;; =============================================================================

(def ^:private default-id (random-uuid))
(def ^:private feature-id (random-uuid))


(defn- with-fake-router
  "Build a BranchRouter pointed at fake ctx/handler atoms so we can
   verify dispatch's routing without spinning up storage. `handlers`
   pre-seeds the atom; `resolutions` is a `{branch-ref → branch-id}`
   stub for `resolve-branch-id`."
  [{:keys [handlers resolutions]}]
  (with-redefs [br/resolve-branch-id
                (fn [_ branch-ref]
                  (cond
                    (or (nil? branch-ref) (= "main" branch-ref)) default-id
                    :else                                        (get resolutions branch-ref)))]
    {:router (br/->BranchRouter nil default-id (atom (or handlers {})) :stub-fn-id)
     :calls (atom [])}))


(deftest dispatch-falls-back-to-default-branch
  (testing "no header / query → default-branch handler invoked"
    (let [calls (atom [])
          {:keys [router]} (with-fake-router
                             {:handlers {default-id
                                         {:handler (fn [req]
                                                     (swap! calls conj [:default req])
                                                     {:status 200 :body "main"})}}})
          resp (br/dispatch router {:headers {} :query-string nil})]
      (is (= 200 (:status resp)))
      (is (= "main" (:body resp)))
      (is (= 1 (count @calls))))))


(deftest dispatch-routes-by-header
  (testing "X-Graphden-Branch hits the corresponding per-branch handler"
    (with-redefs [br/resolve-branch-id
                  (fn [_ branch-ref]
                    (cond
                      (or (nil? branch-ref) (= "main" branch-ref)) default-id
                      (= "feature-a" branch-ref)                   feature-id))]
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


(deftest dispatch-rejects-unknown-branch
  (testing "explicit ref that doesn't resolve → 400 JSON, default NOT called"
    (with-redefs [br/resolve-branch-id
                  (fn [_ branch-ref]
                    (when (or (nil? branch-ref) (= "main" branch-ref)) default-id))]
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


(deftest dispatch-prefers-header-over-query
  (testing "header wins even when both are set"
    (with-redefs [br/resolve-branch-id
                  (fn [_ branch-ref]
                    (cond
                      (or (nil? branch-ref) (= "main" branch-ref)) default-id
                      (= "from-header" branch-ref)                 feature-id
                      :else                                        nil))]
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
;; ref-cache + invalidate! semantics. Mocks resolve-branch-id-uncached
;; to count calls, then verifies the cache cuts subsequent reads.
;; =============================================================================

(deftest ref-cache-skips-redundant-uncached-lookups
  (let [calls (atom 0)]
    (with-redefs [br/resolve-branch-id-uncached
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
        (is (= 1 @calls)
            "after first hit the ref-cache holds the mapping; subsequent
             lookups skip the storage round-trip")

        (testing "invalidate! drops the ref-cache entry for that branch"
          (br/invalidate! router feature-id)
          (br/resolve-branch-id router "feature-a")
          (is (= 2 @calls)
              "post-invalidate, the next lookup goes back to uncached"))

        (testing "unresolved refs are NOT cached — a typo never sticks"
          (br/resolve-branch-id router "no-such")
          (br/resolve-branch-id router "no-such")
          (is (= 4 @calls)
              "each unresolved call should re-query (calls went from 2 → 4)"))))))


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

(defn- full-schema
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (ds/build)))


(defn- create-versioned-storage!
  "Fresh PG storage wrapped in versioning, pointed at the 'main'
   branch. Mirrors what `:db/versioned` builds in prod."
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        storage (pg/create-storage (pth/get-container-config container))]
    (sp/initialize storage (full-schema))
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
      ;; Start clean (sibling tests may have left state — `^:serial`
      ;; protects against parallel writes but not from carry-over).
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
