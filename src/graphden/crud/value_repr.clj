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
   - Nothing here ever throws: any failure logs and returns nil, and
     the result pane falls back to the shape-based panes."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.crud.value-form :as value-form]
    [graphden.executor.interface :as executor]
    [graphden.executor.registry.core :as registry]
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


(defn dispatch-type
  "The type repr dispatch runs on: the alias-resolved declared type
   when it says something (`nil`/`:any` don't), else the runtime
   value's shape — a keyword-map list dispatches as
   `[:list [:map :keyword :any]]`, everything else via the literal
   classifier. nil when nothing knows."
  [declared value]
  (let [d (some-> declared types/resolve-alias)]
    (cond
      (and (some? d) (not= :any d)) d
      (record-list? value) [:list [:map :keyword :any]]
      :else (types-lit/classify-literal value))))


(defn render-repr
  "Resolve + execute + sanitize the registered representation of
   `value` as returned by fn `fn-id`. Returns sanitized hiccup, or
   nil when: value is nil, no registry row accepts the dispatch type,
   the target attempts ANY effect, the target's output sanitizes to
   nothing, or anything throws (logged).

   Purity is enforced by the executor's own effect gate — the repr
   subtree runs under `:allowed-effects #{}`, so the first
   `record-effect!` of any category throws `:execution/forbidden-effect`
   (docs/TENANCY_SEAM.md § Effect gate). Enforcing at the runtime
   layer (not a registry lookup by NAME) keeps this id-correct per
   ADR-identity-model and fail-closed by construction."
  [ctx value fn-id]
  (try
    (when (some? value)
      (when-let [dt (dispatch-type (declared-return-type fn-id) value)]
        (when-let [fn-name (value-form/pick-form-fn
                             (value-form/registry-pairs ctx registry-fn-name)
                             dt)]
          (some-> (executor/execute-by-name
                    (assoc ctx :allowed-effects #{})
                    fn-name {:value value})
                  sanitize/sanitize-hiccup))))
    (catch Exception e
      (log/warn e "value-repr render failed — falling back to shape pane")
      nil)))
