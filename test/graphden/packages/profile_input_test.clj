(ns ^:integration ^:serial graphden.packages.profile-input-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.input.browser :as browser]
    [graphden.input.compile :as compile]
    [graphden.input.migration :as migration]
    [graphden.input.read-view :as view]
    [graphden.packages.owned :as owned]
    [graphden.packages.primitive-input :as primitive]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.closed-input :as closed]
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


(deftest pinned-package-boot-keeps-nested-callable-identity-and-contract
  (let [storage (:storage golden/*bootstrap*)
        root closed/root
        input (migration/derived-id :input-type root)
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "closedinputfixture"]
                                             :primitive-input closed/admission})
        sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
        {:keys [base-fns]} (sync!)
        payload (sp/read-entity storage :slot (ids/slot-id root :payload))
        record (:type-fn-id payload)
        field (sp/read-entity storage :slot (ids/slot-id record :callback))
        callable (:type-fn-id field)
        versioned-ids [root input record callable]
        versions #(resolution/resolve-version-ids (vs/unwrap storage) :fn versioned-ids (vs/current-branch-id storage))
        before (versions)]
    (is (identical? (:closed-apply-text base-fns) (:fixture-closed-apply-text base-fns)))
    (is (owned/owned-impl? root (:fixture-closed-apply-text base-fns)))
    (is (= input (:input-type-fn-id (sp/read-entity storage :fn root))))
    (is (= closed/callback (:constraint (sp/read-entity storage :fn callable))))
    (is (= (set versioned-ids) (set (keys before))))
    (sync!)
    (is (= before (versions)))))


(defn- stored-browser-rejections
  [storage base-fns root]
  (let [ctx (view/collect storage root (tenancy/browser-source-policy storage))
        signature (get (::owned/native-signatures (meta base-fns)) root)
        trusted {root {:impl (get base-fns (:name signature)) :signature signature}}
        {:keys [analysis implementations]} (compile/analyze-stored ctx root trusted)
        refusal (fn [visible?]
                  (try (browser/export-named analysis {:context ctx :snapshot-id (:snapshot-id ctx)
                                                       :implementations implementations :allow-fn? visible?})
                       nil
                       (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    [(refusal #(= root %)) (refusal #(contains? (:fns ctx) %))]))


(deftest pinned-defaults-follow-their-own-record-occurrences
  (let [storage (:storage golden/*bootstrap*)
        root (ids/fn-id "nesteddefaultfixture.structured" :defaulted-label-pair)
        shape {:id :text :label [:union :null :text]}
        admission {:module ["nesteddefaultfixture" "structured"] :fn-id root
                   :abi-fn-id (ids/fn-id "nesteddefaultfixture.native" :describe-label-pair)
                   :sha256 "f1189b8270cc11cc7bdf3e5ec3159323b99d864c2abedc78f0eb54ea3e3fb7e8"
                   :record-contract {:left shape :right shape}}
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "nesteddefaultfixture"]
                                             :primitive-input admission})
        sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
        {:keys [base-fns]} (sync!)
        slots (mapv #(ids/slot-id root %) [:left :right])
        types (mapv #(:type-fn-id (sp/read-entity storage :slot %)) slots)
        record (first types)
        label (ids/slot-id record :label)
        members (mapv #(migration/derived-id :membership root (ids/fn-slot-id root %)) slots)
        shared (ids/fn-slot-id record label)
        versions #(resolution/resolve-version-ids (vs/unwrap storage) :fn-slot (conj members shared) (vs/current-branch-id storage))
        before (versions)
        token (str (random-uuid))
        ctx (exec/create-context {:storage storage :base-fns base-fns :auth-provider (auth/single-token-provider token)})
        _ (runtime/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        post #(-> (router/dispatch app {:request-method :post :uri "/api/execute"
                                        :headers {"authorization" (str "Bearer " token) "content-type" "application/json"}
                                        :body (json/generate-string {:fn-id root :args % :timeout-ms 20000})})
                  :body (json/parse-string true))]
    (is (= [:browser-field-default-unsupported :unsupported-browser-primitive]
           (stored-browser-rejections storage base-fns root)))
    (is (= [record record] types))
    (is (= [(str label) (str label)] (mapv #(get-in (sp/read-entity storage :fn-slot %) [:input-default :target-path 0]) members)))
    (is (= ["Ada" "Bea"] (mapv #(get-in (sp/read-entity storage :fn-slot %) [:input-default :value]) members)))
    (is (nil? (:input-default (sp/read-entity storage :fn-slot shared))))
    (is (= "absent|absent" (:result (post {}))))
    (is (= "Ada|Bea" (:result (post {:left {:id "1"} :right {:id "2"}}))))
    (is (= "nil|given" (:result (post {:left {:id "1" :label nil} :right {:id "2" :label "given"}}))))
    (is (= "failed" (:status (post {:right {}}))))
    (is (= "failed" (:status (post {:right {:id "2" :label 7}}))))
    (is (= (set (conj members shared)) (set (keys before))))
    (sync!)
    (is (= before (versions)))))
