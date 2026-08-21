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


(def ^:private hostile-leaves
  "One of each kind of value that has actually turned up in ex-data, or
   plausibly could: authors put whatever helps them debug in there. The
   named ones are only the seeds — the POINT is the invariant below, not
   any single entry."
  {:class String
   :fn (fn [] 1)
   :atom (atom 1)
   :object (Object.)
   :throwable (RuntimeException. "boom")
   :exception (ex-info "boom" {:nested String})
   :regex #"x+"
   :bytes (byte-array 3)
   :array (into-array String ["a"])
   :stream (java.io.ByteArrayInputStream. (byte-array 1))
   :ratio 22/7
   :char \x
   :date (java.util.Date.)
   :instant (java.time.Instant/now)
   :lazy (map inc [1 2 3])
   :set #{1 2}})


(deftest every-shape-of-ex-data-encodes-test
  ;; The invariant, not a sample: `json-safe` exists so that NO ex-data can
  ;; turn a well-typed failure into an opaque 500. A leaf reaches the encoder
  ;; from three positions — plain, nested inside a collection, and as a map
  ;; KEY — and each is walked differently.
  (doseq [[k v] hostile-leaves]
    (testing (str "leaf " k)
      (doseq [[position data] {:plain {:type :validation-error/x k v}
                               :nested {:type :validation-error/x
                                        :deep {:xs [{:leaf v}] :in-set #{:ok}}}
                               :as-key {v :was-a-key}}]
        (is (string? (json/generate-string (json-safe/json-safe data)))
            (str "ex-data carrying " k " " (name position)
                 " must still encode — otherwise the caller gets a 500 and a"
                 " log ref instead of the error the author raised"))))))


(deftest walking-preserves-collection-shape-test
  ;; The rewrite must not quietly change a vector into a list or lose a set:
  ;; `:error-data` is read back by the editor's error panel, which renders by
  ;; shape.
  (let [safe (json-safe/json-safe {:v [1 String] :s #{String} :m {:a [String]}})]
    (is (vector? (:v safe)) "a vector stays a vector")
    (is (set? (:s safe)) "a set stays a set")
    (is (vector? (get-in safe [:m :a])) "nested vectors too")
    (is (= 1 (first (:v safe))) "and the encodable siblings are untouched")))
