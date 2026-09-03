(ns graphden.packages.app.editor-panels.impls
  "Impls for the sidebar panel partials. One thin boundary defbase —
   the type-diagnostics read joins the in-memory per-branch store
   (`graphden.types.diagnostics`) with fn names from storage; all
   rendering is graph fn-defs in `fns.edn`."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.lint.graph :as lint-graph]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(defbase branch-diagnostics-flat
  ;; Error-tolerance Phase 3 — the CURRENT branch's recorded type
  ;; diagnostics joined with fn names, one entry per diagnostic:
  ;; `{:fn-id <str> :fn-name <str-or-nil> :diag <d>}`. The store is
  ;; derived in-memory state ({fn-id [diagnostic …]} per branch,
  ;; re-recorded by every post-write check), so the only storage
  ;; round-trip is the batched fn-name join. Branch comes from the
  ;; request's versioned storage (nil = default branch), same as the
  ;; post-write recorder. The DISPLAY reshape (row keys, arg
  ;; coalescing, uuid-name fallback, sort) is graph composition —
  ;; `:type-diagnostics-list` in fns.edn.
  []
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        errs (diag/branch-errors (vs/current-branch-id storage))
        fn-ids (vec (keys errs))
        fn-rows (when (seq fn-ids) (sp/read-entities storage :fn fn-ids))]
    (->> errs
         ;; DROP diagnostics whose fn the org-scoped read didn't return.
         ;; The store is keyed branch×fn with no org dimension, and on a
         ;; multi-tenant pod every org shares the default branch — so a
         ;; foreign org's fn-ids land in the same bucket. Emitting them
         ;; (even UUID-named) leaks the diagnostic body (expected/actual
         ;; types, source file/line) across orgs. An own-org fn ALWAYS
         ;; joins (the recorder ran under this same scoped storage), so
         ;; nothing legitimate is lost. An anonymous own fn joins too —
         ;; its `:fn-name` is nil; the uuid label is graph composition.
         ;; This drop is a SECURITY boundary — it stays impl-side,
         ;; adjacent to the join, never graph-reachable to skip.
         (keep (fn [[fn-id diags]]
                 (when-let [row (get fn-rows fn-id)]
                   [fn-id (:name row) diags])))
         (mapcat (fn [[fn-id fn-name diags]]
                   (map (fn [d]
                          {:fn-id (str fn-id)
                           :fn-name fn-name
                           :diag d})
                        diags)))
         vec)))


(defbase request-capabilities
  ;; The tenancy addon's per-request capability list (the same strings it
  ;; stamps into `X-Graphden-Capabilities`), read off the core seam and
  ;; coerced to a membership map (`{"write" true …}`) so graph composition
  ;; can `:get` individual capabilities. nil = no addon / single-tenant —
  ;; the Settings access card renders its single-tenant copy on that.
  []
  (when-let [caps (tc/current-capabilities)]
    (zipmap caps (repeat true))))


(defbase branch-lint-warnings
  ;; The graph lint over the CURRENT branch — one `lint.graph/lint-branch`
  ;; call over the request's graph snapshot, minus the entries the
  ;; author marked as not-an-issue (`suppressed`: the `lint-suppressions`
  ;; const's value, `[{:rule :fn-ids} …]` as the graph stores it). Each
  ;; finding comes back as a display row: the rule + message, the member
  ;; fns (id / name / namespace — anonymous rows get their `_anon-` label)
  ;; and the ids joined for the suppress / restore URLs. Warnings only:
  ;; the engine's info tier is calibration, not a problem to show.
  ;; `fresh` — read the branch straight from storage (the drawer, opened
  ;; right after an edit) instead of the per-ctx snapshot (the inspector,
  ;; on every selection); see `lint.graph/lint-branch`.
  [suppressed fresh]
  (cr/record-effect! :db)
  (let [suppress (into #{}
                       (map (fn [e]
                              [(keyword (:rule e))
                               (vec (sort (map str (:fn-ids e))))]))
                       suppressed)]
    (mapv (fn [{:keys [rule message weight fns fn-ids]}]
            {:rule (name rule)
             :message message
             :weight weight
             :fn-ids (mapv str fn-ids)
             :fn-ids-csv (str/join "," (map str fn-ids))
             :fns (mapv (fn [[nsp n] id] {:id (str id) :name (name n) :ns nsp})
                        fns fn-ids)})
          (lint-graph/lint-branch ctx suppress {:fresh? (boolean fresh)}))))


(def impls
  {:branch-diagnostics-flat branch-diagnostics-flat
   :branch-lint-warnings branch-lint-warnings
   :request-capabilities request-capabilities})
