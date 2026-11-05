(ns ^:integration ^:serial graphden.crud.apply-policy-graph-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.graph-harness :as gh]))


(use-fixtures :once (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(deftest guarded-outcomes-keep-success-and-authority-rejections
  (doseq [outcome [{:created (random-uuid) :type-warnings [{:reason "warning"}]}
                   {:updated (random-uuid) :dependent-type-warning-count 2}
                   {:error "Denied" :http-status 403}
                   {:error "Collision" :http-status 409}
                   {:error "Secret flow refused"}]]
    (is (= outcome (gh/exec-name :_apply-response {:outcome outcome :fallback "Fallback"}))))
  (is (= {:error "Failed to create fn"}
         (gh/exec-name :_apply-response
                       {:outcome {:error :crud/write-failed} :fallback "Failed to create fn"}))))


(deftest safe-rejection-facts-select-wire-status-after-rollback
  (doseq [[facts status] [[{:kind :package} 403] [{:kind :secret-leaf} 403]
                          [{:kind :validation} 400] [{:kind :validation :status 409} 409]
                          [{:kind :storage :duplicate-key? true :status 500} 409]
                          [{:kind :storage :status 404} 404]
                          [{:kind :storage :include-null-status? true} nil]]]
    (is (= {:error "Safe reason" :http-status status}
           (gh/exec-name :_apply-response
                         {:outcome {:error "Safe reason" :write-rejection facts} :fallback "Fallback"}))))
  (is (= {:error "Fallback"}
         (gh/exec-name :_apply-response
                       {:outcome {:error :crud/write-failed :write-rejection {:kind :storage}}
                        :fallback "Fallback"}))))


(deftest form-graphs-assemble-the-exact-atomic-input
  (let [id (random-uuid)
        form {:rename-to "renamed"}
        data {:name "example"}
        args {:entity-type "fn" :entity-data data :type-str "fn" :form-data form}
        calls (atom [])]
    (with-redefs [entities/apply-create-core
                  (fn [parsed _ctx] (swap! calls conj parsed) {:error :crud/write-failed})
                  entities/apply-update-core
                  (fn [parsed _ctx] (swap! calls conj parsed) {:error :crud/write-failed :http-status nil})]
      (is (= {:error "Failed to create fn"} (gh/exec-name :try-apply-create args)))
      (is (= {:error "Failed to update entity" :http-status nil}
             (gh/exec-name :try-apply-update (assoc args :id-uuid id)))))
    (is (= [(assoc args :entity-type :fn) (assoc args :entity-type :fn :id-uuid id)] @calls))))


(deftest sequence-graphs-render-rejections-after-the-atomic-call
  (let [id (random-uuid)
        append {:fn-id id :body {:value 1}}
        update-parsed {:item-id id :body {:value 2}}
        calls (atom [])]
    (with-redefs [entities/apply-seq-append-core
                  (fn [parsed binding _ctx]
                    (swap! calls conj [:append parsed binding])
                    {:error :crud/sequence-binding-changed})
                  entities/apply-seq-update-core
                  (fn [parsed item _ctx]
                    (swap! calls conj [:update parsed item])
                    {:error :crud/sequence-item-missing})
                  entities/apply-seq-move-core
                  (fn [parsed item _ctx]
                    (swap! calls conj [:move parsed item])
                    {:error :crud/sequence-item-missing})]
      (is (= {:error "Sequence binding or inherited slot changed; refresh and retry" :http-status 409}
             (gh/exec-name :try-apply-seq-append {:parsed append :seq-binding nil})))
      (doseq [name [:try-apply-seq-update :try-apply-seq-move]]
        (is (= {:error "Sequence item no longer exists" :http-status 404}
               (gh/exec-name name {:parsed update-parsed :item nil})))))
    (is (= [[:append append nil] [:update update-parsed nil] [:move update-parsed nil]] @calls))))
