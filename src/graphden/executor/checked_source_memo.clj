(ns graphden.executor.checked-source-memo
  "Opt-in bounded ownership of pure checker artifacts. Never caches source
   authority, descriptors, execution results or failures. No global instance."
  (:require
    [graphden.executor.checked-source-data :as data]
    [graphden.executor.source-recipe-store :as store]
    [graphden.types.check.provenance :as provenance])
  (:import
    (java.lang.ref
      WeakReference)))


(def ^:dynamic *memo* nil)


(defn create-memo
  "All resource limits and the reviewed checker callback ABI are explicit.
   callbacks must be exact approved deterministic static function identities;
   this constructor checks capture shape, not callback purity. Unknown values
   miss. A frozen build is required; diagnostic reload must drain in-flight
   checks, retire! this instance, and construct a new one with the new ABI."
  [{:keys [entry-bytes max-nodes ttl-ms clock-ms checker-abi callbacks hit-contract] :as options}]
  (when-not (and (every? #(and (integer? %) (pos? %)) [entry-bytes max-nodes ttl-ms])
                 (<= entry-bytes (:max-bytes options))
                 (<= ttl-ms (:max-ttl-ms options))
                 (string? checker-abi) (<= 1 (count checker-abi) 256)
                 (vector? callbacks) (<= (count callbacks) 128)
                 (every? data/static-callback? callbacks)
                 (or (nil? hit-contract)
                     (and (integer? (:bytes hit-contract)) (pos? (:bytes hit-contract))
                          (<= (:bytes hit-contract) entry-bytes)
                          (every? data/static-callback?
                                  (map hit-contract [:prepare :eligible? :restore])))))
    (throw (ex-info "Checker memo requires explicit limits and a reviewed callback ABI" {})))
  (let [active? (atom true)]
    {:store (store/create-store (assoc options :closed? #(or (not @active?)
                                                             (nil? (WeakReference/.get %)))))
     :active? active? :environment (atom [])
     :admission {:max-bytes entry-bytes :max-nodes max-nodes :callbacks callbacks}
     :entry-bytes entry-bytes :ttl-ms ttl-ms :clock-ms clock-ms :checker-abi checker-abi
     :hit-contract hit-contract}))


(defn admitted?
  "Conservative admission only; not freshness, equality or authorization."
  [memo value]
  (boolean (data/charge value (:admission memo))))


(defn stats
  [memo]
  (store/stats (:store memo)))


(defn retire!
  "Disable reuse/publication before a diagnostic code reload. Existing worker
   reservations/leases stay charged until their finally exits. Drain workers
   before changing code: this is not live-hot-reload synchronization."
  [memo]
  (reset! (:active? memo) false)
  (reset! (:environment memo) [])
  (stats memo))


(defn- environment-token
  [memo original-rich]
  (let [environment (:environment memo)]
    (locking environment
      (let [live (filterv #(some? (WeakReference/.get %)) @environment)
            existing (some #(when (identical? original-rich (WeakReference/.get %)) %) live)
            token (or existing (WeakReference. original-rich))
            others (remove #(identical? token %) live)
            limit (get-in memo [:store :options :max-entries])]
        ;; Alternate branch/org registries keep their physical identity without
        ;; retaining the registries themselves. Overflow only loses reuse.
        (reset! environment (into [] (take-last limit (concat others [token]))))
        token))))


(defn- register-family!
  [memo family]
  ;; Only a fixed-size digest becomes family metadata. Digest collisions merely
  ;; contend for an entry: every hit still compares the complete admitted input.
  (when (and @(:active? memo) (admitted? memo family))
    (store/create-family! (:store memo) (provenance/fingerprint-data family)
                          (+ ((:clock-ms memo)) (:ttl-ms memo)))))


(defn- build-and-consume
  [memo ticket input {:keys [prepare-build build consume fallback check-cancel]}]
  (if-let [build-input (if prepare-build (prepare-build input) input)]
    (if (and prepare-build (not (admitted? memo build-input)))
      (fallback)
      (let [artifact (build build-input)
            payload {:input input :artifact artifact}
            ;; Include the store entry, weak identity token and fixed-size family
            ;; reference, in addition to all reachable payload data.
            charge (some-> (data/charge payload (:admission memo)) (+ 1024))
            result (consume)]
        (check-cancel)
        (when (Thread/.isInterrupted (Thread/currentThread))
          (throw (InterruptedException. "Checker artifact builder interrupted")))
        (when (and charge (<= charge (:entry-bytes memo)))
          ;; Pure checker artifacts publish after checked-source including consume.
          ;; Raw source recipes instead require full invocation AND postcheck success.
          ;; A later execution failure does not invalidate this deterministic proof.
          (store/publish! (:store memo) ticket payload charge))
        result))
    (fallback)))


(defn- call-with-build-reservation
  [memo family token lease {:keys [prepare restore consume fallback] :as operations}]
  (if-let [ticket (store/reserve! (:store memo) family token (:entry-bytes memo))]
    (try
      (if-let [input (prepare)]
        (if (admitted? memo input)
          (let [cached (when lease (store/lease-value (:store memo) lease))]
            (if (and cached (data/same-input? input (:input cached)))
              (do (restore (:artifact cached)) (consume))
              (build-and-consume memo ticket input operations)))
          (fallback))
        (fallback))
      (finally (store/abort! (:store memo) ticket)))
    (fallback)))


(defn- call-with-read-reservation
  [memo lease cached {:keys [restore consume hit-input]}]
  (let [contract (:hit-contract memo)]
    (if-let [ticket (store/reserve-read! (:store memo) lease (:bytes contract))]
      (try
        (when-let [input ((:prepare contract) hit-input)]
          (when (and (admitted? memo input) (data/same-input? input (:input cached)))
            {:hit? true :result (do (restore (:artifact cached)) (consume))}))
        (finally (store/abort! (:store memo) ticket)))
      {:denied? true})))


(defn call-with-artifact
  "Called synchronously on the checking worker. prepare returns a fresh complete
   immutable comparison key, or nil for the original ordered path. Optional
   prepare-build performs reconstruction/provenance preparation only on a miss;
   all its immutable inputs, including the reconstruction environment, must
   already be present in the comparison key. Its nil/non-admitted output uses
   the ordinary path. build returns derived registry data;
   restore installs only that data into isolated atoms. consume ALWAYS runs fresh.
   Optional runtime-owned hit-contract is reviewed at construction: bytes bounds all NEW
   live preparation/admission/comparison/restore memory, with borrowed immutable
   inputs owned by the request. eligible? checks the retained artifact BEFORE
   bounded prepare/restore. The request supplies only hit-input, never its own
   scratch limit or adapter; restore must match the runtime's exact identity.
   Generic callers retain full builder accounting.
   Cancellation and release happen after actual worker unwind, not caller timeout."
  [memo {:keys [family original-rich restore fallback] :as operations}]
  (if-let [family (register-family! memo family)]
    (let [token (environment-token memo original-rich)
          lease (store/acquire! (:store memo) family token)]
      (try
        (let [cached (when lease (store/lease-value (:store memo) lease))
              contract (:hit-contract memo)
              read-result (when (and cached contract (contains? operations :hit-input)
                                     (identical? restore (:restore contract))
                                     ((:eligible? contract) (:artifact cached)))
                            (call-with-read-reservation memo lease cached operations))]
          (cond
            (:hit? read-result) (:result read-result)
            (:denied? read-result) (fallback)
            ;; A mismatched read input leaves its scope/accounting first. The
            ;; ordinary builder prepares again under its full reservation.
            :else (call-with-build-reservation memo family token lease operations)))
        (finally (when lease (store/release! (:store memo) lease)))))
    (fallback)))
