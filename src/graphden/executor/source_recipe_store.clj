(ns graphden.executor.source-recipe-store
  "Bounded ownership of source replay recipes, not source authorization.
   Callers supply conservative charges for all retained data and reserve before
   capture. No production defaults or global instance are installed here.")


(defn create-store
  "Create an isolated store with explicit positive limits. clock-ms must be
   monotonic; family deadlines use that clock's domain. closed? must be cheap,
   nonblocking and must not borrow a connection. Payloads must be immutable."
  [{:keys [max-bytes max-entries max-pending max-families max-leases max-ttl-ms clock-ms closed?] :as options}]
  (when-not (and (every? #(and (integer? %) (pos? %))
                         [max-bytes max-entries max-pending max-families max-leases max-ttl-ms])
                 (ifn? clock-ms) (ifn? closed?))
    (throw (ex-info "Source recipe store requires explicit limits and callbacks" {})))
  {:options options
   :state (atom {:families {} :pending {} :entries {} :leases {} :tick 0})})


(defn- closed-handle?
  [store physical]
  (try (boolean ((get-in store [:options :closed?]) physical))
       (catch Exception _ true)))


(defn- retire-entry
  [state id]
  (if (some #(= id %) (vals (:leases state)))
    (assoc-in state [:entries id :retired?] true)
    (update state :entries dissoc id)))


(defn- sweep
  [store state now]
  (let [state (update state :families
                      #(into {} (filter (fn [[_ family]] (< now (:expires-at family)))) %))
        unavailable? (fn [{:keys [family physical generation]}]
                       (or (not (identical? generation (get-in state [:families family :generation])))
                           (closed-handle? store physical)))
        state (update state :pending
                      #(into {} (map (fn [[id reservation]]
                                       [id (cond-> reservation
                                             (unavailable? reservation) (assoc :revoked? true))])) %))]
    (reduce (fn [s [id entry]]
              (if (or (:retired? entry) (unavailable? entry))
                (retire-entry s id)
                s)) state (:entries state))))


(defn- change!
  [store f]
  (let [state (:state store)]
    (locking state
      (let [now ((get-in store [:options :clock-ms]))
            [next-state result] (f (sweep store @state now) now)]
        (reset! state next-state)
        result))))


(defn- occupied-bytes
  [state]
  (+' (reduce +' 0 (map :charge (vals (:entries state))))
      (reduce +' 0 (map :reserved (vals (:pending state))))))


(defn- same-location?
  [family physical entry]
  (and (= family (:family entry)) (identical? physical (:physical entry))))


(defn- matching-entry
  [state family physical]
  (first (filter (fn [[_ entry]]
                   (and (not (:retired? entry)) (same-location? family physical entry)))
                 (:entries state))))


(defn- room?
  [store state charge]
  (and (<= (+' (occupied-bytes state) charge) (get-in store [:options :max-bytes]))
       (< (+ (count (:entries state)) (count (:pending state)))
          (get-in store [:options :max-entries]))))


(defn- make-room
  [store state charge]
  (let [leased (set (vals (:leases state)))
        victims (sort-by (comp :used val)
                         (remove #(contains? leased (key %)) (:entries state)))]
    (loop [state state victims victims]
      (if (or (room? store state charge) (empty? victims))
        state
        (recur (update state :entries dissoc (ffirst victims)) (rest victims))))))


(defn create-family!
  "Register a consumer-owned opaque family ID. Sharing policy is the caller's
   responsibility. A live registration never extends its fixed expiry. Reusing
   an expired/evicted ID creates a new generation; old tickets cannot publish."
  [store family expires-at]
  (change! store
           (fn [state now]
             (cond
               (contains? (:families state) family) [state family]
               (and (some? family) (integer? expires-at) (< now expires-at)
                    (<= expires-at (+' now (get-in store [:options :max-ttl-ms])))
                    (< (count (:families state)) (get-in store [:options :max-families])))
               [(assoc-in state [:families family]
                          {:expires-at expires-at :generation (Object.)}) family]
               :else [state nil]))))


(defn reserve!
  "Reserve aggregate bytes and an entry slot BEFORE allocating a capture.
   At most one pending capture per family/physical identity. A revoked capture
   stays charged until publish!/abort!: expiry cannot free a running builder's
   memory. A denied reservation means ordinary execution without recording."
  [store family physical max-charge]
  (change! store
           (fn [state _]
             (if (and (contains? (:families state) family) (some? physical)
                      (not (closed-handle? store physical))
                      (integer? max-charge) (pos? max-charge)
                      (<= max-charge (get-in store [:options :max-bytes]))
                      (< (count (:pending state)) (get-in store [:options :max-pending]))
                      (not-any? #(same-location? family physical %) (vals (:pending state))))
               (let [state (make-room store state max-charge)]
                 (if (room? store state max-charge)
                   (let [ticket (Object.)]
                     [(assoc-in state [:pending ticket]
                                {:family family :physical physical :reserved max-charge
                                 :generation (get-in state [:families family :generation])}) ticket])
                   [state nil]))
               [state nil]))))


(defn abort!
  "Release a capture reservation after its builder has unwound. Idempotent."
  [store ticket]
  (change! store (fn [state _]
                   [(update state :pending dissoc ticket) (contains? (:pending state) ticket)])))


(defn publish!
  "Call ONLY after the complete export/invocation, including postcheck, succeeds.
   Transfers a reservation to retained payload ownership. Charge must include
   every retained structure and fit its reservation. A rejected publication
   releases its reservation; the caller must then discard the payload. A stale
   ticket cannot replace a newer entry or recreate an evicted/expired family."
  [store ticket payload actual-charge]
  (change! store
           (fn [state _]
             (let [{:keys [family physical generation reserved revoked?] :as reservation}
                   (get-in state [:pending ticket])
                   state (update state :pending dissoc ticket)]
               (if (and reservation (not revoked?)
                        (integer? actual-charge) (pos? actual-charge) (<= actual-charge reserved))
                 (let [old-id (first (matching-entry state family physical))
                       state (cond-> state old-id (retire-entry old-id))
                       tick (inc (:tick state))]
                   [(-> state (assoc :tick tick)
                        (assoc-in [:entries (Object.)]
                                  {:family family :physical physical :generation generation :payload payload
                                   :charge actual-charge :used tick})) true])
                 [state false])))))


(defn acquire!
  "Lease a matching live recipe. Leases are opaque and carry no payload;
   release! in finally after certificate validation/replay, including failures."
  [store family physical]
  (change! store
           (fn [state _]
             (if-let [[id _] (when (< (count (:leases state)) (get-in store [:options :max-leases]))
                               (matching-entry state family physical))]
               (let [lease (Object.) tick (inc (:tick state))]
                 [(-> state (assoc :tick tick)
                      (assoc-in [:entries id :used] tick)
                      (assoc-in [:leases lease] id)) lease])
               [state nil]))))


(defn lease-value
  "Return the payload while this lease is held, even if its entry was evicted.
   A released token cannot retain or retrieve a payload."
  [store lease]
  (change! store (fn [state _]
                   [state (get-in state [:entries (get-in state [:leases lease]) :payload])])))


(defn release!
  "Release once; a retired entry's charge remains until its LAST lease exits."
  [store lease]
  (change! store
           (fn [state _]
             (if-let [id (get-in state [:leases lease])]
               (let [state (update state :leases dissoc lease)]
                 [(if (get-in state [:entries id :retired?]) (retire-entry state id) state) true])
               [state false]))))


(defn evict-family!
  "Invalidate the family's generation. Pending builders remain charged and
   cannot publish; leased payloads remain charged until their owners release."
  [store family]
  (change! store
           (fn [state now]
             [(sweep store (update state :families dissoc family) now)
              (contains? (:families state) family)])))


(defn stats
  "Bounded accounting only: no source payloads, handles or authority material."
  [store]
  (change! store
           (fn [state _]
             [state {:families (count (:families state)) :pending (count (:pending state))
                     :entries (count (:entries state)) :leases (count (:leases state))
                     :retired (count (filter :retired? (vals (:entries state))))
                     :pending-bytes (reduce +' 0 (map :reserved (vals (:pending state))))
                     :retained-bytes (reduce +' 0 (map :charge (vals (:entries state))))
                     :charged-bytes (occupied-bytes state)}])))
