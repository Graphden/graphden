(ns graphden.crud.typed-rename-reconstruction-test
  "Stored rename views must retain their declared type when CRUD rebuilds
   the fn-def for a check, independently of unrelated binding references."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.type-check :as tc]
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
