(ns graphden.executor.checker-tenant-index
  "Rename only the isolated checker registry's tenant index. Complete fresh
   source, aliases, signatures and provenance remain comparison inputs."
  (:require
    [graphden.tenancy.context :as tenancy]))


(def ^:private tenant-index ::tenant-index)


(defn- tenant?
  [org]
  (and (string? org) (seq org) (not= tenancy/public-org org)))


(defn- local-index?
  [index org]
  (and (map? index) (or (empty? index) (= #{org} (set (keys index))))))


(defn- rename-index
  [index from to]
  (if (contains? index from)
    (-> index (dissoc from) (assoc to (get index from)))
    index))


(defn- public-row?
  [row]
  (and (map? row) (tenancy/platform-tier? (:org-id row))))


(defn comparison-org
  "Only tenant-mode isolated seeds over freshly acquired public source share
   an index. This is no proof of source completeness: callers still read and
   authorize the entire graph in the requesting org before comparison."
  [org {:keys [graph namespaces]} seed]
  (if (and (tenant? org) (local-index? (:per-org seed) org)
           (seq (:fns graph))
           (every? public-row? namespaces)
           (every? #(every? public-row? (get graph %))
                   [:fns :slots :fn-slots :bindings :list-items]))
    tenant-index
    org))


(defn prepare-input
  "Never rewrite org-valued literals, ownership fields, aliases or metadata."
  [{:keys [org source seed] :as input}]
  (if (= tenant-index (comparison-org org source seed))
    (-> input (assoc :org tenant-index)
        (update-in [:seed :per-org] rename-index org tenant-index))
    input))


(defn prepare-artifact
  "Nil means the fresh computation succeeds without retaining its artifact.
   Foreign slices cannot enter the shared tenant-index family."
  [input-org org artifact]
  (if (= tenant-index input-org)
    (when (and (tenant? org)
               (local-index? (get-in artifact [:registry :per-org]) org)
               (local-index? (:mirrors artifact) org))
      (-> artifact
          (update-in [:registry :per-org] rename-index org tenant-index)
          (update :mirrors rename-index org tenant-index)))
    artifact))


(defn restore-artifact
  "Restore only the requesting tenant's isolated indexes, preserving signature
   identity/provenance. No previous tenant's index or policy closure survives."
  [artifact org]
  (if (or (contains? (get-in artifact [:registry :per-org]) tenant-index)
          (contains? (:mirrors artifact) tenant-index))
    (when (and (tenant? org)
               (local-index? (get-in artifact [:registry :per-org]) tenant-index)
               (local-index? (:mirrors artifact) tenant-index))
      (-> artifact
          (update-in [:registry :per-org] rename-index tenant-index org)
          (update :mirrors rename-index tenant-index org)))
    artifact))
