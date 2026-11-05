(ns graphden.crud.types-api
  "Type-API logic for the web/crud base functions — the Clojure bodies
   the `/api/types`, `/api/types/candidates` and `/api/types/usages`
   graph handlers call (rich-type snapshots, candidate ranking, the
   usages walk).

   Also holds the shared graph-cache loaders (`cached-or-load-graph` /
   `load-graph-entities-uncached`) and the storage-row → role / rich-type
   derivations. The graph-cache loaders are used by both this namespace
   and the higher-level `graphden.crud.entities`, so they live here, at
   the lower level of the crud.* DAG.

   Depends on `graphden.crud.request` for request context."
  (:require
    [clojure.string :as str]
    [graphden.crud.graph-refs :as refs]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records.types :as record-types]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]
    [graphden.types.core :as types]
    [graphden.types.core.execution :as execution]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.graph-rows :as graph-rows]))


;; === Graph-cache loaders ====================================================

(defn load-graph-entities-uncached
  "Read every slot/fn-slot/binding-model row from storage in one
   branch-cached batch when versioned, or as five vanilla queries
   otherwise. Same shape as the `:graph-cache` atom."
  [storage]
  (graph-rows/read-all storage))


(defn org-visible-slice
  "Restrict a graph dump to rows the CURRENT org may see — its own rows
   plus the un-owned / public platform rows — mirroring OrgScopedStorage's
   read predicate (`(or org-id public) ∈ {public, current-org}`). The
   shared `:graph-cache` is primed org-AGNOSTICALLY by the compiler
   (`prime-graph-cache!` reads the privileged compile storage), so serving
   it raw to a tenant request would enumerate every org's fn names,
   namespaces and binding values through the sidebar `:tree`/`:search`,
   the type datalist and `/api/graph/entities`. Platform-tier viewers
   (public org / operator / single-tenant, where `*current-org*` is
   unbound) get the dump unchanged — the common single-tenant case stays
   a no-op."
  [graph]
  (let [org (tctx/current-org)]
    (if (tctx/platform-tier? org)
      graph
      (let [vis? (fn [row]
                   (contains? #{tctx/public-org org}
                              (or (:org-id row) tctx/public-org)))
            slice (fn [g k] (cond-> g (contains? g k) (update k #(filterv vis? %))))]
        (reduce slice graph [:fns :slots :fn-slots :bindings :list-items])))))


(def ^:private slice-memo-cap
  "(graph-cache, org) pairs whose slice is kept — one live entry per
   branch ctx × active tenant org; the oldest goes when a new pair
   arrives. Each entry pins at most one raw snapshot (the one it sliced),
   and a pair's next read replaces it."
  64)


(defonce ^:private slice-memo
  ;; [[cache-atom org raw sliced] …], most recent last.
  (atom []))


(defn- memo-org-slice
  "`org-visible-slice` of the cached snapshot `raw`, memoised on the
   snapshot's IDENTITY + the org. Every tenant read used to filter the
   whole graph again and hand back a new object, so every downstream
   identity-keyed memo (layout lookups, the lint memo) missed on every
   request. The snapshot is replaced, never mutated, on a write, so
   identity is the freshness check. Platform tier: the raw snapshot
   itself (no slice)."
  [cache raw]
  (let [org (tctx/current-org)]
    (if (or (nil? cache) (tctx/platform-tier? org))
      (org-visible-slice raw)
      (let [same-pair? (fn [[c o]] (and (identical? c cache) (= o org)))]
        (or (some (fn [[_ _ r sliced :as e]]
                    (when (and (same-pair? e) (identical? r raw)) sliced))
                  @slice-memo)
            (let [sliced (org-visible-slice raw)]
              (swap! slice-memo
                     (fn [entries]
                       (conj (vec (take-last (dec slice-memo-cap)
                                             (remove same-pair? entries)))
                             [cache org raw sliced])))
              sliced))))))


(defn cached-or-load-graph
  "The shared per-ctx graph snapshot, restricted to the current org's
   visibility (see `org-visible-slice`). The cache itself always holds
   the FULL org-agnostic graph: a hit slices per read; a miss loads via
   the privileged `:compile-storage` handle (same source
   `prime-graph-cache!` uses) so one tenant's miss can never poison the
   shared cache with its narrower slice for every other reader. A hit's
   slice is memoised per (snapshot, org) — `memo-org-slice`."
  [ctx]
  (if-let [raw (exec-ctx/cached-graph ctx)]
    (memo-org-slice (:graph-cache ctx) raw)
    (org-visible-slice
      ;; Load-on-miss races the write path: a write that commits while
      ;; the load runs finds an EMPTY cache (nothing to splice) and the
      ;; loaded snapshot is already behind it. Take the invalidation
      ;; epoch first and install only if it did not move. On a move,
      ;; serve what the write itself left in the cache (its delta path
      ;; primes the post-write graph), else read again — bounded, so a
      ;; write storm ends with an uncached read rather than a stale
      ;; snapshot published as the truth.
      (loop [attempt 1]
        (let [epoch (exec-ctx/invalidation-epoch ctx)
              data (load-graph-entities-uncached
                     (or (:compile-storage ctx) (request/require-storage ctx)))]
          (cond
            (exec-ctx/fill-graph-cache! ctx data epoch) data
            (exec-ctx/cached-graph ctx) (exec-ctx/cached-graph ctx)
            (>= attempt 3) data
            :else (recur (inc attempt))))))))


(defn org-visible-rich-snapshot
  "The NAME-keyed rich-type snapshot restricted to names the current
   principal may read — the registry equivalent of the filtered graph
   slice. The
   in-memory registry is name-keyed and, unlike the graph cache, has
   NO org filter (`record-rich-types-raw!` writes every tenant fn to
   the global index unconditionally), so serving it raw to a tenant
   enumerates every OTHER org's composed-fn names, arg types, returns
   and effects through `/api/types` + `/api/types/candidates`. We keep
   only entries whose (bare) name appears as a fn-row in the org-sliced
   graph — public base-fns / type-rows (org-id nil) and the org's own
   fns pass; foreign-org composed fns drop out. Platform-tier viewers
   (`*current-org*` unbound) get the snapshot object unchanged so the
   single-tenant cache in `rich-types-with-type-rows` still hits by
   identity."
  [ctx]
  (let [snap (registry/rich-types-snapshot)
        org (tctx/current-org)]
    (if (tctx/platform-tier? org)
      snap
      (let [visible (into #{}
                          (comp (map :name) (filter some?) (map keyword))
                          (:fns (tctx/apply-graph-read-filter
                                  (cached-or-load-graph ctx))))]
        (into {}
              (filter (fn [[k _]] (contains? visible (keyword (name k)))))
              snap)))))


(defn compute-fn-role
  "Row-role via the shared `records.types/type-row-role`, plus a
   belt-and-braces registry fallback: a fn-name with a non-empty
   `:args` map that is NOT marked `:type-row?` is a base-fn even when
   its row predates the `:return-type-fn-id` marker. Roles:
   `:composed`, `:base-fn`, `:refinement`, `:list`, `:union`,
   `:variant`, `:fn-type`, `:record`, `:primitive`."
  [fn-row has-slots? rich-snapshot]
  (let [rich-entry (some-> (:name fn-row) keyword rich-snapshot)
        base-fn-via-registry? (and rich-entry
                                   (not (:type-row? rich-entry))
                                   (seq (:args rich-entry)))]
    (cond
      (seq (:parent-ids fn-row))                 :composed
      (or (some? (:return-type-fn-id fn-row))
          base-fn-via-registry?)                 :base-fn
      :else (record-types/type-row-role fn-row has-slots?))))


(def type-lens-roles
  "Roles the editor classifies as a TYPE — the server twin of the
   sidebar lens's `TYPE_ROLES` (editor-sidebar.js); keep the two in
   sync. Includes `:primitive`: a slot-less refine-less named row IS
   an empty type-row (what Create-type produces before its first
   field), and the lens shows it as one."
  #{:refinement :list :union :variant :record :fn-type :primitive})


(defn type-name-entries
  "Picker rows with an internal identity-preserving type reference.
   Selection spelling stays bare when unique, qualified when ambiguous."
  [ctx]
  (let [{:keys [fns fn-slots]}
        (tctx/apply-graph-read-filter (cached-or-load-graph ctx))
        name-counts (frequencies (keep :name fns))
        ns-paths (ns-path/path-map
                   (sp/query-entities (or (:compile-storage ctx)
                                          (request/require-storage ctx)) :ns {}))
        slotted (into #{} (map :fn-id) fn-slots)
        rich-snapshot (registry/rich-types-snapshot)
        ;; No :primitive here — the datalist appends the BARE primitives
        ;; (`types/primitives`) below; a primitive-ROLE graph row is an
        ;; empty type-row a user hasn't finished, noise in a picker.
        type-row-roles (disj type-lens-roles :primitive)
        rows (keep (fn [f]
                     (when (:name f)
                       (let [role (compute-fn-role f
                                                   (contains? slotted (:id f))
                                                   rich-snapshot)]
                         (when (type-row-roles role)
                           (let [qualified (cr/qualified-type-name ns-paths f)
                                 selection (if (> (get name-counts (:name f)) 1)
                                             (str (get ns-paths (:namespace-id f) "")
                                                  "/" (:name f))
                                             (:name f))]
                             {:name selection :kind (name role)
                              :type-ref (or qualified (keyword (:name f)))})))))
                   fns)
        prims (map (fn [p] {:name (name p) :kind "primitive" :type-ref p})
                   types/primitives)]
    (->> (concat rows prims)
         (sort-by :name)
         (vec))))


(defn project-rich-type-entry
  "Strip the backend-only per-base-fn type-rule fns from a single
   registry entry — the rule values are Clojure functions and must
   never leak to JSON. The `:has-*-rule?` boolean flags that used to
   replace them are gone: their one reader (the editor's client-side
   rule-owner walk) was replaced by the server-computed layout strip
   facts + `/partials/return-type-rule`, which read the RAW registry
   via `registry/rule-owner-info-of-id`.

   Exposed for tests — the wire shape of `/api/types` depends on this
   projector being applied to every entry."
  [entry]
  (dissoc entry :return-type-rule :slot-types-rule :nav-types-rule))


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
  (let [raw-snapshot (org-visible-rich-snapshot ctx)
        graph (tctx/apply-graph-read-filter (cached-or-load-graph ctx))
        ;; The org rides the identity key: `cached-or-load-graph` now
        ;; returns a per-org SLICE (fresh vectors per read for tenants),
        ;; so `identical?` on the slice would never hit for them. Keying
        ;; on the org + the raw cache identity keeps single-tenant hits
        ;; reference-stable and gives adjacent same-org tenant calls a
        ;; hit window too (last-org-wins single entry, deliberately small).
        org (tctx/current-org)
        raw (or (exec-ctx/cached-graph ctx) graph)
        [cs cg co cr] @rich-types-with-type-rows-cache]
    (if (and (identical? cs raw-snapshot) (identical? cg raw) (= co org))
      cr
      (let [result (rich-types-with-type-rows-uncached raw-snapshot graph)]
        (reset! rich-types-with-type-rows-cache [raw-snapshot raw org result])
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
      (constraint-tagged? f :list)
      (constraint-tagged? f :map)
      (constraint-tagged? f :tuple)))


(defn- type-row?
  "Fn rows we'll surface as type-rows in the augmented snapshot:
   - refinements / lists / unions / variants (marker-bearing), OR
   - genuine record-types (no parent-ids, no return-type-fn-id, has fn-slots)."
  [f slots-by-fn]
  (and (:name f)
       (or (marker-type? f)
           (and (empty? (:parent-ids f))
                (nil? (:return-type-fn-id f))
                (seq (get slots-by-fn (:id f)))))))


(defn- type-row-entry
  "Augment-snapshot entry for a single type-row. Returns nil when the
   existing snapshot already has a CALLABLE entry the augmentation
   would shadow (non-empty :args, non-`:any` :return)."
  [f acc slots-by-fn slot-by-id fns-by-id]
  (let [;; Marker-bearing rows (refinement / list / union / variant /
        ;; …) carry their structural form via `rich-type-from-row` —
        ;; `record-shape` would misclassify them. Fall back to
        ;; `record-shape` only when no marker FK / tag is present.
        stored (record-types/stored-type-ref
                 (:id f) {:fn-by-id fns-by-id :unfold-root? true
                          :slot-by-id slot-by-id :slots-by-fn slots-by-fn
                          :named-ref #(some-> (:name %) keyword)})
        structural (if (constraint-tagged? f :variant) (types/desugar-variant stored) stored)
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


(defn wire-type-var?
  "Is this wire string a TYPE VARIABLE rather than a type name? The
   checker's variables are single lowercase letters, optionally
   `-<n>`-suffixed by `types.core/freshen` — `a`, `b`, `a-4609`. No
   primitive or shipped alias is one letter long, and a registered
   alias that happens to be (`:a`) keeps winning: the alias table is
   consulted first, so a user-defined one-letter type is still a name."
  [s]
  (boolean (and (string? s)
                (re-matches #"[a-z](-\d+)?" s)
                (not (types/alias-registered? (keyword s))))))


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


(defn- invalid-callable-wire!
  [position value]
  (throw (ex-info "Invalid callable type expression"
                  {:type :validation-error/invalid-type-expression
                   :position position :value value})))


(defn- json->effect-constraint
  [value]
  (cond
    (or (nil? value) (contains? #{:any "any"} value)) (when (some? value) :any)
    (and (or (sequential? value) (set? value))
         (every? #(or (string? %) (keyword? %)) value))
    (into #{} (map #(if (string? %) (keyword %) %)) value)
    :else (invalid-callable-wire! :effects value)))


(defn- json->execution-constraint
  [value]
  (let [execution
        (cond
          (contains? #{:unknown "unknown"} value) :unknown
          (and (vector? value) (contains? #{:execution-var "execution-var"} (first value)))
          (if (and (= 2 (count value))
                   (or (symbol? (second value))
                       (and (string? (second value)) (seq (second value)))))
            [:execution-var (symbol (second value))]
            (invalid-callable-wire! :execution value))
          (and (or (sequential? value) (set? value))
               (every? #(or (string? %) (keyword? %)) value))
          (into #{} (map #(if (string? %) (keyword %) %)) value)
          :else (invalid-callable-wire! :execution value))]
    (when-not (types/execution-constraint? execution)
      (invalid-callable-wire! :execution value))
    execution))


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
   `[:not= \"\"]` into `[:not= :]` and silently break `:non-empty-text`.

   TYPE VARIABLES are the other place the wire loses information: a
   var is a SYMBOL in the checker (`'a`, and `'a-17` after freshening)
   and cheshire writes it as the bare string `\"a\"` — the same shape
   as a type name. Decoding it as the keyword `:a` made every
   polymorphic slot RIGID at the API layer: `[:fn {:item a} b]` came
   back as `[:fn {:item :a} :b]`, no candidate's return could be a
   subtype of the unknown type `:b`, and the picker's Compatible list
   on `map`'s `:func` held nothing but never-returning fns.
   `wire-type-var?` is the grammar both sides already agree on — the
   editor's chip text (`compactTypeChipText`) reads the same regex."
  [x]
  (cond
    (string? x) (if (wire-type-var? x) (symbol x) (keyword x))

    (map? x)
    (into {}
          (map (fn [[k v]] [(if (string? k) (keyword k) k) (json->type v)]))
          x)

    ;; Callable contract positions are not value types: JSON arrays encode
    ;; effect/profile sets, and an execution variable has its own symbol sort.
    ;; Preserve legacy arity so anonymous storage identities do not change.
    (and (sequential? x) (contains? #{:fn "fn"} (first x)))
    (do
      (when-not (contains? #{3 4 5} (count x))
        (invalid-callable-wire! :shape x))
      (let [args (json->type (nth x 1))
            ret (json->type (nth x 2))]
        (when-not (or (nil? args) (map? args))
          (invalid-callable-wire! :args args))
        (cond-> [:fn args ret]
          (>= (count x) 4) (conj (json->effect-constraint (nth x 3)))
          (= (count x) 5) (conj (json->execution-constraint (nth x 4))))))

    ;; Refinement — decode `base` as a type, then hand the constraint
    ;; to `json->constraint` with the base as the disambiguating
    ;; context. Other compound value types (union / variant / list / record)
    ;; carries only types and recurses uniformly below.
    (and (vector? x) (#{:refine "refine"} (first x)))
    (let [base (json->type (second x))]
      [:refine base (json->constraint (keyword-domain? base) (nth x 2 nil))])

    (sequential? x) (mapv json->type x)

    :else x))


(defn- profile-label
  [profiles]
  (cond
    (= :unknown profiles) "unknown execution (no runtime proof)"
    (and (set? profiles) (empty? profiles)) "no proven runtime"
    (set? profiles) (str/join " + " (sort (map {:browser-main "Browser" :jvm-server "JVM"} profiles)))
    :else "inferred execution (not yet proven)"))


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

    (and (types/fn-type? candidate) (types/fn-type? expected)
         (not (execution/compatible? (types/fn-execution candidate) (types/fn-execution expected))))
    (str "Execution mismatch: requires " (profile-label (types/fn-execution expected))
         "; callback has " (profile-label (types/fn-execution candidate)) ".")

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
;;
;; The endpoints' parse → validate stages (and all of /api/types/compatible
;; and the candidates enumeration) are graph fn-defs in
;; `web/crud-types/fns.edn`; what stays here is the
;; namespace index the candidates chain calls, and the usages walk.

;; --- types-candidates — per-row helpers of the graph enumeration.

(defn candidate-ns-index
  "fn-id → `{:ns \"dotted.path\" :ns-id \"<uuid>\"}` for every fn the
   current org can see — the picker's namespace grouping. Best-effort:
   a storage without the `:ns` table (minimal test fixtures) yields
   path-less entries. PUBLIC: `:fn-ns-index` (web/crud-types) is the
   graph's one boundary over it, closure-captured into the per-row
   candidate callback so the walk pays for the index once."
  [ctx]
  (let [ns-paths (try
                   (ns-path/path-map
                     (sp/query-entities
                       (or (:compile-storage ctx) (request/require-storage ctx))
                       :ns {}))
                   (catch Exception _ {}))]
    (into {}
          (keep (fn [f]
                  (when-let [ns-id (:namespace-id f)]
                    [(:id f) (cond-> {:ns-id (str ns-id)}
                               (get ns-paths ns-id) (assoc :ns (get ns-paths ns-id)))])))
          (:fns (cached-or-load-graph ctx)))))


;; --- types-usages — the walk behind the graph's apply stage (find every
;; place a fn / type-row is referenced).

(defn- usage-namespaces
  "Acquire namespace rows once, before the ACL seam resolves namespace paths."
  [ctx]
  (try
    (vec (sp/query-entities
           (or (:compile-storage ctx) (request/require-storage ctx)) :ns {}))
    (catch Exception _ [])))


(defn- constraint-links
  "Named type references inside recursive constraints are the only incoming
   links that do not carry a target fn-id in a graph row."
  [target-name {:keys [fns]}]
  (->> fns
       (filter (fn [f]
                 (and (vector? (:constraint f))
                      target-name
                      (constraint-contains-type-ref? (:constraint f) target-name))))
       (map (fn [f]
              {:fn-id (:id f)
               :kind (case (first (:constraint f))
                       :union :union-branch
                       :variant :variant-branch
                       :fn :fn-type-arg-or-return
                       :other)}))))


(defn- reachability-roots
  "Route/service handlers and an optional editor root, intersected with the
   viewer's concealed graph. The submitted editor id selects a point of view;
   it never grants access to a source row."
  [parsed ctx fn-by-id]
  (let [storage (request/require-storage ctx)
        candidates (concat
                     (map (fn [row]
                            [(:fn-id row) :service
                             (str (or (:name row) "enabled service"))])
                          (sp/query-entities storage :service {:enabled? true}))
                     (map (fn [row]
                            [(:handler-fn-id row) :route
                             (str (or (:label row) "app route"))])
                          (tctx/tenant-app-routes (tctx/current-org)))
                     (when-let [id (:root-id parsed)] [[id :editor nil]]))]
    (->> candidates
         (filter (fn [[id _ _]] (contains? fn-by-id id)))
         distinct
         (sort-by (fn [[id kind label]] [(str kind) (str id) (str label)]))
         vec)))


(defn- usage-link-facts
  "Total nullable fields at the typed facts boundary; graph formatting omits absent labels."
  [link]
  (merge {:target-id nil :slot-name nil :root-kind nil} link))


(defn- reachability-facts
  [parsed graph links roots]
  (let [target-id (:target-id parsed)
        path (refs/shortest-path graph (map first roots) target-id links)
        origin (some (fn [[id kind _]] (when (= id (:fn-id (first path))) kind)) roots)]
    {:state (cond
              path :reachable
              (seq roots) :unreachable
              :else :unknown)
     :scope :visible-roots
     :roots (mapv (fn [[id kind label]]
                    (usage-link-facts {:fn-id id :kind :root :root-kind kind :slot-name label}))
                  roots)
     :path (mapv (fn [{:keys [fn-id kind slot-name]}]
                   (usage-link-facts {:fn-id fn-id :kind (or kind :root) :slot-name slot-name}))
                 path)
     :origin origin}))


(defn types-usages-facts
  "One concealed snapshot for graph-side incoming-link selection and summaries.
   Shared edge/constraint walks and shortest-path computation stay native.
   Only inert data crosses the boundary; namespace lookup happens once."
  [parsed ctx conceal]
  (let [snapshot (cached-or-load-graph ctx)
        graph (conceal (assoc snapshot :namespaces (usage-namespaces ctx)))
        fn-by-id (into {} (map (juxt :id identity)) (:fns graph))
        target-id (:target-id parsed)
        target-name (:name (get fn-by-id target-id))
        paths (ns-path/path-map (:namespaces graph))
        links (refs/edges graph)
        roots (reachability-roots parsed ctx fn-by-id)]
    {:target-id target-id
     :target-name (some-> target-name str)
     :visible? (contains? fn-by-id target-id)
     ;; Summaries need identity metadata, never implementation fields from
     ;; the concealed snapshot (including references beyond this slice).
     :fns (update-vals fn-by-id
                       #(merge {:name nil :role nil :namespace-id nil :anonymous-hash nil}
                               (select-keys % [:id :name :role :namespace-id :anonymous-hash])))
     :namespace-paths (select-keys paths (keep :namespace-id (vals fn-by-id)))
     ;; Edges may point outside the visible slice. Their IDs are not facts
     ;; an independent graph consumer is authorized to enumerate.
     :links (into [] (comp (filter #(contains? fn-by-id (:target-id %)))
                           (map usage-link-facts)) links)
     :constraints (if (contains? fn-by-id target-id)
                    (mapv usage-link-facts (constraint-links target-name graph))
                    [])
     :reachability (reachability-facts parsed graph links roots)}))


(defn all-rich-types
  "Body of the `all-rich-types` base-fn. Snapshot of the in-memory
   rich-type registry, augmented with structural definitions for
   storage-only type-rows.

   FULL — every per-entry field. This is the internal single-source-of-
   truth `/api/types/candidates` inherits (`:_types-candidates-snapshot`);
   the HTTP wire-shaping (lean bulk / per-fn) lives in `api-rich-types`."
  [ctx]
  (rich-types-with-type-rows ctx))
