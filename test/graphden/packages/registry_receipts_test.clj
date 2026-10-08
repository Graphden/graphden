(ns ^{:cost :heavy} graphden.packages.registry-receipts-test
  "Committed package receipts and UUID-conditional cleanup through real graph handlers."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.registry-fixture :as rf :refer [*bootstrap* run-named storage]]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.seams :as ts]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once ts/isolated-seams-fixture (rf/bootstrap-fixture "registry-receipts-test"))


(defn- delete-request
  [params]
  {:request-method :delete :query-params params :headers {}})


(defn- publish-version!
  [name version value]
  (run-named "publish-package"
             {:pkg-name name :pkg-version version
              :bundle {:namespace name :namespaces [name]
                       :fns [{:namespace name :name :receipt-leaf :parent :const :args {:value value}}]
                       :dependencies [] :package-dependencies [] :secrets [] :secret-paths-included? false}}))


(deftest install-update-receipts-describe-actual-created-namespace-and-written-pin
  (let [name "receipt-demo"
        first-release (publish-version! name "1.0.0" 1)
        second-release (publish-version! name "1.0.1" 2)
        installed (run-named "install-package" {:pkg-name name :pkg-version "1.0.0"})
        namespaces (:created-namespaces installed)
        pin (:pin installed)]
    (is (true? (:ok installed)))
    (is (= (:id first-release) (str (:package-version-id installed))))
    (is (= (:content-hash first-release) (:content-hash installed)))
    (is (= ["receipt-demo@1-0-0"] (mapv :name namespaces)))
    (doseq [row namespaces]
      (is (= row (select-keys (sp/read-entity (storage) :ns (:id row)) [:id :name :parent-id]))))
    (is (= pin (select-keys (sp/read-entity (storage) :package-install (:id pin))
                            [:id :package-name :version :branch-id])))
    (is (= (str (:id pin)) (:id (first (filter #(= name (:package-name %))
                                               (run-named "list-installed-packages" {}))))))
    (let [retry (run-named "install-package" {:pkg-name name :pkg-version "1.0.0"})
          updated (run-named "update-package-version" {:pkg-name name :pkg-version "1.0.1"})
          rolled-back (run-named "update-package-version" {:pkg-name name :pkg-version "1.0.0"})]
      (is (empty? (:created-namespaces retry)) "idempotent install claims no existing namespace")
      (is (= (:id pin) (get-in retry [:pin :id])))
      (is (= ["receipt-demo@1-0-1"] (mapv :name (:created-namespaces updated))))
      (is (= (:id second-release) (str (:package-version-id updated))))
      (is (= (:id pin) (get-in updated [:pin :id]) (get-in rolled-back [:pin :id])))
      (is (empty? (:created-namespaces rolled-back)) "rollback reuses its prior materialization"))))


(deftest cleanup-with-wrong-uuid-cannot-remove-a-pin-or-release
  (let [name "receipt-cleanup"
        release (publish-version! name "1.0.0" 1)
        installed (run-named "install-package" {:pkg-name name :pkg-version "1.0.0"})
        pin-id (get-in installed [:pin :id])
        wrong (str (random-uuid))
        uninstall (fn [id]
                    (setup/via-graph *bootstrap* :_uninstall-handler
                                     (delete-request {"name" name "expected-id" (str id)})))
        withdraw (fn [id]
                   (setup/via-graph *bootstrap* :withdraw-package-handler
                                    (delete-request {"name" name "version" "1.0.0"
                                                     "expected-id" (str id)})))]
    (is (= 409 (:status (uninstall wrong))))
    (is (some? (sp/read-entity (storage) :package-install pin-id)))
    (is (= 200 (:status (uninstall pin-id))))
    (is (nil? (sp/read-entity (storage) :package-install pin-id)))
    (let [rejected (withdraw wrong)]
      (is (= 409 (:status rejected)))
      (is (= "identity-changed" (:reason (json/parse-string (:body rejected) true)))))
    (is (some? (sp/read-entity (storage) :package-version (parse-uuid (:id release)))))
    (is (= 200 (:status (withdraw (:id release)))))
    (is (nil? (sp/read-entity (storage) :package-version (parse-uuid (:id release)))))))


(defn- bootstrap-on
  [branch-id]
  (let [view (vs/switch-branch (storage) branch-id)]
    (assoc *bootstrap* :storage view
           :ctx (assoc (:ctx *bootstrap*) :storage view :graph-cache (atom nil)))))


(defn- handler-body
  [bootstrap handler request]
  (let [response (setup/via-graph bootstrap handler request)]
    (is (= 200 (:status response)) (str handler " " (:body response)))
    (json/parse-string (:body response) true)))


(defn- create-sibling!
  [name]
  (:branch (handler-body *bootstrap* :create-branch-handler
                         (rf/publish-req {:name name :id (str (random-uuid))}))))


(defn- raw-lifecycle-counts
  []
  (let [raw (vs/unwrap (storage))]
    (into {} (map (fn [entity] [entity (count (sp/query-entities raw entity {}))]))
          [:branch :fn :fn-version :fn-slot :fn-slot-version :binding :binding-version
           :slot :ns :package-install :package-version])))


(defn- lifecycle-consumer!
  [view namespace-id reference-id]
  (let [to-str (get (:all-name->id *bootstrap*) :to-str)
        slot (:slot-id (first (sp/query-entities view :fn-slot {:fn-id to-str})))
        owner (sp/create-entity view :fn {:name "welcome" :namespace-id namespace-id
                                          :parent-ids [to-str]})]
    (sp/create-entity view :binding {:fn-id (:id owner) :slot-id slot :ref-fn-id reference-id})))


(defn- release-from-source!
  [bootstrap version]
  (handler-body bootstrap :publish-package-handler
                (rf/publish-req {:name "tutorial-greet" :version version
                                 :ns-root "tutorial-greetings"})))


(defn- cleanup-lifecycle!
  [consumer author pin releases namespaces]
  (let [consumer-id (parse-uuid (:id consumer))
        on-consumer (bootstrap-on consumer-id)]
    ;; The ordinary uninstall endpoint returns the refreshed HTMX panel,
    ;; not a JSON envelope. Its decisive outcome is exact pin removal.
    (let [response (setup/via-graph on-consumer :_uninstall-handler
                                    (delete-request {"name" "tutorial-greet"
                                                     "expected-id" (str (:id pin))}))]
      (is (= 200 (:status response)))
      (is (re-find #"text/html"
                   (or (some (fn [[key value]]
                               (when (= "content-type" (str/lower-case (name key))) value))
                             (:headers response)) "")))
      (is (nil? (sp/read-entity (:storage on-consumer) :package-install (:id pin)))))
    ;; Use the public graph handler over VersionedStorage, not physical SQL
    ;; deletion. This is what frees branch-born identities and tenant quota.
    (doseq [branch [consumer author]]
      (is (true? (:ok (handler-body *bootstrap* :delete-branch-handler
                                    {:uri (str "/api/branches/" (:id branch))
                                     :request-method :delete :headers {}})))))
    (doseq [release releases]
      (is (true? (:ok (handler-body *bootstrap* :withdraw-package-handler
                                    (delete-request {"name" "tutorial-greet"
                                                     "version" (:version release)
                                                     "expected-id" (:id release)}))))))
    (doseq [ns-row (reverse namespaces)]
      (let [response (setup/via-graph *bootstrap* :process-delete-entity
                                      {:uri (str "/api/entities/ns/" (:id ns-row))
                                       :request-method :delete :headers {}})]
        (is (= 200 (:status response)) (str "Exact empty namespace cleanup: " (:body response)))))))


(deftest two-owned-siblings-repeat-install-update-rollback-and-cleanup-without-identity-leaks
  (let [before (raw-lifecycle-counts)
        const-id (get (:all-name->id *bootstrap*) :const)
        value-slot (:slot-id (first (sp/query-entities (storage) :fn-slot {:fn-id const-id})))]
    (dotimes [_ 2]
      (let [author (create-sibling! "tutorial-vendor")
            consumer (create-sibling! "tutorial-site")
            author-bootstrap (bootstrap-on (parse-uuid (:id author)))
            consumer-bootstrap (bootstrap-on (parse-uuid (:id consumer)))
            author-view (:storage author-bootstrap)
            consumer-view (:storage consumer-bootstrap)
            source-ns (sp/create-entity author-view :ns {:name "tutorial-greetings"})
            source (sp/create-entity author-view :fn {:name "greet" :namespace-id (:id source-ns)
                                                      :parent-ids [const-id]})
            value (sp/create-entity author-view :binding {:fn-id (:id source) :slot-id value-slot :value 1})
            first-release (release-from-source! author-bootstrap "1.0.0")
            installed (exec/execute-by-name (:ctx consumer-bootstrap) "install-package"
                                            {:pkg-name "tutorial-greet" :pkg-version "1.0.0"})
            first-ns (first (:created-namespaces installed))
            first-ref (:id (first (sp/query-entities consumer-view :fn {:namespace-id (:id first-ns)})))
            shop (sp/create-entity consumer-view :ns {:name "tutorial-shop"})
            caller (lifecycle-consumer! consumer-view (:id shop) first-ref)]
        (is (true? (:ok first-release)))
        (is (true? (:ok installed)))
        (is (= "tutorial-greetings@1-0-0" (:name first-ns)))
        (is (nil? (sp/read-entity consumer-view :fn (:id source))) "Sibling sees no later author version")
        (sp/update-entity author-view :binding (:id value) {:value 2})
        (let [second-release (release-from-source! author-bootstrap "1.0.1")
              invoke #(exec/execute-by-name (:ctx consumer-bootstrap) "update-package-version"
                                            {:pkg-name "tutorial-greet" :pkg-version %})
              updated (invoke "1.0.1")
              second-ns (first (:created-namespaces updated))
              second-ref (:id (first (sp/query-entities consumer-view :fn {:namespace-id (:id second-ns)})))]
          (is (= second-ref (:ref-fn-id (sp/read-entity consumer-view :binding (:id caller)))))
          (is (= 2 (:value (first (sp/query-entities consumer-view :binding {:fn-id second-ref})))))
          (is (= 1 (:rewritten-refs updated)))
          (is (empty? (:created-namespaces (invoke "1.0.0"))) "Rollback claims no pre-existing namespace")
          (is (= first-ref (:ref-fn-id (sp/read-entity consumer-view :binding (:id caller)))))
          (is (empty? (:created-namespaces (invoke "1.0.1"))))
          (is (= second-ref (:ref-fn-id (sp/read-entity consumer-view :binding (:id caller)))))
          (is (= (:id (:pin installed)) (:id (:pin updated))))
          (cleanup-lifecycle! consumer author (:pin updated)
                              [first-release second-release] [source-ns shop first-ns second-ns])
          (is (= before (raw-lifecycle-counts))
              "Same branch/package/namespace/function names can repeat with baseline physical counts"))))))


(deftest branch-create-id-is-create-only-and-supports-exact-lost-response-cleanup
  (let [id (random-uuid)
        counts-before (raw-lifecycle-counts)
        created (create-sibling! "receipt-existing-branch")
        existing-id (:id created)
        existing-row (sp/read-entity (storage) :branch (parse-uuid existing-id))
        counts-with-existing (raw-lifecycle-counts)
        rejected (setup/via-graph *bootstrap* :create-branch-handler
                                  (rf/publish-req {:name "must-not-adopt-existing" :id existing-id}))]
    (is (= 409 (:status rejected)))
    (is (false? (:ok (json/parse-string (:body rejected) true))))
    (is (= existing-row (sp/read-entity (storage) :branch (parse-uuid existing-id))))
    (is (= counts-with-existing (raw-lifecycle-counts)) "No adopted identity or appended versions")
    (let [malformed (setup/via-graph *bootstrap* :create-branch-handler
                                     (rf/publish-req {:name "must-not-create-malformed" :id "wrong"}))]
      (is (= 400 (:status malformed)))
      (is (= counts-with-existing (raw-lifecycle-counts))))
    ;; The library guard repeats the no-existing check inside the writer,
    ;; including when a caller does not use HTTP's earlier validation.
    (try
      (vs/create-branch! (storage) "must-not-revive" {:id (parse-uuid existing-id)})
      (is false "Expected a create-only identity refusal")
      (catch clojure.lang.ExceptionInfo error
        (is (= :constraint-violation/branch-id-exists (:type (ex-data error))))))
    ;; Simulate a committed create with a lost response: only the pre-staged
    ;; UUID/name/base is available for the fresh read and exact deletion.
    (setup/via-graph *bootstrap* :create-branch-handler
                     (rf/publish-req {:name "receipt-lost-reply" :id (str id)}))
    (let [listed (:branches (handler-body *bootstrap* :list-branches-handler
                                          {:request-method :get :headers {}}))
          row (first (filter #(= (str id) (:id %)) listed))]
      (is (= "receipt-lost-reply" (:name row)))
      (is (= (str (vs/current-branch-id (storage))) (:base-branch-id row))))
    (doseq [branch-id [id (parse-uuid existing-id)]]
      (is (true? (:ok (handler-body *bootstrap* :delete-branch-handler
                                    {:uri (str "/api/branches/" branch-id)
                                     :request-method :delete :headers {}})))))
    (is (= counts-before (raw-lifecycle-counts)))))
