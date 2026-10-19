(ns ^:serial graphden.layout.data-test
  "The derived arg rows a card is drawn from (`derive-fn-slot-views`)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.walk :as walk]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.layout.data :as data]))


(deftest a-binding-on-the-rename-root-reaches-the-view-row
  ;; `text-ok-response` exposes `body`, a rename view over `assoc`'s :value.
  ;; Binding `body` in the editor writes the row on :value (the write path
  ;; canonicalises to the family's root); the card's arg row is the view's.
  ;; The view row must carry that binding, or its `+` outlives the bind.
  (let [assoc-id (random-uuid) ring-id (random-uuid) hello-id (random-uuid)
        value-slot (random-uuid) body-slot (random-uuid) item-slot (random-uuid)
        text-id (random-uuid)
        rows (data/derive-fn-slot-views
               {:fns [{:id text-id :name "text" :parent-ids []}
                      {:id assoc-id :name "assoc" :parent-ids []}
                      {:id ring-id :name "ring-response" :parent-ids [assoc-id]}
                      {:id hello-id :name "tutorial-hello" :parent-ids [ring-id]}]
                :slots [{:id value-slot :name "value" :type-fn-id text-id}
                        {:id body-slot :name "body" :type-fn-id text-id :source-slot-id value-slot}
                        {:id item-slot :name "items" :type-fn-id text-id}]
                :fn-slots [{:fn-id assoc-id :slot-id value-slot :position 0}
                           {:fn-id ring-id :slot-id body-slot :position 0}
                           {:fn-id assoc-id :slot-id item-slot :position 1}]
                :bindings [{:id "b-hello" :fn-id hello-id :slot-id value-slot
                            :value "hello" :value-present true}
                           {:id "b-list" :fn-id hello-id :slot-id item-slot :list-append true}]
                :list-items [{:id "i1" :binding-id "b-list" :position 0 :value 1}]})
        hello-rows (filter #(= hello-id (:fn-id %)) rows)
        body-row (first (filter #(= "body" (:name %)) hello-rows))]
    (testing "the view row carries the binding written on the root"
      (is (some? body-row))
      (is (= "b-hello" (:binding-id body-row)))
      (is (= "hello" (:value body-row)))
      (is (true? (:value-present body-row))))
    (testing "and no row for the root slot itself is emitted twice"
      (is (= 1 (count (filter #(contains? #{"body" "value"} (:name %)) hello-rows)))))
    (testing "a list binding's items chain to the emitted anchor row"
      (let [items-row (first (filter #(= "items" (:name %)) hello-rows))
            item (first (filter #(= "i1" (:id %)) rows))]
        (is (= (:id items-row) (:source-id item)))
        (is (= (:id item) (:next-arg-id items-row)))))))


(deftest synthesised-graph-is-stable-per-snapshot
  ;; The cached graph carries no `:args`, so every layout request built a
  ;; NEW map here — and `cached-build-lookups` (identity-keyed) re-derived
  ;; the whole graph on every POST /api/graph/layout.
  (let [snapshot {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []}
        synth (data/ensure-synth-args snapshot)]
    (is (identical? synth (data/ensure-synth-args snapshot))
        "the same snapshot yields the same synthesised map — so the
         identity-keyed lookups memo downstream can hit")
    (is (not (identical? synth (data/ensure-synth-args (assoc snapshot :namespaces []))))
        "a replaced snapshot is synthesised afresh")))


(deftest layout-caches-retain-only-two-correct-snapshots
  (let [fn-id (random-uuid)
        slot-id (random-uuid)
        graph {:fns [{:id fn-id :name "root" :parent-ids []}]
               :slots [{:id slot-id :name "value"}]
               :fn-slots [{:fn-id fn-id :slot-id slot-id :position 0}]
               :bindings [] :list-items []}
        bound (assoc graph :bindings [{:id (random-uuid) :fn-id fn-id
                                       :slot-id slot-id :value "bound" :value-present true}])
        cleaned (assoc graph :namespaces [])]
    (doseq [{:keys [label run cache prepare rows]}
            [{:label "synthesis" :run data/ensure-synth-args
              :cache #'data/synth-args-state :prepare identity :rows :args}
             {:label "concealed-view synthesis" :run data/resynth-args
              :cache #'data/resynth-args-state :prepare identity :rows :args}
             {:label "lookups" :run data/cached-build-lookups
              :cache #'data/cached-build-lookups-state
              :prepare #(assoc % :args (data/derive-fn-slot-views %))
              :rows #(vals (:arg-map %))}]]
      (testing label
        (with-redefs-fn {cache (atom [])}
          (fn []
            (let [before (prepare graph)
                  changed (prepare bound)
                  after (prepare cleaned)
                  original (run before)
                  changed-result (run changed)]
              (testing "same and alternating snapshots reuse their results"
                (dotimes [_ 3]
                  (is (identical? original (run before)))
                  (is (identical? changed-result (run changed)))))
              (testing "cleanup installs a third snapshot and evicts the oldest"
                (let [clean-result (run after)]
                  (is (= 2 (count @(var-get cache))))
                  (is (identical? changed-result (run changed))
                      "the second snapshot survives installing the third")
                  (let [recomputed (run before)]
                    (is (not (identical? original recomputed)))
                    (is (nil? (:value (first (rows recomputed))))))
                  (is (nil? (:value (first (rows clean-result)))))
                  (is (= "bound" (:value (first (rows changed-result))))
                      "older immutable results do not contaminate cleanup"))))))))))


(deftest lookups-carry-the-children-index
  ;; The executor's descendant walks (`renames/inheritance-descendants`)
  ;; read `:children-by-fn` off the lookups they are handed; layout's lookups
  ;; lacked it, so every call from the layout pipeline rebuilt the inverse
  ;; of every fn's `:parent-ids`.
  (let [lk (data/build-lookups {:fns [{:id :p :parent-ids []}
                                      {:id :c1 :parent-ids [:p]}
                                      {:id :c2 :parent-ids [:p :c1]}]
                                :slots [] :fn-slots [] :bindings [] :list-items []})]
    (is (= {:p #{:c1 :c2} :c1 #{:c2}} (:children-by-fn lk)))))


(deftest hof-analysis-is-reused-only-within-its-snapshot
  (let [root-id (random-uuid)
        type-id (random-uuid)
        slot-id (random-uuid)
        graph {:fns [{:id root-id :name "root" :parent-ids []
                      :return-type-fn-id type-id}
                     {:id type-id :constraint [:fn {} :any]}]
               :slots [{:id slot-id :name "handler" :type-fn-id type-id}]
               :fn-slots [{:fn-id root-id :slot-id slot-id :position 0}]
               :bindings [] :list-items []}
        scans (atom 0)
        compute lookups/compute-fn-typed-fn-ids]
    (with-redefs [lookups/compute-fn-typed-fn-ids
                  (fn [lk] (swap! scans inc) (compute lk))]
      (let [lk (data/cached-build-lookups graph)
            free (bindings/collect-bindings root-id lk)]
        (is (= {:kind :free :is-fn true}
               (select-keys (first free) [:kind :is-fn])))
        (dotimes [_ 10]
          (is (identical? free (bindings/collect-bindings
                                 root-id (data/cached-build-lookups graph)))))
        (is (= 1 @scans) "HOF classification scans the graph once per snapshot")
        (let [changed (assoc graph :bindings
                             [{:id (random-uuid) :fn-id root-id :slot-id slot-id
                               :value false :value-present true}])
              bound (bindings/collect-bindings root-id (data/cached-build-lookups changed))]
          (is (= {:kind :value :value false}
                 (select-keys (first bound) [:kind :value])))
          (is (= 2 @scans))
          (is (= :free (:kind (first (bindings/collect-bindings root-id lk))))))))))


(deftest repeated-rename-renders-only-the-most-specific-source-view
  (let [identities (zipmap [:base :child :text :root :old :new :binding] (repeatedly random-uuid))
        fns [{:id :base :name "base"} {:id :child :name "child" :parent-ids [:base]}
             {:id :text :name "text"}]
        slots [{:id :root :name "root" :type-fn-id :text}
               {:id :old :name "old" :type-fn-id :text :source-slot-id :root}
               {:id :new :name "new" :type-fn-id :text :source-slot-id :old}]]
    (doseq [ids [[:old :new] [:new :old]]]
      (let [rows (data/derive-fn-slot-views
                   (walk/postwalk-replace identities
                                          {:fns fns :slots slots
                                           :fn-slots (into [{:fn-id :base :slot-id :root :position 0}]
                                                           (map #(hash-map :fn-id :child :slot-id % :position 0)) ids)
                                           :bindings [{:id :binding :fn-id :child :slot-id :root}]
                                           :list-items []}))
            own (filter #(= (:child identities) (:fn-id %)) rows)]
        (is (= ["new"] (mapv :name own)))
        (is (= [(:binding identities)] (mapv :binding-id own)))))))
