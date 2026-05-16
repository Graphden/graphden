(ns graphden.executor.registry.core
  "Core infrastructure for base function registration + storage sync.

   ## New slot/binding model

   Base functions get synced as fn rows with `impl-hash` set,
   accompanied by slot/fn-slot rows produced by
   `graphden.packages.records/parse-fn-def`. Type-rows (record /
   refinement / list) take the same path: their role is encoded in the
   fn-row's `:base-fn-id` / `:element-fn-id` / `:constraint` /
   non-empty `:fn-slot` rows. The old `:arg` table is gone.

   This namespace owns:
   - register-base-fns! — Clojure impl registration (executor's atom).
   - sync-defs-to-storage! — `{fn-name → fn-def}` to records, then
     batch upsert via `composition/write-records!`.
   - rich-types-registry — in-memory `{fn-name → {:return :args :effects}}`
     map kept in sync with the source-of-truth fn-defs. The type-checker
     reads from here; storage rows degrade structural types to a single
     primitive `value-kind`."
  (:require
    [clojure.walk :as walk]
    [graphden.executor.composition.core :as composition]
    [graphden.executor.interface :as exec]
    [graphden.packages.records :as records]
    [graphden.types.core :as types]))


;; =============================================================================
;; Implementation Hash
;; =============================================================================

(defn- sort-maps-recursively
  [form]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into (sorted-map) x)
        x))
    form))


(defn compute-impl-hash
  "SHA-256 of a base function's canonical form. Detects body / args /
   return-type drift; ignores whitespace, comments, map-key ordering."
  [{:keys [args return-type impl-source]}]
  (records/digest-hex
    "SHA-256"
    (pr-str {:args (sort-maps-recursively args)
             :return-type return-type
             :impl-source (when impl-source
                            (mapv sort-maps-recursively impl-source))})))


;; =============================================================================
;; Synthesised impls for type-rows (record / refinement / list)
;; =============================================================================

(defn- record-type-impl
  "Synthesised impl for a record-type fn-row. The args map IS the
   record — every field already present as a key. Resolve any delays
   the executor passed and return the assembled map."
  [args _ctx]
  (into {} (map (fn [[k v]] [k (force v)])) args))


(defn- refinement-type-impl
  "Synthesised impl for a refinement-type fn-row. Single arg :value;
   validate against the closed-over constraint, throw on violation,
   pass-through otherwise. `:unknown` results pass through — best-effort."
  [constraint]
  (fn [args _ctx]
    (let [v (force (:value args))
          check-fn (requiring-resolve 'graphden.types.check/literal-satisfies-refinement?)
          result (check-fn v constraint)]
      (when (false? result)
        (throw (ex-info (str "refinement constraint failed: "
                             (pr-str constraint) " on value " (pr-str v))
                        {:type :refinement/violated
                         :constraint constraint
                         :value v})))
      v)))


(defn- synthesised-impl-for
  "Type-row markers `:type {…}` (record), `:refine {…}` (refinement),
   `:list T` (list) get an auto-generated impl. Other fn-defs return
   the user-provided `:impl`."
  [fn-def]
  (cond
    (:type fn-def)   record-type-impl
    (:refine fn-def) (refinement-type-impl (:constraint (:refine fn-def)))
    (:list fn-def)   (fn [args _ctx] (force (:items args)))
    :else            (:impl fn-def)))


(defn register-base-fns!
  "Registers Clojure impls in the executor's global registry. Type-rows
   (no `:impl` key) get a synthesised impl matching their role."
  [defs]
  (doseq [[fn-name fn-def] defs
          :let [impl (synthesised-impl-for fn-def)]
          :when impl]
    (exec/register-base-fn! fn-name impl)))


;; =============================================================================
;; Rich-types registry — in-memory map
;; =============================================================================
;;
;; Storage's `value-kind` enum loses structure (`:fn` instead of
;; `[:fn args ret]`). The rich shape lives here so save-time type
;; checking can do real subtype/unify against the original. Empty
;; until `sync-defs-to-storage!` populates it.

(defonce ^:private rich-types-registry (atom {}))


(defn- validate-arg-type!
  [arg-name arg-type]
  ;; Accept primitives, type-vars, structural types — and any other
  ;; keyword (which may name a user-declared refinement / record /
  ;; list / union / variant fn-row). Genuine typos surface at the
  ;; records-parser stage, where unknown keyword references throw
  ;; `:records/unknown-type-ref`.
  (when-not (or (keyword? arg-type)
                (types/well-formed? arg-type))
    (throw (ex-info (str "Unknown arg type: " (pr-str arg-type))
                    {:type :invalid-arg-type
                     :arg-name arg-name
                     :arg-type arg-type}))))


(defn- arg-spec->rich-type
  "Extracts the structural rich-type from an arg-spec. Accepts:
     :int / :fn / …                       primitive keyword
     :positive-int                        type alias / named refinement
     'a                                   type variable
     [:fn args ret] / [:list T] / …       structural
     {:type T :required B :description S} loader's expanded form
     {:k :int}                            inline record"
  [arg-name arg-spec]
  (cond
    (or (keyword? arg-spec) (symbol? arg-spec) (vector? arg-spec))
    (let [resolved (types/resolve-alias arg-spec)]
      (validate-arg-type! arg-name resolved)
      resolved)

    (map? arg-spec)
    (cond
      (contains? arg-spec :type)
      (let [arg-type (types/resolve-alias (:type arg-spec))]
        (validate-arg-type! arg-name arg-type)
        arg-type)

      (types/well-formed? arg-spec)
      arg-spec

      :else
      (throw (ex-info "arg-spec map must contain :type key"
                      {:type :invalid-arg-spec
                       :arg-name arg-name
                       :arg-spec arg-spec})))

    :else
    (throw (ex-info "arg-spec must be a keyword or map with :type"
                    {:type :invalid-arg-spec
                     :arg-name arg-name
                     :arg-spec arg-spec}))))


(defn record-rich-types!
  "Snapshot the structured types for one fn-def into the in-memory
   registry. Idempotent — re-syncing overwrites. Aliases are resolved
   to their structural body so downstream type-check sees the canonical
   shape.

   `:effects` is recorded straight from the fn-def as a set of keyword
   tags. `:effectful? true` legacy boolean normalises to `#{:effect}`.
   `:description` is propagated so the editor's inline-expand panel
   can surface a human-readable hint without a separate API call.

   `:return-type-rule` / `:slot-types-rule` / `:nav-types-rule` —
   per-base-fn type-rules declared at the base-fn's `impls.clj`
   registration site. When present they ride into the registry entry
   so the type-checker looks them up by base-fn identity instead of
   dispatching a multimethod on the fn name."
  [fn-name fn-def]
  (let [args (:args fn-def)
        ret  (some-> (:return-type fn-def) types/resolve-alias)
        per-arg (into {}
                      (map (fn [[arg-name arg-spec]]
                             [arg-name (or (arg-spec->rich-type arg-name arg-spec) :any)]))
                      args)
        effects (cond
                  (:effects fn-def)    (set (:effects fn-def))
                  (:effectful? fn-def) #{:effect}
                  :else                #{})
        desc (:description fn-def)]
    (swap! rich-types-registry assoc fn-name
           (cond-> {:return (or ret :any)
                    :args   per-arg}
             (seq effects)              (assoc :effects effects)
             (and desc (seq desc))      (assoc :description desc)
             (:return-type-rule fn-def) (assoc :return-type-rule
                                               (:return-type-rule fn-def))
             (:slot-types-rule fn-def)  (assoc :slot-types-rule
                                               (:slot-types-rule fn-def))
             (:nav-types-rule fn-def)   (assoc :nav-types-rule
                                               (:nav-types-rule fn-def))))))


(defn effectful-rich-type?
  "True iff the entry mentions any effect tag."
  [info]
  (boolean (seq (:effects info))))


(defn record-rich-types-raw!
  "Stash a pre-computed `{:return … :args …}` map directly. Used by the
   type-checker for composed fn-defs whose computed types come from
   unification."
  [fn-name rich-type-map]
  (swap! rich-types-registry assoc fn-name rich-type-map))


(defn rich-type-of
  ([fn-name]            (get @rich-types-registry fn-name))
  ([fn-name arg-name]   (get-in @rich-types-registry [fn-name :args arg-name])))


(defn rich-types-snapshot
  []
  @rich-types-registry)


;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-fn-def!
  [fn-name fn-def]
  (when-not (keyword? fn-name)
    (throw (ex-info "fn-name must be a keyword"
                    {:type :invalid-fn-def
                     :fn-name fn-name})))
  ;; Type-rows (`:type {…}` / `:refine {…}` / `:list T`) carry their
  ;; role explicitly and don't need a separate `:return-type`.
  (when-not (or (:type fn-def) (:refine fn-def) (:list fn-def)
                (:union fn-def) (:variant fn-def))
    (when-not (:return-type fn-def)
      (throw (ex-info "Function definition must include :return-type"
                      {:type :invalid-fn-def
                       :fn-name fn-name})))
    (let [resolved-return (types/resolve-alias (:return-type fn-def))]
      (when-not (or (keyword? resolved-return)
                    (types/well-formed? resolved-return))
        (throw (ex-info (str "Unknown return type: " (pr-str (:return-type fn-def)))
                        {:type :invalid-return-type
                         :fn-name fn-name
                         :return-type (:return-type fn-def)})))))
  ;; Refinement-specific: catch nonsense like `{:base :text :constraint
  ;; [:>= 0]}` at sync time rather than letting it silently land in
  ;; storage and confuse downstream type-checking / runtime narrowing.
  (when-let [refine (:refine fn-def)]
    (let [base (:base refine)
          constraint (:constraint refine)
          check-fn (requiring-resolve 'graphden.types.check/constraint-compatible-with-base?)]
      (when (and constraint check-fn
                 (not (check-fn base constraint)))
        (throw (ex-info (str "Refinement constraint " (pr-str constraint)
                             " uses operators not valid on base " (pr-str base))
                        {:type :invalid-refinement-constraint
                         :fn-name fn-name
                         :base base
                         :constraint constraint})))))
  (doseq [[arg-name arg-spec] (:args fn-def)]
    (arg-spec->rich-type arg-name arg-spec)))


(defn validate-all-defs!
  [defs]
  (doseq [[fn-name fn-def] defs]
    (validate-fn-def! fn-name fn-def)))


;; =============================================================================
;; Storage sync
;; =============================================================================

(defn- def-pair->fn-def-record
  "Convert one `[fn-name fn-def]` from the loader's map into a fn-def
   record the records-parser understands. Loader's normalize-args has
   already expanded args to `{arg-name {:type T :required B}}`."
  [[fn-name fd]]
  (assoc fd :name fn-name))


(defn sync-defs-to-storage!
  "Sync fn-defs to storage via the records-parser pipeline. Both
   base-fn defs ({fn-name → fn-def} from the loader) and type-rows
   declared inline within `fns.edn` flow through here. Idempotent —
   deterministic UUIDs from `records/fn-id`.

   Returns the fn-name→id map for the synced rows so the caller can
   pass it as `extra-name->id` to a downstream composed-fn sync.

   `extra-name->id` (optional) — names already known from prior syncs
   (or pre-computed from peer fn-defs). Used to resolve cross-module
   references like a base-fn's `:return-type` pointing at a type-row
   declared in another module."
  ([storage defs]
   (sync-defs-to-storage! storage defs {} {}))
  ([storage defs ns-id-map]
   (sync-defs-to-storage! storage defs ns-id-map {}))
  ([storage defs ns-id-map extra-name->id]
   (validate-all-defs! defs)
   (doseq [[fn-name fn-def] defs]
     (record-rich-types! fn-name fn-def))
   (let [fn-def-records (mapv def-pair->fn-def-record defs)
         records-list (records/parse-module fn-def-records extra-name->id)
         name->id (composition/write-records! storage records-list ns-id-map)]
     name->id)))


(defn sync-primitives!
  "Pre-seed the 14 primitive fn-rows. Should run once at storage init,
   before any base-fns or composed fn-defs sync. Idempotent."
  [storage]
  (composition/sync-primitives! storage))


;; =============================================================================
;; Re-exports kept for downstream compatibility
;; =============================================================================

(def type->storage-kind types/type->storage-kind)


(defn fn-uuid
  "Deterministic UUID for a globally-named fn — namespace-less. Tests
   call this; production paths use `records/fn-id` with the actual
   namespace path."
  [fn-name]
  (records/fn-id nil fn-name))
