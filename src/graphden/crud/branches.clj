(ns graphden.crud.branches
  "Read-side orchestration for the `/api/branches/*` and
   `/api/fns/:fn-id/versions` endpoints.

   Thin wrappers over `graphden.versioning.storage.{core,merge}` that
   shape responses for JSON output (stringified UUIDs, ISO-8601
   timestamps). Write endpoints (create/delete/merge) live in
   `graphden.crud.branches-write` once they land — keeping reads in
   their own ns matches the `fn-execution / fn-execution.lookup`
   split."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as mrg]))


(defn- base-storage
  "Read endpoints always operate on the unwrapped base storage — the
   branch context is encoded in the request path/query, not in the
   wrapper. Throws the same `:execution-error/missing-storage` shape
   as `request/require-storage` when ctx has no storage."
  [ctx]
  (vs/unwrap (request/require-storage ctx)))


(defn- as-json-branch
  [b]
  (when b
    {:id (str (:id b))
     :name (:name b)
     :base-branch-id (some-> (:base-branch-id b) str)
     :created-at (some-> (:created-at b) str)}))


(defn- stringify-uuids
  "Walk a versioned-entity map and stringify every UUID value so JSON
   encoders don't choke. Shallow — version records don't nest."
  [m]
  (when m
    (reduce-kv
      (fn [acc k v]
        (assoc acc k (if (or (uuid? v) (inst? v)) (str v) v)))
      {}
      m)))


(defn- resolve-branch-ref
  "Branch lookup that accepts either a stringified UUID or a branch
   name. Returns the row, or nil when not found."
  [storage branch-ref]
  (when (and (string? branch-ref) (not (str/blank? branch-ref)))
    (or (try (some->> branch-ref java.util.UUID/fromString
                      (sp/read-entity storage :branch))
             (catch IllegalArgumentException _ nil))
        (first (sp/query-entities storage :branch {:name branch-ref})))))


;; =============================================================================
;; GET /api/branches
;; =============================================================================

(defn list-branches
  "Every branch row, ordered by created-at ascending so the editor can
   render them as a forked-from chain."
  [ctx]
  (let [storage (base-storage ctx)
        rows (sort-by :created-at (sp/query-entities storage :branch {}))]
    {:ok true :count (count rows) :branches (mapv as-json-branch rows)}))


;; =============================================================================
;; GET /api/branches/:ref
;; =============================================================================

(defn get-branch
  "Single branch by id or name. `{:ok false :error}` on miss."
  [ctx branch-ref]
  (let [storage (base-storage ctx)
        row (resolve-branch-ref storage branch-ref)]
    (if row
      {:ok true :branch (as-json-branch row)}
      {:ok false :error (str "Branch not found: " branch-ref)})))


;; =============================================================================
;; GET /api/fns/:fn-id/versions
;; =============================================================================

(defn list-fn-versions
  "Every fn-version row for one fn, joined with branch name. Latest
   first (most recent created-at). `nil`/missing fn-id → empty list
   rather than 404 — the editor calls this opportunistically and a
   404 would create unnecessary error noise."
  [ctx fn-id]
  (let [storage (base-storage ctx)
        versions (if fn-id
                   (sp/query-entities storage :fn-version {:fn-id fn-id})
                   [])
        ;; Only query the branches that actually appear in the version
        ;; rows — pre-fix this read every :branch row (full table scan)
        ;; even when 3 versions referenced 2 branches out of a hundred.
        referenced-branch-ids (into #{} (keep :branch-id) versions)
        branches-by-id (if (empty? referenced-branch-ids)
                         {}
                         (into {} (map (juxt :id identity))
                               (sp/query-entities storage :branch
                                                  {:id (vec referenced-branch-ids)})))
        ;; One IN-query pulls every execution for ANY version of the fn,
        ;; grouped by `:fn-version-id` so each version row gets its own
        ;; count without an N+1 fetch. The `⌛` panel uses this to show
        ;; "(N runs)" inline.
        version-ids (mapv :id versions)
        exec-counts (if (empty? version-ids)
                      {}
                      (->> (sp/query-entities storage :fn-execution
                                              {:fn-version-id version-ids})
                           (group-by :fn-version-id)
                           (reduce-kv (fn [m k vs] (assoc m k (count vs))) {})))
        sorted (sort-by :created-at #(compare %2 %1) versions)]
    {:ok true
     :fn-id (some-> fn-id str)
     :count (count sorted)
     :versions (mapv (fn [v]
                       (assoc (stringify-uuids
                                (select-keys v [:id :name :description :impl-hash
                                                :constraint :base-fn-id
                                                :element-fn-id :return-type-fn-id
                                                :anonymous-hash :expects-effects
                                                :created-at :deleted-at]))
                              :branch-id (some-> (:branch-id v) str)
                              :branch-name (some-> (get branches-by-id (:branch-id v))
                                                   :name)
                              :execution-count (get exec-counts (:id v) 0)))
                     sorted)}))


;; =============================================================================
;; GET /api/branches/:target/diff?against=<source>
;; =============================================================================

(defn- prepare-diff-entry
  [{:keys [entity-name entity-id source-version target-version change]}]
  {:entity-name entity-name
   :entity-id (str entity-id)
   :change change
   :source-version (stringify-uuids source-version)
   :target-version (stringify-uuids target-version)})


(defn parse-diff-branches-request
  "Parse `GET /api/branches/:target/diff?against=<source>` into the
   bundle the C12 `:cond` graph fn-def consumes."
  [target-ref against-ref]
  {:target-ref target-ref :against-ref against-ref})


(defn diff-target-branch
  "C12 sub-result — resolve the target branch by ref. Shared by the
   not-found guard + apply."
  [parsed ctx]
  (resolve-branch-ref (base-storage ctx) (:target-ref parsed)))


(defn diff-source-branch
  "C12 sub-result — resolve the source branch when an `?against=`
   query was supplied; nil otherwise."
  [parsed ctx]
  (when-let [src-ref (:against-ref parsed)]
    (resolve-branch-ref (base-storage ctx) src-ref)))


(defn apply-diff-branches
  "C12 success branch — compute the diff between target + source."
  [_parsed target source ctx]
  (let [{:keys [diffs]} (mrg/diff-branches (base-storage ctx)
                                           (:id source) (:id target))]
    {:ok true
     :target (as-json-branch target)
     :source (as-json-branch source)
     :count (count diffs)
     :diffs (mapv prepare-diff-entry diffs)}))


(defn diff-branches
  "Resolved-view diff between target branch and `against`. Both refs
   can be either a UUID or a name. Returns `{:ok true :target :source
   :count :diffs}` shaped for JSON."
  [ctx target-ref against-ref]
  (let [storage (base-storage ctx)
        target (resolve-branch-ref storage target-ref)
        source (when against-ref (resolve-branch-ref storage against-ref))]
    (cond
      (nil? target)
      {:ok false :error (str "Target branch not found: " target-ref)}

      (and against-ref (nil? source))
      {:ok false :error (str "Source branch not found: " against-ref)}

      (nil? source)
      {:ok false :error "Query parameter 'against' is required"}

      :else
      (let [{:keys [diffs]} (mrg/diff-branches storage (:id source) (:id target))]
        {:ok true
         :target (as-json-branch target)
         :source (as-json-branch source)
         :count (count diffs)
         :diffs (mapv prepare-diff-entry diffs)}))))


;; =============================================================================
;; POST /api/branches — create a new branch
;; =============================================================================

(defn parse-create-branch-request
  "Parse the JSON body of `POST /api/branches` into `{:branch-name
   :base-ref}`."
  [body]
  {:branch-name (some-> body :name str/trim)
   :base-ref (when-let [v (:base-branch-id body)]
               (when-not (str/blank? (str v)) (str v)))})


(defn create-branch-name-taken?
  "C13 guard — a branch with the same name already exists."
  [parsed ctx]
  (seq (sp/query-entities (vs/unwrap (request/require-storage ctx))
                          :branch {:name (:branch-name parsed)})))


(defn create-branch-resolved-parent
  "C13 sub-result — resolve the base-branch ref to a row. When no
   `:base-ref` was supplied, defaults to the wrapper's current
   branch (= main). Returns nil when the supplied ref doesn't
   resolve, distinguishable from the default-main case via
   `:base-ref`-non-nil + result-nil."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        base (vs/unwrap storage)]
    (if-let [base-ref (:base-ref parsed)]
      (resolve-branch-ref base base-ref)
      (sp/read-entity base :branch (vs/current-branch-id storage)))))


(defn apply-create-branch
  "C13 success branch — `vs/create-branch!` with the resolved parent's
   id."
  [parsed parent ctx]
  (let [row (vs/create-branch! (request/require-storage ctx)
                               (:branch-name parsed)
                               {:base-branch-id (:id parent)})]
    {:ok true :branch (as-json-branch row)}))


(defn create-branch
  "Create a new branch. `body` is the parsed JSON `{:name :base-branch-id?}`.
   `:base-branch-id` accepts either a UUID or a branch name; missing /
   nil forks from `main`. Returns the created row."
  [ctx body]
  (let [storage (request/require-storage ctx)
        base (vs/unwrap storage)
        branch-name (some-> body :name str/trim)
        base-ref (when-let [v (:base-branch-id body)]
                   (when-not (str/blank? (str v)) (str v)))]
    (cond
      (or (nil? branch-name) (str/blank? branch-name))
      {:ok false :error "Required field ':name' is missing"}

      (seq (sp/query-entities base :branch {:name branch-name}))
      {:ok false :error (str "Branch already exists: " branch-name)}

      :else
      (let [parent (if base-ref
                     (resolve-branch-ref base base-ref)
                     ;; Default = main (the wrapper's current branch).
                     (sp/read-entity base :branch (vs/current-branch-id storage)))]
        (cond
          (and base-ref (nil? parent))
          {:ok false :error (str "Base branch not found: " base-ref)}

          :else
          (let [row (vs/create-branch! storage branch-name
                                       {:base-branch-id (:id parent)})]
            {:ok true :branch (as-json-branch row)}))))))


;; =============================================================================
;; DELETE /api/branches/:ref
;; =============================================================================

(defn parse-delete-branch-request
  "Parse `DELETE /api/branches/:ref` URL into `{:branch-ref}`."
  [branch-ref]
  {:branch-ref branch-ref})


(defn delete-branch-resolved
  "C14 sub-result — resolve the branch ref to a row. Shared by the
   not-found guard + apply."
  [parsed ctx]
  (resolve-branch-ref (vs/unwrap (request/require-storage ctx))
                      (:branch-ref parsed)))


(defn apply-delete-branch
  "C14 success branch — `vs/delete-branch!` with the resolved row's
   id. Caught :constraint-violation exceptions (main / has-children)
   are translated to specific JSON-shaped rejections; other failures
   surface as generic `{:ok false :error}`."
  [parsed branch-row ctx]
  (let [storage (request/require-storage ctx)]
    (try
      (vs/delete-branch! storage (:id branch-row))
      {:ok true :id (str (:id branch-row)) :name (:name branch-row)}
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (case (:type data)
            :constraint-violation/main-branch-undeletable
            {:ok false :reason :main-branch-undeletable
             :error "Cannot delete the main branch"}

            :constraint-violation/branch-has-children
            {:ok false :reason :branch-has-children
             :error "Branch has children — delete or re-parent them first"
             :child-branch-ids (mapv str (:child-branch-ids data))}

            :not-found
            {:ok false :reason :not-found
             :error (str "Branch not found: " (:branch-ref parsed))}

            {:ok false :error (or (ex-message e) "Unknown error")}))))))


(defn delete-branch
  "Deletes every version record on the branch + the branch row.
   Rejects `main` and branches that have children. Returns
   `{:ok true :id :name}` on success, `{:ok false :error :reason}`
   on rejection."
  [ctx branch-ref]
  (let [storage (request/require-storage ctx)
        base (vs/unwrap storage)
        branch (resolve-branch-ref base branch-ref)]
    (if-not branch
      {:ok false :error (str "Branch not found: " branch-ref)}
      (try
        (vs/delete-branch! storage (:id branch))
        {:ok true :id (str (:id branch)) :name (:name branch)}
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (case (:type data)
              :constraint-violation/main-branch-undeletable
              {:ok false :reason :main-branch-undeletable
               :error "Cannot delete the main branch"}

              :constraint-violation/branch-has-children
              {:ok false :reason :branch-has-children
               :error "Branch has children — delete or re-parent them first"
               :child-branch-ids (mapv str (:child-branch-ids data))}

              :not-found
              {:ok false :reason :not-found
               :error (str "Branch not found: " branch-ref)}

              {:ok false :error (or (ex-message e) "Unknown error")})))))))


;; =============================================================================
;; GET /api/branches/:ref/conflicts?source=<ref>
;; =============================================================================

(defn- prepare-conflict-entry
  [{:keys [entity-name entity-id source-version target-version]}]
  {:entity-name entity-name
   :entity-id (str entity-id)
   :source-version (stringify-uuids source-version)
   :target-version (stringify-uuids target-version)})


(defn parse-preview-conflicts-request
  "Parse `GET /api/branches/:target/conflicts?source=<ref>` into
   `{:target-ref :source-ref}`."
  [target-ref source-ref]
  {:target-ref target-ref :source-ref source-ref})


(defn preview-conflicts-target
  [parsed ctx]
  (resolve-branch-ref (base-storage ctx) (:target-ref parsed)))


(defn preview-conflicts-source
  [parsed ctx]
  (when-let [s (:source-ref parsed)]
    (resolve-branch-ref (base-storage ctx) s)))


(defn apply-preview-conflicts
  [target source ctx]
  (let [{:keys [conflicts fork-point]}
        (mrg/detect-conflicts (base-storage ctx) (:id source) (:id target))]
    {:ok true
     :target (as-json-branch target)
     :source (as-json-branch source)
     :fork-point (some-> fork-point str)
     :count (count conflicts)
     :conflicts (mapv prepare-conflict-entry conflicts)}))


(defn preview-conflicts
  "Read-only conflict preview — exposes `merge/detect-conflicts` over
   HTTP so the editor can decide whether to surface the conflict
   modal before issuing a merge."
  [ctx target-ref source-ref]
  (let [storage (base-storage ctx)
        target (resolve-branch-ref storage target-ref)
        source (when source-ref (resolve-branch-ref storage source-ref))]
    (cond
      (nil? target) {:ok false :error (str "Target branch not found: " target-ref)}
      (nil? source-ref) {:ok false :error "Query parameter 'source' is required"}
      (nil? source) {:ok false :error (str "Source branch not found: " source-ref)}
      :else
      (let [{:keys [conflicts fork-point]}
            (mrg/detect-conflicts storage (:id source) (:id target))]
        {:ok true
         :target (as-json-branch target)
         :source (as-json-branch source)
         :fork-point (some-> fork-point str)
         :count (count conflicts)
         :conflicts (mapv prepare-conflict-entry conflicts)}))))


;; =============================================================================
;; POST /api/branches/:ref/merge
;; =============================================================================

(defn- coerce-resolutions
  "Translate the JSON-friendly resolutions array into the Clojure-side
   `{[entity-name entity-id] :source|:target}` map merge-branch!
   expects. Resolutions arrive as
   `[{:entity-name :fn :entity-id \"<uuid>\" :choice \"source\"} …]`.
   Unknown choices are dropped — same shape as merge.clj's `case`
   matcher (anything not `:source`/`:target` is silently skipped)."
  [resolutions]
  (when (seq resolutions)
    (into {}
          (keep (fn [r]
                  (let [ename (some-> (:entity-name r) keyword)
                        eid (try (some-> (:entity-id r) java.util.UUID/fromString)
                                 (catch Exception _ nil))
                        choice (some-> (:choice r) keyword)]
                    (when (and ename eid (#{:source :target} choice))
                      [[ename eid] choice]))))
          resolutions)))


(defn parse-merge-branch-request
  "Parse `POST /api/branches/:target/merge` URL + body into
   `{:target-ref :source-ref :resolutions}`."
  [target-ref body]
  {:target-ref target-ref
   :source-ref (some-> (:source body) str)
   :resolutions (coerce-resolutions (:conflict-resolutions body))})


(defn merge-target-branch
  [parsed ctx]
  (resolve-branch-ref (vs/unwrap (request/require-storage ctx))
                      (:target-ref parsed)))


(defn merge-source-branch
  [parsed ctx]
  (when-let [s (:source-ref parsed)]
    (resolve-branch-ref (vs/unwrap (request/require-storage ctx)) s)))


(defn apply-merge-branch
  "C16 success branch — `vs/merge-branch!`. Catches the
   `:merge-conflict` exception and reshapes it into a JSON-friendly
   409-style payload; other failures surface as generic
   `{:ok false :error}`."
  [target source resolutions ctx]
  (let [storage (request/require-storage ctx)]
    (try
      (let [target-storage (vs/switch-branch storage (:id target))
            record (vs/merge-branch! target-storage (:id source)
                                     {:conflict-resolutions resolutions})]
        {:ok true
         :merge {:id (str (:id record))
                 :source-branch-id (str (:source-branch-id record))
                 :target-branch-id (str (:target-branch-id record))
                 :created-at (some-> (:created-at record) str)}})
      (catch clojure.lang.ExceptionInfo e
        (if (= :merge-conflict (:type (ex-data e)))
          {:ok false
           :reason :merge-conflict
           :error "Merge has unresolved conflicts"
           :target (as-json-branch target)
           :source (as-json-branch source)
           :conflicts (mapv prepare-conflict-entry
                            (:conflicts (ex-data e)))}
          {:ok false :error (or (ex-message e) "Unknown error")})))))


(defn merge-branch
  "Merge `source-ref` into `target-ref`. Body is the parsed JSON;
   optional `:conflict-resolutions` is an array — see
   `coerce-resolutions`.

   Returns the created `:branch-merge` row on success, or `{:ok false
   :conflicts […]}` (status 409) when merge.clj raises
   `:merge-conflict` and no resolutions cover them."
  [ctx target-ref body]
  (let [storage (request/require-storage ctx)
        base (vs/unwrap storage)
        target (resolve-branch-ref base target-ref)
        source-ref (some-> (:source body) str)
        source (when source-ref (resolve-branch-ref base source-ref))
        resolutions (coerce-resolutions (:conflict-resolutions body))]
    (cond
      (nil? target) {:ok false :error (str "Target branch not found: " target-ref)}
      (nil? source-ref) {:ok false :error "Required field ':source' is missing"}
      (nil? source) {:ok false :error (str "Source branch not found: " source-ref)}
      (= (:id source) (:id target))
      {:ok false :error "Source and target branches must differ"}

      :else
      (try
        (let [target-storage (vs/switch-branch storage (:id target))
              record (vs/merge-branch! target-storage (:id source)
                                       {:conflict-resolutions resolutions})]
          {:ok true
           :merge {:id (str (:id record))
                   :source-branch-id (str (:source-branch-id record))
                   :target-branch-id (str (:target-branch-id record))
                   :created-at (some-> (:created-at record) str)}})
        (catch clojure.lang.ExceptionInfo e
          (if (= :merge-conflict (:type (ex-data e)))
            {:ok false
             :reason :merge-conflict
             :error "Merge has unresolved conflicts"
             :target (as-json-branch target)
             :source (as-json-branch source)
             :conflicts (mapv prepare-conflict-entry
                              (:conflicts (ex-data e)))}
            {:ok false :error (or (ex-message e) "Unknown error")}))))))
