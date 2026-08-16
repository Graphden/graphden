(ns graphden.types.check.narrowing
  "Phase α' + Phase #170 — control-flow narrowing for the sync-time
   type-checker.

   Two passes both keyed off `:if` / `:cond` shapes:

   - **Phase α' caller-context narrowings** (`build-caller-narrowings`
     + `check-fn-def-with-narrowings!`): when a fn-def F binds an
     arg-slot via a fn-ref whose return is narrower than the slot's
     declared type, downstream rename-host fn-defs reachable through
     F's ref-tree see that narrowed type instead of the slot's
     declared one. Built as `{rename-host-name → {as-name →
     narrowed-type}}`; bound around `check-fn-def!` via
     `*caller-narrowings*`.

   - **Phase #170 control-flow ref-return overrides**
     (`build-ref-return-overrides`): when F's parent is `:if` and
     `:test`'s impl-chain root is `:some?` / `:nil?` / `:is-a?` on a
     target T, the taken branch sees T's return narrowed (null-stripped
     or structural-tag projection). Same idea for `:cond` with
     per-clause accumulation. Built as `{fn-name → {target-fn-name →
     narrowed-type}}`; bound via `*ref-return-overrides*`.

   `check.clj` owns the dynamic vars + `check-fn-def!` that this
   namespace `binding`s around — one-way dep, no cycle.

   See `docs/TYPE_SYSTEM_DECISIONS.md` for why broader path-
   sensitive analysis (composed guards / row polymorphism) is
   deferred."
  (:require
    [graphden.executor.registry.core :as registry]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


;; =============================================================================
;; Phase α' — caller-context narrowings for rename-host fn-defs.
;; =============================================================================

(defn- ref-binding-name
  "Extract a fn-ref from an `:args` value: bare keyword or `{:ref X}`.
   Inline anons are expanded to synthetic names before either pass
   runs, so those two shapes are all we ever see. Shared by BOTH
   passes (α' ref-tree building and #170 branch extraction)."
  [b]
  (cond
    (keyword? b) b
    (and (map? b) (contains? b :ref) (keyword? (:ref b))) (:ref b)
    :else nil))


(defn- rename-only-binding?
  "True when an `:args` value is a pure `{:as :name}` rename — keeps
   the slot free under a new name, binds no value/ref. The α' pass
   needs this twice (collect AS-names; detect the rebind-stop) — one
   predicate so the two sites can't drift."
  [b]
  (and (map? b)
       (contains? b :as)
       (not (contains? b :value))
       (not (contains? b :ref))))


(defn- ref-children-of
  [fd known-names]
  (let [from-binding (fn [b]
                       (cond
                         (ref-binding-name b) [(ref-binding-name b)]
                         (vector? b) (keep (fn [item]
                                             (cond
                                               (keyword? item) item
                                               (and (map? item) (contains? item :ref))
                                               (:ref item)
                                               :else nil))
                                           b)
                         :else nil))]
    (into #{}
          (comp (mapcat (fn [[_ b]] (from-binding b)))
                (filter known-names))
          (:args fd))))


(defn- rename-as-names-in
  "Set of AS-names `fd` introduces as rename-bindings in its own
   args (NOT inherited)."
  [fd]
  (into #{}
        (keep (fn [[_ b]] (when (rename-only-binding? b) (:as b))))
        (or (:args fd) {})))


(defn- propagate-narrowing-to-rename-hosts
  "DFS from `from-name`'s children EXCLUDING `exclude-ref` (the
   binder's own ref-target — produces the narrowed value, doesn't
   consume the free arg). At each visited callee whose own args
   have a rename `{:as arg-name}`, record the narrowing. Continue
   walking into ref-children regardless (deeper rename-hosts may
   need narrowing too). Callees that rebind `arg-name` via real
   value/ref stop THIS branch."
  [ref-children-by-name fn-defs-by-name from-name exclude-ref
   arg-name narrowed acc]
  (let [seed (vec (disj (set (get ref-children-by-name from-name #{}))
                        exclude-ref))]
    (loop [queue seed
           visited (conj #{from-name} exclude-ref)
           acc acc]
      (if (empty? queue)
        acc
        (let [callee (peek queue)
              queue (pop queue)]
          (if (contains? visited callee)
            (recur queue visited acc)
            (let [visited' (conj visited callee)
                  callee-fd (get fn-defs-by-name callee)
                  callee-args (or (:args callee-fd) {})
                  under-key (get callee-args arg-name)
                  rebinds? (not (or (nil? under-key)
                                    (rename-only-binding? under-key)))]
              (if rebinds?
                (recur queue visited' acc)
                (let [as-names (rename-as-names-in callee-fd)
                      acc' (if (contains? as-names arg-name)
                             (update-in acc [callee arg-name]
                                        (fn [existing]
                                          (if (some? existing)
                                            (types/make-union [existing narrowed])
                                            narrowed)))
                             acc)
                      queue' (into queue (get ref-children-by-name callee []))]
                  (recur queue' visited' acc'))))))))))


(defn build-caller-narrowings
  "Pass 2 — narrowings for rename-host fn-defs. Returns
   `{rename-host-name → {as-name → narrowed-type}}`.

   PRECONDITION: call AFTER the full per-fn-def check sweep
   (`packages/sync`'s fault-tolerant check loop in production;
   `check-all-defs!` in tests) — it reads each ref's `:return` via
   `registry/rich-type-of`, so an under-populated registry silently
   produces no narrowings."
  [fn-defs]
  (let [fn-defs-by-name (into {} (map (fn [fd] [(:name fd) fd])) fn-defs)
        known-names     (set (keys fn-defs-by-name))
        ref-children    (into {}
                              (map (fn [[n fd]]
                                     [n (ref-children-of fd known-names)]))
                              fn-defs-by-name)]
    (reduce
      (fn [acc fd]
        (let [F-name (:name fd)
              ;; ALL parents' slots, not just the primary's — the
              ;; checker merges the full MI parent list
              ;; (`merge-mi-parent-infos`), and this exclusion must
              ;; match it: a binding to a SECONDARY parent's contract
              ;; slot is parent-contract fulfilment, not a lifted free
              ;; arg, and must not seed rename-host propagation.
              parent-slots (into #{}
                                 (comp (keep registry/rich-type-of)
                                       (mapcat (fn [info] (keys (:args info)))))
                                 (or (some-> fd :parents seq)
                                     (some-> fd :parent vector)))]
          (reduce
            (fn [acc [arg-name b]]
              (let [ref-name (ref-binding-name b)]
                (cond
                  (contains? parent-slots arg-name) acc

                  (and ref-name (contains? known-names ref-name))
                  (let [ref-info (registry/rich-type-of ref-name)
                        narrowed (some-> ref-info :return)]
                    (if (or (nil? narrowed) (= :any narrowed))
                      acc
                      (propagate-narrowing-to-rename-hosts
                        ref-children fn-defs-by-name
                        F-name ref-name arg-name narrowed acc)))

                  :else acc)))
            acc
            (:args fd))))
      {}
      fn-defs)))


(defn check-fn-def-with-narrowings!
  "Phase α' Pass 3."
  ([fd narrowings-map]
   (check-fn-def-with-narrowings! fd narrowings-map nil))
  ([fd narrowings-map overrides-map]
   (binding [check/*caller-narrowings*    (get narrowings-map (:name fd))
             check/*ref-return-overrides* (get overrides-map (:name fd))]
     (check/check-fn-def! fd))))


;; =============================================================================
;; Phase #170 — control-flow narrowing through `:if` / `:cond` guards.
;;
;; When F's parent is `:if` AND :test's impl-chain root is `:some?` /
;; `:nil?` on a target fn-name T, the taken branch knows T is non-null
;; (or null, for the other branch). Same idea for `:cond`: a result
;; clause runs only when its own test is truthy AND all prior tests
;; were falsy — each test contributes a per-target narrowing.
;;
;; Scope of this iteration: ONLY direct `:some?` / `:nil?` predicates.
;; Predicates that wrap multi-step guards (`:str-blank?`, `:and`/`:or`
;; over multiple `:nil?`s, custom `_X-blank?` shims with `:get` +
;; `:str-blank?` chains) still need `:type T` author-assertions. The
;; broader path-sensitive analysis is deferred — see
;; `docs/TYPE_SYSTEM_DECISIONS.md`.
;; =============================================================================

(defn- direct-predicate-of-ref
  "If `ref-name`'s impl-chain root is `:some?` / `:nil?` / `:is-a?`
   and its target slot is a ref to fn-name T, return:
     `[:some?|:nil? T]`           — null-guard predicates
     `[:is-a? T type-tag]`        — type-shape predicate with literal `:type` arg
   Otherwise nil. Mirrors `predicate-of-ref` in
   `core/logic/impls.clj`; duplicated here because that one is
   `defn-` and importing would create a cycle (impls.clj loads
   AFTER types/check.clj)."
  [ref-name]
  (when ref-name
    (when-let [info (registry/rich-type-of ref-name)]
      (let [root (registry/root-base-fn-name ref-name)
            rb (:resolved-bindings info {})]
        (cond
          (#{:some? :nil?} root)
          (when-let [target (:ref (get rb :value))]
            [root target])

          ;; `:is-a?` with a ref `:value` AND a literal-keyword `:type`.
          ;; Returns `[:is-a? target-fn-name type-tag-keyword]` so the
          ;; branch narrowing can map the tag to a structural type.
          (= :is-a? root)
          (let [target (:ref (get rb :value))
                type-binding (get rb :type)
                type-tag (or (:value type-binding)
                             (when (keyword? type-binding) type-binding))]
            (when (and target (keyword? type-tag))
              [:is-a? target type-tag])))))))


;; `:is-a?` type-tag → structural type. Mirrors `runtime-predicates`
;; in `graphden.types.core`, restricted to tags that map to a useful
;; narrowing (skip `:any` — it widens, not narrows).
(def is-a-tag->structural-type
  {:sequence    [:list :any]
   :vector      [:list :any]
   :map         [:map :any :any]
   :text        :text
   :int         :int
   :numeric     :numeric
   :bool        :bool
   :keyword     :keyword
   :null        :null
   :uuid        :uuid
   :bytes       :bytes
   :timestamptz :timestamptz
   :float       :float})


(defn- merge-branch-override-maps
  "Merge per-target narrowings for a branch ref reachable from MORE
   THAN ONE branch of the same `:if`/`:cond` (e.g. `:then` and `:else`
   referencing the same fn). Sound combination is per-target: narrowed
   on BOTH paths → union of the two narrowings; narrowed on only one
   path → the other path sees the target's full static type, and
   union-with-static is static — so the narrowing is DROPPED. The old
   `assoc` overwrite kept only the LAST branch's narrowing, typing a
   shared `:some?`-guarded branch fn as if the target were `:null`."
  [m1 m2]
  (into {}
        (keep (fn [[target n1]]
                (when-some [n2 (get m2 target)]
                  [target (if (= n1 n2)
                            n1
                            (types/make-union [n1 n2]))])))
        m1))


(defn- add-branch-override
  "Attach `override-map` for `branch-ref` into the `{branch-ref →
   {target → narrowed}}` accumulator, merging via
   `merge-branch-override-maps` when the ref already has an entry."
  [acc branch-ref override-map]
  (if (and branch-ref (seq override-map))
    (update acc branch-ref
            (fn [existing]
              (if existing
                (merge-branch-override-maps existing override-map)
                override-map)))
    acc))


(defn- structural-type-preserving-markers
  "Project `target-static` onto the structural type for `type-tag`,
   PRESERVING any markers (`[:secret …]`, `[:pii …]`, …) that wrap
   the target's static return — the `:is-a?` counterpart to
   `strip-null`'s marker-preservation. Peel the outer marker tags
   in order, apply `is-a-tag->structural-type`, re-wrap with the same
   tags so a secret stays a secret (soundness: an `:is-a?`-narrowed
   branch must not launder taint into a plain sink).

   Falls back to `target-static` unchanged when the tag is unknown."
  [target-static type-tag]
  (loop [t     target-static
         tags  []]
    (cond
      (types/marker-type? t)
      (recur (types/marker-inner t) (conj tags (types/marker-tag t)))

      ;; A marker survived under a non-peelable wrapper (unusual): we
      ;; can't re-attach it precisely, so decline to narrow rather than
      ;; launder taint into the branch's recorded return.
      (and (empty? tags) (types/contains-marker? target-static))
      target-static

      :else
      (if-some [structural (get is-a-tag->structural-type type-tag)]
        (reduce (fn [inner tag] (types/make-marker-type tag inner))
                structural
                (rseq tags))
        target-static))))


(defn- narrowed-type-for-predicate
  "Apply predicate-kind to target's static return. Supported predicates:
   - `:some?` taken → strip-null; not-taken → `:null`
   - `:nil?`  taken → `:null`; not-taken → strip-null
   - `:is-a?` taken → structural-type-for-tag (uses `is-a-tag->
     structural-type`), marker-preserving; not-taken → original (no
     useful subtraction without a row-poly type-system)
   `polarity` is `:taken` or `:not-taken`."
  [pred-kind polarity target-static & [type-tag]]
  (cond
    (= pred-kind :is-a?)
    (if (= polarity :taken)
      (structural-type-preserving-markers target-static type-tag)
      target-static)

    (or (and (= pred-kind :some?) (= polarity :taken))
        (and (= pred-kind :nil?)  (= polarity :not-taken)))
    (types/strip-null target-static)

    :else :null))


(defn- if-branch-overrides
  "Return `{branch-ref-name → {target → narrowed}}` for an `:if`-
   shaped fn-def F. `:then` is the taken branch; `:else` is
   not-taken. Empty map when no direct-predicate test is found."
  [fd]
  (let [args      (or (:args fd) {})
        test-b    (get args :test)
        test-ref  (ref-binding-name test-b)
        pred      (direct-predicate-of-ref test-ref)
        then-ref  (ref-binding-name (get args :then))
        else-ref  (ref-binding-name (get args :else))]
    (if-not pred
      {}
      (let [[pred-kind target type-tag] pred
            target-static (:return (registry/rich-type-of target) :any)
            taken-narrow     (narrowed-type-for-predicate pred-kind :taken     target-static type-tag)
            not-taken-narrow (narrowed-type-for-predicate pred-kind :not-taken target-static type-tag)]
        ;; `add-branch-override` (not assoc) so `:then` and `:else`
        ;; referencing the SAME fn union their narrowings instead of
        ;; the else entry silently overwriting the then entry.
        (cond-> {}
          (some? taken-narrow)
          (add-branch-override then-ref {target taken-narrow})

          (some? not-taken-narrow)
          (add-branch-override else-ref {target not-taken-narrow}))))))


(defn- cond-branch-overrides
  "Return `{result-ref-name → {target → narrowed}}` for a `:cond`-
   shaped fn-def F. Clauses sit in `:clauses` as a vector of
   alternating test/result items. Result at clause k receives:
   prior tests' `:not-taken` narrowings + this test's `:taken`
   narrowing. The literal-true sentinel at an even index acts as
   an exhaustiveness marker — no narrowing it can contribute to
   its own result, but prior clauses' `:not-taken`s still apply."
  [fd]
  (let [args     (or (:args fd) {})
        clauses  (get args :clauses)]
    (if-not (sequential? clauses)
      {}
      (let [pairs (partition 2 clauses)]
        (loop [remaining pairs
               prior-not-taken {}
               acc {}]
          (if (empty? remaining)
            acc
            (let [[test-item result-item] (first remaining)
                  test-ref     (ref-binding-name test-item)
                  pred         (direct-predicate-of-ref test-ref)
                  result-ref   (ref-binding-name result-item)
                  this-taken   (when pred
                                 (let [[k t tag] pred
                                       static (:return (registry/rich-type-of t) :any)]
                                   {t (narrowed-type-for-predicate k :taken static tag)}))
                  for-this     (merge prior-not-taken this-taken)
                  ;; `add-branch-override` (not assoc): the same
                  ;; result fn referenced from SEVERAL clauses unions
                  ;; per-target narrowings across its paths — the old
                  ;; assoc kept only the LAST clause's view.
                  acc'         (add-branch-override acc result-ref for-this)
                  this-not-taken (when pred
                                   (let [[k t tag] pred
                                         static (:return (registry/rich-type-of t) :any)]
                                     {t (narrowed-type-for-predicate k :not-taken static tag)}))
                  prior'       (merge prior-not-taken this-not-taken)]
              (recur (rest remaining) prior' acc'))))))))


(defn- propagate-override-to-ref-tree
  "Starting from a ref `start-name`, walk transitive fn-refs and
   attach `override-map` (`{target → narrowed-type}`) at every
   visited fn-def. Stops at refs not known in `fn-defs-by-name`."
  [ref-children-by-name fn-defs-by-name start-name override-map acc]
  (loop [queue (vector start-name)
         visited #{}
         acc acc]
    (if (empty? queue)
      acc
      (let [callee (peek queue)
            queue  (pop queue)]
        (if (or (contains? visited callee)
                (not (contains? fn-defs-by-name callee)))
          (recur queue visited acc)
          (let [visited' (conj visited callee)
                acc' (update acc callee
                             (fn [existing]
                               (reduce-kv (fn [m target narrowed]
                                            (update m target
                                                    (fn [prev]
                                                      (if prev
                                                        (types/make-union [prev narrowed])
                                                        narrowed))))
                                          (or existing {})
                                          override-map)))
                children (get ref-children-by-name callee [])]
            (recur (into queue children) visited' acc')))))))


(defn build-ref-return-overrides
  "Phase #170 — build `{fn-name → {target-fn-name → narrowed-type}}`.
   For each F whose parent root is `:if` / `:cond`, compute per-
   branch overrides and propagate into the branch's transitive ref-
   tree. Pass 3 binds `*ref-return-overrides*` to the per-callee
   entry, so the type-checker sees narrowed returns when re-checking
   a fn-def reachable only from a provably-non-null guarded branch.

   PRECONDITION: call AFTER the full per-fn-def check sweep
   (`packages/sync`'s check loop in production; `check-all-defs!` in
   tests) — this reads every target's `:return` via
   `registry/rich-type-of`, so a registry not yet fully populated
   yields empty / `:any` overrides (no error, just no narrowing).
   Same ordering dependency as `build-caller-narrowings`."
  [fn-defs]
  (let [fn-defs-by-name (into {} (map (fn [fd] [(:name fd) fd])) fn-defs)
        known-names     (set (keys fn-defs-by-name))
        ref-children    (into {}
                              (map (fn [[n fd]]
                                     [n (ref-children-of fd known-names)]))
                              fn-defs-by-name)]
    (reduce
      (fn [acc fd]
        (let [parent (or (:parent fd) (first (:parents fd)))
              root   (when parent (registry/root-base-fn-name parent))
              branch-overrides (case root
                                 :if    (if-branch-overrides fd)
                                 :cond  (cond-branch-overrides fd)
                                 nil)]
          (if (empty? branch-overrides)
            acc
            (reduce-kv
              (fn [a branch-ref override-map]
                (propagate-override-to-ref-tree
                  ref-children fn-defs-by-name branch-ref override-map a))
              acc
              branch-overrides))))
      {}
      fn-defs)))
