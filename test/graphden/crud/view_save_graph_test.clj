(ns ^:integration ^:serial graphden.crud.view-save-graph-test
  "Save view through the shipped HTTP graph and execute the saved composition.
   Full-app graph execution belongs to integration; small-graph transactional
   save, rollback and type-publication checks remain in view-save-test."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.test-autorun :as autorun]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*))
  (fn [f] (binding [autorun/*auto-run?* false] (f))))


(deftest http-save-and-run-use-the-same-complete-filter-set
  (let [{:keys [storage ctx all-name->id] :as graph} ga/*bootstrap*
        post! (fn [body]
                (let [response (setup/via-graph
                                 graph :api-view-save-handler
                                 {:uri "/api/views/save" :request-method :post
                                  :headers {"content-type" "application/json"}
                                  :body (json/generate-string body)})]
                  (is (= 200 (:status response)))
                  (json/parse-string (:body response) true)))
        const-id (get all-name->id :const)
        get-id (get all-name->id :get)
        value-slot (:slot-id (first (sp/query-entities storage :fn-slot {:fn-id const-id})))
        candidate (entities/create-entity :fn {:name "match-http-view" :parent-ids [const-id]} ctx)
        _ (entities/create-entity :binding
                                  {:fn-id (:id candidate) :slot-id value-slot :ref-fn-id get-id
                                   :type-override-fn-id (:fn-ref setup/primitive-fn-ids)} ctx)
        first-view (post! {:name "http-first-view" :filters {:name "match"}})
        second-view (post! {:name "http-second-view" :filters {:name "http"}})
        result (post! {:name "http-combined-view"
                       :filters {:uses [const-id get-id]
                                 :views [(get-in first-view [:view :id]) (get-in second-view [:view :id])]
                                 :name "match-http-view"}})
        saved-id (some-> (get-in result [:view :id]) parse-uuid)]
    (is (true? (:committed result)))
    (is (= 2 (count (get-in result [:view :filters :uses]))))
    (is (= 2 (count (get-in result [:view :filters :also]))))
    (let [stored (entities/view-members ctx (get-in result [:view :filters]))
          direct (entities/view-members ctx {:uses [const-id get-id] :name "match-http-view"})
          run (setup/exec-with-storage ctx storage saved-id {})]
      (is (= #{(:id candidate)} (into #{} (map :id) (:fns direct))) (pr-str direct))
      (is (= #{(:id candidate)} (into #{} (map :id) (:fns stored))) (pr-str stored))
      (is (= #{(:id candidate)} (into #{} (map :id) (:fns run))) (pr-str run)))))
