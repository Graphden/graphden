(ns graphden.executor.composition.core-test
  "Unit tests for composition core functions.
   Tests pure/internal functions that don't require storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging]
    [graphden.executor.composition.core :as core]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.parsing :as parsing]
    [graphden.executor.composition.records :as records]
    [graphden.executor.composition.source-chain :as sc]
    [graphden.executor.composition.validation :as validation]
    [graphden.executor.registry.interface :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === valid-identifier? ===

(deftest valid-identifier?-test
  (testing "accepts valid identifiers"
    (is (parsing/valid-identifier? "hello"))
    (is (parsing/valid-identifier? "my-fn"))
    (is (parsing/valid-identifier? "_private"))
    (is (parsing/valid-identifier? "fn123"))
    (is (parsing/valid-identifier? "a"))
    (is (parsing/valid-identifier? "A-B-C"))
    (is (parsing/valid-identifier? "my_fn_name")))

  (testing "rejects identifiers starting with digit"
    (is (not (parsing/valid-identifier? "123abc")))
    (is (not (parsing/valid-identifier? "0test"))))

  (testing "rejects identifiers with special chars"
    (is (not (parsing/valid-identifier? "hello world")))
    (is (not (parsing/valid-identifier? "fn@name")))
    (is (parsing/valid-identifier? "a.b"))
    (is (parsing/valid-identifier? "core.arithmetic.add"))
    (is (not (parsing/valid-identifier? "a/b")))
    (is (not (parsing/valid-identifier? ">")))
    (is (not (parsing/valid-identifier? "+"))))

  (testing "rejects empty and nil"
    (is (not (parsing/valid-identifier? "")))
    (is (not (parsing/valid-identifier? nil))))

  (testing "rejects non-string input"
    (is (not (parsing/valid-identifier? 123)))
    (is (not (parsing/valid-identifier? true)))
    (is (not (parsing/valid-identifier? :keyword)))))


;; === local-fn-name? ===

(deftest local-fn-name?-test
  (testing "returns true for underscore-prefixed keywords"
    (is (parsing/local-fn-name? :_local))
    (is (parsing/local-fn-name? :_my-local-fn)))

  (testing "returns true for underscore-prefixed strings"
    (is (parsing/local-fn-name? "_local")))

  (testing "returns false for non-underscore names"
    (is (not (parsing/local-fn-name? :my-fn)))
    (is (not (parsing/local-fn-name? :hello)))
    (is (not (parsing/local-fn-name? "normal"))))

  (testing "returns nil/falsy for nil"
    (is (not (parsing/local-fn-name? nil)))))


;; === parse-fn-ref ===

(deftest parse-fn-ref-test
  (testing "returns keyword for valid identifiers"
    (is (= :my-fn (parsing/parse-fn-ref :my-fn)))
    (is (= :handler (parsing/parse-fn-ref :handler)))
    (is (= :my-fn-123 (parsing/parse-fn-ref :my-fn-123))))

  (testing "returns nil for non-keyword values"
    (is (nil? (parsing/parse-fn-ref "string")))
    (is (nil? (parsing/parse-fn-ref 42)))
    (is (nil? (parsing/parse-fn-ref nil)))
    (is (nil? (parsing/parse-fn-ref [1 2 3])))
    (is (nil? (parsing/parse-fn-ref {:a 1}))))

  (testing "returns nil for keywords with invalid names"
    (is (nil? (parsing/parse-fn-ref :>)))
    (is (nil? (parsing/parse-fn-ref :+)))
    (is (nil? (parsing/parse-fn-ref :123-starts-with-digit)))))


;; === free-arg? ===

(deftest free-arg?-test
  (testing "returns true when both value and ref-id are nil"
    (is (sc/free-arg? {:value nil :ref-id nil}))
    (is (sc/free-arg? {:id (random-uuid) :fn-id (random-uuid)}))
    (is (sc/free-arg? {})))

  (testing "returns false when value is set"
    (is (not (sc/free-arg? {:value 42 :ref-id nil})))
    (is (not (sc/free-arg? {:value "hello"})))
    (is (not (sc/free-arg? {:value false}))))

  (testing "returns false when ref-id is set"
    (is (not (sc/free-arg? {:value nil :ref-id (random-uuid)})))
    (is (not (sc/free-arg? {:ref-id (random-uuid)})))))


;; === in-sequence-chain? ===

(deftest in-sequence-chain?-test
  (testing "sequence-anchor with bound chain (next-arg-id set) is in-chain"
    (is (sc/in-sequence-chain? {:type :sequence :next-arg-id (random-uuid)})))

  (testing "sequence-anchor with empty chain (next-arg-id nil) is NOT in-chain"
    ;; Empty anchor is propagatable as a free slot — child can re-bind it.
    (is (not (sc/in-sequence-chain? {:type :sequence :next-arg-id nil}))))

  (testing "any arg with prev-arg-id set is in-chain (it's a sequence item)"
    (is (sc/in-sequence-chain? {:type :any :prev-arg-id (random-uuid) :name "content"}))
    (is (sc/in-sequence-chain? {:type :text :prev-arg-id (random-uuid)})))

  (testing "scalar args (no chain links) are NOT in-chain"
    (is (not (sc/in-sequence-chain? {:type :any})))
    (is (not (sc/in-sequence-chain? {:type :text :value "x"})))
    (is (not (sc/in-sequence-chain? {:type :any :ref-id (random-uuid)})))))


;; === partition-args-by-freedom ===

(deftest partition-args-by-freedom-test
  (testing "partitions into free and bound args"
    (let [free1 {:id :a :value nil :ref-id nil}
          free2 {:id :b}
          bound1 {:id :c :value 42}
          bound2 {:id :d :ref-id (random-uuid)}
          result (#'sc/partition-args-by-freedom [free1 free2 bound1 bound2])]
      (is (= [free1 free2] (:free-args result)))
      (is (= [bound1 bound2] (:bound-args result)))))

  (testing "handles empty input"
    (let [result (#'sc/partition-args-by-freedom [])]
      (is (= [] (:free-args result)))
      (is (= [] (:bound-args result)))))

  (testing "handles all free"
    (let [result (#'sc/partition-args-by-freedom [{:id :a} {:id :b}])]
      (is (= 2 (count (:free-args result))))
      (is (zero? (count (:bound-args result))))))

  (testing "handles all bound"
    (let [result (#'sc/partition-args-by-freedom [{:value 1} {:ref-id (random-uuid)}])]
      (is (zero? (count (:free-args result))))
      (is (= 2 (count (:bound-args result)))))))


;; === resolve-arg-name-cached ===

(deftest resolve-arg-name-cached-test
  (testing "returns name directly if present"
    (let [arg {:id :a :name "my-arg"}]
      (is (= "my-arg" (sc/resolve-arg-name-cached {} arg 0)))))

  (testing "follows source-id chain to find name"
    (let [root-id (random-uuid)
          mid-id (random-uuid)
          leaf-id (random-uuid)
          args-by-id {root-id {:id root-id :name "root-name"}
                      mid-id {:id mid-id :source-id root-id}
                      leaf-id {:id leaf-id :source-id mid-id}}]
      (is (= "root-name" (sc/resolve-arg-name-cached args-by-id
                                                     (get args-by-id leaf-id)
                                                     0)))))

  (testing "returns nil when source-id chain leads nowhere"
    (let [orphan-id (random-uuid)
          args-by-id {orphan-id {:id orphan-id :source-id (random-uuid)}}]
      (is (nil? (sc/resolve-arg-name-cached args-by-id
                                            (get args-by-id orphan-id)
                                            0)))))

  (testing "returns nil for arg with no name and no source-id"
    (is (nil? (sc/resolve-arg-name-cached {} {:id :x} 0))))

  (testing "throws when chain is too deep"
    ;; Create a circular chain that will exceed max-graph-iterations
    (binding [sp/*max-graph-iterations* 3]
      (let [id-a (random-uuid)
            id-b (random-uuid)
            id-c (random-uuid)
            id-d (random-uuid)
            id-e (random-uuid)
            ;; Chain: a->b->c->d->e, none have :name so it keeps recursing
            args-by-id {id-a {:id id-a :source-id id-b}
                        id-b {:id id-b :source-id id-c}
                        id-c {:id id-c :source-id id-d}
                        id-d {:id id-d :source-id id-e}
                        id-e {:id id-e :source-id id-a}}] ; circular to ensure depth exceeded
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too deep"
              (sc/resolve-arg-name-cached args-by-id
                                          (get args-by-id id-a)
                                          0)))))))


;; === collect-source-id-chain ===

(deftest collect-source-id-chain-test
  (testing "collects single id when no source-id"
    (let [id (random-uuid)
          args-by-id {id {:id id}}
          result (sc/collect-source-id-chain args-by-id id)]
      (is (contains? result id))
      (is (= 1 (count result)))))

  (testing "collects full chain"
    (let [a (random-uuid)
          b (random-uuid)
          c (random-uuid)
          args-by-id {a {:id a :source-id b}
                      b {:id b :source-id c}
                      c {:id c}}
          result (sc/collect-source-id-chain args-by-id a)]
      (is (= #{a b c} result))))

  (testing "handles nil arg-id"
    (is (= #{} (sc/collect-source-id-chain {} nil))))

  (testing "handles missing arg in index"
    (let [id (random-uuid)
          result (sc/collect-source-id-chain {} id)]
      ;; Should include the id itself and stop (source-id from nil arg is nil)
      (is (contains? result id)))))


;; === collect-free-args-from-fn ===

(deftest collect-free-args-from-fn-test
  (testing "returns free args from fn"
    (let [fn-id (random-uuid)
          free-arg {:id (random-uuid) :fn-id fn-id :value nil :ref-id nil}
          bound-arg {:id (random-uuid) :fn-id fn-id :value 42 :ref-id nil}
          args-data {:by-fn {fn-id [free-arg bound-arg]}
                     :by-id {(:id free-arg) free-arg
                             (:id bound-arg) bound-arg}}
          result (#'sc/collect-free-args-from-fn {} args-data fn-id #{} 0 true)]
      (is (= [free-arg] result))))

  (testing "follows ref-id to collect transitive free args"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-a has bound arg referencing fn-b
          bound-a {:id (random-uuid) :fn-id fn-a :value nil :ref-id fn-b}
          ;; fn-b has a free arg
          free-b {:id (random-uuid) :fn-id fn-b :value nil :ref-id nil}
          args-data {:by-fn {fn-a [bound-a]
                             fn-b [free-b]}
                     :by-id {(:id bound-a) bound-a
                             (:id free-b) free-b}}
          result (#'sc/collect-free-args-from-fn {} args-data fn-a #{} 0 true)]
      (is (= [free-b] result))))

  (testing "handles cycle in visited-fns"
    (let [fn-id (random-uuid)
          result (#'sc/collect-free-args-from-fn {} {:by-fn {}} fn-id #{fn-id} 0 true)]
      (is (= [] result))))

  (testing "returns empty for fn with no args"
    (let [fn-id (random-uuid)
          result (#'sc/collect-free-args-from-fn {} {:by-fn {}} fn-id #{} 0 true)]
      (is (= [] result))))

  (testing "throws when chain too deep"
    (binding [sp/*max-graph-iterations* 2]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too deep"
            (#'sc/collect-free-args-from-fn {} {:by-fn {}} (random-uuid) #{} 10 true))))))


;; === collect-parent-free-args ===

(deftest collect-parent-free-args-test
  (testing "collects free args from parent"
    (let [parent-id (random-uuid)
          free-arg {:id (random-uuid) :fn-id parent-id :value nil :ref-id nil}
          args-data {:by-fn {parent-id [free-arg]}
                     :by-id {(:id free-arg) free-arg}}
          result (sc/collect-parent-free-args {} args-data [parent-id] 0)]
      (is (= [free-arg] result))))

  (testing "collects free args from refs in parent's bound args"
    (let [parent-id (random-uuid)
          ref-fn-id (random-uuid)
          ;; parent has bound arg referencing ref-fn
          bound-arg {:id (random-uuid) :fn-id parent-id :value nil :ref-id ref-fn-id}
          ;; ref-fn has a free arg
          ref-free {:id (random-uuid) :fn-id ref-fn-id :value nil :ref-id nil}
          args-data {:by-fn {parent-id [bound-arg]
                             ref-fn-id [ref-free]}
                     :by-id {(:id bound-arg) bound-arg
                             (:id ref-free) ref-free}}
          result (sc/collect-parent-free-args {} args-data [parent-id] 0)]
      (is (= [ref-free] result))))

  (testing "throws when depth exceeded"
    (binding [sp/*max-graph-iterations* 2]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too deep"
            (sc/collect-parent-free-args {} {:by-fn {}} [(random-uuid)] 10))))))


;; === parse-arg-value-spec ===

(deftest parse-arg-value-spec-test
  (testing "simple values - no rename"
    (is (= {:rename nil :value-spec 42 :is-fn nil :literal? false}
           (#'records/parse-arg-value-spec 42)))
    (is (= {:rename nil :value-spec "hello" :is-fn nil :literal? false}
           (#'records/parse-arg-value-spec "hello")))
    (is (= {:rename nil :value-spec :my-fn :is-fn nil :literal? false}
           (#'records/parse-arg-value-spec :my-fn)))
    (is (= {:rename nil :value-spec nil :is-fn nil :literal? false}
           (#'records/parse-arg-value-spec nil))))

  (testing "map with :as rename only"
    (is (= {:rename :new-name :value-spec nil :is-fn false :literal? false}
           (#'records/parse-arg-value-spec {:as :new-name}))))

  (testing "map with :as and :value — marks literal so downstream skips fn-ref resolution"
    (is (= {:rename :first :value-spec 42 :is-fn false :literal? true}
           (#'records/parse-arg-value-spec {:as :first :value 42}))))

  (testing "map with :as and :ref"
    (is (= {:rename :handler :value-spec :target-fn :is-fn false :literal? false}
           (#'records/parse-arg-value-spec {:as :handler :ref :target-fn}))))

  (testing "map with :as and :type :fn"
    (is (= {:rename :callback :value-spec nil :is-fn true :literal? false}
           (#'records/parse-arg-value-spec {:as :callback :type :fn}))))

  (testing "map with :as, :value, and :type :fn — still a literal slot"
    (is (= {:rename :callback :value-spec :some-fn :is-fn true :literal? true}
           (#'records/parse-arg-value-spec {:as :callback :value :some-fn :type :fn}))))

  (testing "throws when :as is not a keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":as must be a keyword"
          (#'records/parse-arg-value-spec {:as "not-keyword"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":as must be a keyword"
          (#'records/parse-arg-value-spec {:as 123}))))

  (testing "map without :as is treated as literal value"
    ;; A map without :as key is NOT treated as a spec, just a literal map value
    (is (= {:rename nil :value-spec {:some "data"} :is-fn nil :literal? false}
           (#'records/parse-arg-value-spec {:some "data"})))))


;; === validate-fn-def! ===

(deftest validate-fn-def!-test
  (testing "accepts valid fn-def"
    (is (nil? (#'validation/validate-fn-def! {:name :my-fn :parent :base})))
    (is (nil? (#'validation/validate-fn-def! {:name :my-fn :parent :base :args {:a 1}}))))

  (testing "throws on missing name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :name"
          (#'validation/validate-fn-def! {:parent :base}))))

  (testing "throws on non-keyword name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (#'validation/validate-fn-def! {:name "string" :parent :base}))))

  (testing "throws on missing parent"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :parent"
          (#'validation/validate-fn-def! {:name :my-fn}))))

  (testing "throws on non-map args"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be a map"
          (#'validation/validate-fn-def! {:name :my-fn :parent :base :args [1 2]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be a map"
          (#'validation/validate-fn-def! {:name :my-fn :parent :base :args "bad"}))))

  (testing "accepts nil args (no args key)"
    (is (nil? (#'validation/validate-fn-def! {:name :my-fn :parent :base})))))


;; === validate-all-defs! ===

(deftest validate-all-defs!-test
  (testing "accepts valid definitions"
    (is (nil? (validation/validate-all-defs!
                [{:name :a :parent :base}
                 {:name :b :parent :base}]))))

  (testing "throws on non-sequential input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
          (validation/validate-all-defs! {:name :a :parent :base}))))

  (testing "throws on duplicate names"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
          (validation/validate-all-defs!
            [{:name :a :parent :base}
             {:name :a :parent :other}]))))

  (testing "reports multiple duplicates"
    (try
      (validation/validate-all-defs!
        [{:name :a :parent :base}
         {:name :b :parent :base}
         {:name :a :parent :other}
         {:name :b :parent :other}])
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [dups (:duplicates (ex-data e))]
          (is (= #{:a :b} (set dups)))))))

  (testing "accepts empty vector"
    (is (nil? (validation/validate-all-defs! []))))

  (testing "validates each fn-def"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :parent"
          (validation/validate-all-defs! [{:name :a}])))))


;; === topological-sort ===

(deftest topological-sort-unit-test
  (testing "sorts linear dependency chain"
    (let [fn-defs [{:name :a :parent :base :args {:x :b}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base}]
          sorted (deps/topological-sort fn-defs)
          names (mapv :name sorted)
          pos (into {} (map-indexed (fn [i n] [n i])) names)]
      (is (< (pos :c) (pos :b)))
      (is (< (pos :b) (pos :a)))))

  (testing "preserves order for independent fns"
    (let [fn-defs [{:name :x :parent :base}
                   {:name :y :parent :base}
                   {:name :z :parent :base}]
          sorted (deps/topological-sort fn-defs)]
      (is (= 3 (count sorted)))
      (is (= #{:x :y :z} (set (map :name sorted))))))

  (testing "handles diamond dependencies"
    (let [fn-defs [{:name :d :parent :base :args {:x :b :y :c}}
                   {:name :b :parent :base :args {:x :a}}
                   {:name :c :parent :base :args {:x :a}}
                   {:name :a :parent :base}]
          sorted (deps/topological-sort fn-defs)
          names (mapv :name sorted)
          pos (into {} (map-indexed (fn [i n] [n i])) names)]
      ;; a must come first, then b and c, then d
      (is (< (pos :a) (pos :b)))
      (is (< (pos :a) (pos :c)))
      (is (< (pos :b) (pos :d)))
      (is (< (pos :c) (pos :d)))))

  (testing "handles parent as dependency (parent in fn-names set)"
    (let [fn-defs [{:name :child :parent :parent-fn :args {:a 1}}
                   {:name :parent-fn :parent :base}]
          sorted (deps/topological-sort fn-defs)
          names (mapv :name sorted)
          pos (into {} (map-indexed (fn [i n] [n i])) names)]
      (is (< (pos :parent-fn) (pos :child)))))

  (testing "detects two-node cycle"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
          (deps/topological-sort
            [{:name :a :parent :base :args {:x :b}}
             {:name :b :parent :base :args {:x :a}}]))))

  (testing "cycle exception includes remaining nodes"
    (try
      (deps/topological-sort
        [{:name :a :parent :base :args {:x :b}}
         {:name :b :parent :base :args {:x :a}}])
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :fn-composition/circular-dependency (:type (ex-data e))))
        (is (= #{:a :b} (:remaining (ex-data e)))))))

  (testing "single element"
    (let [sorted (deps/topological-sort [{:name :solo :parent :base}])]
      (is (= [:solo] (mapv :name sorted))))))


;; === check-order-and-warn ===

(deftest check-order-and-warn-test
  (testing "does not warn when order is correct"
    (let [logged (atom [])
          fn-defs [{:name :a :parent :base} {:name :b :parent :base}]
          sorted fn-defs]
      (with-redefs [clojure.tools.logging/log*
                    (fn [_ level _ msg]
                      (swap! logged conj {:level level :message msg}))]
        (deps/check-order-and-warn fn-defs sorted))
      (is (empty? @logged))))

  (testing "warns when order differs"
    (let [logged (atom [])
          fn-defs [{:name :b :parent :base} {:name :a :parent :base}]
          sorted [{:name :a :parent :base} {:name :b :parent :base}]]
      (with-redefs [clojure.tools.logging/log*
                    (fn [_ level _ msg]
                      (swap! logged conj {:level level :message msg}))]
        (deps/check-order-and-warn fn-defs sorted))
      (is (= 1 (count @logged)))
      (is (= :warn (:level (first @logged)))))))


;; === preload-all-args ===

(deftest preload-all-args-empty-test
  (testing "returns empty indexes for empty fn-ids"
    (let [result (#'core/preload-all-args nil [])]
      (is (= {} (:by-fn result)))
      (is (= {} (:by-id result)))
      (is (= {} (:by-fn-source result))))))


;; === extract-dependencies ===

(deftest extract-dependencies-comprehensive-test
  (testing "includes parent when parent is in fn-names set"
    (let [fn-def {:name :child :parent :parent-fn :args {:a 1}}
          deps (#'deps/extract-dependencies fn-def #{:parent-fn :child})]
      (is (contains? deps :parent-fn))))

  (testing "does not include parent when parent is external"
    (let [fn-def {:name :child :parent :external-base :args {:a :some-fn}}
          deps (#'deps/extract-dependencies fn-def #{:child :some-fn})]
      (is (not (contains? deps :external-base)))
      (is (contains? deps :some-fn))))

  (testing "handles arg values that are maps (not fn refs)"
    (let [fn-def {:name :my-fn :parent :base :args {:a {:key "value"}}}
          deps (#'deps/extract-dependencies fn-def #{:my-fn})]
      ;; Maps are not fn refs, so no deps
      (is (empty? deps))))

  (testing "handles arg values that are vectors (not fn refs)"
    (let [fn-def {:name :my-fn :parent :base :args {:a [1 2 3]}}
          deps (#'deps/extract-dependencies fn-def #{:my-fn})]
      (is (empty? deps))))

  (testing "handles arg values that are numbers"
    (let [fn-def {:name :my-fn :parent :base :args {:a 42 :b 3.14}}
          deps (#'deps/extract-dependencies fn-def #{:my-fn})]
      (is (empty? deps))))

  (testing "handles mixed arg values"
    (let [fn-def {:name :my-fn :parent :base
                  :args {:a :dep-fn    ; fn ref in set
                         :b :external  ; fn ref not in set
                         :c 42         ; literal
                         :d "string"}} ; literal
          deps (#'deps/extract-dependencies fn-def #{:my-fn :dep-fn})]
      (is (= #{:dep-fn} deps)))))


;; === build-dependency-graph ===

(deftest build-dependency-graph-comprehensive-test
  (testing "empty input"
    (is (= {} (#'deps/build-dependency-graph []))))

  (testing "single fn with no deps"
    (is (= {:solo #{}} (#'deps/build-dependency-graph [{:name :solo :parent :base}]))))

  (testing "complex graph"
    (let [fn-defs [{:name :a :parent :base}
                   {:name :b :parent :a :args {:x :a}}
                   {:name :c :parent :base :args {:x :a :y :b}}]
          graph (#'deps/build-dependency-graph fn-defs)]
      (is (= #{} (get graph :a)))
      (is (= #{:a} (get graph :b)))
      (is (= #{:a :b} (get graph :c))))))


;; === get-parent-arg-cached ===

(deftest get-parent-arg-cached-test
  (testing "finds arg by name on first fn"
    (let [fn-id (random-uuid)
          arg {:id (random-uuid) :fn-id fn-id :name "x"}
          fn-cache {fn-id {:id fn-id :parent-ids nil}}
          args-data {:by-fn {fn-id [arg]}
                     :by-id {(:id arg) arg}}]
      (is (= arg (#'records/get-parent-arg-cached fn-cache args-data [fn-id] :x)))))

  (testing "follows parent-id chain to find arg"
    (let [grandparent-id (random-uuid)
          parent-id (random-uuid)
          arg {:id (random-uuid) :fn-id grandparent-id :name "deep-arg"}
          fn-cache {parent-id {:id parent-id :parent-ids [grandparent-id]}
                    grandparent-id {:id grandparent-id :parent-ids nil}}
          args-data {:by-fn {parent-id []
                             grandparent-id [arg]}
                     :by-id {(:id arg) arg}}]
      (is (= arg (#'records/get-parent-arg-cached fn-cache args-data [parent-id] :deep-arg)))))

  (testing "throws when arg not found in chain"
    (let [fn-id (random-uuid)
          fn-cache {fn-id {:id fn-id :parent-ids nil}}
          args-data {:by-fn {fn-id []}
                     :by-id {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Argument not found"
            (#'records/get-parent-arg-cached fn-cache args-data [fn-id] :nonexistent)))))

  (testing "throws when chain too deep"
    (binding [sp/*max-graph-iterations* 2]
      (let [ids (repeatedly 5 random-uuid)
            fn-cache (into {} (map-indexed (fn [i id]
                                             [id {:id id :parent-ids [(get (vec ids) (inc i))]}])
                                           (butlast ids)))
            args-data {:by-fn {} :by-id {}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too deep"
              (#'records/get-parent-arg-cached fn-cache args-data [(first ids)] :x)))))))


;; === find-available-arg ===

(deftest find-available-arg-test
  (testing "finds arg in direct parent chain first"
    (let [parent-id (random-uuid)
          arg {:id (random-uuid) :fn-id parent-id :name "x"}
          fn-cache {parent-id {:id parent-id :parent-ids nil}}
          args-data {:by-fn {parent-id [arg]}
                     :by-id {(:id arg) arg}}]
      (is (= arg (#'records/find-available-arg fn-cache args-data [parent-id] :x)))))

  (testing "finds arg in propagated free args when not in parent chain"
    (let [parent-id (random-uuid)
          ref-fn-id (random-uuid)
          ;; parent has bound arg referencing ref-fn
          bound-arg {:id (random-uuid) :fn-id parent-id :value nil :ref-id ref-fn-id :name "f"}
          ;; ref-fn has free arg named "target"
          free-arg {:id (random-uuid) :fn-id ref-fn-id :value nil :ref-id nil :name "target"}
          fn-cache {parent-id {:id parent-id :parent-ids nil}
                    ref-fn-id {:id ref-fn-id :parent-ids nil}}
          args-data {:by-fn {parent-id [bound-arg]
                             ref-fn-id [free-arg]}
                     :by-id {(:id bound-arg) bound-arg
                             (:id free-arg) free-arg}}]
      (is (= free-arg (#'records/find-available-arg fn-cache args-data [parent-id] :target)))))

  (testing "throws when arg not found anywhere"
    (let [parent-id (random-uuid)
          fn-cache {parent-id {:id parent-id :parent-ids nil}}
          args-data {:by-fn {parent-id []}
                     :by-id {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found in available args"
            (#'records/find-available-arg fn-cache args-data [parent-id] :nonexistent))))))


;; === prepare-propagated-arg-record ===

(deftest prepare-propagated-arg-record-test
  (testing "creates new propagated arg record"
    (let [fn-id (random-uuid)
          parent-arg {:id (random-uuid) :type :int :is-fn false}
          args-data {:by-fn-source {}}
          result (records/prepare-propagated-arg-record args-data fn-id parent-arg)]
      (is (some? (:new result)))
      (is (= fn-id (:fn-id (:new result))))
      (is (= (:id parent-arg) (:source-id (:new result))))
      (is (nil? (:value (:new result))))
      (is (nil? (:ref-id (:new result))))
      (is (= :int (:type (:new result))))))

  (testing "returns nil when propagated arg already exists"
    (let [fn-id (random-uuid)
          parent-arg {:id (random-uuid) :type :int :is-fn false}
          existing {:id (random-uuid) :fn-id fn-id :source-id (:id parent-arg)}
          args-data {:by-fn-source {[fn-id (:id parent-arg)] existing}}
          result (records/prepare-propagated-arg-record args-data fn-id parent-arg)]
      (is (nil? result)))))


;; === prepare-fn-record ===

(deftest prepare-fn-record-test
  (testing "creates new fn when not in cache"
    (let [parent-id (random-uuid)
          fn-name-cache {}
          fn-id-cache {parent-id {:id parent-id}}
          created-fns {}
          fn-def {:name :new-fn :parent :base-fn}
          ;; Mock registry to return parent-id for :base-fn
          result (with-redefs [registry/fn-uuid
                               (fn [n] (when (= n :base-fn) parent-id))]
                   (records/prepare-fn-record fn-name-cache fn-id-cache created-fns fn-def {}))]
      (is (some? (:new result)))
      (is (= "new-fn" (:name (:new result))))
      (is (= [parent-id] (:parent-ids (:new result))))))

  (testing "returns existing fn when in name cache"
    (let [existing-fn {:id (random-uuid) :name "existing-fn"}
          fn-name-cache {"existing-fn" existing-fn}
          fn-def {:name :existing-fn :parent :base}
          result (records/prepare-fn-record fn-name-cache {} {} fn-def {})]
      (is (= existing-fn (:existing result)))))

  (testing "local fn (underscore prefix) is always created fresh"
    (let [parent-id (random-uuid)
          fn-name-cache {"_local" {:id (random-uuid) :name "_local"}}
          fn-id-cache {parent-id {:id parent-id}}
          fn-def {:name :_local :parent :base-fn}
          result (with-redefs [registry/fn-uuid
                               (fn [n] (when (= n :base-fn) parent-id))]
                   (records/prepare-fn-record fn-name-cache fn-id-cache {} fn-def {}))]
      ;; Should create new, not return existing
      (is (some? (:new result)))
      ;; Local fns get name=nil in DB
      (is (nil? (:name (:new result))))))

  (testing "resolves parent from created-fns"
    (let [parent-id (random-uuid)
          fn-def {:name :child :parent :parent-fn}
          result (with-redefs [registry/fn-uuid
                               (fn [_] nil)]
                   (records/prepare-fn-record {} {} {:parent-fn parent-id} fn-def {}))]
      (is (some? (:new result)))
      (is (= [parent-id] (:parent-ids (:new result))))))

  (testing "throws when parent not found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
          (with-redefs [registry/fn-uuid
                        (fn [_] nil)]
            (records/prepare-fn-record {} {} {} {:name :orphan :parent :missing} {}))))))


;; === resolve-fn-id-cached ===

(deftest resolve-fn-id-cached-test
  (testing "resolves from created-fns first"
    (let [id (random-uuid)]
      (is (= id (#'records/resolve-fn-id-cached {} {:my-fn id} :my-fn)))))

  (testing "resolves from fn-name-cache"
    (let [id (random-uuid)]
      (is (= id (#'records/resolve-fn-id-cached {"my-fn" {:id id}} {} :my-fn)))))

  (testing "prefers created-fns over fn-name-cache"
    (let [created-id (random-uuid)
          cached-id (random-uuid)]
      (is (= created-id (#'records/resolve-fn-id-cached
                         {"my-fn" {:id cached-id}}
                         {:my-fn created-id}
                         :my-fn)))))

  (testing "throws when not found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
          (#'records/resolve-fn-id-cached {} {} :missing)))))


;; === resolve-parent-fn-id-cached ===

(deftest resolve-parent-fn-id-cached-test
  (testing "resolves from created-fns first"
    (let [id (random-uuid)]
      (is (= id (records/resolve-parent-fn-id-cached {} {} {:my-parent id} :my-parent)))))

  (testing "resolves from registry via fn-id-cache"
    (let [base-id (random-uuid)]
      (with-redefs [registry/fn-uuid
                    (fn [n] (when (= n :base) base-id))]
        (is (= base-id (records/resolve-parent-fn-id-cached
                         {} {base-id {:id base-id}} {} :base))))))

  (testing "resolves from fn-name-cache"
    (let [id (random-uuid)]
      (is (= id (records/resolve-parent-fn-id-cached
                  {"my-parent" {:id id}} {} {} :my-parent)))))

  (testing "throws when not found"
    (with-redefs [registry/fn-uuid
                  (fn [_] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (records/resolve-parent-fn-id-cached {} {} {} :missing))))))


;; === resolve-sequence-item ===
;;
;; Converts one element of a sequence literal (`[:item-1 :item-2 42 ...]`)
;; into a `{:value … :ref-id …}` spec. Covers: UUID pass-through, keyword
;; that names an already-created fn (ref), keyword that does NOT match a
;; known fn (literal keyword), keyword with bad identifier (literal),
;; map with explicit `:ref` override, map with explicit `:value`
;; override, everything else (number, string, …) as a literal.

(deftest resolve-sequence-item-uuid
  (let [id (random-uuid)]
    (is (= {:value nil :ref-id id :name nil}
           (#'records/resolve-sequence-item {} {} id)))))


(deftest resolve-sequence-item-keyword-matching-created
  (testing "keyword matching a freshly-created fn resolves to a ref"
    (let [id (random-uuid)]
      (is (= {:value nil :ref-id id :name nil}
             (#'records/resolve-sequence-item {} {:my-fn id} :my-fn))))))


(deftest resolve-sequence-item-keyword-matching-registry
  (testing "keyword matching an existing fn in the name-cache resolves"
    (let [id (random-uuid)]
      (is (= {:value nil :ref-id id :name nil}
             (#'records/resolve-sequence-item {"my-fn" {:id id}} {} :my-fn))))))


(deftest resolve-sequence-item-keyword-unknown-fn-is-literal
  (testing "keyword that doesn't name any known fn stays as a literal value"
    (is (= {:value :not-a-fn :ref-id nil :name nil}
           (#'records/resolve-sequence-item {} {} :not-a-fn)))))


(deftest resolve-sequence-item-keyword-invalid-identifier
  (testing "keyword with invalid identifier (e.g. contains `/`) stays literal"
    ;; Namespaced keywords produce names with `/` which `valid-identifier?`
    ;; rejects. The item is still valid as a literal keyword value.
    (is (= {:value :ns/kw :ref-id nil :name nil}
           (#'records/resolve-sequence-item {} {} :ns/kw)))))


(deftest resolve-sequence-item-map-with-ref-override
  (let [id (random-uuid)]
    (is (= {:value nil :ref-id id :name nil}
           (#'records/resolve-sequence-item {"target" {:id id}} {} {:ref :target})))))


(deftest resolve-sequence-item-map-with-value-override
  (testing "explicit `:value` bypasses fn-ref resolution"
    (is (= {:value :keyword-as-literal :ref-id nil :name nil}
           (#'records/resolve-sequence-item {} {} {:value :keyword-as-literal})))
    (is (= {:value [1 2 3] :ref-id nil :name nil}
           (#'records/resolve-sequence-item {} {} {:value [1 2 3]})))))


(deftest resolve-sequence-item-as-only-named-free-slot
  (testing "{:as :name} alone produces a named free slot — no value, no ref"
    (is (= {:value nil :ref-id nil :name "defaults"}
           (#'records/resolve-sequence-item {} {} {:as :defaults}))))
  (testing "{:as :name :ref :fn} carries both ref-id and name"
    (let [id (random-uuid)]
      (is (= {:value nil :ref-id id :name "tagged"}
             (#'records/resolve-sequence-item {"f" {:id id}} {} {:ref :f :as :tagged}))))))


(deftest resolve-sequence-item-literals
  (testing "non-keyword, non-map, non-uuid values pass through as literal :value"
    (is (= {:value 42 :ref-id nil :name nil}
           (#'records/resolve-sequence-item {} {} 42)))
    (is (= {:value "string" :ref-id nil :name nil}
           (#'records/resolve-sequence-item {} {} "string")))
    (is (= {:value [1 2] :ref-id nil :name nil}
           (#'records/resolve-sequence-item {} {} [1 2])))))


;; === walk-anchor-chain-ids ===
;;
;; Follows the `:next-arg-id` pointer from an anchor and returns the
;; ordered item-ids. Used at sync-time to reap orphaned items.

(deftest walk-anchor-chain-ids-empty
  (testing "anchor with no next-arg-id returns []"
    (is (= [] (#'records/walk-anchor-chain-ids {} {:id :anchor})))))


(deftest walk-anchor-chain-ids-single-item
  (let [item-id (random-uuid)
        args-by-id {item-id {:id item-id :next-arg-id nil}}]
    (is (= [item-id] (#'records/walk-anchor-chain-ids args-by-id {:next-arg-id item-id})))))


(deftest walk-anchor-chain-ids-multiple-items
  (let [a (random-uuid), b (random-uuid), c (random-uuid)
        args-by-id {a {:id a :next-arg-id b}
                    b {:id b :next-arg-id c}
                    c {:id c :next-arg-id nil}}]
    (is (= [a b c] (#'records/walk-anchor-chain-ids args-by-id {:next-arg-id a})))))


(deftest walk-anchor-chain-ids-detects-overlong-chain
  (testing "chains exceeding 10K hops throw — guards against a corrupted cycle"
    ;; Build a cycle: each item points at the next, and the last loops
    ;; back. Walk will detect > 10000 depth and throw.
    (let [ids (vec (repeatedly 5 random-uuid))
          args-by-id (into {} (map-indexed
                                (fn [i id]
                                  [id {:id id
                                       :next-arg-id (nth ids (mod (inc i) 5))}]))
                           ids)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Sequence chain exceeded maximum length"
            (#'records/walk-anchor-chain-ids args-by-id
                                             {:id :anchor :next-arg-id (first ids)}))))))


;; === parse-arg-value-spec — additional shapes not in the existing test ===

(deftest parse-arg-value-spec-map-with-ref
  (testing "bare {:ref :fn-name} → ref without rename"
    (is (= {:rename nil :value-spec :target :is-fn false :literal? false}
           (#'records/parse-arg-value-spec {:ref :target})))))


(deftest parse-arg-value-spec-map-with-value
  (testing "bare {:value …} → literal without rename, bypasses fn-ref resolution"
    (is (= {:rename nil :value-spec :kw-as-value :is-fn nil :literal? true}
           (#'records/parse-arg-value-spec {:value :kw-as-value})))))


(deftest parse-arg-value-spec-map-as-with-type-fn
  (testing "{:as :rename :type :fn} sets is-fn true"
    (is (= {:rename :r :value-spec nil :is-fn true :literal? false}
           (#'records/parse-arg-value-spec {:as :r :type :fn})))))


(deftest parse-arg-value-spec-as-rejects-non-keyword
  (testing ":as with non-keyword value throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":as must be a keyword"
          (#'records/parse-arg-value-spec {:as "string"})))))


;; =============================================================================
;; prepare-sequence-arg-chain — anchor + linked item records
;; =============================================================================
;;
;; Takes a parent arg (template with :type :sequence) and a vector of
;; items, and produces an `{:new-chain [...] :delete-items […] :source-id
;; <template-id>}` map. Items become a doubly-linked list rooted at the
;; anchor.

(deftest prepare-sequence-arg-chain-empty-items
  (testing "no items — anchor only, no delete-items"
    (let [template-id (random-uuid)
          fn-id (random-uuid)
          parent-arg {:id template-id :type :sequence :of :int}
          args-data {:by-id {} :by-fn-source {}}
          {:keys [new-chain delete-items source-id]}
          (#'records/prepare-sequence-arg-chain {} {} args-data fn-id parent-arg [])]
      (is (= template-id source-id))
      (is (= [] delete-items))
      (is (= 1 (count new-chain)) "anchor only")
      (let [anchor (first new-chain)]
        (is (= fn-id (:fn-id anchor)))
        (is (= template-id (:source-id anchor)))
        (is (= :sequence (:type anchor)))
        (is (nil? (:next-arg-id anchor)) "tail=nil for empty chain")))))


(deftest prepare-sequence-arg-chain-multi-item-linking
  (testing "chain of item records linked via next-arg-id / prev-arg-id"
    (let [template-id (random-uuid)
          fn-id (random-uuid)
          parent-arg {:id template-id :type :sequence :of :any}
          args-data {:by-id {} :by-fn-source {}}
          {:keys [new-chain]}
          (#'records/prepare-sequence-arg-chain {} {} args-data fn-id parent-arg
                                                [1 2 3])
          [anchor i1 i2 i3] new-chain]
      (is (= 4 (count new-chain)))
      (is (= :sequence (:type anchor)))
      (is (= (:id i1) (:next-arg-id anchor)))
      (is (= (:id anchor) (:prev-arg-id i1)) "head.prev → anchor")
      (is (= (:id i2) (:next-arg-id i1)))
      (is (= (:id i1) (:prev-arg-id i2)))
      (is (= (:id i3) (:next-arg-id i2)))
      (is (nil? (:next-arg-id i3)) "tail.next=nil")
      (is (= 1 (:value i1)))
      (is (= 2 (:value i2)))
      (is (= 3 (:value i3)))
      (is (every? #(= :any (:type %)) [i1 i2 i3])
          ":of of template propagates as element :type"))))


(deftest prepare-sequence-arg-chain-reuses-existing-anchor-id
  (testing "existing anchor's id is preserved; old items go to :delete-items"
    (let [template-id (random-uuid)
          fn-id (random-uuid)
          old-anchor-id (random-uuid)
          old-item-a (random-uuid)
          old-item-b (random-uuid)
          parent-arg {:id template-id :type :sequence}
          args-data {:by-id {old-item-a {:id old-item-a :next-arg-id old-item-b}
                             old-item-b {:id old-item-b :next-arg-id nil}}
                     :by-fn-source {[fn-id template-id]
                                    {:id old-anchor-id :next-arg-id old-item-a}}}
          {:keys [new-chain delete-items]}
          (#'records/prepare-sequence-arg-chain {} {} args-data fn-id parent-arg
                                                [:new-val])]
      (is (= old-anchor-id (:id (first new-chain))) "reuses old anchor id")
      (is (= [old-item-a old-item-b] delete-items)
          "sweep listed for reap"))))


;; =============================================================================
;; prepare-arg-record — top-level dispatch + error paths
;; =============================================================================

(deftest prepare-arg-record-throws-on-nil-fn-id
  (testing "nil fn-id aborts immediately"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"fn-id cannot be nil"
          (records/prepare-arg-record {} {} {} {} nil [] :foo 1)))))


(deftest prepare-arg-record-sequence-arg-requires-vector
  (testing "parent arg :type :sequence + non-vector value throws"
    (let [tmpl-id (random-uuid)
          fn-id (random-uuid)
          parent-arg {:id tmpl-id :name "items" :type :sequence}
          args-data {:by-id {tmpl-id parent-arg}
                     :by-fn {(random-uuid) [parent-arg]}
                     :by-fn-source {}}
          fn-cache {fn-id {:id fn-id :parent-ids []}}]
      (with-redefs [records/find-available-arg (fn [_ _ _ _] parent-arg)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Sequence arg ':items' requires a vector"
              (records/prepare-arg-record fn-cache args-data {} {} fn-id [] :items
                                          "not-a-vector")))))))


(deftest prepare-arg-record-dispatches-sequence-to-chain
  (testing "vector value on :sequence parent → chain record"
    (let [tmpl-id (random-uuid)
          fn-id (random-uuid)
          parent-arg {:id tmpl-id :name "items" :type :sequence :of :int}
          args-data {:by-id {tmpl-id parent-arg} :by-fn {} :by-fn-source {}}]
      (with-redefs [records/find-available-arg (fn [_ _ _ _] parent-arg)]
        (let [result (records/prepare-arg-record {} args-data {} {} fn-id [] :items
                                                 [10 20])]
          (is (contains? result :new-chain))
          (is (= 3 (count (:new-chain result))))
          (is (= tmpl-id (:source-id result))))))))


(deftest prepare-arg-record-sequence-arg-rename
  (testing "bare {:as :name} on :sequence parent → empty anchor with rename"
    (let [tmpl-id (random-uuid)
          fn-id (random-uuid)
          parent-arg {:id tmpl-id :name "items" :type :sequence :of :any}
          args-data {:by-id {tmpl-id parent-arg} :by-fn {} :by-fn-source {}}]
      (with-redefs [records/find-available-arg (fn [_ _ _ _] parent-arg)]
        (let [result (records/prepare-arg-record {} args-data {} {} fn-id [] :items
                                                 {:as :buttons})
              [anchor] (:new-chain result)]
          (is (= 1 (count (:new-chain result))))
          (is (= :sequence (:type anchor)))
          (is (= "buttons" (:name anchor)))
          (is (nil? (:next-arg-id anchor)))
          (is (= tmpl-id (:source-id anchor)))))))

  (testing "{:as :name :value …} (with explicit :value) is NOT a whole-arg rename"
    ;; Falls through to the error branch — that combination isn't supported.
    (let [tmpl-id (random-uuid)
          fn-id (random-uuid)
          parent-arg {:id tmpl-id :name "items" :type :sequence :of :any}
          args-data {:by-id {tmpl-id parent-arg} :by-fn {} :by-fn-source {}}]
      (with-redefs [records/find-available-arg (fn [_ _ _ _] parent-arg)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Sequence arg ':items' requires a vector"
              (records/prepare-arg-record {} args-data {} {} fn-id [] :items
                                          {:as :buttons :value 5})))))))


;; =============================================================================
;; prepare-scalar-arg-record — override-forbidden error path
;; =============================================================================

(deftest prepare-scalar-arg-record-rejects-override-of-bound-parent
  (testing "child cannot override parent's already-bound :value"
    (let [parent-arg-id (random-uuid)
          parent-arg {:id parent-arg-id :name "x" :value 42 :ref-id nil}
          args-data {:by-id {parent-arg-id parent-arg}
                     :by-fn-source {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot override already-bound argument"
            (#'records/prepare-scalar-arg-record args-data {} {} (random-uuid)
                                                 parent-arg :x 7))))))
