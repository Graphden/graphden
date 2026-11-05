(ns graphden.crud.value-repr
  "Typed value-REPRESENTATION dispatch — the read-side sibling of
   `graphden.crud.value-form`.

   The forms system answers \"how do I EDIT a value of this type\";
   this ns answers \"how do I SHOW one\". Same shape: a graph-held
   dispatch table (`:_value-repr-registry`, `[type-name repr-fn-name]`
   pairs) matched most-specific-first by `subtype?`, each target a
   fn-def executed by name. Add a repr by adding a fn-def + a registry
   row — no Clojure change (the same extensibility contract as
   `:_value-form-registry`).

   Differences from the form path, all deliberate:
   - Dispatch is on the executed fn's DECLARED/INFERRED return type
     (rich-types by id), falling back to the runtime value's literal
     classification when the registry knows nothing narrower than
     `:any` — a repr should still fire for an untyped ad-hoc fn-def
     returning an obvious shape.
   - The repr fn must be PURE (empty effective `:effects`) — a repr
     renders a value, it must not read or write anything. Unknown
     effects (no rich-types entry) fail closed.
   - Output passes `hiccup-sanitize` before it may be inlined into
     the editor DOM (see that ns for the threat model).
   - Public graph policy catches render failures and falls back to the
     shape-based panes; the bounded preview batch memoizes failed renders."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as type-check]
    [graphden.crud.value-form :as value-form]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.interface :as executor]
    [graphden.executor.registry.core :as registry]
    [graphden.layout.data :as layout-data]
    [graphden.types.check.literals :as types-lit]
    [graphden.types.core :as types]
    [graphden.web.hiccup-sanitize :as sanitize]))


(def registry-fn-name "_value-repr-registry")


(defn declared-return-type
  "Declared/inferred return type of the fn identified by `fn-id`
   (UUID or string), from the id-keyed rich-types registry. nil when
   unknown."
  [fn-id]
  (let [id (cond
             (uuid? fn-id)   fn-id
             (string? fn-id) (request/parse-uuid-or-clear fn-id)
             :else           nil)]
    (some-> id registry/rich-type-of-id :return)))


(defn- record-list?
  "Every element a non-empty keyword-keyed map — the everyday
   storage-query / API-selection shape. Checked directly because the
   literal classifier lubs heterogeneous field types to `[:list :any]`
   (a nullable column is enough), which would silently lose the
   record-table repr for exactly the lists it exists for."
  [v]
  (and (sequential? v) (seq v)
       (every? #(and (map? %) (seq %) (every? keyword? (keys %))) v)))


(defn runtime-repr-type
  "Canonical runtime shape for representation dispatch. A heterogeneous
   keyword-map list retains its record-list shape rather than losing fields
   through the general literal classifier's least upper bound."
  [value]
  (if (record-list? value)
    [:list [:map :keyword :any]]
    (types-lit/classify-literal value)))


(defn render-selected-repr
  "Execute the graph-selected target with effects denied, then sanitize its DOM."
  [ctx target value]
  (when target
    (some-> (executor/execute-by-name
              (assoc ctx :allowed-effects #{}) target {:value value})
            sanitize/sanitize-hiccup)))


(defn- effective-literal-type
  "Resolve the actual slot type, including inherited narrowing and rename
   views, from the already loaded graph. Never dispatch by a display name."
  [{:keys [fnId slotId]} {:keys [fn-map slot-map binding-by-fn-slot] :as lookups}]
  (let [fid (request/parse-uuid-or-clear fnId)
        sid (request/parse-uuid-or-clear slotId)
        slot-ids (renames/chain-source-slot-ids sid slot-map)
        chain (layout-data/get-inheritance-chain* fid lookups)
        tid (or (some (fn [ancestor]
                        (some #(get-in binding-by-fn-slot
                                       [[ancestor %] :type-override-fn-id])
                              slot-ids)) chain)
                (:type-fn-id (get slot-map sid)))
        row (get fn-map tid)
        rich (registry/rich-type-of-id tid)]
    (when row
      ;; Refinement constraints come from the selected type ID. A record
      ;; named "color" must never resolve through the built-in color alias.
      (when-let [t (if (or (:base-fn-id row) (:element-fn-id row))
                     (type-check/rich-type-from-row row fn-map)
                     (:return rich))]
        {:id tid :name (:name row) :type t}))))


(defn- valid-literal?
  [t value]
  (and t (not (types/contains-marker? t))
       (or (types/subtype? (types-lit/classify-literal value) t)
           (and (types/refine-type? t)
                (types/subtype? (types-lit/classify-literal value)
                                (types/refine-base t))
                (true? (types-lit/literal-satisfies-refinement?
                         value (types/refine-constraint t)))))))


(defn literal-preview-entries
  "Bounded safe previews paired with their source nodes. Eligibility uses
   canonical slot identities and rejects markers, secret references and invalid
   refinements before any pure sanitized execution. Equal previews share the
   same budget entry, including failed evaluations."
  [ctx elements graph selector]
  (let [rows (value-form/registry-pairs ctx "_value-inline-repr-registry")
        lookups {:fn-map (into {} (map (juxt :id identity)) (:fns graph))
                 :slot-map (into {} (map (juxt :id identity)) (:slots graph))
                 :binding-by-fn-slot (into {} (map (fn [b] [[(:fn-id b) (:slot-id b)] b]))
                                           (:bindings graph))
                 :chain-cache (atom {})}
        previews (atom {})
        targets (atom {})]
    (mapv (fn [node]
            (let [{:keys [type value secretRef]} (:data node)
                  info (when (and (= type "arg") (not secretRef))
                         (effective-literal-type (:data node) lookups))
                  t (:type info)
                  target (when (valid-literal? t value)
                           (if (contains? @targets t)
                             (get @targets t)
                             (let [selected (selector {:registry rows :type-expr t})]
                               (swap! targets assoc t selected)
                               selected)))
                  k [(:id info) value]
                  repr (when target
                         (if (contains? @previews k)
                           (get @previews k)
                           (when (< (count @previews) 64)
                             (let [r (try (render-selected-repr ctx target value)
                                          (catch Exception _ nil))]
                               (swap! previews assoc k r)
                               r))))]
              {:node node :repr repr}))
          (:nodes elements))))
