(ns graphden.executor.source-recipe-lifecycle
  "Publication belongs to a complete export/invoke, never a source subpass.
   Cancellation revokes pending work immediately but releases its charge only
   after actual worker completion. No global controller or store is installed."
  (:require
    [graphden.executor.source-recipe-store :as store]))


(def ^:dynamic ^:private *operation* nil)


(defn current-controller
  "Current explicitly supplied controller while this operation remains active.
   Bound workers share the operation atom; closed scopes retain no controller."
  []
  (when *operation*
    (let [operation @*operation*]
      (when (and (map? operation) (not (:finished? operation)))
        (:controller operation)))))


(defn- finish-if-unwound!
  [operation-ref]
  (let [{:keys [controller workers finished? success? pending]} @operation-ref]
    (when (and finished? (zero? workers))
      (try
        (doseq [[ticket {:keys [recipe charge]}] pending]
          (when (and success? recipe)
            (store/publish! (:store controller) ticket recipe charge)))
        (finally
          (try (doseq [ticket (keys pending)] (store/abort! (:store controller) ticket))
               (finally (reset! operation-ref ::closed))))))))


(defn- finish!
  [operation-ref success?]
  (locking operation-ref
    (when (map? @operation-ref)
      (swap! operation-ref assoc :finished? true :success? success?)
      (when-not success?
        (let [{:keys [controller pending]} @operation-ref]
          (doseq [ticket (keys pending)] (store/revoke! (:store controller) ticket))))
      (finish-if-unwound! operation-ref))))


(defn call-with-operation
  "Run the WHOLE export/invoke, including its fresh postcheck. Only {:ok true}
   can publish. Nested entrypoints share the outer completion boundary.
   The controller is trusted application configuration, never request input."
  [controller f]
  (if (or (nil? controller) *operation*)
    (f)
    (let [operation-ref (atom {:controller controller :workers 0 :finished? false
                               :success? false :pending {}})]
      (binding [*operation* operation-ref]
        (try
          (let [result (f)]
            (finish! operation-ref (true? (:ok result)))
            result)
          (catch Throwable error
            (finish! operation-ref false)
            (throw error)))))))


(defn register-worker!
  "Register BEFORE submitting the existing bounded execution task. Invoke the
   returned idempotent release from its actual on-done (also on rejected or
   never-started tasks), not from the caller's timeout/finally."
  []
  (when *operation*
    (let [operation-ref *operation*]
      (locking operation-ref
        (when (and (map? @operation-ref) (not (:finished? @operation-ref)))
          (swap! operation-ref update :workers inc)
          (let [active? (atom true)]
            (fn []
              (when (compare-and-set! active? true false)
                (locking operation-ref
                  (when (map? @operation-ref)
                    (swap! operation-ref update :workers dec)
                    (finish-if-unwound! operation-ref)))))))))))


(defn track-reservation!
  "Attach an already reserved capture before allocating its recipe. False
   means cancellation/closure won the race; caller aborts its unused ticket."
  [ticket]
  (boolean
    (when (and ticket *operation*)
      (locking *operation*
        (when (and (map? @*operation*) (not (:finished? @*operation*)))
          (swap! *operation* assoc-in [:pending ticket] {})
          true)))))


(defn complete-record!
  "Stage a completed immutable recipe. This does NOT publish it. False means
   the caller must discard it and release its reservation after this pass."
  [ticket recipe charge]
  (boolean
    (when *operation*
      (locking *operation*
        (when (and (map? @*operation*) (not (:finished? @*operation*))
                   (contains? (:pending @*operation*) ticket))
          (swap! *operation* assoc-in [:pending ticket] {:recipe recipe :charge charge})
          true)))))


(defn discard-record!
  "Forget an unsuccessful builder only AFTER its synchronous pass unwound.
   Cancellation alone never calls this on a still-running worker's behalf."
  [ticket]
  (when *operation*
    (locking *operation*
      (when (map? @*operation*)
        (let [controller (:controller @*operation*)]
          (swap! *operation* update :pending dissoc ticket)
          (store/abort! (:store controller) ticket))))))
