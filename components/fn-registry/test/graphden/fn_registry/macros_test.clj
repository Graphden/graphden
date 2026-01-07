(ns graphden.fn-registry.macros-test
  "Tests for the defbase macro and helper functions."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fn-registry.macros :as macros]))


;; === binding-form? Tests ===
;; Note: binding-form? returns truthy (the matched symbol) or falsey (nil/false)

(deftest binding-form?-test
  (testing "recognizes fn forms"
    (is (#'macros/binding-form? '(fn [x] x)))
    (is (#'macros/binding-form? '(fn* [x] x)))
    (is (#'macros/binding-form? '(fn name [x] x))))

  (testing "recognizes let forms"
    (is (#'macros/binding-form? '(let [x 1] x)))
    (is (#'macros/binding-form? '(let* [x 1] x))))

  (testing "recognizes loop forms"
    (is (#'macros/binding-form? '(loop [x 1] x)))
    (is (#'macros/binding-form? '(loop* [x 1] x))))

  (testing "recognizes letfn forms"
    (is (#'macros/binding-form? '(letfn [(f [x] x)] (f 1))))
    (is (#'macros/binding-form? '(letfn* [(f [x] x)] (f 1)))))

  (testing "recognizes for/doseq forms"
    (is (#'macros/binding-form? '(for [x coll] x)))
    (is (#'macros/binding-form? '(doseq [x coll] x))))

  (testing "recognizes other binding forms"
    (is (#'macros/binding-form? '(with-open [f (open)] f)))
    (is (#'macros/binding-form? '(with-local-vars [x 1] x)))
    (is (#'macros/binding-form? '(binding [*out* writer] 1)))
    (is (#'macros/binding-form? '(catch Exception e e))))

  (testing "rejects non-binding forms"
    (is (not (#'macros/binding-form? '(+ 1 2))))
    (is (not (#'macros/binding-form? '(if cond then else))))
    (is (not (#'macros/binding-form? '(when cond body))))
    (is (not (#'macros/binding-form? '(map f coll)))))

  (testing "rejects non-sequences"
    (is (not (#'macros/binding-form? 'x)))
    (is (not (#'macros/binding-form? 42)))
    (is (not (#'macros/binding-form? nil)))
    (is (not (#'macros/binding-form? [1 2 3]))))

  (testing "rejects sequences with non-symbol first element"
    (is (not (#'macros/binding-form? '("fn" [x] x))))
    (is (not (#'macros/binding-form? '(123 [x] x))))))


;; === extract-bound-symbols Tests ===

(deftest extract-bound-symbols-test
  (testing "extracts symbols from fn forms"
    (is (= #{'x} (#'macros/extract-bound-symbols '(fn [x] x))))
    (is (= #{'x 'y} (#'macros/extract-bound-symbols '(fn [x y] (+ x y)))))
    (is (= #{'x} (#'macros/extract-bound-symbols '(fn name [x] x)))))

  (testing "extracts symbols from fn* forms"
    (is (= #{'a 'b} (#'macros/extract-bound-symbols '(fn* [a b] (+ a b))))))

  (testing "extracts symbols from let forms"
    (is (= #{'x} (#'macros/extract-bound-symbols '(let [x 1] x))))
    (is (= #{'x 'y} (#'macros/extract-bound-symbols '(let [x 1 y 2] (+ x y))))))

  (testing "extracts symbols from let* forms"
    (is (= #{'a} (#'macros/extract-bound-symbols '(let* [a 1] a)))))

  (testing "extracts symbols from loop forms"
    (is (= #{'x} (#'macros/extract-bound-symbols '(loop [x 0] x))))
    (is (= #{'i 'acc} (#'macros/extract-bound-symbols '(loop [i 0 acc 0] acc)))))

  (testing "extracts symbols from for/doseq forms"
    (is (= #{'x} (#'macros/extract-bound-symbols '(for [x coll] x))))
    (is (= #{'item} (#'macros/extract-bound-symbols '(doseq [item items] item)))))

  (testing "extracts symbols from catch forms"
    (is (= #{'e} (#'macros/extract-bound-symbols '(catch Exception e e))))
    (is (= #{'ex} (#'macros/extract-bound-symbols '(catch Throwable ex (.getMessage ex))))))

  (testing "handles empty catch (less than 3 elements)"
    (is (= #{} (#'macros/extract-bound-symbols '(catch Exception)))))

  (testing "handles fn without vector params (multi-arity)"
    (is (= #{} (#'macros/extract-bound-symbols '(fn
                                                  ([x] x)
                                                  ([x y] y))))))

  (testing "handles let with non-vector bindings (edge case)"
    (is (= #{} (#'macros/extract-bound-symbols '(let bad-bindings body)))))

  (testing "handles for with non-vector bindings (edge case)"
    (is (= #{} (#'macros/extract-bound-symbols '(for bad-bindings body)))))

  (testing "returns empty set for unknown binding forms"
    ;; This tests the :else branch
    (is (= #{} (#'macros/extract-bound-symbols '(unknown-form [x] x))))))


;; === get-arg-type Tests ===

(deftest get-arg-type-test
  (testing "extracts type from keyword shorthand"
    (is (= :int (#'macros/get-arg-type :int)))
    (is (= :text (#'macros/get-arg-type :text)))
    (is (= :fn (#'macros/get-arg-type :fn))))

  (testing "extracts type from map form"
    (is (= :int (#'macros/get-arg-type {:type :int})))
    (is (= :text (#'macros/get-arg-type {:type :text :required false})))
    (is (= :fn (#'macros/get-arg-type {:type :fn :required true}))))

  (testing "returns nil for map without :type"
    (is (nil? (#'macros/get-arg-type {:required false}))))

  (testing "handles empty map"
    (is (nil? (#'macros/get-arg-type {})))))


;; === transform-body Tests ===

(deftest transform-body-test
  (testing "replaces regular arg symbols with deref"
    (let [result (#'macros/transform-body 'x ['x] [])]
      (is (= '(clojure.core/when x (clojure.core/deref x)) result))))

  (testing "replaces :fn arg symbols with make-callable"
    (let [result (#'macros/transform-body 'f [] ['f])]
      (is (seq? result))
      ;; The transformation wraps f with exec/make-single-arg-callable
      (is (= 3 (count result)))
      (is (symbol? (first result)))))

  (testing "transforms nested expressions"
    (let [result (#'macros/transform-body '(+ a b) ['a 'b] [])]
      (is (list? result))
      (is (= '+ (first result)))))

  (testing "respects lexical scope in fn"
    ;; When 'x is bound by fn, it should not be replaced
    (let [result (#'macros/transform-body '(fn [x] x) ['x] [])]
      (is (= '(fn [x] x) result))))

  (testing "respects lexical scope in let"
    (let [result (#'macros/transform-body '(let [x 1] x) ['x] [])]
      (is (= '(let [x 1] x) result))))

  (testing "replaces outer usage, not inner binding"
    ;; outer-x should be replaced, but inner x should not
    (let [result (#'macros/transform-body '(+ outer-x (let [x 1] x)) ['outer-x 'x] [])]
      (is (list? result))
      ;; First arg (outer-x) should be transformed
      ;; Second arg (let ...) should have x NOT transformed inside
      (is (= '(let [x 1] x) (nth result 2)))))

  (testing "transforms vectors"
    (let [result (#'macros/transform-body '[a b c] ['a 'b] [])]
      (is (vector? result))
      ;; a and b should be transformed, c should not
      (is (= 3 (count result)))))

  (testing "transforms maps"
    (let [result (#'macros/transform-body '{:key val} ['val] [])]
      (is (map? result))))

  (testing "transforms sets"
    (let [result (#'macros/transform-body '#{a b} ['a] [])]
      (is (set? result))))

  (testing "returns as-is when no args to replace"
    (is (= '(+ 1 2) (#'macros/transform-body '(+ 1 2) [] [])))
    (is (= 42 (#'macros/transform-body 42 [] [])))
    (is (= "string" (#'macros/transform-body "string" [] []))))

  (testing "handles nil form"
    (is (nil? (#'macros/transform-body nil ['x] []))))

  (testing "handles keywords (not symbols)"
    (is (= :keyword (#'macros/transform-body :keyword ['x] [])))))


;; === defbase Macro Tests ===

(deftest defbase-expansion-test
  (testing "expands simple function without docstring"
    (let [expanded (macroexpand-1
                     '(graphden.fn-registry.macros/defbase test-add
                                                           {:args {:a :int, :b :int}
                                                            :return-type :int}
                                                           (+ a b)))]
      (is (= 'def (first expanded)))
      (is (= 'test-add (second expanded)))
      (let [fn-map (nth expanded 2)]
        (is (map? fn-map))
        (is (= {:a :int, :b :int} (:args fn-map)))
        (is (= :int (:return-type fn-map)))
        (is (some? (:impl fn-map))))))

  (testing "expands function with docstring"
    (let [expanded (macroexpand-1
                     '(graphden.fn-registry.macros/defbase test-fn
                                                           "This is a docstring."
                                                           {:args {:x :text}
                                                            :return-type :text}
                                                           x))]
      (is (= 'def (first expanded)))
      (is (= 'test-fn (second expanded)))
      (is (= "This is a docstring." (nth expanded 2)))))

  (testing "expands HOF with :fn type arg"
    (let [expanded (macroexpand-1
                     '(graphden.fn-registry.macros/defbase test-map
                                                           {:args {:f :fn, :coll :jsonb}
                                                            :return-type :jsonb}
                                                           (mapv f coll)))]
      (is (= 'def (first expanded)))
      (let [fn-map (if (string? (nth expanded 2))
                     (nth expanded 3)
                     (nth expanded 2))]
        (is (= {:f :fn, :coll :jsonb} (:args fn-map)))
        (is (= :jsonb (:return-type fn-map))))))

  (testing "handles optional args with map spec"
    (let [expanded (macroexpand-1
                     '(graphden.fn-registry.macros/defbase test-opt
                                                           {:args {:required :int
                                                                   :optional {:type :text :required false}}
                                                            :return-type :any}
                                                           [required optional]))]
      (is (= 'def (first expanded)))
      (let [fn-map (nth expanded 2)]
        (is (= {:required :int
                :optional {:type :text :required false}}
               (:args fn-map)))))))


;; === Integration Tests with Execution ===

(deftest defbase-function-execution-test
  (testing "defined function works with delay args"
    ;; Define a simple add function using defbase
    (eval '(do
             (require '[graphden.fn-registry.macros :refer [defbase]])
             (defbase test-add-exec
               {:args {:a :int, :b :int}
                :return-type :int}
               (+ a b))))
    ;; Get the impl and test it
    (let [impl (:impl (eval 'test-add-exec))
          result (impl {:a (delay 3) :b (delay 4)} nil)]
      (is (= 7 result))))

  (testing "short-circuit works with conditional"
    (eval '(do
             (require '[graphden.fn-registry.macros :refer [defbase]])
             (defbase test-if-exec
               {:args {:cond :bool, :then :any, :else :any}
                :return-type :any}
               (if cond then else))))
    (let [impl (:impl (eval 'test-if-exec))
          then-evaled (atom false)
          else-evaled (atom false)
          result (impl {:cond (delay true)
                        :then (delay (do (reset! then-evaled true) "then"))
                        :else (delay (do (reset! else-evaled true) "else"))}
                       nil)]
      (is (= "then" result))
      (is (true? @then-evaled))
      (is (false? @else-evaled))))

  (testing "optional args work with nil delay"
    (eval '(do
             (require '[graphden.fn-registry.macros :refer [defbase]])
             (defbase test-opt-exec
               {:args {:x :int, :y {:type :int :required false}}
                :return-type :int}
               (+ x (or y 0)))))
    (let [impl (:impl (eval 'test-opt-exec))]
      ;; With both args
      (is (= 7 (impl {:x (delay 3) :y (delay 4)} nil)))
      ;; With optional arg as nil
      (is (= 3 (impl {:x (delay 3) :y (delay nil)} nil))))))


;; === Edge Case Tests ===

(deftest edge-cases-test
  (testing "handles nested binding forms"
    ;; Inner let shadows outer arg
    (let [result (#'macros/transform-body
                  '(let [x 10]
                     (fn [y]
                       (+ x y z)))
                  ['x 'y 'z]
                  [])]
      ;; z should still be transformed since it's not shadowed
      ;; x and y should NOT be transformed where shadowed
      (is (list? result))))

  (testing "handles deeply nested structures"
    (let [result (#'macros/transform-body
                  '[[{:a x}] #{y}]
                  ['x 'y]
                  [])]
      (is (vector? result))
      (is (vector? (first result)))
      (is (map? (ffirst result)))
      (is (set? (second result)))))

  (testing "fn args with optional map spec are handled"
    (let [expanded (macroexpand-1
                     '(graphden.fn-registry.macros/defbase test-fn-opt
                                                           {:args {:f {:type :fn :required true}
                                                                   :coll :jsonb}
                                                            :return-type :jsonb}
                                                           (mapv f coll)))]
      (is (= 'def (first expanded)))))

  (testing "empty args map"
    (let [expanded (macroexpand-1
                     '(graphden.fn-registry.macros/defbase const-fn
                                                           {:args {}
                                                            :return-type :int}
                                                           42))]
      (is (= 'def (first expanded)))
      (let [fn-map (nth expanded 2)]
        (is (= {} (:args fn-map))))))

  (testing "symbol arg names (not keywords)"
    ;; This tests the branch where arg key is already a symbol, not keyword
    (let [expanded (macroexpand-1
                     '(graphden.fn-registry.macros/defbase sym-arg-fn
                                                           {:args {x :int, y :int}
                                                            :return-type :int}
                                                           (+ x y)))]
      (is (= 'def (first expanded)))
      (let [fn-map (nth expanded 2)]
        ;; Args should be preserved (note: symbols become keywords after parsing)
        (is (= 2 (count (:args fn-map))))))))
