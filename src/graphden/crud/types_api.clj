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
  (let [;; Strip the backend-only per-base-fn type-rule fns from each
        ;; registry entry — they are Clojure functions (not JSON-
        ;; encodable) and the editor never needs them; it reads only
        ;; :return / :args / :effects / :description.
        snapshot (update-vals (registry/rich-types-snapshot)
                              #(dissoc % :return-type-rule
                                       :slot-types-rule :nav-types-rule))
        ;; Reuse the shared graph-cache instead of re-querying :fn —
        ;; chain walks over `:base-fn-id` / `:element-fn-id` resolve
        ;; in memory via `fns-by-id`.
        graph (cached-or-load-graph ctx)
        fns (:fns graph)
        slots (:slots graph)
        fn-slots (:fn-slots graph)
        fns-by-id (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        slots-by-fn (group-by :fn-id fn-slots)
        ;; Type-rows we'll surface:
        ;; - refinements (have :base-fn-id)
        ;; - list-types (have :element-fn-id)
        ;; - record-types (empty parent-ids + no impl-hash + has fn-slots)
        ;; - union / variant / fn-type rows (have :constraint shaped as
        ;;   `[:union …]` / `[:variant …]` / `[:fn args ret]` —
        ;;   payload goes through `rich-type-from-row` which knows
        ;;   all three shapes)
        constraint-tagged?
        (fn [f tag]
          (and (vector? (:constraint f))
               (= tag (first (:constraint f)))))
        type-row?
        (fn [f]
          (and (:name f)
               (or (some? (:base-fn-id f))
                   (some? (:element-fn-id f))
                   (constraint-tagged? f :union)
                   (constraint-tagged? f :variant)
                   (constraint-tagged? f :fn)
                   (and (empty? (:parent-ids f))
                        (nil? (:impl-hash f))
                        (seq (get slots-by-fn (:id f)))))))
        type-rows (filter type-row? fns)
        record-shape
        (fn [f]
          (when-let [own (seq (get slots-by-fn (:id f)))]
            (into {}
                  (keep (fn [fs]
                          (when-let [s (get slot-by-id (:slot-id fs))]
                            (when-let [tn (some-> (:type-fn-id s)
                                                  fns-by-id
                                                  :name
                                                  keyword)]
                              [(keyword (:name s)) tn]))))
                  (sort-by :position own))))]
    (reduce (fn [acc f]
              (let [;; Marker-bearing rows (refinement / list / union /
                    ;; variant) carry their structural form via
                    ;; `rich-type-from-row` — `record-shape` would
                    ;; misclassify e.g. `:positive-int` (which has a
                    ;; synthesised `:value` slot for the inner-type
                    ;; binding) as the record `{:value :int}`,
                    ;; losing the refinement constraint. Only fall
                    ;; back to `record-shape` when no marker FK / tag
                    ;; is present — the genuine record case.
                    marker? (or (:base-fn-id f)
                                (:element-fn-id f)
                                (constraint-tagged? f :union)
                                (constraint-tagged? f :variant)
                                (constraint-tagged? f :fn))
                    structural (if marker?
                                 (tc/rich-type-from-row f fns-by-id)
                                 (or (record-shape f)
                                     (tc/rich-type-from-row f fns-by-id)))
                    n (some-> (:name f) keyword)
                    existing (get acc n)
                    ;; A real type-row's registry entry (when one
                    ;; exists from a prior pass) has empty :args —
                    ;; type-rows aren't called, they're just shapes.
                    ;; Base-fns whose declared `:return-type :any`
                    ;; would otherwise match the override criterion
                    ;; (`:invoke`, `:call`, …) — they'd get clobbered
                    ;; by the structural-shape override and lose
                    ;; their real args. The empty-args guard keeps
                    ;; them out.
                    real-type-row? (or (nil? existing)
                                       (and (= :any (:return existing))
                                            (empty? (:args existing))))]
                ;; Prefer the structural form ([:refine …] / [:list …]
                ;; / record map) over a registry entry that just
                ;; records `:return :any` — without that, the type-
                ;; checker pass on a refinement / record type-row
                ;; overwrites the structural entry with a stub and the
                ;; editor loses constraint info.
                ;;
                ;; `:type-row? true` marks augmented entries so callers
                ;; (e.g. `types-candidates`) can skip them — a type-row
                ;; isn't itself callable, just a shape. Real fns whose
                ;; declared return happens to be a structural type
                ;; (`(:return-type :positive-int)` etc.) come through
                ;; the original `record-rich-types-raw!` path and have
                ;; no `:type-row?` flag, so they stay candidate-eligible.
                (if (and n structural real-type-row?)
                  (assoc acc n (cond-> {:return structural :args {} :effects #{}
                                        :type-row? true}
                                 (and (:description f)
                                      (seq (:description f)))
                                 (assoc :description (:description f))))
                  acc)))
            snapshot
            type-rows)))


;; === Type-API helpers (Phase 1: type-aware UI integration) ===

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
   numbers / booleans / nil pass through. Strings inside refinement
   constraints (rare) WILL be keywordised — refinements are mostly
   numeric so the trade-off is acceptable; refactor here if string
   literals enter the type language."
  [x]
  (cond
    (string? x)     (keyword x)
    (map? x)        (into {}
                          (map (fn [[k v]]
                                 [(if (string? k) (keyword k) k)
                                  (json->type v)]))
                          x)
    (sequential? x) (mapv json->type x)
    :else           x))


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
    {:target-id (when target-id-raw
                  (try (java.util.UUID/fromString (str target-id-raw))
                       (catch Exception _ nil)))}))


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
