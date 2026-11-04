(ns ^:integration ^:serial graphden.crud.branches-graph-test
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
   per JVM (via `gh/graph-fixture`, which clones the GOLDEN template —
   ~100 ms — rather than re-running the full sync) against a fresh
   VERSIONED storage and invoke each handler through `cr/execute`,
   the same code path the `:list-branches-handler` Ring handler
   reaches in production. Each deftest opens a fresh storage so
   branch / fn rows from neighbouring tests don't collide."
  (:require
    [cheshire.core :as cheshire]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities.list :as entity-list]
    [graphden.executor.test-setup :as setup]
    [graphden.lint.graph :as graph-lint]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.graph-harness :as gh :refer [*graph* json-req uniq]]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.diff-view :as dv]
    [graphden.versioning.storage.merge :as mrg]))


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
      (is (false? (:restore-needed? v)) "current fields cannot be restored to themselves")
      (is (zero? (:execution-count v))
          "no executions yet → count 0")
      (is (string? (:id v)) "uuids stringified")
      (is (string? (:branch-id v)))
      (is (string? (:created-at v))))))


(deftest fn-history-diff-handler-shows-a-versioned-field
  (let [fn-id (mk-fn! (uniq "history-diff"))
        branch-id (vs/current-branch-id *storage*)
        base (vs/unwrap *storage*)
        before (:change-revision
                 (first (sp/query-entities base :fn-version {:fn-id fn-id})))]
    (sp/update-entity *storage* :fn fn-id {:description "new description"})
    (let [after (apply max (map :change-revision
                                (sp/query-entities base :fn-version {:fn-id fn-id})))
          uri (str "/api/fns/" fn-id "/history/diff?branch-id=" branch-id
                   "&from=" before "&to=" after)
          body (json-body (gh/via :fn-history-diff-handler (get-req uri)))]
      (is (:ok body))
      (is (:complete? body))
      (is (some #(and (= "fn" (:kind %))
                      (some (fn [field]
                              (and (= "description" (:field field))
                                   (= "new description" (:after field))))
                            (:fields %)))
                (:entries body))))))


(deftest fn-history-diff-handler-conceals-unreadable-and-invalid-targets
  (let [branch-id (vs/current-branch-id *storage*)
        url (fn [fn-id from]
              (str "/api/fns/" fn-id "/history/diff?branch-id=" branch-id
                   "&from=" from "&to=1"))]
    (is (false? (:ok (json-body
                       (gh/via :fn-history-diff-handler
                               (get-req (url (random-uuid) 1)))))))
    (is (false? (:ok (json-body
                       (gh/via :fn-history-diff-handler
                               (get-req (url (random-uuid) "bad")))))))))


(deftest fn-history-restore-handlers-preview-and-commit
  (let [fn-id (mk-fn! (uniq "history-restore"))
        branch-id (vs/current-branch-id *storage*)
        base (vs/unwrap *storage*)
        revision (:change-revision
                   (first (sp/query-entities base :fn-version {:fn-id fn-id})))
        path (str "/api/fns/" fn-id "/history/restore")]
    (sp/update-entity *storage* :fn fn-id {:description "later"})
    (let [preview! #(json-body
                      (gh/via :fn-history-restore-preview-handler
                              (get-req (str path "/preview?branch-id=" branch-id
                                            "&revision=" revision))))
          commit! #(gh/via :fn-history-restore-commit-handler
                           (json-req path {:branch-id (str branch-id)
                                           :revision revision
                                           :expected-current %}))
          preview (preview!)]
      (is (:ok preview))
      (is (= "ready" (:status preview)))
      (is (seq (:changes preview)))
      (sp/update-entity *storage* :fn fn-id {:description "newer"})
      (let [stale (commit! (:expected-current preview))]
        (is (= 409 (:status stale)))
        (is (false? (:ok (json-body stale))))
        (is (= "history/stale-current"
               (:reason (json-body stale)))))
      (let [commit (json-body (commit! (:expected-current (preview!))))]
        (is (:ok commit))
        (is (= "committed" (:status commit)))
        (is (nil? (:description (sp/read-entity *storage* :fn fn-id))))))))


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
      (is (= "main" (-> body :versions second :branch-name)))
      (is (true? (-> body :versions first :restore-needed?))
          "another branch's changed fields can be restored on main")
      (is (false? (-> body :versions second :restore-needed?))
          "global newest does not define the current state of main"))))


(deftest fn-version-restore-state-ignores-name-and-matches-older-equal-fields
  (let [fn-id (mk-fn! (uniq "restore-fields"))
        versions #(-> (gh/via :list-fn-versions-handler
                              (get-req (str "/api/fns/" fn-id "/versions")))
                      json-body :versions)]
    (sp/update-entity *storage* :fn fn-id {:description "first"})
    (sp/update-entity *storage* :fn fn-id {:description "other"})
    (sp/update-entity *storage* :fn fn-id {:description "first"})
    (sp/update-entity *storage* :fn fn-id {:name (uniq "renamed")})
    (let [rows (versions)
          first-states (filter #(= "first" (:description %)) rows)
          other-state (first (filter #(= "other" (:description %)) rows))]
      (is (= 3 (count first-states)))
      (is (every? #(false? (:restore-needed? %)) first-states)
          "Restore does not rewrite identity labels or create duplicate field states")
      (is (true? (:restore-needed? other-state))))))


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


(deftest delete-branch-releases-derived-editor-snapshots
  (let [branch (mk-branch! (uniq "cache-lifecycle"))
        id (parse-uuid (:id branch))
        ctx (assoc (:ctx *graph*) :storage (vs/switch-branch *storage* id)
                   :graph-cache (atom nil))
        lint-cache @(ns-resolve 'graphden.lint.graph 'memo)
        tree-cache @(ns-resolve 'graphden.crud.entities.list 'tree-kinds-memo)
        lint-entry? #(some (fn [[[_ bid _] _]] (= id bid)) @lint-cache)]
    (try
      (graph-lint/lint-branch ctx #{})
      (entity-list/graph-tree ctx)
      (is (lint-entry?))
      (is (contains? @tree-cache id))
      (let [body (json-body (gh/via :delete-branch-handler
                                    (delete-req (str "/api/branches/" (:name branch)))))]
        (is (:ok body))
        (is (not (lint-entry?)))
        (is (not (contains? @tree-cache id))))
      (finally
        (graph-lint/forget-branch! id)
        (entity-list/forget-branch! id)))))


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


(deftest merge-branch-carries-inherited-content-transitively
  ;; A stacked branch (S.base = R, R edited) merged into R's base lands
  ;; R's change too: the handler merges R in first and says so in the
  ;; body (`merged-first`). The HTTP-level twin of merge core_test's
  ;; `merge-transitively-carries-inherited-content-test`; the storage
  ;; guard a direct `mrg/merge-branch!` still raises is pinned by
  ;; versioning core_test's `merge-refuses-to-silently-drop-…`.
  (let [fn-name (uniq "stack")
        fn-id (mk-fn! fn-name)
        target (mk-branch! (uniq "tgt"))
        r (mk-branch! (uniq "r") (:name target))
        on-r (vs/switch-branch *storage* (java.util.UUID/fromString (:id r)))
        _ (sp/update-entity on-r :fn fn-id {:name fn-name :description "edited on R"})
        s (mk-branch! (uniq "s") (:name r))
        resp (gh/via :merge-branch-handler
                     (json-req (str "/api/branches/" (:name target) "/merge")
                               {:source (:name s)}))
        body (json-body resp)
        on-target (vs/switch-branch *storage* (java.util.UUID/fromString (:id target)))]
    (is (= 200 (:status resp)))
    (is (true? (:ok body)))
    (is (= [(:name r)] (mapv :name (:merged-first body))) "R was merged in first")
    (is (= [(:id r)] (mapv :id (:merged-first body))) "each step carries the id-safe ref")
    (is (= "edited on R" (:description (sp/read-entity on-target :fn fn-id)))
        "R's inherited change reached the target through S's merge")))


(deftest archive-route-folds-a-branch-away-and-back
  ;; POST /api/branches/:ref/archive — `archived-at` set / cleared; the
  ;; JSON list carries it; the popover folds archived rows under the
  ;; collapsed "Merged" group and keeps active rows in the listbox.
  (let [b (mk-branch! (uniq "arch"))
        list-branch (fn []
                      (->> (json-body (gh/via :list-branches-handler (get-req "/api/branches")))
                           :branches (filter #(= (:name b) (:name %))) first))
        popover (fn [] (:body (gh/via :_partial-branch-popover-handler (get-req "/partials/branch-popover"))))]
    (is (nil? (:archived-at (list-branch))) "a fresh branch is active")
    (is (str/includes? (popover) ">Archive<") "the ⋯ menu offers Archive on an active row")
    (let [resp (gh/via :set-branch-archive-handler
                       (json-req (str "/api/branches/" (:name b) "/archive") {}))
          body (json-body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:archived body))))
    (is (string? (:archived-at (list-branch))) "the list shows when it was folded away")
    (let [html (popover)
          archived-part (subs html (String/.indexOf ^String html "branch-archived"))]
      (is (str/includes? html "class=\"branch-archived\"") "the Merged group renders")
      (is (str/includes? archived-part (str "data-branch-name=\"" (:name b) "\""))
          "the folded row lives in the Merged group")
      (is (str/includes? archived-part "data-archived=\"1\""))
      (is (str/includes? archived-part "class=\"branch-row-archive\" data-archive-branch=")
          "the ⋯ menu offers Reopen on a folded row")
      (is (str/includes? archived-part ">Reopen<"))
      (is (not (str/includes? (subs html 0 (String/.indexOf ^String html "branch-archived"))
                              (str "data-branch-name=\"" (:name b) "\"")))
          "...and not in the active listbox"))
    (let [body (json-body (gh/via :set-branch-archive-handler
                                  (json-req (str "/api/branches/" (:name b) "/archive") {:archived false})))]
      (is (false? (:archived body))))
    (is (nil? (:archived-at (list-branch))) "opening it brings it back")
    (testing "an unknown ref is a 404 envelope"
      (is (false? (:ok (json-body (gh/via :set-branch-archive-handler
                                          (json-req "/api/branches/no-such-branch-xyz/archive" {})))))))))


(deftest merge-into-own-base-archives-the-source
  ;; The source landed into ITS base: it is done and cannot be deleted
  ;; (the target resolves through it), so the merge folds it away. A
  ;; merge into a branch that is NOT the source's base leaves it active.
  (let [fn-name (uniq "arch-merge")
        fn-id (mk-fn! fn-name)
        target (mk-branch! (uniq "tgt"))
        feat (mk-branch! (uniq "feat") (:name target))
        sibling (mk-branch! (uniq "sib") (:name target))
        on-feat (vs/switch-branch *storage* (java.util.UUID/fromString (:id feat)))
        _ (sp/update-entity on-feat :fn fn-id {:name fn-name :description "edited on feat"})
        row (fn [b] (sp/read-entity *storage* :branch (java.util.UUID/fromString (:id b))))]
    (testing "into a sibling: source stays active"
      (is (true? (:ok (json-body (gh/via :merge-branch-handler
                                         (json-req (str "/api/branches/" (:name sibling) "/merge")
                                                   {:source (:name feat)}))))))
      (is (nil? (:archived-at (row feat)))))
    (testing "into its own base: source is archived"
      (is (true? (:ok (json-body (gh/via :merge-branch-handler
                                         (json-req (str "/api/branches/" (:name target) "/merge")
                                                   {:source (:name feat)}))))))
      (is (some? (:archived-at (row feat)))))))


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


(deftest diff-view-graph-formats-labels-with-one-read
  (let [source (random-uuid)
        target (random-uuid)
        named "11111111-1111-4111-8111-111111111111"
        anon "22222222-2222-4222-8222-222222222222"
        ;; The formatter sees only the same bounded display entry as before,
        ;; including ref IDs and any secret marker already in that entry.
        entry {:entity-name :binding :entity-id "binding" :change :modified
               :source-ref named :target-ref anon :slot-name "in"
               :fields [{:field "value" :source "{:secret true}" :target "∅"}]}
        row {:fn-id named :fn-name "owner" :assets-only? false
             :ns-path "app" :branch-local? true :entry entry}
        cases [[row ":owner"]
               [(assoc row :fn-name "") ":"]
               [(assoc row :fn-id anon :fn-name nil) "#22222222"]
               [(assoc row :fn-id nil :fn-name nil :assets-only? true) "(assets)"]
               [(assoc row :fn-id nil :fn-name nil) "(unowned)"]]]
    (doseq [rows [(mapv first cases) [] (vec (repeat 128 row))]]
      (let [calls (atom 0)
            input {:source-branch-id source :target-branch-id target
                   :count (count rows) :rows rows}
            result (with-redefs [dv/diff-branches-view-facts
                                 (fn [_ s t]
                                   (is (= [source target] [s t]))
                                   (swap! calls inc)
                                   input)]
                     (gh/exec-name :diff-branches-view
                                   {:source-branch-id source :target-branch-id target}))
            labels (if (= 5 (count rows)) (mapv second cases)
                       (vec (repeat (count rows) ":owner")))]
        (is (= 1 @calls) "one shared acquisition even for a large row batch")
        (is (= (dissoc input :rows) (dissoc result :rows)))
        (is (= labels (mapv :fn-label (:rows result))))
        (is (= (mapv #(dissoc % :assets-only?) rows)
               (mapv #(dissoc % :fn-label) (:rows result)))
            "entry fields, reference IDs, order and existing marker text are preserved")))))


(deftest diff-view-handler-uses-graph-owner-labels
  (let [fn-name (uniq "diff-label")
        fn-id (mk-fn! fn-name)
        branch (mk-branch! (uniq "diff-label-branch"))
        on-branch (vs/switch-branch *storage* (parse-uuid (:id branch)))]
    (sp/update-entity on-branch :fn fn-id {:description "label parity"})
    (let [response (gh/via :diff-view-handler
                           (get-req (str "/api/branches/main/diff-view?against=" (:id branch))))
          body (json-body response)
          group (some #(when (= (str fn-id) (:fn-id %)) %) (:groups body))]
      (is (= 200 (:status response)))
      (is (:ok body))
      (is (= (str ":" fn-name) (:fn-label group)))
      (is (some #(some (fn [field] (= "label parity" (:source field))) (:fields %))
                (:entries group))))))


(deftest merge-skipped-graph-projects-and-stably-sorts-one-bulk-read
  (let [source (random-uuid)
        rows [{:id (random-uuid) :name "z" :unrelated "not audit data"}
              {:id (random-uuid) :name nil}
              {:id (random-uuid) :name ""}
              {:id (random-uuid) :name "a"}
              {:id (random-uuid) :name "a"}]]
    (doseq [facts [[] rows (vec (mapcat identity (repeat 32 rows)))]]
      (let [calls (atom 0)
            result (with-redefs [mrg/branch-local-source-rows
                                 (fn [_ source-id]
                                   (is (= source source-id))
                                   (swap! calls inc)
                                   facts)]
                     (gh/exec-name :merge-skipped-branch-local {:source-branch-id source}))
            expected (->> facts
                          (map (fn [row] {:entity-name :fn :entity-id (:id row) :fn-name (:name row)}))
                          (sort-by #(or (:fn-name %) ""))
                          vec)]
        (is (= 1 @calls) "one acquisition independent of batch size")
        (is (vector? result))
        (is (= expected result) "exact projection, stable ties, duplicates and nil names")))))


(deftest merge-skipped-graph-retains-effective-inheritance-and-audit-envelope
  (let [parent (sp/create-entity *storage* :fn {:id (random-uuid) :name (uniq "local-parent")
                                                :parent-ids [] :branch-local? true})
        child (sp/create-entity *storage* :fn {:id (random-uuid) :name (uniq "local-child")
                                               :parent-ids [(:id parent)]})
        ordinary (mk-fn! (uniq "ordinary"))
        target (mk-branch! (uniq "audit-target"))
        source (mk-branch! (uniq "audit-source") (:name target))
        source-id (parse-uuid (:id source))
        on-source (vs/switch-branch *storage* source-id)]
    (doseq [id [(:id parent) (:id child) ordinary]]
      (sp/update-entity on-source :fn id {:description "source change"}))
    ;; More than one version for a single identity must still produce one row.
    (sp/update-entity on-source :fn (:id child) {:description "second change"})
    (let [expected (->> [parent child]
                        (map (fn [row] {:entity-name :fn :entity-id (:id row) :fn-name (:name row)}))
                        (sort-by :fn-name) vec)]
      (is (= expected (gh/exec-name :merge-skipped-branch-local {:source-branch-id source-id})))
      (let [body (json-body (gh/via :merge-branch-handler
                                    (json-req (str "/api/branches/" (:name target) "/merge")
                                              {:source (:name source)})))]
        (is (:ok body))
        (is (= {:branch-local (mapv #(-> % (update :entity-name name) (update :entity-id str)) expected)}
               (get-in body [:merge :skipped])))))))


(deftest diff-affected-graph-enriches-shared-bfs-facts-with-visible-paths
  (let [a #uuid "11111111-0000-4000-8000-000000000001"
        b #uuid "22222222-0000-4000-8000-000000000002"
        shared (random-uuid)
        child (random-uuid)
        hidden (random-uuid)
        root-ns (random-uuid)
        leaf-ns (random-uuid)
        unrelated-ns (random-uuid)
        reverse-deps {a #{shared hidden} b #{shared} shared #{child}}
        acquire dv/affected-view-facts]
    (doseq [groups [[] [{:fn-id (str b)} {:fn-id (str a)} {:fn-id (str a)}
                        {:fn-id nil} {:fn-id "junk"}]]]
      (let [calls (atom [])
            unexpected (fn [op]
                         (throw (ex-info "Unexpected storage call in diff affected test"
                                         {:op op})))
            storage (reify
                      sp/StorageCRUD
                      (query-entities
                        [_ entity where]
                        (swap! calls conj [:query entity where])
                        (is (= :ns entity))
                        [{:id root-ns :name "app"}
                         {:id leaf-ns :name "visible" :parent-id root-ns}
                         {:id unrelated-ns :name "unrelated"}])

                      (query-entities [_ _ _ _] (unexpected :query-entities-opts))

                      (create-entity [_ _ _] (unexpected :create-entity))

                      (read-entity [_ _ _] (unexpected :read-entity))

                      (update-entity [_ _ _ _] (unexpected :update-entity))

                      (delete-entity [_ _ _] (unexpected :delete-entity))

                      (query-latest-per-group [_ _ _ _] (unexpected :query-latest-per-group))


                      sp/StorageBatchCRUD

                      (read-entities
                        [_ entity ids]
                        (swap! calls conj [:bulk entity (set ids)])
                        (is (= :fn entity))
                        ;; ACL-filtered batch omits hidden; its namespace path
                        ;; must remain nil even though the dependency ID is known.
                        (select-keys {shared {:id shared :namespace-id leaf-ns}
                                      child {:id child :namespace-id unrelated-ns}}
                                     ids))

                      (create-entities [_ _ _] (unexpected :create-entities))

                      (update-entities [_ _ _] (unexpected :update-entities))

                      (upsert-entities [_ _ _] (unexpected :upsert-entities))

                      (delete-entities [_ _ _] (unexpected :delete-entities))

                      (query-ref-many-owners [_ _ _ _] (unexpected :query-ref-many-owners)))
            acquisitions (atom 0)
            result (with-redefs [dv/affected-view-facts
                                 (fn [_ _ supplied-groups]
                                   (swap! acquisitions inc)
                                   (is (= groups supplied-groups))
                                   (acquire storage reverse-deps supplied-groups))]
                     (gh/exec-name :diff-affected {:groups groups}))]
        (is (= 1 @acquisitions))
        (if (empty? groups)
          (do (is (= {} result)) (is (= [] @calls)))
          (do
            (is (= {(str shared) {:via (str a) :depth 1 :ns-path "app.visible"}
                    (str child) {:via (str a) :depth 2 :ns-path "unrelated"}
                    (str hidden) {:via (str a) :depth 1 :ns-path nil}}
                   result)
                "nearest via uses sorted seed tie-breaking; missing identities disclose no path")
            (is (= [[:bulk :fn #{shared child hidden}] [:query :ns {}]] @calls)
                "one shared identity batch and namespace read, with no per-row calls")))))))
