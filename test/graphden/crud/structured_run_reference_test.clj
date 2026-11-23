(ns ^:integration ^:serial graphden.crud.structured-run-reference-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.crud.structured-run :as run]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.input.access :as access]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.test-infra.golden-app :as golden]
    [integrant.core :as ig]
    [org.httpkit.client :as http]
    [org.httpkit.server :as server]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(defn- arithmetic
  [n]
  (ids/fn-id "core.arithmetic" n))


(defn- stage-native!
  [storage id]
  (let [root (sp/read-entity storage :fn id)
        input (migration/input-type-record root)
        members (sp/query-entities storage :fn-slot {:fn-id id})]
    (sp/create-entity storage :fn input)
    (sp/create-entities storage :fn-slot (mapv #(migration/input-membership-record root %) members))
    (sp/update-entity storage :fn id {:input-type-fn-id (:id input)})))


(defn- install-graph!
  "The native roots retain their shipped implementations and field identities.
   A composed floor reads one of two computed record fields by projection."
  [storage]
  (let [floor (arithmetic :floor)
        ceil (arithmetic :ceil)
        sqrt (arithmetic :sqrt)
        numeric (ids/primitive-fn-id :numeric)
        number (ids/slot-id floor :number)
        [root input record options rounded invalid projection round-use sqrt-use] (repeatedly 9 random-uuid)]
    (binding [access/*offline?* true]
      (tx/in-transaction
        storage
        (fn [st]
          (doseq [base [floor ceil sqrt]] (stage-native! st base))
          (sp/create-entities st :fn [{:id input :name "nested-number-input"}
                                      {:id record :name "nested-number-fields"}])
          (sp/create-entity st :fn {:id root :name "nested-reference-floor" :parent-ids [floor] :input-type-fn-id input})
          (sp/create-entities st :slot [{:id options :name "options" :type-fn-id record :required true}
                                        {:id rounded :name "rounded" :type-fn-id numeric :required true}
                                        {:id invalid :name "invalid" :type-fn-id numeric :required true}])
          (sp/create-entities st :fn-slot [{:fn-id input :slot-id number :position 0}
                                           {:fn-id input :slot-id options :position 1}
                                           {:fn-id record :slot-id rounded :position 0}
                                           {:fn-id record :slot-id invalid :position 1}])
          (sp/create-entities st :input-binding
                              [{:id projection :fn-id root :scope-path [] :target-path [number]
                                :expression-kind "projection" :value-present false :projection-scope-path [] :projection-path [options rounded]}
                               {:id round-use :fn-id root :scope-path [] :target-path [options rounded]
                                :expression-kind "ref" :ref-fn-id ceil :value-present false}
                               {:id sqrt-use :fn-id root :scope-path [] :target-path [options invalid]
                                :expression-kind "ref" :ref-fn-id sqrt :value-present false}
                               {:id (random-uuid) :fn-id root :scope-path [sqrt-use] :target-path [(ids/slot-id sqrt :number)]
                                :expression-kind "value" :value-present true :value -1}]))))
    {:root root :projection projection :invalid-path [options invalid]}))


(defn- install-callback!
  [storage]
  (let [base (ids/fn-id "runfields.native" :run-field-transform)
        upper (ids/fn-id "core.strings" :str-upper)
        substring (ids/fn-id "core.strings" :subs)
        payload (ids/slot-id base :payload)
        record (:type-fn-id (sp/read-entity storage :slot payload))
        callback (ids/slot-id record :callback)
        item (ids/slot-id record :item)
        [root input use-id] (repeatedly 3 random-uuid)]
    (binding [access/*offline?* true]
      (tx/in-transaction
        storage
        (fn [st]
          (doseq [id [base upper substring]] (stage-native! st id))
          (sp/create-entity st :fn {:id input :name "nested-callback-input"})
          (sp/create-entity st :fn {:id root :name "nested-captured-substring" :parent-ids [base] :input-type-fn-id input})
          (sp/create-entities st :fn-slot [{:fn-id input :slot-id payload :position 0}
                                           {:fn-id input :slot-id (ids/slot-id substring :start) :position 1}])
          (sp/create-entities st :input-binding
                              [{:id (random-uuid) :fn-id root :scope-path [] :target-path [payload item]
                                :expression-kind "ref" :value-present false :ref-fn-id upper}
                               {:id use-id :fn-id root :scope-path [] :target-path [payload callback]
                                :expression-kind "ref" :value-present false :ref-fn-id substring}
                               {:id (random-uuid) :fn-id root :scope-path [use-id] :target-path [(ids/slot-id substring :end)]
                                :expression-kind "value" :value-present true :value 4}]))))
    {:root root :unbound base}))


(defn- post!
  [origin token root args]
  (let [response @(http/post (str origin "/api/execute")
                             {:headers {"content-type" "application/json" "authorization" (str "Bearer " token)}
                              :body (json/generate-string {:fn-id root :args args :timeout-ms 30000})
                              :timeout 40000})]
    (when-let [error (:error response)] (throw error))
    (json/parse-string (:body response) true)))


(deftest stored-nested-value-only-runs-when-read
  (let [storage (:storage golden/*bootstrap*)
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "runfields"]})
        {:keys [base-fns]} (ig/init-key :exec/base-fns {:storage storage :packages packages})
        callback (install-callback! storage)
        {:keys [root projection invalid-path]} (install-graph! storage)
        token (str (random-uuid))
        denied (atom nil)
        guard (fn [_ id] (when (= id @denied) (throw (ex-info "Denied" {:type :authz/forbidden}))))
        ctx (exec/create-context {:storage storage :base-fns base-fns :execute-guard guard
                                  :auth-provider (auth/single-token-provider token)})
        _ (runtime/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        handler (bound-fn [request] (update (router/dispatch app request) :headers update-keys name))
        stop (server/run-server handler {:ip "127.0.0.1" :port 0})
        origin (str "http://127.0.0.1:" (:local-port (meta stop)))
        args {:options {:rounded {:number 2.25}}}]
    (try
      (let [call-args (fn [start] {:payload {:item {:string "graph"}} :start start})
            unbound (post! origin token (:unbound callback) {:payload {:item "x" :callback {:ref (:root callback)}}})]
        (is (fn? (:invoke (run/plan ctx (:root callback) (call-args 1)))))
        (doseq [[start expected] [[1 "RAP"] [2 "AP"]]]
          (let [response (post! origin token (:root callback) (call-args start))]
            (is (= expected (:result response)) (pr-str response))))
        (is (= "structured-input-unavailable" (get-in unbound [:error-data :reason])))
        (let [injected (assoc-in (call-args 1) [:payload :callback] {:ref (:unbound callback)})]
          (is (= "invalid-structured-arguments"
                 (get-in (post! origin token (:root callback) injected) [:error-data :reason])))))
      (let [response (post! origin token root args)]
        (is (= 3 (:result response)) (pr-str response)))
      (let [{:keys [invoke]} (run/plan ctx root args)]
        (reset! denied (arithmetic :ceil))
        (is (= "forbidden" (get-in (post! origin token root args) [:error-data :reason])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Denied" (invoke args ctx)))
        (reset! denied nil))
      (is (= "invalid-structured-arguments"
             (get-in (post! origin token root {:options {:rounded {:ref (arithmetic :ceil)}}}) [:error-data :reason])))
      (binding [access/*offline?* true]
        (sp/update-entity storage :input-binding projection {:projection-path invalid-path}))
      (let [{:keys [invoke]} (run/plan ctx root args)
            response (post! origin token root args)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arithmetic result is NaN" (invoke args ctx)))
        (is (= "failed" (:status response)) (pr-str response)))
      (finally (stop)))))
