(ns graphden.packages.app.branches.impls
  "Impls for app/branches read endpoints. Each `defbase` is a thin
   shim that parses the URL / query string and delegates to
   `graphden.crud.branches`."
  (:require
    [clojure.string :as str]
    [graphden.crud.branches :as branches]
    [graphden.crud.request :as request]
    [graphden.executor.defbase :refer [defbase]]))


(defn- uri-segments
  "Split `:uri` into non-empty path segments. `/api/branches/foo` →
   `[\"api\" \"branches\" \"foo\"]`. Used because reitit's path-params
   aren't always available — handlers compiled inside a fn-graph
   sometimes receive the raw http-kit request that hasn't been
   through `enrich-request`. Mirrors `fn_execution.impls`'s
   `path-id` trick."
  [request]
  (->> (-> (:uri request "") (str/split #"/"))
       (remove str/blank?)
       vec))


(defn- after-segment
  "Return the segment immediately following `marker` in the URI path,
   or nil if `marker` is the final / absent segment."
  [request marker]
  (let [segs (uri-segments request)
        idx (.indexOf ^java.util.List segs marker)]
    (when (and (not (neg? idx)) (< (inc idx) (count segs)))
      (get segs (inc idx)))))


;; =============================================================================
;; GET /api/branches
;; =============================================================================

(defbase _list-branches-data
  [_request]
  ;; `_request` is the single-arg callable's leftover — unused: listing
  ;; branches needs no request fields. Underscore matches the
  ;; `_list-services` convention.
  (branches/list-branches ctx))


;; =============================================================================
;; GET /api/branches/:ref
;; =============================================================================

(defbase _get-branch-data
  [request]
  (branches/get-branch ctx (after-segment request "branches")))


;; =============================================================================
;; GET /api/fns/:fn-id/versions
;; =============================================================================

(defbase _list-fn-versions-data
  [request]
  (let [raw (after-segment request "fns")
        fn-id (request/parse-uuid-or-clear raw)]
    (branches/list-fn-versions ctx fn-id)))


;; =============================================================================
;; GET /api/branches/:ref/diff?against=<source>
;; =============================================================================

;; --- C12 atoms: diff-branches variant-2.

(defbase _diff-parsed
  [request]
  (let [target (after-segment request "branches")
        params (request/parse-query-string (:query-string request))]
    (branches/parse-diff-branches-request target (get params "against"))))


(defbase _diff-target-branch
  [parsed]
  (branches/diff-target-branch parsed ctx))


(defbase _diff-source-branch
  [parsed]
  (branches/diff-source-branch parsed ctx))


(defbase _diff-target-missing?
  [target-branch]
  (nil? target-branch))


(defbase _diff-against-missing?
  [parsed]
  (nil? (:against-ref parsed)))


(defbase _diff-source-missing?
  [source-branch]
  (nil? source-branch))


(defbase _diff-err-target-missing
  [parsed]
  {:ok false :error (str "Target branch not found: " (:target-ref parsed))})


(defbase _diff-err-source-missing
  [parsed]
  {:ok false :error (str "Source branch not found: " (:against-ref parsed))})


(defbase _diff-apply
  [parsed target-branch source-branch]
  (branches/apply-diff-branches parsed target-branch source-branch ctx))


;; =============================================================================
;; POST /api/branches
;; =============================================================================

;; --- C13 atoms: create-branch variant-2.

(defbase _create-branch-parsed
  [request]
  (branches/parse-create-branch-request (request/read-json-body request)))


(defbase _create-branch-name-blank?
  [parsed]
  (or (nil? (:branch-name parsed)) (str/blank? (:branch-name parsed))))


(defbase _create-branch-name-taken?
  [parsed]
  (boolean (branches/create-branch-name-taken? parsed ctx)))


(defbase _create-branch-resolved-parent
  [parsed]
  (branches/create-branch-resolved-parent parsed ctx))


(defbase _create-branch-base-missing?
  [parsed resolved-parent]
  (and (some? (:base-ref parsed)) (nil? resolved-parent)))


(defbase _create-branch-err-name-blank
  [_request]
  {:ok false :error "Required field ':name' is missing"})


(defbase _create-branch-err-name-taken
  [parsed]
  {:ok false :error (str "Branch already exists: " (:branch-name parsed))})


(defbase _create-branch-err-base-missing
  [parsed]
  {:ok false :error (str "Base branch not found: " (:base-ref parsed))})


(defbase _create-branch-apply
  [parsed resolved-parent]
  (branches/apply-create-branch parsed resolved-parent ctx))


;; =============================================================================
;; DELETE /api/branches/:ref
;; =============================================================================

;; --- C14 atoms: delete-branch variant-2. The constraint-rejection
;; cases (main-branch / has-children) stay inside apply because they
;; surface as exceptions from `vs/delete-branch!` — pre-checking them
;; would duplicate underlying constraint logic.

(defbase _delete-branch-parsed
  [request]
  (branches/parse-delete-branch-request (after-segment request "branches")))


(defbase _delete-branch-resolved
  [parsed]
  (branches/delete-branch-resolved parsed ctx))


(defbase _delete-branch-missing?
  [resolved]
  (nil? resolved))


(defbase _delete-branch-err-missing
  [parsed]
  {:ok false :error (str "Branch not found: " (:branch-ref parsed))})


(defbase _delete-branch-apply
  [parsed resolved]
  (branches/apply-delete-branch parsed resolved ctx))


;; =============================================================================
;; GET /api/branches/:ref/conflicts?source=<ref>
;; =============================================================================

;; --- C15 atoms: preview-conflicts variant-2.

(defbase _conflicts-parsed
  [request]
  (let [target (after-segment request "branches")
        params (request/parse-query-string (:query-string request))]
    (branches/parse-preview-conflicts-request target (get params "source"))))


(defbase _conflicts-target
  [parsed]
  (branches/preview-conflicts-target parsed ctx))


(defbase _conflicts-source
  [parsed]
  (branches/preview-conflicts-source parsed ctx))


(defbase _conflicts-target-missing?
  [target]
  (nil? target))


(defbase _conflicts-source-not-supplied?
  [parsed]
  (nil? (:source-ref parsed)))


(defbase _conflicts-source-missing?
  [source]
  (nil? source))


(defbase _conflicts-err-target-missing
  [parsed]
  {:ok false :error (str "Target branch not found: " (:target-ref parsed))})


(defbase _conflicts-err-source-missing
  [parsed]
  {:ok false :error (str "Source branch not found: " (:source-ref parsed))})


(defbase _conflicts-apply
  [target source]
  (branches/apply-preview-conflicts target source ctx))


;; =============================================================================
;; POST /api/branches/:ref/merge
;; =============================================================================

;; --- C16 atoms: merge-branch variant-2.

(defbase _merge-parsed
  [request]
  (branches/parse-merge-branch-request
    (after-segment request "branches")
    (request/read-json-body request)))


(defbase _merge-target
  [parsed]
  (branches/merge-target-branch parsed ctx))


(defbase _merge-source
  [parsed]
  (branches/merge-source-branch parsed ctx))


(defbase _merge-target-missing?
  [target]
  (nil? target))


(defbase _merge-source-not-supplied?
  [parsed]
  (nil? (:source-ref parsed)))


(defbase _merge-source-missing?
  [source]
  (nil? source))


(defbase _merge-same?
  [target source]
  (and (some? target) (some? source) (= (:id source) (:id target))))


(defbase _merge-err-target-missing
  [parsed]
  {:ok false :error (str "Target branch not found: " (:target-ref parsed))})


(defbase _merge-err-source-missing
  [parsed]
  {:ok false :error (str "Source branch not found: " (:source-ref parsed))})


(defbase _merge-apply
  [parsed target source]
  (branches/apply-merge-branch target source (:resolutions parsed) ctx))


(def impls
  {:_list-branches-data     _list-branches-data
   :_get-branch-data        _get-branch-data
   :_list-fn-versions-data  _list-fn-versions-data
   :_diff-parsed            _diff-parsed
   :_diff-target-branch     _diff-target-branch
   :_diff-source-branch     _diff-source-branch
   :_diff-target-missing?   _diff-target-missing?
   :_diff-against-missing?  _diff-against-missing?
   :_diff-source-missing?   _diff-source-missing?
   :_diff-err-target-missing _diff-err-target-missing
   :_diff-err-source-missing _diff-err-source-missing
   :_diff-apply             _diff-apply
   :_create-branch-parsed   _create-branch-parsed
   :_create-branch-name-blank? _create-branch-name-blank?
   :_create-branch-name-taken? _create-branch-name-taken?
   :_create-branch-resolved-parent _create-branch-resolved-parent
   :_create-branch-base-missing? _create-branch-base-missing?
   :_create-branch-err-name-blank _create-branch-err-name-blank
   :_create-branch-err-name-taken _create-branch-err-name-taken
   :_create-branch-err-base-missing _create-branch-err-base-missing
   :_create-branch-apply    _create-branch-apply
   :_delete-branch-parsed   _delete-branch-parsed
   :_delete-branch-resolved _delete-branch-resolved
   :_delete-branch-missing? _delete-branch-missing?
   :_delete-branch-err-missing _delete-branch-err-missing
   :_delete-branch-apply    _delete-branch-apply
   :_conflicts-parsed       _conflicts-parsed
   :_conflicts-target       _conflicts-target
   :_conflicts-source       _conflicts-source
   :_conflicts-target-missing? _conflicts-target-missing?
   :_conflicts-source-not-supplied? _conflicts-source-not-supplied?
   :_conflicts-source-missing? _conflicts-source-missing?
   :_conflicts-err-target-missing _conflicts-err-target-missing
   :_conflicts-err-source-missing _conflicts-err-source-missing
   :_conflicts-apply        _conflicts-apply
   :_merge-parsed           _merge-parsed
   :_merge-target           _merge-target
   :_merge-source           _merge-source
   :_merge-target-missing?  _merge-target-missing?
   :_merge-source-not-supplied? _merge-source-not-supplied?
   :_merge-source-missing?  _merge-source-missing?
   :_merge-same?            _merge-same?
   :_merge-err-target-missing _merge-err-target-missing
   :_merge-err-source-missing _merge-err-source-missing
   :_merge-apply            _merge-apply})
