(ns graphden.storage-protocol.codec-test
  "Tests for generic codec utilities."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.codec :as codec]))


(deftest generic-encode-row-test
  (testing "encodes values using provided encode-fn"
    (let [encode-fn (fn [value _field-spec] (str "encoded:" value))
          row {:name "John" :age 30}
          field-specs {:name {:type :text} :age {:type :int}}
          result (codec/generic-encode-row encode-fn row field-specs)]
      (is (= {:name "encoded:John" :age "encoded:30"} result))))

  (testing "passes through values without field-spec"
    (let [encode-fn (fn [value _field-spec] (str "encoded:" value))
          row {:name "John" :unknown "value"}
          field-specs {:name {:type :text}}
          result (codec/generic-encode-row encode-fn row field-specs)]
      (is (= {:name "encoded:John" :unknown "value"} result))))

  (testing "applies key-transform option"
    (let [encode-fn (fn [value _spec] value)
          row {:user-name "John"}
          field-specs {:user-name {:type :text}}
          result (codec/generic-encode-row encode-fn row field-specs
                                           {:key-transform (comp keyword #(str/replace (name %) "-" "_"))})]
      (is (= {:user_name "John"} result))))

  (testing "uses fallback-specs for known fields"
    (let [encode-fn (fn [value field-spec]
                      (if (= :jsonb (:type field-spec))
                        {:json value}
                        value))
          row {:name "John" :data {:nested true}}
          field-specs {:name {:type :text}}
          result (codec/generic-encode-row encode-fn row field-specs
                                           {:fallback-specs {:data {:type :jsonb}}})]
      (is (= {:name "John" :data {:json {:nested true}}} result))))

  (testing "handles empty row"
    (let [encode-fn (fn [value _spec] value)
          result (codec/generic-encode-row encode-fn {} {})]
      (is (= {} result))))

  (testing "handles nil values"
    (let [encode-fn (fn [value _spec] (or value "DEFAULT"))
          row {:name nil :age 30}
          field-specs {:name {:type :text} :age {:type :int}}
          result (codec/generic-encode-row encode-fn row field-specs)]
      (is (= {:name "DEFAULT" :age 30} result)))))


(deftest generic-decode-row-test
  (testing "decodes values using provided decode-fn"
    (let [decode-fn (fn [value _field-spec] (str "decoded:" value))
          row {:name "stored" :age "25"}
          field-specs {:name {:type :text} :age {:type :int}}
          result (codec/generic-decode-row decode-fn row field-specs)]
      (is (= {:name "decoded:stored" :age "decoded:25"} result))))

  (testing "calls decode-fn for all values including those without field-spec"
    ;; decode-fn is always called - this allows backends to handle storage-native
    ;; types (e.g., PGobject) that need decoding even without explicit field-spec
    (let [decode-fn (fn [value _field-spec] (str "decoded:" value))
          row {:name "stored" :unknown "raw"}
          field-specs {:name {:type :text}}
          result (codec/generic-decode-row decode-fn row field-specs)]
      (is (= {:name "decoded:stored" :unknown "decoded:raw"} result))))

  (testing "applies key-transform option"
    (let [decode-fn (fn [value _spec] value)
          row {:user_name "John"}
          field-specs {:user-name {:type :text}}
          result (codec/generic-decode-row decode-fn row field-specs
                                           {:key-transform (fn [k] (keyword (str/replace (name k) "_" "-")))})]
      (is (= {:user-name "John"} result))))

  (testing "handles empty row"
    (let [decode-fn (fn [value _spec] value)
          result (codec/generic-decode-row decode-fn {} {})]
      (is (= {} result))))

  (testing "handles nil values correctly"
    (let [decode-fn (fn [value _spec] (when value (str "decoded:" value)))
          row {:name nil :age 30}
          field-specs {:name {:type :text} :age {:type :int}}
          result (codec/generic-decode-row decode-fn row field-specs)]
      (is (= {:name nil :age "decoded:30"} result)))))


(deftest encode-decode-roundtrip-test
  (testing "encode then decode produces original values"
    (let [;; Simulates a simple codec that uppercases text and stringifies ints
          encode-fn (fn [value field-spec]
                      (case (:type field-spec)
                        :text (str/upper-case value)
                        :int (str value)
                        value))
          decode-fn (fn [value field-spec]
                      (case (:type field-spec)
                        :text (str/lower-case value)
                        :int (Integer/parseInt value)
                        value))
          original {:name "john" :age 30}
          field-specs {:name {:type :text} :age {:type :int}}
          encoded (codec/generic-encode-row encode-fn original field-specs)
          decoded (codec/generic-decode-row decode-fn encoded field-specs)]
      (is (= {:name "JOHN" :age "30"} encoded))
      (is (= {:name "john" :age 30} decoded)))))


(deftest edge-cases-test
  (testing "encode-fn that throws propagates exception"
    (let [encode-fn (fn [_value _spec] (throw (ex-info "Encode failed" {:cause :test})))
          row {:name "test"}
          field-specs {:name {:type :text}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Encode failed"
            (codec/generic-encode-row encode-fn row field-specs)))))

  (testing "decode-fn that throws propagates exception"
    (let [decode-fn (fn [_value _spec] (throw (ex-info "Decode failed" {:cause :test})))
          row {:name "test"}
          field-specs {:name {:type :text}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Decode failed"
            (codec/generic-decode-row decode-fn row field-specs)))))

  (testing "handles special characters in field names"
    (let [encode-fn (fn [value _spec] value)
          row {:field-with-dashes "value" :field_with_underscores "value2"}
          field-specs {:field-with-dashes {:type :text}
                       :field_with_underscores {:type :text}}
          result (codec/generic-encode-row encode-fn row field-specs)]
      (is (= row result))))

  (testing "handles large rows efficiently"
    (let [encode-fn (fn [value _spec] value)
          large-row (into {} (map (fn [i] [(keyword (str "field" i)) i]) (range 100)))
          field-specs (into {} (map (fn [i] [(keyword (str "field" i)) {:type :int}]) (range 100)))
          result (codec/generic-encode-row encode-fn large-row field-specs)]
      (is (= 100 (count result)))
      (is (= large-row result)))))
