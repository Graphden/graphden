(ns graphden.layout.strip-facts
  "Server-computed FACTS for the editor's bottom-of-card metadata
   strips — attached to each fn-node's `:data` during layout assembly
   so the client renders the strips without re-deriving server-owned
   reasoning. Before this pass the editor kept three hand-written
   mirrors of these walks (`editor-overlay-strips.js`): the
   return-type-alias inheritance BFS + a copied primitives set, the
   rule-owner primary-parent walk, and a transitive branch-local walk
   that even labelled itself a mirror of
   `graphden.versioning.branch-local/effective-branch-local?`.

   Facts (each absent when it doesn't apply):
     :returnTypeAlias  — name of the non-primitive type-row the fn's
                         (possibly inherited) `:return-type-fn-id`
                         resolves to. The strip shows `→ port` instead
                         of the unfolded structural form.
     :ruleOwner        — name of the base-fn whose `:return-type-rule`
                         computed this fn's return type
                         (`registry/rule-owner-info-of-id` — by the
                         fn's id, never its bare name). Presence gates
                         the `↳` provenance badge.
     :branchLocal      — `{:own bool :seed name}` when the fn (or an
                         ancestor) carries `:branch-local? true`
                         (`branch-local/branch-local-seed`).

   For a fn whose composition the viewer may not see (the view-impl
   seam) — or whose chain runs through one — two of them would name
   those internals, so they are redacted: no `:ruleOwner` (the base-fn
   the chain bottoms out in), and a `:branchLocal` whose seed sits
   beyond concealed composition keeps `:own false` but not the
   ancestor's name (`viewer-branch-local`)."
  (:require
    [graphden.crud.entities.list :as entity-list]
    [graphden.executor.registry.core :as registry]
    [graphden.types.core.shapes :as shapes]
    [graphden.versioning.branch-local :as branch-local]))


(defn- inherited-return-type-alias
  "Name of the type-row behind the first `:return-type-fn-id` found
   BFS'ing `fn-id`'s `:parent-ids` closure (self included) — composed
   fn-defs INHERIT the declared return-type from their parent
   (`web-server`'s row carries nil; the value lives on
   `:http-server`'s). nil when the closure has none, or when it
   resolves to a bare primitive / unnamed row (the structural form
   is then at least as informative as the alias).

   MI edge (documented, not handled): the walk stops at the FIRST
   `:return-type-fn-id` in BFS order — a fn whose primary parent
   declares a primitive return while a secondary parent carries a
   named alias shows no alias. Matches the old client behaviour;
   revisit only if MI return-type aliasing becomes a real case."
  [fns-by-id fn-id]
  (loop [queue [fn-id]
         visited #{}]
    (when-let [cur (first queue)]
      (if (contains? visited cur)
        (recur (rest queue) visited)
        (let [row (get fns-by-id cur)]
          (if-let [rt-id (:return-type-fn-id row)]
            (let [nm (:name (get fns-by-id rt-id))]
              (when (and (string? nm)
                         (not (contains? shapes/primitives (keyword nm))))
                nm))
            (recur (concat (rest queue) (:parent-ids row))
                   (conj visited cur))))))))


(defn viewer-branch-local
  "`{:own bool :seed name}` when branch-local is in effect for `fn-id`
   (itself or an ancestor carries the seed), nil otherwise — as the
   viewer may know it: the flag always (it is how the fn behaves on a
   merge), the seed's NAME only when the viewer's own view of the chain
   (`seen-by-id`, `entity-list/conceal-parents`) reaches it. A seed found
   only through a concealed fn's parents stays unnamed (`:seed` absent).
   Shared by the card strip and the Inspector's Overview row."
  [fns-by-id seen-by-id fn-id]
  (when-let [seed (branch-local/branch-local-seed fns-by-id fn-id)]
    (let [own? (= (:id seed) fn-id)
          named (if own? seed (branch-local/branch-local-seed seen-by-id fn-id))]
      (cond-> {:own own?}
        named (assoc :seed (:name named))))))


(defn annotate
  "Attach strip facts to every fn-node's `:data` in a built
   `{:nodes :edges}` element map. `graph` is the layout entity
   snapshot (`:fns` et al). Non-fn nodes and unknown ids pass
   through untouched."
  [elements graph]
  (let [fns-by-id (into {} (map (juxt :id identity)) (:fns graph))
        node-fn-id #(some-> (get-in % [:data :originalFnId]) parse-uuid)
        seen-by-id (entity-list/conceal-parents fns-by-id (keep node-fn-id (:nodes elements)))
        chain-concealed? (partial entity-list/chain-concealed? fns-by-id seen-by-id)]
    (update elements :nodes
            (fn [nodes]
              (mapv (fn [n]
                      (let [fn-id (node-fn-id n)
                            row (get fns-by-id fn-id)]
                        (if-not row
                          n
                          (let [owner (when-not (chain-concealed? fn-id)
                                        (:name (registry/rule-owner-info-of-id fn-id)))
                                rt-alias (inherited-return-type-alias fns-by-id fn-id)
                                bl (viewer-branch-local fns-by-id seen-by-id fn-id)]
                            (cond-> n
                              rt-alias (assoc-in [:data :returnTypeAlias] rt-alias)
                              owner (assoc-in [:data :ruleOwner] owner)
                              bl (assoc-in [:data :branchLocal] bl))))))
                    nodes)))))
