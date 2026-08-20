(ns graphden.util.json-safe-test
  "`json-safe` — ex-data leaves a JSON encoder can refuse."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [graphden.util.json-safe :as json-safe]))


(deftest keeps-encodable-values-untouched-test
  (let [data {:type :validation-error/type-mismatch
              :field :fn-id
              :count 3
              :ok? true
              :id (random-uuid)
              :nested {:xs [1 "two" :three nil]}}]
    (is (= data (json-safe/json-safe data))
        "nothing an encoder already accepts is rewritten")))


(deftest renders-unencodable-leaves-test
  (testing "a Class — the leaf that turned an honest 400 into a 500"
    (let [safe (json-safe/json-safe {:type :validation-error/type-mismatch
                                     :value-type String})]
      (is (= :validation-error/type-mismatch (:type safe)))
      (is (string? (:value-type safe)))
      (is (re-find #"java\.lang\.String" (:value-type safe))
          "the diagnostic survives, as text")))

  (testing "functions, atoms and objects nested anywhere"
    (let [safe (json-safe/json-safe {:fn (fn [] 1)
                                     :state (atom {:a 1})
                                     :deep [{:obj (Object.)}]})]
      (is (every? string? [(:fn safe) (:state safe)]))
      (is (string? (get-in safe [:deep 0 :obj])))))

  (testing "the result always encodes"
    (is (string? (json/generate-string
                   (json-safe/json-safe {:c String :f (fn [] 1)
                                         :a (atom 1) :ok 1}))))))


(deftest unencodable-map-keys-are-rendered-test
  ;; A map key is a leaf to the encoder too.
  (let [safe (json-safe/json-safe {String :was-a-class-key})]
    (is (= [:was-a-class-key] (vals safe)))
    (is (every? string? (keys safe)))
    (is (string? (json/generate-string safe)))))
