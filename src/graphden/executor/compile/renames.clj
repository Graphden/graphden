(ns graphden.executor.compile.renames
  "Compile-time helpers for translating free-arg names between a caller
   F and a ref-target R, plus deep-walking R's free args to populate
   HOF lambda-param lists.

   In the slot/fn-slot/binding model the rename information lives in
   `binding.rename-to`. F's caller-facing name for slot S is the
   closest binding's `:rename-to` if any, else slot.name. Walking
   non-HOF refs collects R's leftover free slots so the outer F can
   thread them through."
  (:require
    [clojure.set :as set]
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]))


(defn chain-source-slot-ids
  "Walk slot.source-slot-id chain starting from `slot-id`. Returns a
   vector of slot-ids along the chain INCLUDING the start, in chain
   order (head → deeper sources). Bounded to 16 hops; cycle-guarded
   via a parallel seen-set."
  [slot-id slot-map]
  (loop [acc [], seen #{}, sid slot-id, depth 0]
    (if (or (nil? sid) (contains? seen sid) (>= depth 16))
      acc
      (recur (conj acc sid)
             (conj seen sid)
             (:source-slot-id (get slot-map sid))
             (inc depth)))))


(defn- own-rename-chain-map
  "For `fid`'s own rename slots — own-slots whose `:source-slot-id` is
   set — return `{slot-id → rename-name}` covering every slot-id
   along each rename's source chain. First occurrence wins so an
   ancestor's own renames don't shadow each other.

   The chain entries matter because the rename's source may itself
   be a renamed-view slot, e.g. `_list-branches-as-json-item.item`'s
   source is `_branch-row-id.branch-row` whose source is `:get.coll`.
   When an inner walker emits a free for the leaf slot, we need to
   recognise BOTH chain links so the outer rename wins regardless of
   which link the inner classify chose."
  [fid {:keys [fn-slots-by-fn slot-map]}]
  (reduce (fn [acc fs]
            (let [s (get slot-map (:slot-id fs))]
              (if-let [src (:source-slot-id s)]
                (let [chain (chain-source-slot-ids src slot-map)
                      nm (keyword (:name s))]
                  (reduce (fn [m sid]
                            (if (contains? m sid) m (assoc m sid nm)))
                          acc
                          chain))
                acc)))
          {}
          (get fn-slots-by-fn fid [])))


(defn- merge-keep-outer
  "Merge `inner` into `outer` keeping outer values on collision —
   outer rename slots were declared closer to the call site, so their
   ext-name is the one the caller supplies."
  [outer inner]
  (reduce-kv (fn [acc k v] (if (contains? acc k) acc (assoc acc k v)))
             outer
             inner))


(declare ^:private deep-free-ext-names*)
(declare ^:private deep-free-ext-entries*)
(declare ^:private hof-lambda-params)


(defn deep-free-ext-names
  "Memoised wrapper around `deep-free-ext-names*` — same fn-id +
   lookups always produce the same answer, and the underlying walk
   is O(reachable-graph). compile-all calls this MANY times per
   compile-fn (once per ref-binding via `build-ref-renames`); without
   memoisation the worst case is quadratic in graph size.

   SURFACE walker — stops at `:is-fn` HOF boundaries because HOF
   callables expose their OWN argument surface (HOFs are invoked
   via `hof-wrap`'s `make-shape-callable`, not threaded with the
   outer caller's fa). Consumers that need the HOF boundary opaque
   (`hof-lambda-params`, `alpha-equiv-lambda-params`,
   `build-ref-renames`, the editor's free-arg-ext-names) use THIS
   function.

   For CACHE-KEY projection, where closure-captured names DO affect
   the result, use `cache-projection-frees` (below). See the
   docstring there for the invariant relating the two."
  [fn-id {:keys [deep-frees-cache] :as lookups}]
  (if-let [cache deep-frees-cache]
    (or (get @cache fn-id)
        (let [r (deep-free-ext-names* fn-id lookups)]
          (swap! cache assoc fn-id r)
          r))
    (deep-free-ext-names* fn-id lookups)))


(defn deep-free-ext-entries
  "Slot-id-keyed sibling of `deep-free-ext-names` — returns a vector of
   `{:ext-name K :slot-id UUID}` entries, one per chain-leaf slot the
   inner consumers under `fn-id` will read from `fa` at runtime.

   - `:slot-id` is the SLOT the inner consumer's `bnd.slot-id`
     references (= the inner's root-slot id for `:free` bindings; the
     owner's own rename-slot id for seq `{:as :name}` positional
     items). It identifies WHICH slot the value will flow into past
     the public-API boundary.
   - `:ext-name` is the caller-facing name — the outermost rename's
     name when an ancestor rename applies, otherwise the inner's own
     slot name.
   - Multiple entries with the SAME `:ext-name` are allowed — that is
     precisely the runtime collision case (#104 `:body`). Phase 2
     decides what to do at the public API boundary (raise
     `:execution-error/ambiguous-arg-name` when caller passes the
     collided name).
   - Deduped by `:slot-id` only: two inheritance paths reaching the
     same chain-leaf slot collapse into one entry (same underlying
     consumer); two distinct chain-leaf slots sharing a name do NOT
     collapse.

   SURFACE walker — stops at `:is-fn` HOF boundaries for the same
   reason as `deep-free-ext-names`: HOFs expose their own argument
   surface through `hof-wrap`'s `make-shape-callable`, not by
   threading the outer caller's fa.

   Memoised via `:deep-free-ext-entries-cache`. Pure walker — no
   consumer yet (Phase 1 of the slot-id-keyed-runtime refactor;
   docs/RUNTIME_SLOT_ID_REFACTOR.md)."
  [fn-id {:keys [deep-free-ext-entries-cache] :as lookups}]
  (if-let [cache deep-free-ext-entries-cache]
    (or (get @cache fn-id)
        (let [r (deep-free-ext-entries* fn-id lookups)]
          (swap! cache assoc fn-id r)
          r))
    (deep-free-ext-entries* fn-id lookups)))


(declare cache-projection-frees)


(defn- hof-closure-captures
  "Helper for `cache-projection-frees`. Walks F's non-HOF tree (same
   shape as `deep-free-ext-names*`'s walk over `:ref :is-fn false`
   bindings + seq item refs) and at every `:is-fn :ref` binding
   encountered, computes the HOF's closure-captured contribution:
   `cache-projection-frees(target) \\ hof-lambda-params(...)`.

   Returns a set of ext-names. Used internally only — public callers
   should query `cache-projection-frees` which unions this with
   `deep-free-ext-names`'s surface output."
  [fn-id lookups]
  (let [captures (atom #{})
        visited (atom #{})]
    (letfn [(walk
              [fid]
              (when-not (contains? @visited fid)
                (swap! visited conj fid)
                (doseq [bnd (b/collect-bindings fid lookups)]
                  (case (:kind bnd)
                    :ref (if (:is-fn bnd)
                           (let [target (:ref-id bnd)
                                 inner (cache-projection-frees target lookups)
                                 lambda-params (set (hof-lambda-params
                                                      target (:slot-id bnd)
                                                      bnd fid lookups))]
                             (doseq [n inner]
                               (when-not (contains? lambda-params n)
                                 (swap! captures conj n))))
                           (walk (:ref-id bnd)))
                    :seq (doseq [item (:items bnd)]
                           (when-let [r (:ref-fn-id item)] (walk r)))
                    nil))))]
      (walk fn-id))
    @captures))


(defn cache-projection-frees
  "Names whose value affects fn-id's evaluation result, used by
   `compile-eager`'s `call-with-cache` to project the caller's `fa`
   into the cache key. Strict SUPERSET of `deep-free-ext-names`.

   `cache-projection-frees(F)` = `deep-free-ext-names(F)` ∪
   {names HOF callbacks in F's tree read from F's caller's fa}.

   The HOF closure-capture component comes from `hof-wrap`'s
   `(merge fa lambda-args)` (`compile_eager.clj:234`): when F invokes
   a HOF target H, H's body sees F's fa snapshot plus the per-call
   lambda-args. Anything H reads beyond its lambda-params is read
   from F's fa — and thus part of F's effective evaluation
   dependencies even though it's NOT part of F's caller-facing
   interface.

   Production bug closed by this (commit `[...]`):
   `_shape-secret-bindings`'s `:filter :pred` reads `:fn-row` via
   closure capture; `deep-free-ext-names` returned `#{}` for
   `_shape-secret-bindings`, so the cache key omitted `:fn-row` and
   every secret invocation hashed to one cache slot — `GET
   /api/secrets` returned every row with the FIRST secret's `:path`.

   Invariant (verified by
   `verify-cache-projection-frees-superset-of-deep-free!`):
   `(set/superset? (cache-projection-frees F) (set (deep-free-ext-names F)))`
   for every fn-id F. A strict superset can only ever produce MORE
   cache misses (slower), never a wrong cache hit. If this invariant
   ever breaks, the bug-class (stale-cache returning a
   `make-shape-callable` closure that flows as data into a sibling
   evaluation) returns.

   Memoised per fn-id via `:cache-projection-frees-cache`. The cache
   is seeded with `#{}` before the recursive descent so any
   structural cycle terminates (graphden has none today, cheap
   insurance)."
  [fn-id {:keys [cache-projection-frees-cache] :as lookups}]
  (if-let [cache cache-projection-frees-cache]
    (or (get @cache fn-id)
        (let [_ (swap! cache assoc fn-id #{})
              direct (set (deep-free-ext-names fn-id lookups))
              captured (hof-closure-captures fn-id lookups)
              result (into direct captured)]
          (swap! cache assoc fn-id result)
          result))
    (into (set (deep-free-ext-names fn-id lookups))
          (hof-closure-captures fn-id lookups))))


(defn verify-cache-projection-frees-superset-of-deep-free!
  "Exhaustive invariant check: for every fn-id in `lookups`, asserts
   `(set/superset? (cache-projection-frees F) (set (deep-free-ext-names F)))`.

   Returns a vector of counter-examples
   `[{:fn-id F :missing #{names…}} …]`, EMPTY when the invariant
   holds. Used by tests and by ad-hoc nREPL probes to verify the
   walker before deploying the cache-projection switch.

   Wired BEFORE `compile-eager` swaps to `cache-projection-frees` —
   if any counter-example surfaces, the walker is broken and would
   re-introduce the bug class my prior attempts hit (stale cache hit
   returning a closure as data)."
  [lookups]
  (vec
    (keep (fn [[fid _]]
            (let [surface (set (deep-free-ext-names fid lookups))
                  cache (cache-projection-frees fid lookups)
                  missing (set/difference surface cache)]
              (when (seq missing)
                {:fn-id fid
                 :fn-name (get-in lookups [:fn-map fid :name])
                 :missing missing
                 :surface surface
                 :cache cache})))
          (:fn-map lookups))))


(defn- deep-free-ext-names*
  "Collect free-arg external names reachable from `fn-id`, walking
   across non-HOF ref bindings. `:is-fn` refs are a BOUNDARY — the
   inner hof-wrap consumes its own leftovers without widening the
   outer interface.

   Emits BOTH required and optional free args — the rename-dispatch
   callers (`hof-lambda-params`, `alpha-equiv-lambda-params`,
   `build-ref-renames`) need to see every name a callee can receive
   at its call boundary, not just the ones it requires. An earlier
   `(not (:required bnd))` filter silently dropped optional frees,
   which broke HOF dispatch over callables whose only free arg was
   an optional rename — e.g. `_parse-fn-form-pid-parse-uuid :parent
   :parse-uuid :args {:s {:as :item}}` (parse-uuid's `:s` is
   `:required false`) wrapped as a 0-arg variadic-ignore lambda
   instead of a 1-arg lambda, so each `:map` iteration discarded
   the per-element value and parse-uuid returned nil.

   Cross-fn rename propagation: a fn that owns a rename slot whose
   source-slot-id reaches into a ref-tree (e.g.
   `_list-branches-as-json-item.item {:as → :_branch-row-id.branch-row}`
   whose source-of-source is `:get.coll`) translates the inner walk's
   ext-names for the deep slot to the outer rename's name. Without
   this, inner classify would emit the inner rename (`:branch-row`)
   and HOF dispatch at `:map :func :_list-branches-as-json-item`
   would see 3 unrelated frees instead of `[:item :default :else]`,
   pick the wrong wrap shape, and silently return all-nil rows.

   The propagation uses the inner fn's OWN rename slot-id as the key
   into outer-renames — NOT the binding's raw (root-inherited)
   slot-id. This distinguishes two structurally identical-looking
   shapes:

   - Bridged: outer's chain expansion (own-rename-chain-map)
     includes a NON-ROOT intermediate slot that inner's rename
     ALSO owns (slot dedup via `(name, source-slot-id)`-keyed
     resolution, e.g. `_branch-row-id.branch-row` slot
     `6a8b587c` is also in `_list-branches-as-json-item`'s chain).
     Translate ext-name to outer's name; runtime
     `build-ref-renames` correspondingly emits the bridge.

   - Sibling: outer and inner both rename the same root slot but
     own DIFFERENT rename slots (e.g.
     `_rv-versions-for-this-eid.coll {:as :versions-by-eid}` and
     `_rv-this-eid.coll {:as :item}` both source from `:get.coll`
     but their rename slots aren't deduped — no shared
     intermediate). Don't translate; keep inner's ext-name; runtime
     `build-ref-renames` correspondingly returns `{}` and inner
     reads its own free arg.

   Returns the names in their first-encountered order, deduped."
  [fn-id lookups]
  (let [result (atom [])
        seen (atom #{})
        visited-fns (atom #{})
        emit! (fn [n]
                (when-not (contains? @seen n)
                  (swap! seen conj n)
                  (swap! result conj n)))]
    (letfn [(translate
              [entry name-key outer-renames fid]
              ;; Prefer fid's own rename slot-id over the binding's raw
              ;; (root-inherited) slot-id. This is the structural test
              ;; that distinguishes outer-rename-bridges-into-inner
              ;; (shared chain link via rename slot) from outer-and-inner-
              ;; are-siblings-on-root (no shared chain link).
              ;;
              ;; - Bridged case (e.g. `_list-branches-as-json-item :item`
              ;;   → `_branch-row-id :branch-row`): R's rename slot id is
              ;;   the SHARED intermediate (e.g. `6a8b587c`); F's chain
              ;;   includes it; translate applies, R emits :item.
              ;; - Sibling case (e.g. `_rv-versions-for-this-eid
              ;;   :versions-by-eid` → `_rv-this-eid :item`): R's rename
              ;;   slot id is R's own (e.g. `0de3ffed`), NOT in F's chain
              ;;   (whose only non-root link is F's own slot); translate
              ;;   skips, R emits :item.
              ;;
              ;; Without a rename slot (inner has no own rename for the
              ;; binding's slot), fall back to the raw slot-id —
              ;; preserving the original "outer wins for inherited slot
              ;; with no inner rename" semantics.
              (let [own-rename-slot (get (:slot-by-fn-source-slot lookups)
                                         [fid (:slot-id entry)])
                    lookup-id (or (:id own-rename-slot) (:slot-id entry))]
                (if-let [r (get outer-renames lookup-id)]
                  (assoc entry name-key r)
                  entry)))
            (walk
              [fid covered outer-renames]
              (when-not (contains? @visited-fns fid)
                (swap! visited-fns conj fid)
                (let [bindings (mapv #(translate % :ext-name outer-renames fid)
                                     (b/collect-bindings fid lookups))
                      env-bindings (mapv #(translate % :env-name outer-renames fid)
                                         (b/collect-env-bindings fid lookups))
                      ;; NON-HOF `:ref` bindings DON'T cover the slot
                      ;; name at the caller's surface, regardless of
                      ;; rename:
                      ;;
                      ;; - The slot's VALUE is computed by the ref-target,
                      ;;   so the caller doesn't need to provide it.
                      ;; - But the ref-target itself reads names from
                      ;;   the SAME `fa` the caller passes (`compile-eager`
                      ;;   threads outer's `fa` into the ref's
                      ;;   `call-with-cache`). Those names ARE the
                      ;;   caller's surface.
                      ;;
                      ;; If the ref-target's deep-free happens to overlap
                      ;; with F's own slot name (e.g. `_normalized-tag`'s
                      ;; `:if :then {:parent :str-to-keyword :args {:string
                      ;; :_element-tag}}` where `:then`'s anon-fn's
                      ;; `:value` slot is bound by a ref to `_element-tag`
                      ;; which itself reads `:value` from fa), adding the
                      ;; ext-name to own-primaries would mask the inner
                      ;; emit and the walker would silently drop the
                      ;; caller-side free arg. Production symptom:
                      ;; `:hiccup-normalize`'s `:_hiccup-normalize-node`
                      ;; emitted nothing for `:value`, so the editor
                      ;; page rendered every tag as `<lang>` (string
                      ;; tags never coerced to keywords because the
                      ;; postwalk callback's free wasn't piped through).
                      ;;
                      ;; HOF refs (`:is-fn true`) DO cover — they're
                      ;; called via `make-shape-callable` with their
                      ;; OWN argument surface, not threaded with outer's
                      ;; fa, so their free args don't leak to outer's
                      ;; caller. `:value` / `:seq` bindings cover for
                      ;; the obvious reason (literal value or sequence
                      ;; constructor — caller doesn't supply).
                      own-primaries (into #{}
                                          (comp
                                            (remove #(or (= :free (:kind %))
                                                         (and (= :ref (:kind %))
                                                              (not (:is-fn %)))))
                                            (map :ext-name))
                                          bindings)
                      ;; Env-bindings cover names exposed by ref-targets'
                      ;; free args — without including them, the walker
                      ;; would re-emit the same name from inside the
                      ;; ref-tree when it should be considered bound.
                      env-names (into #{} (map :env-name) env-bindings)
                      next-covered (-> covered
                                       (into own-primaries)
                                       (into env-names))
                      ;; Outer renames win, so add fid's own renames
                      ;; only for keys not yet covered by an outer entry.
                      next-renames (merge-keep-outer outer-renames
                                                     (own-rename-chain-map
                                                       fid lookups))]
                  (doseq [bnd bindings]
                    (case (:kind bnd)
                      :free (let [n (:ext-name bnd)]
                              (when-not (next-covered n)
                                (emit! n)))
                      :ref  (when-not (:is-fn bnd)
                              (walk (:ref-id bnd) next-covered next-renames))
                      :seq  (doseq [item (:items bnd)]
                              (cond
                                ;; Ref item — recurse to collect its
                                ;; deep-free names.
                                (:ref-fn-id item)
                                (walk (:ref-fn-id item) next-covered next-renames)

                                ;; Positional rename `{:as :name}` —
                                ;; that name is exposed as a free arg
                                ;; from the binding's owner. A positional
                                ;; rename creates a rename slot on `fid`;
                                ;; if an outer fn renames that slot again
                                ;; (e.g. `_delete-err-fn-in-use :reason
                                ;; {:as :fn-in-use-reason}` over
                                ;; `_html-error-body :parts [{:as :reason}…]`),
                                ;; translate through `next-renames` so the
                                ;; outer name wins.
                                (and (map? (:value item))
                                     (:as (:value item))
                                     (not (:literal item)))
                                (let [n (some-> (:as (:value item)) keyword)
                                      slot (when n
                                             (get (:slot-by-fn-name lookups)
                                                  [fid n]))
                                      n' (or (when slot
                                               (get next-renames (:id slot)))
                                             n)]
                                  (when (and n' (not (next-covered n')))
                                    (emit! n')))))
                      :value nil))
                  ;; Env-bindings are synthetic shared computations
                  ;; (`:cond` / `:if` patterns like `:args {:test … :parsed :_…}`
                  ;; where `:parsed` isn't a parent's slot). Their ref-targets
                  ;; still expose deep free args of the outer fn — walk them
                  ;; so `:request` propagates from `:_create-parsed` up to
                  ;; `:process-create-entity`.
                  ;;
                  ;; Skip env-bindings whose `:env-name` is already covered
                  ;; by a same-named direct binding on a HOF-typed slot:
                  ;; `:_pocb-rows-consumer :args {:func {:as :storage-query}}` +
                  ;; `:probe-via-pg :args {:storage-query :pg-query}` produces
                  ;; BOTH a `:ref :is-fn true` direct binding AND a `:ref
                  ;; :is-fn false` env-binding for `:storage-query`. The
                  ;; direct one is the HOF site (walk stops); the env one
                  ;; would walk INTO `:pg-query` and emit `:hsql` as a
                  ;; "free arg" of the top-level fn, polluting HOF
                  ;; alpha-equiv dispatch and dropping the call-site arg.
                  (doseq [env-bnd env-bindings]
                    (when (and (= :ref (:kind env-bnd))
                               (not (:is-fn env-bnd))
                               (:ref-id env-bnd)
                               (not (own-primaries (:env-name env-bnd))))
                      (walk (:ref-id env-bnd) next-covered next-renames))))))]
      (walk fn-id #{} {}))
    @result))


(defn- deep-free-ext-entries*
  "Slot-id-keyed walker. Same traversal shape as
   `deep-free-ext-names*` (inheritance + non-HOF refs + seq items +
   env-binding ref walk; stops at HOF boundaries) but emits
   `{:ext-name :slot-id}` pairs and covers by SLOT-ID rather than by
   name.

   Why slot-id coverage instead of name: the runtime bug at #104 is
   precisely two distinct slots sharing an ext-name colliding through
   the name-keyed `fa`. Coverage by name in the legacy walker is
   correct for the name-keyed runtime (where `fa[:body]` is one cell)
   but wrong for the slot-id-keyed runtime — there `fa[<SID-a>]` and
   `fa[<SID-b>]` are independent cells and BOTH inner consumers must
   surface even when their names collide. Coverage by slot-id is the
   structural truth: only when an outer binding actually takes the
   same slot does the inner consumer get its value supplied.

   Dedup by slot-id: an inner consumer reached via multiple
   inheritance paths still resolves to one slot at runtime — emit it
   once. Different slot-ids with the same ext-name → multiple
   entries; that's the #104 case the new architecture is built to
   express."
  [fn-id lookups]
  (let [result (atom [])
        seen-slots (atom #{})
        visited-fns (atom #{})
        emit! (fn [entry]
                (let [sid (:slot-id entry)]
                  (when (and sid (not (contains? @seen-slots sid)))
                    (swap! seen-slots conj sid)
                    (swap! result conj entry))))]
    (letfn [(rename-name-for
              [bnd-slot-id own-rename-name outer-renames fid]
              ;; Same priority chain as `deep-free-ext-names*`'s
              ;; `translate`: prefer fid's own rename slot-id, else
              ;; the binding's raw slot-id. Returns the outer rename
              ;; name when one applies; nil otherwise.
              (let [own-rename-slot (get (:slot-by-fn-source-slot lookups)
                                         [fid bnd-slot-id])
                    lookup-id (or (:id own-rename-slot) bnd-slot-id)]
                (get outer-renames lookup-id)))
            (walk
              [fid covered-slots outer-renames]
              (when-not (contains? @visited-fns fid)
                (swap! visited-fns conj fid)
                (let [bindings (b/collect-bindings fid lookups)
                      env-bindings (b/collect-env-bindings fid lookups)
                      ;; Cover by slot-id, mirroring
                      ;; `deep-free-ext-names*`'s own-primaries logic
                      ;; but slot-id-keyed:
                      ;;   - `:value`, `:secret-value`, `:seq`,
                      ;;     `:is-fn :ref` cover their root slot.
                      ;;   - `:ref :is-fn false` does NOT cover —
                      ;;     the ref-target reads names from the same
                      ;;     fa the caller passes (see the long
                      ;;     comment in `deep-free-ext-names*`).
                      own-primary-slots
                      (into #{}
                            (comp
                              (remove #(or (= :free (:kind %))
                                           (and (= :ref (:kind %))
                                                (not (:is-fn %)))))
                              (map :slot-id))
                            bindings)
                      env-slot-ids (into #{} (map :slot-id) env-bindings)
                      next-covered (-> covered-slots
                                       (into own-primary-slots)
                                       (into env-slot-ids))
                      ;; Outer renames win — only add fid's own
                      ;; renames for keys the outer doesn't already
                      ;; carry. Same priority as the name-keyed
                      ;; walker.
                      next-renames (merge-keep-outer
                                     outer-renames
                                     (own-rename-chain-map fid lookups))]
                  (doseq [bnd bindings]
                    (case (:kind bnd)
                      :free (let [sid (:slot-id bnd)]
                              (when-not (next-covered sid)
                                (emit! {:ext-name (or (rename-name-for
                                                        sid nil
                                                        next-renames fid)
                                                      (:ext-name bnd))
                                        :slot-id sid})))
                      :ref  (when-not (:is-fn bnd)
                              (walk (:ref-id bnd) next-covered next-renames))
                      :seq  (doseq [item (:items bnd)]
                              (cond
                                (:ref-fn-id item)
                                (walk (:ref-fn-id item) next-covered next-renames)

                                (and (map? (:value item))
                                     (:as (:value item))
                                     (not (:literal item)))
                                (let [n (some-> (:as (:value item)) keyword)
                                      slot (when n
                                             (get (:slot-by-fn-name lookups)
                                                  [fid n]))
                                      sid (:id slot)
                                      ext (or (when slot
                                                (get next-renames sid))
                                              n)]
                                  (when (and sid
                                             ext
                                             (not (next-covered sid)))
                                    (emit! {:ext-name ext :slot-id sid})))))
                      :value nil
                      :secret-value nil))
                  ;; Env-binding ref-walk mirrors
                  ;; `deep-free-ext-names*` — synthetic shared
                  ;; computations still propagate free args of their
                  ;; ref-targets, except where a same-named direct
                  ;; HOF binding already consumes the slot.
                  (doseq [env-bnd env-bindings]
                    (when (and (= :ref (:kind env-bnd))
                               (not (:is-fn env-bnd))
                               (:ref-id env-bnd)
                               (not (own-primary-slots (:slot-id env-bnd))))
                      (walk (:ref-id env-bnd) next-covered next-renames))))))]
      (walk fn-id #{} {}))
    @result))


(defn- find-slot-id-in-tree
  "Find the slot-id corresponding to free-arg name `ext-name` reachable
   from `fn-id` via inheritance + non-HOF refs + seq items. Returns the
   slot-id or nil. `:is-fn` refs are a boundary — slots inside a HOF
   lambda's body don't bubble up as F-tree slots."
  [fn-id ext-name {:keys [slot-by-fn-name] :as lookups}]
  (let [visited (atom #{})]
    (letfn [(walk
              [fid]
              (when-not (contains? @visited fid)
                (swap! visited conj fid)
                (or (some (fn [chain-fid]
                            (some-> (get slot-by-fn-name [chain-fid ext-name])
                                    :id))
                          (l/inheritance-chain* fid lookups))
                    (some (fn [bnd]
                            (case (:kind bnd)
                              :ref (when-not (:is-fn bnd) (walk (:ref-id bnd)))
                              :seq (some (fn [item]
                                           (when-let [r (:ref-fn-id item)]
                                             (walk r)))
                                         (:items bnd))
                              nil))
                          (b/collect-bindings fid lookups)))))]
      (walk fn-id))))


(defn- inheritance-descendants
  "Set of fn-ids whose inheritance chain reaches `f-fn-id` — F itself
   plus every fn that has F (transitively) as a parent. These are the
   fns whose runtime free-args propagate back into F's body when they
   are invoked through F's chain."
  [f-fn-id {:keys [fn-map]}]
  (let [parent->children (reduce (fn [acc [id f]]
                                   (reduce (fn [a pid]
                                             (update a pid (fnil conj #{}) id))
                                           acc
                                           (or (:parent-ids f) [])))
                                 {}
                                 fn-map)
        out (atom #{f-fn-id})]
    (letfn [(walk
              [fid]
              (doseq [child (get parent->children fid #{})]
                (when-not (contains? @out child)
                  (swap! out conj child)
                  (walk child))))]
      (walk f-fn-id))
    @out))


(defn- slot-bound-by?
  "True iff some fn in `fn-ids` has a value/ref/list-append binding
   targeting `slot-id`. Pure rename-to declarations don't count — we
   ask whether someone supplies a value for the slot at runtime."
  [slot-id fn-ids {:keys [binding-by-fn-slot]}]
  (boolean
    (some (fn [fid]
            (when-let [b (get binding-by-fn-slot [fid slot-id])]
              (or (b/value-binding? b)
                  (b/ref-binding? b)
                  (b/list-binding? b))))
          fn-ids)))


(defn- slot-structural-call-site-args
  "Returns the SET of arg-names declared in the slot's structural
   `[:fn {ARGS} RET …]` type. Mirrors the effective-type resolution
   in `compile.bindings/fn-typed-slot?` — prefer the binding's
   `:type-override-fn-id`, then walk the chain for any override, then
   fall back to the slot's own `:type-fn-id`. Returns nil only for
   structurally-malformed `:fn`-typed slots (bare `:fn` keyword
   constraint instead of `[:fn {ARGS} RET]`) — `hof-lambda-params`
   rejects those at compile time with a clear error.

   Closure-capture semantics (docs/CLOSURE_CAPTURE.md): R's free args
   INSIDE the returned set are call-site lambda-params, R's free args
   OUTSIDE are captured at wrap time."
  [slot-id b-row f-fn-id {:keys [slot-map fn-map binding-by-fn-slot] :as lookups}]
  (let [override (or (:type-override-fn-id b-row)
                     (some (fn [fid]
                             (when-let [b (get binding-by-fn-slot [fid slot-id])]
                               (:type-override-fn-id b)))
                           (l/inheritance-chain* f-fn-id lookups)))
        type-fn-id (or override (some-> (get slot-map slot-id) :type-fn-id))
        constraint (some-> (get fn-map type-fn-id) :constraint)]
    (when (and (vector? constraint) (= :fn (first constraint)))
      (set (keys (or (second constraint) {}))))))


(defn- global-env-binding-names
  "Set of every env-binding `:env-name` across the WHOLE graph,
   memoised on `lookups`. Used by `alpha-equiv-lambda-params` to spot
   names a wrap-time-captured `fa` is LIKELY to already carry — any
   env-binding name a fn somewhere declares can flow into `fa` via
   the closure-capture propagation from an upstream caller.

   Names found in this set are at risk of collision when chosen as a
   lambda-param: the wrap's `(merge fa lambda-args)` would overwrite
   the captured value (request, env-binding callable, etc.) with the
   per-call lambda value. The full hof-lambda-params policy decides
   what to do with the risk based on the slot's iteration semantics
   (`:item`-style iteration wants the override; `:arg`-style generic
   one-shot does NOT)."
  [{:keys [fn-map global-env-cache] :as lookups}]
  (let [compute (fn []
                  (set (mapcat #(keep :env-name
                                      (b/collect-env-bindings % lookups))
                               (keys fn-map))))]
    (if-let [cache global-env-cache]
      (or @cache (let [r (compute)] (reset! cache r) r))
      (compute))))


(defn alpha-equiv-lambda-params
  "Resolves the single lambda-param of a 1-arg HOF slot by picking
   R's surviving free-arg name AFTER subtracting everything F
   provides through its own bindings, env-bindings (synthetic slots
   like `:_…-validation :parent :cond :args {… :parsed :_…}`), or
   inheritance chain.

   Needed because graphden's 1-arg HOF slots use POSITIONAL CONVENTION
   names in their structural type — `:item` for collection iteration
   (`:map`, `:filter`, `:some`), `:arg` for general invocation
   (`:invoke`, `:call`), etc. — while sub-fns keep their DOMAIN names
   (`:some?`'s `:value`, `:str-upper`'s `:string`, `:read-resource`'s
   `:path`). Forcing sub-fns to rename their args to the slot's
   conventional name would break their callsite contract everywhere
   else they're used; forcing each HOF callsite to insert an explicit
   `{:value {:as :item}}` rename would be boilerplate. Alpha-
   equivalent positional unification is the right primitive for the
   convention.

   `hof-lambda-params` calls this only when the names DIVERGE — when
   they match, the structural path is taken directly (faster, no
   binding scan needed)."
  [r-fn-id f-fn-id lookups]
  (let [r-frees (deep-free-ext-names r-fn-id lookups)
        f-bindings (b/collect-bindings f-fn-id lookups)
        ;; Env-bindings cover bindings on F whose slot isn't one of
        ;; F's parent's root slots — synthetic slots created by F's
        ;; own `:args` entries that name something the parent
        ;; base-fn doesn't declare (`:_…-validation :parent :cond
        ;; :args {:clauses […] :parsed :_…}` — `:parsed` binding lives
        ;; here). At runtime they're merged into free-args; for HOF
        ;; wrap-time-capture they must show up as captured by name
        ;; too, otherwise a HOF lambda's free arg named the same
        ;; would wrongly become a lambda-param.
        f-env-bindings (b/collect-env-bindings f-fn-id lookups)
        f-own-names (into (into #{} (map :ext-name) f-bindings)
                          (map :env-name)
                          f-env-bindings)
        f-deep (set (deep-free-ext-names f-fn-id lookups))
        static-captured (into f-own-names f-deep)
        relatives (into (set (l/inheritance-chain* f-fn-id lookups))
                        (inheritance-descendants f-fn-id lookups))
        env-captured (into #{}
                           (filter (fn [n]
                                     (when-let [sid (find-slot-id-in-tree
                                                      r-fn-id n lookups)]
                                       (slot-bound-by? sid relatives lookups))))
                           r-frees)
        captured (into static-captured env-captured)]
    (vec (remove captured r-frees))))


(defn hof-lambda-params
  "Lambda-param names of HOF target `r-fn-id` when invoked from
   `f-fn-id` through the binding `b-row`. Dispatched on the slot's
   structural `[:fn {ARGS} RET]` shape (closure-capture;
   docs/CLOSURE_CAPTURE.md):

   - 0-arg slot (`[:fn {} a]`) → `[]`
     Variadic-ignore wrap; R's free args are all captured at wrap
     time (`:future :body`, `:loop-until-interrupted :body`).
   - 1-arg slot (`[:fn {:x T} a]`) → structural name when R declares
     it, alpha-equivalent positional unification when not
     If R's free args include the slot's structural name, return
     `[structural-name]` directly (the fast path — sub-fn was
     written to match the slot contract, e.g. `:try`'s `:on-throw`
     typed `[:fn {:exception :any} a]` with `_rollback` declaring an
     `:exception` arg). Otherwise call `alpha-equiv-lambda-params`,
     which picks the lambda-param name from R's non-captured frees —
     covers `:filter :pred :some?` where the slot's positional
     `:item` doesn't match `:some?`'s domain-named `:value`.
   - 2+-arg slot (`[:fn {:a A :b B} ret]`) → R's free args matching
     the slot's structural ARGS by NAME (map-callable;
     `:wrap-middleware :handler {:request _ :next-handler _}`).

   Bare `:fn` keyword slots are REJECTED with a sync-time error —
   every HOF slot must declare its structural shape.

   `hof-wrap` picks its call shape from `(count lambda-params)`:
   0 / 1 / N → variadic / single-arg / map-callable."
  [r-fn-id slot-id b-row f-fn-id lookups]
  (let [structural-args (slot-structural-call-site-args slot-id b-row f-fn-id
                                                        lookups)]
    (cond
      ;; Bare `:fn` keyword slot — no structural shape to constrain
      ;; on. Falls back to the alpha-equiv heuristic: a deep-free of
      ;; R is a lambda-param iff nothing on F's chain supplies a
      ;; value for it; everything else is captured at wrap time.
      ;; Used by the closure-capture acceptance test and any other
      ;; slot whose author opted out of structural typing.
      (nil? structural-args)
      (alpha-equiv-lambda-params r-fn-id f-fn-id lookups)

      ;; 0-arg structural slot — variadic-ignore wrap; everything
      ;; captured (cron / future / loop-until-interrupted case).
      (zero? (count structural-args))
      []

      ;; 1-arg structural slot — structural name when R declares it,
      ;; alpha-equivalent positional unification otherwise.
      ;;
      ;; Iteration vs one-shot policy (#52-followup): the structural
      ;; name `:arg` is the generic-positional convention for one-shot
      ;; HOF slots (`:call`, `:invoke`, `:assoc-fn`, route handlers).
      ;; All OTHER structural names (`:item`, `:value`, `:pair`,
      ;; `:existing`, `:acc`, `:request`, …) declare per-element or
      ;; domain-specific iteration semantics where the lambda-arg IS
      ;; meant to override fa.
      ;;
      ;; For one-shot slots whose callee doesn't use `:arg`, if the
      ;; alpha-equiv fallback finds ONLY names that would collide
      ;; with `fa` keys (every candidate is somewhere an env-binding
      ;; name), return `[]` — variadic-ignore wrap — so reitit's
      ;; `(handler request)` call doesn't overwrite a captured
      ;; `:storage-query → :pg-query` callable with the request map.
      ;; Real-world repro: `/api/branches` and `/api/services` whose
      ;; handler chains have only `[:storage-query]` as r-frees.
      (= 1 (count structural-args))
      (let [structural-name (first structural-args)
            r-frees (deep-free-ext-names r-fn-id lookups)]
        (if (some #{structural-name} r-frees)
          [structural-name]
          (let [a (alpha-equiv-lambda-params r-fn-id f-fn-id lookups)
                one-shot? (= :arg structural-name)
                global-env (when one-shot? (global-env-binding-names lookups))]
            (if (and one-shot?
                     (seq a)
                     (every? global-env a))
              []
              a))))

      ;; Map-callable structural slot — names must match. Sub free
      ;; args outside structural-args are captured.
      :else
      (filterv structural-args (deep-free-ext-names r-fn-id lookups)))))


(defn build-ref-renames
  "For ref R called from F, produce `{R-ext-name → F-ext-name}` to
   rewrite F's free-arg map before handing it to R. If F renames a
   slot that R exposes as a free arg, F supplies the value under the
   renamed name and we translate back to R's name at the call.

   Picks up two distinct rename mechanisms in priority order:

   - **Same-name binding rename** — F's collected bindings include a
     `:free` binding whose `:base-name` (R-side) matches one of R's
     deep frees AND whose `:ext-name` (F-side) is different. This
     covers the legacy `{:as ...}` rename pattern where F directly
     binds a slot that R also exposes under the same name (e.g.
     `:_pocb-rows-consumer.func {:as :storage-query}`).

   - **Cross-tree slot-id rename** — for any R-free without a binding
     match, find R's slot-id for that name (walking R's inheritance
     + non-HOF ref-targets + seq items), then ask F's inheritance
     chain whether it owns a renamed-view slot whose
     `:source-slot-id` FK points at THAT slot. This covers the case
     where F renames a slot that lives on a fn reached via ref-target
     from R (e.g. `:html-error-response.value :_html-error-body`
     where `:reason` is exposed by a positional `{:as :reason}` deep
     in `:_html-error-body`'s body and a descendant binds `:reason
     {:as :fn-in-use-reason}`). The binding-walking implementation
     alone misses this because `collect-bindings` doesn't traverse
     ref-targets."
  [r-fn-id f-fn-id lookups]
  (let [r-frees (deep-free-ext-names r-fn-id lookups)
        f-bindings (b/collect-bindings f-fn-id lookups)
        ;; Same-name binding renames.
        binding-renames (into {}
                              (keep (fn [bnd]
                                      (let [b-name (:base-name bnd)
                                            e-name (:ext-name bnd)]
                                        (when (and (= :free (:kind bnd))
                                                   (not= b-name e-name)
                                                   (some #{b-name} r-frees))
                                          [b-name e-name]))))
                              f-bindings)
        ;; Cross-tree slot-id renames for any R-free not yet covered.
        cross-tree-renames (into {}
                                 (keep (fn [r-name]
                                         (when-not (contains? binding-renames r-name)
                                           (when-let [slot-id (find-slot-id-in-tree
                                                                r-fn-id r-name lookups)]
                                             (let [f-name (l/rename-for-slot
                                                            f-fn-id slot-id lookups)]
                                               (when (and f-name (not= f-name r-name))
                                                 [r-name f-name]))))))
                                 r-frees)]
    (merge binding-renames cross-tree-renames)))


(defn apply-renames
  "Apply `{R-name → F-name}`: for each entry, expose F's value under
   R's name and drop the F-name key. Extra keys pass through."
  [free-args renames]
  (reduce-kv (fn [acc r-name f-name]
               (if (contains? acc f-name)
                 (-> acc
                     (assoc r-name (get acc f-name))
                     (dissoc f-name))
                 acc))
             free-args
             renames))


(defn compute-rename-aliases
  "Compile-time: for each own rename slot of `fn-id` whose source-
   slot-id points OUTSIDE the root's slot set, emit one alias per
   chain link `{chain-name K, rename-name R}`. At runtime
   `apply-rename-aliases` uses these to copy `free-args[rename-name]`
   into `free-args[chain-name]` so a downstream ref-walk reading the
   inner ext-name (e.g. `:branch-row`) finds the caller-supplied
   value under the renamed outer name (e.g. `:item`).

   Worked example: `:_list-branches-as-json-item :args {:branch-row
   {:as :item}}`. Rename `:item` source-chain reaches
   `_branch-row-id.branch-row` (name `\"branch-row\"`) and `:get.coll`
   (name `\"coll\"`). Aliases emitted: `:branch-row → :item` and
   `:coll → :item`. Every inner walker reading `:branch-row` (or
   `:coll`) now sees the caller-supplied `:item` value."
  [fn-id {:keys [fn-slots-by-fn slot-map] :as lookups}]
  (let [root-ids (into #{}
                       (map :id)
                       (or (l/root-slots fn-id lookups) []))]
    (vec
      (for [fs (get fn-slots-by-fn fn-id [])
            :let [s (get slot-map (:slot-id fs))
                  src (:source-slot-id s)]
            :when (and src (not (contains? root-ids src)))
            chain-sid (chain-source-slot-ids src slot-map)
            :let [chain-slot (get slot-map chain-sid)
                  chain-name (some-> chain-slot :name keyword)
                  rename-name (some-> s :name keyword)]
            :when (and chain-name rename-name)]
        {:chain-name chain-name :rename-name rename-name}))))


(defn apply-rename-aliases
  "Runtime: apply `compute-rename-aliases` output to `free-args`.
   For each alias, copy `free-args[rename-name] → free-args[
   chain-name]` UNLESS chain-name is already supplied by the caller
   (explicit caller-provided binding wins). Empty `aliases`
   short-circuits to the input map — the common case for fns
   without own rename slots."
  [free-args aliases]
  (if (empty? aliases)
    free-args
    (reduce (fn [acc {:keys [chain-name rename-name]}]
              (if (and (contains? acc rename-name)
                       (not (contains? acc chain-name)))
                (assoc acc chain-name (get acc rename-name))
                acc))
            free-args
            aliases)))
