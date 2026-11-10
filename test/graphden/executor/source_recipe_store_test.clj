(ns graphden.executor.source-recipe-store-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.source-recipe-store :as recipes]))


(defn- fixture
  ([] (fixture {}))
  ([limits]
   (let [clock (atom 0) closed (atom #{})]
     {:clock clock :closed closed
      :store (recipes/create-store
               (merge {:max-bytes 100 :max-entries 4 :max-pending 2
                       :max-families 3 :max-leases 8 :max-ttl-ms 1000
                       :clock-ms #(deref clock) :closed? #(contains? @closed %)} limits))})))


(defn- family!
  [store]
  (recipes/create-family! store (Object.) 100))


(defn- put!
  [store family physical payload charge]
  (let [ticket (recipes/reserve! store family physical charge)]
    (is (some? ticket))
    (is (true? (recipes/publish! store ticket payload charge)))))


(deftest families-share-one-recipe-but-physical-keys-use-identity
  (let [{:keys [store]} (fixture)
        family (family! store)
        a (String. "equal") b (String. "equal")]
    (is (= a b))
    (put! store family a {:source :one} 30)
    (is (nil? (recipes/acquire! store family b)))
    (is (nil? (recipes/acquire! store (Object.) a)))
    (let [first-lease (recipes/acquire! store family a)
          second-lease (recipes/acquire! store family a)]
      (is (= {:source :one} (recipes/lease-value store first-lease)))
      (is (= {:source :one} (recipes/lease-value store second-lease)))
      (is (= 30 (:charged-bytes (recipes/stats store))))
      (is (= 1 (:entries (recipes/stats store))))
      (try (is (true? (recipes/evict-family! store family)))
           (finally (recipes/release! store first-lease)))
      (is (= 30 (:charged-bytes (recipes/stats store))))
      (is (= {:source :one} (recipes/lease-value store second-lease)))
      (is (true? (recipes/release! store second-lease)))
      (is (false? (recipes/release! store second-lease)))
      (is (nil? (recipes/lease-value store second-lease)))
      (is (zero? (:charged-bytes (recipes/stats store)))))))


(deftest pending-and-retained-use-one-budget-and-publish-is-one-shot
  (let [{:keys [store]} (fixture)
        family (family! store) a (Object.) b (Object.)
        ticket (recipes/reserve! store family a 70)]
    (is (some? ticket))
    (is (nil? (recipes/reserve! store family a 1)) "One builder per physical family")
    (is (nil? (recipes/reserve! store family b 31)))
    (is (true? (recipes/publish! store ticket :value 40)))
    (is (false? (recipes/publish! store ticket :stale 40)))
    (let [lease (recipes/acquire! store family a)
          pending (recipes/reserve! store family b 60)]
      (is (= 100 (:charged-bytes (recipes/stats store))))
      (is (= :value (recipes/lease-value store lease)))
      (is (false? (recipes/publish! store pending :oversized 61)))
      (is (= 100 (:charged-bytes (recipes/stats store))))
      (recipes/abort! store pending)
      (is (= 40 (:charged-bytes (recipes/stats store))))
      (recipes/release! store lease))))


(deftest expiry-does-not-free-a-running-builder-or-extend-on-registration
  (let [{:keys [store clock]} (fixture)
        family (family! store) physical (Object.)
        ticket (recipes/reserve! store family physical 80)]
    (reset! clock 90)
    (is (= family (recipes/create-family! store family 900)))
    (reset! clock 100)
    (is (= 80 (:pending-bytes (recipes/stats store))))
    (is (zero? (:families (recipes/stats store))))
    (is (nil? (recipes/acquire! store family physical)))
    (is (= family (recipes/create-family! store family 200)))
    (is (nil? (recipes/reserve! store family physical 10)) "Old builder remains bounded until unwind")
    (is (false? (recipes/publish! store ticket :late 80)))
    (is (= 80 (:pending-bytes (recipes/stats store))))
    (recipes/abort! store ticket)
    (is (zero? (:charged-bytes (recipes/stats store))))
    (let [fresh (recipes/reserve! store family physical 20)]
      (is (some? fresh))
      (is (true? (recipes/publish! store fresh :fresh 20))))))


(deftest generation-eviction-retires-leases-and-revokes-pending
  (let [{:keys [store]} (fixture)
        family (family! store) a (Object.) b (Object.)]
    (put! store family a :leased 40)
    (let [lease (recipes/acquire! store family a)
          ticket (recipes/reserve! store family b 60)]
      (recipes/evict-family! store family)
      (is (= {:retired 1 :pending 1 :charged-bytes 100}
             (select-keys (recipes/stats store) [:retired :pending :charged-bytes])))
      (recipes/create-family! store family 100)
      (is (nil? (recipes/reserve! store family (Object.) 1)))
      (is (false? (recipes/publish! store ticket :late 60)))
      (recipes/abort! store ticket)
      (is (= :leased (recipes/lease-value store lease)))
      (is (nil? (recipes/acquire! store family a)))
      (recipes/release! store lease)
      (is (zero? (:charged-bytes (recipes/stats store)))))))


(deftest closed-handles-evict-and-revoke-without-closing-a-borrow
  (let [{:keys [store closed]} (fixture)
        family (family! store) a (Object.) b (Object.)]
    (put! store family a :one 30)
    (let [lease (recipes/acquire! store family a)
          ticket (recipes/reserve! store family b 60)]
      (reset! closed #{a b})
      (is (nil? (recipes/acquire! store family a)))
      (is (nil? (recipes/reserve! store family b 1)))
      (is (= 90 (:charged-bytes (recipes/stats store))))
      (is (false? (recipes/publish! store ticket :closed 60)))
      (recipes/abort! store ticket)
      (is (= :one (recipes/lease-value store lease)))
      (recipes/release! store lease)
      (is (zero? (:charged-bytes (recipes/stats store)))))))


(deftest least-recently-used-unleased-entry-makes-room
  (let [{:keys [store]} (fixture {:max-entries 2})
        family (family! store) a (Object.) b (Object.) c (Object.)]
    (put! store family a :a 40)
    (put! store family b :b 40)
    (let [lease (recipes/acquire! store family a)] (recipes/release! store lease))
    (let [ticket (recipes/reserve! store family c 60)]
      (is (some? ticket))
      (is (nil? (recipes/acquire! store family b)))
      (is (= 100 (:charged-bytes (recipes/stats store))))
      (is (true? (recipes/abort! store ticket)))
      (is (false? (recipes/abort! store ticket)))
      (let [lease (recipes/acquire! store family a)]
        (try (is (= :a (recipes/lease-value store lease)))
             (finally (recipes/release! store lease)))))))


(deftest pending-and-family-cardinality-are-bounded-independently
  (let [{:keys [store]} (fixture {:max-pending 1 :max-families 1})
        family (family! store)
        ticket (recipes/reserve! store family (Object.) 1)]
    (is (nil? (family! store)))
    (is (nil? (recipes/reserve! store family (Object.) 1)))
    (is (nil? (recipes/create-family! store (Object.) 1001)))
    (is (nil? (recipes/reserve! store family (Object.) 0)))
    (recipes/abort! store ticket)
    (is (zero? (:charged-bytes (recipes/stats store))))))


(deftest racing-reservations-cannot-overbook-or-publish-after-abort
  (let [{:keys [store]} (fixture)
        family (family! store) physical (Object.) start (promise)
        workers (mapv (fn [_] (future @start (recipes/reserve! store family physical 70))) (range 8))]
    (deliver start true)
    (let [tickets (vec (keep #(deref % 5000 nil) workers))
          ticket (first tickets)]
      (is (= 1 (count tickets)))
      (is (= 70 (:charged-bytes (recipes/stats store))))
      (recipes/abort! store ticket)
      (let [next-ticket (recipes/reserve! store family physical 60)]
        (is (false? (recipes/publish! store ticket :old 70)))
        (is (= 60 (:pending-bytes (recipes/stats store))))
        (is (true? (recipes/publish! store next-ticket :new 60)))))))


(deftest invalid-limits-fail-at-construction-only
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit limits"
        (recipes/create-store {}))))


(deftest lease-count-is-bounded-and-finally-releases-on-failure
  (let [{:keys [store]} (fixture {:max-leases 1})
        family (family! store) physical (Object.)]
    (put! store family physical :value 40)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"consumer failed"
          (let [lease (recipes/acquire! store family physical)]
            (try
              (is (nil? (recipes/acquire! store family physical)))
              (is (= :value (recipes/lease-value store lease)))
              (throw (ex-info "consumer failed" {}))
              (finally (recipes/release! store lease))))))
    (is (zero? (:leases (recipes/stats store))))
    (let [lease (recipes/acquire! store family physical)]
      (is (some? lease))
      (recipes/release! store lease))))


(deftest very-large-charges-deny-capacity-without-overflow
  (let [{:keys [store]} (fixture {:max-bytes Long/MAX_VALUE})
        family (family! store)
        ticket (recipes/reserve! store family (Object.) Long/MAX_VALUE)]
    (is (some? ticket))
    (is (nil? (recipes/reserve! store family (Object.) 1)))
    (is (= Long/MAX_VALUE (:charged-bytes (recipes/stats store))))
    (recipes/abort! store ticket)))


(deftest cancellation-revokes-only-its-ticket-until-builder-finally
  (let [{:keys [store]} (fixture)
        family (family! store) a (Object.) b (Object.)]
    (put! store family a :shared 30)
    (let [ticket (recipes/reserve! store family b 70)]
      (is (true? (recipes/revoke! store ticket)))
      (is (false? (recipes/publish! store ticket :late 70)))
      (is (= 100 (:charged-bytes (recipes/stats store))))
      (is (nil? (recipes/reserve! store family b 1)))
      (let [lease (recipes/acquire! store family a)]
        (try (is (= :shared (recipes/lease-value store lease)))
             (finally (recipes/release! store lease))))
      ;; Actual worker on-done releases the reservation, independently of caller cancellation.
      (is (true? (recipes/abort! store ticket)))
      (is (= 30 (:charged-bytes (recipes/stats store))))
      (is (false? (recipes/revoke! store ticket))))))
