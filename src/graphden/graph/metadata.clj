(ns graphden.graph.metadata
  "Bounded exact namespace/name discovery over the caller's immutable view.
   Function identity discovery never reads implementation rows or executes it."
  (:require
    [clojure.string :as str]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(defn path-parts
  "Validate a finite dot path before storage reads. No path is inferred from
   a user's arbitrary grants or from a submitted identity."
  [path]
  (when-not (and (string? path) (<= 1 (count path) 512))
    (throw (ex-info "Invalid namespace path" {:type :graph-metadata/invalid-path})))
  (let [parts (str/split path #"\." -1)]
    (when-not (and (<= (count parts) 16) (every? #(not (str/blank? %)) parts))
      (throw (ex-info "Invalid namespace path" {:type :graph-metadata/invalid-path})))
    parts))


(defn- exact-rows
  [storage entity where org]
  (when-not (and (string? org) (not (str/blank? org)))
    (throw (ex-info "Invalid organization scope" {:type :graph-metadata/invalid-org})))
  ;; Legacy NULL and explicit public ownership mean the same authority.
  ;; Query both forms explicitly rather than scanning an organization's tree.
  (let [owners (if (= tenancy/public-org org) [nil tenancy/public-org] [org])
        read-owner (fn [owner]
                     (let [predicate (assoc where :org-id owner)]
                       (if (and (= entity :fn) (satisfies? sp/StorageBoundedQuery storage))
                         ;; LIMIT before branch resolution is unsafe. Bound
                         ;; identity candidates, resolve normally, THEN enforce
                         ;; exact cardinality. Old/tombstoned names count toward
                         ;; the same finite 2048-function source envelope.
                         (sp/query-bounded-entities storage entity predicate 2048)
                         (sp/query-entities storage entity predicate {:limit 2}))))
        rows (into [] (mapcat read-owner) owners)]
    (when (> (count rows) 1)
      (throw (ex-info "Ambiguous graph identity" {:type :graph-metadata/ambiguous})))
    rows))


(defn namespace-child-row
  "Read one exact namespace metadata edge; never enumerate siblings."
  [storage parent name org]
  (when-not (and (or (nil? parent) (uuid? parent))
                 (= 1 (count (path-parts name))))
    (throw (ex-info "Invalid namespace edge" {:type :graph-metadata/invalid-path})))
  (when-let [row (first (exact-rows storage :ns {:name name :parent-id parent} org))]
    (when-not (and (uuid? (:id row)) (= org (or (:org-id row) tenancy/public-org))
                   (= parent (:parent-id row)) (= name (:name row)))
      (throw (ex-info "Inconsistent namespace metadata" {:type :graph-metadata/inconsistent})))
    row))


(defn namespace-row
  "Resolve only `org`'s exact namespace path. Missing is nil; ambiguity is an
   error. Each segment performs bounded metadata queries, never a tree scan."
  [storage path org]
  (loop [parts (seq (path-parts path)) parent nil]
    (when-let [row (namespace-child-row storage parent (first parts) org)]
      (if (next parts)
        (recur (next parts) (:id row))
        row))))


(defn function-row
  "Find a named function in one exact namespace/organization view. Authorize
   its metadata before returning it; absence never masks source denial."
  [storage namespace-id function-name org authorize!]
  (when-not (and (uuid? namespace-id) (string? function-name)
                 (<= 1 (count function-name) 128) (not (str/blank? function-name)))
    (throw (ex-info "Invalid function discovery" {:type :graph-metadata/invalid-name})))
  (when-let [row (first (exact-rows storage :fn {:namespace-id namespace-id :name function-name} org))]
    (when-not (and (uuid? (:id row)) (= namespace-id (:namespace-id row))
                   (= function-name (:name row)) (= org (or (:org-id row) tenancy/public-org)))
      (throw (ex-info "Inconsistent function metadata" {:type :graph-metadata/inconsistent})))
    (authorize! row {:execute? true})
    row))


(defrecord SnapshotFunctionLookup
  [storage authorize! scope])


(defn function-lookup
  "Create a server-owned read-only lookup capability for one exact name in
   the caller's authenticated home and immutable organization/branch view.
   The source guard belongs to that same snapshot. Never accept this scope
   or guard from request data or from a tenant graph."
  [storage authorize! scope]
  (when-not (and (map? scope)
                 (= #{:namespace-path :name :principal-id :org :branch-id} (set (keys scope)))
                 (or (nil? (:namespace-path scope)) (string? (:namespace-path scope)))
                 (string? (:name scope)) (<= 1 (count (:name scope)) 128)
                 (string? (:principal-id scope)) (not (str/blank? (:principal-id scope)))
                 (string? (:org scope)) (not (str/blank? (:org scope)))
                 (uuid? (:branch-id scope)) (ifn? authorize!))
    (throw (ex-info "Invalid metadata capability" {:type :graph-metadata/invalid-capability})))
  (->SnapshotFunctionLookup storage authorize! scope))


(defn lookup-function
  "Return only an actual authorized identity, or nil for an absent exact
   namespace/name. A scope mismatch or source denial never means absence.
   This capability cannot execute the discovered function or read its body."
  [capability arguments]
  (when-not (and (instance? SnapshotFunctionLookup capability)
                 (= (:scope capability) arguments))
    (throw (ex-info "Metadata lookup scope changed" {:type :graph-metadata/scope-mismatch})))
  (let [{:keys [namespace-path name org]} arguments
        {:keys [storage authorize!]} capability]
    (when namespace-path
      (when-let [home-row (namespace-row storage namespace-path org)]
        (when-let [row (function-row storage (:id home-row) name org authorize!)]
          {:fn-id (:id row)})))))
