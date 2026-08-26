(ns graphden.crud.value-form
  "Resolver + endpoint stages for the type-aware value-form system.

   `/api/value-form` answers: \"for the value bound at this
   binding / fn-slot, give me the editor form\". The flow:

   - `resolve-slot-effective-type` — what type is being edited.
   - `resolve-form` — pure structural classifier:
     type -> {:kind :leaf/:record/:list/:union …}.
   - `pick-form-fn` — leaf type -> form-fn, via the shared
     `:_value-form-registry` list matched by `subtype?`.
   - the chosen form-fn is a `:const` holding a static hiccup
     control template; it is executed and wrapped in a
     `data-form-root` div carrying the binding ids.

   The endpoint returns `{:ok true :form <hiccup> :value <current>}`;
   the editor renders the hiccup and fills `:value` into the
   controls. Composite types recurse: `resolve-form` classifies a
   slot as `:leaf` / `:record` / `:list` / `:union` and the renderer
   builds nested sub-forms (per-field, per-element, per-branch). Two
   bounded fallbacks to a raw JSON editor remain by design: a
   composite (record / list) branch nested inside a union, and any
   type past the depth-12 recursion guard.

   Sits alongside `graphden.crud.type-check` in the crud.* layer."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.executor.interface :as executor]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check.literals :as types-lit]
    [graphden.types.core :as types]))


;; =============================================================================
;; Type resolution
;; =============================================================================

(defn- type-fn-rich
  "Read a type-fn row by id and walk it to its rich (structural)
   type. nil id -> nil."
  [storage type-fn-id]
  (when type-fn-id
    (tc/type-fn->rich-type storage (sp/read-entity storage :fn type-fn-id))))


;; --- nav-typed sequence items ------------------------------------------------
;; A sequence whose items index INTO a structure (e.g. `:update-in`'s
;; `:path` walking a record) has a per-POSITION type: the valid key at
;; segment N depends on segments 0..N-1. The type-checker records the
;; navigable structure on the rich-types entry (`:nav-types`); these
;; helpers walk it — a Clojure mirror of the editor's `navItemType`.

(defn- nav-descend
  "Structure reached by following key `k` (a keyword field-name, or nil
   for a dynamic segment) into structural type `t`."
  [t k]
  (let [d (types/resolve-alias t)]
    (cond
      (types/record-type? d) (if (and k (contains? d k)) (get d k) :any)
      (#{:jsonb :any} d)      d
      (types/list-type? d)    (types/list-elem d)
      (= :sequence d)         :any
      :else                   nil)))


(defn- nav-key-type
  "Type of a KEY that indexes into structural type `t`: a record → a
   closed-enum of its field names, an open map → `:keyword`, a list →
   `:int`. nil when `t` is a scalar (no further key is valid)."
  [t]
  (let [d (types/resolve-alias t)]
    (cond
      (types/record-type? d) (let [ks (vec (sort (keys d)))]
                               (when (seq ks) [:refine :keyword [:in ks]]))
      (#{:jsonb :any} d)     :keyword
      (or (types/list-type? d) (= :sequence d)) :int
      :else                  nil)))


(defn- item-key
  "The nav key a list-item contributes — its keyword literal value, or
   nil for a ref / non-keyword item (a dynamic segment)."
  [item]
  (let [v (:value item)]
    (when (and (nil? (:ref-fn-id item)) (keyword? v)) v)))


(defn- nav-item-type
  "Per-position type of a nav-typed sequence item: walk `nav-type`
   along the keys of the items BEFORE `item-id`, then take the key
   type of the structure reached. nil when not resolvable."
  [storage binding-id item-id nav-type]
  (when binding-id
    (let [items  (->> (sp/query-entities storage :binding-list-item
                                         {:binding-id binding-id})
                      (sort-by :position)
                      vec)
          idx    (or (first (keep-indexed
                              (fn [i it] (when (= (:id it) item-id) i))
                              items))
                     (count items))
          prefix (mapv item-key (subvec items 0 (min idx (count items))))
          walked (reduce (fn [t k] (if (nil? t) (reduced nil) (nav-descend t k)))
                         nav-type prefix)]
      (nav-key-type walked))))


(defn- backward-unified-slot-type
  "Tier-2 of the slot-type priority chain — the backward-unified type
   the type-checker recorded on the owning fn's rich-type entry for
   this slot. nil when the rich-types entry has nothing to say
   (no narrowing recorded, or owning/slot row missing).

   Keyed by the owning fn's IDENTITY, deliberately with NO stale-name
   rescue: a name-keyed lookup let a DIFFERENT fn that happened to
   share the name (e.g. a leftover from a deleted tutorial branch)
   dictate this fn's form shape (AUDIT-name-vs-id-resolution class).
   A missing id entry just degrades to tier-3, the declared slot type.

   Shared by `resolve-slot-effective-type` (which picks the first
   non-nil tier through `or`) and `slot-type-provenance` (which
   exposes each tier separately under `:unified` and gates the
   computation on `:override-fn-id` being absent)."
  [owning-fn-row slot-row]
  (when (and (:id owning-fn-row) (:name slot-row))
    (get-in (registry/rich-type-of-id (:id owning-fn-row))
            [:slot-types (keyword (:name slot-row))])))


(declare inheritance-chain-info)


(defn- declared-arg-elem-type
  "Element type of a sequence slot as DECLARED in the rich-types
   registry, read from the closest inheritance-chain ancestor whose
   rich `:args` entry types this slot as a list. The slot's own row
   often degrades to the bare `:sequence` base on sync (an inline
   `[:list T]` isn't a named type-row), and the owning fn's rich
   `:args` carries only its FREE args — but the declaring ancestor
   (e.g. `:hiccup` for `:children [:list :hiccup-node]`) keeps the
   type the aggregate type-check actually enforces. nil when no
   ancestor declares a list type for this slot name."
  [storage fn-id slot]
  (when (and fn-id (:name slot))
    (let [slot-kw (keyword (:name slot))
          {:keys [ids fn-map]} (inheritance-chain-info storage fn-id)]
      (some (fn [fid]
              ;; Id-keyed with the stale-name rescue — ancestors here are
              ;; typically package fns whose abandoned historical ids the
              ;; rescue re-points; a bare-name lookup would let a same-
              ;; named OTHER fn declare the element type.
              (let [rich (registry/rich-type-of-id-or-stale-name
                           fid (:name (get fn-map fid)))
                    t    (some-> (get-in rich [:args slot-kw])
                                 types/resolve-alias)]
                (when (types/list-type? t) (types/list-elem t))))
            ids))))


(defn resolve-slot-effective-type
  "Effective rich type at a (binding | fn+slot) edit site, alias-
   resolved to structural form. Priority, most-specific first:
     1. binding `:type-override-fn-id` — the author's explicit pin.
     2. backward-unified slot type from the rich-types registry,
        keyed by slot-name on the owning fn (the type-checker records
        a narrowed slot type here when a fn-def's `:return-type`
        narrowed a parent type-var that also types this slot).
     3. the slot's declared `:type-fn-id`.

   For a `binding-list-item` (`:item-id` present) the slot type is a
   list and the item's effective type is the element type — taken from
   the resolved slot type, or (when the slot row degraded to the bare
   `:sequence` base on sync) from the declared arg type in the
   rich-types registry.

   Ref-binding return-type narrowing (JS `expectedSlotType` step 3)
   is intentionally NOT mirrored: a ref binding carries no literal
   value to form-edit, so the value-form is never opened on one."
  [storage {:keys [binding-id fn-id slot-id item-id]}]
  (let [bnd       (when binding-id (sp/read-entity storage :binding binding-id))
        fn-id     (or fn-id (:fn-id bnd))
        slot-id   (or slot-id (:slot-id bnd))
        slot      (when slot-id (sp/read-entity storage :slot slot-id))
        owning    (when fn-id (sp/read-entity storage :fn fn-id))
        slot-type (or (type-fn-rich storage (:type-override-fn-id bnd))
                      (backward-unified-slot-type owning slot)
                      (type-fn-rich storage (:type-fn-id slot)))
        resolved  (some-> slot-type types/resolve-alias)]
    (cond
      (nil? resolved) nil
      ;; A list-item edits ONE element. For a nav-typed sequence (an
      ;; index path into a structure) the type is per-position — walk
      ;; the structure. Otherwise descend past the [:list …] wrapper.
      item-id
      ;; Nav-types keyed by the owning fn's IDENTITY — same no-stale-
      ;; name-rescue rationale as `backward-unified-slot-type`.
      (let [nav (when (and (:id owning) (:name slot))
                  (get-in (registry/rich-type-of-id (:id owning))
                          [:nav-types (keyword (:name slot))]))]
        (or (when nav (nav-item-type storage binding-id item-id nav))
            (when (types/list-type? resolved) (types/list-elem resolved))
            (declared-arg-elem-type storage fn-id slot)
            :any))
      :else resolved)))


;; =============================================================================
;; Slot type provenance — server-side mirror of the editor's
;; `slotTypeProvenance` (editor-literal-types.js). Answers HOW a slot's
;; effective type resolved: the 4-tier priority chain (binding type-
;; override → backward-unified slot-type → bound-fn return-type → slot
;; declaration), the type each tier contributes (nil when the tier
;; doesn't apply), the source fn-name + fn-id per tier, which tier won,
;; and the inheritance chain — every ancestor that contributed an
;; override binding. Used by the mismatch-explainer popover (and the
;; standalone provenance popover) to show users WHERE the expected
;; type came from. List-item rows return nil — their type comes from
;; nav / element logic, not the slot chain.
;; =============================================================================

(defn- inheritance-chain-info
  "BFS the `:parent-ids` closure starting at `fn-id`, level-order
   (closer-wins: a fn always precedes its ancestors). Returns
   `{:ids [fn-ids] :fn-map {id row}}` where `:ids` lists every
   ancestor including `fn-id` itself at position 0 and `:fn-map`
   carries the row for each id (populated during the walk, so
   downstream helpers read `:name` etc. without a second
   `sp/read-entity` pass).

   Reads one frontier level per batched `query-entities` (`:id` IN
   ancestor-ids) rather than one `read-entity` per ancestor. The
   level-order (rather than DFS) walk also mirrors the editor's JS
   parent-ids traversal the downstream helpers replicate.

   Cycles can't form (sync-time topological-sort rejects them), so no
   cycle guard is needed."
  [storage fn-id]
  (loop [frontier [fn-id]
         seen     #{}
         ids      []
         fn-map   {}]
    (if (empty? frontier)
      {:ids ids :fn-map fn-map}
      (let [fresh     (vec (remove seen (distinct frontier)))
            rows      (if (seq fresh)
                        (sp/query-entities storage :fn {:id fresh})
                        [])
            row-by-id (into {} (map (juxt :id identity)) rows)
            ;; `query-entities` may reorder — re-key on the frontier order
            ;; so `:ids` stays closer-wins; drop ids with no row (a broken
            ;; FK-less ancestor can't declare slots or bindings anyway).
            ordered   (keep row-by-id fresh)]
        (recur (vec (mapcat :parent-ids ordered))
               (into seen fresh)
               (into ids (map :id) ordered)
               (merge fn-map row-by-id))))))


(defn- find-slot-declaring-fn
  "Walk inheritance chain in REVERSE (root-first) — return the deepest
   ancestor that declares the slot via a `:fn-slot` junction row.
   Mirrors the JS `findSlotDeclaringFn`. Uses a single batched
   `query-entities` over `:fn-slot` with `:fn-id` IN ancestor-ids
   instead of one query per ancestor (~N round-trips → 1)."
  [storage chain-info slot-id]
  (let [{:keys [ids fn-map]} chain-info
        fn-slot-rows (sp/query-entities storage :fn-slot
                                        {:fn-id ids :slot-id slot-id})
        owning-fn-ids (set (map :fn-id fn-slot-rows))]
    (some (fn [fid]
            (when (contains? owning-fn-ids fid)
              {:fn-id fid :fn-name (:name (get fn-map fid))}))
          (reverse ids))))


(defn- find-binding-override-chain
  "Walk inheritance chain leaf-first — every ancestor that carries a
   binding for this slot WITH `:type-override-fn-id`. Returns the list
   in closer-wins order (the first entry is the override actually
   applied; later entries are shadowed siblings the editor surfaces as
   `(also by)` rows). Single batched `query-entities` over `:binding`
   with `:fn-id` IN ancestor-ids replaces the per-ancestor N+1."
  [storage chain-info slot-id]
  (let [{:keys [ids fn-map]} chain-info
        binding-rows (sp/query-entities storage :binding
                                        {:fn-id ids :slot-id slot-id})
        by-fn (into {} (map (juxt :fn-id identity)) binding-rows)]
    (vec (keep (fn [fid]
                 (let [bnd (get by-fn fid)
                       override-fn-id (:type-override-fn-id bnd)]
                   (when override-fn-id
                     {:fn-id fid :fn-name (:name (get fn-map fid))
                      :override-fn-id override-fn-id})))
               ids))))


(defn- compute-slot-type-for-row
  "Server-side mirror of editor's `computeSlotType`. Given a type-fn
   row, prefer the named rich-type alias when one exists; otherwise
   return the fn-row's name (string), or the structural form on
   `:constraint` for anonymous fn-/map-/tuple-typed rows."
  [type-fn-row]
  (when type-fn-row
    (let [constraint (:constraint type-fn-row)]
      (cond
        (and (vector? constraint)
             (#{:fn :map :tuple} (first constraint)))
        constraint

        (not (:name type-fn-row)) nil

        :else
        ;; Id-first with the stale-name rescue — a type-row read off
        ;; storage may carry a historical id, but a bare-name lookup
        ;; alone could hit a same-named unrelated fn.
        (let [rich (registry/rich-type-of-id-or-stale-name
                     (:id type-fn-row) (:name type-fn-row))]
          (cond
            (:type-row? rich)                         (:name type-fn-row)
            (and (:return rich) (not= :any (:return rich))) (:return rich)
            :else                                     (:name type-fn-row)))))))


(defn- type-of-fn-id
  "Resolve the rich type of the slot whose `:type-fn-id` is `fn-id`.
   nil for nil id."
  [storage fn-id]
  (when fn-id
    (compute-slot-type-for-row (sp/read-entity storage :fn fn-id))))


(defn slot-type-provenance
  "4-tier resolution chain + inheritance chain for the type at a
   `(fn-id, slot-id, binding-id)` edit site. Mirrors the editor's
   `slotTypeProvenance`. Returns nil for list-item rows (`:item-id`
   present) — their type comes from nav / element logic, not the
   slot chain. Returns nil for unresolved sites (no resolvable
   slot / missing `:type-fn-id`).

   When only `:binding-id` is supplied, `:fn-id` and `:slot-id` are
   resolved off the binding row — same fallback chain
   `resolve-slot-effective-type` uses, so callers can pass just
   `{:binding-id …}` and get a valid result."
  [storage {:keys [fn-id slot-id binding-id item-id]}]
  (when-not item-id
    (let [bnd      (when binding-id (sp/read-entity storage :binding binding-id))
          fn-id    (or fn-id (:fn-id bnd))
          slot-id  (or slot-id (:slot-id bnd))]
      (when slot-id
        (let [slot (sp/read-entity storage :slot slot-id)]
          (when (:type-fn-id slot)
            ;; One inheritance walk feeds BOTH the slot-declarer lookup
            ;; AND the override-chain lookup AND the `own-fn` read —
            ;; without sharing the result and the per-fn batch read, a
            ;; popover open did ~N round-trips per helper (was ~25 for
            ;; a 5-deep chain).
            (let [chain-info   (when fn-id (inheritance-chain-info storage fn-id))
                  override-fid (:type-override-fn-id bnd)
                  own-fn       (when fn-id (get-in chain-info [:fn-map fn-id]))
                  ;; Tier 2 is skipped under an explicit override —
                  ;; mirrors `resolve-slot-effective-type`'s `or`-fall-
                  ;; through where tier-1 takes precedence.
                  unified      (when (nil? override-fid)
                                 (backward-unified-slot-type own-fn slot))
                  ref-fid      (:ref-fn-id bnd)
                  ref-fn       (when ref-fid (sp/read-entity storage :fn ref-fid))
                  declaring    (when chain-info
                                 (find-slot-declaring-fn storage chain-info slot-id))
                  tiers        [{:key :override :label "Binding type-override"
                                 :type (type-of-fn-id storage override-fid)
                                 :source (when (and override-fid (:name own-fn))
                                           {:fn-name (:name own-fn) :fn-id fn-id})}
                                {:key :unified :label "Backward-unified return type"
                                 :type unified
                                 :source (when (and (some? unified) (:name own-fn))
                                           {:fn-name (:name own-fn) :fn-id fn-id})}
                                {:key :ref-return :label "Bound fn return type"
                                 :type (type-of-fn-id storage (:return-type-fn-id ref-fn))
                                 :source (when (:name ref-fn)
                                           {:fn-name (:name ref-fn) :fn-id ref-fid})}
                                {:key :slot :label "Slot declaration"
                                 :type (type-of-fn-id storage (:type-fn-id slot))
                                 :source declaring}]
                  winner       (some (fn [t] (when (some? (:type t)) (:key t))) tiers)
                  chain        (when chain-info
                                 (find-binding-override-chain storage chain-info slot-id))]
              {:winner winner
               :tiers tiers
               :inheritance-chain (or chain [])})))))))


(defn resolve-form
  "Classify a structural type into a form descriptor tree. Pure:
   alias-resolves the input, desugars variants, then dispatches on
   structure:
     record  -> {:kind :record :type T :fields [{:name :type :form} …]}
     list    -> {:kind :list   :type T :element {…}}
     union   -> {:kind :union  :type T :branches [{…} …]}
     leaf    -> {:kind :leaf   :type T}   (primitive / refinement /
                fn-type / unknown — the endpoint picks ONE form-fn)
   Every descriptor carries `:type` — the alias-resolved structural
   type it classified — so `build-form` can try an exact-registry
   dispatch (a registered form-fn for the WHOLE composite type)
   before structurally decomposing it.
   `depth` guards a pathologically deep / recursive record type."
  ([t] (resolve-form t 0))
  ([t depth]
   (let [s0 (types/resolve-alias t)
         s  (if (and (vector? s0) (= :variant (first s0)))
              (types/desugar-variant s0)
              s0)]
     (cond
       (> depth 12)            {:kind :leaf :type s}
       (types/union-type? s)   {:kind :union
                                :type s
                                :branches (mapv (fn [b]
                                                  {:type b
                                                   :form (resolve-form b (inc depth))})
                                                (types/union-members s))}
       (types/list-type? s)    {:kind :list
                                :type s
                                :element (resolve-form (types/list-elem s)
                                                       (inc depth))}
       (types/record-type? s)  {:kind :record
                                :type s
                                :fields (mapv (fn [[k v]]
                                                {:name k
                                                 :type v
                                                 :form (resolve-form v (inc depth))})
                                              s)}
       :else                   {:kind :leaf :type s}))))


;; =============================================================================
;; Form-fn dispatch
;; =============================================================================

(defn registry-pairs
  "Read a type-dispatch registry fn-def (a `:const` vector of
   `[type-name target-fn-name]` string pairs) -> `[[type fn-name] …]`.
   The 1-arity reads `:_value-form-registry`; the 2-arity takes the
   registry fn name so sibling dispatch tables (the value-REPR
   registry in `crud.value-repr`) reuse the same read/normalize path.
   The type-name is alias-resolved to its structural form so a NAMED
   type (a refinement / record alias) matches the structural slot type
   under `subtype?`. Empty when the registry isn't synced yet."
  ([ctx] (registry-pairs ctx "_value-form-registry"))
  ([ctx registry-fn-name]
   (try
     (->> (executor/execute-by-name ctx registry-fn-name {})
          (keep (fn [pair]
                  (when (and (sequential? pair) (= 2 (count pair)))
                    (let [t (first pair)
                          ;; A VECTOR type-name is a structural key,
                          ;; keywordized RECURSIVELY — `["secret" "any"]`
                          ;; → `[:secret :any]`, `["list" ["map" "keyword"
                          ;; "any"]]` → `[:list [:map :keyword :any]]`.
                          ;; Deliberately NO alias resolution inside
                          ;; vectors: alias registration is registry
                          ;; state (per-NS-thread-isolated under the
                          ;; parallel test runner), and a dispatch key
                          ;; must not change meaning with it — write
                          ;; the structural form out. Scalars stay the
                          ;; alias-resolved keyword path.
                          t' (if (sequential? t)
                               ((fn deep
                                  [x]
                                  (if (sequential? x)
                                    (mapv deep x)
                                    (keyword x)))
                                t)
                               (types/resolve-alias (keyword t)))]
                      [t' (str (second pair))]))))
          vec)
     (catch clojure.lang.ExceptionInfo e
       ;; `fn-not-found` is expected during early sync (registry fn-def
       ;; not loaded yet). Other ExceptionInfo types (malformed registry
       ;; result, type-check failure, executor-internal bug) are real
       ;; problems — log so the editor's empty form-picker isn't
       ;; silently masking a regression.
       (let [reason (-> e ex-data :error-data :reason)]
         (when-not (= :fn-not-found reason)
           (log/warn e registry-fn-name "read failed — type dispatch will be empty")))
       [])
     (catch Exception e
       (log/warn e registry-fn-name "read failed — type dispatch will be empty")
       []))))


(defn pick-form-fn
  "Most-specific registered form-fn whose type accepts `leaf-type`.
   `registry` is `[[type-kw fn-name] …]`. Returns the form-fn name
   (string) or nil. Tie-break for two unrelated accepting types:
   prefer the non-`:any` one, then alphabetical."
  [registry leaf-type]
  (let [accepting (filterv (fn [[t _]] (types/subtype? leaf-type t)) registry)]
    (when (seq accepting)
      (let [ts        (mapv first accepting)
            most-spec (first (filter (fn [c]
                                       (every? #(types/subtype? c %) ts))
                                     ts))
            chosen    (or most-spec
                          (first (sort-by #(vector (if (= :any %) 1 0) (str %))
                                          ts)))]
        (some (fn [[t fn-name]] (when (= t chosen) fn-name)) accepting)))))


(defn exact-form-fn
  "Form-fn registered for EXACTLY this structural type — the
   pre-decomposition dispatch tier: a form-fn registered for a whole
   COMPOSITE type wins over structural decomposition (e.g.
   `hiccup-node`'s EDN textarea over its 6-branch union editor).
   Matched by MUTUAL `subtype?` (semantic type equality — robust to
   union-member ordering, which differs between `resolve-alias` and
   the rich-types registry's canonical form) — never one-way
   `subtype?`, so the generic `:any` / `:jsonb` fallback rows can't
   swallow record / union decomposition. nil when no equal row
   exists."
  [registry t]
  (some (fn [[rt fn-name]]
          (when (and (types/subtype? t rt) (types/subtype? rt t))
            fn-name))
        registry))


;; =============================================================================
;; Endpoint stages — parse / validate / apply
;; =============================================================================

(defn parse-value-form-request
  "Stage 1 — JSON body -> `{:binding-id :fn-id :slot-id :item-id}`,
   each coerced to a UUID (nil when absent / malformed)."
  [request]
  (let [body (request/read-json-body request)]
    {:binding-id (request/parse-uuid-or-clear (:binding-id body))
     :fn-id      (request/parse-uuid-or-clear (:fn-id body))
     :slot-id    (request/parse-uuid-or-clear (:slot-id body))
     :item-id    (request/parse-uuid-or-clear (:item-id body))}))


(defn validate-value-form
  "Stage 2 — the request must identify a slot: either a `binding-id`,
   or both `fn-id` and `slot-id` (an unbound free-arg). Returns the
   `{:ok false :error}` rejection, or nil when well-formed."
  [parsed]
  (when-not (or (:binding-id parsed)
                (and (:fn-id parsed) (:slot-id parsed)))
    {:ok false
     :error "Request must include 'binding-id', or both 'fn-id' and 'slot-id'"}))


(defn current-value
  "The literal currently bound at this site — from the list-item row
   when editing a sequence element, else from the binding row. nil
   for an unbound free-arg."
  [storage {:keys [binding-id item-id]}]
  (cond
    item-id    (:value (sp/read-entity storage :binding-list-item item-id))
    binding-id (:value (sp/read-entity storage :binding binding-id))
    :else      nil))


;; =============================================================================
;; Form assembly — leaf controls (with refinement awareness) + composites
;; =============================================================================

(defn- merge-attrs
  "Merge `extra` into the attrs map of a `[tag attrs …]` hiccup vector.
   Inserts an attrs map when the control has none."
  [hiccup extra]
  (cond
    (not (vector? hiccup))           hiccup
    (map? (second hiccup))           (assoc hiccup 1 (merge (second hiccup) extra))
    :else                            (into [(first hiccup) extra] (rest hiccup))))


(defn- enum-of
  "If `t` is a closed-enum refinement `[:refine base [:in members]]`,
   return `{:base base :members [...]}`, else nil."
  [t]
  (when (types/refine-type? t)
    (let [c (types/refine-constraint t)]
      (when (and (vector? c) (= :in (first c)) (sequential? (second c)))
        {:base (types/refine-base t) :members (vec (second c))}))))


(defn- collect-bounds
  "Walk a refinement constraint, collecting `{:min :max}` as INCLUSIVE
   bounds. Exclusive `>` / `<` are nudged by one for an integer base
   (HTML `min`/`max` are inclusive); for a real base they pass through
   as a soft hint — the live type-check stays authoritative."
  [c int-base?]
  (cond
    (not (vector? c))   {}
    (= :and (first c))  (reduce (fn [acc sub] (merge acc (collect-bounds sub int-base?)))
                                {} (rest c))
    :else
    (let [[op n] c]
      (if-not (number? n)
        {}
        (case op
          :>= {:min n}
          :>  {:min (if int-base? (inc n) n)}
          :<= {:max n}
          :<  {:max (if int-base? (dec n) n)}
          {})))))


(defn- numeric-bounds
  "`{:min :max}` for a numeric refinement, or nil."
  [t]
  (when (types/refine-type? t)
    (let [base (types/refine-base t)]
      (when (#{:int :numeric :float} base)
        (not-empty (collect-bounds (types/refine-constraint t) (= base :int)))))))


(defn- build-enum-control
  "A `<select>` for a closed-enum type. Keyword members are rendered
   colon-prefixed so the option value round-trips AS a keyword. `id`
   (when non-nil) links the control to its record-field `<label>`."
  [path id {:keys [base members]}]
  (let [kw?  (= base :keyword)
        opts (mapv (fn [m]
                     (let [s (str m)
                           v (if (and kw? (not (str/starts-with? s ":")))
                               (str ":" s) s)]
                       ["option" {"value" v} v]))
                   members)]
    (into ["select" (cond-> {"class" "arg-value-edit-input"
                             "data-form-field" ""
                             "data-field-kind" "enum"}
                      (seq path) (assoc "data-field-path" path)
                      id         (assoc "id" id))]
          opts)))


(defn- form-fn-control
  "Execute form-fn `fn-name` and enrich its control hiccup with the
   collection path / label id, plus `min`/`max` when `t` is a bounded
   numeric refinement. Shared by the leaf dispatch and the
   exact-registry composite tier."
  [ctx fn-name t path id]
  (let [control (executor/execute-by-name ctx fn-name {})
        bounds  (numeric-bounds t)
        ;; Native HTML `min`/`max` give the browser affordance; the
        ;; live type-check (`validateLiteralAgainstType`) stays the
        ;; source of truth, so no `data-field-min/max` mirror.
        extra   (cond-> {}
                  (seq path)    (assoc "data-field-path" path)
                  id            (assoc "id" id)
                  (:min bounds) (assoc "min" (:min bounds))
                  (:max bounds) (assoc "max" (:max bounds)))]
    (if (seq extra) (merge-attrs control extra) control)))


(defn- build-leaf-form
  "Hiccup control for a leaf (non-composite) type:
     - closed enum  -> a `<select>` of its members,
     - otherwise    -> the registry-picked form-fn's control, enriched
       with `min`/`max` when the type is a bounded numeric refinement.
   `path` (when non-empty) is threaded onto the control as
   `data-field-path` so a composite field collects to the right key;
   `id` (when non-nil) links it to its record-field `<label>`."
  [ctx t path id]
  (if-let [enum (enum-of t)]
    (build-enum-control path id enum)
    (form-fn-control ctx (or (pick-form-fn (registry-pairs ctx) t) "_form-json")
                     t path id)))


(defn- value-fits?
  "True when `value` plausibly belongs to type `t` — used to pick a
   union's initially-active branch. Mirrors the binding type-guard:
   primitive subtype plus a lenient refinement check. A nil value
   fits any branch (caller falls back to branch 0)."
  [value t]
  (let [actual (or (types-lit/classify-literal value) :any)]
    (or (nil? value) (= actual :any) (= t :any)
        (types/subtype? actual t)
        (and (types/refine-type? t)
             (types/subtype? actual (types/refine-base t))
             (let [r (types-lit/literal-satisfies-refinement?
                       value (types/refine-constraint t))]
               (or (true? r) (= :unknown r)))))))


(defn- type-label
  "Short human label for a union branch's `<option>` — a primitive's
   name, a refinement's BASE (not the bare word \"refine\"), `[elem]`
   for a list, `record` / `fn` for composites."
  [t]
  (cond
    (keyword? t)           (name t)
    (types/refine-type? t) (type-label (types/refine-base t))
    (types/list-type? t)   (str "[" (type-label (types/list-elem t)) "]")
    (types/fn-type? t)     "fn"
    (map? t)               "record"
    (and (vector? t) (keyword? (first t))) (name (first t))
    :else                  (pr-str t)))


(defn- render-template
  "Execute one of the `app.forms` composite structure templates
   (`:_form-record-group` / `:_form-record-field` / `:_form-union-*`)
   — the presentational shells live in the GRAPH (restyle them there,
   like the leaf controls); this assembler computes the parts and hands
   them in."
  [ctx template-name args]
  (executor/execute-by-name ctx template-name args))


(defn build-form
  "Recursively assemble the form hiccup for a `resolve-form` descriptor.
   `path` is the dotted `data-field-path` prefix; `id` (when non-nil)
   is the control id a record-field `<label for>` points at; `value`
   is the current value at this path — used to pick a union's active
   branch.

   A record becomes a labelled fieldset. A union becomes a branch
   `<select>` plus one sub-form per branch — the backend pre-selects
   the branch the current value fits, the editor swaps on change; a
   union branch that is itself composite falls back to a JSON editor.
   Lists fall back to a JSON editor (items are edited via
   `/api/sequence/*`).

   A COMPOSITE type with an exact registry row skips decomposition
   entirely — the registered form-fn IS its editor (e.g.
   `hiccup-node` -> the EDN textarea instead of a 6-branch union).

   The recursion + type logic (branch fit, labels, bounds) live HERE;
   the presentational wrappers are graph templates (`render-template`)."
  [ctx desc path id value]
  (if-let [fn-name (and (not= :leaf (:kind desc))
                        (exact-form-fn (registry-pairs ctx) (:type desc)))]
    (form-fn-control ctx fn-name (:type desc) path id)
    (case (:kind desc)
      :record
      (render-template
        ctx "_form-record-group"
        {:fields
         (mapv (fn [f]
                 (let [fname  (name (:name f))
                       fp     (if (str/blank? path) fname (str path "." fname))
                       ;; A nested record / union is a group — its
                       ;; label is a heading, not a `<label for>`.
                       group? (boolean (#{:record :union} (:kind (:form f))))
                       fid    (when-not group? (str "vf-" fp))
                       fval   (when (map? value)
                                (if (contains? value (keyword fname))
                                  (get value (keyword fname))
                                  (get value fname)))]
                   (render-template
                     ctx "_form-record-field"
                     {:field-id fid
                      :field-name fname
                      :control (build-form ctx (:form f) fp fid fval)})))
               (:fields desc))})

      :union
      (let [branches (:branches desc)
            active   (or (first (keep-indexed
                                  (fn [i br] (when (value-fits? value (:type br)) i))
                                  branches))
                         0)
            options  (map-indexed
                       (fn [i br]
                         ["option" (cond-> {"value" (str i)}
                                     (= i active) (assoc "selected" "selected"))
                          (type-label (:type br))])
                       branches)
            select   (render-template ctx "_form-union-select"
                                      {:options (vec options)})
            ;; Branch controls share `path` and carry no `id` — only the
            ;; active (visible) branch is collected. A composite branch
            ;; falls back to a JSON editor.
            branch-divs
            (map-indexed
              (fn [i br]
                (render-template
                  ctx "_form-union-branch"
                  {:index (str i)
                   :active? (= i active)
                   :control (build-leaf-form ctx
                                             (if (= :leaf (:kind (:form br))) (:type br) :any)
                                             path nil)}))
              branches)]
        (render-template ctx "_form-union-wrap"
                         {:select select
                          :branches (vec branch-divs)
                          :active (str active)}))

      :list
      (build-leaf-form ctx :any path id)

      ;; :leaf
      (build-leaf-form ctx (:type desc) path id))))


(defn apply-value-form
  "Stage 3 — resolve the slot's effective type, build the type-aware
   form hiccup, and wrap it in a `data-form-root` div carrying the
   binding ids the editor POSTs back with. Reached only after
   `validate-value-form` passes."
  [parsed ctx]
  (let [storage  (request/require-storage ctx)
        eff-type (or (resolve-slot-effective-type storage parsed) :any)
        cur-val  (current-value storage parsed)
        control  (build-form ctx (resolve-form eff-type) "" nil cur-val)
        root     (cond-> {"data-form-root" ""}
                   (:binding-id parsed) (assoc "data-binding-id" (str (:binding-id parsed)))
                   (:fn-id parsed)      (assoc "data-fn-id" (str (:fn-id parsed)))
                   (:slot-id parsed)    (assoc "data-slot-id" (str (:slot-id parsed)))
                   (:item-id parsed)    (assoc "data-item-id" (str (:item-id parsed))))]
    {:ok    true
     :form  ["div" root control]
     :value cur-val}))
