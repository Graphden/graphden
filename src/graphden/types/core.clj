(ns graphden.types.core
  "Type system core — representation, subtyping, unification.

   Phase 2 of the plan in docs/TYPES.md. Pure data + pure functions —
   no storage, no executor coupling. Other layers (registry, executor)
   call into these primitives.

   ## Representation

   A type is one of:

     keyword  :int :text :bool :float :keyword :jsonb :any :null :uuid
              :timestamptz :bytes :numeric                        — primitive
     symbol   'a 'b                                               — type variable
     map      {:field-name field-type}                            — record (open: more fields = subtype)
     vector   [:list elem-type]                                   — parameterised list
              [:fn args-map ret-type]                             — function: args-map is {arg-name arg-type}

   Compound types nest freely:
     [:fn {:f [:fn {:x 'a} 'b], :coll [:list 'a]} [:list 'b]]    — type of `map`

   ## Why these choices

   - Keywords are already used throughout for primitives — backwards
     compatible with the existing :type enum field.
   - Symbols cleanly distinguish type vars from primitive keywords.
     Clojure prints `'a` as `a` which is unambiguous in the doc.
   - Vectors-with-keyword-head match Clojure's spec/malli convention
     for parameterised constructors. Easy to dispatch on `(first t)`.
   - Records as plain Clojure maps — no constructor wrapper — because
     a record IS a structural map; field-name → field-type is exactly
     what we need.

   ## Subtyping rules (from docs/TYPES.md)

     t ⊆ :any                                              always
     primitive ⊆ :jsonb                                    one-way
     record ⊆ :jsonb                                       one-way
     list ⊆ :jsonb                                         one-way
     {a :int b :text} ⊆ {a :int}                           more fields = subtype
     [:list :a] ⊆ [:list :b]  iff  :a ⊆ :b                 covariant
     [:fn args ret] ⊆ [:fn args' ret']  iff
         ret ⊆ ret'                                        covariant
         and args contravariant, matched the way hof-wrap
         calls the callable — a 1-arg slot positionally
         (param names are alpha-equivalent), a ≥2-arg slot
         by name (map-callable). See `fn-args-subtype?`.
     :jsonb ⊄ any concrete type                            requires explicit conversion"
  (:refer-clojure :exclude [resolve])
  (:require
    [clojure.set]
    [clojure.tools.logging :as log]
    [graphden.types.core.shapes :as shapes]))


;; -----------------------------------------------------------------------------
;; Re-exports — the shape predicates / accessors / taint helpers live
;; in `graphden.types.core.shapes`. 23+ external NSes import this ns
;; and call `types/fn-type?` / `types/record-type?` / … directly, so
;; we re-export each public shape symbol as a Var in THIS ns so the
;; historical surface keeps working without callers changing their
;; imports. New code that ONLY needs shapes can require the
;; `…core.shapes` ns directly.

(def primitives                    shapes/primitives)
(def primitive?                    shapes/primitive?)
(def runtime-predicates            shapes/runtime-predicates)
(def type-var?                     shapes/type-var?)
(def record-type?                  shapes/record-type?)
(def fn-type?                      shapes/fn-type?)
(def callable-type?                shapes/callable-type?)
(def make-fn-type                  shapes/make-fn-type)
(def list-type?                    shapes/list-type?)
(def refine-type?                  shapes/refine-type?)
(def secret-type?                  shapes/secret-type?)
(def secret-inner                  shapes/secret-inner)
(def make-secret-type              shapes/make-secret-type)
(def marker-type?                  shapes/marker-type?)
(def marker-tag                    shapes/marker-tag)
(def marker-inner                  shapes/marker-inner)
(def make-marker-type              shapes/make-marker-type)
(def register-marker!              shapes/register-marker!)
(def unregister-marker!            shapes/unregister-marker!)
(def contains-marker?              shapes/contains-marker?)
(def contains-hide-result-marker?  shapes/contains-hide-result-marker?)
(def coarse-lub                    shapes/coarse-lub)
(def union-type?                   shapes/union-type?)
(def refine-base                   shapes/refine-base)
(def refine-constraint             shapes/refine-constraint)
(def union-members                 shapes/union-members)
(def fn-args                       shapes/fn-args)
(def fn-ret                        shapes/fn-ret)
(def fn-effects                    shapes/fn-effects)
(def list-elem                     shapes/list-elem)
(def map-type?                     shapes/map-type?)
(def map-key                       shapes/map-key)
(def map-val                       shapes/map-val)
(def tuple-type?                   shapes/tuple-type?)
(def tuple-elems                   shapes/tuple-elems)
(def contains-secret?              shapes/contains-secret?)
(def child-types                   shapes/child-types)
(def type-any?                     shapes/type-any?)


(def known-effect-categories
  "THE effect vocabulary — single source for every consumer: the
   checker's declaration gate (`types.check/known-effect-categories`
   aliases this), the runtime recording set
   (`compile-runtime/known-effects` aliases this), the editor's
   declared-effects form roster, and the graph's
   `:_effect-descriptions` table (guarded by
   `editor-shell-partials-test`). One set — a category can never be
   recordable-but-undeclarable again (`:state` was exactly that:
   `:swap`/`:reset` recorded it, the checker rejected declaring it,
   and every composition over them wore a permanent un-fixable
   drift chip).

   `:cross-org` is a PLATFORM-ONLY category (the cloud domain-router's
   `:execute-in-org` primitive records it): it is in `known-effect-
   categories` so the checker admits its declaration and propagates it,
   and in `cloud-forbidden-effects` so NO tenant `:allowed-effects` set
   ever contains it — a tenant graph that references `:execute-in-org`
   gets `:execution/forbidden-effect`, while the platform router runs
   unrestricted (nil `*allowed-effects*`). Structurally identical to the
   `:raw-sql`/`:pg-query` gate. Declarable-but-inert for tenants."
  #{:db :env :io :network :process :state :time :random :raw-sql :cross-org})


(def taint-with-secret-if-tainted  shapes/taint-with-secret-if-tainted)
(def wrap-with-taint               shapes/wrap-with-taint)


;; -----------------------------------------------------------------------------
;; Validity check — anything else is malformed

(declare aliases-snapshot)


;; Validation-time scratch view. While `register-type-aliases-batch`
;; (or self-recursive `register-type-alias!`) is checking a body
;; with forward / mutual / self references, every pending name is
;; pre-bound here so `well-formed?`'s keyword check passes for
;; references that have no entry in the live atom yet. nil ⇒ fall
;; through to the live registry (the normal lookup path).
(def ^:dynamic *alias-view* nil)


(defn well-formed?
  "True iff `t` is a syntactically valid type. Use this as a guard at
   the boundary between user input (fns.edn parsing, API form data)
   and the rest of the type module.

   A non-primitive keyword is well-formed iff it names a registered
   alias — this lets compound types reference other named types
   (`[:fn {:request :ring-request} :ring-response]`) without first
   substituting them, as long as the inner names are known."
  [t]
  (cond
    ;; Classifier sentinels are valid types alongside primitives and
    ;; vars: `:empty-map` (the `{}` literal) survives into canonical
    ;; `make-union` outputs (`[:union :empty-map <record>]` branch
    ;; returns); `:never` is the bottom type. Neither is in
    ;; `primitives` nor an alias.
    (or (primitive? t) (type-var? t)
        (contains? #{:empty-map :never} t)) true
    (keyword? t)     (boolean (get (aliases-snapshot) t))
    (record-type? t) (every? well-formed? (vals t))
    (fn-type? t)     (and (map? (fn-args t))
                          (every? keyword? (keys (fn-args t)))
                          (every? well-formed? (vals (fn-args t)))
                          (well-formed? (fn-ret t))
                          (let [eff (fn-effects t)]
                            (or (nil? eff)
                                (= eff :any)
                                (and (set? eff)
                                     (every? keyword? eff)))))
    (list-type? t)   (well-formed? (list-elem t))
    (map-type? t)    (and (well-formed? (map-key t)) (well-formed? (map-val t)))
    (tuple-type? t)  (every? well-formed? (tuple-elems t))
    (refine-type? t) (well-formed? (refine-base t))
    (marker-type? t) (well-formed? (marker-inner t))
    (union-type? t)  (every? well-formed? (union-members t))
    :else            false))


(defn unresolved-refs
  "The keyword refs inside `t` that name NO registered alias — the
   concrete reason `well-formed?` rejects a body. Diagnostic
   companion (mirrors `well-formed?`'s walk): the batch registration
   names these in its skip log so a dangling ref is actionable
   instead of an opaque `body not well-formed`. A structurally
   invalid node contributes nothing — `well-formed?` already covers
   that shape of failure."
  [t]
  (cond
    (or (primitive? t) (type-var? t)
        (contains? #{:empty-map :never} t)) []
    (keyword? t)     (if (get (aliases-snapshot) t) [] [t])
    (record-type? t) (mapcat unresolved-refs (vals t))
    (fn-type? t)     (concat (mapcat unresolved-refs (vals (fn-args t)))
                             (unresolved-refs (fn-ret t)))
    (list-type? t)   (unresolved-refs (list-elem t))
    (map-type? t)    (concat (unresolved-refs (map-key t))
                             (unresolved-refs (map-val t)))
    (tuple-type? t)  (mapcat unresolved-refs (tuple-elems t))
    (refine-type? t) (unresolved-refs (refine-base t))
    (marker-type? t) (unresolved-refs (marker-inner t))
    (union-type? t)  (mapcat unresolved-refs (union-members t))
    :else            []))


;; `contains-secret?` / `taint-with-secret-if-tainted` / `wrap-with-taint`
;; live in `graphden.types.core.shapes` and are re-referred into this
;; ns above so historical `types/contains-secret?` etc. keep working.


;; Forward declaration — `make-union` consults `subtype?` for
;; absorption (drop members strictly subsumed by another sibling).
;; `subtype?` itself only calls `make-union` indirectly through
;; `normalise → resolve-alias`, and only on STRICTLY SMALLER subtypes
;; in the type tree, so the mutual recursion bottoms out.
(declare subtype?)


(defn make-union
  "Smart constructor for `[:union …]`. Flattens nested unions, drops
   duplicates, and collapses singletons (a 1-member union IS that
   member). Result: either a non-union type or a `[:union T1 T2 …]`
   with ≥2 distinct flattened members.

   Use this instead of building `[:union …]` literals directly so
   the rest of the system can rely on the canonical shape."
  [members]
  (let [flat (mapcat (fn [m] (if (union-type? m) (union-members m) [m]))
                     members)
        ;; `:never` (bottom) drops out of a union — `[:union :never T]`
        ;; = `T` — dual to `:any` (top) absorbing. A union of nothing
        ;; but `:never` members collapses back to `:never`.
        non-never (remove #{:never} flat)
        flat (if (seq non-never) non-never flat)
        ;; NOTE on `:empty-map`: no special case needed — the generic
        ;; subtype-absorption pass below drops it next to any sibling
        ;; it subtypes (`:jsonb` / `[:map K V]`) and keeps it next to
        ;; records (`:empty-map ⊄ record` — required fields). The
        ;; typevar guard in that pass skips SYMBOLS only; keywords
        ;; like the sentinel are evaluated normally.
        ;; Stable order to keep the canonical form deterministic for
        ;; equality. `pr-str` sorts heterogeneous keywords/vectors
        ;; without confusing comparators.
        unique (vec (sort-by pr-str (distinct flat)))
        ;; Subtype absorption: `T ⊆ S` ⟹ T is redundant in
        ;; `[:union T S]` because every value of T is already a
        ;; value of S. Drop any non-typevar member that's a STRICT
        ;; subtype of some other non-typevar sibling. Type-vars
        ;; are KEPT (they're polymorphic — `'a` may bind to
        ;; anything, so we can't reason about subtype here) but
        ;; can't absorb others either. Without this the checker
        ;; surfaces redundant unions like
        ;; `[:union :int :positive-int]` (where `:positive-int ⊆
        ;; :int` makes the wider `:int` cover everything) — UI
        ;; chips and `:if`/`:cond` rule outputs end up noisier than
        ;; the semantics warrants.
        absorbed (vec
                   (keep-indexed
                     (fn [i m]
                       (if (type-var? m)
                         m
                         (when-not (some (fn [[j other]]
                                           (and (not= i j)
                                                (not (type-var? other))
                                                (not= m other)
                                                (subtype? m other)
                                                ;; If two members are
                                                ;; mutually-subtype
                                                ;; (alias equality
                                                ;; through normalise),
                                                ;; keep the lower-
                                                ;; indexed one.
                                                (or (not (subtype? other m))
                                                    (< j i))))
                                         (map-indexed vector unique))
                           m)))
                     unique))
        unique (if (seq absorbed) absorbed unique)]
    (cond
      ;; Union of NO members is the bottom type — nothing inhabits it.
      ;; Without this arm, `(make-union [])` produced the malformed
      ;; literal `[:union]` (not union-type?, rejected by well-formed?),
      ;; reachable from `check-sequence-items` on an EMPTY literal
      ;; vector bound to a `[:list a]` slot — the malformed type then
      ;; bound into the substitution and leaked into recorded registry
      ;; types. `:never` is the principled answer (dual of the
      ;; `:never`-absorption above) and downstream `[:list :never]`
      ;; subtypes any `[:list T]` covariantly.
      (empty? unique) :never
      (= 1 (count unique)) (first unique)
      (some #{:any} unique) :any        ; :any absorbs everything
      :else (into [:union] unique))))


(defn strip-null
  "Remove `:null` members from a top-level union; the bare `:null` type
   becomes `:never` (bottom). A non-union, non-`:null` input passes
   through unchanged. Conservative on `[:secret …]`: a secret-wrapped
   nullable carries `:null` INSIDE the wrapped union, not at top level,
   so this leaves the taint marker intact — it never strips a secret.

   The narrowing helper (`:some?`/`:nil?` control-flow refinement in
   `types.check.narrowing`) and the `:coalesce` return-rule
   (`core/logic/impls.clj`) both remove nullability the same way; this
   is their single source of truth."
  [t]
  (cond
    (union-type? t)
    (let [members (vec (remove #{:null} (union-members t)))]
      (cond
        (empty? members) :never
        (= 1 (count members)) (first members)
        :else (make-union members)))
    (= t :null) :never
    :else t))


(defn desugar-variant
  "Desugar `[:variant tag1 T1 tag2 T2 …]` to its structural union form
   per docs/TYPES.md § Tagged Variants:

     [:union {:tag [:refine :keyword [:= :tag1]] :value T1}
             {:tag [:refine :keyword [:= :tag2]] :value T2}
             …]

   Each branch is a record pinned by an equality refinement on the
   `:tag` slot, making the variant discriminable at runtime via
   `(:variant-is? v :tag …)`. Accepts the alternate input shape
   `[tag1 T1 tag2 T2 …]` (without the leading `:variant` marker) so
   callers don't have to strip it.

   Returns nil if the payload is malformed (odd count, non-keyword
   tags, empty list) so the caller can surface it as an alias-body
   well-formedness failure."
  [t]
  (let [pairs-raw (cond
                    (and (vector? t) (= :variant (first t))) (vec (rest t))
                    (sequential? t)                          (vec t)
                    :else                                    nil)]
    (when (and pairs-raw
               (seq pairs-raw)
               (even? (count pairs-raw))
               (every? keyword? (take-nth 2 pairs-raw)))
      (let [pairs (partition 2 pairs-raw)
            branches (mapv (fn [[tag-kw branch-t]]
                             {:tag [:refine :keyword [:= tag-kw]]
                              :value branch-t})
                           pairs)]
        (make-union branches)))))


;; -----------------------------------------------------------------------------
;; Type aliases — keyword → structural type
;;
;; Lets a base-fn / fn-def declare `:type :positive-int` and have
;; the system resolve it to `[:refine :int [:> 0]]`. Reuses the
;; existing keyword surface in `:type` slots; primitives shadow
;; aliases when the same keyword names both (so registering a
;; bogus alias for `:int` is a no-op).

(defonce ^:private type-aliases (atom {}))


;; Thread-local override of the process-global `type-aliases` atom.
;; When non-nil, every `register-type-alias!` / `clear-aliases!` /
;; `resolve-alias` operation reads and writes THIS atom instead of
;; the process-global one. Tests bind it via a `:each` fixture to
;; get an isolated alias map per NS, so parallel test runs don't
;; race on the shared atom.
;;
;; Mirrors `graphden.executor.registry.core/*registry-override*`.
;; Production paths leave it nil and hit the global atom.
(def ^:dynamic *type-aliases-override* nil)


(defn- aliases-atom
  []
  (or *type-aliases-override* type-aliases))


(declare register-type-aliases-batch)


;; Diagnostic side-table `{alias-name → owner-fn-id}` for the GLOBAL
;; registry only (override-bound contexts — test isolation, per-org
;; filtered views — skip it). Per-namespace names mean two DIFFERENT
;; type-rows may legally share a bare name; until alias RESOLUTION is
;; namespace-aware (per-ns migration stage 4, ADR-identity-model.md)
;; the registry stays last-write-wins — this table makes the
;; cross-owner overwrite LOUD instead of silent, so a same-named type
;; shadowing another is a visible event, not a mystery type error.
(defonce ^:private alias-owners (atom {}))


;; Side-table `{bare-alias-name → {owner-fn-id qualified-keyword}}` for
;; the GLOBAL registry. Per-ns names mean two DIFFERENT type-rows may
;; legally share a bare alias name; each also registers under its
;; QUALIFIED `:ns.path/name` keyword. When a bare name has ≥2 distinct
;; owners it is AMBIGUOUS — `resolve-alias` throws, naming the
;; qualified candidates, instead of silently resolving to whichever
;; registration happened last (the shadowing this table exists to
;; kill). Mirrors the per-(ns,name) fn-name model
;; (ADR-identity-model.md stage 4/5).
(defonce ^:private alias-qualified (atom {}))


(defn- track-alias-owner!
  "Record `owner-fn-id` as the owner of `alias-name`, warn-logging when
   a DIFFERENT owner previously registered the name (the cross-ns
   collision the per-ns migration exists to resolve). No-op when the
   owner is unknown or an override registry is bound."
  [alias-name owner-fn-id]
  (when (and owner-fn-id (nil? *type-aliases-override*))
    (let [prev (get @alias-owners alias-name)]
      (when (and prev (not= prev owner-fn-id))
        (log/warn "type-alias collision: name re-bound by a DIFFERENT type-row"
                  {:alias alias-name
                   :previous-owner prev
                   :new-owner owner-fn-id
                   :consequence (str "the BARE name is now AMBIGUOUS — "
                                     "resolve-alias throws on it; use the "
                                     "QUALIFIED :ns.path/name form at every "
                                     "reference site")}))
      (swap! alias-owners assoc alias-name owner-fn-id))))


(defn register-type-alias!
  "Bind `alias-name` to a structural type. Throws if `alias-name` is
   already a primitive (would shadow it confusingly) or if `t` isn't
   well-formed. Idempotent — re-registering the same name replaces the
   binding.

   Self-recursive bodies (e.g. `[:list :tree]` for `alias-name = :tree`)
   are valid here: validation runs against a view that includes the
   name being registered, so `:tree` resolves as a known reference
   inside its own body. For mutual recursion across names, batch
   them via `register-type-aliases-batch`.

   The optional `owner-fn-id` feeds the collision diagnostic (see
   `alias-owners` above) — pass the declaring type-row's id whenever
   the caller holds one."
  ([alias-name t] (register-type-alias! alias-name t nil))
  ([alias-name t owner-fn-id] (register-type-alias! alias-name t owner-fn-id nil))
  ([alias-name t owner-fn-id qualified-name]
   (when (primitives alias-name)
     (throw (ex-info (str "type-alias name shadows a primitive: " (pr-str alias-name))
                     {:type :types/invalid-alias
                      :name alias-name})))
   (binding [*alias-view* (assoc @(aliases-atom) alias-name :any)]
     (when-not (well-formed? t)
       (throw (ex-info (str "type-alias body is not well-formed: " (pr-str t))
                       {:type :types/invalid-alias
                        :name alias-name :body t}))))
   (track-alias-owner! alias-name owner-fn-id)
   (swap! (aliases-atom) assoc alias-name t)
   ;; Qualified variant — the collision-proof reference form. Written
   ;; to the same alias map (resolve-alias needs no special arm; works
   ;; in override-bound contexts too). The bare→qualified ambiguity
   ;; side-table is GLOBAL-registry bookkeeping only, like
   ;; `alias-owners`.
   (when qualified-name
     (swap! (aliases-atom) assoc qualified-name t))
   ;; Ambiguity bookkeeping tracks EVERY owner, qualified or not — a
   ;; version-materialized (`@`) namespace can't register a qualified
   ;; keyword, but its collision with another owner must still make
   ;; the bare name throw (the candidates list then names the
   ;; versioned package by its ns-path string instead of a keyword).
   (when (and owner-fn-id (nil? *type-aliases-override*))
     (swap! alias-qualified update alias-name
            (fnil assoc {}) owner-fn-id
            (or qualified-name alias-name)))
   alias-name))


(defn resolve-alias
  "Recursive keyword alias resolution. Primitives pass through;
   registered aliases expand to their structural body; other
   keywords (unknown — typically user error caught later) pass
   through too. Compound types ARE recursed into so an alias that
   appears inside `[:fn args ret]`, `[:list T]`, `{:k T}`, etc.
   gets expanded to its structural form — otherwise
   `well-formed?` would reject the wrapper for unknown keywords.

   `seen` tracks alias names already followed in the current chain
   so a circular alias (`:a → :b → :a`) breaks out instead of
   stack-overflowing — the offending name passes through
   unresolved, and the surrounding `well-formed?` check turns it
   into a clean error."
  ([t] (resolve-alias t #{}))
  ([t seen]
   (cond
     (or (primitive? t) (type-var? t)) t
     (keyword? t)     (if (contains? seen t)
                        t
                        (if-let [target (@(aliases-atom) t)]
                          (do
                            ;; A BARE name registered by ≥2 distinct
                            ;; type-rows is ambiguous — refuse to pick
                            ;; a winner silently; the author must use
                            ;; a qualified form. Qualified keywords
                            ;; (and override-bound test registries)
                            ;; skip the check.
                            (when (and (nil? (namespace t))
                                       (nil? *type-aliases-override*))
                              (let [owners (get @alias-qualified t)]
                                (when (> (count owners) 1)
                                  (throw (ex-info
                                           (str "type-alias " (pr-str t)
                                                " is ambiguous — declared by "
                                                (count owners) " type-rows; "
                                                "use a qualified form: "
                                                (pr-str (sort (vals owners))))
                                           {:type :types/ambiguous-alias
                                            :name t
                                            :candidates (sort (vals owners))})))))
                            (resolve-alias target (conj seen t)))
                          t))
     (fn-type? t)     (let [base [:fn
                                  (into {} (map (fn [[k v]] [k (resolve-alias v seen)])) (fn-args t))
                                  (resolve-alias (fn-ret t) seen)]
                            eff (fn-effects t)]
                        ;; Effect constraint passes through verbatim —
                        ;; it's a set of category keywords, not types
                        ;; that need resolution.
                        (if eff (conj base eff) base))
     (list-type? t)   [:list (resolve-alias (list-elem t) seen)]
     (map-type? t)    [:map (resolve-alias (map-key t) seen)
                       (resolve-alias (map-val t) seen)]
     (tuple-type? t)  (into [:tuple]
                            (map #(resolve-alias % seen))
                            (tuple-elems t))
     (record-type? t) (into {} (map (fn [[k v]] [k (resolve-alias v seen)])) t)
     (refine-type? t) [:refine (resolve-alias (refine-base t) seen) (refine-constraint t)]
     (marker-type? t) [(marker-tag t) (resolve-alias (marker-inner t) seen)]
     (union-type? t)  (make-union (mapv #(resolve-alias % seen) (union-members t)))
     :else            t)))


(defn aliases-snapshot
  "Returns the current `{name struct-type}` map. Test convenience.
   During batch registration the dynamic `*alias-view*` shadows
   the atom — see `register-type-aliases-batch`."
  []
  (or *alias-view* @(aliases-atom)))


(defn register-type-aliases-batch
  "Register a batch of `[alias-name body owner-fn-id?]` tuples that may reference
   each other or themselves — typical for record-types with
   mutual / recursive shape (Tree, LinkedList, Person↔Address).

   Validation runs against a view of the alias registry pre-extended
   with EVERY pending name (bound to `:any` as a placeholder), so
   `well-formed?`'s keyword check accepts forward references inside
   the batch. After all bodies pass, the actual bodies are committed
   atomically to the live atom.

   Individual failures don't abort the batch — they're collected and
   returned. Caller decides whether to log / surface them. Returns
   `{:registered #{names} :failed [{:name n :body b :reason r} ...]}`.

   DELIBERATE BOUNDARY vs the singular `register-type-alias!`: the
   batch records owner diagnostics (warn on cross-owner overwrite)
   but does NOT register qualified `:ns/name` variants or ambiguity
   throw-records — its production caller (`register-type-aliases-
   from-db!`) has no namespace rows in the graph shape to derive
   qualified names from, and flipping DB-path collisions from
   last-write-wins+warn to throw would break existing graphs with no
   qualified escape hatch. Collision blast radius is bounded by the
   per-org alias slices (an org resolves only public + its own).
   Revisit when ns rows join the read-graph shape."
  [pairs]
  (let [proposed-names (into #{} (keep first) pairs)
        owner-of (into {}
                       (keep (fn [[nm _ owner]] (when owner [nm owner])))
                       pairs)
        scratch (merge @(aliases-atom)
                       (zipmap proposed-names (repeat :any)))
        classify
        (fn [[nm body]]
          (cond
            (nil? nm)              {:nm nm :body body :reason "alias name is nil"}
            (primitives nm)        {:nm nm :body body
                                    :reason (str "name " (pr-str nm)
                                                 " shadows a primitive")}
            (not (well-formed? body))
            {:nm nm :body body
             :reason (let [dangling (distinct (unresolved-refs body))]
                       (if (seq dangling)
                         (str "body not well-formed — unresolved refs "
                              (pr-str (vec dangling)))
                         "body not well-formed — invalid structure"))}
            :else                  {:nm nm :body body :ok true}))
        results (binding [*alias-view* scratch]
                  (mapv classify pairs))
        ok-pairs (->> results (filter :ok) (mapv (juxt :nm :body)))
        failed   (->> results (remove :ok)
                      (mapv (fn [r] (select-keys r [:nm :body :reason]))))]
    (when (seq ok-pairs)
      (doseq [[nm _] ok-pairs]
        (track-alias-owner! nm (get owner-of nm)))
      (swap! (aliases-atom)
             (fn [m]
               (reduce (fn [acc [nm body]] (assoc acc nm body))
                       m ok-pairs))))
    {:registered (into #{} (map first) ok-pairs)
     :failed     failed}))


(defn type->storage-kind
  "Reduce a (possibly structured) type to the primitive enum tag the
   DB's `value-kind` column accepts. Storage-side enums don't carry
   structure; the rich type lives in the in-memory rich-types registry
   and degrades to one of these primitives on the wire.

       :int            → :int
       'a              → :any        (type vars are storage-untagged)
       [:fn …]         → :fn
       [:list …]       → :sequence   (canonical chain shape)
       {…}             → :jsonb      (records are jsonb-shaped on the wire)
       [:refine B c]   → storage-kind of B (the constraint lives only
                                            in the type system)
       [:union …]      → :any        (no single storage tag fits a union)
       :never          → :any        (bottom type — no value is ever
                                       `:never`-typed at rest)
       :input-stream   → :any        (transient runtime object — never
                                       stored as data)
       :decimal        → :numeric    (storage value_kind has no
                                       :decimal — degrades to its super)
       <alias keyword> → resolves through `resolve-alias` first, then
                          recurses on the structural body."
  [t]
  (let [t' (resolve-alias t)]
    (cond
      ;; `:empty-map` — classifier sentinel for the `{}` literal;
      ;; jsonb-shaped at rest like every map value.
      (= t' :empty-map)    :jsonb
      (or (= t' :never) (= t' :input-stream)) :any
      (= t' :decimal)      :numeric
      (primitive? t')   t'
      (type-var? t')    :any
      (fn-type? t')     :fn
      (or (list-type? t') (tuple-type? t')) :sequence
      (or (map-type? t') (record-type? t')) :jsonb
      (refine-type? t') (recur (refine-base t'))
      (union-type? t')  :any
      :else             nil)))


(defn unregister-type-alias!
  "Drop an alias registration. Two arities with DIFFERENT blast radius:

   1-arity (tests / full-clear): drops the bare name, EVERY owner's
   qualified variant, and the whole ambiguity record — the nuke the
   test-cleanup paths want.

   2-arity `[alias-name owner-fn-id]` (the delete-path form): drops
   ONLY that owner's qualified alias + its ambiguity entry. The bare
   name is removed only when no other owner remains; when exactly one
   sibling owner survives, the bare name is re-pointed at ITS body so
   bare references resolve again instead of throwing ambiguous. The
   1-arity's collateral deletion of SIBLING owners' qualified
   registrations was the audited hazard this arity exists to avoid."
  ([alias-name]
   (when (nil? *type-aliases-override*)
     (swap! alias-owners dissoc alias-name)
     ;; Drop the qualified variants + ambiguity bookkeeping too.
     (doseq [q (vals (get @alias-qualified alias-name))]
       (swap! (aliases-atom) dissoc q))
     (swap! alias-qualified dissoc alias-name))
   (swap! (aliases-atom) dissoc alias-name)
   nil)
  ([alias-name owner-fn-id]
   (if (some? *type-aliases-override*)
     ;; Override-bound (test isolation) registries carry no ambiguity
     ;; bookkeeping — fall back to the plain drop.
     (swap! (aliases-atom) dissoc alias-name)
     (let [owners (get @alias-qualified alias-name)
           own-q  (get owners owner-fn-id)
           rest-owners (dissoc owners owner-fn-id)]
       (when (and own-q (not= own-q alias-name))
         (swap! (aliases-atom) dissoc own-q))
       (if (seq rest-owners)
         (do (swap! alias-qualified assoc alias-name rest-owners)
             ;; Exactly one survivor → bare name resolves to it again.
             (when (= 1 (count rest-owners))
               (let [[surv-owner surv-q] (first rest-owners)]
                 (swap! alias-owners assoc alias-name surv-owner)
                 (when-let [body (get @(aliases-atom) surv-q)]
                   (swap! (aliases-atom) assoc alias-name body)))))
         (do (swap! alias-qualified dissoc alias-name)
             (swap! alias-owners dissoc alias-name)
             (swap! (aliases-atom) dissoc alias-name)))))
   nil))


(defn clear-aliases!
  "Drop every registered alias. Test convenience — production
   registers aliases via `register-alias!` calls driven by
   `:type` / `:refine` / `:list` / `:union` / `:variant` fn-defs in
   the package loader."
  []
  (when (nil? *type-aliases-override*)
    (reset! alias-owners {})
    (reset! alias-qualified {}))
  (reset! (aliases-atom) {}))


(defn global-aliases-snapshot
  "Test-support: capture the PROCESS-GLOBAL alias state — the three registry
   atoms (struct map, owners, qualified names), ignoring any
   `*type-aliases-override*`. Pair with `restore-global-aliases!` so a namespace
   whose tests deliberately drive the global registry (`*type-aliases-override*`
   nil — e.g. the owner-collision-warning path) can't leak into a sibling NS.
   Mirrors `registry/snapshot-for-isolation` for rich-types."
  []
  {:aliases @type-aliases :owners @alias-owners :qualified @alias-qualified})


(defn restore-global-aliases!
  "Test-support: reset the process-global alias state to a snapshot taken by
   `global-aliases-snapshot`."
  [{:keys [aliases owners qualified]}]
  (reset! type-aliases aliases)
  (reset! alias-owners owners)
  (reset! alias-qualified qualified))


;; -----------------------------------------------------------------------------
;; Substitutions — type-var → type binding map

(defn resolve
  "Walk a substitution chain to a fixed point. If `t` is a type
   variable bound in `subst`, follow the binding; recurse on compound
   types so nested vars are also resolved."
  [subst t]
  (cond
    (type-var? t) (if-let [v (get subst t)]
                    (if (= v t) t (resolve subst v))
                    t)
    (fn-type? t) (let [base [:fn
                             (into {} (map (fn [[k v]] [k (resolve subst v)])) (fn-args t))
                             (resolve subst (fn-ret t))]
                       eff (fn-effects t)]
                   ;; Preserve the effect constraint when present.
                   ;; Without this, type-var substitution silently
                   ;; drops the 4th element and the slot-effect-
                   ;; constraint check at unify-time gets nil instead
                   ;; of the declared set.
                   (if eff (conj base eff) base))
    (list-type? t)   [:list (resolve subst (list-elem t))]
    (map-type? t)    [:map (resolve subst (map-key t)) (resolve subst (map-val t))]
    (tuple-type? t)  (into [:tuple] (map #(resolve subst %)) (tuple-elems t))
    (record-type? t) (into {} (map (fn [[k v]] [k (resolve subst v)])) t)
    (refine-type? t) [:refine (resolve subst (refine-base t)) (refine-constraint t)]
    (marker-type? t) [(marker-tag t) (resolve subst (marker-inner t))]
    (union-type? t)  (make-union (mapv #(resolve subst %) (union-members t)))
    :else t))


(defn- occurs?
  "Standard occurs-check — does `v` appear inside `t` after applying
   `subst`? Required to keep unification sound; without it
   `unify('a, [:list 'a])` would build an infinite type.

   Must cover EVERY compound shape `types/core` defines — a missing
   arm here lets a self-referential binding slip through unification
   and the type-checker builds an infinite type. The constraint
   payload on `:refine` is opaque (a vector of comparators / regex /
   `:and`/`:or` shapes) and never names a type-variable, so the
   refine arm only recurses into the base."
  [v t subst]
  (let [t' (resolve subst t)]
    (cond
      (= v t')         true
      (type-var? t')   false
      (fn-type? t')    (or (some #(occurs? v % subst) (vals (fn-args t')))
                           (occurs? v (fn-ret t') subst))
      (list-type? t')  (occurs? v (list-elem t') subst)
      (map-type? t')   (or (occurs? v (map-key t') subst)
                           (occurs? v (map-val t') subst))
      (tuple-type? t') (some #(occurs? v % subst) (tuple-elems t'))
      (record-type? t') (some #(occurs? v % subst) (vals t'))
      (refine-type? t') (occurs? v (refine-base t') subst)
      (marker-type? t') (occurs? v (marker-inner t') subst)
      (union-type? t') (some #(occurs? v % subst) (union-members t'))
      :else            false)))


;; -----------------------------------------------------------------------------
;; Subtyping
;; (Forward-declared near `make-union` so absorption can consult it.)


(defn- record-subtype?
  "Open record subtyping: sub is a subtype of sup if it has every
   field sup requires, with sub's field type ⊆ sup's. Extra fields
   on sub are allowed."
  [sub sup]
  (every? (fn [[k st]]
            (when-let [t (get sub k)]
              (subtype? t st)))
          sup))


(defn- list-subtype?
  "List subtyping — covariant in element type. Typevar on sup side
   accepts any concrete sub elem; same rationale as `map-subtype?`."
  [sub sup]
  (or (type-var? (list-elem sup))
      (subtype? (list-elem sub) (list-elem sup))))


(defn- map-subtype?
  "Homogeneous-map subtyping — covariant in both key and value type.
   Typevars on the sup side accept any concrete sub component (the
   slot is parametric, the call-site picks a binding); without this
   `[:map :keyword :any] ⊆ [:map a :any]` fails at the first key
   subtype check. The TOP-level `subtype?` deliberately does NOT
   blanket-accept typevar-sup (would short-circuit `unify`'s typevar
   binding step). Structural helpers like THIS, where the typevar
   is passive recursion context, do."
  [sub sup]
  (and (or (type-var? (map-key sup))
           (subtype? (map-key sub) (map-key sup)))
       (or (type-var? (map-val sup))
           (subtype? (map-val sub) (map-val sup)))))


(defn- tuple-subtype?
  "Fixed-length-tuple subtyping — equal length, covariant per position."
  [sub sup]
  (let [a (tuple-elems sub) b (tuple-elems sup)]
    (and (= (count a) (count b))
         (every? true? (map subtype? a b)))))


(defn- effects-compatible?
  "Slot-effect-constraint check. The callee's effects (`sub-eff`) must
   be a subset of the slot's allowed set (`sup-eff`).

   - `sup-eff` nil or `:any` → the slot declares no constraint; any
     callee passes.
   - `sub-eff` nil → the callee is PURE. graphden computes effects
     totally — `compute-effects` runs on every fn-def and treats an
     absent `:effects` as `#{}`, and `record-result!` stores
     `:effects` only when non-empty — so a missing/nil effect set IS
     `#{}` (computed-pure), not \"unknown\". Treating it as pure keeps
     `effects-compatible?` consistent with `compute-effects`; the
     opposite (\"can't prove pure → assume impure\") would make a
     `#{}` pure-only slot unsatisfiable by any ordinary pure fn.
   - `sub-eff` `:any` → the callee's effects are explicitly
     unconstrained; it cannot satisfy a concrete `sup-eff`."
  [sub-eff sup-eff]
  (cond
    (or (nil? sup-eff) (= sup-eff :any)) true
    (= sub-eff :any)                     false
    (set? sup-eff)                       (every? sup-eff (or sub-eff #{}))
    :else                                true))


(defn- fn-args-subtype?
  "Contravariant argument check for `fn-subtype?`, dispatched on the
   SLOT (sup) arity — which fixes the calling convention the
   executor's `hof-wrap` uses for a bound callable:

   - sup arity = 0 → variadic-ignore callable; the impl invokes it
     as `(f)` and the wrap passes no per-call args. The callee's
     free args (if any) are CAPTURED from the binding-chain at wrap
     time (closure-capture; docs/CLOSURE_CAPTURE.md). Any sub arity
     accepts.

   - sup arity = 1 → single-arg callable; the impl invokes as
     `(f v)`. Parameter NAMES are local bound variables —
     alpha-equivalent — so the lone arg pair is compared by
     POSITION, not by name. A callee with extra free args
     (optional / captured) still satisfies a 1-arg slot: hof-wrap
     supplies the lone call-site value to the callee's first free
     arg name; the rest are captured. A 0-arg callee satisfies a
     1-arg slot too — the value is just ignored.

   - sup arity ≥ 2 → map-callable; `hof-wrap` fills the callee's free
     args BY NAME (`(f {:k v …})`), so names ARE significant. Every
     sup param must name an arg the callee exposes; those pairs are
     compared contravariantly. Extra callee args are fine — captured
     from the environment.

   `a` is the sub (callee) arg-map, `b` the sup (slot) arg-map.
   Contravariance: `sup-arg ⊆ sub-arg`."
  [a b]
  (cond
    ;; 0-arg slot — wrap passes nothing per call. Closure-capture
    ;; handles every sub free arg as captured; any sub arity OK.
    (zero? (count b))
    true

    ;; Map-callable slot — by name.
    (>= (count b) 2)
    (every? (fn [[k bt]]
              (when-let [at (get a k)]
                ;; `:any` or a free type-var on the slot side is "no
                ;; concrete constraint" — the callee may narrow it
                ;; freely (assertion-style: "I expect this loose slot
                ;; to actually carry T"). Typevar specifically: the
                ;; slot's `a` gets bound at the call site, so any
                ;; concrete callee arg-type is a valid satisfying
                ;; assignment. Without the typevar arm, a callee
                ;; tightened by upstream propagation (e.g. `:item
                ;; [:union :null …]` after `:get :coll` narrowed the
                ;; inferred shape) rejects a `[:fn {:item a} …]`
                ;; slot even though the contravariant relation
                ;; trivially holds under unification.
                (or (= bt :any) (type-var? bt) (subtype? bt at))))
            b)

    ;; Single-arg / nullary slot, callee of the same arity — positional.
    ;; Strict contravariance on `:any` (per `subtype-fn-test` line 414:
    ;; positional `:any` slot ARG is NOT "no constraint" — slot
    ;; promises `:any` at call time and a callee restricting to `:int`
    ;; would crash). Typevars on the slot side DO accept any callee
    ;; arg-type — same logic as `map-subtype?`'s sup-typevar arm,
    ;; because the slot's typevar is bound at the call site.
    (= (count a) (count b))
    (let [av (mapv val (sort-by key a))
          bv (mapv val (sort-by key b))]
      (every? (fn [i]
                (let [bt (get bv i) at (get av i)]
                  (or (type-var? bt) (subtype? bt at))))
              (range (count bv))))

    ;; 1-arg slot, callee carries extra (optional / captured) args —
    ;; match the slot's lone param to the callee arg of that name.
    ;; Typevar on slot side accepts (same rationale as above).
    (and (= 1 (count b)) (> (count a) 1))
    (let [[k bt] (first b)]
      (boolean (when-let [at (get a k)]
                 (or (type-var? bt) (subtype? bt at)))))

    ;; 1-arg slot, nullary callee — the callable ignores its input.
    (and (= 1 (count b)) (zero? (count a)))
    true

    :else false))


(defn- fn-subtype?
  "Function subtyping under graphden's hof-wrap semantics.

   Covariant return, effect-subset constraint, and contravariant
   arguments via `fn-args-subtype?` — which dispatches on the slot's
   arity to mirror hof-wrap's single-arg (positional) vs map-callable
   (by-name) conventions.

   - `sub-ret ⊆ sup-ret` (covariant return).
   - `sub-effects ⊆ sup-effects` (callable-side covariant effect
     constraint — bound fn must NOT exceed the slot's allowed
     effect set; `nil`/`:any` on the slot means \"unconstrained\").

   Typevar on the slot's return is lenient (same as map/list/fn-args
   sup-typevars): subtype? returns true. `check-binding!` separately
   runs `unify` after a successful subtype check whenever typevars
   are present, so the variable still BINDS to the callee's actual
   return — the lenient subtype is for the structural check, the
   binding flows through unify."
  [sub sup]
  (let [sub-ret (fn-ret sub) sup-ret (fn-ret sup)]
    (and (or (type-var? sup-ret) (subtype? sub-ret sup-ret))
         (effects-compatible? (fn-effects sub) (fn-effects sup))
         (fn-args-subtype? (fn-args sub) (fn-args sup)))))


(defn- normalise
  "Canonicalise a type before subtype/unify reasoning. Two passes:

   1. Storage-primitive ↔ structural-type rewrite. `:sequence` (the
      wire-level primitive) IS `[:list :any]` (the typed view of the
      same shape). Without this, a composed fn-def whose inherited
      slot stores `:sequence` can't unify against a base-fn slot
      annotated `[:list :any]`, even though they describe the same
      runtime value. Single canonical list shape post-#16 already
      enforces that mapping at storage write-time; this is the
      read-time equivalent.

   2. Alias resolution. Keyword aliases like `:nullable-int`,
      `:positive-int`, `:result-text` are sugar for structural
      types (`[:union :null :int]`, `[:refine :int [:> 0]]`,
      `[:union {:tag … :value …} {:tag … :value …}]`). Without this
      step, every `subtype?` / `unify` caller had to remember to
      pre-resolve via `resolve-alias` — most did, but the
      `/api/types/compatible` and `/api/types/candidates` endpoints
      did NOT, leaving `:nullable-int ⊆ :int`, `:positive-int ⊆ :int`,
      and (post-Phase 7) `:result-text` matching against a
      tag-pinned record all returning the wrong answer. Fold the
      resolution into `normalise` so the property is universal.

   `resolve-alias` recurses into compound types and is idempotent on
   already-structural inputs, so chaining the two passes is safe."
  [t]
  (let [t (cond
            (= t :sequence) [:list :any]
            ;; Canonicalise the legacy 3-element fn-type form to 4-element
            ;; with `:any` (unconstrained slot). Internal code can rely on
            ;; the 4th slot always being present.
            (and (vector? t) (= :fn (first t)) (= 3 (count t)))
            (conj t :any)
            :else t)]
    (resolve-alias t)))


;; Primitive subtype hierarchy. Doc-aligned:
;;   :int     ⊆ :numeric  ⊆ :jsonb  ⊆ :any
;;   :float   ⊆ :numeric
;;   :decimal ⊆ :numeric
;; (`:numeric` is the doc's wider numeric supertype — runtime
;; arbitrary-precision values, plus integers, floats and decimals.)
(def ^:private primitive-supers
  {:int     #{:numeric}
   :float   #{:numeric}
   :decimal #{:numeric}})


(defn- primitive-subtype?
  "Walk the primitive-supers map to see if `sub` is `sup` or any of
   its transitive supers. Used in `subtype?`'s primitive case."
  [sub sup]
  (or (= sub sup)
      (some #(primitive-subtype? % sup) (primitive-supers sub))))


(declare constraint-implies?)


(defn- atom-implies?
  "Decide whether the leaf constraint `a` implies `b` for the
   numeric-comparison shapes graphden actually uses
   (`[:>` / `[:>=` / `[:<` / `[:<=` / `[:=` / `[:not= …]`). Returns
   true iff every value satisfying `a` also satisfies `b`. nil for
   shapes outside the supported set so the caller falls back to
   structural equality."
  [a b]
  (when (and (vector? a) (vector? b))
    (let [[ak av] [(first a) (when (>= (count a) 2) (nth a 1))]
          [bk bv] [(first b) (when (>= (count b) 2) (nth b 1))]
          numeric? (and (number? av) (number? bv))]
      (cond
        ;; Identical leaves trivially imply each other.
        (= a b) true

        ;; Equality on the LHS is a singleton — easy to check against
        ;; any RHS comparison.
        (and (= ak :=) numeric?)
        (case bk
          := (= av bv)
          :>  (> av bv)
          :>= (>= av bv)
          :<  (< av bv)
          :<= (<= av bv)
          :not= (not= av bv)
          nil)

        (and numeric? (= ak bk))
        (case ak
          ;; Tightening lower bound: [:> a] ⊆ [:> b] iff a >= b.
          (:> :>=) (>= av bv)
          ;; Tightening upper bound symmetrically.
          (:< :<=) (<= av bv)
          nil)

        ;; Strict-vs-loose comparison crosses:
        ;; [:> a] ⊆ [:>= b] iff a >= b  (every x>a is also >=b when a>=b)
        ;; [:>= a] ⊆ [:> b] iff a > b   (need strictly above b's threshold)
        (and numeric? (= ak :>) (= bk :>=))   (>= av bv)
        (and numeric? (= ak :>=) (= bk :>))   (> av bv)
        (and numeric? (= ak :<) (= bk :<=))   (<= av bv)
        (and numeric? (= ak :<=) (= bk :<))   (< av bv)

        ;; Set-membership / equality reasoning — value-domain agnostic
        ;; (keywords, strings, …), not just numerics. `:in` operands
        ;; arrive as a set or a vector depending on the EDN author, so
        ;; normalise to a set before reasoning.
        ;; [:= x]  ⊆ [:in S]    iff  x ∈ S
        (and (= ak :=) (= bk :in) (coll? bv))
        (contains? (set bv) av)
        ;; [:in S1] ⊆ [:in S2]  iff  S1 ⊆ S2
        (and (= ak :in) (= bk :in) (coll? av) (coll? bv))
        (let [bs (set bv)] (every? #(contains? bs %) av))
        ;; [:in S]  ⊆ [:= x]    iff  S = #{x}  (singleton)
        (and (= ak :in) (= bk :=) (coll? av))
        (= (set av) #{bv})
        ;; [:= x]  ⊆ [:not= y]  iff  x ≠ y
        (and (= ak :=) (= bk :not=))
        (not= av bv)
        ;; NOTE on `[:matches P]` ⊆ `[:matches Q]`: different patterns
        ;; are NOT subtype-comparable (regex containment needs a
        ;; theorem prover); the identical-pattern case is already
        ;; caught by the `(= a b)` arm at the top, so no `:matches`
        ;; pair arm is needed here.
        ;; `[:matches P]` ⊆ `[:not= ""]` iff P cannot match the EMPTY
        ;; string — then every string matching P is non-empty. Testing
        ;; `(re-find P "")` is decidable and cheap; the previous heuristic
        ;; only checked the PATTERN string was non-empty (`(seq av)`),
        ;; which is unsound — `".*"` / `"\\s*"` / `"a?"` are non-empty
        ;; patterns that match `""`, so they would have been wrongly
        ;; accepted as `:non-empty-text`. Load-bearing case still holds:
        ;; `:url` (`^https?://`) doesn't match `""` → subtype stands. An
        ;; un-compilable pattern conservatively fails the subtype.
        (and (= ak :matches) (= bk :not=)
             (string? av) (= bv "")
             (try (nil? (re-find (re-pattern av) ""))
                  (catch Exception _ false)))
        true

        ;; [:in S]  ⊆ [:not= y]  iff  y ∉ S
        (and (= ak :in) (= bk :not=) (coll? av))
        (not (contains? (set av) bv))

        :else nil))))


(defn constraint-implies?
  "True iff every value satisfying refinement constraint `a` also
   satisfies `b`. Drives subtype reasoning across refinement types
   (`:positive-int ⊆ :non-negative-int`, `:user-port ⊆ :port`,
   `:probability ⊆ :percent`).

   Recurses through `[:and …]` (intersection — narrower than any
   branch) and `[:or …]` (union — wider than every branch). Falls
   back to structural equality for unknown shapes so legacy
   refinements with custom constraints behave as before."
  [a b]
  (cond
    ;; Trivial identity / open universal.
    (or (= a b) (= b [:any])) true
    (or (nil? a) (nil? b))    false

    ;; AND on RHS: a must imply EVERY component of b.
    (and (vector? b) (= :and (first b)))
    (every? #(constraint-implies? a %) (rest b))

    ;; OR on LHS: every branch of a must imply b (worst-case widens
    ;; LHS to its widest member).
    (and (vector? a) (= :or (first a)))
    (every? #(constraint-implies? % b) (rest a))

    ;; AND on LHS: any single component implying b is enough — the
    ;; intersection is at least as narrow as that component.
    (and (vector? a) (= :and (first a)))
    (boolean (some #(constraint-implies? % b) (rest a)))

    ;; OR on RHS: a must imply at least one branch.
    (and (vector? b) (= :or (first b)))
    (boolean (some #(constraint-implies? a %) (rest b)))

    :else
    (or (atom-implies? a b)
        ;; Unknown leaf shape — fall back to structural equality.
        false)))


(def ^:dynamic *type-compare-depth*
  "Recursion-depth guard shared by `subtype?` / `unify`. Neither has a
   structural fixpoint, so two DISTINCT mutually-recursive aliases with
   the same outer constructor — e.g. `:tree-a = {:kids [:list :tree-a]}`
   vs `:tree-b = {:kids [:list :tree-b]}` — recurse forever: `normalise`
   unfolds one alias level per call and the `(= sub sup)` short-circuit
   never fires because the names differ at every level. The cap turns
   that StackOverflow (which would crash a `bb rebuild` sync or 500 an
   `/api/types/compatible` call) into a clean fail-closed error. Real
   types nest a handful of levels; the cap is far above any legit depth."
  0)


(def ^:private max-type-compare-depth 512)


(defn- check-type-compare-depth!
  [op a b]
  (when (> (long *type-compare-depth*) max-type-compare-depth)
    (throw (ex-info (str op " recursion limit exceeded — cyclic or non-fixpoint "
                         "mutually-recursive type aliases")
                    {:type :types/recursion-limit :op op :a a :b b}))))


(defn subtype?
  "Is `sub` a subtype of `sup`? Reflexive and transitive; a PREORDER,
   not a partial order — antisymmetry holds only up to semantic
   equivalence (e.g. `[:refine B [:and c]]` and `[:refine B c]` are
   mutual subtypes while structurally distinct), so don't rely on
   `subtype?`-both-ways implying structural equality. Type variables
   compare by identity for now — unification handles the substitution
   case before subtype check is meaningful.

   Refinement rules (Phase 4):
   - `[:refine B c] ⊆ B`            (refinement is a subtype of base)
   - `B ⊄ [:refine B c]`             (no implicit narrowing — caller
                                       must insert `:validate-refinement`)
   - `[:refine B c1] ⊆ [:refine B c2]` iff `constraint-implies? c1 c2`
     — real implication reasoning over the numeric-comparison /
     `:in` / `:matches` atoms and `:and`/`:or` combinators (so
     `:positive-int ⊆ :non-negative-int`, `:user-port ⊆ :port`).
     Unknown constraint shapes fall back to structural equality;
     full SMT-style logic stays out of scope per TYPES.md.

   Union rules:
   - `[:union T1 T2 …] ⊆ S` iff every Tᵢ ⊆ S          (disjunction)
   - `T ⊆ [:union T1 T2 …]` iff T ⊆ Tᵢ for some i      (membership)

   Known deliberate hole: `:any` is the top type, so a marker-carrying
   type may first widen into an `:any`-shaped context
   (`[:list [:secret :text]] ⊆ [:list :any]`) and from there reach
   sinks the direct marker guards refuse — label tracking ends where
   `:any` begins. See SECRETS.md § Limitations."
  [sub sup]
  (check-type-compare-depth! "subtype?" sub sup)
  (binding [*type-compare-depth* (inc (long *type-compare-depth*))]
    (let [sub (normalise sub)
          sup (normalise sup)]
      (cond
        (or (= sub sup) (= sup :any))            true
        (= sub :any)                             false

        ;; Universal yes-arms:
        ;; - `typevar SUB ⊆ :jsonb` — at any binding site the var
        ;;   resolves to SOME type, and most concrete types subtype
        ;;   `:jsonb`. Without it, every fn-ref whose computed return
        ;;   is polymorphic (`[:union 'a <record>]` etc.) rejects
        ;;   against a `:jsonb`-typed slot.
        ;; - `:never ⊆ T` — `:never` is the bottom type, a subtype of
        ;;   every type; nothing but itself is a subtype of it.
        (or (and (type-var? sub) (= sup :jsonb))
            (= sub :never))                      true
        (= sup :never)                           false

        ;; `:empty-map` — sentinel from `classify-literal` for `{}`.
        ;; Vacuous-truth subtype of every HOMOGENEOUS map-shaped target
        ;; (`:jsonb` / `[:map K V]`): it carries no entries to violate
        ;; a per-entry constraint. Without it an `:_storage-where-map`-
        ;; typed slot bound to `{:value {}}` fails the
        ;; `:jsonb ⊄ [:map :keyword :any]` rule below.
        ;;
        ;; NOT a subtype of a record type: a record's fields are
        ;; REQUIRED (record-subtype? demands every sup field present),
        ;; and `{}` has none — `{} ⊆ {:name :text}` would promise a
        ;; `:text` at `(:name {})` = nil. The vacuous truth only holds
        ;; where there is no required structure.
        ;;
        ;; Unions: an `:empty-map` is a subtype of `[:union :null [:map K V]]`
        ;; because it's a subtype of the map-shape member. Descend so the
        ;; nullable-map shapes (common boundary type for read-or-nil sites
        ;; like `:resolve-branch-ref`) accept empty-map literals too.
        ;; Marker sups descend into the inner type (auto-promote on
        ;; entry, same as the general marker rule below) so e.g.
        ;; `:empty-map ⊆ [:secret :jsonb]` — restores transitivity
        ;; through `record ⊆ [:secret :jsonb]` chains.
        (= sub :empty-map)
        (boolean (or (= sup :empty-map) (= sup :jsonb)
                     (map-type? sup)
                     (and (marker-type? sup)
                          (subtype? :empty-map (marker-inner sup)))
                     (and (union-type? sup)
                          (some #(subtype? :empty-map %) (union-members sup)))))
        (= sup :empty-map)                       false

        ;; Union LHS: every member must subtype.
        (union-type? sub)
        (every? #(subtype? % sup) (union-members sub))

        ;; Union RHS: at least one member accepts.
        (union-type? sup)
        (boolean (some #(subtype? sub %) (union-members sup)))

        (or (and (primitive? sub) (primitive? sup)
                 (primitive-subtype? sub sup))
            (and (= sup :jsonb)
                 ;; A compound carrying ANY nested marker must NOT flow
                 ;; into a jsonb content sink — that would strip the
                 ;; information-flow label. The top-level `[<tag> T]`
                 ;; case is already refused by the marker arm below; this
                 ;; makes the type core self-defend against the nested
                 ;; case (a record/list/tuple field) instead of relying on
                 ;; every propagator keeping markers top-level-typed.
                 (not (contains-marker? sub))
                 (or (primitive? sub)
                     (record-type? sub)
                     (list-type? sub)
                     (map-type? sub)
                     (tuple-type? sub)
                     (refine-type? sub)))) true
        (= sub :jsonb)                           false

        ;; Refinement: SUB carries a refinement, SUP is its base or any —
        ;; pass; SUP carries a refinement → constraint inclusion.
        ;;
        ;; Constraint inclusion (`constraint-implies?`) covers the
        ;; common numeric-comparison shapes plus `:and` / `:or`
        ;; combinators, so e.g. `:positive-int ⊆ :non-negative-int`
        ;; (`[:> 0] ⊆ [:>= 0]`) and `:user-port ⊆ :port` (one nested
        ;; `:and` inside another) hold. Falls back to structural
        ;; equality for shapes outside the supported set, preserving
        ;; the prior behaviour for refinements with custom constraints.
        (refine-type? sub)
        (cond
          (refine-type? sup)
          (and (constraint-implies? (refine-constraint sub)
                                    (refine-constraint sup))
               (subtype? (refine-base sub) (refine-base sup)))
          :else
          (subtype? (refine-base sub) sup))

        (refine-type? sup)
        ;; SUB is not a refinement, SUP is — must NOT widen.
        false

        ;; Markers: registry-driven information-flow labels — asymmetric
        ;; subtyping, generic over every registered tag (`:secret` is the
        ;; seeded instance; SECRETS.md):
        ;;
        ;; `[<m> T] ⊆ [<m> T']`  iff `T ⊆ T'` (covariant inside, SAME tag —
        ;;                                     `[:pii T] ⊄ [:secret T']`)
        ;; `[<m> T] ⊆ T'`        NEVER        (can't STRIP the label)
        ;; `T ⊆ [<m> T']`        iff `T ⊆ T'` (auto-PROMOTE on entry —
        ;;                                     monotone direction; plain
        ;;                                     values become labelled when
        ;;                                     they flow into marked slots)
        ;;
        ;; Same shape rationale as `refine` above — the marker is opaque
        ;; to the base type, but unlike `refine` the asymmetry is on
        ;; LEAVING the marker: refine narrows (sub ⊆ base; strip allowed),
        ;; a marker labels (sub ⊄ base; strip forbidden). That's why we
        ;; don't reuse `refine` — the subtype direction is the whole point
        ;; of the abstraction.
        (marker-type? sub)
        (cond
          (and (marker-type? sup)
               (= (marker-tag sub) (marker-tag sup)))
          (subtype? (marker-inner sub) (marker-inner sup))
          ;; nested-label case: `[:pii [:secret T]] ⊆ [:secret T']` —
          ;; peeling the OUTER pii label to satisfy a secret slot would
          ;; strip it; refuse (the caller wraps the slot type instead).
          :else false)

        (marker-type? sup)
        ;; Promotion: a plain value into a marked slot is fine; the value
        ;; gets labelled on entry. This is what lets ordinary string fns
        ;; declare `[:secret :text]` slots and still accept both kinds of
        ;; input.
        (subtype? sub (marker-inner sup))

        (and (record-type? sub) (record-type? sup))
        (record-subtype? sub sup)
        (and (list-type? sub) (list-type? sup))  (list-subtype? sub sup)
        (and (map-type? sub) (map-type? sup))    (map-subtype? sub sup)
        (and (tuple-type? sub) (tuple-type? sup)) (tuple-subtype? sub sup)
        ;; A fixed-length tuple IS a list at runtime (both are
        ;; `:sequence`-shaped on the wire) — `[:tuple :int :int]` is a
        ;; valid `[:list :int]` value when every position fits the
        ;; element type. Typevar on the sup elem accepts (passive
        ;; recursion context, same rationale as `list-subtype?`). The
        ;; reverse direction stays false: a list has no length
        ;; guarantee to offer a tuple.
        (and (tuple-type? sub) (list-type? sup))
        (or (type-var? (list-elem sup))
            (every? #(subtype? % (list-elem sup)) (tuple-elems sub)))
        ;; A concrete keyword-keyed record IS a valid homogeneous-map
        ;; value when the map's key type admits keywords and every field
        ;; value fits the map's value type — lets a literal map (which
        ;; classifies as a record) bind into a `[:map …]`-typed slot.
        ;;
        ;; Typevar slots in `(map-key sup)` / `(map-val sup)` accept
        ;; unconditionally: a fresh typevar at a parametric slot like
        ;; `:merge`'s `:maps [:list [:map a :any]]` represents
        ;; "anything the call-site picks for `a`"; the record's
        ;; concrete `:keyword` key (or each concrete field type) is a
        ;; satisfying assignment. Without this, every record-bound-to-
        ;; HOF-typed-map-slot site rejected during the sweep — e.g.
        ;; `_value-form-root-attrs`'s `{:data-binding-id :text}`
        ;; against `:merge :maps` declared `[:list [:map a :any]]`.
        (and (record-type? sub) (map-type? sup))
        (let [k-ok? (or (type-var? (map-key sup))
                        (subtype? :keyword (map-key sup)))
              v-ok? (or (type-var? (map-val sup))
                        (every? #(subtype? % (map-val sup)) (vals sub)))]
          (and k-ok? v-ok?))
        (and (fn-type? sub) (fn-type? sup))      (fn-subtype? sub sup)
        :else                                    false))))


;; -----------------------------------------------------------------------------
;; Unification — Robinson with occurs check

(declare unify)


(defn- bind-var
  [v t subst]
  (cond
    (= v t) subst

    ;; Occurs in a UNION — `v` ↔ `[:union T … v …]` is the common
    ;; let-poly "I might return `v` or something derived from `v`"
    ;; pattern. The strict occurs check would reject it as an
    ;; infinite type; the looser-and-correct interpretation is to
    ;; drop `v` from the union (the recursive branch is just `v`
    ;; itself, a tautological subtype of any binding we pick) and
    ;; unify against the remainder. Common shape:
    ;; `:keywordize-map-keys`'s `:f` callable returns `[:union 'a
    ;; 'b]` against `:postwalk`'s `:f [:fn {:value v} v]` slot —
    ;; without the strip, the arg-side `v := b` binding makes the
    ;; return-side `b ↔ [:union 'a 'b]` trip occurs.
    (and (union-type? t) (contains? (set (union-members t)) v))
    (let [rest-members (remove #{v} (union-members t))]
      (cond
        (empty? rest-members)    subst
        (= 1 (count rest-members)) (unify v (first rest-members) subst)
        :else                    (unify v (make-union rest-members) subst)))

    (occurs? v t subst) ::fail
    :else (assoc subst v t)))


(defn- unify-fn
  "Unify two function types positionally — argument NAMES are
   documentation only, what matters at the type level is arity and
   per-position type. Args are zipped after sorting by key so that
   the order is deterministic regardless of the underlying map
   implementation. Cardinality must match.

   Why positional, not name-based: a fn-typed slot like
   `[:fn {:item a} b]` should accept any one-arg callable returning
   `b` over any `a`, regardless of how the callable named its own
   parameter (`:string`, `:x`, `:input`, …). HOF binding sites and
   their referenced fn-graphs rarely agree on names, but they must
   agree on shape.

   When BOTH sides carry effect constraints, the bound fn's effects
   must be a subset of the slot's allowed set. Either side
   nil/`:any` skips the check. Mirrors `fn-subtype?`'s
   `effects-compatible?` rule.

   WARNING — the effect check is DIRECTION-SENSITIVE inside an
   otherwise symmetric unifier: it assumes the caller convention
   `(unify expected actual)` (slot first). Swapping operands silently
   skips the constraint. Direct call sites keep the order; if you add
   a code path that can reach `unify-fn` with swapped fn-types (e.g. a
   new union-fallback arm), preserve the convention."
  [a b subst]
  (let [a-args (fn-args a)
        b-args (fn-args b)]
    (if (not= (count a-args) (count b-args))
      ::fail
      (let [a-types (mapv val (sort-by key a-args))
            b-types (mapv val (sort-by key b-args))
            arg-step (reduce (fn [s i]
                               (if (= s ::fail)
                                 (reduced ::fail)
                                 (unify (get a-types i) (get b-types i) s)))
                             subst
                             (range (count a-types)))
            ret-step (when-not (= arg-step ::fail)
                       (unify (fn-ret a) (fn-ret b) arg-step))
            ;; Effects: caller convention is `(unify expected
            ;; actual subst)` so `a` is the slot's expected type
            ;; (sup) and `b` is the bound fn's actual type (sub).
            ;; The slot constraint must hold one-way: actual's
            ;; effects ⊆ expected's allowed effects.
            effects-ok? (effects-compatible? (fn-effects b) (fn-effects a))]
        (if (or (= arg-step ::fail)
                (= ret-step ::fail)
                (not effects-ok?))
          ::fail
          ret-step)))))


(defn- unify-record
  "Open-record unification — unify the SHARED keys, with extra keys
   on EITHER side allowed (mirrors `record-subtype?`'s open
   semantics: `{a A b B c C}` is a valid subtype of `{a A b B}`, and
   vice-versa under the sub-direction). If the shared-keys subset
   is empty, fail (no information to unify against).

   Without the open form, a `{:status :int :body :text}`-typed slot
   couldn't unify against a `{:status :int :body :text :headers …}`
   binding — even though `subtype?` accepts it. The asymmetry
   (`subtype?` open, `unify-record` closed) silently rejected
   legitimate bindings where the record fields needed any actual
   typevar binding to happen via unify."
  [a b subst]
  (let [a-keys (set (keys a))
        b-keys (set (keys b))
        shared (clojure.set/intersection a-keys b-keys)]
    (if (empty? shared)
      ::fail
      (reduce (fn [s k]
                (if (= s ::fail)
                  (reduced ::fail)
                  (unify (get a k) (get b k) s)))
              subst
              shared))))


(defn- unify-map-record
  "Unify a homogeneous-map type `[:map K V]` with a concrete
   keyword-keyed record. Mirrors the `subtype?` rule
   `record ⊆ [:map :keyword V]`: the record's keyword keys fix
   `K := :keyword`, every field value unifies against `V`.

   Without this, a `[:map k v]`-typed slot (`:keys` / `:vals` /
   `:zipmap`'s return) would reject a record argument even though
   `subtype?` accepts it — the same asymmetry the record ↔ `:jsonb`
   arm below already closes for the untyped case."
  [map-t rec subst]
  (let [k-step (unify (map-key map-t) :keyword subst)]
    (if (= k-step ::fail)
      ::fail
      (reduce (fn [s v]
                (if (= s ::fail)
                  (reduced ::fail)
                  (unify (map-val map-t) v s)))
              k-step
              (vals rec)))))


(defn- ^:no-doc union-rest-and-tv
  "Helper for the union-fallback chain: project both sides to their
   member sets, compute the common intersection, and report the
   leftovers (`a-rest` / `b-rest`) plus their typevar subsets
   (`a-rest-tvs` / `b-rest-tvs`)."
  [a b]
  (let [a-mems     (if (union-type? a) (set (union-members a)) #{a})
        b-mems     (if (union-type? b) (set (union-members b)) #{b})
        common     (clojure.set/intersection a-mems b-mems)
        a-rest     (vec (clojure.set/difference a-mems common))
        b-rest     (vec (clojure.set/difference b-mems common))
        a-rest-tvs (filter type-var? a-rest)
        b-rest-tvs (filter type-var? b-rest)]
    {:common common :a-rest a-rest :b-rest b-rest
     :a-rest-tvs a-rest-tvs :b-rest-tvs b-rest-tvs}))


(defn- try-unify-unions
  "Unify two types when one or both sides is a union. HM unifier
   doesn't naturally pick a branch (multiple valid choices), so this
   runs a documented fallback chain and returns the first successful
   substitution — or `::fail` if every arm fails:

   1. **Subtype-direction success** — `:null ⊆ [:union :null T]` etc.;
      matches the lenient `:any` handling in `unify`.
   2. **Common-member strip + single-leftover unify** —
      `[:union :null 'a]` vs `[:union :null :text]` strips the shared
      `:null` and unifies `'a` with `:text`.
   3. **Concrete vs union-with-single-typevar** — pick the typevar arm
      and bind. Common at `:coalesce` and other nullable-default sites.
   4. **LHS-rest single typevar, RHS-rest concrete** — bind the typevar
      to the full leftover RHS (preserves slot constraints across
      polymorphic chains like `:first` ↦ `[:union :null a]`).
   5. **Mirror of (4)** with sides swapped."
  [a b subst]
  (or
    (when (or (subtype? a b) (subtype? b a)) subst)
    (let [{:keys [common a-rest b-rest]} (union-rest-and-tv a b)]
      (when (and (seq common) (= 1 (count a-rest)) (= 1 (count b-rest)))
        (let [s (unify (first a-rest) (first b-rest) subst)]
          (when-not (= s ::fail) s))))
    (let [concrete   (if (union-type? a) b a)
          union-side (if (union-type? a) a b)
          tv-members (filter type-var? (union-members union-side))]
      (when (and (not (union-type? concrete))
                 (= 1 (count tv-members)))
        (let [s (unify concrete (first tv-members) subst)]
          (when-not (= s ::fail) s))))
    (let [{:keys [common a-rest b-rest a-rest-tvs b-rest-tvs]}
          (union-rest-and-tv a b)]
      (when (and (seq common)
                 (= 1 (count a-rest)) (= 1 (count a-rest-tvs))
                 (seq b-rest) (empty? b-rest-tvs))
        (let [s (unify (first a-rest-tvs) (make-union b-rest) subst)]
          (when-not (= s ::fail) s))))
    (let [{:keys [common a-rest b-rest a-rest-tvs b-rest-tvs]}
          (union-rest-and-tv a b)]
      (when (and (seq common)
                 (= 1 (count b-rest)) (= 1 (count b-rest-tvs))
                 (seq a-rest) (empty? a-rest-tvs))
        (let [s (unify (first b-rest-tvs) (make-union a-rest) subst)]
          (when-not (= s ::fail) s))))
    ::fail))


(defn unify
  "Unify two types. Returns an updated substitution, or `::fail` if
   they cannot be made equal. Substitution is a `{type-var type}` map.
   3-arg form takes a starting substitution; 2-arg form starts empty.

   Subtype-aware leniency: when EITHER side resolves to `:any`, the
   unification succeeds without further constraint. Pure HM unify
   would reject `:any` ↔ `:int` even though `:int ⊆ :any` makes them
   compatible in the subtyping sense; rejecting would force every
   polymorphic slot to pick one concrete type and stick to it across
   all uses, which is too strict when the user has deliberately
   declared a slot as `:any` (the doc's escape hatch)."
  ([t1 t2] (unify t1 t2 {}))
  ([t1 t2 subst]
   (check-type-compare-depth! "unify" t1 t2)
   (binding [*type-compare-depth* (inc (long *type-compare-depth*))]
     (let [a (normalise (resolve subst t1))
           b (normalise (resolve subst t2))]
       (cond
         ;; Equality OR universal `:any` accept on the OTHER side when
         ;; neither is a type-var. With a type-var on the other side we
         ;; deliberately fall through to the typevar arms below so the
         ;; var BINDS to `:any` rather than silently passing without a
         ;; binding. Without the binding the var leaks downstream
         ;; (e.g. `:try`'s declared `[:union a b]` retains free `a`/`b`
         ;; when the body returns the type-checker's `:any` fallback,
         ;; which then fails every consumer expecting a concrete
         ;; member shape).
         (or (= a b)
             (and (or (= a :any) (= b :any))
                  (not (or (type-var? a) (type-var? b))))) subst
         ;; A type variable binds to whatever the other side is — a
         ;; primitive, a union, a fn-type, anything (subject to the
         ;; occurs-check in `bind-var`). This MUST precede the union
         ;; branch below: `unify('a, [:union …])` would otherwise be
         ;; captured by the union case, which only runs a subtype probe
         ;; — and a free type-var is a subtype of nothing — so it would
         ;; fail instead of binding the var to the union.
         (type-var? a)        (bind-var a b subst)
         (type-var? b)        (bind-var b a subst)
         ;; `:never` (bottom) unifies with anything — it is a subtype of
         ;; every type, so a divergent `:throw` branch fits any context.
         ;; Placed AFTER the type-var arms so a var still BINDS to
         ;; `:never` (letting `make-union` later absorb it) rather than
         ;; merely succeeding without a binding.
         (or (= a :never) (= b :never)) subst
         ;; Unions: HM unifier doesn't naturally pick a branch (multiple
         ;; valid choices), so `try-unify-unions` runs the documented
         ;; fallback chain (subtype-direction success, common-member
         ;; strip, single-typevar arm-pick, single-typevar absorption).
         ;; Reduces this `cond` arm to the dispatch only — the chain
         ;; itself reads as a small list of helpers.
         (or (union-type? a) (union-type? b))
         (try-unify-unions a b subst)
         ;; Subtype-aware unification — succeeds without further binding
         ;; when one of the relations holds:
         ;;
         ;; - Primitive subtype within the numeric hierarchy
         ;;   (`:int ↔ :numeric` is fine because `:int ⊆ :numeric`).
         ;; - Records / lists / refinements ↔ `:jsonb`. They ARE
         ;;   jsonb-shaped on the wire (matches the subtype rule
         ;;   `record ⊆ :jsonb`). A slot whose declared type narrowed
         ;;   from a type-var to `:jsonb` (because earlier in the chain
         ;;   it bound to a jsonb-typed value) needs to unify against
         ;;   a more-precisely-typed record at a later call site.
         ;;
         ;; The pinned type-var keeps its first binding; subsequent
         ;; compatible refinements flow through unchanged.
         (or (and (primitive? a) (primitive? b)
                  (or (primitive-subtype? a b) (primitive-subtype? b a)))
             ;; Marker guard mirrors `subtype?`'s jsonb arm: a compound
             ;; carrying a nested information-flow label must not be
             ;; declared jsonb-compatible here either. Without it,
             ;; `unify(:jsonb, [:list [:secret :text]])` succeeded
             ;; where `subtype?` deliberately refuses both directions —
             ;; drift between the two relations.
             (and (= a :jsonb)
                  (not (contains-marker? b))
                  (or (record-type? b) (list-type? b) (map-type? b)
                      (tuple-type? b) (refine-type? b)))
             (and (= b :jsonb)
                  (not (contains-marker? a))
                  (or (record-type? a) (list-type? a) (map-type? a)
                      (tuple-type? a) (refine-type? a)))
             ;; Refinement ↔ (primitive OR refinement) — defer to
             ;; `subtype?`, which already understands
             ;; `[:refine B c] ⊆ B` and constraint-implies between
             ;; refinement constraints. Without this, unifying a
             ;; record field declared `:int` against a record field
             ;; computed as `:non-negative-int` (or vice-versa) falls
             ;; into the `::fail` arm, even though the relation holds.
             (and (or (refine-type? a) (refine-type? b))
                  (or (subtype? a b) (subtype? b a)))
             ;; `:empty-map` is the bottom of HOMOGENEOUS map shapes —
             ;; vacuous-truth subtype of `:jsonb` / `[:map K V]` (the
             ;; same rule `subtype?` grants). Without this branch a
             ;; `{:value {}}` binding to `:coalesce :default a` after
             ;; `:value` resolved `a := :jsonb` falls into the `:else
             ;; ::fail` arm — the subtype-aware leniency for `:jsonb`
             ;; above only fires for record/list/map/tuple/refine
             ;; literal classifications, not the `:empty-map` sentinel.
             ;; NOT compatible with record types — their fields are
             ;; required and `{}` supplies none (mirrors `subtype?`).
             (and (= a :empty-map) (or (= b :jsonb) (map-type? b)))
             (and (= b :empty-map) (or (= a :jsonb) (map-type? a))))
         subst
         (and (fn-type? a) (fn-type? b))         (unify-fn a b subst)
         (and (list-type? a) (list-type? b))     (unify (list-elem a) (list-elem b) subst)
         ;; List-expected × tuple-actual ONLY — mirrors the subtype
         ;; rule `[:tuple …] ⊆ [:list E]`: every tuple position
         ;; unifies against the list elem, so a `[:list a]` slot
         ;; bound to a tuple-returning ref BINDS `a` instead of
         ;; silently keeping it free. The REVERSE direction (tuple
         ;; expected, list actual) is deliberately absent: a list
         ;; carries no length guarantee, `subtype?` refuses it, and a
         ;; unify arm here would fire exactly on check-binding!'s
         ;; after-subtype-FAILED fallback — admitting `[:list :int]`
         ;; into a `[:tuple a a]` slot with no length check.
         (and (list-type? a) (tuple-type? b))
         (reduce (fn [s x]
                   (if (= s ::fail) (reduced ::fail)
                       (unify (list-elem a) x s)))
                 subst (tuple-elems b))
         (and (map-type? a) (map-type? b))
         (let [s (unify (map-key a) (map-key b) subst)]
           (if (= s ::fail) ::fail (unify (map-val a) (map-val b) s)))
         (and (tuple-type? a) (tuple-type? b))
         (let [ea (tuple-elems a) eb (tuple-elems b)]
           (if (= (count ea) (count eb))
             (reduce (fn [s [x y]]
                       (if (= s ::fail) ::fail (unify x y s)))
                     subst (map vector ea eb))
             ::fail))
         (and (record-type? a) (record-type? b)) (unify-record a b subst)
         ;; A keyword-keyed record unifies with `[:map K V]` — same
         ;; relation `subtype?` already grants (`record ⊆ [:map …]`).
         (and (map-type? a) (record-type? b))    (unify-map-record a b subst)
         (and (record-type? a) (map-type? b))    (unify-map-record b a subst)
         :else                ::fail)))))


(defn unified?
  [r]
  (not= r ::fail))


(defn fail?
  [r]
  (= r ::fail))


;; -----------------------------------------------------------------------------
;; Type-var freshening — let-polymorphism support
;;
;; When a fn-def lifts free-args from multiple refs into its own
;; surface, every ref carries its own scope of type-vars. Naïvely
;; merging would conflate ALL `'a`s into one variable — wrong, since
;; each ref instantiates `'a` independently at call time. The fix:
;; per ref, walk its types and rename each `'a` to a unique
;; `'a-<n>` BEFORE merging.

(defonce ^:private fresh-counter (atom 0))


(defn- next-fresh-suffix
  "Monotonic integer for gensym-style type-var naming."
  []
  (swap! fresh-counter inc))


(declare freshen)


(defn- freshen*
  "Walk `t`, renaming each type-var via the `subst` atom (filled
   on demand). Recurse into compound shapes."
  [t subst]
  (cond
    (type-var? t)
    (or (get @subst t)
        ;; Strip any prior suffix (`a-1` → `a`) before appending the
        ;; new counter so deep transitive lifts produce `a-N`, not
        ;; `a-1-2-3`. The base name stays traceable; equality between
        ;; freshened vars still relies on the unique counter.
        (let [n (name t)
              dash (String/.indexOf ^String n "-")
              base (if (neg? dash) n (subs n 0 dash))
              fresh (symbol (str base "-" (next-fresh-suffix)))]
          (swap! subst assoc t fresh)
          fresh))
    (fn-type? t)     [:fn
                      (into {} (map (fn [[k v]] [k (freshen* v subst)])) (fn-args t))
                      (freshen* (fn-ret t) subst)]
    (list-type? t)   [:list (freshen* (list-elem t) subst)]
    (map-type? t)    [:map (freshen* (map-key t) subst) (freshen* (map-val t) subst)]
    (tuple-type? t)  (into [:tuple] (map #(freshen* % subst)) (tuple-elems t))
    (record-type? t) (into {} (map (fn [[k v]] [k (freshen* v subst)])) t)
    (refine-type? t) [:refine (freshen* (refine-base t) subst)
                      (refine-constraint t)]
    (marker-type? t) [(marker-tag t) (freshen* (marker-inner t) subst)]
    (union-type? t)  (make-union (mapv #(freshen* % subst) (union-members t)))
    :else            t))


(defn freshen-args
  "Rename every type-var across the given `args-map` ({arg-name type})
   with a SINGLE shared substitution scope — so `'a` appearing in
   multiple slot types within the SAME ref stays linked, but two
   different calls to `freshen-args` produce DIFFERENT vars (the
   essential let-polymorphism move)."
  [args-map]
  (let [subst (atom {})]
    (into {} (map (fn [[k v]] [k (freshen* v subst)])) args-map)))


(defn freshen
  "Freshen the type-vars of a SINGLE type. Standard let-polymorphism
   move for a value-bound ref's return-type: each use site gets its
   own scope of `'a`/`'b` so the caller's free typevars can't
   accidentally collide with the callee's.

   NOTE: `freshen*` rebuilds fn-types as 3-element forms — the 4th
   (effects) element is dropped at EVERY nesting level (a deliberate
   historical contract other call sites rely on). Callers that need
   effects preserved use `freshen-with-effects` below."
  [t]
  (freshen* t (atom {})))


(defn- restore-fn-effects
  "Walk `fresh` alongside the ORIGINAL `t`, re-attaching each fn-type's
   effects element that `freshen*` dropped — at every nesting level.
   The two trees are structurally parallel for every shape except
   unions (freshen* renames vars only), so keyed/positional descent
   is safe there."
  [t fresh]
  (cond
    (and (fn-type? t) (fn-type? fresh))
    (let [args (into {}
                     (map (fn [[k v]]
                            [k (restore-fn-effects (get (fn-args t) k) v)]))
                     (fn-args fresh))
          ret (restore-fn-effects (fn-ret t) (fn-ret fresh))
          eff (fn-effects t)]
      (if (and eff (not= eff :any))
        [:fn args ret eff]
        [:fn args ret]))
    (and (list-type? t) (list-type? fresh))
    [:list (restore-fn-effects (list-elem t) (list-elem fresh))]
    (and (map-type? t) (map-type? fresh))
    [:map (restore-fn-effects (map-key t) (map-key fresh))
     (restore-fn-effects (map-val t) (map-val fresh))]
    (and (tuple-type? t) (tuple-type? fresh))
    (into [:tuple] (map restore-fn-effects (tuple-elems t) (tuple-elems fresh)))
    (and (record-type? t) (record-type? fresh))
    (into {} (map (fn [[k v]] [k (restore-fn-effects (get t k) v)])) fresh)
    (and (refine-type? t) (refine-type? fresh))
    [:refine (restore-fn-effects (refine-base t) (refine-base fresh))
     (refine-constraint fresh)]
    (and (marker-type? t) (marker-type? fresh))
    [(marker-tag fresh) (restore-fn-effects (marker-inner t) (marker-inner fresh))]
    ;; Unions: freshen* runs make-union which may reorder/merge
    ;; members — positional descent is unsafe; effects inside union
    ;; members stay dropped (conservative; a union rarely sits in
    ;; slot-constraint position).
    :else fresh))


(defn freshen-with-effects
  "`freshen`, but fn-type effects elements survive at EVERY nesting
   level — for the checker's assembled-callee path, where a nested
   fn-typed arg's concrete effect set participates in the
   slot-effect-constraint check (a dropped set normalises to `:any`
   on the sub side, which `effects-compatible?` reads as cannot-
   satisfy — flipping would-pass into fail)."
  [t]
  (restore-fn-effects t (freshen t)))
