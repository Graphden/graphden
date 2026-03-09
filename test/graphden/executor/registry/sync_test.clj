(ns graphden.executor.registry.sync-test
  "Tests for fn-registry storage synchronization."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as core]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


;; === Storage Sync Tests ===

(deftest sync-defs-to-storage-test
  (testing "sync-defs-to-storage! creates fn-schemas and arg-schemas"
    (let [storage (setup/create-test-storage)
          defs {:my-add {:args {:a :numeric :b :numeric}
                         :return-type :numeric
                         :impl (fn [{:keys [a b]} _ctx] (+ a b))}
                :my-neg {:args {:n :numeric}
                         :return-type :numeric
                         :impl (fn [{:keys [n]} _ctx] (- n))}}]
      (try
        (let [result (registry/sync-defs-to-storage! storage defs)]
          (is (= 2 (:created (:fns result))))
          (is (zero? (:updated (:fns result))))
          (is (= 3 (:created (:args result)))) ; 2 for my-add, 1 for my-neg
          (is (zero? (:updated (:args result)))))

        ;; Verify fn-schemas exist
        (let [all-schemas (sp/query-entities storage :fn {})]
          (is (= 2 (count all-schemas)))
          (is (some #(= "my-add" (:name %)) all-schemas))
          (is (some #(= "my-neg" (:name %)) all-schemas)))

        ;; Verify arg-schemas exist
        (let [all-args (sp/query-entities storage :arg {})
              add-schema (first (sp/query-entities storage :fn {:name "my-add"}))
              add-args (sp/query-entities storage :arg {:fn-id (:id add-schema)})]
          (is (= 3 (count all-args)))
          (is (= 2 (count add-args)))
          (is (= #{"a" "b"} (set (map :name add-args)))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! is idempotent"
    (let [storage (setup/create-test-storage)
          defs {:idempotent-fn {:args {:x :any}
                                :return-type :any
                                :impl (fn [{:keys [x]} _ctx] x)}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs)
        (let [count-after-first (count (sp/query-entities storage :fn {}))]
          ;; Second sync
          (let [result (registry/sync-defs-to-storage! storage defs)]
            (is (zero? (:created (:fns result))))
            (is (= 1 (:updated (:fns result))))
            (is (zero? (:created (:args result))))
            (is (= 1 (:updated (:args result)))))
          ;; Same count
          (is (= count-after-first (count (sp/query-entities storage :fn {})))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - missing :type in map"
    (let [storage (setup/create-test-storage)
          invalid-defs {:bad-fn {:args {:x {:required false}} ; missing :type
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec map must contain :type key"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - invalid type"
    (let [storage (setup/create-test-storage)
          invalid-defs {:bad-fn {:args {:x "not-a-keyword"} ; invalid
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - nil"
    (let [storage (setup/create-test-storage)
          invalid-defs {:bad-fn {:args {:x nil}
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - number"
    (let [storage (setup/create-test-storage)
          invalid-defs {:bad-fn {:args {:x 123}
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! handles update when data actually changed"
    (let [storage (setup/create-test-storage)
          defs-v1 {:changing-fn {:args {:x :int}
                                 :return-type :int
                                 :impl (fn [{:keys [x]} _ctx] x)}}
          defs-v2 {:changing-fn {:args {:x :int :y :int}  ; Added arg
                                 :return-type :numeric    ; Changed return type
                                 :impl (fn [{:keys [x y]} _ctx] (+ x y))}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [schema-v1 (sp/read-entity storage :fn (registry/fn-uuid :changing-fn))]
          (is (= :int (:return-type schema-v1))))

        ;; Second sync with changes
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [schema-v2 (sp/read-entity storage :fn (registry/fn-uuid :changing-fn))
              all-args (sp/query-entities storage :arg {:fn-id (:id schema-v2)})]
          (is (= :numeric (:return-type schema-v2)))
          (is (= 2 (count all-args)))
          (is (= #{"x" "y"} (set (map :name all-args)))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! handles optional args"
    (let [storage (setup/create-test-storage)
          defs {:opt-fn {:args {:required :int
                                :optional {:type :text :required false}}
                         :return-type :any
                         :impl (fn [_ _] nil)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :opt-fn)
              args (sp/query-entities storage :arg {:fn-id fn-id})
              req-arg (first (filter #(= "required" (:name %)) args))
              opt-arg (first (filter #(= "optional" (:name %)) args))]
          (is (true? (:required req-arg)))
          (is (false? (:required opt-arg))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! updates only when return-type changes"
    (let [storage (setup/create-test-storage)
          defs-v1 {:ret-change {:args {:x :int}
                                :return-type :int
                                :impl (fn [_ _] 1)}}
          defs-v2 {:ret-change {:args {:x :int}
                                :return-type :numeric  ; Only return-type changed
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [schema-v1 (sp/read-entity storage :fn (registry/fn-uuid :ret-change))]
          (is (= :int (:return-type schema-v1))))
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [schema-v2 (sp/read-entity storage :fn (registry/fn-uuid :ret-change))]
          (is (= :numeric (:return-type schema-v2))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! updates when required changes"
    (let [storage (setup/create-test-storage)
          defs-v1 {:req-change {:args {:x {:type :int :required true}}
                                :return-type :int
                                :impl (fn [_ _] 1)}}
          defs-v2 {:req-change {:args {:x {:type :int :required false}}  ; required changed
                                :return-type :int
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [fn-id (registry/fn-uuid :req-change)
              arg-v1 (first (sp/query-entities storage :arg {:fn-id fn-id}))]
          (is (true? (:required arg-v1))))
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [fn-id (registry/fn-uuid :req-change)
              arg-v2 (first (sp/query-entities storage :arg {:fn-id fn-id}))]
          (is (false? (:required arg-v2))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! updates when type changes"
    (let [storage (setup/create-test-storage)
          defs-v1 {:type-change {:args {:x :int}
                                 :return-type :any
                                 :impl (fn [_ _] 1)}}
          defs-v2 {:type-change {:args {:x :numeric}  ; type changed
                                 :return-type :any
                                 :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [fn-id (registry/fn-uuid :type-change)
              arg-v1 (first (sp/query-entities storage :arg {:fn-id fn-id}))]
          (is (= :int (:type arg-v1))))
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [fn-id (registry/fn-uuid :type-change)
              arg-v2 (first (sp/query-entities storage :arg {:fn-id fn-id}))]
          (is (= :numeric (:type arg-v2))))
        (finally
          (sp/close storage))))))


;; === Short-circuit Coverage Tests ===
;; These tests ensure all branches of `or` conditions are evaluated

(deftest sync-fn-schema-or-branches-test
  (testing "sync-fn-schema! triggers update when only name differs"
    ;; This tests the first branch of the `or` in sync-fn-schema!
    (let [storage (setup/create-test-storage)
          defs {:name-test {:args {:x :int}
                            :return-type :int
                            :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :name-test)]
          ;; Manually corrupt name in storage
          (sp/update-entity storage :fn fn-id
                            {:name "wrong-name"
                             :return-type :int
                             })
          ;; Re-sync - should update because name differs
          (registry/sync-defs-to-storage! storage defs)
          (let [schema-after (sp/read-entity storage :fn fn-id)]
            (is (= "name-test" (:name schema-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! triggers update when only returned-type differs"
    ;; This tests the second branch of the `or` in sync-fn-schema!
    (let [storage (setup/create-test-storage)
          defs {:ret-test {:args {:x :int}
                           :return-type :numeric
                           :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :ret-test)]
          ;; Manually corrupt returned-type in storage (keep name correct)
          (sp/update-entity storage :fn fn-id
                            {:name "ret-test"
                             :return-type :text  ; Wrong type
                             })
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :fn fn-id)]
            (is (= :text (:return-type corrupted))))
          ;; Re-sync - should update because only returned-type differs
          (registry/sync-defs-to-storage! storage defs)
          (let [schema-after (sp/read-entity storage :fn fn-id)]
            (is (= :numeric (:return-type schema-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! triggers update when only impl-hash differs"
    ;; This tests the fourth branch of the `or` in sync-fn-schema!
    (let [storage (setup/create-test-storage)
          defs {:impl-hash-test {:args {:x :int}
                                 :return-type :int
                                 :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :impl-hash-test)
              schema-before (sp/read-entity storage :fn fn-id)]
          (is (some? (:impl-hash schema-before)))
          ;; Manually corrupt impl-hash in storage (keep name, returned-type correct)
          (sp/update-entity storage :fn fn-id
                            {:name "impl-hash-test"
                             :return-type :int
                             
                             :impl-hash "corrupted-hash"})
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :fn fn-id)]
            (is (= "corrupted-hash" (:impl-hash corrupted))))
          ;; Re-sync - should update because impl-hash differs
          (registry/sync-defs-to-storage! storage defs)
          (let [schema-after (sp/read-entity storage :fn fn-id)]
            (is (not= "corrupted-hash" (:impl-hash schema-after)))
            (is (= (:impl-hash schema-before) (:impl-hash schema-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! does NOT update when nothing differs"
    ;; This tests when all four `or` branches are false
    (let [storage (setup/create-test-storage)
          defs {:no-change {:args {:x :int}
                            :return-type :int
                            :impl (fn [_ _] 1)}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs)
        ;; Second sync with same data - should not trigger update
        (let [result (registry/sync-defs-to-storage! storage defs)]
          ;; updated should be 1 because the function goes through update path
          ;; but sp/update-entity shouldn't be called (when condition is false)
          (is (= 1 (:updated (:fns result)))))
        (finally
          (sp/close storage))))))


(deftest sync-arg-schema-or-branches-test
  (testing "sync-arg-schemas! triggers update when only fn-schema-id differs"
    ;; This tests the first branch of the `or` in sync-arg-schemas!
    (let [storage (setup/create-test-storage)
          defs {:arg-fn-test {:args {:x :int}
                              :return-type :int
                              :impl (fn [_ _] 1)}}
          ;; Create a different fn-schema to use as wrong reference
          wrong-schema (sp/create-entity storage :fn
                                         {:name "wrong-schema"
                                          :return-type :text})]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :arg-fn-test)
              arg-id (registry/arg-uuid :arg-fn-test :x)]
          ;; Manually corrupt fn-schema-id in arg-schema
          (sp/update-entity storage :arg arg-id
                            {:fn-id (:id wrong-schema)
                             :name "x"
                             :type :int
                             :required true})
          ;; Re-sync - should update because fn-schema-id differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg arg-id)]
            (is (= fn-id (:fn-id arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! triggers update when only name differs"
    ;; This tests the second branch of the `or` in sync-arg-schemas!
    (let [storage (setup/create-test-storage)
          defs {:arg-name-test {:args {:myarg :int}
                                :return-type :int
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :arg-name-test)
              arg-id (registry/arg-uuid :arg-name-test :myarg)]
          ;; Manually corrupt name in arg-schema
          (sp/update-entity storage :arg arg-id
                            {:fn-id fn-id
                             :name "wrong-name"
                             :type :int
                             :required true})
          ;; Re-sync - should update because name differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg arg-id)]
            (is (= "myarg" (:name arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! triggers update when only type differs"
    ;; This tests the third branch of the `or` in sync-arg-schemas!
    (let [storage (setup/create-test-storage)
          defs {:arg-type-test {:args {:z :numeric}
                                :return-type :int
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :arg-type-test)
              arg-id (registry/arg-uuid :arg-type-test :z)]
          ;; Manually corrupt type in arg-schema (keep fn-schema-id, name, required correct)
          (sp/update-entity storage :arg arg-id
                            {:fn-id fn-id
                             :name "z"
                             :type :text  ; Wrong type
                             :required true})
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :arg arg-id)]
            (is (= :text (:type corrupted))))
          ;; Re-sync - should update because only type differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg arg-id)]
            (is (= :numeric (:type arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! triggers update when only required differs"
    ;; This tests the fourth branch of the `or` in sync-arg-schemas!
    (let [storage (setup/create-test-storage)
          defs {:arg-req-test {:args {:w {:type :int :required false}}
                               :return-type :int
                               :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-uuid :arg-req-test)
              arg-id (registry/arg-uuid :arg-req-test :w)]
          ;; Manually corrupt required in arg-schema (keep fn-schema-id, name, type correct)
          (sp/update-entity storage :arg arg-id
                            {:fn-id fn-id
                             :name "w"
                             :type :int
                             :required true})  ; Wrong required
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :arg arg-id)]
            (is (true? (:required corrupted))))
          ;; Re-sync - should update because only required differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg arg-id)]
            (is (false? (:required arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! does NOT update when nothing differs"
    ;; This tests when all four `or` branches are false
    (let [storage (setup/create-test-storage)
          defs {:arg-nochange {:args {:p :int}
                               :return-type :int
                               :impl (fn [_ _] 1)}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs)
        ;; Second sync with same data
        (let [result (registry/sync-defs-to-storage! storage defs)]
          ;; updated should be 1 because the arg goes through update path
          (is (= 1 (:updated (:args result)))))
        (finally
          (sp/close storage))))))


;; === sync-defs-to-storage! Core Function Tests ===

(deftest sync-defs-to-storage-core-test
  (testing "creates new fn-schema and arg-schema entries"
    (let [storage (setup/create-test-storage)]
      ;; create-test-storage returns storage already initialized with graph schema
      (try
        (let [defs {:test-fn {:args {:x :int :y :int}
                              :return-type :int}}
              result (core/sync-defs-to-storage! storage defs)]
          (is (= 1 (:created (:fns result))))
          (is (zero? (:updated (:fns result))))
          (is (= 2 (:created (:args result))))
          (is (zero? (:updated (:args result)))))
        (finally
          (sp/close storage)))))

  (testing "updates existing fn-schema when re-syncing"
    (let [storage (setup/create-test-storage)]
      (try
        ;; First sync
        (core/sync-defs-to-storage! storage {:test-fn {:args {:x :int} :return-type :int}})
        ;; Second sync (update)
        (let [result (core/sync-defs-to-storage! storage {:test-fn {:args {:x :int} :return-type :int}})]
          (is (zero? (:created (:fns result))))
          (is (= 1 (:updated (:fns result))))
          (is (zero? (:created (:args result))))
          (is (= 1 (:updated (:args result)))))
        (finally
          (sp/close storage)))))

  (testing "sync is idempotent with deterministic UUIDs"
    (let [storage (setup/create-test-storage)]
      (try
        (let [defs {:add {:args {:a :int :b :int} :return-type :int}}
              fn-id-before (core/fn-uuid :add)]
          (core/sync-defs-to-storage! storage defs)
          ;; Verify the fn-schema was created with expected UUID
          (let [entity (sp/read-entity storage :fn fn-id-before)]
            (is (some? entity))
            (is (= "add" (:name entity)))))
        (finally
          (sp/close storage)))))

  (testing "handles empty defs map"
    (let [storage (setup/create-test-storage)]
      (try
        (let [result (core/sync-defs-to-storage! storage {})]
          (is (= {:fns {:created 0 :updated 0}
                  :args {:created 0 :updated 0}}
                 result)))
        (finally
          (sp/close storage)))))

  (testing "handles optional args correctly"
    (let [storage (setup/create-test-storage)]
      (try
        (let [defs {:opt-fn {:args {:x :int
                                    :y {:type :int :required false}}
                             :return-type :int}}]
          (core/sync-defs-to-storage! storage defs)
          ;; Verify the optional arg was stored correctly
          (let [arg-id (core/arg-uuid :opt-fn :y)
                arg-entity (sp/read-entity storage :arg arg-id)]
            (is (some? arg-entity))
            (is (false? (:required arg-entity)))))
        (finally
          (sp/close storage)))))

  (testing "fails fast on invalid definition"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Should throw before any storage operations
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown return type"
              (core/sync-defs-to-storage! storage {:bad-fn {:args {} :return-type :invalid}})))
        (finally
          (sp/close storage))))))


;; === sync-fn-schema! Update Path Tests ===

(deftest sync-fn-schema-update-test
  (testing "updates fn-schema when returned-type changes"
    (let [storage (setup/create-test-storage)]
      (try
        ;; First sync with :int return type
        (core/sync-defs-to-storage! storage {:my-fn {:args {} :return-type :int}})
        (let [fn-id (core/fn-uuid :my-fn)
              before (sp/read-entity storage :fn fn-id)]
          (is (= :int (:return-type before)))
          ;; Second sync with :text return type
          (core/sync-defs-to-storage! storage {:my-fn {:args {} :return-type :text}})
          (let [after (sp/read-entity storage :fn fn-id)]
            (is (= :text (:return-type after)))))
        (finally
          (sp/close storage)))))

  (testing "does not update fn-schema when nothing changed"
    (let [storage (setup/create-test-storage)]
      (try
        (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})
        ;; Same sync again - should report as update but no actual change
        (let [result (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})]
          (is (= 1 (:updated (:fns result)))))
        (finally
          (sp/close storage))))))


;; === sync-arg-schemas! Update Path Tests ===

(deftest sync-arg-schemas-update-test
  (testing "updates arg-schema when type changes"
    (let [storage (setup/create-test-storage)]
      (try
        (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})
        (let [arg-id (core/arg-uuid :my-fn :x)
              before (sp/read-entity storage :arg arg-id)]
          (is (= :int (:type before)))
          ;; Change arg type to :text
          (core/sync-defs-to-storage! storage {:my-fn {:args {:x :text} :return-type :int}})
          (let [after (sp/read-entity storage :arg arg-id)]
            (is (= :text (:type after)))))
        (finally
          (sp/close storage)))))

  (testing "updates arg-schema when required changes"
    (let [storage (setup/create-test-storage)]
      (try
        (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})
        (let [arg-id (core/arg-uuid :my-fn :x)
              before (sp/read-entity storage :arg arg-id)]
          (is (true? (:required before)))
          ;; Change to optional
          (core/sync-defs-to-storage! storage {:my-fn {:args {:x {:type :int :required false}} :return-type :int}})
          (let [after (sp/read-entity storage :arg arg-id)]
            (is (false? (:required after)))))
        (finally
          (sp/close storage))))))


;; === Batch Size Limit Tests ===

(deftest sync-defs-batch-limit-test
  (testing "throws when batch exceeds max-sync-batch-size"
    (let [storage (setup/create-test-storage)
          ;; Create 501 function definitions (max is 500)
          large-defs (into {}
                           (for [i (range 501)]
                             [(keyword (str "fn-" i))
                              {:args {} :return-type :int}]))]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Too many function definitions to sync"
              (core/sync-defs-to-storage! storage large-defs)))
        (finally
          (sp/close storage)))))

  (testing "exception contains batch size info"
    (let [storage (setup/create-test-storage)
          large-defs (into {}
                           (for [i (range 502)]
                             [(keyword (str "fn-" i))
                              {:args {} :return-type :int}]))]
      (try
        (try
          (core/sync-defs-to-storage! storage large-defs)
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :batch-error/batch-too-large (:type (ex-data e))))
            (is (= 502 (:batch-size (ex-data e))))
            (is (= 500 (:max-batch-size (ex-data e))))))
        (finally
          (sp/close storage)))))

  (testing "accepts batch at exactly max size"
    (let [storage (setup/create-test-storage)
          ;; Create exactly 500 function definitions (max allowed)
          max-defs (into {}
                         (for [i (range 500)]
                           [(keyword (str "fn-" i))
                            {:args {} :return-type :int}]))]
      (try
        (let [result (core/sync-defs-to-storage! storage max-defs)]
          (is (= 500 (:created (:fns result)))))
        (finally
          (sp/close storage))))))


;; === parse-arg-spec :required Validation Tests ===

(deftest parse-arg-spec-required-validation-test
  (testing "throws when :required is not a boolean"
    (let [storage (setup/create-test-storage)
          invalid-defs {:bad-fn {:args {:x {:type :int :required "yes"}}
                                 :return-type :int}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":required must be a boolean"
              (core/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "exception contains arg-spec info"
    (let [storage (setup/create-test-storage)
          invalid-defs {:bad-fn {:args {:x {:type :int :required 1}}
                                 :return-type :int}}]
      (try
        (try
          (core/sync-defs-to-storage! storage invalid-defs)
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :invalid-arg-spec (:type (ex-data e))))
            (is (= :x (:arg-name (ex-data e))))
            (is (= 1 (:required-value (ex-data e))))))
        (finally
          (sp/close storage))))))
