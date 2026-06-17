(ns graphden.crud.types-api
  "Type-API logic for the web/crud base functions — the heavy bodies
   behind `/api/types`, `/api/types/compatible`, `/api/types/candidates`
   and `/api/types/usages`.

   Also holds the shared graph-cache loaders (`cached-or-load-graph` /
   `load-graph-entities-uncached`) and the storage-row → role / rich-type
   derivations. The graph-cache loaders are used by both this namespace
   and the higher-level `graphden.crud.entities`, so they live here, at
   the lower level of the crud.* DAG.

   Depends on `graphden.crud.request` and `graphden.crud.type-check`."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


;; === Graph-cache loaders ====================================================

(defn load-graph-entities-uncached
  "Read every slot/fn-slot/binding-model row from storage in one
   branch-cached batch when versioned, or as five vanilla queries
   otherwise. Same shape as the `:graph-cache` atom."
  [storage]
  (if (instance? VersionedStorage storage)
    (vs/query-all-graph-entities storage)
    {:fns        (vec (sp/query-entities storage :fn {}))
     :slots      (vec (sp/query-entities storage :slot {}))
     :fn-slots   (vec (sp/query-entities storage :fn-slot {}))
     :bindings   (vec (sp/query-entities storage :binding {}))
     :list-items (vec (sp/query-entities storage :binding-list-item {}))}))


(defn cached-or-load-graph
  [ctx]
  (or (exec-ctx/cached-graph ctx)
      (let [data (load-graph-entities-uncached (request/require-storage ctx))]
        (exec-ctx/fill-graph-cache! ctx data)
        data)))


(defn compute-fn-role
  "Mirrors `executor.compile-runtime/type-row-role`, with one tweak:
   `impl-hash` is currently always `nil` in storage (sync uses a
   placeholder hash impl — see `executor.composition.core/
   compute-impl-hash`), so a base-fn looks indistinguishable from a
   synthesised record in storage alone. We cross-reference the
   `rich-types-registry` snapshot: if a fn-name has a non-empty
   `:args` map and is NOT marked `:type-row?`, it's a base-fn even
   when `impl-hash` is nil. Roles: `:composed`, `:base-fn`,
   `:refinement`, `:list`, `:union`, `:variant`, `:fn-type`,
   `:record`, `:primitive`."
  [fn-row has-slots? rich-snapshot]
  (let [c (:constraint fn-row)
        rich-entry (some-> (:name fn-row) keyword rich-snapshot)
        base-fn-via-registry? (and rich-entry
                                   (not (:type-row? rich-entry))
                                   (seq (:args rich-entry)))]
    (cond
      (seq (:parent-ids fn-row))                 :composed
      (or (some? (:impl-hash fn-row))
          base-fn-via-registry?)                 :base-fn
      (some? (:base-fn-id fn-row))               :refinement
      (some? (:element-fn-id fn-row))            :list
      (and (vector? c) (= :union (first c)))     :union
      (and (vector? c) (= :variant (first c)))   :variant
      (and (vector? c) (= :fn (first c)))        :fn-type
      has-slots?                                 :record
      :else                                      :primitive)))


(defn project-rich-type-entry
  "Strip the backend-only per-base-fn type-rule fns from a single
   registry entry and replace each with a JSON-safe boolean flag.

   Why: the rule values themselves are Clojure functions (not JSON-
   encodable) and the editor never needs them; it reads only
   :return / :args / :effects / :description. The flag preserves the
   *fact* of a rule's presence so the editor's return-type provenance
   popover can attribute computed return-types to the originating
   base-fn without round-tripping the (non-serializable) impl.

   Exposed for tests — the wire shape of `/api/types` depends on this
   projector being applied to every entry."
  [entry]
  (cond-> entry
    (:return-type-rule entry)
    (-> (dissoc :return-type-rule) (assoc :has-return-type-rule? true))
    (:slot-types-rule entry)
    (-> (dissoc :slot-types-rule) (assoc :has-slot-types-rule? true))
    (:nav-types-rule entry)
    (-> (dissoc :nav-types-rule) (assoc :has-nav-types-rule? true))))


(defonce ^:private rich-types-with-type-rows-cache
  ;; Single-entry identity-keyed cache. Holds `[raw-snapshot graph result]`.
  ;; Both inputs are atom-deref'd values that stay reference-stable
  ;; between their respective invalidations (rich-types registry
  ;; reset / graph-cache reload), so adjacent
  ;; `/api/types/candidates` calls during one mutation window hit
  ;; the cache. Cache miss is the same cost as the uncached path.
  (atom nil))


(declare ^:private rich-types-with-type-rows-uncached)


(defn rich-types-with-type-rows
  "Augment the in-memory rich-type registry with structural definitions
   for storage-only type-rows (refinements, list types, records). The
   registry built by the type-checker only carries fn / fn-def entries;
   refinement types like `:port` show up as bare keywords inside other
   fns' rich-type bodies but have no top-level entry. The editor's
   refinement-aware mismatch indicator and value-validation hint look
   up `:port` → expect `[:refine :int [:and [:>= 1] [:<= 65535]]]`,
   so we expose the structural form alongside the existing entries."
  [ctx]
  (let [raw-snapshot (registry/rich-types-snapshot)
        graph (cached-or-load-graph ctx)
        [cs cg cr] @rich-types-with-type-rows-cache]
    (if (and (identical? cs raw-snapshot) (identical? cg graph))
      cr
      (let [result (rich-types-with-type-rows-uncached raw-snapshot graph)]
        (reset! rich-types-with-type-rows-cache [raw-snapshot graph result])
        result))))


(defn- constraint-tagged?
  "True when `f`'s `:constraint` is a vector whose first element is
   `tag` — the shape of structural-type markers (`[:union …]`,
   `[:variant …]`, `[:fn …]`, `[:map …]`, `[:tuple …]`)."
  [f tag]
  (and (vector? (:constraint f))
       (= tag (first (:constraint f)))))


(defn- marker-type?
  "Marker-bearing rows carry their structural form via FK fields
   (`:base-fn-id` / `:element-fn-id`) or a tagged `:constraint`
   vector. Used to skip the `record-shape` interpretation for type
   rows that would otherwise misclassify (e.g. `:positive-int` has a
   synthesised `:value` slot that `record-shape` would read as the
   record `{:value :int}`, losing the refinement constraint)."
  [f]
  (or (some? (:base-fn-id f))
      (some? (:element-fn-id f))
      (constraint-tagged? f :union)
      (constraint-tagged? f :variant)
      (constraint-tagged? f :fn)
      (constraint-tagged? f :map)
      (constraint-tagged? f :tuple)))


(defn- type-row?
  "Fn rows we'll surface as type-rows in the augmented snapshot:
   - refinements / lists / unions / variants (marker-bearing), OR
   - genuine record-types (no parent-ids, no impl-hash, has fn-slots)."
  [f slots-by-fn]
  (and (:name f)
       (or (marker-type? f)
           (and (empty? (:parent-ids f))
                (nil? (:impl-hash f))
                (seq (get slots-by-fn (:id f)))))))


(defn- record-shape
  "Project a fn-row's own slots into the record map
   `{slot-name slot-type-name}`, in `:position` order. Returns nil
   when the fn-row owns no slots (every other shape is handled by
   `rich-type-from-row`)."
  [f slots-by-fn slot-by-id fns-by-id]
  (when-let [own (seq (get slots-by-fn (:id f)))]
    (into {}
          (keep (fn [fs]
                  (when-let [s (get slot-by-id (:slot-id fs))]
                    (when-let [tn (some-> (:type-fn-id s)
                                          fns-by-id
                                          :name
                                          keyword)]
                      [(keyword (:name s)) tn]))))
          (sort-by :position own))))


(defn- type-row-entry
  "Augment-snapshot entry for a single type-row. Returns nil when the
   existing snapshot already has a CALLABLE entry the augmentation
   would shadow (non-empty :args, non-`:any` :return)."
  [f acc slots-by-fn slot-by-id fns-by-id]
  (let [;; Marker-bearing rows (refinement / list / union / variant /
        ;; …) carry their structural form via `rich-type-from-row` —
        ;; `record-shape` would misclassify them. Fall back to
        ;; `record-shape` only when no marker FK / tag is present.
        structural (if (marker-type? f)
                     (tc/rich-type-from-row f fns-by-id)
                     (or (record-shape f slots-by-fn slot-by-id fns-by-id)
                         (tc/rich-type-from-row f fns-by-id)))
        n (some-> (:name f) keyword)
        existing (get acc n)
        ;; A real type-row's registry entry (when one exists from a
        ;; prior pass) has empty :args — type-rows aren't called,
        ;; they're just shapes. Base-fns whose declared `:return-type
        ;; :any` would otherwise match the override criterion (`:invoke`,
        ;; `:call`, …) would get clobbered by the structural-shape
        ;; override and lose their real args. The empty-args guard
        ;; keeps them out.
        real-type-row? (or (nil? existing)
                           (and (= :any (:return existing))
                                (empty? (:args existing))))]
    (when (and n structural real-type-row?)
      ;; `:type-row? true` marks augmented entries so callers (e.g.
      ;; `types-candidates`) can skip them — a type-row isn't itself
      ;; callable, just a shape. Real fns whose declared return
      ;; happens to be a structural type come through the original
      ;; `record-rich-types-raw!` path and have no `:type-row?` flag,
      ;; so they stay candidate-eligible.
      [n (cond-> {:return structural :args {} :effects #{}
                  :type-row? true}
           (and (:description f) (seq (:description f)))
           (assoc :description (:description f)))])))


(defn- rich-types-with-type-rows-uncached
  [raw-snapshot graph]
  (let [snapshot    (update-vals raw-snapshot project-rich-type-entry)
        fns         (:fns graph)
        fns-by-id   (into {} (map (juxt :id identity)) fns)
        slot-by-id  (into {} (map (juxt :id identity)) (:slots graph))
        slots-by-fn (group-by :fn-id (:fn-slots graph))]
    ;; Single pass: walk every fn, skip non-type-rows inline. Cache-
    ;; miss path — acceptable perf — but one reduce reads cleaner
    ;; than the prior filter-then-reduce two-walk.
    (reduce (fn [acc f]
              (if-not (type-row? f slots-by-fn)
                acc
                (if-let [[n entry] (type-row-entry f acc slots-by-fn slot-by-id fns-by-id)]
                  (assoc acc n entry)
                  acc)))
            snapshot
            fns)))


;; === Type-API helpers (Phase 1: type-aware UI integration) ===

;; Constraint ops whose operands are themselves sub-constraints (and so
;; recurse). Every other op — comparison, membership, regex — carries
;; literal VALUE operands. Mirrors the `:and`/`:or` recursion in
;; `graphden.types.core/constraint-implies?`.
(def ^:private logical-constraint-ops
  #{:and :or :not})


(declare json->type)


(defn- keyword-domain?
  "True when a refinement's (already-decoded) base type bottoms out at
   `:keyword` — meaning its constraint operands are keyword values
   (`[:in [:get :post]]`, `[:= :ok]`) rather than string literals.

   Recurses through nested refinements: `rich-type-from-row` always
   emits a fully-resolved base, so only a primitive keyword or a
   `[:refine …]` vector can appear here — never a bare type name."
  [base]
  (cond
    (= base :keyword) true
    (and (vector? base) (= :refine (first base))) (recur (second base))
    :else false))


(defn- json->constraint
  "Decode the constraint slot of a `[:refine base C]` wire shape.

   JSON can't distinguish a keyword from a string, so a blind decode
   corrupts value-carrying constraints. The type grammar resolves it:

   - The operator is always at position 0 → always a keyword.
   - The refinement BASE type fixes the value domain. A `:keyword`
     base means the operands are keyword values (`[:in [:get :post]]`,
     `[:= :ok]`); any other base means a string operand is a genuine
     literal (`[:not= \"\"]`, `[:= \"x\"]`, `[:matches \"re\"]`) and
     must survive verbatim.

   Logical ops (`:and`/`:or`/`:not`) carry sub-constraints and recurse;
   every other op carries values, with `:in` carrying a value
   collection. The head is keywordised whether or not it's recognised,
   so a future op can't silently decode to a string."
  [kw-vals? c]
  (if-not (and (sequential? c) (seq c))
    c
    (let [op (let [h (first c)] (if (string? h) (keyword h) h))
          decode-val (fn [v] (if (and kw-vals? (string? v)) (keyword v) v))]
      (if (logical-constraint-ops op)
        (into [op] (map #(json->constraint kw-vals? %)) (rest c))
        (into [op]
              (map (fn [o]
                     (if (sequential? o)
                       (mapv decode-val o)   ; :in value collection
                       (decode-val o))))
              (rest c))))))


(defn json->type
  "Inverse of cheshire's default Clojure→JSON encoding for type
   expressions. The wire format is whatever `(rich-types-with-type-rows)`
   yields after JSON round-trip:
     :int                    →  \"int\"               →  :int
     [:fn {:x :int} :int]    →  [\"fn\", {\"x\":\"int\"}, \"int\"]
                                                      →  [:fn {:x :int} :int]
     [:refine :int [:>= 0]]  →  [\"refine\", \"int\", [\">=\", 0]]
                                                      →  [:refine :int [:>= 0]]
     {:a :int :b :text}      →  {\"a\":\"int\", \"b\":\"text\"}
                                                      →  {:a :int :b :text}

   Strings become keywords, map keys become keywords, vectors recurse,
   numbers / booleans / nil pass through — EXCEPT inside a refinement
   constraint, where `json->constraint` uses the refinement base type
   to keep string literal values intact. A blind decode would turn
   `[:not= \"\"]` into `[:not= :]` and silently break `:non-empty-text`."
  [x]
  (cond
    (string? x) (keyword x)

    (map? x)
    (into {}
          (map (fn [[k v]] [(if (string? k) (keyword k) k) (json->type v)]))
          x)

    ;; Refinement — decode `base` as a type, then hand the constraint
    ;; to `json->constraint` with the base as the disambiguating
    ;; context. Everything else (union / variant / fn / list / record)
    ;; carries only types and recurses uniformly below.
    (and (vector? x) (#{:refine "refine"} (first x)))
    (let [base (json->type (second x))]
      [:refine base (json->constraint (keyword-domain? base) (nth x 2 nil))])

    (sequential? x) (mapv json->type x)

    :else x))


(defn describe-mismatch
  "One-line human-readable explanation for why `candidate` is NOT a
   subtype of `expected`. Best-effort — the type-checker proper
   (in graphden.types.check) emits richer contextual messages but
   they require a binding context we don't have at the API layer.

   For UI use: enough to render \"why is this dimmed in the picker?\"
   tooltip text. The structured `expected` and `candidate` types
   accompany this string in the response so the UI can render its
   own details if it wants to."
  [expected candidate]
  (cond
    (= expected :any)
    "every type is a subtype of :any"

    (= candidate :any)
    (str ":any is not a subtype of " (pr-str expected)
         " — :any can't narrow to a concrete type without an explicit cast")

    (and (types/refine-type? expected) (not (types/refine-type? candidate)))
    (str (pr-str candidate) " lacks the refinement constraint required by "
         (pr-str expected))

    (and (types/refine-type? candidate) (types/refine-type? expected)
         (not= (types/refine-constraint candidate)
               (types/refine-constraint expected)))
    (str "refinement constraints differ — "
         (pr-str (types/refine-constraint candidate))
         " ≠ " (pr-str (types/refine-constraint expected)))

    (and (types/primitive? candidate) (types/primitive? expected))
    (str (pr-str candidate) " is not a primitive subtype of " (pr-str expected))

    (and (types/fn-type? candidate) (types/fn-type? expected))
    (str "function signature mismatch — "
         (pr-str candidate) " is not a subtype of " (pr-str expected))

    :else
    (str (pr-str candidate) " is not a subtype of " (pr-str expected))))


(defn constraint-contains-type-ref?
  "Walk a `constraint` payload (`[:union T1 T2 …]`, `[:variant tag1 T1
   tag2 T2 …]`, `[:fn {arg T} ret eff-set]`) and return true if any
   nested keyword/string entry resolves to `type-name`."
  [constraint type-name]
  (let [target (some-> type-name name)]
    (boolean
      (when target
        (letfn [(matches?
                  [x]
                  (cond
                    (keyword? x) (= (name x) target)
                    (string? x)  (= x target)
                    :else        false))
                (walk
                  [x]
                  (cond
                    (matches? x)             true
                    (or (vector? x) (seq? x)) (some walk x)
                    (map? x)                 (or (some walk (keys x))
                                                 (some walk (vals x)))
                    :else                    false))]
          (walk constraint))))))


;; === Heavy logic bodies behind the type-API defbases ========================

;; --- types-compatible — parse → validate → apply (single-pair
;; subtype check). `validate-*` returns the `{:ok false :error}`
;; rejection directly (or nil); `apply-*` is reached only when valid.

(defn parse-types-compatible-request
  "Stage 1 of types-compatible — JSON body → `{:expected :candidate}`,
   each decoded from the wire shape via `json->type`."
  [request]
  (let [body (request/read-json-body request)]
    {:expected (json->type (:expected body))
     :candidate (json->type (:candidate body))}))


(defn validate-types-compatible
  "Stage 2 of types-compatible. Returns the `{:ok false :error}`
   rejection response, or nil when both sides are present."
  [parsed]
  (cond
    (nil? (:expected parsed))
    {:ok false :error "Request body must include 'expected'"}

    (nil? (:candidate parsed))
    {:ok false :error "Request body must include 'candidate'"}

    :else nil))


(defn apply-types-compatible
  "Stage 3 of types-compatible — the subtype check. Reached only after
   `validate-types-compatible` passes."
  [parsed]
  (let [{:keys [expected candidate]} parsed
        ok? (types/subtype? candidate expected)]
    (cond-> {:ok ok?
             :expected expected
             :candidate candidate}
      (not ok?)
      (assoc :reason (describe-mismatch expected candidate)))))


;; --- types-candidates — parse → validate → apply (enumerate every fn
;; whose return type is a subtype of `expected`, optionally filtered).

(defn parse-types-candidates-request
  "Stage 1 of types-candidates — JSON body → `{:expected
   :allowed-effects :name-prefix}`."
  [request]
  (let [body (request/read-json-body request)]
    {:expected (json->type (:expected body))
     :allowed-effects (when-let [effs (:effects body)]
                        (set (map (fn [e] (if (string? e) (keyword e) e))
                                  effs)))
     :name-prefix (some-> (:name-prefix body) str)}))


(defn validate-types-candidates
  "Stage 2 of types-candidates. Returns the `{:ok false :error}`
   rejection response, or nil when `expected` is present."
  [parsed]
  (when (nil? (:expected parsed))
    {:ok false :error "Request body must include 'expected'"}))


(defn apply-types-candidates
  "Stage 3 of types-candidates — enumerate matching fns. Reached only
   after `validate-types-candidates` passes."
  [parsed ctx]
  (let [{:keys [expected allowed-effects name-prefix]} parsed
        registry-snapshot (rich-types-with-type-rows ctx)
        candidates
        (->> registry-snapshot
             (keep (fn [[fn-name {:keys [return args effects type-row?]}]]
                     (let [eff-set (or effects #{})
                           name-str (some-> fn-name name)]
                       (when (and (not type-row?) ; type-rows aren't callable producers
                                  (types/subtype? return expected)
                                  (or (nil? allowed-effects)
                                      (every? allowed-effects eff-set))
                                  (or (nil? name-prefix)
                                      (and name-str
                                           (str/starts-with? name-str name-prefix))))
                         {:name fn-name
                          :return return
                          :args (or args {})
                          :effects (vec (sort eff-set))}))))
             (sort-by (fn [c] (some-> c :name name))))]
    {:ok true
     :expected expected
     :count (count candidates)
     :candidates (vec candidates)}))


;; --- types-usages — parse → validate → apply (find every place a
;; type-row is referenced).

(defn parse-types-usages-request
  "Stage 1 of types-usages — JSON body → `{:target-id}` (the
   `type-fn-id` coerced to a UUID, or nil when absent / malformed)."
  [request]
  (let [body (request/read-json-body request)
        target-id-raw (:type-fn-id body)]
    {:target-id (request/parse-uuid-or-clear (some-> target-id-raw str))}))


(defn validate-types-usages
  "Stage 2 of types-usages. Returns the `{:ok false :error}` rejection
   response, or nil when a valid `type-fn-id` was supplied."
  [parsed]
  (when (nil? (:target-id parsed))
    {:ok false :error "Request body must include valid 'type-fn-id'"}))


(defn apply-types-usages
  "Stage 3 of types-usages — walk the graph for every reference to the
   target type-row. Reached only after `validate-types-usages` passes."
  [parsed ctx]
  (let [target-id (:target-id parsed)
        {:keys [fns slots fn-slots bindings]} (cached-or-load-graph ctx)
        fn-by-id (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        slot-owner-by-id (into {} (map (juxt :slot-id :fn-id)) fn-slots)
        target-fn (get fn-by-id target-id)
        target-name (some-> target-fn :name)
        fn-summary (fn [fid kind & [extra]]
                     (let [f (get fn-by-id fid)]
                       (merge {:fn-id (str fid)
                               :fn-name (or (:name f) "(anonymous)")
                               :role (:role f)
                               :kind kind}
                              (or extra {}))))
        base-uses (->> fns
                       (filter #(= (:base-fn-id %) target-id))
                       (map #(fn-summary (:id %) :base-of)))
        elem-uses (->> fns
                       (filter #(= (:element-fn-id %) target-id))
                       (map #(fn-summary (:id %) :element-of)))
        ret-uses (->> fns
                      (filter #(= (:return-type-fn-id %) target-id))
                      (map #(fn-summary (:id %) :return-of)))
        constraint-uses
        (->> fns
             (filter (fn [f]
                       (and (vector? (:constraint f))
                            target-name
                            (constraint-contains-type-ref? (:constraint f) target-name))))
             (mapcat (fn [f]
                       (let [kind (case (first (:constraint f))
                                    :union :union-branch
                                    :variant :variant-branch
                                    :fn :fn-type-arg-or-return
                                    :other)]
                         [(fn-summary (:id f) kind)]))))
        slot-uses (->> slots
                       (filter #(= (:type-fn-id %) target-id))
                       (mapcat (fn [s]
                                 (when-let [owner-id (get slot-owner-by-id (:id s))]
                                   [(fn-summary owner-id :slot-of
                                                {:slot-name (:name s)})]))))
        binding-uses (->> bindings
                          (filter #(= (:type-override-fn-id %) target-id))
                          (map (fn [b]
                                 (let [s (get slot-by-id (:slot-id b))]
                                   (fn-summary (:fn-id b) :binding-of
                                               {:slot-name (:name s)})))))
        usages (vec (concat base-uses elem-uses ret-uses
                            constraint-uses slot-uses binding-uses))]
    {:ok true
     :type-fn-id (str target-id)
     :type-name (some-> target-name str)
     :count (count usages)
     :usages usages}))


(defn all-rich-types
  "Body of the `all-rich-types` base-fn. Snapshot of the in-memory
   rich-type registry, augmented with structural definitions for
   storage-only type-rows."
  [ctx]
  (rich-types-with-type-rows ctx))
