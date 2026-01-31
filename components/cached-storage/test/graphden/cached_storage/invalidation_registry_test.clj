(ns graphden.cached-storage.invalidation-registry-test
  "Tests for the invalidation rule registry."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.cached-storage.invalidation :as inv]))


(defn- reset-and-restore-fixture
  "Saves registry state before each test and restores after."
  [f]
  (let [saved-rules (inv/get-rules)]
    (inv/reset-registry!)
    (try
      (f)
      (finally
        (inv/reset-registry!)
        (doseq [rule saved-rules]
          (inv/register-rule! rule))))))


(use-fixtures :each reset-and-restore-fixture)


(deftest register-rule-test
  (testing "registers a rule and retrieves it"
    (inv/register-rule! {:entity-type :fn :on-event :create :handler identity})
    (is (= 1 (count (inv/get-rules))))
    (is (= :fn (:entity-type (first (inv/get-rules))))))

  (testing "registers multiple rules"
    (inv/register-rule! {:entity-type :fn :on-event :delete :handler identity})
    (is (= 2 (count (inv/get-rules))))))


(deftest reset-registry-test
  (testing "clears all rules and returns previous"
    (inv/register-rule! {:entity-type :fn :on-event :create :handler identity})
    (inv/register-rule! {:entity-type :fn :on-event :delete :handler identity})
    (let [prev (inv/reset-registry!)]
      (is (= 2 (count prev)))
      (is (zero? (count (inv/get-rules)))))))


(deftest has-strategy-test
  (testing "returns false when no rules registered"
    (is (not (inv/has-strategy? :fn :create))))

  (testing "returns true when matching rule exists"
    (inv/register-rule! {:entity-type :fn :on-event :create :handler identity})
    (is (inv/has-strategy? :fn :create)))

  (testing "returns false for non-matching entity-type"
    (is (not (inv/has-strategy? :arg-value :create))))

  (testing "returns false for non-matching on-event"
    (is (not (inv/has-strategy? :fn :delete)))))


(deftest process-invalidation-test
  (testing "executes matching handlers"
    (let [calls (atom [])]
      (inv/register-rule! {:entity-type :fn
                           :on-event :create
                           :handler (fn [ctx] (swap! calls conj ctx))})
      (inv/process-invalidation! :fn :create {:id 1})
      (is (= 1 (count @calls)))
      (is (= {:id 1} (first @calls)))))

  (testing "does not execute non-matching handlers"
    (let [calls (atom [])]
      (inv/register-rule! {:entity-type :fn
                           :on-event :create
                           :handler (fn [ctx] (swap! calls conj ctx))})
      (inv/process-invalidation! :fn :delete {:id 2})
      (is (zero? (count @calls)))))

  (testing "executes multiple matching handlers in order"
    (let [calls (atom [])]
      (inv/register-rule! {:entity-type :fn
                           :on-event :create
                           :handler (fn [_] (swap! calls conj :first))})
      (inv/register-rule! {:entity-type :fn
                           :on-event :create
                           :handler (fn [_] (swap! calls conj :second))})
      (inv/process-invalidation! :fn :create {})
      (is (= [:first :second] @calls)))))


(deftest compute-dependencies-test
  (testing "computes dependency counts from graph"
    (let [fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)
          cs-id (random-uuid)
          graph {:fns {fn-id-1 {} fn-id-2 {}}
                 :fn-schemas {schema-id {}}
                 :arg-schemas {arg-schema-id {}}
                 :call-sites {cs-id {}}}
          deps (inv/compute-dependencies graph)]
      (is (= 2 (count (:fn-ids deps))))
      (is (= 1 (count (:fn-schema-ids deps))))
      (is (= 1 (count (:arg-schema-ids deps))))
      (is (= 1 (count (:call-site-ids deps))))))

  (testing "handles empty maps"
    (let [deps (inv/compute-dependencies {:fns {} :fn-schemas {} :arg-schemas {} :call-sites {}})]
      (is (= {} (:fn-ids deps)))
      (is (= {} (:fn-schema-ids deps)))))

  (testing "handles nil call-sites"
    (let [deps (inv/compute-dependencies {:fns {} :fn-schemas {} :arg-schemas {}})]
      (is (= {} (:call-site-ids deps))))))


(deftest default-rules-test
  (testing "register-default-rules! populates registry"
    (inv/register-default-rules!)
    (let [rules (inv/get-rules)]
      ;; fn(3) + arg-value(3) + fn-arg(3) + fn-schema(2) + arg-schema(2) + call-site(3) + call-site-arg(3) = 19
      (is (= 19 (count rules)))))

  (testing "default rules cover expected entity types"
    (inv/register-default-rules!)
    (let [entity-types (set (map :entity-type (inv/get-rules)))]
      (is (contains? entity-types :fn))
      (is (contains? entity-types :arg-value))
      (is (contains? entity-types :fn-arg))
      (is (contains? entity-types :fn-schema))
      (is (contains? entity-types :arg-schema))
      (is (contains? entity-types :call-site))
      (is (contains? entity-types :call-site-arg))))

  (testing "has-strategy? returns true for default rules"
    (inv/register-default-rules!)
    (is (inv/has-strategy? :fn :create))
    (is (inv/has-strategy? :fn :update))
    (is (inv/has-strategy? :fn :delete))
    (is (inv/has-strategy? :fn-arg :create))
    (is (inv/has-strategy? :call-site :create))
    ;; fn-schema has no :create rule
    (is (not (inv/has-strategy? :fn-schema :create)))))


(deftest invalidate-entity-dependents-unknown-dep-type-test
  (testing "asserts on unknown dep-type"
    (is (thrown? AssertionError
          (inv/invalidate-entity-dependents! nil nil :unknown-type (random-uuid))))))


(deftest delete-handler-nil-record-test
  (testing "fn-arg delete with nil record is no-op"
    (inv/register-default-rules!)
    (inv/process-invalidation! :fn-arg :delete
                               {:base-storage nil :cache-storage nil :record nil})
    (is true "should not throw"))

  (testing "call-site-arg delete with nil record is no-op"
    (inv/register-default-rules!)
    (inv/process-invalidation! :call-site-arg :delete
                               {:base-storage nil :cache-storage nil :record nil})
    (is true "should not throw")))
