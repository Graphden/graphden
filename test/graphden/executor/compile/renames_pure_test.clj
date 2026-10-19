(ns graphden.executor.compile.renames-pure-test
  "Pure-helper tests for `executor.compile.renames` — kept in a
   sibling NS to renames-test so they don't need the
   container-backed fixture (renames-test builds real graphs).
   These exercise the small data-shape transforms that the rename
   layer is built on."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]))


(deftest chain-source-slot-ids-empty-test
  (testing "nil slot-id → empty vector"
    (is (= [] (r/chain-source-slot-ids nil {}))))

  (testing "missing entry in slot-map → vector with just the start id"
    ;; Lookup yields nil :source-slot-id → loop exits after appending start.
    (is (= [:s1] (r/chain-source-slot-ids :s1 {})))))


(deftest chain-source-slot-ids-walks-chain-test
  (testing "follows :source-slot-id down to a slot with no source"
    (let [slot-map {:a {:source-slot-id :b}
                    :b {:source-slot-id :c}
                    :c {:source-slot-id nil}}]
      (is (= [:a :b :c] (r/chain-source-slot-ids :a slot-map)))))

  (testing "single-link chain"
    (is (= [:a :b] (r/chain-source-slot-ids
                     :a {:a {:source-slot-id :b}
                         :b {}}))))

  (testing "head with no source field at all → just the head"
    (is (= [:only] (r/chain-source-slot-ids :only {:only {}})))))


(deftest chain-source-slot-ids-cycle-guard-test
  (testing "cycle short-circuits via seen-set (no infinite loop)"
    (let [slot-map {:a {:source-slot-id :b}
                    :b {:source-slot-id :a}}]
      ;; Visits :a, then :b — :a is `seen`, loop exits.
      (is (= [:a :b] (r/chain-source-slot-ids :a slot-map)))))

  (testing "bounded at 16 hops"
    ;; Build a 20-long chain :s0 → :s1 → … → :s19.
    (let [slot-map (into {} (for [i (range 20)]
                              [(keyword (str "s" i))
                               {:source-slot-id (when (< i 19)
                                                  (keyword (str "s" (inc i))))}]))]
      (is (= 16 (count (r/chain-source-slot-ids :s0 slot-map)))
          "depth cap stops after 16 entries"))))


(deftest apply-rename-aliases-test
  (testing "empty aliases → input passed through"
    (is (= {:a 1 :b 2} (r/apply-rename-aliases {:a 1 :b 2} []))))

  (testing "copy rename-name → chain-name when chain is absent"
    (is (= {:item 7 :coll 7}
           (r/apply-rename-aliases {:item 7}
                                   [{:chain-name :coll :rename-name :item}]))
        "chain-name :coll is added, rename-name :item is preserved"))

  (testing "explicit caller-supplied chain-name wins over the alias"
    (is (= {:item 7 :coll 99}
           (r/apply-rename-aliases {:item 7 :coll 99}
                                   [{:chain-name :coll :rename-name :item}]))
        "alias does NOT clobber an explicit :coll the caller supplied"))

  (testing "rename-name absent → noop for that alias"
    (is (= {:other 1}
           (r/apply-rename-aliases {:other 1}
                                   [{:chain-name :coll :rename-name :item}]))))

  (testing "multiple aliases process in order"
    (is (= {:item 7 :coll 7 :branch-row 7}
           (r/apply-rename-aliases
             {:item 7}
             [{:chain-name :coll       :rename-name :item}
              {:chain-name :branch-row :rename-name :item}])))))


(deftest repeated-renames-have-one-runtime-leaf-in-either-row-order
  (doseq [ids [[:old :new] [:new :old]]]
    (let [graph {:fns [{:id :base} {:id :parent :parent-ids [:base]} {:id :grandchild :parent-ids [:parent]}]
                 :slots [{:id :root :name "root"} {:id :old :name "events" :source-slot-id :root}
                         {:id :new :name "renamed-again" :source-slot-id :old}]
                 :fn-slots (into [{:fn-id :base :slot-id :root :position 0}]
                                 (map #(hash-map :fn-id :parent :slot-id % :position 0)) ids)}
          lookup (l/build-lookups graph)]
      (doseq [fid [:parent :grandchild]]
        (is (= :renamed-again (l/chain-rename-for-slot fid :root lookup)))
        (is (= :new (l/effective-reader-slot-id fid :root lookup))))
      (is (= :renamed-again (get (#'r/own-rename-chain-map :parent lookup) :root)))))
  (let [slots {:root {:id :root} :foreign {:id :foreign :source-slot-id :root}
               :capture {:id :capture :source-slot-id :foreign}}
        junctions [{:slot-id :root} {:slot-id :capture}]]
    (is (= junctions (vec (l/active-fn-slots junctions slots)))
        "a captured reader does not hide an independent own root slot")))
