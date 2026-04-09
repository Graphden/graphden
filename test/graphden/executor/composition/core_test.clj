(ns graphden.executor.composition.core-test
  "Unit tests for composition core functions.
   Tests pure/internal functions that don't require storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging]
    [graphden.executor.composition.core :as core]
    [graphden.executor.registry.interface :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === valid-identifier? ===

(deftest valid-identifier?-test
  (testing "accepts valid identifiers"
    (is (#'core/valid-identifier? "hello"))
    (is (#'core/valid-identifier? "my-fn"))
    (is (#'core/valid-identifier? "_private"))
    (is (#'core/valid-identifier? "fn123"))
    (is (#'core/valid-identifier? "a"))
    (is (#'core/valid-identifier? "A-B-C"))
    (is (#'core/valid-identifier? "my_fn_name")))

  (testing "rejects identifiers starting with digit"
    (is (not (#'core/valid-identifier? "123abc")))
    (is (not (#'core/valid-identifier? "0test"))))

  (testing "rejects identifiers with special chars"
    (is (not (#'core/valid-identifier? "hello world")))
    (is (not (#'core/valid-identifier? "fn@name")))
    (is (not (#'core/valid-identifier? "a.b")))
    (is (not (#'core/valid-identifier? "a/b")))
    (is (not (#'core/valid-identifier? ">")))
    (is (not (#'core/valid-identifier? "+"))))

  (testing "rejects empty and nil"
    (is (not (#'core/valid-identifier? "")))
    (is (not (#'core/valid-identifier? nil))))

  (testing "rejects non-string input"
    (is (not (#'core/valid-identifier? 123)))
    (is (not (#'core/valid-identifier? true)))
    (is (not (#'core/valid-identifier? :keyword)))))


;; === local-fn-name? ===

(deftest local-fn-name?-test
  (testing "returns true for underscore-prefixed keywords"
    (is (#'core/local-fn-name? :_local))
    (is (#'core/local-fn-name? :_my-local-fn)))

  (testing "returns true for underscore-prefixed strings"
    (is (#'core/local-fn-name? "_local")))

  (testing "returns false for non-underscore names"
    (is (not (#'core/local-fn-name? :my-fn)))
    (is (not (#'core/local-fn-name? :hello)))
    (is (not (#'core/local-fn-name? "normal"))))

  (testing "returns nil/falsy for nil"
    (is (not (#'core/local-fn-name? nil)))))


;; === parse-fn-ref ===

(deftest parse-fn-ref-test
  (testing "returns keyword for valid identifiers"
    (is (= :my-fn (#'core/parse-fn-ref :my-fn)))
    (is (= :handler (#'core/parse-fn-ref :handler)))
    (is (= :my-fn-123 (#'core/parse-fn-ref :my-fn-123))))

  (testing "returns nil for non-keyword values"
    (is (nil? (#'core/parse-fn-ref "string")))
    (is (nil? (#'core/parse-fn-ref 42)))
    (is (nil? (#'core/parse-fn-ref nil)))
    (is (nil? (#'core/parse-fn-ref [1 2 3])))
    (is (nil? (#'core/parse-fn-ref {:a 1}))))

  (testing "returns nil for keywords with invalid names"
    (is (nil? (#'core/parse-fn-ref :>)))
    (is (nil? (#'core/parse-fn-ref :+)))
    (is (nil? (#'core/parse-fn-ref :123-starts-with-digit)))))


;; === free-arg? ===

(deftest free-arg?-test
  (testing "returns true when both value and ref-id are nil"
    (is (#'core/free-arg? {:value nil :ref-id nil}))
    (is (#'core/free-arg? {:id (random-uuid) :fn-id (random-uuid)}))
    (is (#'core/free-arg? {})))

  (testing "returns false when value is set"
    (is (not (#'core/free-arg? {:value 42 :ref-id nil})))
    (is (not (#'core/free-arg? {:value "hello"})))
    (is (not (#'core/free-arg? {:value false}))))

  (testing "returns false when ref-id is set"
    (is (not (#'core/free-arg? {:value nil :ref-id (random-uuid)})))
    (is (not (#'core/free-arg? {:ref-id (random-uuid)})))))


;; === partition-args-by-freedom ===

(deftest partition-args-by-freedom-test
  (testing "partitions into free and bound args"
    (let [free1 {:id :a :value nil :ref-id nil}
          free2 {:id :b}
          bound1 {:id :c :value 42}
          bound2 {:id :d :ref-id (random-uuid)}
          result (#'core/partition-args-by-freedom [free1 free2 bound1 bound2])]
      (is (= [free1 free2] (:free-args result)))
      (is (= [bound1 bound2] (:bound-args result)))))

  (testing "handles empty input"
    (let [result (#'core/partition-args-by-freedom [])]
      (is (= [] (:free-args result)))
      (is (= [] (:bound-args result)))))

  (testing "handles all free"
    (let [result (#'core/partition-args-by-freedom [{:id :a} {:id :b}])]
      (is (= 2 (count (:free-args result))))
      (is (zero? (count (:bound-args result))))))

  (testing "handles all bound"
    (let [result (#'core/partition-args-by-freedom [{:value 1} {:ref-id (random-uuid)}])]
      (is (zero? (count (:free-args result))))
      (is (= 2 (count (:bound-args result)))))))


;; === resolve-arg-name-cached ===

(deftest resolve-arg-name-cached-test
  (testing "returns name directly if present"
    (let [arg {:id :a :name "my-arg"}]
      (is (= "my-arg" (#'core/resolve-arg-name-cached {} arg 0)))))

  (testing "follows source-id chain to find name"
    (let [root-id (random-uuid)
          mid-id (random-uuid)
          leaf-id (random-uuid)
          args-by-id {root-id {:id root-id :name "root-name"}
                      mid-id {:id mid-id :source-id root-id}
                      leaf-id {:id leaf-id :source-id mid-id}}]
      (is (= "root-name" (#'core/resolve-arg-name-cached args-by-id
                                                         (get args-by-id leaf-id)
                                                         0)))))

  (testing "returns nil when source-id chain leads nowhere"
    (let [orphan-id (random-uuid)
          args-by-id {orphan-id {:id orphan-id :source-id (random-uuid)}}]
      (is (nil? (#'core/resolve-arg-name-cached args-by-id
                                                (get args-by-id orphan-id)
                                                0)))))

  (testing "returns nil for arg with no name and no source-id"
    (is (nil? (#'core/resolve-arg-name-cached {} {:id :x} 0))))

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
              (#'core/resolve-arg-name-cached args-by-id
                                              (get args-by-id id-a)
                                              0)))))))


;; === collect-source-id-chain ===

(deftest collect-source-id-chain-test
  (testing "collects single id when no source-id"
    (let [id (random-uuid)
          args-by-id {id {:id id}}
          result (#'core/collect-source-id-chain args-by-id id)]
      (is (contains? result id))
      (is (= 1 (count result)))))

  (testing "collects full chain"
    (let [a (random-uuid)
          b (random-uuid)
          c (random-uuid)
          args-by-id {a {:id a :source-id b}
                      b {:id b :source-id c}
                      c {:id c}}
          result (#'core/collect-source-id-chain args-by-id a)]
      (is (= #{a b c} result))))

  (testing "handles nil arg-id"
    (is (= #{} (#'core/collect-source-id-chain {} nil))))

  (testing "handles missing arg in index"
    (let [id (random-uuid)
          result (#'core/collect-source-id-chain {} id)]
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
          result (#'core/collect-free-args-from-fn {} args-data fn-id #{} 0)]
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
          result (#'core/collect-free-args-from-fn {} args-data fn-a #{} 0)]
      (is (= [free-b] result))))

  (testing "handles cycle in visited-fns"
    (let [fn-id (random-uuid)
          result (#'core/collect-free-args-from-fn {} {:by-fn {}} fn-id #{fn-id} 0)]
      (is (= [] result))))

  (testing "returns empty for fn with no args"
    (let [fn-id (random-uuid)
          result (#'core/collect-free-args-from-fn {} {:by-fn {}} fn-id #{} 0)]
      (is (= [] result))))

  (testing "throws when chain too deep"
    (binding [sp/*max-graph-iterations* 2]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too deep"
            (#'core/collect-free-args-from-fn {} {:by-fn {}} (random-uuid) #{} 10))))))


;; === collect-parent-free-args ===

(deftest collect-parent-free-args-test
  (testing "collects free args from parent"
    (let [parent-id (random-uuid)
          free-arg {:id (random-uuid) :fn-id parent-id :value nil :ref-id nil}
          args-data {:by-fn {parent-id [free-arg]}
                     :by-id {(:id free-arg) free-arg}}
          result (#'core/collect-parent-free-args {} args-data parent-id 0)]
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
          result (#'core/collect-parent-free-args {} args-data parent-id 0)]
      (is (= [ref-free] result))))

  (testing "throws when depth exceeded"
    (binding [sp/*max-graph-iterations* 2]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too deep"
            (#'core/collect-parent-free-args {} {:by-fn {}} (random-uuid) 10))))))


;; === parse-arg-value-spec ===

(deftest parse-arg-value-spec-test
  (testing "simple values - no rename"
    (is (= {:rename nil :value-spec 42 :is-fn nil}
           (#'core/parse-arg-value-spec 42)))
    (is (= {:rename nil :value-spec "hello" :is-fn nil}
           (#'core/parse-arg-value-spec "hello")))
    (is (= {:rename nil :value-spec :my-fn :is-fn nil}
           (#'core/parse-arg-value-spec :my-fn)))
    (is (= {:rename nil :value-spec nil :is-fn nil}
           (#'core/parse-arg-value-spec nil))))

  (testing "map with :as rename only"
    (is (= {:rename :new-name :value-spec nil :is-fn false}
           (#'core/parse-arg-value-spec {:as :new-name}))))

  (testing "map with :as and :value"
    (is (= {:rename :first :value-spec 42 :is-fn false}
           (#'core/parse-arg-value-spec {:as :first :value 42}))))

  (testing "map with :as and :ref"
    (is (= {:rename :handler :value-spec :target-fn :is-fn false}
           (#'core/parse-arg-value-spec {:as :handler :ref :target-fn}))))

  (testing "map with :as and :type :fn"
    (is (= {:rename :callback :value-spec nil :is-fn true}
           (#'core/parse-arg-value-spec {:as :callback :type :fn}))))

  (testing "map with :as, :value, and :type :fn"
    (is (= {:rename :callback :value-spec :some-fn :is-fn true}
           (#'core/parse-arg-value-spec {:as :callback :value :some-fn :type :fn}))))

  (testing "throws when :as is not a keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":as must be a keyword"
          (#'core/parse-arg-value-spec {:as "not-keyword"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":as must be a keyword"
          (#'core/parse-arg-value-spec {:as 123}))))

  (testing "map without :as is treated as literal value"
    ;; A map without :as key is NOT treated as a spec, just a literal map value
    (is (= {:rename nil :value-spec {:some "data"} :is-fn nil}
           (#'core/parse-arg-value-spec {:some "data"})))))


;; === validate-fn-def! ===

(deftest validate-fn-def!-test
  (testing "accepts valid fn-def"
    (is (nil? (#'core/validate-fn-def! {:name :my-fn :parent :base})))
    (is (nil? (#'core/validate-fn-def! {:name :my-fn :parent :base :args {:a 1}}))))

  (testing "throws on missing name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :name"
          (#'core/validate-fn-def! {:parent :base}))))

  (testing "throws on non-keyword name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (#'core/validate-fn-def! {:name "string" :parent :base}))))

  (testing "throws on missing parent"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :parent"
          (#'core/validate-fn-def! {:name :my-fn}))))

  (testing "throws on non-map args"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be a map"
          (#'core/validate-fn-def! {:name :my-fn :parent :base :args [1 2]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be a map"
          (#'core/validate-fn-def! {:name :my-fn :parent :base :args "bad"}))))

  (testing "accepts nil args (no args key)"
    (is (nil? (#'core/validate-fn-def! {:name :my-fn :parent :base})))))


;; === validate-all-defs! ===

(deftest validate-all-defs!-test
  (testing "accepts valid definitions"
    (is (nil? (#'core/validate-all-defs!
               [{:name :a :parent :base}
                {:name :b :parent :base}]))))

  (testing "throws on non-sequential input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
          (#'core/validate-all-defs! {:name :a :parent :base}))))

  (testing "throws on duplicate names"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
          (#'core/validate-all-defs!
           [{:name :a :parent :base}
            {:name :a :parent :other}]))))

  (testing "reports multiple duplicates"
    (try
      (#'core/validate-all-defs!
       [{:name :a :parent :base}
        {:name :b :parent :base}
        {:name :a :parent :other}
        {:name :b :parent :other}])
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [dups (:duplicates (ex-data e))]
          (is (= #{:a :b} (set dups)))))))

  (testing "accepts empty vector"
    (is (nil? (#'core/validate-all-defs! []))))

  (testing "validates each fn-def"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :parent"
          (#'core/validate-all-defs! [{:name :a}])))))


;; === topological-sort ===

(deftest topological-sort-unit-test
  (testing "sorts linear dependency chain"
    (let [fn-defs [{:name :a :parent :base :args {:x :b}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base}]
          sorted (#'core/topological-sort fn-defs)
          names (mapv :name sorted)
          pos (into {} (map-indexed (fn [i n] [n i])) names)]
      (is (< (pos :c) (pos :b)))
      (is (< (pos :b) (pos :a)))))

  (testing "preserves order for independent fns"
    (let [fn-defs [{:name :x :parent :base}
                   {:name :y :parent :base}
                   {:name :z :parent :base}]
          sorted (#'core/topological-sort fn-defs)]
      (is (= 3 (count sorted)))
      (is (= #{:x :y :z} (set (map :name sorted))))))

  (testing "handles diamond dependencies"
    (let [fn-defs [{:name :d :parent :base :args {:x :b :y :c}}
                   {:name :b :parent :base :args {:x :a}}
                   {:name :c :parent :base :args {:x :a}}
                   {:name :a :parent :base}]
          sorted (#'core/topological-sort fn-defs)
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
          sorted (#'core/topological-sort fn-defs)
          names (mapv :name sorted)
          pos (into {} (map-indexed (fn [i n] [n i])) names)]
      (is (< (pos :parent-fn) (pos :child)))))

  (testing "detects two-node cycle"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
          (#'core/topological-sort
           [{:name :a :parent :base :args {:x :b}}
            {:name :b :parent :base :args {:x :a}}]))))

  (testing "cycle exception includes remaining nodes"
    (try
      (#'core/topological-sort
       [{:name :a :parent :base :args {:x :b}}
        {:name :b :parent :base :args {:x :a}}])
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :fn-composition/circular-dependency (:type (ex-data e))))
        (is (= #{:a :b} (:remaining (ex-data e)))))))

  (testing "single element"
    (let [sorted (#'core/topological-sort [{:name :solo :parent :base}])]
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
        (#'core/check-order-and-warn fn-defs sorted))
      (is (empty? @logged))))

  (testing "warns when order differs"
    (let [logged (atom [])
          fn-defs [{:name :b :parent :base} {:name :a :parent :base}]
          sorted [{:name :a :parent :base} {:name :b :parent :base}]]
      (with-redefs [clojure.tools.logging/log*
                    (fn [_ level _ msg]
                      (swap! logged conj {:level level :message msg}))]
        (#'core/check-order-and-warn fn-defs sorted))
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
          deps (#'core/extract-dependencies fn-def #{:parent-fn :child})]
      (is (contains? deps :parent-fn))))

  (testing "does not include parent when parent is external"
    (let [fn-def {:name :child :parent :external-base :args {:a :some-fn}}
          deps (#'core/extract-dependencies fn-def #{:child :some-fn})]
      (is (not (contains? deps :external-base)))
      (is (contains? deps :some-fn))))

  (testing "handles arg values that are maps (not fn refs)"
    (let [fn-def {:name :my-fn :parent :base :args {:a {:key "value"}}}
          deps (#'core/extract-dependencies fn-def #{:my-fn})]
      ;; Maps are not fn refs, so no deps
      (is (empty? deps))))

  (testing "handles arg values that are vectors (not fn refs)"
    (let [fn-def {:name :my-fn :parent :base :args {:a [1 2 3]}}
          deps (#'core/extract-dependencies fn-def #{:my-fn})]
      (is (empty? deps))))

  (testing "handles arg values that are numbers"
    (let [fn-def {:name :my-fn :parent :base :args {:a 42 :b 3.14}}
          deps (#'core/extract-dependencies fn-def #{:my-fn})]
      (is (empty? deps))))

  (testing "handles mixed arg values"
    (let [fn-def {:name :my-fn :parent :base
                  :args {:a :dep-fn    ; fn ref in set
                         :b :external  ; fn ref not in set
                         :c 42         ; literal
                         :d "string"}} ; literal
          deps (#'core/extract-dependencies fn-def #{:my-fn :dep-fn})]
      (is (= #{:dep-fn} deps)))))


;; === build-dependency-graph ===

(deftest build-dependency-graph-comprehensive-test
  (testing "empty input"
    (is (= {} (#'core/build-dependency-graph []))))

  (testing "single fn with no deps"
    (is (= {:solo #{}} (#'core/build-dependency-graph [{:name :solo :parent :base}]))))

  (testing "complex graph"
    (let [fn-defs [{:name :a :parent :base}
                   {:name :b :parent :a :args {:x :a}}
                   {:name :c :parent :base :args {:x :a :y :b}}]
          graph (#'core/build-dependency-graph fn-defs)]
      (is (= #{} (get graph :a)))
      (is (= #{:a} (get graph :b)))
      (is (= #{:a :b} (get graph :c))))))


;; === get-parent-arg-cached ===

(deftest get-parent-arg-cached-test
  (testing "finds arg by name on first fn"
    (let [fn-id (random-uuid)
          arg {:id (random-uuid) :fn-id fn-id :name "x"}
          fn-cache {fn-id {:id fn-id :parent-id nil}}
          args-data {:by-fn {fn-id [arg]}
                     :by-id {(:id arg) arg}}]
      (is (= arg (#'core/get-parent-arg-cached fn-cache args-data fn-id :x)))))

  (testing "follows parent-id chain to find arg"
    (let [grandparent-id (random-uuid)
          parent-id (random-uuid)
          arg {:id (random-uuid) :fn-id grandparent-id :name "deep-arg"}
          fn-cache {parent-id {:id parent-id :parent-id grandparent-id}
                    grandparent-id {:id grandparent-id :parent-id nil}}
          args-data {:by-fn {parent-id []
                             grandparent-id [arg]}
                     :by-id {(:id arg) arg}}]
      (is (= arg (#'core/get-parent-arg-cached fn-cache args-data parent-id :deep-arg)))))

  (testing "throws when arg not found in chain"
    (let [fn-id (random-uuid)
          fn-cache {fn-id {:id fn-id :parent-id nil}}
          args-data {:by-fn {fn-id []}
                     :by-id {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Argument not found"
            (#'core/get-parent-arg-cached fn-cache args-data fn-id :nonexistent)))))

  (testing "throws when chain too deep"
    (binding [sp/*max-graph-iterations* 2]
      (let [ids (repeatedly 5 random-uuid)
            fn-cache (into {} (map-indexed (fn [i id]
                                             [id {:id id :parent-id (get (vec ids) (inc i))}])
                                           (butlast ids)))
            args-data {:by-fn {} :by-id {}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too deep"
              (#'core/get-parent-arg-cached fn-cache args-data (first ids) :x)))))))


;; === find-available-arg ===

(deftest find-available-arg-test
  (testing "finds arg in direct parent chain first"
    (let [parent-id (random-uuid)
          arg {:id (random-uuid) :fn-id parent-id :name "x"}
          fn-cache {parent-id {:id parent-id :parent-id nil}}
          args-data {:by-fn {parent-id [arg]}
                     :by-id {(:id arg) arg}}]
      (is (= arg (#'core/find-available-arg fn-cache args-data parent-id :x)))))

  (testing "finds arg in propagated free args when not in parent chain"
    (let [parent-id (random-uuid)
          ref-fn-id (random-uuid)
          ;; parent has bound arg referencing ref-fn
          bound-arg {:id (random-uuid) :fn-id parent-id :value nil :ref-id ref-fn-id :name "f"}
          ;; ref-fn has free arg named "target"
          free-arg {:id (random-uuid) :fn-id ref-fn-id :value nil :ref-id nil :name "target"}
          fn-cache {parent-id {:id parent-id :parent-id nil}
                    ref-fn-id {:id ref-fn-id :parent-id nil}}
          args-data {:by-fn {parent-id [bound-arg]
                             ref-fn-id [free-arg]}
                     :by-id {(:id bound-arg) bound-arg
                             (:id free-arg) free-arg}}]
      (is (= free-arg (#'core/find-available-arg fn-cache args-data parent-id :target)))))

  (testing "throws when arg not found anywhere"
    (let [parent-id (random-uuid)
          fn-cache {parent-id {:id parent-id :parent-id nil}}
          args-data {:by-fn {parent-id []}
                     :by-id {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found in available args"
            (#'core/find-available-arg fn-cache args-data parent-id :nonexistent))))))


;; === prepare-propagated-arg-record ===

(deftest prepare-propagated-arg-record-test
  (testing "creates new propagated arg record"
    (let [fn-id (random-uuid)
          parent-arg {:id (random-uuid) :type :int :is-fn false}
          args-data {:by-fn-source {}}
          result (#'core/prepare-propagated-arg-record args-data fn-id parent-arg)]
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
          result (#'core/prepare-propagated-arg-record args-data fn-id parent-arg)]
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
                   (#'core/prepare-fn-record fn-name-cache fn-id-cache created-fns fn-def))]
      (is (some? (:new result)))
      (is (= "new-fn" (:name (:new result))))
      (is (= parent-id (:parent-id (:new result))))))

  (testing "returns existing fn when in name cache"
    (let [existing-fn {:id (random-uuid) :name "existing-fn"}
          fn-name-cache {"existing-fn" existing-fn}
          fn-def {:name :existing-fn :parent :base}
          result (#'core/prepare-fn-record fn-name-cache {} {} fn-def)]
      (is (= existing-fn (:existing result)))))

  (testing "local fn (underscore prefix) is always created fresh"
    (let [parent-id (random-uuid)
          fn-name-cache {"_local" {:id (random-uuid) :name "_local"}}
          fn-id-cache {parent-id {:id parent-id}}
          fn-def {:name :_local :parent :base-fn}
          result (with-redefs [registry/fn-uuid
                               (fn [n] (when (= n :base-fn) parent-id))]
                   (#'core/prepare-fn-record fn-name-cache fn-id-cache {} fn-def))]
      ;; Should create new, not return existing
      (is (some? (:new result)))
      ;; Local fns get name=nil in DB
      (is (nil? (:name (:new result))))))

  (testing "resolves parent from created-fns"
    (let [parent-id (random-uuid)
          fn-def {:name :child :parent :parent-fn}
          result (with-redefs [registry/fn-uuid
                               (fn [_] nil)]
                   (#'core/prepare-fn-record {} {} {:parent-fn parent-id} fn-def))]
      (is (some? (:new result)))
      (is (= parent-id (:parent-id (:new result))))))

  (testing "throws when parent not found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
          (with-redefs [registry/fn-uuid
                        (fn [_] nil)]
            (#'core/prepare-fn-record {} {} {} {:name :orphan :parent :missing}))))))


;; === resolve-fn-id-cached ===

(deftest resolve-fn-id-cached-test
  (testing "resolves from created-fns first"
    (let [id (random-uuid)]
      (is (= id (#'core/resolve-fn-id-cached {} {:my-fn id} :my-fn)))))

  (testing "resolves from fn-name-cache"
    (let [id (random-uuid)]
      (is (= id (#'core/resolve-fn-id-cached {"my-fn" {:id id}} {} :my-fn)))))

  (testing "prefers created-fns over fn-name-cache"
    (let [created-id (random-uuid)
          cached-id (random-uuid)]
      (is (= created-id (#'core/resolve-fn-id-cached
                         {"my-fn" {:id cached-id}}
                         {:my-fn created-id}
                         :my-fn)))))

  (testing "throws when not found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
          (#'core/resolve-fn-id-cached {} {} :missing)))))


;; === resolve-parent-fn-id-cached ===

(deftest resolve-parent-fn-id-cached-test
  (testing "resolves from created-fns first"
    (let [id (random-uuid)]
      (is (= id (#'core/resolve-parent-fn-id-cached {} {} {:my-parent id} :my-parent)))))

  (testing "resolves from registry via fn-id-cache"
    (let [base-id (random-uuid)]
      (with-redefs [registry/fn-uuid
                    (fn [n] (when (= n :base) base-id))]
        (is (= base-id (#'core/resolve-parent-fn-id-cached
                        {} {base-id {:id base-id}} {} :base))))))

  (testing "resolves from fn-name-cache"
    (let [id (random-uuid)]
      (is (= id (#'core/resolve-parent-fn-id-cached
                 {"my-parent" {:id id}} {} {} :my-parent)))))

  (testing "throws when not found"
    (with-redefs [registry/fn-uuid
                  (fn [_] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (#'core/resolve-parent-fn-id-cached {} {} {} :missing))))))
