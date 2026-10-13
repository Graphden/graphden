(ns graphden.system.branch-router.recheck-state
  "Ephemeral generation cancellation shared by recovery and cache retirement.")


(defn generation
  "Capture the recovery generation before retiring a cache entry."
  [ctx]
  (some-> (:type-recheck-state ctx) deref :generation))


(defn cancel!
  "Retire only the captured generation. A later schedule on the same base
   context makes delayed retirement a no-op."
  ([ctx] (cancel! ctx (generation ctx)))
  ([ctx expected-generation]
   (when-let [state (:type-recheck-state ctx)]
     (swap! state #(if (= expected-generation (:generation %))
                     (-> % (update :generation inc)
                         (assoc :cancelled? true :status :idle :pending-ids #{}))
                     %)))))
