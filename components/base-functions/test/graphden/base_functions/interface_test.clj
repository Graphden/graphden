(ns graphden.base-functions.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.arithmetic :as arithmetic]
    [graphden.base-functions.core :as core]
    [graphden.base-functions.interface :as bf]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.config :as config]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


;; === Helper Functions ===

(defn literal-delay
  "Creates a delay wrapping a literal value."
  [value]
  (delay value))


(defn call-base-fn
  "Calls a base function with literal values wrapped in delays."
  [fn-name args]
  (let [delays (into {} (map (fn [[k v]] [k (delay v)]) args))]
    ((exec/get-base-fn fn-name) delays nil)))


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

  (testing "add - overflow to Infinity throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow.*infinite"
          (call-base-fn :add {:nums [Double/MAX_VALUE Double/MAX_VALUE]}))))

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

  (testing "sub with empty list throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Subtraction requires at least one number"
          (call-base-fn :sub {:nums []}))))

  (testing "div with empty list throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Division requires at least one number"
          (call-base-fn :div {:nums []}))))

  (testing "mul - overflow to Infinity throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow.*infinite"
          (call-base-fn :mul {:nums [Double/MAX_VALUE 2.0]}))))

  (testing "sub - overflow to negative Infinity throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow.*infinite"
          (call-base-fn :sub {:nums [(- Double/MAX_VALUE) Double/MAX_VALUE]}))))

  (testing "overflow exception contains correct data"
    (try
      (call-base-fn :mul {:nums [Double/MAX_VALUE Double/MAX_VALUE]})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/numeric-overflow (:type (ex-data e))))
        (is (= :mul (:operation (ex-data e))))
        (is (= 2 (:num-count (ex-data e)))))))

  (testing "NaN result throws"
    ;; Test the private function directly to cover the NaN branch
    ;; NaN can occur from 0.0/0.0 in floating point, but our div catches that first
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"NaN"
          (#'arithmetic/check-numeric-result! Double/NaN :test [1 2]))))

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
          tracking-delay (delay (do (swap! call-count inc) true))
          false-delay (delay false)
          and-fn (exec/get-base-fn :and)]
      (and-fn {:a false-delay :b tracking-delay} nil)
      ;; Short-circuit: b is never evaluated when a is false
      (is (zero? @call-count) "and short-circuits - second arg not evaluated")))

  (testing "or short-circuits on true - second arg not evaluated"
    ;; Clojure's 'or' macro short-circuits, so when a is true, b is never evaluated
    (let [call-count (atom 0)
          tracking-delay (delay (do (swap! call-count inc) false))
          true-delay (delay true)
          or-fn (exec/get-base-fn :or)]
      (or-fn {:a true-delay :b tracking-delay} nil)
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

  (testing "str-split - empty separator throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"separator cannot be empty"
          (call-base-fn :str-split {:s "test" :sep ""}))))

  (testing "str-join"
    (is (= "a,b,c" (call-base-fn :str-join {:coll ["a" "b" "c"] :sep ","})))
    (is (= "abc" (call-base-fn :str-join {:coll ["a" "b" "c"]})))))


;; === String Regex Safety Tests ===

(deftest string-regex-safety-test
  (register-strings!)

  (testing "str-split - regex pattern too long throws"
    (let [long-pattern (str/join (repeat 200 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Regex pattern too long"
            (call-base-fn :str-split {:s "test" :sep long-pattern})))))

  (testing "str-split - input string too long throws"
    (let [long-input (str/join (repeat 200000 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Input string too long"
            (call-base-fn :str-split {:s long-input :sep ","})))))

  (testing "str-split - normal regex works"
    (is (= ["a" "b" "c"] (call-base-fn :str-split {:s "a-b-c" :sep "-"})))
    (is (= ["hello" "world"] (call-base-fn :str-split {:s "hello world" :sep " "})))
    (is (= ["one" "two" "three"] (call-base-fn :str-split {:s "one::two::three" :sep "::"}))))

  (testing "str-split - regex with special chars works"
    (is (= ["a" "b" "c"] (call-base-fn :str-split {:s "a.b.c" :sep "\\."})))
    (is (= ["1" "2" "3"] (call-base-fn :str-split {:s "1|2|3" :sep "\\|"}))))

  ;; Note: Regex compilation timeout test is inherently flaky because:
  ;; - 1ms timeout may not trigger on fast machines
  ;; - Finding a pattern that reliably times out is difficult
  ;; The timeout code path is covered implicitly by integration tests
  )


(deftest string-regex-edge-cases-test
  "Tests edge cases in regex handling for complete coverage."
  (register-strings!)

  (testing "str-split - uses configured limits"
    ;; Verify that with-regex-limits correctly applies custom limits
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Regex pattern too long"
          (config/with-regex-limits
            {:max-pattern-length 5}
            #(call-base-fn :str-split {:s "test" :sep "longer-pattern"})))))

  (testing "str-split - input length limit applies"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Input string too long"
          (config/with-regex-limits
            {:max-input-length 10}
            #(call-base-fn :str-split {:s "this is a longer string" :sep " "}))))))


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
    (is (= [0 1 2] (call-base-fn :range {:end 3})))
    (is (= [1 2 3] (call-base-fn :range {:start 1 :end 4})))
    (is (= [0 2 4] (call-base-fn :range {:start 0 :end 5 :step 2}))))

  (testing "range - step=0 throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step cannot be zero"
          (call-base-fn :range {:start 0 :end 10 :step 0}))))

  (testing "range - negative step"
    (is (= [10 8 6 4 2] (call-base-fn :range {:start 10 :end 1 :step -2})))
    (is (= [5 4 3 2 1] (call-base-fn :range {:start 5 :end 0 :step -1}))))

  (testing "range - too large throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"range would produce.*max allowed"
          (call-base-fn :range {:start 0 :end 2000000 :step 1}))))

  (testing "repeat"
    (is (= [5 5 5] (call-base-fn :repeat {:n 3 :x 5}))))

  (testing "repeat - negative count throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"repeat count cannot be negative"
          (call-base-fn :repeat {:n -1 :x 5}))))

  (testing "repeat - too large throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"repeat count.*exceeds max allowed"
          (call-base-fn :repeat {:n 2000000 :x 5}))))

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
;; HOF functions use executor with fn-ids. Tests create complete function graphs
;; with map/filter/etc calling helper functions via fn-result-value.

(defn- setup-hof-storage
  "Creates storage with helper functions for HOF tests.
   Returns fn-ids for use in HOF tests via executor."
  []
  (let [storage (gsm/create-storage)]
    (register-all!)
    ;; Sync base function schemas to storage so HOF can be found
    (registry/sync-defs-to-storage! storage (bf/get-all-defs))

    ;; Create 'double' function: x -> x * 2 (single required arg)
    (let [double-schema (sp/create-entity storage :fn-schema
                                          {:name "double"
                                           :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id double-schema)
                               :name "x"
                               :type :int
                               :required true})
          double-fn (sp/create-entity storage :fn
                                      {:name "my-double"
                                       :fn-schema-id (:id double-schema)})

          ;; Create 'gt2' predicate: x -> x > 2 (single required arg)
          gt2-schema (sp/create-entity storage :fn-schema
                                       {:name "gt2"
                                        :returned-type :bool})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id gt2-schema)
                               :name "x"
                               :type :int
                               :required true})
          gt2-fn (sp/create-entity storage :fn
                                   {:name "my-gt2"
                                    :fn-schema-id (:id gt2-schema)})

          ;; Create 'add-reducer' function: pair -> pair[0] + pair[1] (single required arg)
          ;; Takes [acc item] as single argument
          add-schema (sp/create-entity storage :fn-schema
                                       {:name "add-reducer"
                                        :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id add-schema)
                               :name "pair"
                               :type :jsonb
                               :required true})
          add-fn (sp/create-entity storage :fn
                                   {:name "my-add-reducer"
                                    :fn-schema-id (:id add-schema)})

          ;; Create 'get-category' function: x -> :small/:large (single required arg)
          cat-schema (sp/create-entity storage :fn-schema
                                       {:name "get-category"
                                        :returned-type :text})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id cat-schema)
                               :name "x"
                               :type :int
                               :required true})
          cat-fn (sp/create-entity storage :fn
                                   {:name "my-get-category"
                                    :fn-schema-id (:id cat-schema)})]

      ;; Register base function implementations
      (exec/register-base-fn! :double
                              (fn [{:keys [x]} _ctx]
                                (* 2 @x)))

      (exec/register-base-fn! :gt2
                              (fn [{:keys [x]} _ctx]
                                (> @x 2)))

      (exec/register-base-fn! :add-reducer
                              (fn [{:keys [pair]} _ctx]
                                (let [[acc item] @pair]
                                  (+ acc item))))

      (exec/register-base-fn! :get-category
                              (fn [{:keys [x]} _ctx]
                                (if (> @x 5) "large" "small")))

      {:storage storage
       :double-fn-id (:id double-fn)
       :gt2-fn-id (:id gt2-fn)
       :add-fn-id (:id add-fn)
       :cat-fn-id (:id cat-fn)})))


(defn- create-hof-caller
  "Creates a function that calls a HOF (map/filter/etc) with given fn-id and collection.
   Returns the result of executing the HOF."
  [storage hof-name f-arg-name fn-id coll]
  (let [;; Get HOF fn-schema
        hof-schema (first (sp/query-entities storage :fn-schema {:name hof-name}))
        hof-arg-schemas (sp/query-entities storage :arg-schema {:fn-schema-id (:id hof-schema)})
        f-arg (first (filter #(= f-arg-name (:name %)) hof-arg-schemas))
        coll-arg (first (filter #(= "coll" (:name %)) hof-arg-schemas))
        ;; Create HOF instance with unique name
        hof-fn (sp/create-entity storage :fn
                                 {:name (str "test-" hof-name "-" (random-uuid))
                                  :fn-schema-id (:id hof-schema)})
        ;; Set :f/:pred/:key-fn arg to fn-id
        _ (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id hof-fn)
                             :arg-schema-id (:id f-arg)
                             :value fn-id})
        ;; Set :coll arg
        _ (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id hof-fn)
                             :arg-schema-id (:id coll-arg)
                             :value coll})
        ctx (exec/create-context {:storage storage})]
    (exec/execute ctx (:id hof-fn) nil)))


(defn- create-reduce-caller
  "Creates a function that calls reduce with given fn-id, init value and collection."
  [storage fn-id init coll]
  (let [reduce-schema (first (sp/query-entities storage :fn-schema {:name "reduce"}))
        reduce-arg-schemas (sp/query-entities storage :arg-schema {:fn-schema-id (:id reduce-schema)})
        f-arg (first (filter #(= "f" (:name %)) reduce-arg-schemas))
        init-arg (first (filter #(= "init" (:name %)) reduce-arg-schemas))
        coll-arg (first (filter #(= "coll" (:name %)) reduce-arg-schemas))
        reduce-fn (sp/create-entity storage :fn
                                    {:name (str "test-reduce-" (random-uuid))
                                     :fn-schema-id (:id reduce-schema)})
        _ (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id reduce-fn)
                             :arg-schema-id (:id f-arg)
                             :value fn-id})
        _ (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id reduce-fn)
                             :arg-schema-id (:id init-arg)
                             :value init})
        _ (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id reduce-fn)
                             :arg-schema-id (:id coll-arg)
                             :value coll})
        ctx (exec/create-context {:storage storage})]
    (exec/execute ctx (:id reduce-fn) nil)))


(defn- create-apply-caller
  "Creates a function that calls apply with given fn-id and args."
  [storage fn-id args]
  (let [apply-schema (first (sp/query-entities storage :fn-schema {:name "apply"}))
        apply-arg-schemas (sp/query-entities storage :arg-schema {:fn-schema-id (:id apply-schema)})
        f-arg (first (filter #(= "f" (:name %)) apply-arg-schemas))
        args-arg (first (filter #(= "args" (:name %)) apply-arg-schemas))
        apply-fn (sp/create-entity storage :fn
                                   {:name (str "test-apply-" (random-uuid))
                                    :fn-schema-id (:id apply-schema)})
        _ (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id apply-fn)
                             :arg-schema-id (:id f-arg)
                             :value fn-id})
        _ (sp/create-entity storage :arg-value
                            {:owner-fn-id (:id apply-fn)
                             :arg-schema-id (:id args-arg)
                             :value args})
        ctx (exec/create-context {:storage storage})]
    (exec/execute ctx (:id apply-fn) nil)))


(deftest hof-map-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (testing "map doubles each element"
        (is (= [2 4 6 8 10] (create-hof-caller storage "map" "f" double-fn-id [1 2 3 4 5]))))

      (testing "map on empty collection"
        (is (= [] (create-hof-caller storage "map" "f" double-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-filter-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "filter keeps elements > 2"
        (is (= [3 4 5] (create-hof-caller storage "filter" "pred" gt2-fn-id [1 2 3 4 5]))))

      (testing "filter on empty collection"
        (is (= [] (create-hof-caller storage "filter" "pred" gt2-fn-id []))))

      (testing "filter with no matches"
        (is (= [] (create-hof-caller storage "filter" "pred" gt2-fn-id [1 2]))))
      (finally
        (sp/close storage)))))


(deftest hof-reduce-test
  (let [{:keys [storage add-fn-id]} (setup-hof-storage)]
    (try
      (testing "reduce sums all elements"
        (is (= 15 (create-reduce-caller storage add-fn-id 0 [1 2 3 4 5]))))

      (testing "reduce with different initial value"
        (is (= 25 (create-reduce-caller storage add-fn-id 10 [1 2 3 4 5]))))

      (testing "reduce on empty collection returns init"
        (is (zero? (create-reduce-caller storage add-fn-id 0 []))))
      (finally
        (sp/close storage)))))


(deftest hof-some-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "some finds first truthy result"
        (is (true? (create-hof-caller storage "some" "pred" gt2-fn-id [1 2 3 4]))))

      (testing "some returns nil when no match"
        (is (nil? (create-hof-caller storage "some" "pred" gt2-fn-id [1 2]))))

      (testing "some on empty collection"
        (is (nil? (create-hof-caller storage "some" "pred" gt2-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-every?-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "every? returns true when all match"
        (is (true? (create-hof-caller storage "every?" "pred" gt2-fn-id [3 4 5]))))

      (testing "every? returns false when some don't match"
        (is (false? (create-hof-caller storage "every?" "pred" gt2-fn-id [1 3 5]))))

      (testing "every? on empty collection returns true"
        (is (true? (create-hof-caller storage "every?" "pred" gt2-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-find-first-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "find-first returns first matching element"
        (is (= 3 (create-hof-caller storage "find-first" "pred" gt2-fn-id [1 2 3 4 5]))))

      (testing "find-first returns nil when no match"
        (is (nil? (create-hof-caller storage "find-first" "pred" gt2-fn-id [1 2]))))

      (testing "find-first on empty collection"
        (is (nil? (create-hof-caller storage "find-first" "pred" gt2-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-group-by-test
  (let [{:keys [storage cat-fn-id]} (setup-hof-storage)]
    (try
      (testing "group-by groups by category"
        (let [result (create-hof-caller storage "group-by" "key-fn" cat-fn-id [1 3 6 8 2 10])]
          (is (= [1 3 2] (get result "small")))
          (is (= [6 8 10] (get result "large")))))

      (testing "group-by on empty collection"
        (is (= {} (create-hof-caller storage "group-by" "key-fn" cat-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-sort-by-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (testing "sort-by sorts by key function result"
        (is (= [1 1 2 3 4 5] (create-hof-caller storage "sort-by" "key-fn" double-fn-id [3 1 4 1 5 2]))))

      (testing "sort-by on empty collection"
        (is (= [] (create-hof-caller storage "sort-by" "key-fn" double-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-apply-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (testing "apply calls function with single arg"
        (is (= 10 (create-apply-caller storage double-fn-id 5))))

      (testing "apply with different value"
        (is (= 20 (create-apply-caller storage double-fn-id 10))))
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
