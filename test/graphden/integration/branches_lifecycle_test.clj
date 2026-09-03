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
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.web.errors :as web-errors]))


(def ^:dynamic *router* nil)


(def ^:private test-auth-token "branches-lifecycle-token-abc")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  ;; Force graph-epoch heals inline — this ns merges, and the heal
  ;; triggered from the merge-post-commit RAW thread otherwise races a
  ;; request/teardown into a ClassCastException. See the helper's docstring.
  setup/inline-heal-fixture
  (fn [t]
    (exec/with-clean-registry
      ;; pool-size 6 (vs the default 2): this ns churns branches hard —
      ;; every create/policy/propose/merge bumps the graph epoch, and merges
      ;; spawn a post-commit thread; at size 2 those contend the test
      ;; thread and a borrowed connection intermittently breaks under load
      ;; ("HikariPool marked broken / Socket closed").
      #(let [storage (setup/create-versioned-test-storage 6)
             _ (sb/bootstrap-with-cached-sweep! storage ["core" "web" "app"])
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         ;; Register the router as the process-active one — same as prod's
         ;; `:exec/branch-router` init-key. The merge post-commit reaches
         ;; for `br/current-router` to invalidate a cross-branch merge's
         ;; TARGET ctx; without this it would fall back to the base ctx and
         ;; a merge into a NON-main target branch would stay invisible.
         (br/set-active-router! router)
         (try
           (binding [*router* router]
             (t))
           (finally
             (br/clear-active-router!)
             (sp/close storage)))))))


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
        (let [restarts (atom [])
              ;; `with-redefs` rebinds the var ROOT — under the parallel
              ;; integration runner a sibling namespace's merge lands in this
              ;; atom too, so every assertion below filters by OUR fn / OUR
              ;; target branch instead of assuming the atom is ours alone.
              merge-resp (with-redefs [recon/restart-services-depending-on!
                                       (fn [& args]
                                         (swap! restarts conj [:depending-on (set (map str (nth args 2))) (str (nth args 3))])
                                         {:started [] :stopped [] :not-our-lock []})
                                       recon/restart-services-on-branch!
                                       (fn [& args]
                                         (swap! restarts conj [:on-branch nil (str (nth args 2))])
                                         {:started [] :stopped [] :not-our-lock []})]
                           (dispatch {:method :post
                                      :path "/api/branches/main/merge"
                                      :body {:source feat-name}}))
              merge-body (parse-json merge-resp)
              probe-id (str (:id (fn-by-name feat-name fn-name)))
              target-id (str (get-in merge-body [:merge :target-branch-id]))]
          (testing "post-merge restarts ONLY the services whose closure the merge touched"
            ;; The branch-wide restart bounced the platform's own web-server on
            ;; the cloud (every org's main is the shared main) — the merger got
            ;; a 502. The delta walk is seeded by the merged fn-ids on the target.
            (is (some (fn [[kind seeds target]]
                        (and (= :depending-on kind) (contains? seeds probe-id) (= target-id target)))
                      @restarts)
                (str "expected a delta restart seeded with the probe fn on the target; got "
                     (pr-str @restarts) " probe=" probe-id " target=" target-id))
            (is (not-any? (fn [[kind _ target]] (and (= :on-branch kind) (= target-id target)))
                          @restarts)
                "no branch-wide restart of the target"))
          (is (= 200 (:status merge-resp))
              (str "POST /api/branches/main/merge returned 200; got status="
                   (:status merge-resp) " body=" (:body merge-resp)))
          (is (true? (:ok merge-body))
              (str "merge response envelope reports :ok true (no conflict / no rejection); "
                   "body=" (pr-str merge-body)))
          (is (true? (:review-state-cleared merge-body))
              (str "stage 2b cleared the merged source's review-state and the envelope says so; "
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
        ;; The `:merge-branch!` base-fn invalidates the target ctx's
        ;; graph cache (a delta seeded by the touched fns), so the next
        ;; `/api/graph/entities` read on target sees the merged-in rows
        ;; immediately. Regression if that invalidation ever drops (or
        ;; skips the no-router path this harness runs in): this
        ;; assertion fires before any cache-warming side-effect bails
        ;; out the editor's view.
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


(deftest branch-require-merge-blocks-direct-writes-allows-merge-test
  ;; Protected branches Stage 2: with `require-merge?` on a branch, DIRECT
  ;; graph writes are refused (open-core enforcement) but a MERGE from
  ;; another branch still lands. This is the GitHub "push only via merge"
  ;; toggle, enforced without the tenancy addon.
  (let [run-id (str "-" (System/currentTimeMillis))
        prot (str "protected" run-id)
        feat (str "feat-into-prot" run-id)
        probe (str "prot-probe" run-id)
        identity-fn (fn-by-name nil "identity")]
    ;; a protected branch off main, flag on
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name prot :base-branch-id "main"
                                          :require-merge true}}))))
    (testing "the branch row carries require-merge? true"
      (let [row (-> (dispatch {:method :get :path (str "/api/branches/" prot)})
                    parse-json :branch)]
        (is (true? (:require-merge? row)))))
    (testing "a DIRECT write to the protected branch is refused"
      (let [resp (dispatch {:method :post :path "/api/entities/fn" :branch prot
                            :content-type "application/x-www-form-urlencoded"
                            :body (str "name=" probe "&parent-ids=" (:id identity-fn))})]
        (is (= 409 (:status resp))
            (str "direct write to a require-merge branch is a 409 CONFLICT "
                 "(well-formed write refused by policy); got " (:status resp)))
        (is (nil? (fn-by-name prot probe)) "nothing was written")))
    (testing "a MERGE into the protected branch still lands"
      (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                     :body {:name feat :base-branch-id prot}}))))
      (is (= 200 (:status (dispatch {:method :post :path "/api/entities/fn" :branch feat
                                     :content-type "application/x-www-form-urlencoded"
                                     :body (str "name=" probe "&parent-ids=" (:id identity-fn))})))
          "a write on the UNprotected child is fine")
      (is (some? (fn-by-name feat probe)) "probe IS on feat before merge")
      (let [m (dispatch {:method :post :path (str "/api/branches/" prot "/merge")
                         :body {:source feat}})]
        (is (= 200 (:status m)) (str "merge into protected branch OK; got " (:body m))))
      (is (some? (fn-by-name prot probe))
          "the fn landed on the protected branch via merge, not a direct write"))
    (testing "clearing the flag re-opens direct writes"
      (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" prot "/protect")
                                     :body {:require-merge false}}))))
      (let [probe2 (str probe "-after")
            resp (dispatch {:method :post :path "/api/entities/fn" :branch prot
                            :content-type "application/x-www-form-urlencoded"
                            :body (str "name=" probe2 "&parent-ids=" (:id identity-fn))})]
        (is (= 200 (:status resp)) "direct write allowed after clearing the flag")
        (is (some? (fn-by-name prot probe2)))))))


(deftest branch-propose-review-state-lifecycle-test
  ;; Change proposals Phase A: a branch owner marks a branch as
  ;; "proposed" (submitted for review into its base) and can withdraw it.
  ;; The state rides on `:review-state`, surfaced in the branch JSON, and
  ;; is what a reviewer's proposal list filters on.
  (let [run-id (str "-" (System/currentTimeMillis))
        feat (str "propose-feat" run-id)]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name feat :base-branch-id "main"}}))))
    (testing "a fresh branch is not a proposal"
      (let [row (-> (dispatch {:method :get :path (str "/api/branches/" feat)})
                    parse-json :branch)]
        (is (nil? (:review-state row)) "review-state absent until proposed")))
    (testing "POST /propose marks it proposed"
      (let [resp (dispatch {:method :post :path (str "/api/branches/" feat "/propose")
                            :body {:proposed true}})]
        (is (= 200 (:status resp)))
        (is (true? (:ok (parse-json resp))))
        (is (= "proposed" (:review-state (parse-json resp))) "handler echoes the stored state"))
      (let [row (-> (dispatch {:method :get :path (str "/api/branches/" feat)})
                    parse-json :branch)]
        (is (= "proposed" (:review-state row)) "review-state persisted on the branch row")))
    (testing "the proposal shows in the branch list as proposed"
      ;; re-propose (the previous `testing` left it proposed) then read the list
      (dispatch {:method :post :path (str "/api/branches/" feat "/propose") :body {:proposed true}})
      (let [branches (-> (dispatch {:method :get :path "/api/branches"})
                         parse-json :branches)
            row (some (fn [b] (when (= feat (:name b)) b)) branches)]
        (is (= "proposed" (:review-state row)) "list surfaces review-state for the reviewer")))
    (testing "POST /propose {proposed false} withdraws it"
      (let [resp (dispatch {:method :post :path (str "/api/branches/" feat "/propose")
                            :body {:proposed false}})]
        (is (= 200 (:status resp)))
        (is (nil? (:review-state (parse-json resp))) "withdrawn → nil"))
      (let [row (-> (dispatch {:method :get :path (str "/api/branches/" feat)})
                    parse-json :branch)]
        (is (nil? (:review-state row)) "review-state cleared on the branch row")))))


(deftest branch-review-policy-gate-lifecycle-test
  ;; Review policy Phase C: a target branch requiring N approvals refuses
  ;; a merge until the proposal has them; approving unblocks it. Goes
  ;; through the real Ring handler chain (same as the reviewer's clicks).
  (let [run-id (str "-" (System/currentTimeMillis))
        tgt (str "rp-tgt" run-id)
        src (str "rp-src" run-id)
        probe (str "rp-probe" run-id)
        identity-fn (fn-by-name nil "identity")]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name tgt :base-branch-id "main"}}))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" tgt "/review-policy")
                                   :body {:required-approvals 1}}))))
    (testing "the policy is surfaced on the branch row"
      (is (= 1 (:required-approvals (-> (dispatch {:method :get :path (str "/api/branches/" tgt)})
                                        parse-json :branch)))))
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id tgt}}))))
    (is (= 200 (:status (dispatch {:method :post :path "/api/entities/fn" :branch src
                                   :content-type "application/x-www-form-urlencoded"
                                   :body (str "name=" probe "&parent-ids=" (:id identity-fn))}))))
    (testing "merge is refused (409) while the proposal has no approvals"
      (let [m (dispatch {:method :post :path (str "/api/branches/" tgt "/merge")
                         :body {:source src}})]
        (is (= 409 (:status m)) (str "needs approval; body=" (:body m))))
      (is (nil? (fn-by-name tgt probe)) "nothing merged while blocked"))
    (testing "approving satisfies the policy and the merge lands"
      (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" src "/approve")
                                     :body {}}))))
      (let [st (-> (dispatch {:method :get :path (str "/api/branches/" src "/approvals")})
                   parse-json)]
        (is (= 1 (:required st)))
        (is (= 1 (:have st)))
        (is (true? (:satisfied st))))
      (let [m (dispatch {:method :post :path (str "/api/branches/" tgt "/merge")
                         :body {:source src}})]
        (is (= 200 (:status m)) (str "merge with approval; body=" (:body m))))
      (is (some? (fn-by-name tgt probe))
          "the fn landed on the target after the approved merge"))
    (testing "withdrawing the approval removes it"
      (is (= 200 (:status (dispatch {:method :delete :path (str "/api/branches/" src "/approve")}))))
      (let [st (-> (dispatch {:method :get :path (str "/api/branches/" src "/approvals")})
                   parse-json)]
        (is (zero? (:have st)))))))


(deftest branch-review-stale-dismissal-and-cascade-test
  ;; Stale-dismissal over HTTP + delete cascades approvals + input validation.
  (let [run-id (str "-" (System/currentTimeMillis))
        tgt (str "sd-tgt" run-id)
        src (str "sd-src" run-id)
        probe (str "sd-probe" run-id)
        probe2 (str "sd-probe2" run-id)
        identity-fn (fn-by-name nil "identity")
        write! (fn [branch nm]
                 (dispatch {:method :post :path "/api/entities/fn" :branch branch
                            :content-type "application/x-www-form-urlencoded"
                            :body (str "name=" nm "&parent-ids=" (:id identity-fn))}))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name tgt :base-branch-id "main"}}))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" tgt "/review-policy")
                                   :body {:required-approvals 1}}))))
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id tgt}}))))
    (is (= 200 (:status (write! src probe))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" src "/approve") :body {}}))))
    (testing "approved → satisfied"
      (is (true? (:satisfied (parse-json (dispatch {:method :get :path (str "/api/branches/" src "/approvals")}))))))
    (testing "editing the source after approval auto-dismisses it (stale) → merge re-blocked"
      (is (= 200 (:status (write! src probe2))))
      (let [st (parse-json (dispatch {:method :get :path (str "/api/branches/" src "/approvals")}))]
        (is (false? (:satisfied st)) "approval went stale after the edit")
        (is (zero? (:have st))))
      (let [m (dispatch {:method :post :path (str "/api/branches/" tgt "/merge") :body {:source src}})]
        (is (= 409 (:status m)) "merge blocked again until re-approved"))
      ;; re-approving at the new content re-satisfies the policy (the merge
      ;; landing itself is covered by branch-review-policy-gate-lifecycle-test;
      ;; not repeated here to avoid a second committing merge in one deftest).
      (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" src "/approve") :body {}}))))
      (is (true? (:satisfied (parse-json (dispatch {:method :get :path (str "/api/branches/" src "/approvals")}))))
          "fresh approval at the new content re-satisfies"))))


;; delete-cascade of :branch-approval → merge/core-test/delete-branch-cascades-approvals-test.


(deftest branch-review-policy-rejects-bad-input-test
  ;; Boundary validation — malformed review-policy input is REJECTED with a
  ;; `:validation-error/*` (which the top-level `wrap-error-boundary` maps to
  ;; a clean 400), never silently accepted or a raw 500. This harness's
  ;; `br/dispatch` runs the router WITHOUT that outer boundary, so the throw
  ;; propagates here — we assert both that it rejects AND that its type maps
  ;; to 400 (the boundary's job in prod).
  (let [run-id (str "-" (System/currentTimeMillis))
        tgt (str "rv-bad" run-id)
        rejected (fn [body]
                   (try (dispatch {:method :post :path (str "/api/branches/" tgt "/review-policy")
                                   :body body})
                        nil
                        (catch clojure.lang.ExceptionInfo ex ex)))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name tgt :base-branch-id "main"}}))))
    (testing "a negative required-approvals is rejected → 400 at the boundary"
      (let [e (rejected {:required-approvals -1})]
        (is (some? e) "rejected, not silently accepted as off")
        (is (= 400 (web-errors/status-for-ex-data (ex-data e)))
            "validation-error maps to a clean 400")))
    (testing "approver-ids as a bare string is rejected → 400 (not char-shredded)"
      (let [e (rejected {:approver-ids "not-a-list"})]
        (is (some? e))
        (is (= 400 (web-errors/status-for-ex-data (ex-data e))))))))


(deftest branch-comments-lifecycle-test
  ;; Proposal comments: add → list (oldest-first) → delete own → cascade
  ;; with the branch. Single-tenant author is "anonymous", so delete-own
  ;; succeeds here; the author≠caller 403 needs the tenancy harness.
  (let [run-id (str "-" (System/currentTimeMillis))
        feat (str "cmt-feat" run-id)
        cpath (fn [] (str "/api/branches/" feat "/comments"))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name feat :base-branch-id "main"}}))))
    (testing "empty body is refused"
      (let [r (parse-json (dispatch {:method :post :path (cpath) :body {:body "   "}}))]
        (is (false? (:ok r)))))
    (testing "add two comments, list oldest-first"
      (is (true? (:ok (parse-json (dispatch {:method :post :path (cpath) :body {:body "first!"}})))))
      (is (true? (:ok (parse-json (dispatch {:method :post :path (cpath) :body {:body "second"}})))))
      (let [cs (:comments (parse-json (dispatch {:method :get :path (cpath)})))]
        (is (= ["first!" "second"] (mapv :body cs)) "oldest first")
        (is (every? #(= "anonymous" (:author-id %)) cs))))
    (testing "delete own comment by id"
      (let [cs (:comments (parse-json (dispatch {:method :get :path (cpath)})))
            id (:id (first cs))
            r (parse-json (dispatch {:method :delete :path (cpath) :body {:id id}}))]
        (is (true? (:ok r)))
        (is (true? (:removed r)))
        (is (= ["second"] (mapv :body (:comments (parse-json (dispatch {:method :get :path (cpath)}))))))))
    (testing "unknown branch → clean envelope"
      (let [r (parse-json (dispatch {:method :get :path "/api/branches/no-such-cmt-branch/comments"}))]
        (is (false? (:ok r)))))))


(deftest branch-review-policy-patch-preserves-approver-ids-test
  ;; audit-2 P1: the ⚙ menu POSTs only required-approvals + allow-self;
  ;; a full-set write nil'd an API-set approver-ids. With :keep patch
  ;; semantics, a partial POST must leave approver-ids intact.
  (let [tgt (str "rp-patch-" (System/currentTimeMillis))
        rp (fn [body]
             (dispatch {:method :post :path (str "/api/branches/" tgt "/review-policy")
                        :body body}))
        read-policy (fn []
                      (-> (dispatch {:method :get :path (str "/api/branches/" tgt)})
                          parse-json :branch))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name tgt :base-branch-id "main"}}))))
    (testing "API sets an explicit reviewer allow-list"
      (is (= 200 (:status (rp {:required-approvals 2 :approver-ids ["alice" "bob"]}))))
      (is (= ["alice" "bob"] (:approver-ids (read-policy)))))
    (testing "a ⚙-menu-shaped partial POST (no approver-ids) leaves it intact"
      (is (= 200 (:status (rp {:required-approvals 1 :allow-self-approval false}))))
      (let [p (read-policy)]
        (is (= 1 (:required-approvals p)) "required-approvals updated")
        (is (false? (:allow-self-approval? p)) "allow-self updated")
        (is (= ["alice" "bob"] (:approver-ids p)) "approver-ids NOT clobbered")))
    (testing "an explicit null clears it (present-but-null ≠ absent) —
              and the two OMITTED fields are :keep-preserved (audit-3 G3)"
      (is (= 200 (:status (rp {:approver-ids nil}))))
      (let [p (read-policy)]
        (is (nil? (:approver-ids p)) "approver-ids cleared by explicit null")
        (is (= 1 (:required-approvals p)) "required-approvals kept (:keep, omitted)")
        (is (false? (:allow-self-approval? p)) "allow-self kept (:keep, omitted)")))))


(deftest branch-comment-body-cap-test
  ;; audit-2 P2: an over-long comment body is a clean 400, not an
  ;; unbounded DB write. Like branch-review-policy-rejects-bad-input-test,
  ;; this harness's br/dispatch runs WITHOUT the outer wrap-error-boundary,
  ;; so the :validation-error throw propagates here — assert it rejects AND
  ;; its type maps to 400 (the boundary's job in prod), and nothing stored.
  (let [src (str "cc-" (System/currentTimeMillis))
        cpath (str "/api/branches/" src "/comments")]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id "main"}}))))
    (testing "a body over the cap is rejected → 400 and nothing is stored"
      (let [e (try (dispatch {:method :post :path cpath
                              :body {:body (str/join (repeat 10001 "x"))}})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "rejected, not silently stored")
        (is (= 400 (web-errors/status-for-ex-data (ex-data e)))
            "comment-too-long maps to a clean 400"))
      (is (empty? (:comments (parse-json (dispatch {:method :get :path cpath}))))))
    (testing "a body at the cap is accepted"
      (let [ok (str/join (repeat 10000 "y"))]
        (is (:ok (parse-json (dispatch {:method :post :path cpath :body {:body ok}}))))))))


(deftest branch-merge-clears-proposal-review-state-test
  ;; audit-2 P2: a merged proposal leaves the inbox — its review-state
  ;; clears on the successful merge (GitHub closes a merged PR).
  (let [run-id (str "-" (System/currentTimeMillis))
        tgt (str "mc-tgt" run-id)
        src (str "mc-src" run-id)
        probe (str "mc-probe" run-id)
        identity-fn (fn-by-name nil "identity")]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name tgt :base-branch-id "main"}}))))
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id tgt}}))))
    (is (= 200 (:status (dispatch {:method :post :path "/api/entities/fn" :branch src
                                   :content-type "application/x-www-form-urlencoded"
                                   :body (str "name=" probe "&parent-ids=" (:id identity-fn))}))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" src "/propose")
                                   :body {:proposed true}}))))
    (testing "proposed before merge"
      (is (= "proposed" (:review-state (:branch (parse-json (dispatch {:method :get :path (str "/api/branches/" src)})))))))
    (is (= 200 (:status (dispatch {:method :post :path (str "/api/branches/" tgt "/merge")
                                   :body {:source src}}))))
    (testing "review-state cleared after the merge — proposal left the inbox"
      (is (nil? (:review-state (:branch (parse-json (dispatch {:method :get :path (str "/api/branches/" src)})))))))))


(deftest branch-comment-row-cap-test
  ;; The per-branch thread has a row cap (no fairness-quota coverage,
  ;; and the client downloads the whole thread) — over the cap = clean
  ;; 400, nothing stored. Cap bound low for the test.
  (let [src (str "cap-" (System/currentTimeMillis))
        cpath (str "/api/branches/" src "/comments")]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id "main"}}))))
    ;; the impls ns is loader-loaded, not on the classpath — resolve
    ;; the cap var at runtime (the fixture has booted the packages).
    ;; NB the suite's dispatch sits BELOW wrap-error-boundary (it lives
    ;; on the real server's top chain), so author errors surface as the
    ;; raw ex-info here — assert the mapped status like the anchor test.
    (let [cap-var (ns-resolve (find-ns 'graphden.packages.app.branches.impls)
                              '*max-comments-per-branch*)]
      (with-bindings {cap-var 2}
        (is (true? (:ok (parse-json (dispatch {:method :post :path cpath
                                               :body {:body "one"}})))))
        (is (true? (:ok (parse-json (dispatch {:method :post :path cpath
                                               :body {:body "two"}})))))
        (let [e (try (dispatch {:method :post :path cpath
                                :body {:body "three"}})
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e) "over-cap post is rejected")
          (is (= :validation-error/comment-limit (:type (ex-data e))))
          (is (= 400 (web-errors/status-for-ex-data (ex-data e)))
              "maps to a clean 400 at the server's error boundary"))))
    (is (= 2 (count (:comments (parse-json (dispatch {:method :get :path cpath})))))
        "nothing stored past the cap")))


(deftest branch-comment-anchor-test
  ;; diff v2: a comment may anchor to one diffed graph element
  ;; (entity-name + entity-id). Anchors round-trip through the wire
  ;; shape; unknown kinds and half-anchors are clean 400s.
  (let [src (str "anch-" (System/currentTimeMillis))
        cpath (str "/api/branches/" src "/comments")
        fid (str (random-uuid))
        comments (fn [] (:comments (parse-json (dispatch {:method :get :path cpath}))))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name src :base-branch-id "main"}}))))
    (testing "anchored comment round-trips entity-name + entity-id"
      (is (true? (:ok (parse-json (dispatch {:method :post :path cpath
                                             :body {:body "pin me"
                                                    :entity-name "fn"
                                                    :entity-id fid}})))))
      (let [c (first (comments))]
        (is (= "fn" (:entity-name c)))
        (is (= fid (:entity-id c)))))
    (testing "a plain comment stays unanchored"
      (is (true? (:ok (parse-json (dispatch {:method :post :path cpath
                                             :body {:body "general"}})))))
      (let [c (last (comments))]
        (is (= "general" (:body c)))
        (is (nil? (:entity-name c)))
        (is (nil? (:entity-id c)))))
    (testing "an unknown anchor kind → 400, nothing stored"
      (let [e (try (dispatch {:method :post :path cpath
                              :body {:body "x" :entity-name "slotx"
                                     :entity-id fid}})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "rejected")
        (is (= 400 (web-errors/status-for-ex-data (ex-data e)))))
      (is (= 2 (count (comments)))))
    (testing "a half-anchor (kind without id) → 400"
      (let [e (try (dispatch {:method :post :path cpath
                              :body {:body "x" :entity-name "fn"}})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= 400 (web-errors/status-for-ex-data (ex-data e))))))))


(defn- form-encode
  [m]
  (->> m
       (map (fn [[k v]]
              (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))


(deftest per-org-rich-slice-is-branch-scoped-test
  ;; The per-ORG rich-types cousin used to stay process-global across
  ;; branches (VERSIONING § Known gaps residual): a tenant editing the
  ;; same-named fn on two branches shared one per-org name index. Branch
  ;; ctxs now fork it exactly like the main slice — assert the ctx
  ;; carries its own atom and a write into the branch's slice never
  ;; reaches the base's.
  (let [feat (str "orgslice-" (System/currentTimeMillis))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name feat :base-branch-id "main"}}))))
    ;; Force the branch entry to build (any dispatch on it).
    (is (= 200 (:status (dispatch {:method :get :path "/api/branches"
                                   :branch feat}))))
    (let [branches-resp (parse-json (dispatch {:method :get :path "/api/branches"}))
          feat-id (:id (some #(when (= feat (:name %)) %) (:branches branches-resp)))
          base-ctx (:base-ctx *router*)
          feat-ctx (br/ctx-for *router* (parse-uuid feat-id))
          base-atom (:per-org-rich-atom base-ctx)
          feat-atom (:per-org-rich-atom feat-ctx)]
      (is (some? feat-atom) "branch ctx carries a per-org slice atom")
      (is (not (identical? base-atom feat-atom))
          "…and it is a FORK, not the base's atom")
      (swap! feat-atom assoc-in ["acme" :by-name :probe] {:effects ["io"]})
      (is (nil? (get-in @base-atom ["acme" :by-name :probe]))
          "a branch-slice write never reaches the base's per-org index"))))


(deftest rich-types-registry-branch-scope-test
  ;; 2026-08-31: the rich-types registry used to be process-global and
  ;; last-compile-wins — compiling a branch ctx overwrote the base's
  ;; entries, so `/api/types` answered with a cross-branch union (a fn
  ;; existing only on a branch showed up under main) and effect sets
  ;; reflected whichever branch compiled last. Per-ctx slices now: the
  ;; default branch keeps the boot registry, every other branch forks a
  ;; private copy at ctx build and the dispatcher binds it per request.
  (let [run-id (str "-" (System/currentTimeMillis))
        feat (str "rts" run-id)
        only-name (str "rts-only" run-id)
        eff-name (str "rts-eff" run-id)
        types (fn [branch]
                (let [resp (dispatch {:method :get :path "/api/types"
                                      :branch branch})]
                  (is (= 200 (:status resp)) (str "/api/types on " (or branch "main")))
                  (parse-json resp)))
        graph (fn [branch]
                (parse-json (dispatch {:method :get :path "/api/graph/entities"
                                       :branch branch})))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name feat :base-branch-id "main"}}))))
    (let [ents (graph nil)
          by-name (fn [nm] (some #(when (= nm (:name %)) %) (:fns ents)))
          identity-fn (by-name "identity")
          coalesce (by-name "coalesce")
          time-fn (by-name "current-time-ms")
          value-slot (some (fn [fs]
                             (when (= (:id coalesce) (:fn-id fs))
                               (some #(when (and (= (:id %) (:slot-id fs))
                                                 (= "value" (:name %)))
                                        %)
                                     (:slots ents))))
                           (:fn-slots ents))]
      (is (and identity-fn coalesce time-fn value-slot) "baseline fns + :value slot resolved")

      (testing "a fn created ONLY on a branch stays out of main's registry"
        (is (= 200 (:status (dispatch {:method :post :path "/api/entities/fn"
                                       :branch feat
                                       :content-type "application/x-www-form-urlencoded"
                                       :body (form-encode {:name only-name
                                                           :parent-ids (:id identity-fn)})}))))
        ;; Compile/serve the BRANCH first — the leak direction was
        ;; "branch compile clobbers the global".
        (is (some? (get (types feat) (keyword only-name)))
            "the branch's own registry carries the fn")
        (is (nil? (get (types nil) (keyword only-name)))
            "main's registry does NOT (pre-fix: cross-branch union)"))

      (testing "effect sets answer per branch"
        (is (= 200 (:status (dispatch {:method :post :path "/api/entities/fn"
                                       :content-type "application/x-www-form-urlencoded"
                                       :body (form-encode {:name eff-name
                                                           :parent-ids (:id coalesce)})}))))
        (let [eff-id (:id (some #(when (= eff-name (:name %)) %) (:fns (graph nil))))]
          (is (some? eff-id))
          ;; Wire the :value slot to :current-time-ms ON THE BRANCH only.
          (is (= 200 (:status (dispatch {:method :post :path "/api/entities/binding"
                                         :branch feat
                                         :content-type "application/x-www-form-urlencoded"
                                         :body (form-encode {:fn-id eff-id
                                                             :slot-id (:id value-slot)
                                                             :ref-fn-id (:id time-fn)})}))))
          (let [feat-effects (set (:effects (get (types feat) (keyword eff-name))))
                main-effects (set (:effects (get (types nil) (keyword eff-name))))]
            (is (contains? feat-effects "time")
                (str "branch view reaches :time — got " feat-effects))
            (is (not (contains? main-effects "time"))
                (str "main view stays pure — got " main-effects))))))))


(deftest diff-view-endpoint-smoke-test
  ;; The grouped diff-v2 JSON — since UX-v3 the ONLY diff wire: compare
  ;; mode, the review dialog's change list and the suggestion previews
  ;; all render client-side from it (the server partial is retired).
  (let [run-id (str "-" (System/currentTimeMillis))
        feat (str "dvep" run-id)
        probe (str "dvep-fn" run-id)]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name feat :base-branch-id "main"}}))))
    (let [identity-fn (fn-by-name nil "identity")]
      (is (= 200 (:status (dispatch {:method :post :path "/api/entities/fn"
                                     :branch feat
                                     :content-type "application/x-www-form-urlencoded"
                                     :body (form-encode {:name probe
                                                         :parent-ids (:id identity-fn)})})))))

    (testing "GET /api/branches/:ref/diff-view?against= — grouped envelope"
      (let [resp (dispatch {:method :get
                            :path "/api/branches/main/diff-view"
                            :query (str "against=" feat)})
            body (parse-json resp)]
        (is (= 200 (:status resp)))
        (is (true? (:ok body)))
        (is (vector? (:groups body)))
        (let [grp (some #(when (= probe (:fn-name %)) %) (:groups body))]
          (is (some? grp) "the branch-only fn groups under its own name")
          (is (= "added-in-source" (:change grp)))
          (is (= (str ":" probe) (:fn-label grp))))))

    (testing "missing ?against= → clean error envelope, not a 500"
      (let [body (parse-json (dispatch {:method :get
                                        :path "/api/branches/main/diff-view"}))]
        (is (false? (:ok body)))))

    (testing "unknown source ref → clean error envelope"
      (let [body (parse-json (dispatch {:method :get
                                        :path "/api/branches/main/diff-view"
                                        :query "against=no-such-branch-xyz"}))]
        (is (false? (:ok body)))))

    (testing "the retired partial route is really gone (UX-v3)"
      (let [resp (dispatch {:method :get
                            :path "/partials/branch-diff"
                            :query (str "target=main&source=" feat)})]
        (is (not= 200 (:status resp))
            "no half-alive server renderer behind the client one")))))


(deftest rich-types-slice-propagation-on-merge-test
  ;; The per-branch slices need a PROPAGATION channel: a merge's
  ;; type-checks ran under the SOURCE's slice, and the default branch's
  ;; entry is pinned (never rebuilt) — without the post-merge re-check
  ;; main's /api/types would miss merged fns forever.
  (let [run-id (str "-" (System/currentTimeMillis))
        feat (str "rtsm" run-id)
        moved (str "rtsm-fn" run-id)
        types-has? (fn []
                     (contains? (parse-json (dispatch {:method :get
                                                       :path "/api/types"}))
                                (keyword moved)))]
    (is (= 200 (:status (dispatch {:method :post :path "/api/branches"
                                   :body {:name feat :base-branch-id "main"}}))))
    (let [identity-fn (fn-by-name nil "identity")]
      (is (= 200 (:status (dispatch {:method :post :path "/api/entities/fn"
                                     :branch feat
                                     :content-type "application/x-www-form-urlencoded"
                                     :body (form-encode {:name moved
                                                         :parent-ids (:id identity-fn)})})))))
    (is (false? (types-has?)) "pre-merge: main's slice does not know the fn")
    (is (true? (:ok (parse-json (dispatch {:method :post
                                           :path "/api/branches/main/merge"
                                           :body {:source feat}})))))
    ;; The re-check is async (a bounded background future) — poll.
    (let [deadline (+ (System/currentTimeMillis) 15000)]
      (loop []
        (cond
          (types-has?) (is true "post-merge: the merged fn reached main's slice")
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 300) (recur))
          :else (is (types-has?)
                    "post-merge: the merged fn reached main's slice (15s deadline)"))))))
