(ns graphden.cache-eager.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-eager.core :as sut]
    [graphden.cache.interface :as cache]
    [integrant.core :as ig]))


;; === on-node-added ===

(deftest add-base-node
  (let [c (sut/create-cache)]
    (testing "Adding base node (no parent)"
      (cache/on-node-added* c {:node-name :base
                               :args [{:arg-name :x :arg-val 1}]})
      ;; Base node has no root-ancestor (it IS the root)
      (is (nil? (sut/get-root-ancestor c :base)))
      ;; But has full-args
      (is (= {:x {:arg-name :x :arg-val 1}}
             (sut/get-full-args c :base)))
      ;; No children yet
      (is (nil? (sut/get-children c :base))))))


(deftest add-child-node
  (let [c (sut/create-cache)]
    (testing "Adding child node sets root-ancestor and updates parent's children"
      (cache/on-node-added* c {:node-name :parent
                               :args [{:arg-name :a :arg-val 1}]})
      (cache/on-node-added* c {:node-name :child
                               :parent-name :parent
                               :args [{:arg-name :b :arg-val 2}]})
      ;; Child's root is parent
      (is (= :parent (sut/get-root-ancestor c :child)))
      ;; Full args are merged
      (is (= {:a {:arg-name :a :arg-val 1}
              :b {:arg-name :b :arg-val 2}}
             (sut/get-full-args c :child)))
      ;; Parent has child in children set
      (is (= #{:child} (sut/get-children c :parent))))))


(deftest add-grandchild-node
  (let [c (sut/create-cache)]
    (testing "Grandchild inherits root from grandparent"
      (cache/on-node-added* c {:node-name :grandparent :args []})
      (cache/on-node-added* c {:node-name :parent
                               :parent-name :grandparent
                               :args []})
      (cache/on-node-added* c {:node-name :child
                               :parent-name :parent
                               :args []})
      (is (= :grandparent (sut/get-root-ancestor c :child))))))


(deftest add-node-with-arg-refs
  (let [c (sut/create-cache)]
    (testing "Arg references are tracked"
      (cache/on-node-added* c {:node-name :target :args []})
      (cache/on-node-added* c {:node-name :source
                               :args [{:arg-name :ref :arg-val :target}]})
      (is (= #{[:source :ref]} (sut/get-arg-refs c :target))))))


;; === on-node-deleted ===

(deftest delete-leaf-node
  (let [c (sut/create-cache)]
    (testing "Deleting leaf node clears cache entries"
      (cache/on-node-added* c {:node-name :parent :args []})
      (cache/on-node-added* c {:node-name :child
                               :parent-name :parent
                               :args []})
      (cache/on-node-deleted* c :child)
      (is (nil? (sut/get-root-ancestor c :child)))
      (is (nil? (sut/get-full-args c :child))))))


(deftest delete-node-with-children-throws
  (let [c (sut/create-cache)]
    (testing "Cannot delete node with children"
      (cache/on-node-added* c {:node-name :parent :args []})
      (cache/on-node-added* c {:node-name :child
                               :parent-name :parent
                               :args []})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot delete node with dependents"
            (cache/on-node-deleted* c :parent))))))


(deftest delete-node-with-arg-refs-throws
  (let [c (sut/create-cache)]
    (testing "Cannot delete node that is referenced"
      (cache/on-node-added* c {:node-name :target :args []})
      (cache/on-node-added* c {:node-name :source
                               :args [{:arg-name :ref :arg-val :target}]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot delete node with dependents"
            (cache/on-node-deleted* c :target))))))


;; === on-node-renamed ===

(deftest rename-updates-root-ancestor
  (let [c (sut/create-cache)]
    (testing "Renaming root updates descendants' root-ancestor"
      (cache/on-node-added* c {:node-name :old-root :args []})
      (cache/on-node-added* c {:node-name :child
                               :parent-name :old-root
                               :args []})
      (cache/on-node-renamed* c :old-root :new-root)
      (is (= :new-root (sut/get-root-ancestor c :child))))))


(deftest rename-updates-full-args-key
  (let [c (sut/create-cache)]
    (testing "Renaming moves full-args to new key"
      (cache/on-node-added* c {:node-name :old-name
                               :args [{:arg-name :x :arg-val 1}]})
      (cache/on-node-renamed* c :old-name :new-name)
      (is (nil? (sut/get-full-args c :old-name)))
      (is (some? (sut/get-full-args c :new-name))))))


(deftest rename-updates-children-set
  (let [c (sut/create-cache)]
    (testing "Renaming updates parent's children set"
      (cache/on-node-added* c {:node-name :parent :args []})
      (cache/on-node-added* c {:node-name :old-child
                               :parent-name :parent
                               :args []})
      (cache/on-node-renamed* c :old-child :new-child)
      (is (= #{:new-child} (sut/get-children c :parent))))))


(deftest rename-updates-arg-refs
  (let [c (sut/create-cache)]
    (testing "Renaming updates arg-refs key"
      (cache/on-node-added* c {:node-name :target :args []})
      (cache/on-node-added* c {:node-name :source
                               :args [{:arg-name :ref :arg-val :target}]})
      (cache/on-node-renamed* c :target :new-target)
      (is (nil? (sut/get-arg-refs c :target)))
      (is (= #{[:source :ref]} (sut/get-arg-refs c :new-target))))))


;; === on-arg-changed ===

(deftest arg-changed-updates-full-args
  (let [c (sut/create-cache)]
    (testing "Changing arg value updates full-args"
      (cache/on-node-added* c {:node-name :node
                               :args [{:arg-name :x :arg-val 1}]})
      (cache/on-arg-changed* c :node :x 42)
      (is (= 42 (get-in (sut/get-full-args c :node) [:x :arg-val]))))))


(deftest arg-changed-tracks-new-ref
  (let [c (sut/create-cache)]
    (testing "Changing arg to keyword adds arg-ref"
      (cache/on-node-added* c {:node-name :target :args []})
      (cache/on-node-added* c {:node-name :source
                               :args [{:arg-name :ref :arg-val nil}]})
      (cache/on-arg-changed* c :source :ref :target)
      (is (contains? (sut/get-arg-refs c :target) [:source :ref])))))


;; === on-parent-changed ===

(deftest parent-changed-updates-root-ancestor
  (let [c (sut/create-cache)]
    (testing "Changing parent updates root-ancestor"
      (cache/on-node-added* c {:node-name :root1 :args []})
      (cache/on-node-added* c {:node-name :root2 :args []})
      (cache/on-node-added* c {:node-name :child
                               :parent-name :root1
                               :args []})
      (is (= :root1 (sut/get-root-ancestor c :child)))
      (cache/on-parent-changed* c :child :root2)
      (is (= :root2 (sut/get-root-ancestor c :child))))))


(deftest parent-changed-cascades-to-descendants
  (let [c (sut/create-cache)]
    (testing "Changing parent cascades to all descendants"
      (cache/on-node-added* c {:node-name :root1 :args []})
      (cache/on-node-added* c {:node-name :root2 :args []})
      (cache/on-node-added* c {:node-name :middle
                               :parent-name :root1
                               :args []})
      (cache/on-node-added* c {:node-name :leaf
                               :parent-name :middle
                               :args []})
      (cache/on-parent-changed* c :middle :root2)
      (is (= :root2 (sut/get-root-ancestor c :middle)))
      (is (= :root2 (sut/get-root-ancestor c :leaf))))))


;; === get-cached / compute-if-absent ===

(deftest get-cached-returns-nil-for-missing
  (let [c (sut/create-cache)]
    (testing "get-cached returns nil for missing keys"
      (is (nil? (cache/get-cached* c [:root-ancestor :non-existent]))))))


(deftest compute-if-absent-throws-on-miss
  (let [c (sut/create-cache)]
    (testing "compute-if-absent throws for eager cache miss"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cache miss in eager cache"
            (cache/compute-if-absent* c
                                      [:root-ancestor :missing]
                                      (fn [] :computed)))))))


;; === Convenience functions ===

(deftest convenience-functions-use-correct-keys
  (let [c (sut/create-cache)]
    (testing "Convenience functions return expected data"
      (cache/on-node-added* c {:node-name :parent :args []})
      (cache/on-node-added* c {:node-name :child
                               :parent-name :parent
                               :args [{:arg-name :val :arg-val :parent}]})
      (is (= :parent (sut/get-root-ancestor c :child)))
      (is (map? (sut/get-full-args c :child)))
      (is (= #{:child} (sut/get-children c :parent)))
      (is (= #{[:child :val]} (sut/get-arg-refs c :parent))))))


;; === Integrant ===

(deftest integrant-init-creates-cache
  (testing "ig/init-key creates cache"
    (let [c (ig/init-key ::sut/cache {})]
      (is (instance? graphden.cache_eager.core.EagerCache c))
      (cache/on-node-added* c {:node-name :test :args []})
      (is (some? (sut/get-full-args c :test))))))
