(ns ^:integration ^:serial graphden.crud.structured-authoring-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.input.access :as access]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.exec]
    [graphden.system.init.packages]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.browser-loopback :as browser]
    [graphden.test-infra.closed-input :as closed]
    [graphden.test-infra.golden-app :as golden]
    [graphden.test-infra.lesson-contact :as contact]
    [graphden.test-infra.lesson-optional :as optional]
    [graphden.test-infra.lesson-profile :as lesson]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [integrant.core :as ig]
    [org.httpkit.client :as http]
    [org.httpkit.server :as server]))


(def ^:private app-packages ["core" "web" "app" "registry" "mcp"])


(use-fixtures :once
  setup/ensure-build-hashes-fixture
  (golden/fixture (ns-name *ns*) app-packages))


(defn- request
  [origin token method uri {:keys [content-type query-string body headers]}]
  (let [response @(http/request {:method method :url (str origin uri (when query-string (str "?" query-string)))
                                 :headers (cond-> (assoc headers "authorization" (str "Bearer " token))
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


(def ^:private profile-ref (ids/fn-id "refprofile.profile" :reference-profile))


(def ^:private profile-ref-admission
  (assoc lesson/admission :module ["refprofile" "profile"] :fn-id profile-ref
         :sha256 "f9a41154524c291215d2b4aff8e33a3c57e389a74b50cca0c2abd7c0304126fe"))


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


(defn- run-profile-name
  [origin token child fields]
  (let [response (request origin token :post "/api/execute"
                          {:content-type "application/json"
                           :body (json/generate-string {:fn-id child :args {:profile (assoc fields :id "run")} :timeout-ms 20000})})]
    (get-in (json/parse-string (:body response) true) [:result :name])))


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
    (is (= "SECOND" (run-profile-name origin token child {})))
    (is (= [id "ref" target false] ((juxt :id :expression-kind :ref-fn-id :value-present) (read-expression))))
    (is (= 409 (:http (command origin token "preview" {:fn-id child :binding-id target-row :action "remove"}))))
    (is (= 409 (:http (command origin token "preview" reference))))
    (let [literal (assoc edit :value "manual")
          basis (preview literal)]
      (is (true? (:committed (change! origin token (assoc current-target :value "third")))))
      (is (= 409 (:http (apply! literal basis))))
      (is (true? (:committed (change! origin token literal))))
      (is (= "manual" (run-profile-name origin token child {})))
      (is (= [id "value" nil "manual" true] ((juxt :id :expression-kind :ref-fn-id :value :value-present) (read-expression)))))
    (is (true? (:committed (change! origin token (assoc edit :ref-fn-id uppercase)))))
    (is (= [id "ref" uppercase nil false] ((juxt :id :expression-kind :ref-fn-id :value :value-present) (read-expression))))
    (retained-reference! origin token storage child id)
    (remove-hidden-reference! origin token storage seams denied child id)
    (is (= "free" (run-profile-name origin token child {:name "free"})))))


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


(defn- exercise-whole-record!
  [origin token storage]
  (let [name "writable-whole-profile"
        created (extend-native origin token name lesson/root)
        owner (:id (first (sp/query-entities storage :fn {:name name})))
        value {:id "23" :name "Ada" :email "ada@example.test"}
        whole {:fn-id owner :action "bind" :path ["profile"] :value value}
        leaf (assoc whole :path ["profile" "name"] :value "Ada")]
    (is (<= 200 (:status created) 299))
    (is (= ["profile"] (get-in (subtree origin token owner) [:fields 0 :bind-path])))
    (let [bound (change! origin token whole)
          binding-id (:binding-id bound)
          operation {:fn-id owner :binding-id binding-id}
          result (request origin token :post "/api/execute"
                          {:content-type "application/json"
                           :body (json/generate-string {:fn-id owner :args {} :timeout-ms 20000})})]
      (is (true? (:committed bound)) (pr-str bound))
      (is (= ["profile"] (get-in (subtree origin token owner) [:occupied 0 :path])))
      (is (= 409 (:http (command origin token "preview" leaf))))
      (is (= value (:result (json/parse-string (:body result) true))))
      (when binding-id
        (let [edit (assoc operation :action "edit" :value (assoc value :name "Bea"))
              edited (change! origin token edit)]
          (is (true? (:committed edited)))
          (is (= "Bea" (get-in (sp/read-entity storage :input-binding (parse-uuid binding-id)) [:value :name])))
          (is (true? (:committed (change! origin token (assoc operation :action "remove")))))))
      (is (true? (:committed (change! origin token leaf))))
      (is (= 409 (:http (command origin token "preview" whole)))))))


(defn- run-profile
  [origin token owner args]
  (let [response (request origin token :post "/api/execute"
                          {:content-type "application/json"
                           :body (json/generate-string {:fn-id owner :args args :timeout-ms 20000})})]
    (is (= 200 (:status response)) (:body response))
    (:result (json/parse-string (:body response) true))))


(defn- exercise-whole-reference!
  [origin token storage]
  (let [name "whole-record-reference-owner"
        created (extend-native origin token name lesson/root)
        owner (:id (first (sp/query-entities storage :fn {:name name})))
        action {:fn-id owner :action "bind" :path ["profile"] :ref-fn-id profile-ref}
        before (subtree origin token owner)
        leaf {:fn-id owner :action "bind" :path ["profile" "name"] :value "occupied"}
        leaf-preview (command origin token "preview" leaf)
        profile {:id "example" :name "Ada" :email "ada@example.test"}
        group-names (fn [node] (mapv :name (:fields node)))
        initial (first (:fields before))]
    (is (<= 200 (:status created) 299))
    (is (= ["profile"] (:bind-path initial)))
    (is (= ["id" "name" "email"] (group-names initial)))
    (is (true? (:ok leaf-preview)))
    (is (= 422 (:http (command origin token "preview" (assoc action :ref-fn-id uppercase)))))
    (is (empty? (sp/query-entities storage :input-binding {:fn-id owner})))
    (let [bound (change! origin token action)
          id (some-> (:binding-id bound) parse-uuid)
          view (subtree origin token owner)
          group (first (:fields view))
          occupied (first (:occupied view))
          change {:fn-id owner :binding-id id}]
      (is (true? (:committed bound)) (pr-str bound))
      (when id
        (let [row (rows/normalize (sp/read-entity storage :input-binding id))]
          (is (= [id owner [] [(ids/slot-id lesson/root :profile)] "ref" profile-ref false]
                 ((juxt :id :fn-id :scope-path :target-path :expression-kind :ref-fn-id :value-present) row)))))
      (is (= [["profile"] (str id)] ((juxt :path :binding-id) occupied)))
      (is (:version-id occupied))
      (is (= ["reference-inputs" ["profile"] ["id" "name" "email"]]
             [(:kind group) (group-names group) (group-names (first (:fields group)))]))
      (is (= 409 (:http (command origin token "preview" leaf))))
      (is (= 409 (:http (command origin token "apply" (assoc leaf :expected-state (:expected-state leaf-preview))))))
      (is (= view (subtree origin token owner)))
      (is (= profile (run-profile origin token owner {:profile {:profile profile}})))
      (when id
        (let [literal (assoc profile :name "edited")
              edited (change! origin token (assoc change :action "edit" :value literal))
              after (subtree origin token owner)]
          (is (= [true (str id)] ((juxt :committed :binding-id) edited)))
          (is (= (str id) (get-in after [:occupied 0 :binding-id])))
          (is (not= (:version-id occupied) (get-in after [:occupied 0 :version-id])))
          (is (empty? (:fields after)))
          (is (= literal (run-profile origin token owner {})))
          (is (true? (:committed (change! origin token (assoc change :action "remove")))))
          (is (nil? (sp/read-entity storage :input-binding id)))
          (is (= before (subtree origin token owner)))
          (is (= profile (run-profile origin token owner {:profile profile}))))))))


(defn- exercise-callable-field!
  [origin token storage]
  (let [template (subtree origin token closed/root)
        name "callable-input-authoring"
        created (extend-native origin token name closed/root)
        owner (:id (first (sp/query-entities storage :fn {:name name})))
        action {:fn-id owner :action "bind" :path ["payload" "callback"] :ref-fn-id uppercase}
        preview (command origin token "preview" action)]
    (is (true? (:extend-available template)) (pr-str template))
    (is (nil? (:run-available template)))
    (is (<= 200 (:status created) 299))
    (is (true? (:ok preview)) (pr-str preview))
    (when (:ok preview)
      (let [bound (command origin token "apply" (assoc action :expected-state (:expected-state preview)))
            view (subtree origin token owner)
            response (request origin token :post "/api/execute"
                              {:content-type "application/json"
                               :body (json/generate-string {:fn-id owner :args {:payload {:item "Ada"}} :timeout-ms 20000})})
            result (json/parse-string (:body response) true)]
        (is (true? (:committed bound)) (pr-str bound))
        (is (= ["item"] (mapv :name (get-in view [:fields 0 :fields]))))
        (is (= "ADA" (:result result)) (pr-str result))))))


(defn- address-routes
  [view side]
  (let [lookup (fn [fields field] (some #(when (= field (:name %)) %) fields))]
    (-> view :fields (lookup side) :fields (lookup "address") :fields (lookup "city") :bind-path)))


(defn- exercise-pair!
  [origin token storage]
  (let [name "lesson-paired-contact-route"
        created (extend-native origin token name contact/root)
        owner (:id (first (sp/query-entities storage :fn {:name name})))
        sender ["sender" "address" "city"]
        recipient ["recipient" "address" "city"]
        bind! (fn [path value]
                (let [args {:fn-id owner :action "bind" :path path :value value}
                      preview (command origin token "preview" args)]
                  (command origin token "apply" (assoc args :expected-state (:expected-state preview)))))]
    (is (<= 200 (:status created) 299) (pr-str created))
    (is (= sender (address-routes (subtree origin token owner) "sender")))
    (is (= recipient (address-routes (subtree origin token owner) "recipient")))
    (let [from (bind! sender "London")
          current (subtree origin token owner)
          to (bind! recipient "Paris")
          result (request origin token :post "/api/execute"
                          {:content-type "application/json"
                           :body (json/generate-string {:fn-id owner :args {:sender {:name "Ada"} :recipient {:name "Bea"}}
                                                        :timeout-ms 20000})})]
      (is (true? (:committed from)) (pr-str from))
      (is (nil? (address-routes current "sender")))
      (is (= recipient (address-routes current "recipient")))
      (is (true? (:committed to)) (pr-str to))
      (is (not= (:binding-id from) (:binding-id to)))
      (is (= "Ada in London → Bea in Paris" (:result (json/parse-string (:body result) true)))
          (pr-str (:body result))))))


(defn- exercise-reference-picker!
  [origin handler token storage ctx]
  (let [owner-name "profile-with-picked-reference"
        created (extend-native origin token owner-name lesson/root)
        owner (:id (first (sp/query-entities storage :fn {:name owner-name})))
        api-cache (ig/init-key :exec/api-routes-js-cache {:context ctx})]
    (try
      (is (<= 200 (:status created) 299))
      (browser/exercise! handler token "tools/browser-test/fixtures/structured-reference-bind.js"
                         owner owner-name uppercase "profile.name" "reference-uppercase")
      (browser/exercise! handler token "tools/browser-test/fixtures/structured-reference-bind.js"
                         owner owner-name profile-ref "profile" "reference-profile")
      (let [view (subtree origin token owner)
            fields (get-in view [:fields 0 :fields])
            name-field (some #(when (= "name" (:name %)) %) fields)]
        (is (empty? (sp/query-entities storage :input-binding {:fn-id owner})))
        (is (empty? (:occupied view)))
        (is (= ["profile" "name"] (:bind-path name-field))))
      (finally (ig/halt-key! :exec/api-routes-js-cache api-cache)))))


(defn- mcp-input!
  [origin token args]
  (let [response (request origin token :post "/mcp"
                          {:content-type "application/json"
                           :body (json/generate-string {:jsonrpc "2.0" :id 71 :method "tools/call"
                                                        :params {:name "configure-input" :arguments args}})})]
    (when (is (= 200 (:status response)) "POST /mcp serves the optional MCP router")
      (let [rpc (json/parse-string (:body response) true)]
        (is (= 71 (:id rpc)))
        (or (:error rpc) (some-> (get-in rpc [:result :content 0 :text]) (json/parse-string true)))))))


(defn- exercise-mcp-input!
  [origin token storage]
  (let [branch-name "mcp-structured-input-proof"
        branch (vs/create-branch! storage branch-name)
        target (vs/switch-branch storage (:id branch))
        name "mcp-profile-config"
        created (request origin token :post "/api/entities/fn"
                         {:content-type "application/x-www-form-urlencoded"
                          :headers {"x-graphden-branch" branch-name}
                          :body (str "name=" name "&parent-ids=" lesson/root)})
        owner (:id (first (sp/query-entities target :fn {:name name})))
        bind-args {:branch branch-name :phase "preview" :action "bind"
                   :fn-id owner :path ["profile" "name"] :value "Ada"}
        propose #(mcp-input! origin token %)
        apply! (fn [params]
                 (let [receipt (propose params)]
                   (is (true? (:ok receipt)) (pr-str receipt))
                   (when (:ok receipt)
                     (propose (merge (assoc params :phase "apply")
                                     (select-keys receipt [:expected-state :expected-version]))))))]
    (is (<= 200 (:status created) 299) (pr-str created))
    (is (= -32602 (:code (propose (assoc bind-args :branch "main")))))
    (is (nil? (sp/read-entity storage :fn owner)))
    (let [bound (apply! bind-args)
          binding-id (:binding-id bound)
          edit (-> bind-args (dissoc :path) (assoc :action "edit" :binding-id binding-id :value "Bea"))]
      (is (true? (:committed bound)) (pr-str bound))
      (when binding-id
        (is (true? (:committed (apply! edit))))
        (is (= "Bea" (:value (sp/read-entity target :input-binding (parse-uuid binding-id)))))
        (is (true? (:committed (apply! (-> edit (dissoc :value) (assoc :action "remove"))))))
        (is (nil? (sp/read-entity target :input-binding (parse-uuid binding-id))))))))


(deftest ordinary-extend-and-named-input-commands
  (let [storage (:storage golden/*bootstrap*)
        packages (ig/init-key :app/packages {:package-names (into app-packages ["lessons" "reftext" "closedinputfixture" "lesson-contact" "refprofile"])
                                             :primitive-inputs [lesson/admission uppercase-admission closed/admission contact/admission profile-ref-admission]})
        {:keys [base-fns]} (ig/init-key :exec/base-fns {:storage storage :packages packages})
        token (str (random-uuid))
        denied (atom nil)
        guard (fn [_ id] (when (= id @denied) (throw (ex-info "Denied" {:type :authz/forbidden}))))
        ctx (exec/create-context {:storage storage :base-fns base-fns :execute-guard guard :auth-provider (auth/single-token-provider token)})
        _ (executor/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response" {:optional-handler-fn-names ["_mcp-ring-response"]})
        router-override (atom app)
        seams (atom (tenancy/seams-isolation-seed))
        handler (bound-fn [req]
                  (binding [tenancy/*seams-override* seams
                            router/*active-router-override* router-override]
                    (update (router/dispatch app req) :headers update-keys name)))
        stop (server/run-server handler {:ip "127.0.0.1" :port 0})]
    (try
      (let [origin (str "http://127.0.0.1:" (:local-port (meta stop)))]
        (exercise-commands! origin token storage seams)
        (exercise-reference! origin token storage seams denied)
        (exercise-callable-field! origin token storage)
        (exercise-mcp-input! origin token storage)
        (exercise-whole-record! origin token storage)
        (exercise-whole-reference! origin token storage)
        (exercise-pair! origin token storage)
        (when (= "1" (System/getenv "GD_STRUCTURED_INPUT_PLAYWRIGHT"))
          (exercise-reference-picker! origin handler token storage ctx)))
      (finally (stop)))))


(defn- exercise-optional!
  [origin token storage]
  (let [created (extend-native origin token "optional-label-child" optional/root)
        _ (is (<= 200 (:status created) 299) (pr-str created))
        child (:id (first (sp/query-entities storage :fn {:name "optional-label-child"})))
        execute! (fn [args]
                   (let [response (request origin token :post "/api/execute"
                                           {:content-type "application/json"
                                            :body (json/generate-string {:fn-id child :args args :timeout-ms 20000})})]
                     (json/parse-string (:body response) true)))
        apply! (fn [command-data]
                 (let [basis (command origin token "preview" command-data)]
                   (is (true? (:ok basis)) (pr-str basis))
                   (command origin token "apply" (merge command-data (select-keys basis [:expected-state :expected-version])))))]
    (when child
      (let [view (subtree origin token child)
            field (first (:fields view))]
        (is (false? (:required-now? field)) (pr-str view))
        (is (= ["label"] (:bind-path field)))
        (is (empty? (:occupied view))))
      (is (= "text: automatic" (:result (execute! {}))))
      (is (= "explicit nil" (:result (execute! {:label nil}))))
      (is (= "text: wire" (:result (execute! {:label "wire"}))))
      (is (contains? #{"failed" "rejected"} (:status (execute! {:label 17}))))
      (let [binding (apply! {:fn-id child :action "bind" :path ["label"] :value nil})
            _ (is (true? (:ok binding)) (pr-str binding))]
        (when-let [id (some-> (:binding-id binding) parse-uuid)]
          (is (= {:value nil :value-present true}
                 (select-keys (sp/read-entity storage :input-binding id) [:value :value-present])))
          (is (= "explicit nil" (:result (execute! {}))))
          (is (= "invalid-structured-arguments" (get-in (execute! {:label "occupied"}) [:error-data :reason])))
          (is (true? (:ok (apply! {:fn-id child :binding-id id :action "remove"}))))
          (is (= "text: automatic" (:result (execute! {})))))))))


(deftest optional-field-remains-bindable-after-defaulted-run
  (let [storage (:storage golden/*bootstrap*)
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "optionalinput"]
                                             :primitive-input optional/admission})
        sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
        {:keys [base-fns]} (sync!)
        input (migration/derived-id :input-type optional/root)
        slot (ids/slot-id optional/root :label)
        member (migration/derived-id :membership optional/root (ids/fn-slot-id optional/root slot))
        versions #(resolution/resolve-version-ids (vs/unwrap storage) %1 %2 (vs/current-branch-id storage))
        snapshot #(hash-map :owners (versions :fn [optional/root input]) :membership (versions :fn-slot [member])
                            :field (select-keys (sp/read-entity storage :slot slot) [:id :type-fn-id :required]))
        before (snapshot)
        token (str (random-uuid))
        ctx (exec/create-context {:storage storage :base-fns base-fns :auth-provider (auth/single-token-provider token)})
        _ (executor/rebuild! ctx)
        router (router/create-router ctx "_app-ring-response")
        handler (bound-fn [req] (update (router/dispatch router req) :headers update-keys name))
        stop (server/run-server handler {:ip "127.0.0.1" :port 0})]
    (try
      (is (= #{optional/root input} (set (keys (:owners before)))))
      (is (= #{member} (set (keys (:membership before)))))
      (is (= {:id slot :type-fn-id (ids/fn-id "core.refinements" :nullable-text) :required false} (:field before)))
      (is (= {:value "automatic"} (:input-default (sp/read-entity storage :fn-slot member))))
      (exercise-optional! (str "http://127.0.0.1:" (:local-port (meta stop))) token storage)
      (sync!)
      (is (= before (snapshot)))
      (finally (stop)))))
