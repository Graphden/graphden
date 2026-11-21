(ns ^:integration ^:serial graphden.crud.structured-authoring-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.interface :as exec]
    [graphden.input.access :as access]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.golden-app :as golden]
    [graphden.test-infra.lesson-profile :as lesson]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]
    [org.httpkit.client :as http]
    [org.httpkit.server :as server]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(defn- request
  [origin token method uri {:keys [content-type query-string body]}]
  (let [response @(http/request {:method method :url (str origin uri (when query-string (str "?" query-string)))
                                 :headers (cond-> {"authorization" (str "Bearer " token)}
                                            content-type (assoc "content-type" content-type))
                                 :body body :timeout 30000})]
    (when-let [error (:error response)] (throw error))
    response))


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


(defn- exercise-commands!
  [app token storage seams]
  (let [parent lesson/root
        v1 (ids/fn-id "core.arithmetic" :ceil)
        input (migration/derived-id :input-type parent)
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
                row (rows/normalize (sp/read-entity storage :input-binding id))
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
      (let [initial @seams]
        (try
          (binding [tenancy/*seams-override* seams]
            (tenancy/install-browser-source-policy!
              (fn [_] (fn [row & _] (when (= input (:id row)) (throw (ex-info "Denied" {:type :authz/forbidden})))))))
          (is (= 403 (:http (command app token "preview" action))))
          (is (nil? (subtree app token child)))
          (is (>= (:status (extend-native app token "closed-profile-child" parent)) 400))
          (finally (reset! seams initial))))
      (is (empty? (sp/query-entities storage :fn {:name "closed-profile-child"})))
      (let [branch (vs/current-branch-id storage)]
        (sp/update-entity storage :branch branch {:require-merge? true})
        (try
          (is (not (:extend-available (subtree app token parent))))
          (is (>= (:http (command app token "preview" action)) 400))
          (finally (sp/update-entity storage :branch branch {:require-merge? false})))))))


(def ^:private uppercase (ids/fn-id "reftext.upper" :reference-uppercase))


(def ^:private uppercase-admission
  {:module ["reftext" "upper"] :fn-id uppercase :abi-fn-id (ids/fn-id "core.strings" :str-upper)
   :sha256 "eb3f5b79f609345850b5888e3a4d6d064c7eea6ef4b5fb9f1beb514a8ff9b93f"})


(defn- changed-reference-source!
  [origin token storage action]
  (let [name "changeable-uppercase"
        created (extend-native origin token name uppercase)
        target (:id (first (sp/query-entities storage :fn {:name name})))
        proposal (assoc action :ref-fn-id target)
        before (command origin token "preview" proposal)
        literal {:fn-id target :action "bind" :path ["string"] :value "changed"}
        edit (command origin token "preview" literal)]
    (is (<= 200 (:status created) 299))
    (is (true? (:ok before)) (pr-str before))
    (is (true? (:committed (command origin token "apply" (assoc literal :expected-state (:expected-state edit))))))
    (is (= 409 (:http (command origin token "apply" (assoc proposal :expected-state (:expected-state before))))))))


(defn- change!
  [origin token proposal]
  (let [preview (command origin token "preview" proposal)]
    (is (true? (:ok preview)) (pr-str preview))
    (command origin token "apply" (merge proposal (select-keys preview [:expected-state :expected-version])))))


(defn- retained-reference!
  "An older scoped overlay on a descendant may still address the use by UUID."
  [origin token storage owner binding-id]
  (let [input (:input-type-fn-id (sp/read-entity storage :fn owner))
        [descendant dependent] (binding [access/*offline?* true]
                                 (let [child (sp/create-entity storage :fn {:name "stored-profile-descendant"
                                                                            :parent-ids [owner] :input-type-fn-id input})
                                       binding (sp/create-entity storage :input-binding
                                                                 {:fn-id (:id child) :scope-path [binding-id]
                                                                  :target-path [(ids/slot-id uppercase :string)]
                                                                  :expression-kind "value" :value-present true :value "scoped"})]
                                   [(:id child) (:id binding)]))
        proposal {:fn-id owner :binding-id binding-id :action "remove"}]
    (try
      (is (= 403 (:http (command origin token "preview" (assoc proposal :fn-id descendant)))))
      (is (= 409 (:http (command origin token "preview" proposal))))
      (is (= 409 (:http (command origin token "preview" (assoc proposal :action "edit" :value "would orphan")))))
      (is (= uppercase (:ref-fn-id (sp/read-entity storage :input-binding binding-id))))
      (finally
        (binding [access/*offline?* true vs/*tombstone-delete?* true]
          (sp/delete-entity storage :input-binding dependent))))))


(defn- remove-hidden-reference!
  [origin token storage seams denied owner binding-id]
  (let [proposal {:fn-id owner :action "remove" :binding-id binding-id}
        profile (ids/slot-id lesson/root :profile)
        record (:type-fn-id (sp/read-entity storage :slot profile))
        original @seams
        forbidden (atom record)
        reads (atom [])]
    (try
      (binding [tenancy/*seams-override* seams]
        (tenancy/install-browser-source-policy!
          (fn [_]
            (fn [row & _]
              (swap! reads conj (:id row))
              (when (= @forbidden (:id row)) (throw (ex-info "Denied" {:type :authz/forbidden})))))))
      (is (= 403 (:http (command origin token "preview" proposal))))
      (reset! forbidden uppercase)
      (reset! denied uppercase)
      (is (= 403 (:http (command origin token "preview" (assoc proposal :action "edit" :value "hidden")))))
      (reset! reads [])
      (let [preview (command origin token "preview" proposal)
            result (command origin token "apply" (merge proposal (select-keys preview [:expected-state :expected-version])))]
        (is (true? (:ok preview)) (pr-str preview))
        (is (= #{:ok :action :fn-id :binding-id :expected-state :expected-version :http} (set (keys preview))))
        (is (true? (:committed result)) (pr-str result))
        (is (= #{:ok :committed :action :fn-id :binding-id :http} (set (keys result))))
        (is (not-any? #{uppercase} @reads))
        (is (nil? (sp/read-entity storage :input-binding binding-id))))
      (finally (reset! seams original) (reset! denied nil)))))


(defn- exercise-reference-changes!
  [origin token storage seams denied child id]
  (let [target (:id (first (sp/query-entities storage :fn {:name "changeable-uppercase"})))
        target-row (:id (first (sp/query-entities storage :input-binding {:fn-id target})))
        edit {:fn-id child :binding-id id :action "edit"}
        reference (assoc edit :ref-fn-id target)
        preview #(select-keys (command origin token "preview" %) [:expected-state :expected-version])
        apply! #(command origin token "apply" (merge %1 %2))
        expected (preview reference)
        removal (assoc edit :action "remove")
        removal-basis (preview removal)
        read-expression #(-> (sp/read-entity storage :input-binding id)
                             (select-keys [:id :expression-kind :value :ref-fn-id :value-present]))
        current-target {:fn-id target :binding-id target-row :action "edit"}]
    (is (true? (:committed (change! origin token {:fn-id child :action "bind" :path ["profile" "email"] :value "fixed"}))))
    (is (= 409 (:http (apply! reference expected))))
    (is (= 409 (:http (apply! removal removal-basis))))
    (let [basis (preview reference)]
      (is (true? (:committed (change! origin token (assoc current-target :value "second")))))
      (is (= 409 (:http (apply! reference basis)))))
    (is (true? (:committed (change! origin token reference))))
    (is (= [id "ref" target false] ((juxt :id :expression-kind :ref-fn-id :value-present) (read-expression))))
    (is (= 409 (:http (command origin token "preview" {:fn-id child :binding-id target-row :action "remove"}))))
    (is (= 409 (:http (command origin token "preview" reference))))
    (let [literal (assoc edit :value "manual")
          basis (preview literal)]
      (is (true? (:committed (change! origin token (assoc current-target :value "third")))))
      (is (= 409 (:http (apply! literal basis))))
      (is (true? (:committed (change! origin token literal))))
      (is (= [id "value" nil "manual" true] ((juxt :id :expression-kind :ref-fn-id :value :value-present) (read-expression)))))
    (is (true? (:committed (change! origin token (assoc edit :ref-fn-id uppercase)))))
    (is (= [id "ref" uppercase nil false] ((juxt :id :expression-kind :ref-fn-id :value :value-present) (read-expression))))
    (retained-reference! origin token storage child id)
    (remove-hidden-reference! origin token storage seams denied child id)))


(defn- exercise-reference!
  [origin token storage seams denied]
  (let [name "profile-with-uppercase-name"
        created (extend-native origin token name lesson/root)
        child (:id (first (sp/query-entities storage :fn {:name name})))
        action {:fn-id child :path ["profile" "name"] :action "bind" :ref-fn-id uppercase}
        preview (command origin token "preview" action)
        apply-args (assoc action :expected-state (:expected-state preview))
        no-binding? #(empty? (sp/query-entities storage :input-binding {:fn-id child}))]
    (is (<= 200 (:status created) 299))
    (is (true? (:ok preview)) (pr-str preview))
    (is (no-binding?))
    (changed-reference-source! origin token storage action)
    (is (= 400 (:http (command origin token "preview" (assoc action :value nil)))))
    (is (= 403 (:http (command origin token "preview" (assoc action :ref-fn-id (random-uuid))))))
    (is (= 422 (:http (command origin token "preview" (assoc action :ref-fn-id lesson/root)))))
    (reset! denied uppercase)
    (try (is (= 403 (:http (command origin token "apply" apply-args))))
         (finally (reset! denied nil)))
    (let [original @seams]
      (try
        (binding [tenancy/*seams-override* seams]
          (tenancy/install-browser-source-policy!
            (fn [_] (fn [row & _] (when (= uppercase (:id row)) (throw (ex-info "Denied" {:type :authz/forbidden})))))))
        (is (= 403 (:http (command origin token "apply" apply-args))))
        (finally (reset! seams original))))
    (is (no-binding?))
    (let [bound (command origin token "apply" apply-args)
          row (some->> (:binding-id bound) parse-uuid (sp/read-entity storage :input-binding) rows/normalize)
          profile (ids/slot-id lesson/root :profile)
          record (:type-fn-id (sp/read-entity storage :slot profile))
          fields (get-in (subtree origin token child) [:fields 0 :fields])
          group (some #(when (= "name" (:name %)) %) fields)
          args {:profile {:id "a" :email "e" :name {:string "Ada"}}}
          response (request origin token :post "/api/execute"
                            {:content-type "application/json"
                             :body (json/generate-string {:fn-id child :args args :timeout-ms 20000})})
          result (json/parse-string (:body response) true)]
      (is (true? (:committed bound)) (pr-str bound))
      (is (= [child [] [profile (ids/slot-id record :name)] "ref" false uppercase]
             ((juxt :fn-id :scope-path :target-path :expression-kind :value-present :ref-fn-id) row)))
      (is (= ["reference-inputs" ["string"]] [(:kind group) (mapv :name (:fields group))]))
      (is (= 409 (:http (command origin token "preview" action))))
      (is (= 409 (:http (command origin token "apply" apply-args))))
      (is (= {:id "a" :email "e" :name "ADA"} (:result result)) (pr-str result))
      (when (:id row) (exercise-reference-changes! origin token storage seams denied child (:id row))))))


(deftest ordinary-extend-and-named-input-commands
  (let [storage (:storage golden/*bootstrap*)
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "lessons" "reftext"]
                                             :primitive-inputs [lesson/admission uppercase-admission]})
        {:keys [base-fns]} (ig/init-key :exec/base-fns {:storage storage :packages packages})
        token (str (random-uuid))
        denied (atom nil)
        guard (fn [_ id] (when (= id @denied) (throw (ex-info "Denied" {:type :authz/forbidden}))))
        ctx (exec/create-context {:storage storage :base-fns base-fns :execute-guard guard :auth-provider (auth/single-token-provider token)})
        _ (executor/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        seams (atom (tenancy/seams-isolation-seed))
        handler (bound-fn [req]
                  (binding [tenancy/*seams-override* seams]
                    (update (router/dispatch app req) :headers update-keys name)))
        stop (server/run-server handler {:ip "127.0.0.1" :port 0})]
    (try
      (let [origin (str "http://127.0.0.1:" (:local-port (meta stop)))]
        (exercise-commands! origin token storage seams)
        (exercise-reference! origin token storage seams denied))
      (finally (stop)))))
