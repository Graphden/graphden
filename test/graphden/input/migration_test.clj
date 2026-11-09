(ns graphden.input.migration-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.migration :as migration]
    [graphden.input.offline :as offline])
  (:import
    (java.sql
      Timestamp)
    (java.time
      Instant)))


(defn- fixture
  []
  (let [[owner child type field membership binding branch version] (repeatedly 8 random-uuid)
        ts (Timestamp/from (Instant/parse "2026-11-09T01:00:00.123456789Z"))]
    {:fn [{:id owner :name "base" :return-type-fn-id type}
          {:id child :name "child" :parent-ids [owner]} {:id type :name "text"}]
     :slot [{:id field :name "value" :type-fn-id type}]
     :fn-slot [{:id membership :fn-id owner :slot-id field :position 0}]
     :fn-slot-version [{:id version :fn-slot-id membership :fn-id owner :slot-id field
                        :branch-id branch :created-at ts :position 0}]
     :fn-version [{:id (random-uuid) :fn-id owner :name "base" :branch-id branch
                   :created-at ts :return-type-fn-id type}
                  {:id (random-uuid) :fn-id child :name "child" :branch-id branch :created-at ts}]
     :binding [{:id binding :fn-id owner :slot-id field :value-present true :value {:ref nil :value false}}]}))


(deftest deterministic-origins-inheritance-and-projection-test
  (let [source (fixture)
        plan (migration/plan source)
        binding (first (:binding source))
        migrated (first (get-in plan [:additions :input-binding]))]
    (is (= [] (:exceptions plan)))
    (is (= plan (migration/plan (into {} (map (fn [[k v]] [k (vec (reverse v))])) source))))
    (is (= 2 (count (get-in plan [:additions :fn]))))
    (is (= 2 (count (get-in plan [:additions :fn-slot]))))
    (is (= 1 (count (get-in plan [:additions :input-binding]))))
    (is (= binding (migration/legacy-binding migrated)))
    (is (= (:id binding)
           (migration/binding-origin plan [(:fn-id binding) []] [(:slot-id binding)])))))


(deftest timestamps-and-literal-nil-survive-artifact-text-test
  (let [artifact (offline/artifact (fixture))
        decoded (offline/decode (offline/encode artifact))]
    (is (= artifact decoded))
    (is (= (get-in artifact [:plan :source-fingerprint])
           (migration/fingerprint (:source decoded)))))
  (is (thrown? RuntimeException (offline/decode "#=(System/exit 1)"))))


(deftest unsupported-forms-are-blocking-reports-test
  (let [source (fixture)
        sources [(assoc-in source [:binding 0 :list-append] true)
                 (assoc-in source [:slot 0 :source-slot-id] (random-uuid))
                 (assoc-in source [:binding 0 :slot-id] (random-uuid))
                 (assoc-in source [:fn-slot-version 0 :fn-id] (random-uuid))]]
    (doseq [unsupported sources]
      (let [artifact (offline/artifact unsupported)]
        (is (seq (get-in artifact [:plan :exceptions])))
        (is (nil? (:target-fingerprint artifact)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported definitions"
              (offline/apply! nil artifact)))))))


(deftest new-nested-bindings-have-no-fake-legacy-projection-test
  (is (thrown? clojure.lang.ExceptionInfo
        (migration/legacy-binding {:scope-path [] :target-path [(random-uuid) (random-uuid)]}))))
