(ns graphden.executor.checked-source-memo-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.checked-source-data :as data]
    [graphden.executor.checked-source-memo :as memo]
    [graphden.types.check.provenance :as provenance]))


(deftype UnknownMeta
  [calls]

  clojure.lang.IMeta

  (meta [_] (swap! calls inc) nil))


(defn- options
  [clock]
  {:max-bytes 1000000 :max-entries 8 :max-pending 4 :max-families 8
   :max-leases 8 :max-ttl-ms 1000 :clock-ms #(deref clock)
   :entry-bytes 100000 :max-nodes 10000 :ttl-ms 1000
   :checker-abi "test-1" :callbacks []})


(defn- hit-eligible?
  [_]
  true)


(defn- read-options
  []
  (assoc (options (atom 0)) :hit-contract
         {:bytes 1000 :prepare identity :restore identity :eligible? hit-eligible?}))


(deftest concurrent-read-hits-share-an-artifact-and-keep-scratch-charged
  (let [m (memo/create-memo (read-options)) built (atom 0) entered (atom 0)
        block? (atom false) all-entered (promise) finish (promise)
        ops {:family [:org :root] :original-rich {} :prepare (constantly {:source 1})
             :hit-input {:source 1} :build (fn [input] (swap! built inc) input)
             :restore identity :consume #(do (when @block?
                                               (when (= 4 (swap! entered inc)) (deliver all-entered true))
                                               @finish)
                                             :checked)
             :fallback (constantly :fallback) :check-cancel (constantly nil)}]
    (is (= :checked (memo/call-with-artifact m ops)))
    (reset! block? true)
    (let [workers (mapv (fn [_] (future (memo/call-with-artifact m ops))) (range 4))]
      (try
        (is (true? (deref all-entered 5000 :timeout)))
        (is (= 1 @built))
        (is (= {:pending 4 :pending-bytes 4000 :entries 1}
               (select-keys (memo/stats m) [:pending :pending-bytes :entries])))
        (is (= :fallback (memo/call-with-artifact m ops)))
        (finally (deliver finish true)))
      (is (= [:checked :checked :checked :checked] (mapv #(deref % 5000 :timeout) workers))))
    (is (= {:pending 0 :leases 0 :entries 1}
           (select-keys (memo/stats m) [:pending :leases :entries])))
    (memo/retire! m)))


(deftest bounded-read-mismatch-and-nested-consume-release-their-own-tickets
  (let [m (memo/create-memo (read-options)) built (atom 0)
        ops {:family [:org :root] :original-rich {} :prepare (constantly {:source 1})
             :hit-input {:source 1} :build (fn [input] (swap! built inc) input)
             :restore identity :consume (constantly :checked)
             :fallback (constantly :fallback) :check-cancel (constantly nil)}]
    (memo/call-with-artifact m ops)
    (is (= :checked (memo/call-with-artifact m (assoc ops :consume #(memo/call-with-artifact m ops)))))
    (is (= 1 @built))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"consumer stopped"
          (memo/call-with-artifact m (assoc ops :consume #(throw (ex-info "consumer stopped" {}))))))
    (is (= {:pending 0 :leases 0}
           (select-keys (memo/stats m) [:pending :leases])))
    (is (= :checked
           (memo/call-with-artifact m
                                    (assoc ops :hit-input {:source 2} :prepare (constantly {:source 2})
                                           :consume #(do (is (= 100000 (:pending-bytes (memo/stats m)))) :checked)))))
    (is (= 2 @built))
    (is (= {:pending 0 :leases 0}
           (select-keys (memo/stats m) [:pending :leases])))
    (memo/retire! m)))


(deftest bounded-hit-adapters-cannot-be-replaced-by-request-callbacks
  (let [m (memo/create-memo (read-options))
        ops {:family [:org :root] :original-rich {} :prepare (constantly {:source 1})
             :hit-input {:source 1} :build identity :restore identity
             :consume (constantly :checked) :fallback (constantly :fallback)
             :check-cancel (constantly nil)}]
    (memo/call-with-artifact m ops)
    (is (= :checked
           (memo/call-with-artifact m
                                    (assoc ops :restore (fn [_])
                                           :consume #(do (is (= 100000 (:pending-bytes (memo/stats m)))) :checked)))))
    (is (= :fallback
           (memo/call-with-artifact m
                                    (assoc ops :hit-input (java.util.Date. 0) :prepare #(java.util.Date. 0)
                                           :consume #(throw (ex-info "Must not consume an unadmitted input" {}))))))
    (is (zero? (:pending (memo/stats m))))
    (is (zero? (:leases (memo/stats m))))
    (memo/retire! m)))


(deftest bounded-restore-counts-nonmirrored-registries-and-handles-hash-collisions
  (let [colliding-keys (reduce (fn [prefixes _]
                                 (vec (for [prefix prefixes suffix ["Aa" "BB"]]
                                        (str prefix suffix)))) [""] (range 11))
        by-id (zipmap colliding-keys (repeat {:return :text}))
        registry {:rich {:by-id by-id} :per-org {:acme {:by-id by-id}}}
        artifact (data/compact-registry-state registry)]
    (is (= 1 (count (set (map hash colliding-keys)))))
    (is (data/bounded-registry-restore? artifact))
    (is (= registry (data/expand-registry-state artifact)))
    (is (not (data/bounded-registry-restore?
               {:registry {:rich {:by-id {}}
                           :per-org {:acme {:by-id (assoc by-id :extra {})}}}
                :mirrors {}})))
    (is (not (data/bounded-registry-restore?
               {:registry {:rich {:by-id {}} :per-org (java.util.HashMap.)} :mirrors {}})))))


(deftest alternating-environments-reuse-only-their-own-checked-artifacts
  (let [m (memo/create-memo (options (atom 0)))
        a {:environment :same} b (with-meta a {:distinct-instance true})
        built (atom 0) restored (atom 0)
        ops {:family [:org :root] :prepare (constantly {:source 1})
             :build (fn [input] (swap! built inc) input)
             :restore (fn [_] (swap! restored inc)) :consume (constantly :checked)
             :fallback (constantly :fallback) :check-cancel (constantly nil)}]
    (is (= a b))
    (is (not (identical? a b)))
    (doseq [rich [a a b a b]]
      (is (= :checked (memo/call-with-artifact m (assoc ops :original-rich rich)))))
    (is (= 2 @built))
    (is (= 3 @restored))
    (is (= 2 (:entries (memo/stats m))))
    (memo/retire! m)))


(deftest environment-identities-are-weak-and-bounded
  (let [m (memo/create-memo (assoc (options (atom 0)) :max-entries 2))
        rich (mapv #(hash-map :environment %) (range 3))
        ops {:family [:org :root] :prepare (constantly {:source 1})
             :build identity :restore (fn [_]) :consume (constantly :checked)
             :fallback (constantly :fallback) :check-cancel (constantly nil)}]
    (doseq [original rich]
      (is (= :checked (memo/call-with-artifact m (assoc ops :original-rich original)))))
    (is (= 2 (count @(:environment m))))
    ;; Deterministic weak-reference clearing avoids a GC-dependent test.
    (doseq [token @(:environment m)] (java.lang.ref.WeakReference/.clear token))
    (is (= :checked (memo/call-with-artifact m (assoc ops :original-rich (first rich)))))
    (is (= 1 (count @(:environment m))))
    (memo/retire! m)
    (is (empty? @(:environment m)))))


(deftest immutable-admission-covers-hidden-retained-values
  (let [m (memo/create-memo (options (atom 0)))
        mutable (java.util.Date. 0)
        meta-reads (atom 0)
        unknown (UnknownMeta. meta-reads)
        stamped (provenance/stamp {:return :text} {:args {:x {:value mutable}}})]
    (is (memo/admitted? m {:a [1 2N 3M (random-uuid)] :b (list nil true)}))
    (doseq [input [unknown mutable {:x mutable} (with-meta {} {:unknown mutable})
                   (subvec [mutable 1] 1)
                   (cons 1 (with-meta '(2) {:unknown mutable}))
                   (with-meta (conj (with-meta (list 2) {:unknown mutable}) 1) nil)]]
      (is (not (memo/admitted? m input))))
    (is (zero? @meta-reads))
    ;; A plain authored digest retains no definition, so it is safe metadata.
    (is (memo/admitted? m stamped))
    ;; The retained projection must not recursively acquire source metadata
    ;; when coverage instruments collection literals.
    (is (nil? (meta (provenance/retained-checker-metadata stamped))))
    (let [issuer (provenance/capture-marker-stamp
                   {:args {} :return-type :text} :example)
          marker (issuer (provenance/stamp {:fn-id :example :args {:x mutable} :return :text}
                                           {:args {} :return-type :text}))]
      (is (not (memo/admitted? m marker))))))


(deftest exact-input-comparison-keeps-representation
  (doseq [[a b] [[1 1N] [1.0M 1.00M] [0.0 -0.0] [[1] '(1)]
                 [(array-map :a 1 :b 2) (array-map :b 2 :a 1)]
                 [(with-meta {} (hash-map :graphden.types.check.provenance/input "a"))
                  (with-meta {} (hash-map :graphden.types.check.provenance/input "a"))]]]
    (is (not (data/same-input? a b))))
  (is (data/same-input? {:a [1 "s"]} {:a [1 "s"]})))


(deftest callback-admission-needs-explicit-static-identity
  (let [clock (atom 0) captured (atom 1)
        closure (fn [] @captured)
        m (memo/create-memo (assoc (options clock) :callbacks [identity]))]
    (is (memo/admitted? m identity))
    (is (not (memo/admitted? m inc)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reviewed callback ABI"
          (memo/create-memo (assoc (options clock) :callbacks [closure]))))))


(deftest derived-input-is-prepared-only-on-a-miss-and-keeps-reservations-bounded
  (let [m (memo/create-memo (options (atom 0)))
        source (atom {:source 1}) prepare-mode (atom :valid)
        prepared (atom 0) built (atom 0) consumed (atom 0) restored (atom 0)
        ops {:family [:org :root] :original-rich {} :prepare #(deref source)
             :prepare-build (fn [input]
                              (swap! prepared inc)
                              (case @prepare-mode
                                :valid (assoc input :definitions [1 2])
                                :mutable (assoc input :definitions (java.util.Date. 0))
                                :cancel (throw (InterruptedException. "preparation cancelled"))
                                nil))
             :build (fn [input] (swap! built inc) {:checked (:definitions input)})
             :restore (fn [_] (swap! restored inc))
             :consume #(do (swap! consumed inc) :result)
             :fallback (constantly :ordinary) :check-cancel (constantly nil)}]
    (is (= :result (memo/call-with-artifact m ops)))
    (is (= :result (memo/call-with-artifact m ops)))
    (is (= [1 1 2 1] [@prepared @built @consumed @restored]))
    (reset! source {:source 2})
    (doseq [mode [:missing :mutable]]
      (reset! prepare-mode mode)
      (is (= :ordinary (memo/call-with-artifact m ops)))
      (is (= 1 @built))
      (is (= 1 (:entries (memo/stats m))))
      (is (zero? (:pending (memo/stats m))))
      (is (zero? (:leases (memo/stats m)))))
    (reset! prepare-mode :cancel)
    (is (thrown? InterruptedException (memo/call-with-artifact m ops)))
    (is (zero? (:pending (memo/stats m))))
    (is (zero? (:leases (memo/stats m))))
    (let [before @prepared]
      (reset! source {:source (java.util.Date. 0)})
      (is (= :ordinary (memo/call-with-artifact m ops)))
      (is (= before @prepared) "Unadmitted base never reaches derived preparation"))
    (is (zero? (:pending (memo/stats m))))))


(deftest worker-ownership-and-pure-publication-boundary
  (let [clock (atom 0) m (memo/create-memo (options clock))
        original {} input (atom {:source 1}) built (atom 0) restored (atom [])
        cancelled? (atom false) consume-error? (atom false)
        ops {:family [:org :root] :original-rich original :prepare #(deref input)
             :build (fn [in] (swap! built inc) {:checked in})
             :restore #(swap! restored conj %)
             :consume #(if @consume-error? (throw (ex-info "consume" {})) :result)
             :fallback #(throw (ex-info "unexpected fallback" {}))
             :check-cancel #(when @cancelled? (throw (ex-info "cancelled" {})))}]
    (is (= :result (memo/call-with-artifact m ops)))
    (is (= :result (memo/call-with-artifact m ops)))
    (is (= 1 @built))
    (is (= [{:checked {:source 1}}] @restored))
    (reset! input {:source 2})
    (reset! consume-error? true)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"consume" (memo/call-with-artifact m ops)))
    (is (zero? (:pending (memo/stats m))))
    (reset! consume-error? false)
    (reset! cancelled? true)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled" (memo/call-with-artifact m ops)))
    (is (zero? (:pending (memo/stats m))))
    (is (zero? (:leases (memo/stats m))))
    (reset! cancelled? false)
    (is (= :result (memo/call-with-artifact m ops)))
    (is (= 4 @built))
    (reset! clock 1000)
    (is (= :result (memo/call-with-artifact m ops)))
    (is (= 5 @built))))


(deftest reservation-denial-does-not-allocate-a-key
  (let [clock (atom 0) m (memo/create-memo (assoc (options clock) :max-bytes 100000))
        original {} prepares (atom 0) fallback (atom 0)
        ops {:family [:root] :original-rich original
             :prepare #(do (swap! prepares inc) {:source 1})
             :build identity :restore identity :consume (constantly :result)
             :fallback #(swap! fallback inc) :check-cancel (constantly nil)}]
    (memo/call-with-artifact m ops)
    ;; The existing lease remains charged. A hit needs bounded transient room;
    ;; insufficient capacity uses the ordinary path before preparing any key.
    (is (= 1 (memo/call-with-artifact m ops)))
    (is (= 1 @prepares))
    (is (zero? (:pending (memo/stats m))))))


(deftest retire-disables-publication-without-releasing-a-running-builder
  (let [clock (atom 0) m (memo/create-memo (options clock))
        during (atom nil) original {}]
    (is (= :result
           (memo/call-with-artifact
             m {:family [:root] :original-rich original :prepare (constantly {:source 1})
                :build (fn [input]
                         (memo/retire! m)
                         (reset! during (memo/stats m))
                         input)
                :restore identity :consume (constantly :result)
                :fallback (constantly :fallback) :check-cancel (constantly nil)})))
    (is (= 100000 (:pending-bytes @during)))
    (is (zero? (:charged-bytes (memo/stats m))))
    (is (= :fallback
           (memo/call-with-artifact
             m {:family [:root] :original-rich original
                :fallback (constantly :fallback)})))))


(deftest distinct-roots-retain-independent-families
  (let [m (memo/create-memo (options (atom 0))) original {} builds (atom 0)
        run (fn [root]
              (memo/call-with-artifact
                m {:family [:org {:root root} false] :original-rich original
                   :prepare #(hash-map :root root)
                   :build #(do (swap! builds inc) %)
                   :restore identity :consume (constantly :result)
                   :fallback #(throw (ex-info "Unexpected fallback" {}))
                   :check-cancel (constantly nil)}))]
    (doseq [root [:a :b :a :b]] (is (= :result (run root))))
    (is (= 2 @builds))
    (is (= 2 (:families (memo/stats m))))))


(deftest reader-locations-are-fully-admitted-and-charged
  (let [m (memo/create-memo (options (atom 0)))
        positions {:source "fixture.edn" :line 1 :column 2 :end-line 1 :end-column 3}
        input (with-meta 'argument positions)]
    (is (memo/admitted? m input))
    (is (< (data/charge 'argument (:admission m))
           (data/charge input (:admission m))))
    (doseq [metadata [(assoc positions :source (atom "mutable"))
                      (assoc positions :line (java.util.Date. 0))
                      (assoc positions :unknown "unreviewed")]]
      (is (not (memo/admitted? m (with-meta 'argument metadata)))))
    (is (not (data/same-input? input (with-meta 'argument (into {} positions))))
        "Reader metadata admission does not weaken metadata identity")))


(deftest nonretained-artifacts-still-consume-without-poisoning-a-family
  (let [m (memo/create-memo (options (atom 0))) built (atom 0) consumed (atom 0)
        ops {:family [:tenant :root] :original-rich {} :prepare (constantly {:source 1})
             :build (fn [_] (swap! built inc) nil)
             :restore (fn [_] (throw (ex-info "Unretained artifact restored" {})))
             :consume #(swap! consumed inc) :fallback (constantly :fallback)
             :check-cancel (constantly nil)}]
    (try
      (is (= 1 (memo/call-with-artifact m ops)))
      (is (= 2 (memo/call-with-artifact m ops)))
      (is (= 2 @built))
      (is (= {:entries 0 :pending 0 :leases 0}
             (select-keys (memo/stats m) [:entries :pending :leases])))
      (finally (memo/retire! m)))))
