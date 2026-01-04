(ns graphden.base-functions.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.core :as core]
    [graphden.base-functions.interface :as bf]
    [graphden.executor.core :as exec-core]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


;; === Test Fixtures ===

(defn with-clean-registry
  [f]
  (exec/clear-base-fns!)
  (try
    (f)
    (finally
      (exec/clear-base-fns!))))


(use-fixtures :each with-clean-registry)


;; === Helper Functions ===

(defn literal-thunk
  "Creates a literal thunk for testing."
  [value]
  (reify exec-core/IThunk
    (force-value [_ _] value)))


(defn call-base-fn
  "Calls a base function with literal values."
  [fn-name args]
  (let [thunks (into {} (map (fn [[k v]] [k (literal-thunk v)]) args))]
    ((exec/get-base-fn fn-name) thunks nil)))


;; === Registration Helpers ===
;; These wrap fn-registry to register base function definitions

(defn register-all!
  []
  (registry/register-base-fns! (bf/get-all-defs)))


(defn register-arithmetic!
  []
  (registry/register-base-fns! core/arithmetic-defs))


(defn register-comparison!
  []
  (registry/register-base-fns! core/comparison-defs))


(defn register-logic!
  []
  (registry/register-base-fns! core/logic-defs))


(defn register-conditionals!
  []
  (registry/register-base-fns! core/conditional-defs))


(defn register-strings!
  []
  (registry/register-base-fns! core/string-defs))


(defn register-collections!
  []
  (registry/register-base-fns! core/collection-defs))


(defn register-hof!
  []
  (registry/register-base-fns! core/hof-defs))


(defn sync-storage!
  [storage]
  (registry/sync-defs-to-storage! storage (bf/get-all-defs)))


;; === Registration Tests ===

(deftest register-all-test
  (testing "register-all! registers all base functions"
    (register-all!)
    ;; Check a sample from each category
    (is (some? (exec/get-base-fn :add)))
    (is (some? (exec/get-base-fn :eq)))
    (is (some? (exec/get-base-fn :and)))
    (is (some? (exec/get-base-fn :if)))
    (is (some? (exec/get-base-fn :str-len)))
    (is (some? (exec/get-base-fn :first)))
    (is (some? (exec/get-base-fn :map)))))


(deftest register-arithmetic-test
  (testing "register-arithmetic! registers arithmetic functions"
    (register-arithmetic!)
    (is (some? (exec/get-base-fn :add)))
    (is (some? (exec/get-base-fn :sub)))
    (is (some? (exec/get-base-fn :mul)))
    (is (some? (exec/get-base-fn :div)))
    (is (some? (exec/get-base-fn :mod)))
    (is (some? (exec/get-base-fn :neg)))
    (is (some? (exec/get-base-fn :abs)))))


;; === Arithmetic Tests ===

(deftest arithmetic-operations-test
  (register-arithmetic!)

  (testing "add"
    (is (= 5 (call-base-fn :add {:nums [2 3]})))
    (is (zero? (call-base-fn :add {:nums [-5 5]})))
    (is (= 3.5 (call-base-fn :add {:nums [1.5 2.0]})))
    (is (= 15 (call-base-fn :add {:nums [1 2 3 4 5]})))
    (is (zero? (call-base-fn :add {:nums []}))))

  (testing "sub"
    (is (= 2 (call-base-fn :sub {:nums [5 3]})))
    (is (= -8 (call-base-fn :sub {:nums [2 10]})))
    (is (= 5 (call-base-fn :sub {:nums [10 3 2]})))
    (is (= -5 (call-base-fn :sub {:nums [5]}))))

  (testing "mul"
    (is (= 12 (call-base-fn :mul {:nums [3 4]})))
    (is (= -15 (call-base-fn :mul {:nums [-3 5]})))
    (is (= 120 (call-base-fn :mul {:nums [1 2 3 4 5]})))
    (is (= 1 (call-base-fn :mul {:nums []}))))

  (testing "div"
    (is (= 2 (call-base-fn :div {:nums [6 3]})))
    (is (= 5/2 (call-base-fn :div {:nums [5 2]})))
    (is (= 1 (call-base-fn :div {:nums [100 5 2 10]})))
    (is (= 1/5 (call-base-fn :div {:nums [5]}))))

  (testing "div by zero throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Division by zero"
          (call-base-fn :div {:nums [5 0]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Division by zero"
          (call-base-fn :div {:nums [10 2 0 5]}))))

  (testing "mod"
    (is (= 1 (call-base-fn :mod {:a 7 :b 3})))
    (is (zero? (call-base-fn :mod {:a 6 :b 2}))))

  (testing "mod by zero throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Modulo by zero"
          (call-base-fn :mod {:a 5 :b 0}))))

  (testing "neg"
    (is (= -5 (call-base-fn :neg {:n 5})))
    (is (= 3 (call-base-fn :neg {:n -3}))))

  (testing "abs"
    (is (= 5 (call-base-fn :abs {:n -5})))
    (is (= 5 (call-base-fn :abs {:n 5})))))


;; === Comparison Tests ===

(deftest comparison-operations-test
  (register-comparison!)

  (testing "eq"
    (is (true? (call-base-fn :eq {:values [5 5]})))
    (is (false? (call-base-fn :eq {:values [5 6]})))
    (is (true? (call-base-fn :eq {:values ["hello" "hello"]})))
    (is (true? (call-base-fn :eq {:values [1 1 1 1]})))
    (is (false? (call-base-fn :eq {:values [1 1 2 1]}))))

  (testing "neq"
    (is (false? (call-base-fn :neq {:values [5 5]})))
    (is (true? (call-base-fn :neq {:values [5 6]}))))

  (testing "lt"
    (is (true? (call-base-fn :lt {:nums [3 5]})))
    (is (false? (call-base-fn :lt {:nums [5 3]})))
    (is (false? (call-base-fn :lt {:nums [5 5]})))
    (is (true? (call-base-fn :lt {:nums [1 2 3 4 5]})))
    (is (false? (call-base-fn :lt {:nums [1 2 3 3 5]}))))

  (testing "lte"
    (is (true? (call-base-fn :lte {:nums [3 5]})))
    (is (true? (call-base-fn :lte {:nums [5 5]})))
    (is (false? (call-base-fn :lte {:nums [6 5]})))
    (is (true? (call-base-fn :lte {:nums [1 2 2 3 3]}))))

  (testing "gt"
    (is (true? (call-base-fn :gt {:nums [5 3]})))
    (is (false? (call-base-fn :gt {:nums [3 5]})))
    (is (false? (call-base-fn :gt {:nums [5 5]})))
    (is (true? (call-base-fn :gt {:nums [5 4 3 2 1]}))))

  (testing "gte"
    (is (true? (call-base-fn :gte {:nums [5 3]})))
    (is (true? (call-base-fn :gte {:nums [5 5]})))
    (is (false? (call-base-fn :gte {:nums [4 5]})))
    (is (true? (call-base-fn :gte {:nums [5 4 4 3 3]})))))


;; === Logic Tests ===

(deftest logic-operations-test
  (register-logic!)

  (testing "and"
    (is (true? (call-base-fn :and {:a true :b true})))
    (is (false? (call-base-fn :and {:a true :b false})))
    (is (false? (call-base-fn :and {:a false :b true})))
    (is (false? (call-base-fn :and {:a false :b false}))))

  (testing "or"
    (is (true? (call-base-fn :or {:a true :b true})))
    (is (true? (call-base-fn :or {:a true :b false})))
    (is (true? (call-base-fn :or {:a false :b true})))
    (is (false? (call-base-fn :or {:a false :b false}))))

  (testing "not"
    (is (false? (call-base-fn :not {:x true})))
    (is (true? (call-base-fn :not {:x false})))
    (is (true? (call-base-fn :not {:x nil})))))


(deftest logic-laziness-test
  ;; Tests that and/or exhibit short-circuit evaluation behavior.
  (register-logic!)

  (testing "and short-circuits on false - second arg not evaluated"
    ;; Clojure's 'and' macro short-circuits, so when a is false, b is never evaluated
    (let [call-count (atom 0)
          tracking-thunk (reify exec-core/IThunk
                           (force-value
                             [_ _]
                             (swap! call-count inc)
                             true))
          false-thunk (reify exec-core/IThunk
                        (force-value [_ _] false))
          and-fn (exec/get-base-fn :and)]
      (and-fn {:a false-thunk :b tracking-thunk} nil)
      ;; Short-circuit: b is never evaluated when a is false
      (is (zero? @call-count) "and short-circuits - second arg not evaluated")))

  (testing "or short-circuits on true - second arg not evaluated"
    ;; Clojure's 'or' macro short-circuits, so when a is true, b is never evaluated
    (let [call-count (atom 0)
          tracking-thunk (reify exec-core/IThunk
                           (force-value
                             [_ _]
                             (swap! call-count inc)
                             false))
          true-thunk (reify exec-core/IThunk
                       (force-value [_ _] true))
          or-fn (exec/get-base-fn :or)]
      (or-fn {:a true-thunk :b tracking-thunk} nil)
      ;; Short-circuit: b is never evaluated when a is true
      (is (zero? @call-count) "or short-circuits - second arg not evaluated"))))


;; === Conditionals Tests ===

(deftest conditionals-test
  (register-conditionals!)

  (testing "if - true branch"
    (is (= "yes" (call-base-fn :if {:condition true :then "yes" :else "no"}))))

  (testing "if - false branch"
    (is (= "no" (call-base-fn :if {:condition false :then "yes" :else "no"}))))

  (testing "if - truthy values"
    (is (= "yes" (call-base-fn :if {:condition 1 :then "yes" :else "no"})))
    (is (= "yes" (call-base-fn :if {:condition "non-empty" :then "yes" :else "no"}))))

  (testing "if - falsy values"
    (is (= "no" (call-base-fn :if {:condition nil :then "yes" :else "no"}))))

  (testing "cond - first match"
    (is (= "one" (call-base-fn :cond {:pairs [{:pred true :result "one"}
                                              {:pred true :result "two"}]
                                      :default "none"}))))

  (testing "cond - second match"
    (is (= "two" (call-base-fn :cond {:pairs [{:pred false :result "one"}
                                              {:pred true :result "two"}]
                                      :default "none"}))))

  (testing "cond - no match, uses default"
    (is (= "none" (call-base-fn :cond {:pairs [{:pred false :result "one"}
                                               {:pred false :result "two"}]
                                       :default "none"}))))

  (testing "cond - no match, no default"
    (is (nil? (call-base-fn :cond {:pairs [{:pred false :result "one"}]})))))


;; === String Tests ===

(deftest string-operations-test
  (register-strings!)

  (testing "str - concatenation"
    (is (= "hello world" (call-base-fn :str {:args ["hello" " " "world"]})))
    (is (= "" (call-base-fn :str {:args []})))
    (is (= "abc" (call-base-fn :str {:args ["a" "b" "c"]}))))

  (testing "str-len"
    (is (= 5 (call-base-fn :str-len {:s "hello"})))
    (is (zero? (call-base-fn :str-len {:s ""}))))

  (testing "str-upper"
    (is (= "HELLO" (call-base-fn :str-upper {:s "hello"})))
    (is (= "HELLO WORLD" (call-base-fn :str-upper {:s "Hello World"}))))

  (testing "str-lower"
    (is (= "hello" (call-base-fn :str-lower {:s "HELLO"})))
    (is (= "hello world" (call-base-fn :str-lower {:s "Hello World"}))))

  (testing "str-trim"
    (is (= "hello" (call-base-fn :str-trim {:s "  hello  "})))
    (is (= "hello" (call-base-fn :str-trim {:s "\n\thello\n\t"}))))

  (testing "subs"
    (is (= "ell" (call-base-fn :subs {:s "hello" :start 1 :end 4})))
    (is (= "llo" (call-base-fn :subs {:s "hello" :start 2})))
    (is (= "" (call-base-fn :subs {:s "hello" :start 5})))
    (is (= "hello" (call-base-fn :subs {:s "hello" :start 0}))))

  (testing "subs - edge cases throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"start index cannot be negative"
          (call-base-fn :subs {:s "hello" :start -1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"start index out of bounds"
          (call-base-fn :subs {:s "hello" :start 10})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"end index cannot be less than start"
          (call-base-fn :subs {:s "hello" :start 3 :end 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"end index out of bounds"
          (call-base-fn :subs {:s "hello" :start 0 :end 10}))))

  (testing "str-split"
    (is (= ["a" "b" "c"] (call-base-fn :str-split {:s "a,b,c" :sep ","}))))

  (testing "str-split - invalid regex throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid regex pattern"
          (call-base-fn :str-split {:s "test" :sep "[invalid"}))))

  (testing "str-join"
    (is (= "a,b,c" (call-base-fn :str-join {:coll ["a" "b" "c"] :sep ","})))
    (is (= "abc" (call-base-fn :str-join {:coll ["a" "b" "c"]})))))


;; === Collection Tests ===

(deftest collection-operations-test
  (register-collections!)

  (testing "first"
    (is (= 1 (call-base-fn :first {:coll [1 2 3]})))
    (is (nil? (call-base-fn :first {:coll []}))))

  (testing "rest"
    (is (= [2 3] (vec (call-base-fn :rest {:coll [1 2 3]}))))
    (is (= [] (vec (call-base-fn :rest {:coll [1]})))))

  (testing "cons"
    (is (= [0 1 2 3] (vec (call-base-fn :cons {:x 0 :coll [1 2 3]})))))

  (testing "conj"
    (is (= [1 2 3 4] (call-base-fn :conj {:coll [1 2 3] :x 4}))))

  (testing "get"
    (is (= 2 (call-base-fn :get {:coll [1 2 3] :k 1})))
    (is (= "b" (call-base-fn :get {:coll {:a "a" :b "b"} :k :b})))
    (is (nil? (call-base-fn :get {:coll {:a 1} :k :b})))
    (is (= "default" (call-base-fn :get {:coll {:a 1} :k :b :default "default"}))))

  (testing "assoc"
    (is (= {:a 1 :b 2} (call-base-fn :assoc {:m {:a 1} :k :b :v 2}))))

  (testing "dissoc"
    (is (= {:a 1} (call-base-fn :dissoc {:m {:a 1 :b 2} :k :b}))))

  (testing "count"
    (is (= 3 (call-base-fn :count {:coll [1 2 3]})))
    (is (= 2 (call-base-fn :count {:coll {:a 1 :b 2}}))))

  (testing "empty?"
    (is (true? (call-base-fn :empty? {:coll []})))
    (is (false? (call-base-fn :empty? {:coll [1]}))))

  (testing "contains?"
    (is (true? (call-base-fn :contains? {:coll {:a 1} :k :a})))
    (is (false? (call-base-fn :contains? {:coll {:a 1} :k :b}))))

  (testing "keys"
    (is (= [:a :b] (sort (call-base-fn :keys {:m {:a 1 :b 2}})))))

  (testing "vals"
    (is (= [1 2] (sort (call-base-fn :vals {:m {:a 1 :b 2}})))))

  (testing "merge"
    (is (= {:a 1 :b 2 :c 3} (call-base-fn :merge {:maps [{:a 1 :b 2} {:c 3}]})))
    (is (= {:a 1 :b 2 :c 3 :d 4} (call-base-fn :merge {:maps [{:a 1} {:b 2} {:c 3} {:d 4}]})))
    (is (= {:a 2} (call-base-fn :merge {:maps [{:a 1} {:a 2}]}))))

  (testing "into"
    (is (= {:a 1 :b 2} (call-base-fn :into {:to {} :from [[:a 1] [:b 2]]}))))

  (testing "range"
    (is (= [0 1 2] (vec (call-base-fn :range {:end 3}))))
    (is (= [1 2 3] (vec (call-base-fn :range {:start 1 :end 4}))))
    (is (= [0 2 4] (vec (call-base-fn :range {:start 0 :end 5 :step 2})))))

  (testing "repeat"
    (is (= [5 5 5] (call-base-fn :repeat {:n 3 :x 5}))))

  (testing "take"
    (is (= [1 2] (call-base-fn :take {:n 2 :coll [1 2 3 4]}))))

  (testing "drop"
    (is (= [3 4] (call-base-fn :drop {:n 2 :coll [1 2 3 4]}))))

  (testing "reverse"
    (is (= [3 2 1] (call-base-fn :reverse {:coll [1 2 3]}))))

  (testing "sort"
    (is (= [1 2 3] (call-base-fn :sort {:coll [3 1 2]}))))

  (testing "concat"
    (is (= [1 2 3 4] (call-base-fn :concat {:colls [[1 2] [3 4]]})))
    (is (= [1 2 3 4 5 6] (call-base-fn :concat {:colls [[1 2] [3 4] [5 6]]})))
    (is (= [] (call-base-fn :concat {:colls []}))))

  (testing "flatten"
    (is (= [1 2 3 4] (call-base-fn :flatten {:coll [[1 2] [3 [4]]]}))))

  (testing "distinct"
    (is (= [1 2 3] (call-base-fn :distinct {:coll [1 2 1 3 2 3]})))))


;; === HOF Tests ===
;; HOF functions require a storage context because they execute fn-ids.
;; We create helper functions and register them as base-fns for testing.

(defn- setup-hof-storage
  "Creates storage with helper functions for HOF tests."
  []
  (let [storage (gsm/create-storage)]
    (register-all!)

    ;; Create 'double' function: item -> item * 2
    (let [double-schema (sp/create-entity storage :fn-schema
                                          {:name "double"
                                           :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id double-schema)
                               :name "item"
                               :type :int
                               :required true})
          double-fn (sp/create-entity storage :fn
                                      {:name "my-double"
                                       :fn-schema-id (:id double-schema)})

          ;; Create 'gt2' predicate: item -> item > 2
          gt2-schema (sp/create-entity storage :fn-schema
                                       {:name "gt2"
                                        :returned-type :bool})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id gt2-schema)
                               :name "item"
                               :type :int
                               :required true})
          gt2-fn (sp/create-entity storage :fn
                                   {:name "my-gt2"
                                    :fn-schema-id (:id gt2-schema)})

          ;; Create 'add-reducer' function: (acc, item) -> acc + item
          add-schema (sp/create-entity storage :fn-schema
                                       {:name "add-reducer"
                                        :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id add-schema)
                               :name "acc"
                               :type :int
                               :required true})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id add-schema)
                               :name "item"
                               :type :int
                               :required true})
          add-fn (sp/create-entity storage :fn
                                   {:name "my-add-reducer"
                                    :fn-schema-id (:id add-schema)})

          ;; Create 'get-category' function: item -> :small/:large
          cat-schema (sp/create-entity storage :fn-schema
                                       {:name "get-category"
                                        :returned-type :enum})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id cat-schema)
                               :name "item"
                               :type :int
                               :required true})
          cat-fn (sp/create-entity storage :fn
                                   {:name "my-get-category"
                                    :fn-schema-id (:id cat-schema)})]

      ;; Register base function implementations
      (exec/register-base-fn! :double
                              (fn [{:keys [item]} ctx]
                                (* 2 (exec/force-value item ctx))))

      (exec/register-base-fn! :gt2
                              (fn [{:keys [item]} ctx]
                                (> (exec/force-value item ctx) 2)))

      (exec/register-base-fn! :add-reducer
                              (fn [{:keys [acc item]} ctx]
                                (+ (exec/force-value acc ctx)
                                   (exec/force-value item ctx))))

      (exec/register-base-fn! :get-category
                              (fn [{:keys [item]} ctx]
                                (if (> (exec/force-value item ctx) 5)
                                  :large
                                  :small)))

      {:storage storage
       :double-fn-id (:id double-fn)
       :gt2-fn-id (:id gt2-fn)
       :add-fn-id (:id add-fn)
       :cat-fn-id (:id cat-fn)})))


(deftest hof-map-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            map-fn (exec/get-base-fn :map)
            f-thunk (literal-thunk double-fn-id)
            coll-thunk (literal-thunk [1 2 3 4 5])]

        (testing "map doubles each element"
          (is (= [2 4 6 8 10] (map-fn {:f f-thunk :coll coll-thunk} ctx))))

        (testing "map on empty collection"
          (is (= [] (map-fn {:f f-thunk :coll (literal-thunk [])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-filter-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            filter-fn (exec/get-base-fn :filter)
            pred-thunk (literal-thunk gt2-fn-id)
            coll-thunk (literal-thunk [1 2 3 4 5])]

        (testing "filter keeps elements > 2"
          (is (= [3 4 5] (filter-fn {:pred pred-thunk :coll coll-thunk} ctx))))

        (testing "filter on empty collection"
          (is (= [] (filter-fn {:pred pred-thunk :coll (literal-thunk [])} ctx))))

        (testing "filter with no matches"
          (is (= [] (filter-fn {:pred pred-thunk :coll (literal-thunk [1 2])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-reduce-test
  (let [{:keys [storage add-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            reduce-fn (exec/get-base-fn :reduce)
            f-thunk (literal-thunk add-fn-id)
            init-thunk (literal-thunk 0)
            coll-thunk (literal-thunk [1 2 3 4 5])]

        (testing "reduce sums all elements"
          (is (= 15 (reduce-fn {:f f-thunk :init init-thunk :coll coll-thunk} ctx))))

        (testing "reduce with different initial value"
          (is (= 25 (reduce-fn {:f f-thunk :init (literal-thunk 10) :coll coll-thunk} ctx))))

        (testing "reduce on empty collection returns init"
          (is (zero? (reduce-fn {:f f-thunk :init init-thunk :coll (literal-thunk [])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-some-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            base-some (exec/get-base-fn :some)
            pred-thunk (literal-thunk gt2-fn-id)]

        (testing "some finds first truthy result"
          (is (true? (base-some {:pred pred-thunk :coll (literal-thunk [1 2 3 4])} ctx))))

        (testing "some returns nil when no match"
          (is (nil? (base-some {:pred pred-thunk :coll (literal-thunk [1 2])} ctx))))

        (testing "some on empty collection"
          (is (nil? (base-some {:pred pred-thunk :coll (literal-thunk [])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-every?-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            every?-fn (exec/get-base-fn :every?)
            pred-thunk (literal-thunk gt2-fn-id)]

        (testing "every? returns true when all match"
          (is (true? (every?-fn {:pred pred-thunk :coll (literal-thunk [3 4 5])} ctx))))

        (testing "every? returns false when some don't match"
          (is (false? (every?-fn {:pred pred-thunk :coll (literal-thunk [1 3 5])} ctx))))

        (testing "every? on empty collection returns true"
          (is (true? (every?-fn {:pred pred-thunk :coll (literal-thunk [])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-find-first-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            find-first-fn (exec/get-base-fn :find-first)
            pred-thunk (literal-thunk gt2-fn-id)]

        (testing "find-first returns first matching element"
          (is (= 3 (find-first-fn {:pred pred-thunk :coll (literal-thunk [1 2 3 4 5])} ctx))))

        (testing "find-first returns nil when no match"
          (is (nil? (find-first-fn {:pred pred-thunk :coll (literal-thunk [1 2])} ctx))))

        (testing "find-first on empty collection"
          (is (nil? (find-first-fn {:pred pred-thunk :coll (literal-thunk [])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-group-by-test
  (let [{:keys [storage cat-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            group-by-fn (exec/get-base-fn :group-by)
            key-fn-thunk (literal-thunk cat-fn-id)]

        (testing "group-by groups by category"
          (let [result (group-by-fn {:key-fn key-fn-thunk
                                     :coll (literal-thunk [1 3 6 8 2 10])}
                                    ctx)]
            (is (= [1 3 2] (:small result)))
            (is (= [6 8 10] (:large result)))))

        (testing "group-by on empty collection"
          (is (= {} (group-by-fn {:key-fn key-fn-thunk :coll (literal-thunk [])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-sort-by-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            sort-by-fn (exec/get-base-fn :sort-by)
            ;; Sort by doubled value (effectively same order for positive ints)
            key-fn-thunk (literal-thunk double-fn-id)]

        (testing "sort-by sorts by key function result"
          (is (= [1 1 2 3 4 5] (sort-by-fn {:key-fn key-fn-thunk
                                            :coll (literal-thunk [3 1 4 1 5 2])}
                                           ctx))))

        (testing "sort-by on empty collection"
          (is (= [] (sort-by-fn {:key-fn key-fn-thunk :coll (literal-thunk [])} ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-apply-test
  (let [{:keys [storage add-fn-id]} (setup-hof-storage)]
    (try
      (let [ctx (exec/create-context {:storage storage})
            apply-fn (exec/get-base-fn :apply)]

        (testing "apply calls function with args map"
          (is (= 7 (apply-fn {:f (literal-thunk add-fn-id)
                              :args (literal-thunk {:acc 3 :item 4})}
                             ctx))))
        (testing "apply with different args"
          (is (= 15 (apply-fn {:f (literal-thunk add-fn-id)
                               :args (literal-thunk {:acc 10 :item 5})}
                              ctx)))))
      (finally
        (sp/close storage)))))


(deftest hof-identity-test
  (register-hof!)

  (testing "identity returns value unchanged"
    (is (= 42 (call-base-fn :identity {:x 42})))
    (is (= "hello" (call-base-fn :identity {:x "hello"})))
    (is (= [1 2 3] (call-base-fn :identity {:x [1 2 3]})))))


(deftest hof-constantly-test
  (register-hof!)

  (testing "constantly returns value"
    (is (= 42 (call-base-fn :constantly {:x 42})))
    (is (= "always" (call-base-fn :constantly {:x "always"})))))


;; === Storage Sync Tests ===

(deftest sync-to-storage-test
  (testing "sync-storage! creates fn-schemas and arg-schemas"
    (let [storage (gsm/create-storage)]
      (try
        ;; First sync - should create all
        (let [result (sync-storage! storage)]
          (is (pos? (:created (:fn-schemas result))))
          (is (zero? (:updated (:fn-schemas result))))
          (is (pos? (:created (:arg-schemas result))))
          (is (zero? (:updated (:arg-schemas result)))))

        ;; Verify some fn-schemas exist
        (let [all-schemas (sp/query-entities storage :fn-schema {})]
          (is (pos? (count all-schemas)))
          ;; Check :add fn-schema exists
          (is (some #(= "add" (:name %)) all-schemas))
          ;; Check it has base-fn-name set
          (is (some #(= "add" (:base-fn-name %)) all-schemas)))

        ;; Verify arg-schemas exist
        (let [all-args (sp/query-entities storage :arg-schema {})]
          (is (pos? (count all-args)))
          ;; Find :add's fn-schema
          (let [add-schema (first (sp/query-entities storage :fn-schema {:name "add"}))
                add-args (sp/query-entities storage :arg-schema {:fn-schema-id (:id add-schema)})]
            (is (= 1 (count add-args)))
            (is (= #{"nums"} (set (map :name add-args))))))
        (finally
          (sp/close storage)))))

  (testing "sync-storage! is idempotent"
    (let [storage (gsm/create-storage)]
      (try
        ;; First sync
        (sync-storage! storage)
        (let [schemas-after-first (sp/query-entities storage :fn-schema {})]
          ;; Second sync - should update, not create
          (let [result (sync-storage! storage)]
            (is (zero? (:created (:fn-schemas result))))
            (is (pos? (:updated (:fn-schemas result))))
            (is (zero? (:created (:arg-schemas result))))
            (is (pos? (:updated (:arg-schemas result)))))
          ;; Same count of schemas
          (is (= (count schemas-after-first)
                 (count (sp/query-entities storage :fn-schema {})))))
        (finally
          (sp/close storage)))))

  (testing "get-all-defs returns function definitions"
    (let [defs (bf/get-all-defs)]
      (is (map? defs))
      (is (contains? defs :add))
      (is (contains? defs :map))
      (is (= {:nums :jsonb} (:args (:add defs))))
      (is (= :numeric (:return-type (:add defs)))))))
