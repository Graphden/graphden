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

(defbase _diff-branches-data
  [request]
  (let [target (after-segment request "branches")
        params (request/parse-query-string (:query-string request))
        against (get params "against")]
    (branches/diff-branches ctx target against)))


;; =============================================================================
;; POST /api/branches
;; =============================================================================

(defbase _create-branch-data
  [request]
  (branches/create-branch ctx (request/read-json-body request)))


;; =============================================================================
;; DELETE /api/branches/:ref
;; =============================================================================

(defbase _delete-branch-data
  [request]
  (branches/delete-branch ctx (after-segment request "branches")))


;; =============================================================================
;; GET /api/branches/:ref/conflicts?source=<ref>
;; =============================================================================

(defbase _preview-conflicts-data
  [request]
  (let [target (after-segment request "branches")
        params (request/parse-query-string (:query-string request))
        source (get params "source")]
    (branches/preview-conflicts ctx target source)))


;; =============================================================================
;; POST /api/branches/:ref/merge
;; =============================================================================

(defbase _merge-branch-data
  [request]
  (branches/merge-branch ctx
                         (after-segment request "branches")
                         (request/read-json-body request)))


(def impls
  {:_list-branches-data     _list-branches-data
   :_get-branch-data        _get-branch-data
   :_list-fn-versions-data  _list-fn-versions-data
   :_diff-branches-data     _diff-branches-data
   :_create-branch-data     _create-branch-data
   :_delete-branch-data     _delete-branch-data
   :_preview-conflicts-data _preview-conflicts-data
   :_merge-branch-data      _merge-branch-data})
