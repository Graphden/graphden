(ns graphden.packages.pin-usage
  "Whether an installed graph has consumers outside its own package. The
   uninstall caller holds the graph writer while checking and deleting."
  (:require
    [graphden.crud.graph-refs :as refs]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(defn- graph-references-package?
  [storage package-ids]
  ;; A single branch-local read per entity family preserves the same edge
  ;; vocabulary as Used-by. In particular, a personal root may inherit from
  ;; or bind a package function without selecting that function directly.
  (let [graph {:fns (sp/query-entities storage :fn {})
               :slots (sp/query-entities storage :slot {})
               :fn-slots (sp/query-entities storage :fn-slot {})
               :bindings (sp/query-entities storage :binding {})
               :list-items (sp/query-entities storage :binding-list-item {})}]
    (boolean (some (fn [{:keys [fn-id target-id]}]
                     (and (contains? package-ids target-id)
                          (not (contains? package-ids fn-id))))
                   (refs/edges graph)))))


(defn- selected-package-graph?
  [storage package-ids]
  (let [connection (tx/datasource storage)
        branch-id (versioned/current-branch-id storage)
        branch-name (:name (sp/read-entity storage :branch branch-id))
        org (tenancy/current-org)]
    (when-not connection
      (throw (ex-info "Package use cannot be checked without a transaction"
                      {:type :packages/guard-unavailable})))
    ;; Owner preferences are intentionally hidden by the normal storage
    ;; decorator. This trusted query returns ONLY graph selectors for the
    ;; caller's organization and branch, never another owner's preference
    ;; document or identity. The Boolean result is all the API exposes.
    (let [rows (jdbc/execute!
                 connection
                 ["SELECT COALESCE(value->>'fn-id', value#>>'{graph,fn-id}') AS selected_id
                    FROM ui_pref
                   WHERE (\"key\" = 'components' AND value->>'org' = ? AND value->>'branch-id' = ?)
                      OR (\"key\" = 'theme' AND value#>>'{graph,org}' = ?
                          AND value#>>'{graph,branch}' IN (?, ?))"
                  org (str branch-id) org (or branch-name (str branch-id)) (str branch-id)]
                 {:builder-fn rs/as-unqualified-lower-maps})]
      (boolean (some (fn [{:keys [selected_id]}]
                       (when-let [id (and (string? selected_id) (parse-uuid selected_id))]
                         (contains? package-ids id)))
                     rows)))))


(defn in-use?
  "Fail closed if an installed package has any branch graph consumer or any
   owner in this org/branch selects one of its functions as a UI root/theme."
  [storage package-ids]
  (and (seq package-ids)
       (or (graph-references-package? storage package-ids)
           (selected-package-graph? storage package-ids))))
