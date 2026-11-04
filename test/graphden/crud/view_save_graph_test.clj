(ns ^:integration ^:serial graphden.crud.view-save-graph-test
  "Save view through the shipped HTTP graph and execute the saved composition.
   Full-app graph execution belongs to integration; small-graph transactional
   save, rollback and type-publication checks remain in view-save-test."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.test-autorun :as autorun]
    [graphden.crud.views :as views]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as vs]))


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


(defn- parse-command
  [body]
  (let [{:keys [ctx storage all-name->id]} ga/*bootstrap*]
    (setup/exec-with-storage ctx storage (get all-name->id :parse-view-command)
                             {:body body})))


(defn- view-command-error
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      ;; The executor may add call-site context around the original rejection.
      (some #(when (= :validation-error/view-command (:type (ex-data %)))
               (ex-data %))
            (take-while some? (iterate ex-cause exception))))))


(deftest graph-command-normalizes-without-losing-presence-or-identity
  (let [view-id (random-uuid)
        parsed (parse-command
                 {:create-id (str view-id)
                  :namespace-id nil
                  :name "  saved name  "
                  :filters {:name "   "
                            :uses [(str view-id) view-id]
                            :views [(str view-id)]
                            :effects [":db" "db"]
                            :kinds [":fn" "fn"]
                            :problems ["lint" ":lint"]
                            :namespaces [" app " "app" " "]
                            :exclude [" secret " ""]
                            :unused false}})]
    (is (= {:create-id view-id :namespace-id nil :name "saved name"
            :filters {:name nil :uses [view-id] :views [view-id]
                      :effects [:db] :kinds [:fn] :problems [:lint]
                      :namespaces ["app"] :exclude ["secret"] :unused false}}
           parsed))
    (is (= {:name "empty" :filters {:name nil :uses [] :views [] :effects []
                                    :kinds [] :problems [] :namespaces []
                                    :exclude [] :unused false}}
           (parse-command {:name "empty" :filters {}})))
    (is (= view-id (:id (parse-command {:id view-id :name "updated" :filters {}}))))
    (is (not (contains? (parse-command {:id false :name "new" :filters {}}) :id)))
    (is (contains? (parse-command {:name "new" :namespace-id false :filters {}})
                   :namespace-id))))


(deftest malformed-command-never-calls-the-atomic-writer
  (let [base {:name "valid" :filters {}}
        view-id (random-uuid)
        cases [[nil "Save view requires a JSON object"]
               [[] "Save view requires a JSON object"]
               [(assoc base :id nil :create-id view-id) "never both"]
               [(dissoc base :filters) "Unknown or missing filter axes"]
               [(assoc base :filters []) "Unknown or missing filter axes"]
               [(assoc base :name 7) "non-empty name"]
               [(assoc base :name "  ") "non-empty name"]
               [(assoc base :name (str/join (repeat 161 "x"))) "160 characters"]
               [(assoc-in base [:filters :also] []) "Unknown or missing filter axes"]
               ;; Preserve the native parser's first rejection when two axes fail.
               [(assoc base :filters {:uses "wrong" :exclude "wrong"})
                ":exclude must contain at most 64 values"]
               [(assoc-in base [:filters :uses] "wrong") "at most 64 values"]
               [(assoc-in base [:filters :uses] (repeat 65 (str view-id))) "at most 64 values"]
               [(assoc-in base [:filters :uses] ["invalid"]) "must be a UUID"]
               [(assoc-in base [:filters :views] [nil]) "must be a UUID"]
               [(assoc-in base [:filters :namespaces] [7]) "at most 512 characters"]
               [(assoc-in base [:filters :effects] ["unknown"]) "Unknown effects value"]
               [(assoc-in base [:filters :effects] ["other/db"]) "Unknown effects value"]
               [(assoc-in base [:filters :kinds] ["unknown"]) "Unknown kinds value"]
               [(assoc-in base [:filters :problems] ["unknown"]) "Unknown problems value"]
               [(assoc-in base [:filters :name] {}) "at most 512 characters"]
               [(assoc-in base [:filters :exclude] [(str/join (repeat 513 "x"))]) "at most 512 characters"]
               [(assoc-in base [:filters :unused] nil) "unused must be a boolean"]
               [(assoc base :id "invalid") "must be a UUID"]
               [(assoc base :create-id nil) "must be a UUID"]
               [(assoc base :create-id false) "must be a UUID"]
               [(assoc base :namespace-id "invalid") "must be a UUID"]]
        {:keys [storage] :as graph} ga/*bootstrap*
        save-calls (atom 0)
        counts #(into {} (map (fn [entity]
                                [entity (count (sp/query-entities (vs/unwrap storage) entity {}))]))
                      [:fn :fn-version :binding :binding-version
                       :binding-list-item :binding-list-item-version])
        before (counts)]
    (with-redefs [views/save!
                  (fn [& _]
                    (swap! save-calls inc)
                    (throw (ex-info "Save must never run for rejected input" {})))]
      (doseq [[body expected] cases]
        (let [error (view-command-error
                      #(setup/via-graph graph :api-view-save-handler
                                        {:uri "/api/views/save" :request-method :post
                                         :headers {"content-type" "application/json"}
                                         :body (json/generate-string body)}))]
          (is (some? error) (str "Expected typed rejection: " expected))
          (is (and (string? (:reason error))
                   (str/includes? (:reason error) expected))
              (str "Expected reason containing: " expected)))))
    ;; save-explorer-view records :db before forcing its command argument.
    ;; This sentinel proves the writer is never called, not an empty effect trace.
    (is (zero? @save-calls) "Rejected commands must never reach the atomic writer")
    (is (= before (counts)) "Rejected commands cannot write identity/version rows")))


(deftest composed-command-refuses-marked-input-before-normalization
  (doseq [marked-type [[:secret :keyword-map]
                       {:private-field [:secret :text]}]]
    (let [source (random-uuid)]
      (registry/record-rich-types-raw! source :view-command-marked-body
                                       {:args {} :return marked-type})
      (let [error (try
                    (check/check-fn-def!
                      {:id (random-uuid) :name :view-command-marked-result
                       :parent :parse-view-command
                       :args {:body :view-command-marked-body}})
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= {:type :types/check-failed :arg :body
                :expected :jsonb :actual marked-type}
               (select-keys error [:type :arg :expected :actual]))
            (str "Wire JSON boundary must refuse marked input " marked-type))))))
