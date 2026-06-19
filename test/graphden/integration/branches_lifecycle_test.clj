(ns ^:integration graphden.integration.branches-lifecycle-test
  "End-to-end coverage for the per-branch CRUD lifecycle through the
   real Ring handler chain — create branch → write a fn on it → diff
   vs main → merge → verify the fn now resolves on main.

   Why this test exists: `branches_graph_test.clj` (41 deftests) covers
   every per-handler shape (create / diff / merge / delete /
   list-versions) through `setup/via-graph`, but `via-graph` calls the
   handler fn directly — it doesn't go through `br/dispatch`, so the
   `:branch-routing-wrap` middleware (which honours `X-Graphden-
   Branch` to pick the per-branch ExecutionContext) is BYPASSED. A
   regression of that wrap (e.g. accidental promotion off
   `:branch-routing-wrap` or a wrap-order shuffle that loses the
   header read) would let every branch-isolated write silently land
   on `main` — every existing branch test would still pass.

   This test closes that gap by dispatching all writes via
   `br/dispatch` with explicit `X-Graphden-Branch` headers — same
   closure http-kit invokes for real /api requests."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as rt]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.core :as sys]))


(def ^:dynamic *router* nil)


(def ^:private test-auth-token "branches-lifecycle-token-abc")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage)
             _ (sys/bootstrap-from-packages! storage ["core" "web" "app"]
                                             {:skip-type-check? false})
             _ (exec/register-base-fn!
                 :env
                 (fn [args _ctx]
                   (let [k (rt/resolve-arg args :name)]
                     (if (= k "AUTH_TOKEN") test-auth-token (System/getenv k)))))
             ctx (exec/create-context {:storage storage})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (try
           (binding [*router* router]
             (t))
           (finally (sp/close storage)))))))


(defn- auth-headers
  ([] (auth-headers nil))
  ([branch-name]
   (cond-> {"authorization" (str "Bearer " test-auth-token)}
     branch-name (assoc "x-graphden-branch" branch-name))))


(defn- dispatch
  [{:keys [method path branch body content-type query]
    :or {content-type "application/json"}}]
  (br/dispatch
    *router*
    {:request-method method
     :uri path
     :headers (cond-> (auth-headers branch)
                body (assoc "content-type" content-type))
     :query-string query
     :body (when body
             (if (string? body) body (json/generate-string body)))}))


(defn- parse-json
  [resp]
  (let [b (:body resp)]
    (when (string? b) (json/parse-string b true))))


(defn- list-fns
  "Pull every visible `:fn` row on `branch-name` through the
   `/api/graph/entities` projection that the editor uses. Returns the
   parsed `:fns` vector (or empty)."
  [branch-name]
  (let [resp (dispatch {:method :get :path "/api/graph/entities" :branch branch-name})]
    (when (= 200 (:status resp))
      (-> resp parse-json :fns))))


(defn- fn-by-name
  [branch-name fn-name]
  (some (fn [f] (when (= fn-name (:name f)) f)) (list-fns branch-name)))


(deftest branches-create-switch-write-merge-roundtrip-test
  (testing "create feat → fn on feat invisible on main → merge → fn visible on main"
    ;; Pick names + bodies the test owns end-to-end so concurrent
    ;; sibling deftests can't smear state. The probe fn is parented
    ;; to `:identity` (every package ships it; it's the cheapest base
    ;; to inherit from).
    (let [run-id    (str "-" (System/currentTimeMillis))
          feat-name (str "feat-roundtrip" run-id)
          fn-name   (str "branch-probe" run-id)
          ;; --- Phase 1: feat branch off main -----------------------
          create-resp (dispatch {:method :post
                                 :path "/api/branches"
                                 :body {:name feat-name
                                        :base-branch-id "main"}})]
      (is (= 200 (:status create-resp))
          (str "POST /api/branches created the feat branch; got status="
               (:status create-resp) " body=" (:body create-resp)))
      (let [feat-row (-> create-resp parse-json :branch)]
        (is (= feat-name (:name feat-row))
            "create response carries the requested branch name")
        ;; --- Phase 2: write a fn on feat -----------------------
        ;; The `:fn` row CRUD goes through `/api/entities/fn` (form-
        ;; encoded body). VersionedStorage routes the write to the
        ;; ExecutionContext picked by `X-Graphden-Branch: feat`.
        (let [identity-fn (fn-by-name nil "identity")
              _ (is (some? identity-fn)
                    ":identity baseline visible on main (cross-branch parent ref)")
              write-resp (dispatch {:method :post
                                    :path "/api/entities/fn"
                                    :branch feat-name
                                    :content-type "application/x-www-form-urlencoded"
                                    :body (str "name=" fn-name
                                               "&parent-ids=" (:id identity-fn))})]
          (is (= 200 (:status write-resp))
              (str "POST /api/entities/fn on feat returned 200; got status="
                   (:status write-resp) " body=" (:body write-resp))))
        ;; --- Phase 3: visibility split -------------------------
        ;; The fn must show up under `X-Graphden-Branch: feat` AND
        ;; must NOT show up on main (otherwise the per-branch
        ;; ExecutionContext routing collapsed and every "isolated"
        ;; branch write secretly hit main).
        (is (some? (fn-by-name feat-name fn-name))
            "probe fn visible on feat branch via X-Graphden-Branch header")
        (is (nil? (fn-by-name nil fn-name))
            "probe fn NOT visible on main (branch isolation honoured)")
        ;; --- Phase 4: diff main vs feat shows the new fn -------
        (let [diff-resp (dispatch {:method :get
                                   :path "/api/branches/main/diff"
                                   :query (str "against=" feat-name)})
              diff-body (parse-json diff-resp)]
          (is (= 200 (:status diff-resp))
              (str "GET /api/branches/main/diff?against=feat returned 200; got status="
                   (:status diff-resp)))
          (is (some (fn [d]
                      (and (= "fn" (:entity-name d))
                           (= "added-in-source" (:change d))))
                    (:diffs diff-body))
              (str "diff includes an added-in-source :fn entry "
                   "(the feat-side probe); diffs=" (pr-str (take 5 (:diffs diff-body))))))
        ;; --- Phase 5: merge feat → main ------------------------
        (let [merge-resp (dispatch {:method :post
                                    :path "/api/branches/main/merge"
                                    :body {:source feat-name}})
              merge-body (parse-json merge-resp)]
          (is (= 200 (:status merge-resp))
              (str "POST /api/branches/main/merge returned 200; got status="
                   (:status merge-resp) " body=" (:body merge-resp)))
          (is (true? (:ok merge-body))
              (str "merge response envelope reports :ok true (no conflict / no rejection); "
                   "body=" (pr-str merge-body)))
          (is (true? (:ok merge-body))
              (str "merge body :ok true; body=" (pr-str merge-body)))
          ;; The merge response carries the new `:branch-merge` row's
          ;; id + source/target branch UUIDs — assert the shape so a
          ;; regression of the merge endpoint's response envelope
          ;; (or a half-finished merge that returns 200 without
          ;; creating the record) trips here.
          (let [m (:merge merge-body)]
            (is (string? (:id m))
                "merge envelope carries :merge.id (the new branch-merge row)")
            (is (= (:id feat-row) (:source-branch-id m))
                "source-branch-id matches the feat branch we created")
            (is (string? (:target-branch-id m))
                "target-branch-id present (main UUID resolved server-side)")
            (is (string? (:created-at m))
                "created-at timestamp present (ISO string)")))
        ;; --- Phase 6: fn STILL visible on feat after merge -----
        ;; Cross-check: merge is a DECLARATIVE branch-merge record
        ;; (`vs/merge-branch!`'s docstring — "no version records are
        ;; copied"), so feat's view must remain intact. Closes a
        ;; would-be regression if merge ever started destructively
        ;; rewriting source's versions onto target.
        (is (some? (fn-by-name feat-name fn-name))
            "after merge, probe fn still visible on feat (declarative-merge invariant)")
        ;; --- Phase 7: fn NOW visible on main after merge ------
        ;; The merge endpoint chains `:invalidate-graph-cache` ahead
        ;; of the response envelope (`:_merge-apply-do-invalidate`
        ;; in `app/branches/fns.edn`), so the next
        ;; `/api/graph/entities` read on target sees the merged-in
        ;; rows immediately. Regression if that chain ever drops:
        ;; this assertion fires before any cache-warming side-effect
        ;; bails out the editor's view.
        (is (some? (fn-by-name nil fn-name))
            "after merge, probe fn visible on main (cache invalidated by merge)")))))


(deftest branches-write-without-branch-header-defaults-to-main-test
  (testing "writes without X-Graphden-Branch land on main"
    ;; Belt-and-braces sentinel: the branch-routing-wrap MUST default
    ;; to main when the header is absent (otherwise a misconfigured
    ;; client without branch awareness would silently 404 / 500). The
    ;; previous test covered the explicit-branch case; this one
    ;; covers the no-header default-to-main path.
    (let [fn-name (str "default-main-probe-" (System/currentTimeMillis))
          identity-fn (fn-by-name nil "identity")
          write-resp (dispatch {:method :post
                                :path "/api/entities/fn"
                                ;; No :branch ⇒ no X-Graphden-Branch header
                                :content-type "application/x-www-form-urlencoded"
                                :body (str "name=" fn-name
                                           "&parent-ids=" (:id identity-fn))})]
      (is (= 200 (:status write-resp))
          (str "no-header write returned 200; got=" (:status write-resp)))
      (is (some? (fn-by-name nil fn-name))
          "fn visible on main without explicit header — branch-routing default works"))))
