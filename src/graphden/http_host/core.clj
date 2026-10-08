(ns graphden.http-host.core
  "Finite HTTP handler publication. Core uses a reserved path on its existing
   origin. An addon may supply its existing isolated app-host mechanism; both
   paths share leases, current authorization, branch selection and response
   policy. A publication executes one bounded request and starts no service."
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.accounts.core :as accounts]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.provider :as accounts-provider]
    [graphden.auth.provider :as auth]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.crud.request :as req]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.http-host.lease :as lease]
    [graphden.http-host.policy :as policy]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tc]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as merge]
    [graphden.versioning.storage.resolution :as resolution]))


(def public-prefix "/__http/")
(def api-prefix "/api/http-host")


(defonce adapter
  ;; One optional deployment adapter, installed/halted by the addon. No
  ;; request may supply these callbacks or choose its own owner/storage.
  (atom nil))


(defn- denied!
  []
  (throw (ex-info "HTTP publication is not available" {:type :authz/forbidden})))


(defn- control-scope?
  "A scoped API token must explicitly permit execution control. Browser
   cleanup still works after graph grants or the creating API token expire."
  [principal]
  (or (not (:api-token? principal)) (nil? (:token-scopes principal))
      (contains? (:token-scopes principal) :execute)))


(defn- platform-storage
  [ctx]
  (vs/unwrap (:storage ctx)))


(defn- account-principal
  [storage owner]
  (when (and (parse-uuid owner) (contains? (sp/current-entities storage) :account))
    (when-let [account (accounts/account-of storage owner)]
      (when (= "active" (:status account))
        {:authenticated? true :user-id owner
         :user (or (:primary-email account) (:display-name account) owner)}))))


(defn- publication-owner
  [ctx request principal]
  (when-not (:authenticated? principal) (denied!))
  (or (when-let [id (:user-id principal)]
        (tc/with-org tc/public-org
                     (when (account-principal (platform-storage ctx) (str id)) (str id))))
      (when-let [token (auth/extract-bearer request)]
        (str "token:" (crypto/sha256-hex token)))
      (denied!)))


(defn- current-owner-principal
  [ctx grant]
  (let [owner (:owner grant)
        principal
        (if-let [f (:principal @adapter)]
          (f ctx grant)
          (tc/with-org tc/public-org
                       (or (account-principal (platform-storage ctx) owner)
                           (when-let [token (:token (:auth-provider ctx))]
                             (when (auth/constant-time-equal? owner (str "token:" (crypto/sha256-hex token)))
                               {:authenticated? true})))))]
    (when (:authenticated? principal)
      (let [account-api? (and (:api-token? grant) (not (str/starts-with? owner "token:")))
            source (when account-api?
                     (tc/with-org tc/public-org
                                  (when-let [id (some-> (:source-session-id grant) parse-uuid)]
                                    (sp/read-entity (platform-storage ctx) :session id))))
            live-source? (or (not account-api?)
                             (and (accounts/authenticating-session? source)
                                  (= "api" (:kind source)) (= owner (:account-id source))))
            fresh (if account-api?
                    (assoc principal :api-token? true :token-scopes (accounts/parse-scopes (:scopes source)))
                    principal)
            captured (when (:api-token? grant) (some->> (:token-scopes grant) (map keyword) set))
            current (when (:api-token? fresh) (:token-scopes fresh))
            ceiling (cond (and captured current) (set/intersection captured current)
                          captured captured
                          :else current)]
        (when live-source?
          (assoc fresh :org (:org grant)
                 :api-token? (boolean (or (:api-token? grant) (:api-token? fresh)))
                 :token-scopes ceiling))))))


(defn- source-session-id
  "Pin an account API publication to its authenticated token's identity.
   Browser publications follow the owner; configured tokens follow their hash."
  [storage request principal owner]
  (when (and (:api-token? principal) (not (str/starts-with? owner "token:")))
    (let [token-hash (some-> (accounts-provider/request-token request) crypto/sha256-hex)
          row (when token-hash (first (sp/query-entities storage :session {:token-hash token-hash})))]
      (when-not (and (accounts/authenticating-session? row)
                     (= "api" (:kind row)) (= owner (:account-id row)))
        (denied!))
      (str (:id row)))))


(defn- authorize-handler!
  [ctx fn-id]
  (when-not (sp/read-entity (:storage ctx) :fn fn-id) (denied!))
  (when-let [guard (:execute-guard ctx)] (guard ctx fn-id)))


(defn validate-handler!
  "Authorize a typed Ring response graph with only request optionally free.
   Shared by finite publication and isolated HTML preview; transport policy
   remains the caller’s responsibility."
  [ctx fn-id]
  (authorize-handler! ctx fn-id)
  (when (type-check/type-check-fn-after-mutation! (:storage ctx) fn-id)
    (throw (ex-info "Resolve the handler's type errors first" {:type :http-host/invalid})))
  (let [free-args (set (keys (lookup/free-arg-slot-map ctx fn-id)))
        info (registry/rich-type-of-id fn-id)
        response-row (sp/read-entity (:storage ctx) :fn (ids/fn-id "core.system" :ring-response-shape))
        expected (when response-row (type-check/type-fn->rich-type (:storage ctx) response-row))
        request-row (sp/read-entity (:storage ctx) :fn (ids/fn-id "web.ring-adapter" :ring-request-shape))
        request-type (when request-row (type-check/type-fn->rich-type (:storage ctx) request-row))
        accepts-request? (or (not (contains? free-args :request))
                             (and request-type (get-in info [:args :request])
                                  (types/subtype? request-type (get-in info [:args :request]))))]
    (when-not (and (every? #{:request} free-args) expected (:return info)
                   accepts-request?
                   (types/subtype? (:return info) expected))
      (throw (ex-info "Choose a typed HTTP response handler with only its request unbound"
                      {:type :http-host/invalid})))))


(defn- command-context
  [router request]
  (let [branch-ref (br/extract-branch-ref request)
        branch-id (br/resolve-branch-id router branch-ref)]
    (when (and branch-ref (nil? branch-id))
      (throw (ex-info "Unknown branch" {:type :http-host/invalid})))
    (br/ctx-for router branch-id)))


(defn- principal
  [ctx request]
  (or tc/*current-principal*
      (when-let [provider (:auth-provider ctx)] (auth/authenticate provider request))))


(defn- request-origin
  [request]
  ;; Returned endpoint data only, never authority for an org or a graph.
  ;; Normal outbound SSRF policy still governs a service-get using this URL.
  (let [host (get-in request [:headers "host"])
        origin (get-in request [:headers "origin"])
        scheme (if (= "http" (or (get-in request [:headers "x-forwarded-proto"])
                                 (some-> (:scheme request) name))) "http" "https")]
    (when-not (and (string? host) (re-matches #"[a-zA-Z0-9.\-\[\]:]+" host))
      (throw (ex-info "Missing public host" {:type :http-host/invalid})))
    (if (contains? #{(str "http://" host) (str "https://" host)} origin)
      origin
      (str scheme "://" host))))


(defn- public-data
  [grant]
  {:id (str (:id grant)) :fn-id (str (:fn-id grant))
   :branch-id (str (:branch-id grant)) :expires-at (:expires-at grant)
   :url (if (:route-id grant)
          (:origin grant)
          (str (:origin grant) public-prefix (:id grant)))})


(defn publish!
  "Authorize and reserve one publication. UUID is create-only and can be
   staged by the client before network I/O for exact lost-response cleanup."
  [router request]
  (when (and (tc/tenancy-addon-active?) (nil? @adapter)) (denied!))
  (let [ctx (command-context router request)
        p (principal ctx request)
        _ (when-not (control-scope? p) (denied!))
        actor (publication-owner ctx request p)
        org (tc/current-org)
        body (req/read-json-body request)
        id (req/parse-uuid-or-clear (:create-id body))
        fn-id (req/parse-uuid-or-clear (:fn-id body))
        branch-id (vs/current-branch-id (:storage ctx))
        host-data (if-let [f (:publication @adapter)]
                    (f ctx request id)
                    {:origin (request-origin request)})]
    (when-not (and id fn-id branch-id)
      (throw (ex-info "create-id and fn-id must be UUIDs" {:type :http-host/invalid})))
    (tx/assert-owns-commit! (:storage ctx))
    (binding [tc/*current-principal* p]
      (public-data
        (writer/call-with-write
          (:storage ctx) {:entity :fn :ids [fn-id]}
          (fn [st]
            (merge/lock-branches! st branch-id)
            (when-not (sp/read-entity st :branch branch-id) (denied!))
            (resolution/call-with-fresh-memos
              (fn []
                (recheck/call-with-ctx-slices
                  ctx #(validate-handler! (assoc ctx :storage st) fn-id))))
            (let [source-id (tc/with-org tc/public-org
                                         (source-session-id (vs/unwrap st) request p actor))
                  data (merge host-data {:id id :org org :owner actor :fn-id fn-id :branch-id branch-id
                                         :source-session-id source-id
                                         :api-token? (boolean (:api-token? p))
                                         :token-scopes (some->> (:token-scopes p) (mapv name))})]
              (tc/with-org tc/public-org
                           (let [base (vs/unwrap st)
                                 row (lease/create! base data (:http-host-limits ctx))]
                             (when-let [f (:publish! @adapter)] (f base data))
                             (lease/descriptor row))))))))))


(defn- execute-handler
  [ctx grant request p]
  (binding [tc/*current-principal* p
            cr/*cancel-check* #(when (Thread/.isInterrupted (Thread/currentThread))
                                 (throw (InterruptedException. "HTTP handler cancelled")))]
    (tc/with-org (:org grant)
                 (authorize-handler! ctx (:fn-id grant))
                 (cr/execute (assoc ctx :execute-guard nil
                                    :allowed-effects (disj (cr/cloud-allowed-effects-for (:org grant)) :process))
                             (:fn-id grant) {:request request}))))


(defn- run-handler
  [ctx grant request p]
  (let [org (:org grant)
        pool (persist/current-execution-pool)
        release (when pool (persist/acquire-execution-slot! (:storage ctx) org (not (tc/platform-tier? org))))
        state (atom :waiting)]
    (if-not release
      (policy/failure 503 "The executor is busy.")
      (try
        (let [result (cr/run-with-timeout
                       10000
                       (bound-fn []
                         (when (compare-and-set! state :waiting :running)
                           (try (execute-handler ctx grant request p)
                                (finally (release)))))
                       pool)]
          (cond
            (identical? result ::cr/timeout) (policy/failure 504 "The handler timed out.")
            (identical? result ::cr/rejected) (policy/failure 503 "The executor is busy.")
            (identical? result ::cr/error) (policy/failure 500 "The handler failed.")
            :else (policy/response result)))
        ;; A timed-out handler may ignore interruption inside a primitive.
        ;; Keep its slot until its task actually exits. A cancelled QUEUED
        ;; task never enters the thunk, so release that reservation here.
        (finally (when (compare-and-set! state :waiting :cancelled) (release)))))))


(defn serve
  "Serve an exact lease using its stored branch, never a caller branch header.
   `expected-host` is nil on core; the private adapter supplies its trusted
   app target's host so a lease cannot cross app origins."
  [router id request path expected-host]
  (try
    (let [base (:base-ctx router)
          grant (tc/with-org tc/public-org (lease/read-active (platform-storage base) id))]
      (if-not (and grant (or (nil? expected-host) (= expected-host (:host grant))))
        (policy/failure 404 "This HTTP publication is unavailable.")
        (tc/with-org (:org grant)
                     (let [branch-id (:branch-id grant)
                           branch (sp/read-entity (:storage base) :branch branch-id)
                           p (current-owner-principal base grant)
                           incoming (policy/request request path)]
                       (cond
                         (or (nil? branch) (nil? p)) (policy/failure 404 "This HTTP publication is unavailable.")
                         (nil? incoming) (policy/failure 413 "The request is too large.")
                         :else (let [ctx (br/ctx-for router branch-id)]
                                 (recheck/call-with-ctx-slices ctx #(run-handler ctx grant incoming p))))))))
    (catch Exception _ (policy/failure 404 "This HTTP publication is unavailable."))))


(defn endpoint
  "Resolve a temporary publication only within the caller's org and branch."
  [ctx fn-id]
  (let [criteria {:org (tc/current-org) :branch-id (vs/current-branch-id (:storage ctx)) :fn-id fn-id}
        grants (tc/with-org tc/public-org (lease/active-for (platform-storage ctx) criteria))]
    (when-let [grant (first (sort-by (comp str :id) grants))]
      (let [url (:url (public-data grant))
            uri (java.net.URI. url)
            port (java.net.URI/.getPort uri)]
        {:host (java.net.URI/.getHost uri)
         :port (if (pos? port) port (if (= "https" (java.net.URI/.getScheme uri)) 443 80))
         :url url}))))


(defn- list-publications
  [router request]
  (let [ctx (command-context router request)
        p (principal ctx request)]
    (cond
      (not (:authenticated? p))
      {:available false :reason "authentication-required" :publications []}

      (and (tc/tenancy-addon-active?) (nil? @adapter))
      {:available false :reason "apps-domain-required" :publications []}

      :else
      (let [actor (publication-owner ctx request p)
            criteria {:org (tc/current-org) :owner actor :branch-id (vs/current-branch-id (:storage ctx))}]
        (cond-> {:available (control-scope? p)
                 :publications (tc/with-org tc/public-org
                                            (mapv public-data (lease/active-for (platform-storage ctx) criteria)))}
          (not (control-scope? p)) (assoc :reason "execute-scope-required"))))))


(defn- stop!
  [router request id]
  ;; Exact lease identity + authenticated owner suffice for cleanup, including
  ;; a lost response followed by branch deletion. A stale branch header must
  ;; not prevent idempotent revocation.
  (let [ctx (:base-ctx router)
        p (principal ctx request)
        _ (when-not (control-scope? p) (denied!))
        actor (publication-owner ctx request p)
        org (tc/current-org)]
    (tc/with-org tc/public-org
                 (tx/in-transaction
                   (platform-storage ctx)
                   (fn [st]
                     (when (lease/revoke! st org actor id)
                       (when-let [f (:revoke! @adapter)] (f st org id))))))
    {:ok true}))


(defn- json-response
  [status body]
  (policy/response {:status status :headers {"Content-Type" "application/json"}
                    :body (json/generate-string body)}))


(defn- cross-origin-write?
  [request]
  (let [origin (get-in request [:headers "origin"])
        host (get-in request [:headers "host"])]
    (and (contains? #{:post :delete} (:request-method request))
         origin (not (contains? #{(str "http://" host) (str "https://" host)} origin)))))


(defn- command-response
  [router request]
  (try
    (let [uri (:uri request)
          method (:request-method request)
          id (some-> (re-matches #"/api/http-host/([0-9a-fA-F-]+)" uri) second parse-uuid)]
      (cond
        (cross-origin-write? request) (json-response 403 {:ok false :error "bad-origin"})
        (and (= uri api-prefix) (= method :get))
        (json-response 200 (assoc (list-publications router request) :ok true))
        (and (= uri api-prefix) (= method :post))
        (json-response 201 {:ok true :publication (publish! router request)})
        (and id (= method :delete)) (json-response 200 (stop! router request id))
        :else (json-response 400 {:ok false :error "invalid-command"})))
    (catch clojure.lang.ExceptionInfo e
      (let [status (case (:type (ex-data e))
                     :authz/forbidden 403
                     :http-host/invalid 400
                     :validation-error/malformed-json 400
                     :http-host/capacity 429
                     (:http-host/conflict :unique-violation :constraint-violation) 409
                     500)]
        (json-response status {:ok false :error (case status
                                                  400 "invalid-publication"
                                                  403 "forbidden"
                                                  409 "identity-unavailable"
                                                  429 "capacity-full"
                                                  "publication-failed")})))))


(defn make-router
  "Install through the existing route collection. Cloud public requests use
   the addon's isolated app router; its editor-origin host path stays closed."
  [router]
  (fn [request]
    (let [uri (str (:uri request))]
      (cond
        (or (= uri api-prefix) (str/starts-with? uri (str api-prefix "/")))
        (command-response router request)
        (str/starts-with? uri public-prefix)
        (if (or @adapter (tc/tenancy-addon-active?))
          (policy/failure 404 "This HTTP publication is unavailable.")
          (let [[_ raw-id path] (re-matches #"/__http/([0-9a-fA-F-]+)(/.*)?" uri)
                id (some-> raw-id parse-uuid)]
            (if id (serve router id request (or path "/") nil)
                (policy/failure 404 "This HTTP publication is unavailable."))))
        :else nil))))
