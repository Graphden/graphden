(ns graphden.executor.source-recipe-jdbc-rows-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.source-recipe-jdbc-rows :as rows]
    [graphden.executor.source-recipe-values :as values]
    [next.jdbc.result-set :as rs])
  (:import
    (java.sql
      Timestamp)
    (java.util
      Date)
    (org.postgresql.util
      PGobject)))


(def ^:private limit (* 16 1024 1024))


(defn- rejection
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest columnar-roundtrip-preserves-schema-presence-and-row-order-test
  (let [input [(array-map :id 1 :nullable nil "label" "first" nil false)
               (array-map :id 2 :nullable nil "label" nil nil nil)
               {:id 3}
               {:id 4 :nullable nil}
               {}]
        frozen (rows/freeze-columnar input limit)
        output (rows/thaw frozen nil {})]
    (is (= input output))
    (is (= [true true false true false] (mapv #(contains? % :nullable) output)))
    (is (= [true true false false false] (mapv #(contains? % nil) output)))
    (is (= [1 2 3 4 nil] (mapv :id output)))
    (is (= [] (rows/thaw (rows/freeze-columnar [] limit) nil {}))))
  (testing "the original one-row and multirow APIs still replay their original shapes"
    (is (= {:id 1} (rows/thaw (rows/freeze {:id 1} true limit) nil {})))
    (is (nil? (rows/thaw (rows/freeze nil true limit) nil {})))
    (is (= [{:id 1}] (rows/thaw (rows/freeze [{:id 1}] false limit) nil {})))))


(deftest columnar-driver-values-are-isolated-on-every-thaw-test
  (let [json (doto (PGobject.) (PGobject/.setType "jsonb") (PGobject/.setValue "{\"a\":1}"))
        timestamp (doto (Timestamp. 12345) (Timestamp/.setNanos 123456789))
        date (Date. 54321)
        input [{:json json :timestamp timestamp :date date :decimal 1.00M
                :integer (int 7) :long (long 7) :negative-zero -0.0
                :nested [nil false {:value "nested"}]}]
        frozen (rows/freeze-columnar input limit)]
    (PGobject/.setValue json "changed input")
    (Timestamp/.setNanos timestamp 0)
    (Date/.setTime date 0)
    (let [first-row (first (rows/thaw frozen nil {}))]
      (is (= "{\"a\":1}" (PGobject/.getValue ^PGobject (:json first-row))))
      (is (= 123456789 (Timestamp/.getNanos ^Timestamp (:timestamp first-row))))
      (is (= 54321 (Date/.getTime ^Date (:date first-row))))
      (is (= [Integer Long] (mapv #(class (get first-row %)) [:integer :long])))
      (is (= 2 (java.math.BigDecimal/.scale ^java.math.BigDecimal (:decimal first-row))))
      (is (= "-0.0" (str (:negative-zero first-row))))
      (is (= [nil false {:value "nested"}] (:nested first-row)))
      (PGobject/.setValue ^PGobject (:json first-row) "changed replay")
      (Timestamp/.setNanos ^Timestamp (:timestamp first-row) 0)
      (Date/.setTime ^Date (:date first-row) 0))
    (let [next-row (first (rows/thaw frozen nil {}))]
      (is (= "jsonb" (PGobject/.getType ^PGobject (:json next-row))))
      (is (= "{\"a\":1}" (PGobject/.getValue ^PGobject (:json next-row))))
      (is (= 123456789 (Timestamp/.getNanos ^Timestamp (:timestamp next-row))))
      (is (= 54321 (Date/.getTime ^Date (:date next-row)))))))


(deftest columnar-navigation-metadata-is-created-for-the-current-lease-test
  (let [old-connection (Object.)
        new-connection (Object.)
        opts {:builder-fn rs/as-unqualified-lower-maps}
        original (rs/datafiable-row {:id 1 :nullable nil} old-connection opts)
        frozen (rows/freeze-columnar [original {:id 2 :nullable nil}] limit)
        calls (atom [])
        datafiable-row rs/datafiable-row
        [first-copy second-copy]
        (with-redefs [rs/datafiable-row (fn [row connection options]
                                          (swap! calls conj [row connection options])
                                          (datafiable-row row connection options))]
          [(rows/thaw frozen new-connection opts)
           (rows/thaw frozen new-connection opts)])]
    (is (= [[{:id 1 :nullable nil} new-connection opts]
            [{:id 1 :nullable nil} new-connection opts]] @calls))
    (is (nil? (meta (second first-copy))))
    (is (= (set (keys (meta original))) (set (keys (meta (first first-copy))))))
    (is (every? false? (map identical? (vals (meta original))
                            (vals (meta (first first-copy))))))
    (is (every? false? (map identical? (vals (meta (first first-copy)))
                            (vals (meta (first second-copy))))))))


(deftest columnar-admission-rejects-unknown-shapes-and-callbacks-test
  (let [called (atom 0)
        callback (fn [& _] (swap! called inc))]
    (doseq [[input reason] [[(list {:id 1}) :jdbc-result-shape]
                            [(with-meta [] {:unknown callback}) :jdbc-result-metadata]
                            [[(sorted-map :id 1)] :jdbc-row-shape]
                            [[(with-meta {:id 1} {'clojure.core.protocols/datafy callback})]
                             :jdbc-row-metadata]
                            [[(with-meta {:id 1} {:unknown callback})] :jdbc-row-metadata]
                            [[(with-meta {:id 1} {'unknown/a nil 'unknown/b nil})] :jdbc-row-metadata]]]
      (is (= {:type ::values/ineligible :reason reason}
             (rejection #(rows/freeze-columnar input limit)))))
    (doseq [value [callback (atom 1) (byte-array [1 2])]]
      (is (= ::values/ineligible
             (:type (rejection #(rows/freeze-columnar [{:id 1 :value value}] limit))))))
    (is (= ::values/ineligible
           (:type (rejection #(rows/freeze-columnar
                                [{:nested (with-meta {:id 1} {:unknown callback})}] limit)))))
    (is (= ::values/ineligible
           (:type (rejection #(rows/freeze-columnar [{:literal-set #{:unsupported}}] limit)))))
    (is (zero? @called))))


(deftest columnar-keys-are-rejected-before-custom-hashing-test
  (let [called (atom 0)
        key (proxy [Object] []
              (hashCode [] (swap! called inc) (throw (ex-info "Hash must not run" {}))))
        input [(array-map key :unsupported)]
        metadata-input [(with-meta {:id 1} (array-map key :unsupported 'clojure.core.protocols/datafy nil))]]
    (is (= {:type ::values/ineligible :reason :jdbc-column-key}
           (rejection #(rows/freeze-columnar input limit))))
    (is (= {:type ::values/ineligible :reason :jdbc-row-metadata}
           (rejection #(rows/freeze-columnar metadata-input limit))))
    (is (zero? @called))))


(deftest columnar-reservation-charges-reconstruction-and-has-an-exact-bound-test
  (let [input (vec (repeat 10 {:id 1 :nil-column nil}))
        frozen (rows/freeze-columnar input limit)
        charge (rows/charge-bytes frozen)]
    (is (> charge (+ 512 (* 10 (+ 512 (* 64 2))))))
    (is (= input (rows/thaw (rows/freeze-columnar input charge) nil {})))
    (is (= {:type ::values/ineligible :reason :byte-limit}
           (rejection #(rows/freeze-columnar input (dec charge)))))
    (is (= :byte-limit (:reason (rejection #(rows/freeze-columnar input 512)))))))


(deftest columnar-dictionaries-and-nil-columns-fit-a-bounded-manifest-table-test
  ;; Match a large binding table's shape: repeated nullable schema, five live
  ;; columns, and thousands of independent rows. No identity-based discount.
  (let [nullable (into {} (map #(vector (keyword (str "nullable-" %)) nil)) (range 15))
        input (mapv (fn [n]
                      (assoc nullable :id n :fn-id (+ 10000 n) :slot-id (+ 20000 n)
                             :terminal false :value "binding"))
                    (range 2874))
        frozen (rows/freeze-columnar input limit)]
    (is (< (rows/charge-bytes frozen) limit))
    (is (> (rows/charge-bytes frozen) (* 2874 (+ 512 (* 64 20)))))
    (is (= input (rows/thaw frozen nil {})))))
