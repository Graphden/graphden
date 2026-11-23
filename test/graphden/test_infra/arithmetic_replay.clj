(ns graphden.test-infra.arithmetic-replay
  "Disposable PostgreSQL half of the pinned arithmetic source replay."
  (:require
    [cheshire.core :as json]
    [graphden.auth.provider :as auth]
    [graphden.crud.structured-run :as run]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as executor]
    [graphden.input.compile :as compile]
    [graphden.input.migration :as migration]
    [graphden.input.read-view :as view]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.test-infra.golden-app :as golden]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


(defn- expect!
  [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Stored arithmetic replay changed" {:expected expected :actual actual}))))


(defn- stored-result
  [ctx name args]
  (let [owner (ids/fn-id "core.arithmetic" name)
        natives (:base-fns ctx)
        impl (get natives name)
        signature (get (::owned/native-signatures (meta natives)) owner)]
    (expect! true (owned/owned-impl? owner impl))
    (source/with-snapshot (:storage ctx)
                          (fn [storage authorize!]
                            (let [frozen (view/collect storage owner authorize!)
                                  prepared (compile/compile-stored frozen owner {owner {:impl impl :signature signature}})]
                              ((:invoke prepared) args ctx))))))


(defn- http-results
  [storage natives samples]
  (let [token (str (random-uuid))
        ctx (executor/create-context {:storage storage :base-fns natives :auth-provider (auth/single-token-provider token)})
        _ (runtime/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")]
    (into (sorted-map)
          (for [name [:round :sqrt :pow] :let [args (get samples name) id (ids/fn-id "core.arithmetic" name)
                                               response (router/dispatch app {:request-method :post :uri "/api/execute"
                                                                              :headers {"authorization" (str "Bearer " token)
                                                                                        "content-type" "application/json"}
                                                                              :body (json/generate-string {:fn-id id :args args :timeout-ms 30000})})
                                               payload (json/parse-string (:body response) true)
                                               native ((get natives name) args ctx)]]
            (do
              (expect! [200 :succeeded Double native] [(:status response) (keyword (:status payload))
                                                       (class native) (:result payload)])
              [name {:native (.getName (class native)) :json (:result payload)}])))))


(defn- scalar-checks!
  []
  (doseq [v [(float 0.25) (double -0.0) Double/MIN_VALUE Double/MAX_VALUE]]
    (expect! v (#'run/project-result :numeric v)))
  (doseq [[type value] [[:numeric Double/NaN] [:numeric Double/POSITIVE_INFINITY] [:numeric Float/NEGATIVE_INFINITY]
                        [:int (double 1.5)] [:numeric 9007199254740992N] [[:list :numeric] [1.5]]
                        [[:refine :numeric [:>= 0]] -1.5]]]
    (expect! :rejected (try (#'run/project-result type value) :accepted (catch clojure.lang.ExceptionInfo _ :rejected)))))


(defn check
  [packages samples]
  (let [result (atom nil)]
    ((golden/fixture "graphden.test-infra.arithmetic-replay")
     (fn []
       (let [{:keys [storage base-fns]} golden/*bootstrap*
             _ (scalar-checks!)
             _ (expect! nil (:input-type-fn-id (sp/read-entity storage :fn (ids/fn-id "core.arithmetic" :round))))
             legacy-http (http-results storage base-fns samples)
             sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
             info (sync!)
             _ (ig/init-key :exec/fn-entities {:storage storage :packages packages :base-fns info})
             owners (mapv #(ids/fn-id "core.arithmetic" %) (keys samples))
             inputs (mapv #(migration/derived-id :input-type %) owners)
             versions #(sp/query-entities (vs/unwrap storage) :fn-version {:fn-id (into owners inputs)})
             original (set (versions))
             _ (sync!)
             actual-natives (with-meta (merge base-fns (:base-fns info)) (meta (:base-fns info)))
             typed-http (http-results storage actual-natives samples)
             rows (sp/read-entities storage :fn owners)
             ctx (executor/create-context {:storage storage :base-fns (:base-fns info)})]
         (expect! legacy-http typed-http)
         (expect! inputs (mapv #(get-in rows [% :input-type-fn-id]) owners))
         (expect! original (set (versions)))
         (let [attempt (fn [p overrides]
                         (try (ig/init-key :exec/base-fns {:storage storage :packages p :extra-base-fns overrides}) :accepted
                              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
               stripped (update packages :base-fn-defs dissoc :floor)]
           (expect! :package-error/primitive-admission (attempt stripped {}))
           (expect! :package-error/primitive-admission (attempt packages {:floor (get-in info [:base-fns :ceil])}))
           (expect! original (set (versions))))
         (let [outputs (into (sorted-map)
                             (for [[name args] samples :let [id (ids/fn-id "core.arithmetic" name)]]
                               [name (try (let [plan (run/plan ctx id args)] ((:invoke plan) args ctx))
                                          (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :code :reason])))]))
               expected (into {} (map (fn [[name args]] [name ((get-in info [:base-fns name]) args ctx)])) samples)
               compiled (into (sorted-map) (map (fn [[name args]] [name (stored-result ctx name args)])) samples)]
           (expect! expected compiled)
           (expect! (assoc expected :neq {:type :browser-plan/unsupported :reason :server-value}) outputs)
           (reset! result {:stored-owners (count owners) :stable-versions (count original)
                           :compiled-values compiled :public-results outputs :http {:v1 legacy-http :v2 typed-http}})))))
    @result))
