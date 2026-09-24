(ns graphden.crud.fn-execution.conceal
  "READ-time concealment of an execution's `:path-trace` — the view-impl
   seam (`crud.entities.list/view-impl-filter`, docs/TENANCY_SEAM.md)
   applied to a call tree.

   A viewer who may not see a fn's composition (another org's shared fn
   with no `:view-impl` grant) must not learn it from a trace either: the
   frames INSIDE such a fn name its helpers, their ids and their captured
   values. So a hidden fn's frame is kept as a LEAF — its own timing /
   cache flag / value stay, it is a call the viewer made — and every frame
   beneath it is dropped. What the viewer can see of the run is exactly
   the part of the tree they could have drawn from the graph they see.

   Per-viewer, hence at READ time (grants change; one org's members may
   hold different grants): every surface that serves a trace — the inline
   `/api/execute` response, GET /api/execute/:id, GET /api/executions,
   the `/partials/execute-trace` rows, MCP `execute-fn trace:true` —
   passes it through `conceal-path-trace`.

   Fails CLOSED on lost ancestry: a frame whose parent entry is absent (an
   entry-capped or byte-truncated capture) or a pre-tree entry with no
   `:seq` links cannot be placed, so it is dropped whenever the viewer
   would be denied an UNKNOWN fn's internals (`unknown-fn-hidden?` — a
   tenant; never single-tenant or the platform context). Kept frames are
   renumbered densely so the `:seq` gaps do not count the concealed
   frames."
  (:require
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]))


(defn- hidden-frame-fn-ids
  "The fn-id STRINGS among `entries` whose composition the viewer may not
   see: the rows the filter hides, plus — when unknown fns read as hidden
   — the ids the viewer's storage cannot resolve to a row at all."
  [storage entries unknown-hidden?]
  (let [ids (into [] (comp (keep :fn-id) (map str) (distinct)) entries)
        uuids (into [] (keep request/parse-uuid-or-clear) ids)
        rows (if (seq uuids) (sp/query-entities storage :fn {:id uuids}) [])
        hidden (into #{} (map str) (entity-list/hidden-fn-ids rows))]
    (if unknown-hidden?
      (into hidden (remove (into #{} (map (comp str :id)) rows)) ids)
      hidden)))


(defn- inside-hidden-pred
  "Predicate over an entry: is it INSIDE a hidden frame (or of unknown
   ancestry while unknowns are hidden)? Memoised walk up `:parent-seq`."
  [entries hidden? unknown-hidden?]
  (let [by-seq (into {} (keep (fn [e] (when-let [s (:seq e)] [s e]))) entries)
        memo (atom {})]
    (letfn [(inside?
              [e]
              (let [p (:parent-seq e)]
                (cond
                  (nil? (:seq e)) unknown-hidden?
                  (nil? p) false
                  :else (if-some [hit (find @memo p)]
                          (val hit)
                          (let [pe (get by-seq p)
                                v (if pe
                                    (or (hidden? pe) (inside? pe))
                                    unknown-hidden?)]
                            (swap! memo assoc p v)
                            v)))))]
      inside?)))


(defn- renumber
  "Dense `:seq` / `:parent-seq` over the kept `entries` (entry order
   preserved), so the gaps left by concealed frames are not a count of
   them."
  [entries]
  (let [dense (into {} (map-indexed (fn [i s] [s i]))
                    (sort (keep :seq entries)))]
    (mapv (fn [e]
            (cond-> e
              (:seq e) (assoc :seq (get dense (:seq e)))
              (:parent-seq e) (assoc :parent-seq (get dense (:parent-seq e)))))
          entries)))


(defn conceal-path-trace
  "`pt` (a `:path-trace` — `{:entries […] …}`, fn-ids as uuids or strings)
   as the CURRENT viewer may see it: hidden fns' frames collapsed to
   leaves (`:concealed? true`), everything beneath them dropped, frames of
   unknown ancestry dropped when unknowns are hidden. Identity (the same
   map) when nothing is concealed — the single-tenant path costs one
   atom deref."
  [storage pt]
  (let [entries (:entries pt)]
    (if (or (empty? entries) (nil? @entity-list/view-impl-filter))
      pt
      (let [unknown-hidden? (entity-list/unknown-fn-hidden?)
            hidden-ids (hidden-frame-fn-ids storage entries unknown-hidden?)
            hidden? #(contains? hidden-ids (str (:fn-id %)))
            inside? (inside-hidden-pred entries hidden? unknown-hidden?)
            kept (into [] (remove inside?) entries)]
        (if (and (empty? hidden-ids) (= (count kept) (count entries)))
          pt
          (assoc pt :entries
                 (renumber (mapv #(cond-> % (hidden? %) (assoc :concealed? true))
                                 kept))))))))
