(ns graphden.crud.entities.tighten
  "Tighten-fn-effects CRUD — narrows an `[:fn args ret]` slot type to
   `[:fn args ret #{eff-set}]` by writing a new anonymous fn-row whose
   `:constraint` carries the narrower shape, then pointing the
   binding's `:type-override-fn-id` at it. Extracted from
   `crud/entities` so the parent ns stays focused on generic CRUD;
   `apply-tighten-core` is re-exported through `crud.entities` so
   `web/crud/impls.clj` can call it via the `entities/` alias.

   Phase 8 carved out a 4-arity `[:fn args ret #{eff-set}]` form so a
   slot whose callable should stay pure (or only do certain effects)
   can REJECT impure callbacks at sync time. There was no UI to set
   the constraint, so it lived only in EDN-side declarations. This
   endpoint exposes it: the editor sends `{effects: [\"io\" \"db\"]}`,
   the server constructs the 4-arity constraint, dedupes via
   deterministic `anonymous-fn-id` (same shape collapses to one row),
   and writes the binding's `:type-override-fn-id`.

   Subtype safety: the new constraint must be a SUBTYPE of the
   current effective fn-type. Tightening from a 3-arity (no eff
   constraint = any effects allowed) to a 4-arity is always a
   narrowing; tightening across two 4-arities requires the new
   eff-set ⊆ old. `subtype?` enforces this and we surface the
   rejection as a 400."
  (:require
    [clojure.set]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


(defn commit-tighten!
  "Helper for `tighten-effects-impl!` — performs the actual write
   (anon fn-row create + binding update) once the safety checks have
   passed. Pulled out so the impl's let-and-cond chain stays
   readable."
  [storage binding-id b new-c _effects-vec]
  (let [hash-hex (records/digest-hex "SHA-1" (pr-str new-c))
        new-id (records/anonymous-fn-id hash-hex)
        pre-override (:type-override-fn-id b)
        ;; Track whether WE materialised the anon fn-row this call. A
        ;; pre-existing row is a shared dedup target (some other binding
        ;; may already point at it), so it must survive a secret-flow
        ;; rejection here; a freshly-created one is rolled back with
        ;; the binding revert.
        created? (nil? (sp/read-entity storage :fn new-id))]
    ;; Find or create. Storage upsert is the natural fit — same
    ;; id ⇒ same row, no orphan duplicates.
    (when created?
      (sp/create-entity storage :fn
                        {:id new-id
                         :name nil
                         :namespace-id nil
                         :parent-ids []
                         :base-fn-id nil
                         :element-fn-id nil
                         :return-type-fn-id nil
                         :anonymous-hash hash-hex
                         :constraint new-c}))
    (sp/update-entity storage :binding binding-id
                      {:type-override-fn-id new-id})
    ;; Aggregate type-check on the owning fn. The bound-callable
    ;; effect check above is the primary guard; this catches
    ;; whatever else `check-fn-def!` evaluates (return-type
    ;; subtype, deeper structural unification, etc.).
    ;; Error-tolerance Phase 2: the new override is KEPT even when
    ;; the aggregate check fails — the guard records the failure in
    ;; the per-branch diagnostics store (and a later fixing write
    ;; clears it), and the response surfaces it additively as
    ;; `:type-warnings` on the success payload. The subtype-safety
    ;; pre-checks in `tighten-fn-type-impl!` (widening rejection,
    ;; bound-callable effect escape) stay hard rejections — they
    ;; guard the tighten operation's own contract, not fn validity.
    ;; SECURITY CARVE-OUT: a SECRET-involving aggregate failure keeps
    ;; the pre-Phase-2 shape — revert the override (and the anon
    ;; fn-row we just materialised), no store record, hard 400.
    (let [post-rej (tc/type-check-fn-after-mutation! storage (:fn-id b)
                                                     {:reject-secret? true})]
      (if (:secret? post-rej)
        (do (sp/update-entity storage :binding binding-id
                              {:type-override-fn-id pre-override})
            (when created?
              (sp/delete-entity storage :fn new-id))
            {:status 400
             :reason (str "Tightening rejected by post-write type-check "
                          "(secret-flow violation): " (:reason post-rej))})
        {:status 200
         :result (cond-> {:type-override-fn-id new-id
                          :constraint new-c
                          :fn-id (:fn-id b)}
                   post-rej (assoc :type-warnings [(:diagnostic post-rej)]))}))))


(defn tighten-fn-type-impl!
  "Compute a narrower fn-type constraint by selectively replacing
   `args`, `ret`, or `effects` from the current effective type.
   `delta` is `{:args {…} :ret T :effects [\"io\" …]}` — any subset.
   Defaults preserve the current value: 3-arity gets a 4th element
   only when `:effects` is supplied, and `:args` / `:ret` keep
   whatever the current shape carries when omitted.

   Subtype-checks the new constraint against the current; rejects
   widenings. Then runs the bound-callable safety check (effects
   only — narrower args / ret don't introduce new escape paths the
   way effects do, and the post-write `check-fn-def!` catches deeper
   structural mismatches)."
  [storage binding-id delta]
  (let [b (sp/read-entity storage :binding binding-id)]
    (cond
      (nil? b)
      {:status 404 :reason "Binding not found"}

      :else
      (let [slot (sp/read-entity storage :slot (:slot-id b))
            cur-tfn-id (or (:type-override-fn-id b) (:type-fn-id slot))
            cur-tfn (when cur-tfn-id (sp/read-entity storage :fn cur-tfn-id))
            cur-c (:constraint cur-tfn)]
        (cond
          (or (not (vector? cur-c)) (not= :fn (first cur-c)))
          {:status 400
           :reason (str "Slot's effective type is not an fn-type ("
                        (pr-str cur-c) "); can't tighten.")}

          :else
          (let [cur-args (or (nth cur-c 1) {})
                cur-ret (nth cur-c 2)
                cur-eff (when (= 4 (count cur-c)) (nth cur-c 3))
                {:keys [args ret effects]} delta
                ;; Args delta is a per-name override map. Merge so
                ;; unmentioned arg names keep their current type.
                new-args (if (map? args)
                           (merge cur-args (types-api/json->type args))
                           cur-args)
                new-ret (if (some? ret)
                          (types-api/json->type ret)
                          cur-ret)
                new-eff (cond
                          (some? effects) (into #{} (map keyword) effects)
                          cur-eff         cur-eff
                          :else           nil)
                new-c (cond-> [:fn new-args new-ret] new-eff (conj new-eff))
                ok? (types/subtype? new-c cur-c)]
            (if-not ok?
              {:status 400
               :reason (str "Proposed type " (pr-str new-c)
                            " is not a narrowing of " (pr-str cur-c)
                            " — every component (args / ret / effects)"
                            " must be a subtype of the current value.")}
              ;; Bound-callable effect check — same as the
              ;; effect-only path. Args / ret narrowings don't
              ;; introduce new escape paths beyond what
              ;; `check-fn-def!` covers.
              (let [eff-set (or new-eff #{})
                    ref-fn-id (:ref-fn-id b)
                    ref-row (when ref-fn-id (sp/read-entity storage :fn ref-fn-id))
                    ref-info (some-> (:id ref-row)
                                     (registry/rich-type-of-id))
                    ref-effects (or (:effects ref-info) #{})
                    escapes (when (and (some? new-eff) (seq ref-effects))
                              (clojure.set/difference (set ref-effects) eff-set))]
                (if (seq escapes)
                  {:status 400
                   :reason (str "Bound fn `" (:name ref-row) "`"
                                " produces effects " (vec (sort escapes))
                                " that the requested constraint "
                                (vec (sort eff-set))
                                " forbids. Either widen the effect set"
                                " or rebind to a fn with effects ⊆ "
                                (vec (sort eff-set)) ".")}
                  (commit-tighten! storage binding-id b new-c nil))))))))))


(defn tighten-effects-impl!
  "Backwards-compatible thin wrapper — `tighten-fn-type-impl!` with
   only the `:effects` delta filled in. Tests load this symbol
   directly; production callers go through the form-driven defbase."
  [storage binding-id effects-vec]
  (tighten-fn-type-impl! storage binding-id {:effects effects-vec}))


(defn apply-tighten-core
  "§3.3 atomic core of tighten-fn-effects: narrows the fn-typed
   binding's effective type. Returns `{:status :reason :result}` from
   `tighten-fn-type-impl!` unchanged — the outer graph dispatches on
   `:status` and runs invalidate + response."
  [parsed ctx]
  (tighten-fn-type-impl! (request/require-storage ctx)
                         (:binding-id parsed) (:delta parsed)))
