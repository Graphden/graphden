(ns ^:integration ^:serial graphden.packages.profile-input-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.input.migration :as migration]
    [graphden.packages.owned :as owned]
    [graphden.packages.primitive-input :as primitive]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.test-infra.golden-app :as golden]
    [graphden.test-infra.lesson-profile :as lesson]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [integrant.core :as ig]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(deftest closed-profile-requires-the-exact-operator-contract
  (doseq [contract [nil {:profile {:id :text :name :text}}
                    {:profile {:id :int :name :text :email :text}}
                    {:person {:id :text :name :text :email :text}}]]
    (is (thrown? clojure.lang.ExceptionInfo
          (primitive/read-module "lessons" "structured-input" (assoc lesson/admission :record-contract contract))))))


(deftest optional-profile-package-is-a-runnable-versioned-parent
  (let [storage (:storage golden/*bootstrap*)
        root lesson/root
        input (migration/derived-id :input-type root)
        slot (ids/slot-id root :profile)
        record (ids/anonymous-fn-id (ids/shape-hash (get-in lesson/admission [:record-contract :profile])))
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "lessons"]
                                             :primitive-input lesson/admission})
        sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
        {:keys [base-fns]} (sync!)
        versions #(resolution/resolve-version-ids (vs/unwrap storage) :fn [root input record] (vs/current-branch-id storage))
        original (versions)
        token (str (random-uuid))
        deny? (atom false)
        guard (fn [_ id] (when (and @deny? (= id root)) (throw (ex-info "Denied" {:type :authz/forbidden}))))
        ctx (exec/create-context {:storage storage :base-fns base-fns :auth-provider (auth/single-token-provider token)
                                  :execute-guard guard})
        _ (runtime/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        request (fn [req]
                  (let [response (router/dispatch app (assoc req :headers {"content-type" "application/json"
                                                                           "authorization" (str "Bearer " token)}))]
                    (json/parse-string (:body response) true)))
        view #(let [response (request {:request-method :get :uri "/api/graph/entities"
                                       :query-string (str "scope=subtree&root-id=" root)})]
                (some (fn [r] (when (= (str root) (:id r)) (:structured-input r))) (:fns response)))
        post #(request {:request-method :post :uri "/api/execute"
                        :body (json/generate-string {:fn-id root :args {:profile %} :timeout-ms 30000})})
        profile {:id "42" :name "Ada" :email "ada@example.test"}]
    (is (owned/owned-impl? root (:lesson-profile base-fns)))
    (is (every? owned/owned-fn-id? [root input record]))
    (is (= #{root input record} (set (keys original))))
    (is (= input (:input-type-fn-id (sp/read-entity storage :fn root))))
    (is (= record (:return-type-fn-id (sp/read-entity storage :fn root)) (:type-fn-id (sp/read-entity storage :slot slot))))
    (let [member (ids/fn-slot-id root slot)]
      (is (= slot (:slot-id (sp/read-entity storage :fn-slot member))
             (:slot-id (sp/read-entity storage :fn-slot (migration/derived-id :membership root member))))))
    (is (= (mapv #(ids/slot-id record %) [:id :name :email])
           (mapv :slot-id (sort-by :position (sp/query-entities storage :fn-slot {:fn-id record})))))
    (is (true? (:run-available (view))))
    (is (= profile (:result (post profile))))
    (is (= "failed" (:status (post (dissoc profile :email)))))
    (is (= "failed" (:status (post (assoc profile :id 42)))))
    (is (= "invalid-structured-arguments" (get-in (post (assoc profile :extra "no")) [:error-data :reason])))
    (reset! deny? true)
    (is (not (:run-available (view))))
    (is (= "forbidden" (get-in (post profile) [:error-data :reason])))
    (sync!)
    (is (= original (versions)))))
