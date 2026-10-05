(ns graphden.executor.qualified-db-alias-test
  "DB aliases retain namespace identity across refreshes and branch views."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.owned :as owned]
    [graphden.packages.records :as records]
    [graphden.packages.sync :as sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (fn [f]
    (binding [cr/*per-org-aliases-override* (atom {})
              types/*type-aliases-override* (atom {})]
      (f))))


(defn- duplicate-graph
  [right-element]
  {:fns [{:id "int" :name "int"}
         {:id "text" :name "text"}
         {:id "left" :name "value" :namespace-id "left-ns"
          :element-fn-id "int" :org-id "A"}
         {:id "right" :name "value" :namespace-id "right-ns"
          :element-fn-id right-element :org-id "A"}
         {:id "record" :name "envelope" :namespace-id "right-ns" :org-id "A"}]
   :slots [{:id "field" :name "payload" :type-fn-id "left"}]
   :fn-slots [{:fn-id "record" :slot-id "field" :position 0}]})


(def paths {"left-ns" "user.left" "right-ns" "user.right"})


(deftest same-short-name-retains-distinct-qualified-bodies-and-references
  (cr/register-type-aliases-from-db! (duplicate-graph "text") :main paths)
  (testing "a record's referenced type follows its id, not the last bare name"
    (is (= {:payload [:list :int]}
           (types/resolve-alias :user.right/envelope))))
  (testing "both qualified declarations resolve and bare compatibility stays"
    (is (= [[:list :int] [:list :text] [:list :text]]
           (mapv types/resolve-alias
                 [:user.left/value :user.right/value :value])))))


(deftest qualified-aliases-use-the-branch-and-org-view-and-retire-with-it
  (cr/register-type-aliases-from-db! (duplicate-graph "text") :main paths)
  (cr/register-type-aliases-from-db! (duplicate-graph "int") :feature paths)
  (testing "compiling another branch does not change this branch's type body"
    (is (= [:list :text]
           (:user.right/value (cr/org-alias-snapshot "public" "A" :main))))
    (is (= [:list :int]
           (:user.right/value (cr/org-alias-snapshot "public" "A" :feature)))))
  (testing "another org cannot read the qualified declaration"
    (is (nil? (:user.right/value (cr/org-alias-snapshot "public" "B" :main)))))
  (cr/register-type-aliases-from-db! {:fns []} :main paths)
  (testing "deletion removes the branch's qualified slice, not another branch's"
    (is (nil? (:user.right/value (cr/org-alias-snapshot "public" "A" :main))))
    (is (= [:list :int]
           (:user.right/value (cr/org-alias-snapshot "public" "A" :feature)))))
  (cr/forget-alias-source! :feature)
  (testing "the last source retiring removes qualified and bare names"
    (is (not-any? types/alias-registered?
                  [:user.right/value :user.left/value :user.right/envelope :value]))))


(deftest self-hosted-user-namesake-does-not-inherit-the-package-type-body
  ;; Unique namespace/name: the declared package-body cache is process-wide.
  (let [nm (keyword (str "qualified-alias-" (random-uuid)))
        package-id (random-uuid)
        qualified (keyword "alias.fixture" (name nm))
        user-qualified (keyword "user.fixture" (name nm))]
    (sync/register-type-aliases!
      [{:name nm :namespace "alias.fixture" :type {:payload :text}}])
    (owned/record-owned-ids! [package-id])
    (cr/register-type-aliases-from-db!
      {:fns [{:id "int" :name "int"}
             {:id package-id :name (name nm) :namespace-id "pkg" :element-fn-id "int"}
             {:id "user" :name (name nm) :namespace-id "user" :element-fn-id "int"}]}
      :main {"pkg" "alias.fixture" "user" "user.fixture"})
    (testing "a bundled type keeps its declared structural body"
      (is (= {:payload :text} (types/resolve-alias qualified))))
    (testing "the untenanted user copy keeps its independently stored shape"
      (is (= [:list :int] (types/resolve-alias user-qualified))))))


(deftest storage-refresh-loads-full-namespace-paths
  (let [storage (setup/create-test-storage)
        parent-id (random-uuid)
        ns-id (random-uuid)
        fn-id (random-uuid)
        type-id (records/primitive-fn-id :int)]
    (try
      (sp/upsert-entities storage :ns
                          [{:id parent-id :name "user" :parent-id nil}
                           {:id ns-id :name "imported" :parent-id parent-id}])
      (sp/upsert-entities storage :fn
                          [{:id fn-id :name "items" :namespace-id ns-id
                            :parent-ids [] :element-fn-id type-id}])
      (cr/refresh-type-registries-from-storage! (exec/create-context {:storage storage}))
      (is (= [:list :int] (types/resolve-alias :user.imported/items)))
      (finally (sp/close storage)))))
