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


(defn deep-free-ext-names
  "Memoised wrapper around `deep-free-ext-names*` — same fn-id +
   lookups always produce the same answer, and the underlying walk
   is O(reachable-graph). compile-all calls this MANY times per
   compile-fn (once per ref-binding via `build-ref-renames`); without
   memoisation the worst case is quadratic in graph size."
  [fn-id {:keys [deep-frees-cache] :as lookups}]
  (if-let [cache deep-frees-cache]
    (or (get @cache fn-id)
        (let [r (deep-free-ext-names* fn-id lookups)]
          (swap! cache assoc fn-id r)
          r))
    (deep-free-ext-names* fn-id lookups)))


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
              [entry name-key outer-renames]
              (if-let [r (get outer-renames (:slot-id entry))]
                (assoc entry name-key r)
                entry))
            (walk
              [fid covered outer-renames]
              (when-not (contains? @visited-fns fid)
                (swap! visited-fns conj fid)
                (let [bindings (mapv #(translate % :ext-name outer-renames)
                                     (b/collect-bindings fid lookups))
                      env-bindings (mapv #(translate % :env-name outer-renames)
                                         (b/collect-env-bindings fid lookups))
                      own-primaries (into #{}
                                          (comp (remove #(= :free (:kind %)))
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
      (= 1 (count structural-args))
      (let [structural-name (first structural-args)
            r-frees (deep-free-ext-names r-fn-id lookups)]
        (if (some #{structural-name} r-frees)
          [structural-name]
          (alpha-equiv-lambda-params r-fn-id f-fn-id lookups)))

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
