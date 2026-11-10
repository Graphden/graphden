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
  [{:keys [entry-bytes max-nodes ttl-ms clock-ms checker-abi callbacks] :as options}]
  (when-not (and (every? #(and (integer? %) (pos? %)) [entry-bytes max-nodes ttl-ms])
                 (<= entry-bytes (:max-bytes options))
                 (<= ttl-ms (:max-ttl-ms options))
                 (string? checker-abi) (<= 1 (count checker-abi) 256)
                 (vector? callbacks) (<= (count callbacks) 128)
                 (every? data/static-callback? callbacks))
    (throw (ex-info "Checker memo requires explicit limits and a reviewed callback ABI" {})))
  (let [active? (atom true)]
    {:store (store/create-store (assoc options :closed? #(or (not @active?)
                                                             (nil? (WeakReference/.get %)))))
     :active? active? :environment (atom nil)
     :admission {:max-bytes entry-bytes :max-nodes max-nodes :callbacks callbacks}
     :entry-bytes entry-bytes :ttl-ms ttl-ms :clock-ms clock-ms :checker-abi checker-abi}))


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
  (reset! (:environment memo) nil)
  (stats memo))


(defn- environment-token
  [memo original-rich]
  (let [environment (:environment memo)]
    (locking environment
      (let [previous @environment]
        (if (and previous (identical? original-rich (WeakReference/.get previous)))
          previous
          (let [token (WeakReference. original-rich)]
            (reset! environment token)
            token))))))


(defn- register-family!
  [memo family]
  ;; Only a fixed-size digest becomes family metadata. Digest collisions merely
  ;; contend for an entry: every hit still compares the complete admitted input.
  (when (and @(:active? memo) (admitted? memo family))
    (store/create-family! (:store memo) (provenance/fingerprint-data family)
                          (+ ((:clock-ms memo)) (:ttl-ms memo)))))


(defn- build-and-consume
  [memo ticket input {:keys [build consume check-cancel]}]
  (let [artifact (build input)
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


(defn call-with-artifact
  "Called synchronously on the checking worker. prepare must perform all fresh
   reconstruction/provenance checks and return complete immutable checker input,
   or nil to use the original ordered path. build returns derived registry data;
   restore installs only that data into isolated atoms. consume ALWAYS runs fresh.
   Cancellation and release happen after actual worker unwind, not caller timeout."
  [memo {:keys [family original-rich prepare restore consume fallback] :as operations}]
  (if-let [family (register-family! memo family)]
    (let [token (environment-token memo original-rich)
          lease (store/acquire! (:store memo) family token)]
      (try
        (if-let [ticket (store/reserve! (:store memo) family token (:entry-bytes memo))]
          (try
            ;; Reserve BEFORE preparation allocates the retained key/definitions.
            ;; A hit also reserves its transient comparison input conservatively.
            (if-let [input (prepare)]
              (if (admitted? memo input)
                (let [cached (when lease (store/lease-value (:store memo) lease))]
                  (if (and cached (data/same-input? input (:input cached)))
                    (do (restore (:artifact cached)) (consume))
                    (build-and-consume memo ticket input operations)))
                (fallback))
              (fallback))
            (finally (store/abort! (:store memo) ticket)))
          (fallback))
        (finally (when lease (store/release! (:store memo) lease)))))
    (fallback)))
