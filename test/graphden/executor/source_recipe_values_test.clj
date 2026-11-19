(ns graphden.executor.source-recipe-values-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.source-recipe-values :as values])
  (:import
    (java.sql
      Timestamp)
    (java.util
      Date)
    (org.postgresql.util
      PGobject)))


(deftest recipe-copies-isolate-original-and-replayed-driver-values-test
  (let [json (doto (PGobject.) (PGobject/.setType "jsonb") (PGobject/.setValue "{\"n\":1}"))
        timestamp (doto (Timestamp. 1700000000123) (Timestamp/.setNanos 123456789))
        date (Date. 12345)
        frozen (values/freeze [{:json json :timestamp timestamp :date date}] 65536)]
    (PGobject/.setValue json "{\"n\":2}")
    (Timestamp/.setNanos timestamp 987654321)
    (Date/.setTime date 99999)
    (let [first-row (first (values/thaw frozen))]
      (is (= "{\"n\":1}" (PGobject/.getValue ^PGobject (:json first-row))))
      (is (= 123456789 (Timestamp/.getNanos ^Timestamp (:timestamp first-row))))
      (is (= 12345 (Date/.getTime ^Date (:date first-row))))
      (PGobject/.setValue ^PGobject (:json first-row) "changed by replay consumer")
      (Timestamp/.setNanos ^Timestamp (:timestamp first-row) 0)
      (Date/.setTime ^Date (:date first-row) 0))
    (let [next-row (first (values/thaw frozen))]
      (is (= "jsonb" (PGobject/.getType ^PGobject (:json next-row))))
      (is (= "{\"n\":1}" (PGobject/.getValue ^PGobject (:json next-row))))
      (is (= 123456789 (Timestamp/.getNanos ^Timestamp (:timestamp next-row))))
      (is (= 12345 (Date/.getTime ^Date (:date next-row)))))))


(deftest replay-retains-driver-and-number-types-test
  (let [data [{:date (java.sql.Date. 12345) :time (java.sql.Time. 67890)
               :decimal 1.00M :bigint 7N :integer (int 7) :long (long 7)
               :negative-zero -0.0 :id #uuid "00000000-0000-0000-0000-000000000123"
               :nested [nil false "Привет 🌍"]}]
        replay (values/thaw (values/freeze data 65536))]
    (is (= data replay))
    (doseq [key [:date :time :decimal :bigint :integer :long :negative-zero]]
      (is (= (class (get (first data) key)) (class (get (first replay) key)))))
    (is (= "-0.0" (str (:negative-zero (first replay)))))
    (is (= 2 (java.math.BigDecimal/.scale ^java.math.BigDecimal (:decimal (first replay)))))))


(deftest recipe-copies-reject-unknown-values-and-exhausted-reservations-test
  (testing "unknown mutable values and subclasses cannot silently retain references"
    (doseq [value [(atom 1) (StringBuilder. "mutable") (byte-array [1 2])
                   (proxy [Date] [12345])
                   (with-meta {:value 1} {:hidden (atom 1)})
                   {(Date. 12345) :mutable-key}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ineligible"
            (values/freeze [{:value value}] 65536)))))
  (testing "byte accounting bounds retained objects, including string storage"
    (let [data [{:value (String/.repeat "x" 1000)}]
          frozen (values/freeze data 65536)
          charge (values/charge-bytes frozen)]
      (is (> charge 4000))
      (is (= data (values/thaw (values/freeze data charge))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ineligible"
            (values/freeze data (dec charge))))))
  (testing "deep values cannot exhaust the stack before admission fails"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ineligible"
          (values/freeze (nth (iterate vector nil) 66) 65536))))
  (testing "keyword names count toward the reservation too"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ineligible"
          (values/freeze {(keyword (String/.repeat "x" 2000)) nil} 1024)))))
