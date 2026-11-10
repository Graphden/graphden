(ns ^:integration graphden.versioning.storage.small-graph-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.small-graph :as small]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture))


(defn- graph-shape
  [graph]
  (-> (select-keys graph [:fns :fn-slots :bindings :list-items])
      (update :fn-slots set) (update :bindings set) (update :list-items set)))


(defn- compare-graph
  [storage root hints]
  (let [expected (sp/resolve-execution-graph storage root)
        actual (small/resolve-hinted (vs/unwrap storage) (:branch-id storage) root hints)]
    (is (= (graph-shape expected) (graph-shape actual)))
    actual))


(deftest branch-moves-merges-tombstones-and-identity-only-rows
  (let [storage (setup/create-versioned-test-storage)
        base (vs/unwrap storage)
        root (:id (sp/create-entity storage :fn {:name "small-root"}))
        target (:id (sp/create-entity storage :fn {:name "small-target"}))
        bare (:id (sp/create-entity base :fn {:name "identity-only"}))
        slot (:id (sp/create-entity storage :slot {:name "arg" :type-fn-id bare}))
        membership (sp/create-entity storage :fn-slot {:fn-id root :slot-id slot :position 0})
        binding-row (sp/create-entity storage :binding {:fn-id target :slot-id slot :value 1})
        item (sp/create-entity storage :binding-list-item
                               {:binding-id (:id binding-row) :position 0 :ref-fn-id bare})
        feature (vs/switch-branch storage (:id (vs/create-branch! storage "small-feature")))
        merged (vs/switch-branch storage (:id (vs/create-branch! storage "small-merged")))]
    (try
      (let [extra (:id (sp/create-entity base :slot {:name "bare-slot" :type-fn-id bare}))]
        (sp/create-entity base :fn-slot {:fn-id root :slot-id extra :position 1}))
      (compare-graph storage root #{root})
      (sp/update-entity feature :binding (:id binding-row) {:fn-id root :value nil})
      (sp/update-entity feature :fn-slot (:id membership) {:position 3})
      (testing "fresh owners and nil bindings replace stale hints"
        (let [graph (compare-graph feature root #{root})]
          (is (= #{(:id binding-row)} (set (map :id (:bindings graph)))))
          (is (true? (:value-present (first (:bindings graph)))))))
      (binding [vs/*tombstone-delete?* true]
        (sp/delete-entity feature :binding-list-item (:id item)))
      (compare-graph feature root #{root target})
      (vs/merge-branch! merged (:branch-id feature))
      (compare-graph merged root #{root})
      (compare-graph storage root #{root})
      (finally (sp/close storage)))))


(deftest advisory-selection-is-bounded-and-does-not-read-values
  (is (= #{1 2} (small/hinted-ids {1 #{2} 2 #{}} 1)))
  (is (nil? (small/hinted-ids {1 #{2}} 1)))
  (is (nil? (small/hinted-ids {} 1)))
  (is (nil? (small/hinted-ids (into {} (map (fn [id] [id (if (< id 100) #{(inc id)} #{})]))
                                    (range 101)) 0))))


(defn- measured
  [f]
  (let [rows (atom 0) calls (atom 0)
        observe (fn [execute one? ds query opts]
                  (let [result (execute ds query opts)]
                    (swap! calls inc)
                    (swap! rows + (if one? (if result 1 0) (count result)))
                    result))
        result (binding [pg/*jdbc-override*
                         {:execute! (partial observe jdbc/execute! false)
                          :execute-one! (partial observe jdbc/execute-one! true)}]
                 (f))]
    {:result result :rows @rows :calls @calls}))


(deftest free-argument-surface-stays-fresh-without-loading-unrelated-branch-rows
  (let [storage (setup/create-versioned-test-storage)
        root (:id (sp/create-entity storage :fn {:name "small-free"}))
        slot (:id (sp/create-entity storage :slot {:name "input" :type-fn-id (ids/primitive-fn-id :any)}))
        _ (sp/create-entity storage :fn-slot {:fn-id root :slot-id slot :position 0})
        ctx {:storage storage}
        hinted (assoc ctx :compile-deps (atom {:forward-deps {root #{(ids/primitive-fn-id :any)} (ids/primitive-fn-id :any) #{}}}))]
    (try
      (sp/upsert-entities (vs/unwrap storage) :fn
                          (mapv (fn [n] {:id (random-uuid) :name (str "unrelated-" n)}) (range 200)))
      (let [old (measured #(lookup/free-arg-facts ctx root))
            fresh (measured #(lookup/free-arg-facts hinted root))]
        (is (= (:result old) (:result fresh)))
        (is (= :input (get-in fresh [:result :entries 0 :ext-name])))
        (is (< (:rows fresh) 40) "Small graph reads stay independent of unrelated identities")
        (is (> (- (:rows old) (:rows fresh)) 190)))
      (sp/create-entity storage :binding {:fn-id root :slot-id slot :value false})
      (is (= {:entries [] :via-names {}} (lookup/free-arg-facts hinted root)))
      (finally (sp/close storage)))))


(deftest stale-small-hint-falls-back-before-expanding-a-large-closure
  (let [storage (setup/create-versioned-test-storage)
        parents (mapv (fn [n] {:id (random-uuid) :name (str "large-parent-" n)}) (range 129))]
    (try
      (sp/upsert-entities (vs/unwrap storage) :fn parents)
      (let [root (:id (sp/create-entity storage :fn
                                        {:name "grown-root" :parent-ids (mapv :id parents)}))
            attempt (measured #(small/resolve-hinted (vs/unwrap storage) (:branch-id storage)
                                                     root #{root}))]
        (is (nil? (:result attempt)))
        (is (< (:calls attempt) 20) "One bounded attempt, not one request per parent")
        (is (= 130 (count (:fns (sp/resolve-execution-graph storage root))))))
      (finally (sp/close storage)))))
