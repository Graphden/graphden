(ns graphden.accounts.provider
  "An `AuthProvider` over the open `accounts` model. A request authenticates
   two ways: `Authorization: Bearer <token>` (API/CLI) or the HttpOnly
   `gd_session` cookie (browser — an OAuth redirect can't carry a bearer). Both
   resolve through `accounts.core/authenticate-token` to an ACTIVE account.

   The principal it returns is org-AGNOSTIC — `{:authenticated? :user-id :user}`
   — because open core has no orgs. When the tenancy addon is present, its
   request-scope resolves the org from the account's membership; when it isn't
   (self-hosted), no org is needed. This is the swap-in for `:auth/provider` /
   `:exec/context :auth-provider`, exactly where the tenancy
   `storage-token-provider` plugs today."
  (:require
    [clojure.string :as str]
    [graphden.accounts.core :as accounts]
    [graphden.auth.provider :as auth]))


(def ^:const session-cookie "gd_session")


(defn- cookie-token
  "The `gd_session` value from the raw Cookie header, or nil. Parsed here (not
   via ring cookie middleware) because auth runs at the base storage layer,
   before per-request middleware."
  [request]
  (when-let [header (get-in request [:headers "cookie"])]
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= (str/trim (str k)) session-cookie) v)))
          (str/split header #";\s*"))))


(defn request-token
  "The session token from a request: bearer first, then the session cookie."
  [request]
  (or (auth/extract-bearer request) (cookie-token request)))


(defrecord AccountsAuthProvider
  [storage]

  auth/AuthProvider

  (authenticate
    [_ request]
    (if-let [acct (accounts/authenticate-token storage (request-token request))]
      {:authenticated? true
       :user-id (str (:id acct))
       :user (or (:primary-email acct) (:display-name acct) (str (:id acct)))}
      {:authenticated? false})))


(defn accounts-provider
  "An `AuthProvider` backed by the `accounts` model over `storage` (the BASE
   storage — auth runs in the platform context, before any request scope)."
  [storage]
  (->AccountsAuthProvider storage))
