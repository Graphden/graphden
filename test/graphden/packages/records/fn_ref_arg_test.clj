(ns graphden.packages.records.fn-ref-arg-test
  "`slot-res/fn-ref-arg?` — the sync-time classifier that tells the
   topological sort which fn-def args are IDENTITY edges (bound into a
   `:fn-ref` slot). Resolves the slot's owner through the inheritance
   chain and the ref tree, like the parser does."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records.slot-resolution :as slot-res]))


(def ^:private defs
  [{:name :service-endpoint :args {:service {:type :fn-ref}} :return-type :jsonb}
   {:name :get-url :args {:coll :jsonb :key :any} :return-type :any}
   ;; `:service` reaches `:service-url` through the ref tree.
   {:name :service-url :parent :get-url :args {:coll :service-endpoint :key {:value :url}}}
   {:name :svc-a :parent :service-endpoint :args {:service :svc-b}}
   {:name :svc-b :parent :service-endpoint :args {:service :svc-a}}
   {:name :fetch :parent :service-url :args {:service :svc-a}}
   {:name :other :parent :get-url :args {:coll :svc-a}}])


(def ^:private by-name
  (slot-res/build-defs-by-name defs))


(deftest fn-ref-arg?-test
  (testing "a direct binding into the base-fn's :fn-ref slot"
    (is (true? (slot-res/fn-ref-arg? (by-name :svc-a) :service by-name))))
  (testing "the same slot reached through the ref tree (free-arg propagation)"
    (is (true? (slot-res/fn-ref-arg? (by-name :fetch) :service by-name))))
  (testing "an ordinary slot is not an identity edge"
    (is (false? (slot-res/fn-ref-arg? (by-name :other) :coll by-name))))
  (testing "an unknown owner stays a dependency (conservative)"
    (is (false? (slot-res/fn-ref-arg? {:name :loose :parent :nowhere :args {:service :svc-a}}
                                      :service by-name)))))
