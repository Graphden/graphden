(ns graphden.crud.typed-rename-reconstruction-test
  "Stored rename views must retain their declared type when CRUD rebuilds
   the fn-def for a check, independently of unrelated binding references."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.type-check :as tc]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
    [graphden.types.diagnostics :as diag]))


(use-fixtures :once (setup/create-container-fixture) exec/with-isolated-rich-types)


(defn- renamed-child!
  "A composed fn with an otherwise unbound :input renamed to typed :event.
   Its type row is deliberately absent from parents, return and value refs."
  [storage type-id]
  (let [parent (setup/create-base-fn! storage "rename-source" :int)
        source (setup/create-slot! storage "input" :any)
        child (setup/create-composed-fn! storage "renamed-child" (:id parent))
        view (sp/create-entity storage :slot
                               {:name "event" :source-slot-id (:id source)
                                :type-fn-id type-id :required true})]
    (setup/attach-slot! storage (:id parent) (:id source) 0)
    (setup/attach-slot! storage (:id child) (:id view) 0)
    (sp/create-entity storage :binding {:fn-id (:id child) :slot-id (:id source)})
    (registry/record-rich-types-raw! (:id parent) :rename-source
                                     {:return :int :args {:input :any} :effects #{}})
    child))


(deftest primitive-type-on-renamed-slot-survives-recheck
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)]
      (try
        (let [child (renamed-child! storage (get setup/primitive-fn-ids :text))]
          (testing "reconstruction fetches a type referenced only by a rename view"
            (is (= {:input {:as :event :type :text}}
                   (:args (tc/reconstruct-fn-def storage (:id child))))))
          (testing "the public recheck records the narrowed argument, not :any"
            (is (nil? (tc/type-check-fn-after-mutation! storage (:id child))))
            (is (= :text (get-in (registry/rich-type-of-id (:id child))
                                 [:args :event])))))
        (finally (sp/close storage))))))


(deftest namespaced-rename-type-keeps-its-identity
  (binding [diag/*diagnostics-override* (atom {})
            types/*type-aliases-override* (atom {})]
    (let [storage (setup/create-test-storage)]
      (try
        (let [left (sp/create-entity storage :ns {:name "left"})
              right (sp/create-entity storage :ns {:name "right"})
              _ (sp/create-entity storage :fn
                                  {:name "event" :namespace-id (:id left)
                                   :constraint {:kind :int}})
              right-type (sp/create-entity storage :fn
                                           {:name "event" :namespace-id (:id right)
                                            :constraint {:kind :text}})
              child (renamed-child! storage (:id right-type))]
          ;; A bare-name lookup would pick the wrong alias. The stored UUID
          ;; unambiguously names right/event regardless of registry order.
          (types/register-type-alias! :event {:kind :int})
          (types/register-type-alias! :left/event {:kind :int})
          (types/register-type-alias! :right/event {:kind :text})
          (is (= {:input {:as :event :type :right/event}}
                 (:args (tc/reconstruct-fn-def storage (:id child)))))
          (is (nil? (tc/type-check-fn-after-mutation! storage (:id child))))
          (is (= {:kind :text}
                 (types/resolve-alias
                   (get-in (registry/rich-type-of-id (:id child)) [:args :event])))))
        (finally (sp/close storage))))))


(deftest declared-return-type-retains-its-namespace
  (binding [diag/*diagnostics-override* (atom {})
            types/*type-aliases-override* (atom {})]
    (let [storage (setup/create-test-storage)]
      (try
        (let [left (sp/create-entity storage :ns {:name "left"})
              right (sp/create-entity storage :ns {:name "right"})
              _ (sp/create-entity storage :fn
                                  {:name "result" :namespace-id (:id left)
                                   :constraint {:payload :int}})
              right-type (sp/create-entity storage :fn
                                           {:name "result" :namespace-id (:id right)
                                            :constraint {:payload :text}})
              base (setup/create-base-fn! storage "return-source" :any)
              slot (setup/create-slot! storage "value" :any)
              child (sp/create-entity storage :fn
                                      {:name "declared-return" :parent-ids [(:id base)]
                                       :return-type-fn-id (:id right-type)})]
          (setup/attach-slot! storage (:id base) (:id slot) 0)
          (setup/bind-value! storage (:id child) (:id slot) {:payload "ok"})
          (registry/record-rich-types-raw! (:id base) :return-source
                                           {:return 'a :args {:value 'a} :effects #{}})
          (types/register-type-alias! :result {:payload :int})
          (types/register-type-alias! :left/result {:payload :int})
          (types/register-type-alias! :right/result {:payload :text})
          (is (= :right/result (:return-type (tc/reconstruct-fn-def storage (:id child)))))
          (is (nil? (tc/type-check-fn-after-mutation! storage (:id child))))
          (is (= {:payload :text}
                 (types/resolve-alias (:return (registry/rich-type-of-id (:id child)))))))
        (finally (sp/close storage))))))


(deftest materialized-type-namespace-keeps-the-registration-contract
  (binding [diag/*diagnostics-override* (atom {})
            types/*type-aliases-override* (atom {})
            cr/*per-org-aliases-override* (atom {})]
    (let [storage (setup/create-test-storage)]
      (try
        (let [nsp (sp/create-entity storage :ns {:name "versioned@1-2-0"})
              type-row (sp/create-entity storage :fn
                                         {:name "result" :namespace-id (:id nsp)
                                          :element-fn-id (get setup/primitive-fn-ids :int)})
              base (setup/create-base-fn! storage "version-source" :any)
              slot (setup/create-slot! storage "value" :any)
              child (sp/create-entity storage :fn
                                      {:name "version-child" :parent-ids [(:id base)]
                                       :return-type-fn-id (:id type-row)})
              view (sp/create-entity storage :slot
                                     {:name "event" :source-slot-id (:id slot)
                                      :type-fn-id (:id type-row)})]
          (setup/attach-slot! storage (:id base) (:id slot) 0)
          (setup/attach-slot! storage (:id child) (:id view) 0)
          (sp/create-entity storage :binding {:fn-id (:id child) :slot-id (:id slot)})
          (registry/record-rich-types-raw! (:id base) :version-source
                                           {:return 'a :args {:value 'a} :effects #{}})
          (cr/refresh-type-registries-from-storage! (exec/create-context {:storage storage}))
          (testing "storage registration intentionally retains its bare alias"
            (is (= [:list :int] (types/resolve-alias :result)))
            (is (not (types/alias-registered? (keyword "versioned@1-2-0" "result")))))
          (testing "both type references use that registered name"
            (is (= {:return-type :result :args {:value {:as :event :type :result}}}
                   (select-keys (tc/reconstruct-fn-def storage (:id child))
                                [:return-type :args]))))
          (is (nil? (tc/type-check-fn-after-mutation! storage (:id child))))
          (is (= [:list :int]
                 (types/resolve-alias
                   (get-in (registry/rich-type-of-id (:id child)) [:args :event])))))
        (finally (sp/close storage))))))
