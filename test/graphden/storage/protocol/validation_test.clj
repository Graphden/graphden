(ns graphden.storage.protocol.validation-test
  "Tests for storage-protocol validation functions."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === validate-required-fields! tests ===

(deftest validate-required-fields!-test
  (testing "valid data with all required fields passes"
    (let [fields {:name {:type :text :nullable? false}
                  :email {:type :text :nullable? false}}
          data {:name "Alice" :email "alice@example.com"}]
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "missing required field throws"
    (let [fields {:name {:type :text :nullable? false}
                  :email {:type :text :nullable? false}}
          data {:name "Alice"}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'email' is missing or nil"
            (storage/validate-required-fields! :user fields data)))))

  (testing "nil required field throws"
    (let [fields {:name {:type :text :nullable? false}}
          data {:name nil}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'name' is missing or nil"
            (storage/validate-required-fields! :user fields data)))))

  (testing "nullable field can be nil"
    (let [fields {:name {:type :text :nullable? false}
                  :bio {:type :text :nullable? true}}
          data {:name "Alice" :bio nil}]
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "nullable field can be missing"
    (let [fields {:name {:type :text :nullable? false}
                  :bio {:type :text :nullable? true}}
          data {:name "Alice"}]
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing ":id field is ignored (auto-generated)"
    (let [fields {:id {:type :uuid :nullable? false}
                  :name {:type :text :nullable? false}}
          data {:name "Alice"}]  ; no :id provided
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "exception contains correct data"
    (try
      (storage/validate-required-fields! :user
                                         {:email {:type :text :nullable? false}}
                                         {:email nil})
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/required-field-missing (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :email (:field (ex-data e)))))))

  (testing "multiple fields - some nullable, some not, iterating through all"
    ;; This exercises more paths through the doseq
    (let [fields {:id {:type :uuid :nullable? false}    ; skipped (is :id)
                  :name {:type :text :nullable? false}  ; required
                  :bio {:type :text :nullable? true}    ; nullable
                  :email {:type :text :nullable? false} ; required
                  :avatar {:type :text :nullable? true}} ; nullable
          data {:name "Alice" :email "alice@example.com"}] ; bio and avatar missing but nullable
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "field with nil nullable spec is treated as required"
    ;; When :nullable? is missing from field-spec, (not (:nullable? field-spec)) = (not nil) = true
    (let [fields {:name {:type :text}}  ; no :nullable? key
          data {}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'name' is missing or nil"
            (storage/validate-required-fields! :user fields data)))))

  (testing "empty fields map passes with any data"
    (is (nil? (storage/validate-required-fields! :user {} {:foo "bar"}))))

  (testing "empty data with only nullable fields passes"
    (let [fields {:bio {:type :text :nullable? true}
                  :avatar {:type :text :nullable? true}}]
      (is (nil? (storage/validate-required-fields! :user fields {}))))))


;; === validate-no-duplicate-ids! tests ===

(deftest validate-no-duplicate-ids!-test
  (testing "unique IDs pass validation"
    (let [data-seq [{:id (random-uuid) :name "Alice"}
                    {:id (random-uuid) :name "Bob"}]]
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "data without explicit IDs passes (IDs auto-generated)"
    (let [data-seq [{:name "Alice"}
                    {:name "Bob"}]]
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "empty data-seq passes"
    (is (nil? (storage/validate-no-duplicate-ids! :user []))))

  (testing "single record passes"
    (let [id (random-uuid)]
      (is (nil? (storage/validate-no-duplicate-ids! :user [{:id id :name "Alice"}])))))

  (testing "duplicate IDs throw"
    (let [dup-id (random-uuid)
          data-seq [{:id dup-id :name "Alice"}
                    {:id (random-uuid) :name "Bob"}
                    {:id dup-id :name "Charlie"}]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Duplicate IDs found in batch"
            (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "exception contains correct data"
    (let [dup-id (random-uuid)
          data-seq [{:id dup-id} {:id dup-id}]]
      (try
        (storage/validate-no-duplicate-ids! :user data-seq)
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/duplicate-ids (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= [dup-id] (:duplicate-ids (ex-data e)))))))))


;; === validate-data-is-map! tests ===

(deftest validate-data-is-map!-test
  (testing "map data passes validation"
    (is (nil? (storage/validate-data-is-map! :user {:name "Alice"}))))

  (testing "empty map passes validation"
    (is (nil? (storage/validate-data-is-map! :user {}))))

  (testing "nil data throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user nil))))

  (testing "vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user [{:name "Alice"}]))))

  (testing "string throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user "not a map"))))

  (testing "integer throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user 123))))

  (testing "exception contains correct data"
    (try
      (storage/validate-data-is-map! :user [1 2 3])
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-data (:type (ex-data e))))
        (is (= :user (:entity-name (ex-data e))))
        (is (= [1 2 3] (:data (ex-data e))))
        (is (some? (:data-type (ex-data e))))))))


;; === validate-where-clause! tests ===

(deftest validate-where-clause!-test
  (testing "nil where clause passes"
    (is (nil? (storage/validate-where-clause! nil))))

  (testing "empty map passes"
    (is (nil? (storage/validate-where-clause! {}))))

  (testing "map with values passes"
    (is (nil? (storage/validate-where-clause! {:name "Alice" :active true}))))

  (testing "vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! [:name "Alice"]))))

  (testing "string throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! "name = 'Alice'"))))

  (testing "number throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! 123))))

  (testing "keyword throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! :name))))

  (testing "exception contains correct data"
    (try
      (storage/validate-where-clause! [:bad :data])
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-where-clause (:type (ex-data e))))
        (is (= [:bad :data] (:where (ex-data e))))
        (is (some? (:where-type (ex-data e))))))))


;; === check-graph-iteration-limit! tests ===

(deftest check-graph-iteration-limit!-test
  (testing "under limit doesn't throw"
    (is (nil? (storage/check-graph-iteration-limit! 0 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! 100 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! 9999 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! storage/*max-graph-iterations* (random-uuid)))))

  (testing "over limit throws"
    (let [fn-id (random-uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"exceeded maximum iterations"
            (storage/check-graph-iteration-limit! (inc storage/*max-graph-iterations*) fn-id)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)]
      (try
        (storage/check-graph-iteration-limit! 10001 fn-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/graph-too-large (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= storage/*max-graph-iterations* (:max-iterations (ex-data e))))
          (is (= 10001 (:iteration-count (ex-data e)))))))))


(deftest with-max-graph-iterations-test
  (testing "executes function and returns result"
    (is (= 42 (storage/with-max-graph-iterations 50000 #(+ 40 2)))))

  (testing "overrides limit within binding"
    (is (= 50000
           (storage/with-max-graph-iterations 50000
                                              #(deref #'storage/*max-graph-iterations*)))))

  (testing "allows higher iterations when limit is increased"
    (let [fn-id (random-uuid)]
      ;; Default limit is 10000, this should throw normally
      (is (thrown? clojure.lang.ExceptionInfo
            (storage/check-graph-iteration-limit! 15000 fn-id)))
      ;; But with increased limit it should not throw
      (is (nil? (storage/with-max-graph-iterations 20000
                                                   #(storage/check-graph-iteration-limit! 15000 fn-id))))))

  (testing "restores original limit after execution"
    (let [original storage/*max-graph-iterations*]
      (storage/with-max-graph-iterations 50000 #(identity :done))
      (is (= original storage/*max-graph-iterations*))))

  (testing "restores original limit after exception"
    (let [original storage/*max-graph-iterations*]
      (try
        (storage/with-max-graph-iterations 50000 #(throw (ex-info "test" {})))
        (catch Exception _))
      (is (= original storage/*max-graph-iterations*)))))


(deftest check-graph-iteration-limit-edge-cases-test
  (testing "exactly at limit doesn't throw"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 100 fn-id))))))

  (testing "one over limit throws"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (thrown-with-msg?
                                                clojure.lang.ExceptionInfo
                                                #"exceeded maximum iterations"
                                                (storage/check-graph-iteration-limit! 101 fn-id))))))

  (testing "at 79% of limit doesn't warn (below threshold)"
    ;; Testing that 79% doesn't trigger warning path
    ;; We can't easily test log output, but we verify no exception
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 79 fn-id))))))

  (testing "at 80% of limit still passes (warning threshold)"
    ;; 80% of limit triggers warning but doesn't throw
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 80 fn-id))))))

  (testing "at 99% of limit still passes"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 99 fn-id))))))

  (testing "works with very small limits"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 1
                                         #(do
                                            (is (nil? (storage/check-graph-iteration-limit! 0 fn-id)))
                                            (is (nil? (storage/check-graph-iteration-limit! 1 fn-id)))
                                            (is (thrown? clojure.lang.ExceptionInfo
                                                  (storage/check-graph-iteration-limit! 2 fn-id)))))))

  (testing "works with large limits"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 1000000
                                         #(is (nil? (storage/check-graph-iteration-limit! 999999 fn-id)))))))


;; === try-parse-uuid tests ===

(deftest try-parse-uuid-test
  (testing "returns UUID for UUID input"
    (let [u (random-uuid)]
      (is (= u (storage/try-parse-uuid u)))))

  (testing "returns UUID for valid UUID string"
    (let [u (random-uuid)
          s (str u)]
      (is (= u (storage/try-parse-uuid s)))))

  (testing "returns nil for invalid UUID string"
    (is (nil? (storage/try-parse-uuid "not-a-uuid")))
    (is (nil? (storage/try-parse-uuid "12345")))
    (is (nil? (storage/try-parse-uuid ""))))

  (testing "returns nil for non-string, non-UUID values"
    (is (nil? (storage/try-parse-uuid 12345)))
    (is (nil? (storage/try-parse-uuid nil)))
    (is (nil? (storage/try-parse-uuid :keyword)))
    (is (nil? (storage/try-parse-uuid [1 2 3])))))
