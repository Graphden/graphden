(ns graphden.crud.entities.seq
  "Sequence-binding CRUD — the append / remove / update flows for
   `binding-list-item` rows. The `apply-*-core` / `load-*` / `find-*` helpers
   are re-exported through `crud.entities` so `web/crud/impls.clj` can
   call them via the `entities/` alias.

   Each `apply-*-core` is a §3.3 atomic write unit — the synthetic-
   binding materialise + position-compute + pre-rej triplet (in
   append) shares a binding-id that can't be split across graph
   nodes without race risk. The graph composition around each
   primitive dispatches on the returned shape and runs invalidate +
   response."
  (:require
    [clojure.math :as math]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.package-guard :as pkg-guard]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.validation :as validation]
    [graphden.storage.protocol.core :as sp]))


(defn- post-write-rej
  "Error-tolerance Phase 3 (Gap A): the same post-write aggregate
   type-check the binding create/update cores run — records / clears
   the owner's per-branch diagnostic and returns the full rej map
   (or nil when clean). A `:secret? true` rej is the SECURITY
   CARVE-OUT (secret-flow violation, NOT recorded in the store) —
   the caller must roll the write back and return `{:error …}`;
   non-secret rejs surface additively as `:type-warnings
   [(:diagnostic rej)]` on the success envelope."
  [storage owner-fn-id]
  (when owner-fn-id
    (tc/type-check-fn-after-mutation! storage owner-fn-id
                                      {:reject-secret? true})))


(defn find-sequence-binding
  "Find the binding row that owns the sequence items for `fn-id`. A fn
   that has at least one sequence-typed slot may have an own binding
   on it (with or without `:list-append`); when it doesn't yet, the
   first append creates one. Returns either the existing binding row
   or a synthetic `{:fn-id … :slot-id …}` placeholder pinning where
   the binding will be created.

   Resolves entirely against the in-memory graph cache — five-table
   reads collapse to one cache hit per editor sequence-edit click."
  [ctx fn-id]
  (let [graph (types-api/cached-or-load-graph ctx)
        fns-by-id (into {} (map (juxt :id identity)) (:fns graph))
        slots-by-id (into {} (map (juxt :id identity)) (:slots graph))
        fn-slots-by-fn (group-by :fn-id (:fn-slots graph))
        bindings-by-fn-slot (into {}
                                  (map (fn [b] [[(:fn-id b) (:slot-id b)] b]))
                                  (:bindings graph))
        ;; Resolve the `:sequence` base-fn's id ONCE (name→id at the
        ;; boundary), then test each slot's `:type-fn-id` by ID — internal
        ;; dispatch keys on the stable id, not a per-slot name compare.
        sequence-type-fn-id (some (fn [f] (when (= "sequence" (:name f)) (:id f)))
                                  (:fns graph))
        sequence?
        (fn [slot]
          (and sequence-type-fn-id
               (= sequence-type-fn-id (:type-fn-id slot))))
        ;; Walk parent chain in memory.
        chain (loop [acc [], seen #{}, queue [fn-id]]
                (if (empty? queue)
                  acc
                  (let [fid (first queue)
                        rest-q (vec (rest queue))]
                    (if (or (nil? fid) (contains? seen fid))
                      (recur acc seen rest-q)
                      (let [f (get fns-by-id fid)
                            pids (->> (:parent-ids f) (remove nil?) (remove seen))]
                        (recur (conj acc fid) (conj seen fid)
                               (into rest-q pids)))))))
        sequence-slot
        (some (fn [fid]
                (some (fn [fs]
                        (let [s (get slots-by-id (:slot-id fs))]
                          (when (sequence? s) s)))
                      (get fn-slots-by-fn fid [])))
              chain)]
    (when sequence-slot
      (or (get bindings-by-fn-slot [fn-id (:id sequence-slot)])
          {:fn-id fn-id :slot-id (:id sequence-slot) :synthetic true}))))


(defn resolve-sequence-payload
  "Parses a sequence-op JSON body into the `binding-list-item` shape.
   Body shapes:
     {\"ref\":  \"fn-uuid-string\"}
     {\"ref-name\": \"my-fn\"}
     {\"value\": <any JSON>}

   A `\":foo\"`-shaped value string is the wire form of a keyword
   literal (JSON has no keyword type) — restore the keyword and set
   `:literal true`, matching how `records.clj` stores a fn-def's
   `{:value :kw}` item. Without the flag a read would re-emit the
   keyword colon-stripped and the editor would mis-type it as plain
   text. (The storage `:literal` column disambiguates keyword
   literals from string text on read-back.)"
  [storage body]
  (cond
    (contains? body :ref)
    ;; `:ref` is UNTRUSTED client JSON — a non-UUID string (or a
    ;; number / nested object the JSON decoder produced) must not
    ;; bubble a bare `IllegalArgumentException` up as a 500. Coerce
    ;; through the soft parser and raise a mapped 400 on failure,
    ;; matching the `:ref-name`-not-found branch below.
    {:ref-fn-id (or (request/parse-uuid-or-clear (:ref body))
                    (throw (ex-info (str "Invalid :ref UUID: " (pr-str (:ref body)))
                                    {:type :validation-error/invalid-uuid
                                     :ref (:ref body)})))}

    (contains? body :ref-name)
    (if-let [target (first (sp/query-entities storage :fn {:name (:ref-name body)}))]
      {:ref-fn-id (:id target)}
      (throw (ex-info (str "Fn not found by name: " (:ref-name body))
                      {:type :sequence-op/fn-not-found :ref-name (:ref-name body)})))

    (contains? body :value)
    (let [v (:value body)]
      (if (and (string? v) (> (count v) 1) (str/starts-with? v ":"))
        {:value (keyword (subs v 1)) :literal true}
        {:value v}))

    :else
    (throw (ex-info "Sequence op body requires :ref, :ref-name, or :value"
                    {:type :sequence-op/invalid-body :body body}))))


(defn find-seq-append-binding
  "Read-only sequence-binding resolution for the C3 graph. Returns
   `find-sequence-binding`'s result (existing binding | synthetic
   placeholder | nil) — does NOT materialize a synthetic binding; the
   `:_seq-append-apply` graph path runs that write only after every
   guard passes."
  [parsed ctx]
  (when-let [fn-id (:fn-id parsed)]
    (find-sequence-binding ctx fn-id)))


(defn- materialize-synthetic-binding!
  "The `:list-append` host binding for a sequence slot that has none yet
   — reusing the row if one already exists.

   `find-sequence-binding` answers from the in-memory graph cache, so a
   binding written outside that cache's last refresh reads as absent and
   a blind create hits the per-branch uniqueness check (a 500 the caller
   can do nothing about). Ask storage before creating, and once more if
   the create loses a race."
  [storage {:keys [fn-id slot-id] :as data}]
  (or (first (sp/query-entities storage :binding {:fn-id fn-id :slot-id slot-id}))
      (try
        (sp/create-entity storage :binding data)
        (catch Exception e
          (or (first (sp/query-entities storage :binding
                                        {:fn-id fn-id :slot-id slot-id}))
              (throw e))))))


(defn- shift-items!
  "Shift the `:position` of every row in `items` by `delta`, writing
   in an order that can never collide with a not-yet-moved sibling
   (descending positions for a +1 shift, ascending for -1)."
  [storage items delta]
  (doseq [it (sort-by :position (if (pos? delta) #(compare %2 %1) compare)
                      items)]
    (sp/update-entity storage :binding-list-item (:id it)
                      {:position (+ (:position it) delta)})))


(defn- requested-insert-pos
  "The validated optional `:position` of an append body — a
   non-negative int, or nil for a plain append-at-end. Returns
   `{:error …}` for a present-but-malformed value."
  [body]
  (let [p (:position body)]
    (cond
      (nil? p) nil
      ;; JSON numbers may decode as Double — accept a whole one.
      (and (number? p)
           (== p (math/floor (double p)))
           (>= p 0))
      {:pos (long p)}
      :else {:error "Optional :position must be a non-negative integer"})))


(defn apply-seq-append-core
  "§3.3 atomic core of sequence-append: materialise synthetic binding
   if needed, compute the position, run pre-write validation, write
   the binding-list-item row. An optional body `:position` turns the
   append into an INSERT — every existing item at that position or
   later shifts +1 first (descending write order, so the per-write
   position-uniqueness check can't collide), then the new row takes
   the freed position. Returns `{:created <item-id> :position
   <int> :fn-id <fn-id>}` on success (plus `:type-warnings
   [<diagnostic> …]` when the item landed but the owning fn now fails
   the aggregate type-check — error-tolerance Phase 3) or `{:error
   <reason>}` on pre-write validation rejection OR on a secret-flow
   type failure (hard reject: item + shifts + synthetic binding rolled
   back — the security carve-out). The graph composition
   around this primitive dispatches on the returned shape and runs
   invalidate + response.

   The synthetic-binding materialise + position-compute + pre-rej
   triplet share a binding-id that can't be split across graph nodes
   without race risk — hence §3.3."
  [parsed seq-binding ctx]
  (let [storage (request/require-storage ctx)
        fn-id (:fn-id parsed)
        body  (:body parsed)
        req-pos (requested-insert-pos body)
        synthetic? (:synthetic seq-binding)
        synth-data (when synthetic?
                     {:fn-id (:fn-id seq-binding)
                      :slot-id (:slot-id seq-binding)
                      :list-append true})
        ;; The synthetic `:list-append` binding used to be created with a
        ;; DIRECT `sp/create-entity`, bypassing `write-rej` (list-closed /
        ;; terminal guards). Validate it FIRST so an append onto a sealed
        ;; slot is rejected before anything is written.
        synth-rej (when synthetic? (validation/write-rej storage :binding synth-data))
        ;; Appending onto a PACKAGE-SYNCED fn's own chain mutates every
        ;; descendant in the installation and is reverted by the next
        ;; sync — refuse it here, whichever fn the "+" click landed on.
        pkg-reason (pkg-guard/write-rejection storage :binding {:fn-id fn-id})]
    (cond
      pkg-reason {:error pkg-reason}
      (:error req-pos) req-pos
      synth-rej {:error (:reason synth-rej)}
      :else
      ;; Parse the BODY before writing anything. It used to be parsed after
      ;; the synthetic host binding was created, so a malformed body threw
      ;; past the rollback and left an empty `:list-append` binding on the
      ;; slot — and the next (well-formed) append then collided with it,
      ;; failing forever until the process restarted.
      (let [payload (resolve-sequence-payload storage body)
            seq-binding (if synthetic?
                          (materialize-synthetic-binding! storage synth-data)
                          seq-binding)
            binding-id (:id seq-binding)
            existing (sp/query-entities storage :binding-list-item
                                        {:binding-id binding-id})
            used-pos (map :position existing)
            end-pos (inc (apply max -1 used-pos))
            new-pos (if req-pos (min (:pos req-pos) end-pos) end-pos)
            ;; Items the insert displaces — empty for a plain append.
            displaced (filterv #(>= (:position %) new-pos) existing)
            new-item (merge {:id (random-uuid)
                             :binding-id binding-id
                             :position new-pos}
                            payload)
            ;; Pre-rej is position-independent (list-closed + ref-cycle)
            ;; — run it BEFORE shifting so a rejected insert writes
            ;; nothing at all.
            pre-rej (validation/write-rej storage :binding-list-item new-item)]
        (if pre-rej
          (do
            ;; Roll back the synthetic binding created just to host the item
            ;; — otherwise a rejected append leaves an orphan `:list-append`
            ;; binding on the slot.
            (when synthetic?
              (try (sp/delete-entity storage :binding binding-id)
                   (catch Exception e
                     (log/warn e "seq-append: synthetic-binding rollback failed"
                               {:binding-id binding-id}))))
            {:error (:reason pre-rej)})
          (do (shift-items! storage displaced 1)
              (sp/create-entity storage :binding-list-item new-item)
              ;; Post-write whole-fn type-check (Phase 3, Gap A) — the
              ;; item is KEPT on an ordinary failure (recorded in the
              ;; per-branch diagnostics store, surfaced additively).
              ;; SECURITY CARVE-OUT: a secret-flow failure rolls the
              ;; item (and the synthetic host binding) back and hard-
              ;; rejects — see docs/SECRETS.md.
              (let [rej (post-write-rej
                          storage (or (:fn-id seq-binding) fn-id))]
                (if (:secret? rej)
                  (do (try (sp/delete-entity storage :binding-list-item
                                             (:id new-item))
                           (catch Exception e
                             (log/warn e "seq-append: item rollback failed after secret-flow rejection"
                                       {:item-id (:id new-item)})))
                      ;; Un-shift the displaced items (they sit at old+1
                      ;; now — hand shift-items! their CURRENT positions).
                      (try (shift-items! storage
                                         (map #(update % :position inc)
                                              displaced)
                                         -1)
                           (catch Exception e
                             (log/warn e "seq-append: shift rollback failed after secret-flow rejection"
                                       {:binding-id binding-id})))
                      (when synthetic?
                        (try (sp/delete-entity storage :binding binding-id)
                             (catch Exception e
                               (log/warn e "seq-append: synthetic-binding rollback failed"
                                         {:binding-id binding-id}))))
                      {:error (:reason rej)})
                  (cond-> {:created (:id new-item)
                           :position new-pos
                           :fn-id fn-id}
                    rej (assoc :type-warnings [(:diagnostic rej)]))))))))))


(defn load-seq-remove-item
  "Resolve the binding-list-item row for a parsed sequence-remove
   request. Returns nil when the item-id is invalid OR when no row
   matches — the `:cond` graph fn-def's not-found guard rejects in
   both cases (the invalid-item-id guard runs first, so by the time
   this fires the id is well-formed)."
  [parsed ctx]
  (when-let [item-id (:item-id parsed)]
    (sp/read-entity (request/require-storage ctx) :binding-list-item item-id)))


(defn load-seq-update-item
  "Read the binding-list-item row for a parsed sequence-update
   request. Returns nil when the id is invalid (guard #1 catches it
   first) OR when no row matches (`:_seq-update-item-not-found?`
   catches that). Shared verbatim by the sequence-move flow (same
   `{:item-id …}` parsed shape)."
  [parsed ctx]
  (when-let [item-id (:item-id parsed)]
    (sp/read-entity (request/require-storage ctx) :binding-list-item item-id)))


(defn- swap-positions!
  "Swap the `:position` of two binding-list-item rows through a free
   temp position (`max + 1`) so each individual write passes the
   per-write position-uniqueness check."
  [storage a b temp-pos]
  (sp/update-entity storage :binding-list-item (:id a) {:position temp-pos})
  (sp/update-entity storage :binding-list-item (:id b) {:position (:position a)})
  (sp/update-entity storage :binding-list-item (:id a) {:position (:position b)}))


(defn apply-seq-move-core
  "§3.3 atomic core of sequence-move: swap `item` with its up/down
   neighbour in position order (three writes through a free temp
   position — each individual write passes the position-uniqueness
   check). A move at the chain's edge is a no-op success. Returns
   `{:moved <item-id> :position <int>}` (plus `:type-warnings` — the
   Phase-3 post-write check, record-or-clear: item order can matter,
   e.g. a nav-typed `:path`) or `{:error <reason>}` on a malformed
   direction OR a secret-flow type failure (hard reject: the swap is
   reversed — the security carve-out)."
  [parsed item ctx]
  (let [storage (request/require-storage ctx)
        direction (get-in parsed [:body :direction])
        pkg-reason (pkg-guard/write-rejection storage :binding-list-item item)]
    (cond
      pkg-reason {:error pkg-reason}
      (not (contains? #{"up" "down"} direction))
      {:error "Body requires {\"direction\": \"up\"} or {\"direction\": \"down\"}"}
      :else
      (let [items (->> (sp/query-entities storage :binding-list-item
                                          {:binding-id (:binding-id item)})
                       (sort-by :position)
                       vec)
            idx   (first (keep-indexed
                           (fn [i it] (when (= (:id it) (:id item)) i))
                           items))
            j     (when idx (if (= "up" direction) (dec idx) (inc idx)))]
        (if (or (nil? idx) (neg? j) (>= j (count items)))
          ;; Edge of the chain (or a raced-away row) — nothing to do.
          {:moved (:id item) :position (:position item)}
          (let [cur      (nth items idx)
                neighbor (nth items j)
                temp-pos (inc (:position (peek items)))]
            (swap-positions! storage cur neighbor temp-pos)
            (let [owner-fn-id (some->> (:binding-id item)
                                       (sp/read-entity storage :binding)
                                       :fn-id)
                  rej (post-write-rej storage owner-fn-id)]
              (if (:secret? rej)
                (do (try (swap-positions!
                           storage
                           (assoc cur :position (:position neighbor))
                           (assoc neighbor :position (:position cur))
                           temp-pos)
                         (catch Exception e
                           (log/warn e "seq-move: swap rollback failed after secret-flow rejection"
                                     {:item-id (:id item)})))
                    {:error (:reason rej)})
                (cond-> {:moved (:id item) :position (:position neighbor)}
                  rej (assoc :type-warnings [(:diagnostic rej)]))))))))))


(defn apply-seq-update-core
  "§3.3 atomic core of sequence-update: resolve body payload, run
   pre-write validation, write the binding-list-item row. Returns
   `{:updated <item-id>}` on success (plus `:type-warnings` — the
   Phase-3 post-write check, record-or-clear) or `{:error <reason>}`
   on pre-write rejection OR on a secret-flow type failure (hard
   reject: the item's fields are restored — the security carve-out)."
  [parsed item ctx]
  (let [storage (request/require-storage ctx)
        item-id (:item-id parsed)
        payload (resolve-sequence-payload storage (:body parsed))
        changes (merge {:value nil :ref-fn-id nil :literal nil} payload)
        pkg-reason (pkg-guard/write-rejection storage :binding-list-item item)
        pre-rej (when-not pkg-reason
                  (validation/write-rej storage :binding-list-item
                                        (merge item changes {:id item-id})))]
    (if (or pkg-reason pre-rej)
      {:error (or pkg-reason (:reason pre-rej))}
      (do (sp/update-entity storage :binding-list-item item-id changes)
          ;; Post-write whole-fn type-check (Phase 3, Gap A) — same
          ;; record-or-clear semantics as the binding update core.
          ;; SECURITY CARVE-OUT: a secret-flow failure restores the
          ;; item's pre-update fields and hard-rejects.
          (let [owner-fn-id (some->> (:binding-id item)
                                     (sp/read-entity storage :binding)
                                     :fn-id)
                rej (post-write-rej storage owner-fn-id)]
            (if (:secret? rej)
              (do (try (sp/update-entity
                         storage :binding-list-item item-id
                         (select-keys (merge {:value nil :ref-fn-id nil
                                              :literal nil}
                                             item)
                                      (keys changes)))
                       (catch Exception e
                         (log/warn e "seq-update: item restore failed after secret-flow rejection"
                                   {:item-id item-id})))
                  {:error (:reason rej)})
              (cond-> {:updated item-id}
                rej (assoc :type-warnings [(:diagnostic rej)]))))))))
