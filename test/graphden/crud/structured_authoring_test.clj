(ns ^:integration ^:serial graphden.crud.structured-authoring-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.interface :as exec]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.golden-app :as golden]
    [graphden.test-infra.lesson-profile :as lesson]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(defn- request
  [app token method uri params]
  (router/dispatch app (merge {:request-method method :uri uri :headers {"authorization" (str "Bearer " token)}}
                              (if-let [content (:content-type params)]
                                {:headers {"authorization" (str "Bearer " token) "content-type" content} :body (:body params)}
                                params))))


(defn- extend-native
  [app token name parent]
  (request app token :post "/api/entities/fn"
           {:content-type "application/x-www-form-urlencoded" :body (str "name=" name "&parent-ids=" parent)}))


(defn- command
  [app token phase payload]
  (let [response (request app token :post (str "/api/structured-input/" phase)
                          {:content-type "application/json" :body (json/generate-string payload)})]
    (assoc (json/parse-string (:body response) true) :http (:status response))))


(defn- subtree
  [app token id]
  (let [response (request app token :get "/api/graph/entities" {:query-string (str "scope=subtree&root-id=" id)})]
    (some #(when (= (str id) (:id %)) (:structured-input %))
          (:fns (json/parse-string (:body response) true)))))


(deftest ordinary-extend-and-named-input-commands
  (let [storage (:storage golden/*bootstrap*)
        parent lesson/root
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "lessons"]
                                             :primitive-input lesson/admission})
        {:keys [base-fns]} (ig/init-key :exec/base-fns {:storage storage :packages packages})
        input (migration/derived-id :input-type parent)
        v1 (ids/fn-id "core.arithmetic" :ceil)
        token (str (random-uuid))
        ctx (exec/create-context {:storage storage :base-fns base-fns :auth-provider (auth/single-token-provider token)})
        _ (executor/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        created (extend-native app token "writable-profile-child" parent)
        old (extend-native app token "legacy-ceil-child" v1)
        child (:id (first (sp/query-entities storage :fn {:name "writable-profile-child"})))
        path ["profile" "name"]
        action {:fn-id child :action "bind" :path path :value "Ada"}
        donor-view (subtree app token parent)]
    (is (true? (:extend-available donor-view)) (pr-str donor-view))
    (is (contains? donor-view :extend-namespace-id))
    (is (nil? (:extend-namespace-id donor-view)))
    (is (<= 200 (:status created) 299) (pr-str created))
    (is (<= 200 (:status old) 299) (pr-str old))
    (is (= input (:input-type-fn-id (sp/read-entity storage :fn child))))
    (is (nil? (:input-type-fn-id (first (sp/query-entities storage :fn {:name "legacy-ceil-child"})))))
    (when child
      (let [leaf #(some (fn [field] (when (= "name" (:name field)) field)) (get-in % [:fields 0 :fields]))]
        (is (= path (:bind-path (leaf (subtree app token child)))))
        (is (nil? (:bind-path (leaf donor-view)))))
      (let [preview (command app token "preview" action)
            _ (is (true? (:ok preview)) (pr-str preview))
            args (assoc action :expected-state (:expected-state preview))
            bound (command app token "apply" args)
            _ (is (true? (:ok bound)) (pr-str bound))
            binding-id (:binding-id bound)]
        (when binding-id
          (let [id (parse-uuid binding-id)
                row (sp/read-entity storage :input-binding id)
                profile (ids/slot-id parent :profile)
                record (:type-fn-id (sp/read-entity storage :slot profile))
                edit {:fn-id child :binding-id binding-id :action "edit" :value "Bea"}
                removal (dissoc (assoc edit :action "remove") :value)
                edit-basis (command app token "preview" edit)
                stale-remove (command app token "preview" removal)
                expected #(select-keys % [:expected-state :expected-version])]
            (is (= [child [profile (ids/slot-id record :name)] "Ada"] ((juxt :fn-id :target-path :value) row)))
            (is (= binding-id (get-in (subtree app token child) [:occupied 0 :binding-id])))
            (is (= 409 (:http (command app token "apply" args))))
            (is (= 403 (:http (command app token "preview" (assoc action :fn-id parent)))))
            (is (= 403 (:http (command app token "preview" (assoc action :fn-id v1)))))
            (is (true? (:ok (command app token "apply" (merge edit (expected edit-basis))))))
            (is (= "Bea" (:value (sp/read-entity storage :input-binding id))))
            (is (= 409 (:http (command app token "apply" (merge removal (expected stale-remove))))))
            (is (true? (:ok (command app token "apply" (merge removal (expected (command app token "preview" removal)))))))
            (is (nil? (sp/read-entity storage :input-binding id))))))
      (is (>= (:status (extend-native app token "mixed-profile-child" (str parent "," v1))) 400))
      (is (empty? (sp/query-entities storage :fn {:name "mixed-profile-child"})))
      (is (>= (:status (request app token :post "/api/entities/fn"
                                {:content-type "application/x-www-form-urlencoded"
                                 :body (str "name=forged-input-child&parent-ids=" parent "&input-type-fn-id=" v1)})) 400))
      (is (empty? (sp/query-entities storage :fn {:name "forged-input-child"})))
      (is (>= (:status (request app token :post "/api/entities/input-binding"
                                {:content-type "application/x-www-form-urlencoded" :body (str "fn-id=" child)})) 400))
      (is (= 401 (:status (request app "unauthorized" :post "/api/structured-input/preview"
                                   {:content-type "application/json" :body (json/generate-string action)}))))
      (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
        (tenancy/install-browser-source-policy!
          (fn [_] (fn [row & _] (when (= input (:id row)) (throw (ex-info "Denied" {:type :authz/forbidden}))))))
        (is (= 403 (:http (command app token "preview" action))))
        (is (nil? (subtree app token child)))
        (is (>= (:status (extend-native app token "closed-profile-child" parent)) 400)))
      (is (empty? (sp/query-entities storage :fn {:name "closed-profile-child"})))
      (let [branch (vs/current-branch-id storage)]
        (sp/update-entity storage :branch branch {:require-merge? true})
        (try
          (is (not (:extend-available (subtree app token parent))))
          (is (>= (:http (command app token "preview" action)) 400))
          (finally (sp/update-entity storage :branch branch {:require-merge? false})))))))
