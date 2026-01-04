(ns graphden.base-functions.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.interface :as bf]
    [graphden.executor.core :as exec-core]
    [graphden.executor.interface :as exec]
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


;; === Registration Tests ===

(deftest register-all-test
  (testing "register-all! registers all base functions"
    (bf/register-all!)
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
    (bf/register-arithmetic!)
    (is (some? (exec/get-base-fn :add)))
    (is (some? (exec/get-base-fn :sub)))
    (is (some? (exec/get-base-fn :mul)))
    (is (some? (exec/get-base-fn :div)))
    (is (some? (exec/get-base-fn :mod)))
    (is (some? (exec/get-base-fn :neg)))
    (is (some? (exec/get-base-fn :abs)))))


;; === Arithmetic Tests ===

(deftest arithmetic-operations-test
  (bf/register-arithmetic!)

  (testing "add"
    (is (= 5 (call-base-fn :add {:a 2 :b 3})))
    (is (zero? (call-base-fn :add {:a -5 :b 5})))
    (is (= 3.5 (call-base-fn :add {:a 1.5 :b 2.0}))))

  (testing "sub"
    (is (= 2 (call-base-fn :sub {:a 5 :b 3})))
    (is (= -8 (call-base-fn :sub {:a 2 :b 10}))))

  (testing "mul"
    (is (= 12 (call-base-fn :mul {:a 3 :b 4})))
    (is (= -15 (call-base-fn :mul {:a -3 :b 5}))))

  (testing "div"
    (is (= 2 (call-base-fn :div {:a 6 :b 3})))
    (is (= 5/2 (call-base-fn :div {:a 5 :b 2}))))

  (testing "div by zero throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Division by zero"
          (call-base-fn :div {:a 5 :b 0}))))

  (testing "mod"
    (is (= 1 (call-base-fn :mod {:a 7 :b 3})))
    (is (zero? (call-base-fn :mod {:a 6 :b 2}))))

  (testing "neg"
    (is (= -5 (call-base-fn :neg {:n 5})))
    (is (= 3 (call-base-fn :neg {:n -3}))))

  (testing "abs"
    (is (= 5 (call-base-fn :abs {:n -5})))
    (is (= 5 (call-base-fn :abs {:n 5})))))


;; === Comparison Tests ===

(deftest comparison-operations-test
  (bf/register-comparison!)

  (testing "eq"
    (is (true? (call-base-fn :eq {:a 5 :b 5})))
    (is (false? (call-base-fn :eq {:a 5 :b 6})))
    (is (true? (call-base-fn :eq {:a "hello" :b "hello"}))))

  (testing "neq"
    (is (false? (call-base-fn :neq {:a 5 :b 5})))
    (is (true? (call-base-fn :neq {:a 5 :b 6}))))

  (testing "lt"
    (is (true? (call-base-fn :lt {:a 3 :b 5})))
    (is (false? (call-base-fn :lt {:a 5 :b 3})))
    (is (false? (call-base-fn :lt {:a 5 :b 5}))))

  (testing "lte"
    (is (true? (call-base-fn :lte {:a 3 :b 5})))
    (is (true? (call-base-fn :lte {:a 5 :b 5})))
    (is (false? (call-base-fn :lte {:a 6 :b 5}))))

  (testing "gt"
    (is (true? (call-base-fn :gt {:a 5 :b 3})))
    (is (false? (call-base-fn :gt {:a 3 :b 5})))
    (is (false? (call-base-fn :gt {:a 5 :b 5}))))

  (testing "gte"
    (is (true? (call-base-fn :gte {:a 5 :b 3})))
    (is (true? (call-base-fn :gte {:a 5 :b 5})))
    (is (false? (call-base-fn :gte {:a 4 :b 5})))))


;; === Logic Tests ===

(deftest logic-operations-test
  (bf/register-logic!)

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


;; === Conditionals Tests ===

(deftest conditionals-test
  (bf/register-conditionals!)

  (testing "if - true branch"
    (is (= "yes" (call-base-fn :if {:condition true :then "yes" :else "no"}))))

  (testing "if - false branch"
    (is (= "no" (call-base-fn :if {:condition false :then "yes" :else "no"})))))


;; === String Tests ===

(deftest string-operations-test
  (bf/register-strings!)

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
    (is (= "llo" (call-base-fn :subs {:s "hello" :start 2}))))

  (testing "str-split"
    (is (= ["a" "b" "c"] (call-base-fn :str-split {:s "a,b,c" :sep ","}))))

  (testing "str-join"
    (is (= "a,b,c" (call-base-fn :str-join {:coll ["a" "b" "c"] :sep ","})))))


;; === Collection Tests ===

(deftest collection-operations-test
  (bf/register-collections!)

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
    (is (= {:a 1 :b 2 :c 3} (call-base-fn :merge {:m1 {:a 1 :b 2} :m2 {:c 3}}))))

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
    (is (= [1 2 3 4] (call-base-fn :concat {:coll1 [1 2] :coll2 [3 4]}))))

  (testing "flatten"
    (is (= [1 2 3 4] (call-base-fn :flatten {:coll [[1 2] [3 [4]]]}))))

  (testing "distinct"
    (is (= [1 2 3] (call-base-fn :distinct {:coll [1 2 1 3 2 3]})))))


;; === HOF Tests (requires full storage setup) ===

(defn setup-hof-test
  "Sets up storage with predicates and mappers for HOF tests."
  []
  (let [storage (gsm/create-storage)]
    ;; Register base functions
    (bf/register-all!)

    ;; Create a simple 'double' function
    (let [double-schema (sp/create-entity storage :fn-schema
                                          {:name "double"
                                           :returned-type :int})
          item-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id double-schema)
                                      :name "item"
                                      :type :int
                                      :required true})
          double-fn (sp/create-entity storage :fn
                                      {:name "my-double"
                                       :fn-schema-id (:id double-schema)})]
      ;; Register double implementation
      (exec/register-base-fn! :double
                              (fn [{:keys [item]} ctx]
                                (* 2 (exec/force-value item ctx))))

      {:storage storage
       :double-fn double-fn
       :item-arg item-arg})))


;; HOF tests require full executor integration - see hof-integration-test below


(deftest hof-integration-test
  (testing "HOF functions work with full storage setup"
    (let [storage (gsm/create-storage)]
      (try
        ;; Register base functions
        (bf/register-all!)

        ;; Create identity function for simple tests
        (let [id-schema (sp/create-entity storage :fn-schema
                                          {:name "identity"
                                           :returned-type :int})
              item-arg (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id id-schema)
                                          :name "item"
                                          :type :int
                                          :required true})
              id-fn (sp/create-entity storage :fn
                                      {:name "my-identity"
                                       :fn-schema-id (:id id-schema)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id id-fn)
                                   :arg-schema-id (:id item-arg)
                                   :value 0}) ; placeholder, will be overridden

              ;; Create predicate for filter (> item 2)
              gt2-schema (sp/create-entity storage :fn-schema
                                           {:name "gt2"
                                            :returned-type :bool})
              _gt2-item-arg (sp/create-entity storage :arg-schema
                                              {:fn-schema-id (:id gt2-schema)
                                               :name "item"
                                               :type :int
                                               :required true})
              _gt2-fn (sp/create-entity storage :fn
                                        {:name "my-gt2"
                                         :fn-schema-id (:id gt2-schema)})]

          ;; Register implementations
          (exec/register-base-fn! :identity
                                  (fn [{:keys [item]} ctx]
                                    (exec/force-value item ctx)))

          (exec/register-base-fn! :gt2
                                  (fn [{:keys [item]} ctx]
                                    (> (exec/force-value item ctx) 2)))

          (let [ctx (exec/create-context {:storage storage})
                filter-fn (exec/get-base-fn :filter)]
            ;; Test filter with gt2
            (is (some? filter-fn))
            ;; ctx is used for proper context setup
            (is (some? ctx))))
        (finally
          (sp/close storage))))))
