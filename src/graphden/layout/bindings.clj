(ns graphden.layout.bindings
  "Bindings resolution + classifier-item constructors for the layout
   pipeline.

   Holds:

   - `resolve-arg-name` / `arg-ids-from` — arg-row inspection helpers;
   - the five classifier-item constructors (`ref-item-from-arg` /
     `ref-item-from-bnd` / `value-item-from-arg` /
     `value-item-from-bnd` / `unset-item-from-arg`) — materialise the
     `{:kind :ref|:value|:unset :arg-name :arg-type :arg-id …}` maps
     the rest of the pipeline threads downstream;
   - `add-bindings-from-fn` / `build-arg-bindings` — slot→binding
     resolution against a single fn's own binding rows;
   - sequence-anchor helpers (`walk-anchor-chain` / `sequence-anchor?`
     / `expand-sequence-anchor`) — materialise
     the per-item synthetic `:ref`/`:value`/`:unset` rows from a
     sequence-typed anchor, plus its append tail;
   - `truncate-label` — small label-shaping primitive shared by the
     builder helpers."
  (:require
    [graphden.layout.data :as data]))


(defn resolve-arg-name
  "Effective name of an arg row. Anchor rows already carry the
   resolved name (closest rename-to, falling back to slot.name) — see
   `derive-fn-slot-views`. Item rows have `:name=nil`; their anchor
   sits exactly one source-id hop up, so a single fallback step is
   enough."
  [arg arg-map]
  (or (:name arg)
      (some-> arg :source-id arg-map :name)))


(defn arg-ids-from
  "Pluck slot-id/binding-id/item-id/fn-id from an arg row so it can be
   merged into the internal `compute-display-args` arg-row shape and
   eventually surface on the emitted graph node via
   `arg-row->node-id-fields`."
  [arg]
  (select-keys arg [:slot-id :binding-id :item-id :fn-id]))


;; ---------------------------------------------------------------------------
;; Classifier-item constructors — five tiny builders that materialise the
;; `{:kind :ref|:value|:unset :arg-name :arg-type :arg-id …}` maps the
;; layout pipeline threads downstream. Pre-extract, every cond arm in
;; `compute-display-args` / `collect-expanded-args` / `expand-sequence-
;; anchor` re-spelled the same 4-7 line merge — easy place to forget
;; `:arg-type` or `:is-binding` while editing. Centralising them also
;; documents the SHAPE of each kind in one place.
;;
;; `:from-ancestor` and `:sequence-anchor?` aren't baked in here — they
;; only matter for a subset of callers, which assoc them on top.

(defn ref-item-from-arg
  "`:ref` classifier item where the raw arg defines its own ref —
   arg-name + ref-id come straight from the arg row."
  [arg arg-name]
  (merge {:kind :ref :arg-name arg-name
          :arg-type (:type arg)
          :ref-id (:ref-id arg) :arg-id (:id arg)
          :is-binding false}
         (arg-ids-from arg)))


(defn ref-item-from-bnd
  "`:ref` classifier item where an ancestor binding (`bnd`) covers the
   slot — bnd's rename + ref-id win over the raw arg. `:arg-type`
   still comes from the raw arg row (the slot's type-kw doesn't change
   under a value/ref binding)."
  [arg bnd]
  (merge {:kind :ref :arg-name (:arg-name bnd)
          :arg-type (:type arg)
          :ref-id (:ref-id bnd) :arg-id (:arg-id bnd)
          :is-binding true}
         (arg-ids-from bnd)))


(defn value-item-from-arg
  "`:value` classifier item where the literal value sits on the raw arg."
  [arg arg-name]
  (merge {:kind :value :arg-name arg-name
          :arg-type (:type arg)
          :value (:value arg) :arg-id (:id arg)}
         (arg-ids-from arg)))


(defn value-item-from-bnd
  "`:value` classifier item where the literal value sits on a covering
   ancestor binding (`bnd`)."
  [arg bnd]
  (merge {:kind :value :arg-name (:arg-name bnd)
          :arg-type (:type arg)
          :value (:value bnd) :arg-id (:arg-id bnd)}
         (arg-ids-from bnd)))


(defn unset-item-from-arg
  "`:unset` classifier item — placeholder for a slot with no value or
   ref binding. The slot's `:type` flows through as `:arg-type` so
   placeholder chips can still display the expected type."
  [arg arg-name]
  (merge {:kind :unset :arg-name arg-name
          :arg-type (:type arg) :arg-id (:id arg)}
         (arg-ids-from arg)))


(defn add-bindings-from-fn
  "Add `fn-id`'s OWN binding rows to the slot-id-keyed bindings map.

   A binding's mere presence (whether scalar `:value` / `:ref-fn-id`
   or `:list-append` with items) marks the slot as bound at fn-id's
   level. List-items live UNDER a binding row and don't introduce new
   slot identities, so the binding's own entry suffices to make
   downstream `(contains? bindings slot-id)` checks succeed.

   Caller is responsible for reduce ordering: walk ancestors-FIRST so
   descendants overlay via `assoc` (closest-fn-wins)."
  [fn-id bindings lookups]
  (let [{:keys [bindings-by-fn slot-map items-by-binding slot-by-fn-source-slot]} lookups]
    (reduce
      (fn [b bnd]
        (let [sid (:slot-id bnd)
              slot (get slot-map sid)
              ;; Phase 6c — same lookup as build-anchor-row. The
              ;; renamed-view's name overrides the source slot's
              ;; for display purposes when the binding-owner has
              ;; declared a rename.
              renamed-view (when slot-by-fn-source-slot
                             (get slot-by-fn-source-slot [(:fn-id bnd) sid]))
              ;; `:value-present`, NOT `(some? :value)` — a binding
              ;; pinned to literal `nil` (`:default nil` in fns.edn)
              ;; must still render in the editor as a value binding.
              has-value (true? (:value-present bnd))
              has-ref (some? (:ref-fn-id bnd))
              has-items (seq (get items-by-binding (:id bnd) []))]
          (if (and slot (or has-value has-ref has-items))
            (assoc b sid {:arg-name (or (:name renamed-view) (:name slot))
                          :value (:value bnd)
                          :value-present (true? (:value-present bnd))
                          :ref-id (:ref-fn-id bnd)
                          :arg-id (data/synth-arg-id fn-id sid)
                          :slot-id sid
                          :binding-id (:id bnd)
                          :fn-id fn-id})
            b)))
      bindings
      (get bindings-by-fn fn-id []))))


(defn build-arg-bindings
  "Bindings from fn's OWN binding rows only (level-0, non-expanded
   mode). Ancestor bindings excluded — they only appear when the user
   explicitly expands those depths."
  [fn-id lookups]
  (add-bindings-from-fn fn-id {} lookups))


;; =============================================================================
;; SEQUENCE-ANCHOR HELPERS
;; =============================================================================

(defn truncate-label
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (dec max-len)) "…")
    s))


(declare sequence-anchor?)


(defn- pass-through-anchor?
  "An anchor that contributes NOTHING of its own to the list — no items,
   no literal, no ref (the fn never bound the slot, or bound only a
   rename / a seal on it). Its list IS its parent's list: a descendant
   of `add-10` has `[10]` as its input, and the append tail after it is
   the descendant's — `list-append` is how a child extends an inherited
   list. Drawn only when the reader UNFOLDS the ancestor's row (the
   `inherit?` arm of `walk-anchor-chain`): at level 0 a card shows what
   the fn binds itself, and an ancestor's list is the ancestor's — the
   same rule that keeps `route`'s `[path method-map]` off every route's
   card."
  [anchor]
  (and (nil? (:next-arg-id anchor))
       (not (true? (:value-present anchor)))
       (nil? (:ref-id anchor))))


(defn- canon-slot
  [slot-map sid]
  (loop [s sid, seen #{}]
    (let [src (:source-slot-id (get slot-map s))]
      (if (and src (not (contains? seen s)))
        (recur src (conj seen s))
        s))))


(defn- parent-anchor-of
  "The anchor `anchor`'s list continues FROM: the same slot's anchor at
   the closest ancestor (BFS over `:parent-ids`) that binds the slot
   itself — items, a literal, a ref — skipping pass-through ancestors.
   `:source-id` cannot serve here: it points at the slot's DECLARING
   fn's anchor (the base-fn's, usually empty), so an `add-10-more`
   appending to `add-10 [10]` would prepend `add`'s nothing and draw
   its own items alone. `within?` (a predicate over fn-ids, nil = any)
   bounds the walk to the fns the reader has UNFOLDED — an ancestor
   outside it is not consulted, and nothing above it is either: at
   depth 1 `health` shows `get-route`'s bindings, not `route`'s list
   two rows further up. Without `lookups` (no fn-map / args-by-fn) the
   `:source-id` hop is the fallback."
  [anchor arg-map lookups within?]
  (let [{:keys [fn-map args-by-fn slot-map]} lookups
        ok? (or within? (constantly true))]
    (if (and fn-map args-by-fn)
      (let [target (canon-slot slot-map (:slot-id anchor))]
        (loop [queue (vec (:parent-ids (get fn-map (:fn-id anchor))))
               visited #{}]
          (when-let [cur (first queue)]
            (cond
              (contains? visited cur) (recur (subvec queue 1) visited)
              (not (ok? cur)) (recur (subvec queue 1) (conj visited cur))
              :else
              (let [here (some #(when (and (sequence-anchor? %)
                                           (= target (canon-slot slot-map (:slot-id %))))
                                  %)
                               (get args-by-fn cur))]
                (if (and here (not (pass-through-anchor? here)))
                  here
                  (recur (into (subvec queue 1) (:parent-ids (get fn-map cur)))
                         (conj visited cur))))))))
      (when-let [src (some-> (:source-id anchor) arg-map)]
        (when (= :sequence (:type src)) src)))))


(defn walk-anchor-chain
  "From a sequence anchor arg, walks next-arg-id via arg-map and returns
   the ordered vector of item arg entities. When the anchor has
   `:append? true` — or, with `inherit`, is a pass-through
   (`pass-through-anchor?`: the fn binds nothing on the slot itself) —
   recursively prepends the parent's effective chain (`parent-anchor-of`)
   so callers see the parent's items followed by this anchor's appended
   items. `inherit` is nil / false (own chain, plus an `:append?`
   prepend from any ancestor) or a predicate over fn-ids — the unfolded
   set — bounding which ancestors' lists a pass-through may show. A
   parent that pins the slot to a literal or a ref has no chain to
   prepend. Without `lookups` the `:source-id` hop resolves the parent
   (the legacy fallback)."
  ([anchor arg-map] (walk-anchor-chain anchor arg-map nil nil))
  ([anchor arg-map lookups] (walk-anchor-chain anchor arg-map lookups nil))
  ([anchor arg-map lookups inherit]
   (let [own (loop [cur (:next-arg-id anchor)
                    acc []
                    depth 0]
               (cond
                 (or (nil? cur) (> depth 10000)) acc
                 :else
                 (let [item (get arg-map cur)]
                   (if (nil? item)
                     acc
                     (recur (:next-arg-id item) (conj acc item) (inc depth))))))
         within? (when (and inherit (not (true? inherit))) inherit)
         prepend? (or (true? (:append? anchor))
                      (and inherit (pass-through-anchor? anchor)))
         prepended (when prepend?
                     (when-let [parent-anchor (parent-anchor-of anchor arg-map lookups
                                                                (when (pass-through-anchor? anchor) within?))]
                       (when (some? (:next-arg-id parent-anchor))
                         (walk-anchor-chain parent-anchor arg-map lookups inherit))))]
     (vec (concat (or prepended []) own)))))


(defn sequence-anchor?
  "A :sequence-typed arg is a chain anchor only when it doesn't carry
   its own fn-ref binding. An arg whose `:ref-id` is set IS a
   ref-to-list-returning-fn (e.g. `:_router/:routes :all`) and must
   render as a regular ref edge — not as an empty-chain sentinel."
  [arg]
  (and (= :sequence (:type arg))
       (nil? (:ref-id arg))))


(defn expand-sequence-anchor
  "For a sequence anchor, returns a vector of synthetic arg descriptors —
   one per chain item, labeled `<slot>[idx]`, followed by the APPEND
   TAIL: an `:unset` entry pointing AT the anchor, flagged
   `:sequence-anchor? true` (plus `:seq-tail? true` when items precede
   it). The editor draws it as the list's trailing free slot — the
   dashed `+` after the last item — so appending reads like binding one
   more argument rather than pressing a button on a chip. Items with
   ref-id become :ref entries, items with value become :value.

   The tail is emitted even when an ancestor closed the list
   (`:list-closed`): the placeholder node carries `:listClosedBy`
   (`builder-helpers/edge-seal-fields`) and the editor draws a lock
   there instead of a `+` — the reader sees WHY there is nothing to
   append to, where a missing tail said nothing.

   The anchor entry is what makes the frontend route the click through
   `appendSequenceItem` instead of the regular free-arg binder (which
   would try to PUT `value=` / `ref-id=` on the anchor itself)."
  ([anchor slot-name arg-map] (expand-sequence-anchor anchor slot-name arg-map nil nil))
  ([anchor slot-name arg-map lookups] (expand-sequence-anchor anchor slot-name arg-map lookups nil))
  ([anchor slot-name arg-map lookups inherit]
   (let [items (walk-anchor-chain anchor arg-map lookups inherit)
         tail (assoc (unset-item-from-arg anchor slot-name)
                     :sequence-anchor? true)]
     (if (empty? items)
       [tail]
       (conj (into []
                   (map-indexed
                     (fn [idx item]
                       (let [lbl (str slot-name "[" idx "]")]
                         (cond
                           (some? (:ref-id item)) (ref-item-from-arg item lbl)
                           (some? (:value item))  (value-item-from-arg item lbl)
                           :else                  (unset-item-from-arg item lbl)))))
                   items)
             (assoc tail :seq-tail? true))))))
