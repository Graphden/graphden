(ns graphden.crud.branches
  "Implementation primitives consumed by the `/api/branches/*` graph
   fn-defs in `resources/packages/app/branches`. Only two helpers
   remain: `base-storage` (used by the read-side `:diff-branches` /
   `:detect-conflicts` base-fns to drop the version wrapper) and
   `resolve-branch-ref` (used by the `:resolve-branch-ref` base-fn).

   Every `apply-*` defn that used to live here was decomposed into
   graph fn-defs over atomic primitives (`:create-branch!` /
   `:delete-branch!` / `:diff-branches` / `:detect-conflicts` /
   `:merge-branch!`) + `:zipmap` / `:as-json-branch` / `:stringify-uuids`
   composition + graph-level `:try` for exception → structured-envelope
   dispatch. See `branches_graph_test.clj` for the equivalence-tested
   call path."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn base-storage
  "Read endpoints always operate on the unwrapped base storage — the
   branch context is encoded in the request path/query, not in the
   wrapper. Throws the same `:execution-error/missing-storage` shape
   as `request/require-storage` when ctx has no storage. Public so
   the `:resolve-branch-ref` base-fn in `app/branches` can use it."
  [ctx]
  (vs/unwrap (request/require-storage ctx)))


(defn resolve-branch-ref
  "Branch lookup that accepts either a stringified UUID or a branch
   name. Returns the row, or nil when not found. Public so the
   `:resolve-branch-ref` base-fn in `app/branches` can delegate
   directly."
  [storage branch-ref]
  (or (some->> (request/parse-uuid-or-clear branch-ref)
               (sp/read-entity storage :branch))
      (when (and (string? branch-ref) (not (str/blank? branch-ref)))
        (first (sp/query-entities storage :branch {:name branch-ref})))))


(def ^:private default-branch-name "main")


(defn- branch-row-hiccup
  "Hiccup for one branch row in the top-bar branch popover.

   `branch` — `{:id :name :base-branch-id}` after `decode-row`. `by-id`
   keys every branch by `:id` so we can resolve `base-branch-id` ⇒
   parent-name without an N+1 lookup AND detect rows that have at
   least one child (used to lock-out delete). `current-name` — name of
   the active branch (drives `branch-row-current` highlight + the
   `(no-op)` switch-on-current dismiss path)."
  [branch by-id current-name]
  (let [name        (:name branch)
        is-current? (= name current-name)
        is-main?    (= name default-branch-name)
        has-children? (some #(= (:id branch) (:base-branch-id %))
                            (vals by-id))
        parent      (some-> (:base-branch-id branch) by-id)
        parent-text (when parent (str " forked from " (:name parent)))]
    [:div {:class (str "branch-row" (when is-current? " branch-row-current"))
           :data-branch-name name
           :role "option"
           :aria-selected (if is-current? "true" "false")
           :tabindex "0"}
     [:span {:class "branch-row-name"} name]
     (when is-current? [:span {:class "branch-row-tag"} "current"])
     [:span {:class "branch-row-meta"} (or parent-text "")]
     [:span {:class "branch-row-actions"}
      (when-not is-current?
        [:button {:class "branch-row-diff"
                  :data-diff-source name
                  :title (str "Show what differs between " name " and " current-name)
                  :aria-label (str "Diff " name " vs " current-name)}
         "Δ"])
      (when-not is-current?
        [:button {:class "branch-row-merge"
                  :data-merge-source name
                  :title (str "Merge " name " INTO " current-name)
                  :aria-label (str "Merge " name " into " current-name)}
         "⇢"])
      (when-not (or is-main? has-children? is-current?)
        [:button {:class "branch-row-delete"
                  :data-branch-name name
                  :title "Delete branch"
                  :aria-label (str "Delete branch " name)}
         "×"])]]))


(defn render-branch-popover-hiccup
  "Hiccup for the top-bar branch popover body — the JS-side render in
   `editor-branches.js renderBranchPopover` moved here so admins can
   reshape labels / sort / extra columns from the graph. `branches` —
   already-decoded `:branch` rows in `:created-at` ascending order
   (`:_list-branches-rows`). `current-name` — name of the active
   branch (resolved from `:current-branch-id`).

   Sort: `main` first, then current, then alphabetic — matches the
   prior client-side order so the popover layout is unchanged."
  [branches current-name]
  (let [current (or current-name default-branch-name)
        by-id   (into {} (map (juxt :id identity) branches))
        sorted  (sort-by
                  (fn [b]
                    (let [n (:name b)]
                      [(cond (= n default-branch-name) 0
                             (= n current) 1
                             :else 2)
                       (or n "")]))
                  branches)]
    [:div
     [:div {:class "branch-popover-list" :role "listbox" :aria-label "Branches"}
      (for [b sorted] (branch-row-hiccup b by-id current))]
     [:div {:class "branch-popover-create"}
      [:input {:id "branch-create-input"
               :type "text"
               :placeholder (str "New branch name (forks from " current ")")
               :autocomplete "off"}]
      [:button {:id "branch-create-btn" :class "branch-popover-btn"} "Create"]]
     [:div {:id "branch-popover-error" :class "branch-popover-error hidden"}]]))
