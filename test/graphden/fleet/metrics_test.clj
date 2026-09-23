(ns graphden.fleet.metrics-test
  "Cell weight / load signals (`graphden.fleet.metrics`, docs/FLEET_RFC.md §6.3).
   Pure reads over the `:forward-deps` index + the `:fn-execution` table — no
   container needed; a synthetic dep-map and a reify-storage exercise both."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.metrics :as metrics]
    [graphden.storage.protocol.core :as sp]))


(def ^:private A #uuid "00000000-0000-0000-0000-00000000000a")
(def ^:private B #uuid "00000000-0000-0000-0000-00000000000b")
(def ^:private C #uuid "00000000-0000-0000-0000-00000000000c")
(def ^:private D #uuid "00000000-0000-0000-0000-00000000000d")


(deftest cell-fn-count-is-forward-closure-size
  ;; forward: A→B→C→D chain, plus an unrelated singleton.
  (let [fwd {A #{B}, B #{C}, C #{D}}]
    (testing "a chain root counts itself + every transitive dep"
      (is (= 4 (metrics/cell-fn-count fwd A)))
      (is (= 2 (metrics/cell-fn-count fwd C))))
    (testing "a leaf is a one-fn cell"
      (is (= 1 (metrics/cell-fn-count fwd D))))
    (testing "a root with no edges at all is a one-fn cell"
      (is (= 1 (metrics/cell-fn-count {} A))))))


(defn- pending-storage
  "Fake storage: `query-entities :fn-execution {:status :pending}` → every
   org's pending rows (`(get org->pending o)` of them per org, each carrying
   its `:org-id`)."
  [org->pending]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (when (and (= en :fn-execution) (= {:status :pending} where))
        (vec (for [[org n] org->pending, _ (range n)] {:org-id org :status :pending}))))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest pending-loads-counts-pending-rows-per-org
  (let [loads (metrics/pending-loads (pending-storage {"acme" 5 "beta" 0}))]
    (testing "an org's live load is its pending-execution row count"
      (is (= 5 (get loads "acme"))))
    (testing "an idle org is absent — load 0"
      (is (zero? (get loads "beta" 0)))
      (is (zero? (get loads "ghost" 0))))))


(deftest cell-weight-folds-structure-and-load
  (let [fwd {A #{B}, B #{C}, C #{D}}]          ; A's cell = 4 fns
    (testing "default equal weights sum fn-count + load"
      ;; 4 fns + 3 pending = 7
      (is (= 7.0 (metrics/cell-weight fwd A 3))))
    (testing "custom weights scale each term independently"
      ;; 2·4 + 10·3 = 38
      (is (= 38.0 (metrics/cell-weight fwd A 3 {:w-fn-count 2.0 :w-load 10.0}))))
    (testing "an idle org's weight is purely structural"
      (is (= 4.0 (metrics/cell-weight fwd A 0))))))
