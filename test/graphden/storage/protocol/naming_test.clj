(ns graphden.storage.protocol.naming-test
  "Tests for naming convention helpers."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === kw->snake-case and snake->kw tests ===

(deftest kw->snake-case-test
  (testing "converts kebab-case to snake_case"
    (is (= "user_name" (storage/kw->snake-case :user-name)))
    (is (= "parent_id" (storage/kw->snake-case :parent-id))))

  (testing "handles simple keywords"
    (is (= "id" (storage/kw->snake-case :id)))
    (is (= "name" (storage/kw->snake-case :name)))))


(deftest snake->kw-test
  (testing "converts snake_case to kebab-case keyword"
    (is (= :user-name (storage/snake->kw "user_name")))
    (is (= :parent-id (storage/snake->kw "parent_id"))))

  (testing "handles simple strings"
    (is (= :id (storage/snake->kw "id")))
    (is (= :name (storage/snake->kw "name")))))


(deftest check-snake-case-collisions!-test
  (testing "passes for unique snake_case names"
    (is (nil? (storage/check-snake-case-collisions! {:context "test"} [:user :fn :arg]))))

  (testing "throws for colliding names"
    ;; :user-name and :user_name would both become user_name
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"collision"
          (storage/check-snake-case-collisions! {:context "test"} [:user-name :user_name])))))


;; === traverse-bfs tests ===

(deftest traverse-bfs-test
  (testing "traverses graph and returns visited nodes"
    (let [graph {:a #{:b :c}
                 :b #{:d}
                 :c #{:d}
                 :d #{}}
          get-neighbors (fn [node] (get graph node #{}))]
      ;; traverse-bfs returns set of visited nodes
      (is (= #{:a :b :c :d} (storage/traverse-bfs :a get-neighbors)))))

  (testing "returns set with just start node if no neighbors"
    (is (= #{:x} (storage/traverse-bfs :x (constantly #{})))))

  (testing "handles cycles in graph"
    (let [graph {:a #{:b}
                 :b #{:c}
                 :c #{:a}}  ; cycle back to :a
          get-neighbors (fn [node] (get graph node #{}))]
      ;; Should visit each node exactly once despite cycle
      (is (= #{:a :b :c} (storage/traverse-bfs :a get-neighbors))))))
