(ns ^:integration graphden.packages.app.editor-shell-partials-test
  "Executes the editor's shell partials end-to-end against the golden
   DB (shared fixture: `graphden.test-infra.golden-app`):

   - `/partials/execute-popover` — free-arg scaffold from the
     backend's own `:free-arg-slot-map` (replaced the JS `freeArgsOf`
     re-derivation);
   - `/partials/type-name-datalist` — create-type name autocomplete
     (replaced the client-assembled datalist + hand-copied
     primitives);
   - `/partials/row-actions` root-row ⚙ — service-block reason from
     `:service-blocking-free-args` (regression guard);
   - `/partials/compatible-type-options` — one subtype? sweep
     (replaced the per-name /api/types/compatible fan-out);
   - `/partials/expects-effects-form` — canonical category roster;
   - `/partials/branch-verification` — the review dialog's
     \"Verified on this branch\" digest (tests line + runs list)."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.golden-app :as ga]
    [graphden.types.core :as types]
    [hiccup2.core :as hiccup]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- body-of
  [handler-name query-params]
  (let [resp (ga/exec-handler handler-name {:query-params query-params})]
    (is (= 200 (:status resp)))
    (:body resp)))


;; ============================================================================
;; /partials/execute-popover
;; ============================================================================

(deftest pure-fn-with-free-args
  (let [body (body-of :_partial-xp-handler
                      {"fn-id" (str (ga/fn-id :add))})]
    (testing "header names the fn"
      (is (str/includes? body "Run :add")))
    (testing "free-arg host from the backend's free-arg-slot-map"
      (is (str/includes? body "data-slot-name=\"nums\""))
      (is (re-find #"data-slot-id=\"[0-9a-f-]{36}\"" body)))
    (testing "pure fn: no effects banner, Run enabled, persist unlocked"
      (is (not (str/includes? body "execute-effects-warning")))
      (is (not (str/includes? body "execute-confirm-checkbox")))
      (is (not (re-find #"execute-run-btn\"[^>]*disabled" body))))))


(deftest effectful-fn-shell
  ;; `:get-entity` declares `:effects #{:db}` — banner + confirm gate
  ;; + locked persist, Run disabled until the confirm ticks.
  (let [body (body-of :_partial-xp-handler
                      {"fn-id" (str (ga/fn-id :get-entity))})]
    (is (str/includes? body "execute-effects-warning"))
    (is (str/includes? body "effects-chip-db"))
    (is (str/includes? body "execute-confirm-checkbox"))
    ;; hiccup sorts attrs alphabetically: … disabled … title … type.
    (is (re-find #"execute-run-btn\"[^>]*disabled=\"disabled\"" body)
        "Run starts disabled behind the confirm gate")
    (is (str/includes? body "execute-option-label-locked"))))


(deftest optional-free-args-fold-under-a-disclosure
  ;; `:get`: `coll` / `key` are the required holes; `default` is declared
  ;; `:required false`. The optional one folds under "N optional" so the
  ;; rows to fill in are the rows you see; every row keeps
  ;; `.execute-arg-row` + its mount host either way.
  (let [body (body-of :_partial-xp-handler
                      {"fn-id" (str (ga/fn-id :get))})]
    (is (str/includes? body "data-slot-name=\"coll\""))
    (is (str/includes? body "execute-optional-args"))
    (is (re-find #"<summary[^>]*>1 optional</summary>" body))
    (is (str/includes? body "data-slot-name=\"default\""))
    (testing "the required rows come BEFORE the fold (auto-focus + the tour helpers take the first field)"
      (is (< (str/index-of body "data-slot-name=\"coll\"")
             (str/index-of body "execute-optional-args"))))
    (testing "an HTTP template's knobs fold: `url` is the row, the rest are optional"
      (let [body (body-of :_partial-xp-handler
                          {"fn-id" (str (ga/fn-id :http-get))})]
        (is (re-find #"<summary[^>]*>4 optional</summary>" body))
        (is (< (str/index-of body "data-slot-name=\"url\"")
               (str/index-of body "execute-optional-args")))))
    (testing "a fn with only required holes renders no fold"
      (is (not (str/includes? (body-of :_partial-xp-handler
                                       {"fn-id" (str (ga/fn-id :add))})
                              "execute-optional-args"))))))


(deftest inspector-names-the-captures-inside-hof-targets
  ;; `:web-server` reads nothing itself; what its handler tree reads from
  ;; the caller is a closure capture — the inspector lists each with the
  ;; HOF target it is read inside, optional ones flagged as not on the
  ;; card (the canvas draws only required captures).
  (let [body (body-of :_partial-inspector-detail-handler
                      {"fn-id" (str (ga/fn-id :web-server))})]
    (is (str/includes? body "gd-insp-captured"))
    (is (str/includes? body "read inside <b>"))
    (testing "a leaf fn without captures has no such section"
      (is (not (str/includes? (body-of :_partial-inspector-detail-handler
                                       {"fn-id" (str (ga/fn-id :add))})
                              "gd-insp-captured"))))))


(deftest execute-history-secret-flows-chip
  ;; The rollup strip's chip and the row filter, executed as fn-defs
  ;; (the whole panel also reads the usage rollups, which the golden
  ;; fixture does not carry): `?secrets=1` renders the chip active and
  ;; narrows the rows to the audit-trail ones.
  (let [{:keys [ctx storage]} ga/*bootstrap*
        run (fn [fn-name request]
              (setup/exec-with-storage ctx storage (ga/fn-id fn-name)
                                       {:request request
                                        :fn-id (ga/fn-id :add)}))
        plain-req {:query-params {"fn-id" (str (ga/fn-id :add))}}
        only-req {:query-params {"fn-id" (str (ga/fn-id :add)) "secrets" "1"}}
        plain (str (hiccup/html (run :_peh-secrets-toggle plain-req)))
        only (str (hiccup/html (run :_peh-secrets-toggle only-req)))]
    (is (str/includes? plain "execute-history-secrets-toggle"))
    (is (not (str/includes? plain "execute-history-secrets-toggle-active")))
    (is (str/includes? plain "data-secrets=\"0\""))
    (is (str/includes? only "execute-history-secrets-toggle-active"))
    (is (str/includes? only "data-secrets=\"1\""))
    (testing "the row filter keeps only touched-secret rows"
      (is (= [] (run :_partial-exec-hist-rows only-req))
          "no run of :add ever touched a secret"))
    (testing "the whole panel renders through its handler, chip and all (the golden carries usage-stat)"
      (let [body (body-of :_partial-execute-history-handler
                          {"fn-id" (str (ga/fn-id :add)) "secrets" "1"})]
        (is (str/includes? body "execute-history-panel"))
        (is (str/includes? body "execute-history-secrets-toggle-active"))
        (is (str/includes? body "execute-history-stats"))))))


(deftest missing-or-unknown-fn-id-degrades
  ;; `parse-uuid` swallows malformed input to nil; the shell degrades
  ;; to the anonymous header instead of erroring.
  (doseq [params [{} {"fn-id" "not-a-uuid"}]]
    (let [body (body-of :_partial-xp-handler params)]
      (is (str/includes? body "Run :(anonymous)"))
      (is (str/includes? body "execute-no-args-note")))))


;; ============================================================================
;; /partials/type-name-datalist
;; ============================================================================

(deftest type-name-datalist
  (let [body (body-of :_partial-tnd-handler {})]
    (is (str/includes? body "id=\"type-create-typename-list\""))
    (testing "primitives present with the primitive label"
      (is (str/includes? body "value=\"int\""))
      (is (str/includes? body "label=\"primitive\"")))
    (testing "a named refinement type-row present with its kind"
      (is (str/includes? body "value=\"positive-int\""))
      (is (str/includes? body "label=\"refinement\"")))))


;; ============================================================================
;; /partials/row-actions — root-row ⚙ service-block reason
;; ============================================================================

(deftest row-actions-service-block-reason-server-computed
  ;; The root-row context's ⚙ disabled-with-reason state comes from
  ;; `:service-blocking-free-args` INSIDE the partial now — no
  ;; `service-blocked-reason` query param. Regression guard for the
  ;; window where the client's deleted `freeArgsOf` mirror left the
  ;; reason permanently null.
  (let [render (fn [fn-name]
                 (body-of :_partial-row-actions-handler
                          {"fn-id" (str (ga/fn-id fn-name))
                           "context" "root-row"}))]
    ;; hiccup escapes the apostrophe (`Can&apos;t`) — match past it.
    (testing "fn with service-blocking free args → disabled + reason"
      (let [body (render :add)]
        (is (str/includes? body "make a service — fn has free args: :nums"))
        (is (str/includes? body "action-icon-disabled"))))
    (testing "startable fn → enabled, no reason"
      (let [body (render :web-server)]
        (is (not (str/includes? body "make a service")))))))


;; ============================================================================
;; /partials/compatible-type-options
;; ============================================================================

(deftest compatible-type-options-partial
  ;; One server-side subtype? sweep replaces the editor's per-name
  ;; /api/types/compatible fan-out. expected=:int → primitives mode
  ;; includes :int itself; type-row mode lists only named narrowings
  ;; (e.g. :positive-int, :port); `current` is excluded server-side.
  (testing "primitives included on demand"
    (let [body (body-of :_partial-cto-handler
                        {"expected" "\"int\"" "primitives" "true"})]
      (is (str/includes? body "value=\"int\""))
      (is (str/includes? body "value=\"positive-int\""))
      (is (not (str/includes? body "value=\"text\"")))))
  (testing "type-rows only by default + current excluded"
    (let [body (body-of :_partial-cto-handler
                        {"expected" "\"int\"" "current" "positive-int"})]
      (is (not (str/includes? body "value=\"int\"")))
      (is (not (str/includes? body "value=\"positive-int\"")))
      (is (str/includes? body "value=\"port\""))))
  (testing "no compatible types and no current → placeholder"
    (let [body (body-of :_partial-cto-handler {"expected" "\"never\""})]
      (is (str/includes? body "no compatible types"))))
  (testing "PRESENT-but-malformed `expected` JSON degrades to the empty-select placeholder, not a 500"
    ;; `%7B` / a bare `{` is present but not valid JSON — `:parse-json`
    ;; throws, the `:try` in `:_cto-expected` swallows it to nil, and the
    ;; fragment renders the placeholder. `body-of` asserts status 200.
    (let [body (body-of :_partial-cto-handler {"expected" "{"})]
      (is (str/includes? body "no compatible types")))))


;; ============================================================================
;; /partials/expects-effects-form
;; ============================================================================

(deftest expects-effects-form-partial
  ;; The category roster comes from the canonical
  ;; known-effect-categories set — the old client grid listed six and
  ;; made :process / :raw-sql undeclarable through the UI.
  (testing "no-contract fn: none-mode checked, full roster disabled"
    (let [body (body-of :_partial-eef-handler
                        {"fn-id" (str (ga/fn-id :add))})]
      ;; Roster = the FULL canonical vocabulary — every recordable
      ;; category is declarable (the :state recordable-but-
      ;; undeclarable split is the bug this pins against).
      (doseq [category (sort (map name types/known-effect-categories))]
        (is (str/includes? body (str "value=\"" category "\""))
            (str category " offered")))
      (is (re-find #"checked=\"checked\"[^>]*value=\"none\"" body)
          "no-contract mode pre-selected (hiccup sorts attrs alphabetically)")
      (is (str/includes? body "disabled")
          "checkboxes disabled while no contract"))))


(deftest effect-descriptions-cover-the-vocabulary
  ;; `:_effect-descriptions` (graph) must describe EXACTLY the
  ;; canonical vocabulary — a recordable category without a row falls
  ;; to the generic popover text (`:state` was exactly that), and a
  ;; stale row would document a retired category. Pure EDN read, no
  ;; HTTP.
  (let [table (->> (io/resource "packages/app/editor/fns.edn")
                   slurp edn/read-string :fns
                   (some #(when (= :_effect-descriptions (:name %)) %)))
        keys* (set (map keyword (keys (get-in table [:args :value]))))]
    (is (= types/known-effect-categories keys*)
        "one row per canonical effect category, no extras")))


(deftest every-rule-owner-has-a-narrative
  ;; Audit-3 drift guard: the narrative roster is hand-maintained; a
  ;; NEW rule (or a newly-polymorphic base-fn declaration) added
  ;; without a narrative previously passed both hand-side checks. The
  ;; golden registry is live here, so derive the owner set the exact
  ;; way registry/rule-owner-of does — base-fn entries (no
  ;; :primary-parent) carrying a hand :return-type-rule or a
  ;; var-carrying :return — and require narrative coverage for each.
  (let [narratives (->> (io/resource "packages/app/editor-provenance/fns.edn")
                        slurp edn/read-string :fns
                        (some #(when (= :_rtr-narratives (:name %)) %))
                        :args :value keys (map keyword) set)
        owners (into #{}
                     (keep (fn [[n entry]]
                             (when (and (nil? (:primary-parent entry))
                                        (or (:return-type-rule entry)
                                            (types/type-any? types/type-var?
                                                             (:return entry))))
                               n)))
                     (registry/rich-types-snapshot))]
    (is (seq owners) "derived owner set is non-empty")
    (doseq [o owners]
      (is (contains? narratives o)
          (str o " owns a return narrowing but has no narrative")))))


;; ============================================================================
;; static scaffold sanity
;; ============================================================================

(deftest static-scaffold-parts
  (let [body (body-of :_partial-xp-handler {"fn-id" (str (ga/fn-id :add))})]
    (doseq [marker ["execute-popover-header" "execute-popover-body"
                    "execute-options-row" "execute-action-bar"
                    "execute-run-btn" "execute-cancel-btn"
                    "execute-result-host"]]
      (is (str/includes? body marker) (str marker " present")))
    ;; The pane lives in the inspector's Runs tab now — no close button,
    ;; and the history list is mounted below the form by the client, not
    ;; toggled from the header.
    (doseq [marker ["execute-popover-close" "execute-history-toggle"
                    "execute-history-host"]]
      (is (not (str/includes? body marker)) (str marker " retired")))))


(deftest branch-verification-digest
  (testing "the digest renders on a branch with nothing run: tests line + the empty-runs note"
    (let [body (body-of :_partial-branch-verification-handler {})]
      (is (str/includes? body "class=\"bd-verif\""))
      (is (re-find #"Tests: \d+ on this branch — \d+ passed, \d+ failed, \d+ never ran" body))
      (is (or (str/includes? body "bd-verif-runs") (str/includes? body "bd-verif-empty"))
          "either the runs list or its empty note"))))
