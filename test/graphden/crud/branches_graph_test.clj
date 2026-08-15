(ns ^:integration graphden.crud.branches-graph-test
  "Graph-path tests for the branches HTTP handlers
   (`:list-branches-handler` / `:get-branch-handler` /
   `:diff-branches-handler` / `:create-branch-handler` /
   `:delete-branch-handler` / `:preview-conflicts-handler` /
   `:merge-branch-handler` / `:list-fn-versions-handler`).

   Replaces the test-side reproductions in `branches_test.clj` that
   called `branches/list-branches` / `branches/create-branch` &c. —
   i.e. Clojure helpers that production never runs once the handlers
   went through the package decomposition.

   These tests bootstrap the full `[core web app]` package set once
   per JVM (gh/via `setup/bootstrap-crud-graph!`) against a fresh
   VERSIONED storage and invoke each handler through `cr/execute`,
   the same code path the `:list-branches-handler` Ring handler
   reaches in production. Each deftest opens a fresh storage so
   branch / fn rows from neighbouring tests don't collide."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.graph-harness :as gh :refer [*graph* json-req uniq]]
    [graphden.versioning.storage.core :as vs]))


;; =============================================================================
;; Bootstrap fixture — heavy, runs ONCE per JVM.
;;
;; Bootstrap-crud-graph! syncs 1040 fn-defs and compiles the registry —
;; ~10 s of one-time work. Sharing it across 34 deftests drops the ns
;; runtime from ~9 min (per-test re-bootstrap) to ~25 s. Tests sidestep
;; cross-test pollution by minting unique branch / fn names per
;; assertion (`uniq` helper) so sibling deftests can run on the same
;; *storage* without their names colliding. The few assertions that
;; rely on a "main is the only branch" empty-state are scoped to the
;; just-created entries via name filtering — see
;; `list-branches-includes-main` + `list-branches-multiple-new-sorted`.
;; =============================================================================

(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*)))
  ;; Thin local alias: 11 assertions read `*storage*` directly.
  (fn [t] (binding [*storage* (:storage *graph*)] (t))))


;; =============================================================================
;; Request helpers
;; =============================================================================

(defn- split-uri
  "Split a `path?query` string into `[path query-string-or-nil]`.
   Mirrors what Ring's middleware would do before the handler sees
   the request — `:uri` carries the path only, `:query-string` the
   raw query portion (no `?`)."
  [uri]
  (let [idx (String/.indexOf uri "?")]
    (if (neg? idx)
      [uri nil]
      [(subs uri 0 idx) (subs uri (inc idx))])))


(defn- get-req
  [uri]
  (let [[path qs] (split-uri uri)]
    (cond-> {:uri path :request-method :get :headers {}}
      qs (assoc :query-string qs))))


(defn- delete-req
  [uri]
  (let [[path qs] (split-uri uri)]
    (cond-> {:uri path :request-method :delete :headers {}}
      qs (assoc :query-string qs))))


(defn- json-body
  "Parse the Ring response body. Handlers' JSON-encoder emits string
   keys; we keywordize for ergonomic assertion."
  [resp]
  (let [b (:body resp)]
    (cond
      (nil? b) nil
      (string? b) (cheshire/parse-string b true)
      :else (cheshire/parse-string (slurp b) true))))


(defn- mk-branch!
  "Create a branch via the public handler + return its row. Caller
   passes the already-`uniq`'d name so sibling deftests don't collide
   on the shared *storage*."
  [branch-name & [base-ref]]
  (let [body (cheshire/parse-string
               (:body (gh/via :create-branch-handler
                              (json-req "/api/branches"
                                        (cond-> {:name branch-name}
                                          base-ref (assoc :base-branch-id base-ref)))))
               true)]
    (is (:ok body) (str "create-branch-handler failed for " branch-name
                        " — " (:error body)))
    (:branch body)))


(defn- mk-fn!
  "Insert a versioned :fn row on the current branch + return its id.
   Caller passes the already-`uniq`'d fn-name."
  [fn-name]
  (let [row (sp/create-entity *storage* :fn
                              {:id (random-uuid)
                               :name fn-name
                               :parent-ids []})]
    (:id row)))


;; =============================================================================
;; list-branches-handler  (GET /api/branches)
;; =============================================================================

(deftest list-branches-includes-main
  ;; Shared *storage* may carry branches minted by sibling deftests,
  ;; so we assert :main is present + JSON shape — NOT
  ;; "main is the only branch".
  (let [resp (gh/via :list-branches-handler (get-req "/api/branches"))
        body (json-body resp)]
    (is (= 200 (:status resp)))
    (is (:ok body))
    (is (pos? (:count body)))
    (let [main (some #(when (= "main" (:name %)) %) (:branches body))]
      (is (some? main) "main must always be present")
      (testing "as-json-branch shape"
        (is (string? (:id main)))
        (is (nil? (:base-branch-id main)) "main has no parent")
        (is (string? (:created-at main)))))))


(deftest list-branches-multiple-new-sorted-by-created-at
  ;; Confirm ascending created-at within the two branches THIS test
  ;; created — siblings' rows may also be present, so we filter to
  ;; just our newly-minted pair.
  (let [a-name (uniq "feature-a")
        _ (mk-branch! a-name)
        _ (Thread/sleep 5)
        b-name (uniq "feature-b")
        _ (mk-branch! b-name)
        body (json-body (gh/via :list-branches-handler (get-req "/api/branches")))
        ours (filter #(#{a-name b-name} (:name %)) (:branches body))]
    (is (= 2 (count ours)))
    (is (= [a-name b-name] (mapv :name ours))
        "ascending created-at ordering for the two branches we created")))


;; =============================================================================
;; get-branch-handler  (GET /api/branches/:ref)
;; =============================================================================

(deftest get-branch-by-name
  (let [name (uniq "feat")
        b (mk-branch! name)
        body (json-body (gh/via :get-branch-handler
                                (get-req (str "/api/branches/" name))))]
    (is (:ok body))
    (is (= name (-> body :branch :name)))
    (is (= (:id b) (-> body :branch :id)))))


(deftest get-branch-by-uuid
  (let [name (uniq "feat")
        b (mk-branch! name)
        body (json-body (gh/via :get-branch-handler
                                (get-req (str "/api/branches/" (:id b)))))]
    (is (:ok body))
    (is (= name (-> body :branch :name)))))


(deftest get-branch-not-found
  (let [body (json-body (gh/via :get-branch-handler
                                (get-req (str "/api/branches/no-such-"
                                              (random-uuid)))))]
    (is (not (:ok body)))
    (is (re-find #"not found" (:error body)))))


(deftest get-branch-blank-returns-not-found
  (let [body (json-body (gh/via :get-branch-handler (get-req "/api/branches/  ")))]
    (is (not (:ok body))
        "blank ref → not-found (resolve-branch-ref guards str/blank?)")))


;; =============================================================================
;; list-fn-versions-handler  (GET /api/fns/:fn-id/versions)
;; =============================================================================

(deftest list-fn-versions-no-fn-id-returns-error
  (let [body (json-body (gh/via :list-fn-versions-handler
                                (get-req "/api/fns/")))]
    ;; URI carries no fn-id segment after `/api/fns/` — the handler
    ;; rejects via its parse stage.
    (is (some? body))
    (is (or (false? (:ok body))
            (and (true? (:ok body)) (zero? (:count body))))
        "missing fn-id surfaces as either a parse rejection or an empty list")))


(deftest list-fn-versions-single-version
  (let [name (uniq "my-fn")
        fn-id (mk-fn! name)
        body (json-body (gh/via :list-fn-versions-handler
                                (get-req (str "/api/fns/" fn-id "/versions"))))]
    (is (:ok body))
    (is (= 1 (:count body)))
    (let [v (first (:versions body))]
      (is (= name (:name v)))
      (is (= "main" (:branch-name v)) "joined branch-name")
      (is (zero? (:execution-count v))
          "no executions yet → count 0")
      (is (string? (:id v)) "uuids stringified")
      (is (string? (:branch-id v)))
      (is (string? (:created-at v))))))


(deftest list-fn-versions-cross-branch
  ;; Same fn touched on two branches → two version rows, latest
  ;; created-at first.
  (let [name (uniq "my-fn")
        fn-id (mk-fn! name)
        feat (mk-branch! (uniq "feat"))
        on-feat (vs/switch-branch *storage* (java.util.UUID/fromString (:id feat)))]
    (Thread/sleep 5)
    (sp/update-entity on-feat :fn fn-id
                      {:name name :description "edited-on-feat"})
    (let [body (json-body (gh/via :list-fn-versions-handler
                                  (get-req (str "/api/fns/" fn-id "/versions"))))]
      (is (= 2 (:count body)))
      (is (= (:name feat) (-> body :versions first :branch-name))
          "latest version (feat) wins the first slot")
      (is (= "main" (-> body :versions second :branch-name))))))


;; =============================================================================
;; diff-branches-handler  (GET /api/branches/:ref/diff?against=…)
;; =============================================================================

(deftest diff-branches-target-not-found
  (let [body (json-body (gh/via :diff-branches-handler
                                (get-req (str "/api/branches/no-target-"
                                              (random-uuid) "/diff?against=main"))))]
    (is (not (:ok body)))
    (is (re-find #"Target branch not found" (:error body)))))


(deftest diff-branches-source-not-found
  (let [body (json-body (gh/via :diff-branches-handler
                                (get-req (str "/api/branches/main/diff?against=no-source-"
                                              (random-uuid)))))]
    (is (not (:ok body)))
    (is (re-find #"Source branch not found" (:error body)))))


(deftest diff-branches-against-missing
  (let [body (json-body (gh/via :diff-branches-handler
                                (get-req "/api/branches/main/diff")))]
    (is (not (:ok body)))
    (is (re-find #"against" (:error body)))))


(deftest diff-branches-identical-returns-empty
  (let [feat (mk-branch! (uniq "feat"))
        body (json-body (gh/via :diff-branches-handler
                                (get-req (str "/api/branches/" (:name feat)
                                              "/diff?against=main"))))]
    (is (:ok body))
    (is (= (:name feat) (-> body :target :name)))
    (is (= "main" (-> body :source :name)))
    (is (zero? (:count body)))
    (is (= [] (:diffs body)))))


(deftest diff-branches-detects-modifications
  (let [fn-name (uniq "shared-fn")
        fn-id (mk-fn! fn-name)
        feat (mk-branch! (uniq "feat"))
        on-feat (vs/switch-branch *storage* (java.util.UUID/fromString (:id feat)))]
    (sp/update-entity on-feat :fn fn-id
                      {:name fn-name :description "modified"})
    (let [body (json-body (gh/via :diff-branches-handler
                                  (get-req (str "/api/branches/" (:name feat)
                                                "/diff?against=main"))))]
      (is (:ok body))
      (is (pos? (:count body)) "feat diverged from main by one fn description edit")
      (let [d (first (:diffs body))]
        (is (#{"fn" :fn} (:entity-name d)))
        (is (string? (:entity-id d)))
        (is (#{"modified" "added-in-source" "added-in-target"
               :modified :added-in-source :added-in-target}
             (:change d)))))))


;; =============================================================================
;; create-branch-handler  (POST /api/branches)
;; =============================================================================

(deftest create-branch-missing-name
  (let [body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches" {})))]
    (is (not (:ok body)))
    (is (re-find #":name|name" (:error body)))))


(deftest create-branch-blank-name
  (let [body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches" {:name "   "})))]
    (is (not (:ok body)) "blank :name treated as missing")))


(deftest create-branch-duplicate-rejected
  (let [name (uniq "feat")
        _ (mk-branch! name)
        body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches" {:name name})))]
    (is (not (:ok body)))
    (is (re-find #"already exists" (:error body)))))


(deftest create-branch-default-forks-main
  (let [body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches" {:name (uniq "feat")})))]
    (is (:ok body))
    (is (string? (-> body :branch :base-branch-id))
        "default fork picks the wrapper's current branch (main)")))


;; =============================================================================
;; Protected branches (Stage 1) — write-policy on create + the
;; set-branch-policy-handler (POST /api/branches/:ref/policy).
;; =============================================================================

(deftest create-branch-with-write-policy
  (let [name (uniq "guarded")
        body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches"
                                          {:name name :write-policy "owner"})))]
    (is (:ok body))
    (is (= "owner" (-> body :branch :write-policy))
        "create surfaces the stored policy in the row envelope")
    (is (= "owner" (:write-policy (first (sp/query-entities (vs/unwrap *storage*)
                                                            :branch {:name name}))))
        "policy persisted on the branch row")))


(deftest create-branch-invalid-write-policy-rejected
  (let [body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches"
                                          {:name (uniq "guarded")
                                           :write-policy "bogus"})))]
    (is (not (:ok body)) "invalid policy value refused at create")))


(deftest set-branch-policy-roundtrip
  (let [name (uniq "guarded")
        _ (mk-branch! name)
        set-resp (json-body (gh/via :set-branch-policy-handler
                                    (json-req (str "/api/branches/" name "/policy")
                                              {:write-policy "admins"})))
        cleared (json-body (gh/via :set-branch-policy-handler
                                   (json-req (str "/api/branches/" name "/policy")
                                             {:write-policy "open"})))]
    (is (:ok set-resp))
    (is (= "admins" (:write-policy set-resp)))
    (is (:ok cleared))
    (is (nil? (:write-policy cleared)) "\"open\" clears back to nil")
    (is (nil? (:write-policy (first (sp/query-entities (vs/unwrap *storage*)
                                                       :branch {:name name}))))
        "cleared policy persisted as NULL")))


(deftest set-branch-policy-guards
  (let [name (uniq "guarded")
        _ (mk-branch! name)
        invalid (json-body (gh/via :set-branch-policy-handler
                                   (json-req (str "/api/branches/" name "/policy")
                                             {:write-policy "bogus"})))
        missing (json-body (gh/via :set-branch-policy-handler
                                   (json-req (str "/api/branches/" (uniq "nope") "/policy")
                                             {:write-policy "open"})))]
    (is (not (:ok invalid)))
    (is (re-find #"open, owner, admins" (:error invalid)))
    (is (not (:ok missing)))
    (is (re-find #"not found" (:error missing)))))


(deftest create-branch-explicit-base
  (let [feat (mk-branch! (uniq "feat"))
        body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches"
                                          {:name (uniq "feat-2")
                                           :base-branch-id (:name feat)})))]
    (is (:ok body))
    (is (= (:id feat) (-> body :branch :base-branch-id)))))


(deftest create-branch-unknown-base
  (let [body (json-body (gh/via :create-branch-handler
                                (json-req "/api/branches"
                                          {:name (uniq "feat")
                                           :base-branch-id (str "no-such-" (random-uuid))})))]
    (is (not (:ok body)))
    (is (re-find #"Base branch not found" (:error body)))))


;; =============================================================================
;; delete-branch-handler  (DELETE /api/branches/:ref)
;; =============================================================================

(deftest delete-branch-not-found
  (let [body (json-body (gh/via :delete-branch-handler
                                (delete-req (str "/api/branches/no-such-" (random-uuid)))))]
    (is (not (:ok body)))
    (is (re-find #"not found" (:error body)))))


(deftest delete-branch-rejects-main
  (let [body (json-body (gh/via :delete-branch-handler
                                (delete-req "/api/branches/main")))]
    (is (not (:ok body)))
    (is (#{"root-branch-undeletable" :root-branch-undeletable}
         (:reason body)))))


(deftest delete-branch-rejects-with-children
  (let [parent (mk-branch! (uniq "parent"))
        _child (mk-branch! (uniq "child") (:name parent))
        body (json-body (gh/via :delete-branch-handler
                                (delete-req (str "/api/branches/" (:name parent)))))]
    (is (not (:ok body)))
    (is (#{"branch-has-children" :branch-has-children} (:reason body)))
    (is (seq (:child-branch-ids body))
        "carries the offending child ids")))


(deftest delete-branch-happy
  (let [name (uniq "ephemeral")
        b (mk-branch! name)
        body (json-body (gh/via :delete-branch-handler
                                (delete-req (str "/api/branches/" name))))]
    (is (:ok body))
    (is (= (:id b) (:id body)))
    (is (= name (:name body)))
    (testing "branch is gone from list-branches-handler"
      (let [list-body (json-body (gh/via :list-branches-handler
                                         (get-req "/api/branches")))]
        (is (not-any? #(= name (:name %)) (:branches list-body)))))))


;; =============================================================================
;; preview-conflicts-handler  (GET /api/branches/:ref/conflicts?source=…)
;; =============================================================================

(deftest preview-conflicts-target-missing
  (let [body (json-body (gh/via :preview-conflicts-handler
                                (get-req (str "/api/branches/no-target-"
                                              (random-uuid)
                                              "/conflicts?source=main"))))]
    (is (not (:ok body)))
    (is (re-find #"Target branch not found" (:error body)))))


(deftest preview-conflicts-source-ref-missing
  (let [body (json-body (gh/via :preview-conflicts-handler
                                (get-req "/api/branches/main/conflicts")))]
    (is (not (:ok body)))
    (is (re-find #"source" (:error body)))))


(deftest preview-conflicts-source-not-found
  (let [body (json-body (gh/via :preview-conflicts-handler
                                (get-req (str "/api/branches/main/conflicts?source=no-such-"
                                              (random-uuid)))))]
    (is (not (:ok body)))
    (is (re-find #"Source branch not found" (:error body)))))


(deftest preview-conflicts-no-conflicts
  (let [feat (mk-branch! (uniq "feat"))
        body (json-body (gh/via :preview-conflicts-handler
                                (get-req (str "/api/branches/main/conflicts?source="
                                              (:name feat)))))]
    (is (:ok body))
    (is (zero? (:count body)))
    (is (= [] (:conflicts body)))
    (is (= "main" (-> body :target :name)))
    (is (= (:name feat) (-> body :source :name)))))


;; =============================================================================
;; merge-branch-handler  (POST /api/branches/:ref/merge)
;; =============================================================================

(deftest merge-branch-target-missing
  (let [body (json-body (gh/via :merge-branch-handler
                                (json-req (str "/api/branches/no-target-"
                                               (random-uuid) "/merge")
                                          {:source "main"})))]
    (is (not (:ok body)))
    (is (re-find #"Target branch not found" (:error body)))))


(deftest merge-branch-source-field-missing
  (let [body (json-body (gh/via :merge-branch-handler
                                (json-req "/api/branches/main/merge" {})))]
    (is (not (:ok body)))
    (is (re-find #":source|source" (:error body)))))


(deftest merge-branch-source-not-found
  (let [body (json-body (gh/via :merge-branch-handler
                                (json-req "/api/branches/main/merge"
                                          {:source (str "no-such-" (random-uuid))})))]
    (is (not (:ok body)))
    (is (re-find #"Source branch not found" (:error body)))))


(deftest merge-branch-same-source-target-rejected
  (let [body (json-body (gh/via :merge-branch-handler
                                (json-req "/api/branches/main/merge"
                                          {:source "main"})))]
    (is (not (:ok body)))
    (is (re-find #"must differ" (:error body)))))


(deftest merge-branch-happy
  ;; Merge a uniquely-named branch INTO another uniquely-named target
  ;; (not "main"), so sibling deftests' state doesn't accumulate on
  ;; main and trigger merge conflicts.
  (let [fn-name (uniq "shared")
        fn-id (mk-fn! fn-name)
        target (mk-branch! (uniq "tgt"))
        feat (mk-branch! (uniq "feat") (:name target))
        on-feat (vs/switch-branch *storage* (java.util.UUID/fromString (:id feat)))]
    (sp/update-entity on-feat :fn fn-id
                      {:name fn-name :description "new on feat"})
    (let [body (json-body (gh/via :merge-branch-handler
                                  (json-req (str "/api/branches/"
                                                 (:name target) "/merge")
                                            {:source (:name feat)})))]
      (is (:ok body))
      (is (string? (-> body :merge :id)))
      (is (= (:id feat) (-> body :merge :source-branch-id)))
      (is (string? (-> body :merge :target-branch-id)))
      (is (string? (-> body :merge :created-at))))))


(deftest merge-branch-bad-resolutions-silently-dropped
  ;; Unknown choice / non-UUID id / unknown entity-name are silently
  ;; skipped (mirrors merge.clj's `case` matcher). The merge still
  ;; happens because the resolutions don't apply.
  (let [fn-name (uniq "shared")
        fn-id (mk-fn! fn-name)
        target (mk-branch! (uniq "tgt"))
        feat (mk-branch! (uniq "feat") (:name target))
        on-feat (vs/switch-branch *storage* (java.util.UUID/fromString (:id feat)))]
    (sp/update-entity on-feat :fn fn-id
                      {:name fn-name :description "edited"})
    (let [body (json-body
                 (gh/via :merge-branch-handler
                         (json-req (str "/api/branches/" (:name target) "/merge")
                                   {:source (:name feat)
                                    :conflict-resolutions
                                    [{:entity-name "fn"
                                      :entity-id "not-a-uuid"
                                      :choice "source"}
                                     {:entity-name "fn"
                                      :entity-id (str fn-id)
                                      :choice "elsewhere"}]})))]
      (is (:ok body) "bad resolutions don't break the merge"))))
