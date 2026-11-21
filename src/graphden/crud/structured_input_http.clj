(ns graphden.crud.structured-input-http
  "Request and response boundary for authorized structured input commands."
  (:require
    [cheshire.core :as json]
    [graphden.crud.branches :as branches]
    [graphden.crud.request :as request]
    [graphden.crud.structured-input-bind :as bind]
    [graphden.crud.structured-input-change :as change]
    [graphden.input.access :as access]
    [graphden.input.authoring-principal :as principal]
    [graphden.system.branch-router :as router]
    [graphden.versioning.storage.core :as vs]))


(defn- refusal
  [type]
  (when-let [[status reason]
             (case type
               (:input/offline-only :authz/forbidden :authz/branch-protected)
               [403 "The structured input operation is unavailable"]
               (:input/stale-preview :input/stale-version)
               [409 "The input changed. Review a fresh preview"]
               (:input/leaf-unavailable :input/no-change :input/binding-conflict :branch/merge-required)
               [409 "The selected input cannot be changed"]
               (:types/check-failed :input/unproven-bind :input/unresolved-contract :input/invalid-binding)
               [422 "The structured input change could not be verified"]
               (:input/invalid-command :input/version-required :validation-error/malformed-json)
               [400 "The structured input request is invalid"]
               nil)]
    {:ok false :type type :error reason :http-status status}))


(defn dispatch
  "The route supplies the phase. Each operation verifies owner, branch and
   source permissions on its own writer snapshot."
  [ctx phase request]
  (try
    (when-not (or access/*offline?* (:authenticated? (principal/current)))
      (throw (ex-info "Structured input editing requires authentication" {:type :authz/forbidden})))
    (let [command (request/read-json-body request)
          input (when (map? command) (dissoc command :action))
          action (:action command)]
      (when (and input (contains? input :offline?))
        (throw (ex-info "Storage authority belongs to the server" {:type :authz/forbidden})))
      (case [phase action]
        ["preview" "bind"] (bind/preview ctx input)
        ["apply" "bind"] (bind/bind! ctx input)
        (["preview" "edit"] ["preview" "remove"]) (change/preview ctx (keyword action) input)
        (["apply" "edit"] ["apply" "remove"]) (change/apply! ctx (keyword action) input)
        (refusal :input/invalid-command)))
    (catch clojure.lang.ExceptionInfo error
      (or (refusal (:type (ex-data error))) (throw error)))))


(defn mcp-dispatch
  "Use the regular writer on an explicitly named working branch. A call
   from a different branch re-enters the authenticated HTTP boundary."
  [ctx req {:keys [branch phase] :as command}]
  (if-not (and (:authenticated? (principal/current)) (#{"preview" "apply"} phase))
    (refusal (if (:authenticated? (principal/current)) :input/invalid-command :authz/forbidden))
    (let [storage (request/require-storage ctx)
          {:keys [id base-branch-id]} (branches/resolve-branch-ref (vs/unwrap storage) branch)
          body (dissoc command :phase :branch)]
      (cond
        (nil? base-branch-id) (refusal :authz/forbidden)
        (= id (vs/current-branch-id storage)) (dispatch ctx phase {:body body})
        :else
        (if-let [active (router/current-router)]
          (let [response (router/dispatch active (-> req
                                                     (dissoc :query-string :params :query-params)
                                                     (assoc :request-method :post :uri (str "/api/structured-input/" phase)
                                                            :body (json/generate-string body))
                                                     (update :headers assoc "x-graphden-branch" (str id)
                                                             "content-type" "application/json")))]
            (or (when (string? (:body response))
                  (select-keys (json/parse-string (:body response) true)
                               [:ok :committed :fn-id :binding-id :expected-state :expected-version :error :type]))
                (refusal :authz/forbidden)))
          (refusal :authz/forbidden))))))
