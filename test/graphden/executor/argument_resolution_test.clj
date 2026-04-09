(ns graphden.executor.argument-resolution-test
  "Tests for argument resolution edge cases: lazy sequences, depth limits.

   ## 2-Entity Schema

   Arguments are stored directly in the arg entity:
   - arg.value → literal JSONB value
   - arg.ref-id → FK to fn (execute and use result)
   - arg.is-fn → pass fn-id directly (for HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.argument-resolution :as arg-res]
    [graphden.executor.test-helpers :as th]
    [graphden.storage.protocol.config :as config]
    [graphden.storage.protocol.graph :as graph]))


(deftest realize-lazy-seq-bounded-test
  (testing "realizes lazy sequence within limit"
    (let [result (#'arg-res/realize-lazy-seq-bounded (map inc [1 2 3]) 10)]
      (is (= [2 3 4] result))))

  (testing "throws when lazy sequence exceeds max-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Lazy sequence exceeds maximum allowed size"
          (#'arg-res/realize-lazy-seq-bounded (range 100) 10))))

  (testing "realizes exactly at max-size"
    (let [result (#'arg-res/realize-lazy-seq-bounded (range 5) 5)]
      (is (= [0 1 2 3 4] result))))

  (testing "handles empty sequence"
    (let [result (#'arg-res/realize-lazy-seq-bounded (map inc []) 10)]
      (is (= [] result)))))


(deftest realize-lazy-value-test
  (testing "passes through nil"
    (is (nil? (#'arg-res/realize-lazy-value nil))))

  (testing "passes through non-lazy values"
    (is (= 42 (#'arg-res/realize-lazy-value 42)))
    (is (= "hello" (#'arg-res/realize-lazy-value "hello")))
    (is (= [1 2 3] (#'arg-res/realize-lazy-value [1 2 3])))
    (is (= #{1 2} (#'arg-res/realize-lazy-value #{1 2}))))

  (testing "realizes lazy sequences"
    (let [result (#'arg-res/realize-lazy-value (map inc [1 2 3]))]
      (is (= [2 3 4] result))
      (is (vector? result))))

  (testing "realizes maps with lazy values recursively"
    (let [result (#'arg-res/realize-lazy-value {:a (map inc [1 2]) :b 42})]
      (is (= {:a [2 3] :b 42} result))))

  (testing "realizes other seqable types like range"
    (let [result (#'arg-res/realize-lazy-value (range 5))]
      (is (= [0 1 2 3 4] result))))

  (testing "throws on collection depth exceeding limit"
    (binding [config/*max-nested-collection-depth* 3]
      ;; Build nested map 4 levels deep
      (let [deep-map {:a {:b {:c {:d 1}}}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Collection nesting exceeds maximum allowed depth"
              (#'arg-res/realize-lazy-value deep-map))))))

  (testing "throws on lazy sequence exceeding size limit"
    (binding [config/*max-lazy-seq-size* 5]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Lazy sequence exceeds maximum allowed size"
            (#'arg-res/realize-lazy-value (map identity (range 100))))))))


;; === build-delay tests ===

(deftest build-delay-test
  (testing "builds delay for literal value"
    (let [arg {:id (random-uuid)
               :name "x"
               :type :int
               :value 42}
          context {}
          execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not be called" {})))
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (= 42 @result))))

  (testing "builds delay for ref-id with is-fn=true (pass fn-id directly)"
    (let [ref-fn-id (random-uuid)
          arg {:id (random-uuid)
               :name "f"
               :type :fn
               :ref-id ref-fn-id
               :is-fn true}
          context {}
          execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not be called" {})))
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (= ref-fn-id @result))))

  (testing "builds delay for ref-id with is-fn=false (execute fn)"
    (let [ref-fn-id (random-uuid)
          arg-id (random-uuid)
          arg {:id arg-id
               :name "result"
               :type :int
               :ref-id ref-fn-id
               :is-fn false}
          context {:test true}
          ;; Context will be augmented with :triggering-arg-id
          expected-ctx (assoc context :triggering-arg-id arg-id)
          execute-ref-fn (fn [ctx fn-id _caller-args _arg-delays]
                           (is (= expected-ctx ctx))
                           (is (= ref-fn-id fn-id))
                           100)
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (= 100 @result))))

  (testing "builds nil delay for arg with no value"
    (let [arg {:id (random-uuid)
               :name "optional"
               :type :text}
          context {}
          execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not be called" {})))
          caller-args []
          arg-delays-atom (atom {})
          result (#'arg-res/build-delay context arg execute-ref-fn caller-args arg-delays-atom)]
      (is (delay? result))
      (is (nil? @result)))))


(deftest arg-has-value-test
  (testing "returns true when value is set"
    (is (true? (#'arg-res/arg-has-value? {:value 42}))))

  (testing "returns true when ref-id is set"
    (is (true? (#'arg-res/arg-has-value? {:ref-id (random-uuid)}))))

  (testing "returns false when neither is set"
    (is (false? (#'arg-res/arg-has-value? {:name "x"})))))


;; === build-value-delay tests for is-fn with string UUID ===

(deftest build-value-delay-fn-type-test
  (testing "converts string UUID to UUID when is-fn=true"
    (let [uuid (random-uuid)
          uuid-str (str uuid)
          result (#'arg-res/build-value-delay uuid-str true)]
      (is (delay? result))
      (is (uuid? @result))
      (is (= uuid @result))))

  (testing "passes through invalid UUID string when is-fn=true"
    (let [result (#'arg-res/build-value-delay "not-a-uuid" true)]
      (is (delay? result))
      (is (= "not-a-uuid" @result))))

  (testing "passes through non-string value when is-fn=true"
    (let [uuid (random-uuid)
          result (#'arg-res/build-value-delay uuid true)]
      (is (delay? result))
      (is (= uuid @result))))

  (testing "passes through value unchanged when is-fn=false"
    (let [uuid-str (str (random-uuid))
          result (#'arg-res/build-value-delay uuid-str false)]
      (is (delay? result))
      (is (= uuid-str @result)))))


;; === Helper to build execution graphs for unit tests ===

(defn- make-execution-graph
  "Creates an execution graph from fns map and args sequence.
   Convenience wrapper around graph/->execution-graph."
  [fns-map args]
  (graph/->execution-graph {:fns fns-map :args args}))


;; === throw-missing-required-arg! tests ===

(deftest throw-missing-required-arg-test
  (testing "throws with correct error type and data"
    (let [arg-id (random-uuid)
          arg-name "my-required-arg"]
      (try
        (arg-res/throw-missing-required-arg! arg-id arg-name)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/missing-required-arg (:type (ex-data e))))
          (is (= arg-id (:arg-id (ex-data e))))
          (is (= arg-name (:arg-name (ex-data e))))
          (is (re-find #"my-required-arg" (ex-message e))))))))


;; === handle-validated-arg tests ===

(deftest handle-validated-arg-test
  (testing "returns delay wrapping value when type matches"
    (let [arg {:id (random-uuid) :name "x" :type :int}
          counter (atom 0)
          result (arg-res/handle-validated-arg 42 arg true 10 counter)]
      (is (delay? result))
      (is (= 42 @result))))

  (testing "throws on type mismatch"
    (let [arg {:id (random-uuid) :name "x" :type :int}
          counter (atom 0)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch"
            (arg-res/handle-validated-arg "not-an-int" arg true 10 counter))))))


;; === handle-runtime-arg-with-db-value tests ===

(deftest handle-runtime-arg-with-db-value-test
  (testing "returns nil (logs warning but does not throw)"
    (let [result (arg-res/handle-runtime-arg-with-db-value (random-uuid) "arg-name" :provided-arg)]
      (is (nil? result)))))


;; === fn-in-parent-chain? tests ===

(deftest fn-in-parent-chain-test
  (let [base-id (random-uuid)
        child-id (random-uuid)
        grandchild-id (random-uuid)
        unrelated-id (random-uuid)
        fns {base-id {:id base-id :parent-id nil}
             child-id {:id child-id :parent-id base-id}
             grandchild-id {:id grandchild-id :parent-id child-id}
             unrelated-id {:id unrelated-id :parent-id nil}}]

    (testing "returns true when fn-id equals start-fn-id"
      (is (true? (#'arg-res/fn-in-parent-chain? fns base-id base-id))))

    (testing "returns true when fn-id is direct parent"
      (is (true? (#'arg-res/fn-in-parent-chain? fns base-id child-id))))

    (testing "returns true when fn-id is grandparent"
      (is (true? (#'arg-res/fn-in-parent-chain? fns base-id grandchild-id))))

    (testing "returns false for unrelated fn"
      (is (false? (#'arg-res/fn-in-parent-chain? fns unrelated-id grandchild-id))))

    (testing "returns false when start-fn-id is nil"
      (is (false? (#'arg-res/fn-in-parent-chain? fns base-id nil))))

    (testing "returns false for non-existent fn-id in chain"
      (is (false? (#'arg-res/fn-in-parent-chain? fns (random-uuid) grandchild-id))))))


;; === arg-belongs-to-current-fn? tests ===

(deftest arg-belongs-to-current-fn-test
  (let [base-fn-id (random-uuid)
        composed-fn-id (random-uuid)
        ref-fn-id (random-uuid)
        ;; Base fn args (root args with names)
        base-arg-a-id (random-uuid)
        base-arg-b-id (random-uuid)
        ;; Composed fn's inherited args
        inherited-arg-a-id (random-uuid)
        inherited-arg-b-id (random-uuid)
        ;; An arg from a ref'd fn (propagated, not in parent chain)
        ref-arg-id (random-uuid)
        propagated-arg-id (random-uuid)

        fns {base-fn-id {:id base-fn-id :parent-id nil}
             composed-fn-id {:id composed-fn-id :parent-id base-fn-id}
             ref-fn-id {:id ref-fn-id :parent-id nil}}

        base-arg-a {:id base-arg-a-id :fn-id base-fn-id :name "a"
                    :type :int :source-id nil}
        base-arg-b {:id base-arg-b-id :fn-id base-fn-id :name "b"
                    :type :int :source-id nil}
        ;; Inherited arg with value bound (source in parent chain)
        inherited-arg-a {:id inherited-arg-a-id :fn-id composed-fn-id :name nil
                         :type :int :source-id base-arg-a-id :value 10}
        ;; Inherited arg without value (exposed)
        inherited-arg-b {:id inherited-arg-b-id :fn-id composed-fn-id :name nil
                         :type :int :source-id base-arg-b-id}
        ;; Arg from ref'd fn (not in parent chain)
        ref-arg {:id ref-arg-id :fn-id ref-fn-id :name "x"
                 :type :int :source-id nil}
        ;; Propagated arg: belongs to composed-fn but source is in ref'd fn
        propagated-arg {:id propagated-arg-id :fn-id composed-fn-id :name nil
                        :type :int :source-id ref-arg-id}

        all-args [base-arg-a base-arg-b inherited-arg-a inherited-arg-b
                  ref-arg propagated-arg]
        exec-graph (make-execution-graph fns all-args)]

    (testing "base-fn arg (no source-id) belongs to current fn"
      (is (true? (#'arg-res/arg-belongs-to-current-fn? exec-graph base-arg-a base-fn-id))))

    (testing "inherited arg with value belongs to composed fn"
      (is (true? (#'arg-res/arg-belongs-to-current-fn? exec-graph inherited-arg-a composed-fn-id))))

    (testing "inherited arg without value but with name on source belongs to composed fn"
      ;; base-arg-b has name "b", so inherited-arg-b should be included
      (is (true? (#'arg-res/arg-belongs-to-current-fn? exec-graph inherited-arg-b composed-fn-id))))

    (testing "propagated arg from ref'd fn does NOT belong to current fn"
      ;; Source is in ref-fn, not in parent chain of composed-fn
      (is (false? (#'arg-res/arg-belongs-to-current-fn? exec-graph propagated-arg composed-fn-id))))))


;; === build-ref-delay tests ===

(deftest build-ref-delay-test
  (testing "is-fn=true returns delay with fn-id directly"
    (let [fn-id (random-uuid)
          result (#'arg-res/build-ref-delay {} fn-id "f" true nil nil nil nil)]
      (is (delay? result))
      (is (= fn-id @result))))

  (testing "is-fn=false executes ref fn and returns result"
    (let [fn-id (random-uuid)
          arg-id (random-uuid)
          execute-called? (atom false)
          exec-fn (fn [ctx ref-fn-id _caller-args _delays]
                    (reset! execute-called? true)
                    (is (= fn-id ref-fn-id))
                    (is (= arg-id (:triggering-arg-id ctx)))
                    99)
          result (#'arg-res/build-ref-delay {:some :context} fn-id "my-arg" false
                                            exec-fn {} (atom {}) arg-id)]
      (is (delay? result))
      (is (= 99 @result))
      (is (true? @execute-called?))))

  (testing "wraps exception from execute-ref-fn with arg context"
    (let [fn-id (random-uuid)
          exec-fn (fn [_ _ _ _]
                    (throw (ex-info "inner error" {:type :test-error})))
          result (#'arg-res/build-ref-delay {} fn-id "bad-arg" false
                                            exec-fn {} (atom {}) (random-uuid))]
      (is (delay? result))
      (try
        @result
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/arg-evaluation-failed (:type (ex-data e))))
          (is (= "bad-arg" (:arg-name (ex-data e))))
          (is (re-find #"bad-arg" (ex-message e)))))))

  (testing "reads arg-delays-atom at force time, not creation time"
    (let [fn-id (random-uuid)
          delays-atom (atom {})
          seen-delays (atom nil)
          exec-fn (fn [_ _ _ delays]
                    (reset! seen-delays delays)
                    42)
          result (#'arg-res/build-ref-delay {} fn-id "a" false
                                            exec-fn {} delays-atom (random-uuid))
          ;; Add delays AFTER creating the delay but BEFORE forcing it
          extra-delay (delay 100)]
      (swap! delays-atom assoc :extra extra-delay)
      (is (= 42 @result))
      ;; The exec-fn should have seen the delays added after creation
      (is (contains? @seen-delays :extra)))))


;; === build-arg-delays tests ===

(deftest build-arg-delays-basic-test
  (let [base-fn-id (random-uuid)
        composed-fn-id (random-uuid)
        base-arg-a-id (random-uuid)
        base-arg-b-id (random-uuid)
        inherited-a-id (random-uuid)
        inherited-b-id (random-uuid)

        fns {base-fn-id {:id base-fn-id :parent-id nil :name "add"}
             composed-fn-id {:id composed-fn-id :parent-id base-fn-id :name "add-10"}}

        base-arg-a {:id base-arg-a-id :fn-id base-fn-id :name "a"
                    :type :int :required true :source-id nil}
        base-arg-b {:id base-arg-b-id :fn-id base-fn-id :name "b"
                    :type :int :required true :source-id nil}
        ;; Composed fn binds a=10, leaves b unbound (optional)
        inherited-a {:id inherited-a-id :fn-id composed-fn-id :name nil
                     :type :int :required true :source-id base-arg-a-id :value 10}
        inherited-b {:id inherited-b-id :fn-id composed-fn-id :name nil
                     :type :int :required false :source-id base-arg-b-id}

        all-args [base-arg-a base-arg-b inherited-a inherited-b]
        exec-graph (make-execution-graph fns all-args)
        ctx (assoc (th/create-test-context)
                   :execution-graph exec-graph)
        fn-data {:fn (get fns composed-fn-id)
                 :args [inherited-a inherited-b]}
        execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not call" {})))]

    (testing "builds delays for args with literal values"
      (let [result (arg-res/build-arg-delays ctx fn-data {} execute-ref-fn)]
        (is (map? (:by-name result)))
        (is (map? (:by-id result)))
        ;; :a should resolve to 10 (bound value)
        (is (= 10 @(get (:by-name result) :a)))))

    (testing "by-id contains delays for all args"
      (let [result (arg-res/build-arg-delays ctx fn-data {} execute-ref-fn)]
        (is (contains? (:by-id result) inherited-a-id))
        (is (contains? (:by-id result) inherited-b-id))))

    (testing "optional arg with no value produces nil delay"
      (let [opt-arg-id (random-uuid)
            opt-arg {:id opt-arg-id :fn-id composed-fn-id :name nil
                     :type :int :required false :source-id base-arg-b-id}
            fn-data-opt {:fn (get fns composed-fn-id)
                         :args [inherited-a opt-arg]}
            ;; Need to rebuild graph with the optional arg
            all-args-opt [base-arg-a base-arg-b inherited-a opt-arg]
            exec-graph-opt (make-execution-graph fns all-args-opt)
            ctx-opt (assoc ctx :execution-graph exec-graph-opt)
            result (arg-res/build-arg-delays ctx-opt fn-data-opt {} execute-ref-fn)]
        (is (nil? @(get (:by-id result) opt-arg-id)))))))


(deftest build-arg-delays-required-missing-test
  (let [base-fn-id (random-uuid)
        composed-fn-id (random-uuid)
        base-arg-id (random-uuid)
        inherited-arg-id (random-uuid)

        fns {base-fn-id {:id base-fn-id :parent-id nil :name "base"}
             composed-fn-id {:id composed-fn-id :parent-id base-fn-id :name "composed"}}

        base-arg {:id base-arg-id :fn-id base-fn-id :name "x"
                  :type :int :required true :source-id nil}
        ;; Inherited arg with no value bound and required=true
        inherited-arg {:id inherited-arg-id :fn-id composed-fn-id :name nil
                       :type :int :required true :source-id base-arg-id}

        all-args [base-arg inherited-arg]
        exec-graph (make-execution-graph fns all-args)
        ctx (assoc (th/create-test-context)
                   :execution-graph exec-graph)
        fn-data {:fn (get fns composed-fn-id)
                 :args [inherited-arg]}
        execute-ref-fn (fn [_ _ _ _] nil)]

    (testing "throws for required arg with no value"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Required argument.*not provided"
            (arg-res/build-arg-delays ctx fn-data {} execute-ref-fn))))))


(deftest build-arg-delays-provided-args-test
  (let [base-fn-id (random-uuid)
        composed-fn-id (random-uuid)
        base-arg-id (random-uuid)
        inherited-arg-id (random-uuid)

        fns {base-fn-id {:id base-fn-id :parent-id nil :name "base"}
             composed-fn-id {:id composed-fn-id :parent-id base-fn-id :name "composed"}}

        base-arg {:id base-arg-id :fn-id base-fn-id :name "x"
                  :type :int :required true :source-id nil}
        ;; Inherited arg with no stored value
        inherited-arg {:id inherited-arg-id :fn-id composed-fn-id :name nil
                       :type :int :required true :source-id base-arg-id}

        all-args [base-arg inherited-arg]
        exec-graph (make-execution-graph fns all-args)
        ctx (assoc (th/create-test-context)
                   :execution-graph exec-graph)
        fn-data {:fn (get fns composed-fn-id)
                 :args [inherited-arg]}
        execute-ref-fn (fn [_ _ _ _] nil)]

    (testing "uses provided arg value when no stored value exists"
      (let [provided {inherited-arg-id 99}
            result (arg-res/build-arg-delays ctx fn-data provided execute-ref-fn)]
        (is (= 99 @(get (:by-name result) :x)))))

    (testing "stored value takes precedence over provided value"
      (let [inherited-with-value (assoc inherited-arg :value 50)
            all-args-v [base-arg inherited-with-value]
            exec-graph-v (make-execution-graph fns all-args-v)
            ctx-v (assoc ctx :execution-graph exec-graph-v)
            fn-data-v {:fn (get fns composed-fn-id) :args [inherited-with-value]}
            provided {inherited-arg-id 99}
            result (arg-res/build-arg-delays ctx-v fn-data-v provided execute-ref-fn)]
        ;; Stored value (50) should win over provided (99)
        (is (= 50 @(get (:by-name result) :x)))))

    (testing "provided delay is used directly without re-wrapping"
      (let [my-delay (delay 77)
            provided {inherited-arg-id my-delay}
            result (arg-res/build-arg-delays ctx fn-data provided execute-ref-fn)]
        ;; Should use the provided delay directly
        (is (= 77 @(get (:by-id result) inherited-arg-id)))))))


(deftest build-arg-delays-ref-id-test
  (let [base-fn-id (random-uuid)
        composed-fn-id (random-uuid)
        ref-target-fn-id (random-uuid)
        base-arg-id (random-uuid)
        inherited-arg-id (random-uuid)

        fns {base-fn-id {:id base-fn-id :parent-id nil :name "base"}
             composed-fn-id {:id composed-fn-id :parent-id base-fn-id :name "composed"}
             ref-target-fn-id {:id ref-target-fn-id :parent-id nil :name "ref-target"}}

        base-arg {:id base-arg-id :fn-id base-fn-id :name "x"
                  :type :int :required true :source-id nil :is-fn false}
        ;; Inherited arg that references another fn
        inherited-arg {:id inherited-arg-id :fn-id composed-fn-id :name nil
                       :type :int :required true :source-id base-arg-id
                       :ref-id ref-target-fn-id :is-fn false}

        all-args [base-arg inherited-arg]
        exec-graph (make-execution-graph fns all-args)
        ctx (assoc (th/create-test-context)
                   :execution-graph exec-graph)
        fn-data {:fn (get fns composed-fn-id)
                 :args [inherited-arg]}
        ;; Mock execute-ref-fn that returns a computed value
        execute-ref-fn (fn [_ctx fn-id _caller-args _delays]
                         (is (= ref-target-fn-id fn-id))
                         42)]

    (testing "ref-id arg executes referenced fn and returns result"
      (let [result (arg-res/build-arg-delays ctx fn-data {} execute-ref-fn)]
        (is (= 42 @(get (:by-name result) :x))))))

  (let [base-fn-id (random-uuid)
        composed-fn-id (random-uuid)
        ref-target-fn-id (random-uuid)
        base-arg-id (random-uuid)
        inherited-arg-id (random-uuid)

        fns {base-fn-id {:id base-fn-id :parent-id nil :name "base"}
             composed-fn-id {:id composed-fn-id :parent-id base-fn-id :name "composed"}
             ref-target-fn-id {:id ref-target-fn-id :parent-id nil :name "ref-target"}}

        base-arg {:id base-arg-id :fn-id base-fn-id :name "f"
                  :type :fn :required true :source-id nil :is-fn true}
        ;; Inherited arg with is-fn=true passes fn-id directly
        inherited-arg {:id inherited-arg-id :fn-id composed-fn-id :name nil
                       :type :fn :required true :source-id base-arg-id
                       :ref-id ref-target-fn-id :is-fn true}

        all-args [base-arg inherited-arg]
        exec-graph (make-execution-graph fns all-args)
        ctx (assoc (th/create-test-context)
                   :execution-graph exec-graph)
        fn-data {:fn (get fns composed-fn-id)
                 :args [inherited-arg]}
        execute-ref-fn (fn [_ _ _ _] (throw (ex-info "Should not execute" {})))]

    (testing "is-fn=true arg passes fn-id directly without executing"
      (let [result (arg-res/build-arg-delays ctx fn-data {} execute-ref-fn)]
        (is (= ref-target-fn-id @(get (:by-name result) :f)))))))


;; === Additional branch coverage tests ===

(deftest build-ref-delay-nil-exec-fn-test
  (testing "throws closure-capture-issue when execute-ref-fn is nil in captured vector"
    ;; This tests the (when (nil? exec-fn) ...) branch inside the delay
    (let [fn-id (random-uuid)
          ;; Pass nil as execute-ref-fn — captured vector will contain nil at exec-fn position
          result (#'arg-res/build-ref-delay {} fn-id "test-arg" false
                                            nil {} (atom {}) (random-uuid))]
      (is (delay? result))
      (try
        @result
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          ;; The nil exec-fn throw is wrapped by the outer catch into arg-evaluation-failed
          (is (= :execution-error/arg-evaluation-failed (:type (ex-data e))))
          (is (= "test-arg" (:arg-name (ex-data e)))))))))


(deftest build-ref-delay-nil-delays-atom-test
  (testing "uses empty map when delays-atom is nil"
    (let [fn-id (random-uuid)
          exec-fn (fn [_ctx _fn-id _args delays]
                    (is (= {} delays))
                    55)
          result (#'arg-res/build-ref-delay {} fn-id "a" false
                                            exec-fn {} nil (random-uuid))]
      (is (= 55 @result)))))


(deftest realize-lazy-value-list-seqable-test
  (testing "realizes a list (seqable, non-lazy-seq, non-vector, non-set, non-string, non-map)"
    ;; This hits the 4th cond branch in realize-lazy-value (line 76-80)
    (let [result (#'arg-res/realize-lazy-value (list 1 2 3))]
      (is (= [1 2 3] result))
      (is (vector? result)))))


(deftest arg-belongs-to-current-fn-root-not-base-test
  (testing "returns false when root arg does not belong to base-fn"
    ;; This tests the root-belongs-to-base? = false branch
    (let [base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          other-fn-id (random-uuid)
          ;; Root arg belongs to other-fn, not base-fn
          other-arg-id (random-uuid)
          inherited-arg-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil}
               composed-fn-id {:id composed-fn-id :parent-id base-fn-id}
               other-fn-id {:id other-fn-id :parent-id nil}}

          other-arg {:id other-arg-id :fn-id other-fn-id :name "x"
                     :type :int :source-id nil}
          ;; Arg on composed-fn but source points to other-fn's arg (not in parent chain)
          inherited-arg {:id inherited-arg-id :fn-id composed-fn-id :name nil
                         :type :int :source-id other-arg-id}

          all-args [other-arg inherited-arg]
          exec-graph (make-execution-graph fns all-args)]

      (is (false? (#'arg-res/arg-belongs-to-current-fn? exec-graph inherited-arg composed-fn-id))))))


(deftest arg-belongs-to-current-fn-own-arg-missing-source-test
  (testing "returns false when own arg's source points to missing arg (root != base-fn)"
    ;; own-arg.source-id -> missing -> root = own-arg itself
    ;; own-arg.fn-id = composed-fn-id != base-fn-id -> root-belongs-to-base? = false
    (let [base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          base-arg-id (random-uuid)
          own-arg-id (random-uuid)
          missing-source-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil}
               composed-fn-id {:id composed-fn-id :parent-id base-fn-id}}

          base-arg {:id base-arg-id :fn-id base-fn-id :name "a"
                    :type :int :source-id nil}
          own-arg {:id own-arg-id :fn-id composed-fn-id :name nil
                   :type :int :source-id missing-source-id}

          all-args [base-arg own-arg]
          exec-graph (make-execution-graph fns all-args)]
      (is (false? (#'arg-res/arg-belongs-to-current-fn? exec-graph own-arg composed-fn-id))))))


(deftest arg-belongs-to-current-fn-inherited-with-ref-test
  (testing "inherited arg with ref-id belongs to composed fn"
    ;; Tests the inherited (not own) arg branch with ref-id set
    (let [base-fn-id (random-uuid)
          mid-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          base-arg-id (random-uuid)
          mid-arg-id (random-uuid)
          inherited-arg-id (random-uuid)
          ref-target-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil}
               mid-fn-id {:id mid-fn-id :parent-id base-fn-id}
               composed-fn-id {:id composed-fn-id :parent-id mid-fn-id}}

          base-arg {:id base-arg-id :fn-id base-fn-id :name "a"
                    :type :int :source-id nil}
          mid-arg {:id mid-arg-id :fn-id mid-fn-id :name nil
                   :type :int :source-id base-arg-id}
          ;; Inherited arg from mid-fn (not own to composed-fn) with ref-id
          inherited-arg {:id inherited-arg-id :fn-id mid-fn-id :name nil
                         :type :int :source-id base-arg-id :ref-id ref-target-id}

          all-args [base-arg mid-arg inherited-arg]
          exec-graph (make-execution-graph fns all-args)]

      ;; inherited-arg.fn-id = mid-fn-id ≠ composed-fn-id
      ;; Root = base-arg (belongs to base-fn) ✓
      ;; Has ref-id → included
      (is (true? (#'arg-res/arg-belongs-to-current-fn? exec-graph inherited-arg composed-fn-id))))))


(deftest arg-belongs-to-current-fn-inherited-no-name-no-value-test
  (testing "inherited arg with no name, no value, no ref-id returns false"
    ;; Tests the inherited arg branch where all three checks are false
    (let [base-fn-id (random-uuid)
          mid-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          base-arg-id (random-uuid)
          ;; Inherited arg with nothing set
          inherited-arg-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil}
               mid-fn-id {:id mid-fn-id :parent-id base-fn-id}
               composed-fn-id {:id composed-fn-id :parent-id mid-fn-id}}

          base-arg {:id base-arg-id :fn-id base-fn-id :name "a"
                    :type :int :source-id nil}
          ;; Inherited arg: no name, no value, no ref-id
          inherited-arg {:id inherited-arg-id :fn-id mid-fn-id :name nil
                         :type :int :source-id base-arg-id
                         :value nil :ref-id nil}

          all-args [base-arg inherited-arg]
          exec-graph (make-execution-graph fns all-args)]

      ;; Root = base-arg (belongs to base-fn) ✓
      ;; Inherited: name=nil, value=nil, ref-id=nil → false
      (is (false? (#'arg-res/arg-belongs-to-current-fn? exec-graph inherited-arg composed-fn-id))))))


(deftest arg-belongs-to-current-fn-own-arg-source-in-parent-no-names-test
  (testing "own arg where source is in parent chain but has no name on arg or source - only value/ref-id"
    ;; Tests the own-arg branch where source is in parent chain
    ;; and arg has no name, source has no name, but has value → true
    (let [base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          base-arg-id (random-uuid)
          own-arg-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil}
               composed-fn-id {:id composed-fn-id :parent-id base-fn-id}}

          ;; Base arg without name (unusual but tests the branch)
          base-arg {:id base-arg-id :fn-id base-fn-id :name nil
                    :type :int :source-id nil}
          ;; Own arg with source in parent chain, no names, but has value
          own-arg {:id own-arg-id :fn-id composed-fn-id :name nil
                   :type :int :source-id base-arg-id :value 42}

          all-args [base-arg own-arg]
          exec-graph (make-execution-graph fns all-args)]

      ;; Root = base-arg (fn-id = base-fn-id, source-id = nil) ✓
      ;; Own arg, source in parent chain
      ;; name=nil, source name=nil, but value=42 → true
      (is (true? (#'arg-res/arg-belongs-to-current-fn? exec-graph own-arg composed-fn-id)))))

  (testing "own arg where source is in parent chain, no names, no value, no ref-id → false"
    (let [base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          base-arg-id (random-uuid)
          own-arg-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil}
               composed-fn-id {:id composed-fn-id :parent-id base-fn-id}}

          base-arg {:id base-arg-id :fn-id base-fn-id :name nil
                    :type :int :source-id nil}
          own-arg {:id own-arg-id :fn-id composed-fn-id :name nil
                   :type :int :source-id base-arg-id}

          all-args [base-arg own-arg]
          exec-graph (make-execution-graph fns all-args)]

      ;; Root = base-arg, belongs to base ✓
      ;; Own arg, source in parent chain
      ;; name=nil, source name=nil, value=nil, ref-id=nil → false
      (is (false? (#'arg-res/arg-belongs-to-current-fn? exec-graph own-arg composed-fn-id)))))

  (testing "own arg where source is in parent chain, source has name → true"
    (let [base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          base-arg-id (random-uuid)
          own-arg-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil}
               composed-fn-id {:id composed-fn-id :parent-id base-fn-id}}

          base-arg {:id base-arg-id :fn-id base-fn-id :name "x"
                    :type :int :source-id nil}
          own-arg {:id own-arg-id :fn-id composed-fn-id :name nil
                   :type :int :source-id base-arg-id}

          all-args [base-arg own-arg]
          exec-graph (make-execution-graph fns all-args)]

      ;; Own arg, source in parent chain
      ;; name=nil but source name="x" → true
      (is (true? (#'arg-res/arg-belongs-to-current-fn? exec-graph own-arg composed-fn-id))))))


(deftest build-arg-delays-collision-handling-test
  (testing "provided value beats existing stored value in by-name collision"
    ;; When two args share the same root name, provided value should win
    (let [base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          base-arg-id (random-uuid)
          arg1-id (random-uuid)
          arg2-id (random-uuid)

          fns {base-fn-id {:id base-fn-id :parent-id nil :name "base"}
               composed-fn-id {:id composed-fn-id :parent-id base-fn-id :name "composed"}}

          base-arg {:id base-arg-id :fn-id base-fn-id :name "x"
                    :type :int :required false :source-id nil}
          ;; First arg: stored value
          arg1 {:id arg1-id :fn-id composed-fn-id :name nil
                :type :int :required false :source-id base-arg-id :value 10}
          ;; Second arg: no stored value, will get provided
          arg2 {:id arg2-id :fn-id composed-fn-id :name nil
                :type :int :required false :source-id base-arg-id}

          all-args [base-arg arg1 arg2]
          exec-graph (make-execution-graph fns all-args)
          ctx (assoc (th/create-test-context)
                     :execution-graph exec-graph)
          fn-data {:fn (get fns composed-fn-id)
                   :args [arg1 arg2]}
          execute-ref-fn (fn [_ _ _ _] nil)
          ;; Provide a value for arg2
          provided {arg2-id 99}
          result (arg-res/build-arg-delays ctx fn-data provided execute-ref-fn)]

      ;; arg2 has provided value → has-provided-value? = true → should replace arg1's stored value
      (is (= 99 @(get (:by-name result) :x))))))


(deftest realize-lazy-value-depth-zero-map-test
  (testing "realizes map at depth 0 with nested lazy seq"
    (binding [config/*max-nested-collection-depth* 2]
      (let [result (#'arg-res/realize-lazy-value {:a (map inc [1 2])})]
        (is (= {:a [2 3]} result))))))


(deftest build-delay-value-with-is-fn-test
  (testing "builds delay for literal UUID string with is-fn=true"
    (let [uuid (random-uuid)
          uuid-str (str uuid)
          arg {:id (random-uuid)
               :name "f"
               :type :fn
               :value uuid-str
               :is-fn true}
          result (#'arg-res/build-delay {} arg nil nil nil)]
      (is (delay? result))
      (is (uuid? @result))
      (is (= uuid @result)))))
