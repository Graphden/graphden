(ns graphden.executor.source-recipe-trace
  "Bounded raw JDBC recipes for a single synchronous source pass.
   No store, activation, authorization decision or transaction ownership lives
   here. A replay restart must unwind the caller's RR before ordinary retry."
  (:require
    [graphden.executor.source-recipe-jdbc-rows :as jdbc-rows]
    [graphden.executor.source-recipe-values :as values]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.source-certificate :as certificate]
    [graphden.storage.postgres.util :as pg]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.sql
      SQLException)))


(def ^:private max-operations 2048)
(def ^:private max-authorizations 8192)


(def preparation-scratch-bytes
  "Explicit per-active-pass reservation for bounded AST/SQL preparation and
   transient copies. Consumers reserve it BEFORE record or replay begins."
  (* 32 1024 1024))


(def ^:private state-charge (+ preparation-scratch-bytes 4096))
(def ^:dynamic ^:private *trace* nil)
(def ^:dynamic ^:private *active-scope* nil)


(deftype ^:private Recipe
  [state])


(def ^:private restart-seal (Object.))


(defn- restart!
  ([reason] (restart! reason nil))
  ([reason cause]
   (throw (ex-info "Source replay requires a fresh snapshot" {:type ::restart :reason reason ::seal restart-seal} cause))))


(defn restart?
  "Recognize only an internal restart, not a target exception that happens
   to use the same public error keyword."
  [error]
  (and (= ::restart (:type (ex-data error)))
       (identical? restart-seal (::seal (ex-data error)))))


^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn charge-bytes
  "Conservative retained recipe size, also the allowance for fresh witness and
   row reconstruction during replay. Caller separately reserves preparation
   scratch before entering the owned guarded snapshot."
  [recipe]
  (when-not (instance? Recipe recipe)
    (restart! :recipe-missing))
  (:charge-bytes (.-state ^Recipe recipe)))


(defn- ineligible!
  [reason]
  (throw (ex-info "Source recipe is ineligible" {:type ::ineligible :reason reason})))


(defn- ineligible?
  [error]
  (contains? #{::ineligible ::certificate/miss ::values/ineligible} (:type (ex-data error))))


(defn- check-active!
  [{:keys [active? owner check!]}]
  (check!)
  (when-not (and @active? (identical? owner (Thread/currentThread)))
    (restart! :source-scope-expired)))


(defn- poison!
  [state error]
  (if (ineligible? error)
    ;; Release already copied data, but the enclosing reservation belongs to
    ;; the actual builder's finally, never to this admission failure alone.
    (reset! state {:poisoned? true :reason (:reason (ex-data error))})
    (throw error)))


(defn- refuse-nested!
  []
  (when *active-scope*
    (let [outer @*active-scope*]
      (when-not (map? outer) (restart! :source-scope-expired))
      (check-active! outer)
      (if (= :record (:mode outer))
        (reset! (:state outer) {:poisoned? true :reason :nested-source-trace})
        (reset! (:active? outer) false))
      (restart! :nested-source-trace))))


(defn- charge!
  [{:keys [state max-bytes]} amount]
  (when (> (:bytes (swap! state update :bytes +' amount)) max-bytes)
    (ineligible! :byte-limit)))


(defn- known-options?
  [opts]
  (and (contains? #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap} (class opts))
       (nil? (meta opts)) (= #{:builder-fn :timeout} (set (keys opts)))
       (identical? rs/as-unqualified-lower-maps (:builder-fn opts))
       (integer? (:timeout opts)) (<= 0 (:timeout opts) Integer/MAX_VALUE)))


(defn- prepare
  [{:keys [connection guard]} one? ds query opts]
  (when-not (identical? connection ds) (ineligible! :foreign-read-connection))
  (when-not (known-options? opts) (ineligible! :jdbc-options))
  (let [ast (or (pg/current-read-plan ds query) (ineligible! :unplanned-read))
        operation (certificate/prepare-operation guard ast query one?)]
    {:operation operation :key [one? (certificate/operation-key operation) opts]}))


(defn- freeze-within-budget
  [{:keys [state max-bytes]} value]
  (let [remaining (- max-bytes (:bytes @state))]
    (when-not (pos? remaining) (ineligible! :byte-limit))
    (values/freeze value remaining)))


(defn- retain-query!
  [{:keys [state] :as trace} {:keys [operation key]} result]
  (when-not (contains? (:by-key @state) key)
    (when (>= (count (:operations @state)) max-operations) (ineligible! :operation-limit))
    (let [operation-charge (+ 1024 (certificate/operation-charge-bytes operation))]
      (charge! trace operation-charge)
      (let [remaining (- (:max-bytes trace) (:bytes @state))
            frozen (jdbc-rows/freeze result (first key) remaining)]
        (charge! trace (jdbc-rows/charge-bytes frozen))
        (swap! state #(-> % (update :operations conj operation) (assoc-in [:by-key key] frozen)))))))


(defn- record-query
  [{:keys [state] :as trace} original one? ds query opts]
  (if (:poisoned? @state)
    (original ds query opts)
    (let [prepared (try (prepare trace one? ds query opts)
                        (catch clojure.lang.ExceptionInfo error (poison! state error) nil))
          ;; Execute exactly the immutable values that were prepared, even if
          ;; the producer mutates a Date/Timestamp before the JDBC call starts.
          result (original ds (if prepared (certificate/operation-query (:operation prepared)) query) opts)]
      (when (and prepared (not (:poisoned? @state)))
        (try (retain-query! trace prepared result)
             (catch clojure.lang.ExceptionInfo error (poison! state error))))
      result)))


(defn- replay-query
  [trace one? ds query opts]
  (try
    (let [{:keys [key]} (prepare trace one? ds query opts)]
      (if-let [frozen (get (:by-key trace) key)]
        (jdbc-rows/thaw frozen ds opts)
        (restart! :unrecorded-read)))
    (catch clojure.lang.ExceptionInfo error
      (if (ineligible? error) (restart! :read-changed error) (throw error)))))


(defn- jdbc-hook
  [original one?]
  (fn [ds query opts]
    (if *trace*
      (let [trace @*trace*]
        (when-not (map? trace) (restart! :source-scope-expired))
        (check-active! trace)
        (let [answer (case (:mode trace)
                       :record (record-query trace original one? ds query opts)
                       :replay (replay-query trace one? ds query opts))]
          (check-active! trace)
          answer))
      (original ds query opts))))


(defn- with-interception
  [trace-ref f]
  (let [prior pg/*jdbc-override*
        many (or (:execute! prior) jdbc/execute!)
        one (or (:execute-one! prior) jdbc/execute-one!)]
    (binding [*trace* trace-ref *active-scope* trace-ref
              pg/*jdbc-override* {:execute! (jdbc-hook many false) :execute-one! (jdbc-hook one true)}]
      (try (pg/call-with-flat-read-plans f) (finally (when-let [active? (:active? @trace-ref)] (reset! active? false))
                                                     (reset! trace-ref ::closed))))))


(defn- authority-projection
  [row fields permissions]
  (let [projection (select-keys row fields)]
    ;; These are the only declared known-adapter authority fields. Keep keys
    ;; immutable before using them for bounded deduplication as well as copying.
    (when-not (and (or (nil? (:id projection)) (uuid? (:id projection)))
                   (or (nil? (:namespace-id projection)) (uuid? (:namespace-id projection)))
                   (or (nil? (:org-id projection)) (string? (:org-id projection)))
                   (or (nil? (:concealed projection)) (boolean? (:concealed projection)))
                   (<= (count permissions) 1)
                   (every? #(and (map? %) (nil? (meta %))
                                 (every? #{:execute? :write? :server-target? :identity-only?} (keys %))
                                 (every? boolean? (vals %))) permissions))
      (ineligible! :authority-shape))
    [projection (mapv #(into {} %) permissions)]))


(defn- fresh-authorizer
  [trace-ref]
  (fn [row & permissions]
    (let [{:keys [state authority-fields authorize!] :as trace} @trace-ref]
      (when-not (map? trace) (restart! :source-scope-expired))
      (check-active! trace)
      (when (and (= :record (:mode trace)) (not (:poisoned? @state)))
        (try
          (let [projection (authority-projection row authority-fields permissions)]
            (when-not (contains? (:authorizations @state) projection)
              (when (>= (count (:authorizations @state)) max-authorizations)
                (ineligible! :authorization-limit))
              (let [frozen (freeze-within-budget trace projection)]
                (charge! trace (+ 256 (values/charge-bytes frozen)))
                (swap! state assoc-in [:authorizations projection] frozen))))
          (catch clojure.lang.ExceptionInfo error (poison! state error))))
      ;; Grants and namespace paths always execute freshly; they are not source
      ;; payloads and cannot borrow the source's SQL recording/replay mode.
      (let [answer (binding [*trace* nil] (apply authorize! row permissions))]
        (check-active! trace)
        answer))))


(defn- trace-context
  [{:keys [connection guard check! authority-fields generation max-bytes] :as scope}]
  (when-not (and connection guard (ifn? check!) generation
                 (vector? authority-fields) (<= (count authority-fields) 4)
                 (every? #{:id :org-id :namespace-id :concealed} authority-fields)
                 (integer? max-bytes) (> max-bytes (+ preparation-scratch-bytes (* 1024 1024))))
    (throw (ex-info "Source trace requires an owned bounded scope" {})))
  (certificate/assert-connection! guard connection)
  (assoc scope :authority-fields (into [] authority-fields)
         :owner (Thread/currentThread) :active? (atom true)))


(defn record
  "Record one ordinary source pass under an ALREADY RESERVED byte bound.
   run takes a fresh authorizer; callers supply fresh resolver/cache scopes.
   Unknown reads/codecs poison the whole recipe while ordinary reads continue.
   Metadata SQL errors require outer transaction restart; cancellation escapes."
  [scope run authorize!]
  (refuse-nested!)
  (let [state (atom {:operations [] :by-key {} :authorizations {} :bytes state-charge})
        trace (assoc (trace-context scope) :mode :record :state state :authorize! authorize!)
        trace-ref (atom trace)]
    (grouped/call-without-grouping
      #(with-interception
         trace-ref
         (fn []
           (check-active! trace)
           (let [answer (run (fresh-authorizer trace-ref))
                 {:keys [operations by-key authorizations poisoned? reason] held-charge :bytes} @state]
             (check-active! trace)
             (if (or poisoned? (empty? operations))
               {:value answer :reason (or reason :empty-trace)}
               (try
                 (let [proof (binding [*trace* nil]
                               (certificate/capture (:guard trace) operations
                                                    {:max-bytes (- (:max-bytes trace) held-charge)}))
                       charge (- (+ held-charge (:charge-bytes (certificate/stats proof))) preparation-scratch-bytes)
                       recipe (Recipe. {:certificate proof :by-key by-key :authorizations (vec (vals authorizations))
                                        :generation (:generation trace) :authority-fields (:authority-fields trace)
                                        :charge-bytes charge})]
                   (check-active! trace)
                   {:value answer :recipe recipe :charge-bytes charge})
                 (catch clojure.lang.ExceptionInfo error
                   (if (ineligible? error)
                     (do (check-active! trace) {:value answer :reason (:reason (ex-data error))})
                     (throw error)))
                 (catch SQLException error (restart! :certificate-sql-failed error))))))))))


(defn replay
  "Validate the whole recipe and reauthorize its narrow projections BEFORE
   returning any cached payload. Every source check/decoder still runs. A miss
   throws ::restart; retry only after the caller has closed this transaction.
   In addition to its entry lease, the caller owns preparation-scratch-bytes
   until this synchronous pass has actually returned or unwound."
  [scope recipe run authorize!]
  (refuse-nested!)
  (when-not (instance? Recipe recipe) (restart! :recipe-missing))
  (let [saved (.-state ^Recipe recipe)
        trace (assoc (trace-context scope) :mode :replay :by-key (:by-key saved) :authorize! authorize!)
        trace-ref (atom trace)]
    (when-not (and (identical? (:generation trace) (:generation saved))
                   (= (:authority-fields trace) (:authority-fields saved)))
      (restart! :policy-generation-changed))
    (grouped/call-without-grouping
      #(with-interception
         trace-ref
         (fn []
           (check-active! trace)
           (try
             (when-not (binding [*trace* nil] (certificate/unchanged? (:guard trace) (:certificate saved)))
               (restart! :source-changed))
             (catch clojure.lang.ExceptionInfo error
               (if (ineligible? error) (restart! :certificate-changed error) (throw error)))
             (catch SQLException error (restart! :certificate-sql-failed error)))
           (binding [*trace* nil]
             (doseq [frozen (:authorizations saved)]
               (check-active! trace)
               (let [[row permissions] (values/thaw frozen)]
                 (try (apply authorize! row permissions)
                      (catch clojure.lang.ExceptionInfo error
                        (if (= :authz/forbidden (:type (ex-data error)))
                          (restart! :authorization-changed error)
                          (throw error)))))))
           (check-active! trace)
           (let [answer (run (fresh-authorizer trace-ref))]
             (check-active! trace)
             answer))))))
