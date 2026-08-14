(ns graphden.accounts.init
  "Integrant wiring for the open `accounts` module — the opt-in identity layer.

   Two init-keys, both no-op-free and only present when a config fragment
   references them (the idiomatic self-hosted opt-in: include
   `graphden/accounts/addon.edn` via `GRAPHDEN_ADDON_CONFIGS` to turn accounts
   ON; omit it and core runs exactly as before — single-token or open):

   - `:accounts/schema-extension` → the `(builder → builder)` fn that adds
     `:account` / `:identity` / `:session`, dropped into `:db/schema
     {:extensions [...]}`.
   - `:accounts/provider` → the `AccountsAuthProvider`, dropped into
     `:exec/context {:auth-provider ...}` (the same swap-point tenancy uses)."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.accounts.account-schema :as account-schema]
    [graphden.accounts.email :as email]
    [graphden.accounts.identity-schema :as identity-schema]
    [graphden.accounts.provider :as provider]
    [graphden.accounts.routes :as routes]
    [graphden.accounts.session-schema :as session-schema]
    [graphden.executor.interface :as exec]
    [graphden.system.route-collection :as rc]
    [integrant.core :as ig]))


;; The graph render seam (`app.auth-pages`): the page/email presentation
;; is graph composition on the PLATFORM ctx — a deployment re-themes the
;; auth surface in the editor. The renderers are optional callbacks so
;; the module stays a drop-in: no ctx wired (or a graph render failure)
;; → routes/flows fall back to the built-in Clojure pages/templates.

(def ^:private page-fn-names
  {:login "auth-login-page"
   :account "auth-account-page"
   :reset "auth-reset-page"})


(def ^:private email-fn-names
  {:verify "auth-verify-email"
   :reset "auth-reset-email"})


(defn- make-page-renderer
  [ctx]
  (when ctx
    (fn [page-kw args]
      (when-let [fn-name (page-fn-names page-kw)]
        (exec/execute-by-name ctx fn-name args)))))


(defn- make-email-renderer
  [ctx]
  (when ctx
    (fn [kind base-url token]
      (when-let [fn-name (email-fn-names kind)]
        (exec/execute-by-name ctx fn-name {:base-url base-url :token token})))))


(defn schema-extension
  "One `(builder → builder)` fn registering all three accounts entities."
  []
  (fn [builder]
    (-> builder
        (account-schema/extend-builder)
        (identity-schema/extend-builder)
        (session-schema/extend-builder))))


(defmethod ig/init-key :accounts/schema-extension
  [_ _]
  (log/info "Accounts: registering :account / :identity / :session schema")
  (schema-extension))


(defmethod ig/init-key :accounts/provider
  [_ {:keys [storage]}]
  (log/info "Accounts: wiring AccountsAuthProvider")
  (provider/accounts-provider storage))


(defmethod ig/init-key :accounts/mailer
  [_ config]
  ;; api-key blank (unset RESEND_API_KEY collapses to "") → LogMailer.
  (email/make-mailer config))


(defn- enabled-oauth
  "Keep only providers whose client-id AND secret are set — an unset #env
   collapses to \"\", so a provider without credentials is simply OFF."
  [oauth-providers]
  (into {} (for [[k {:keys [client-id client-secret] :as cfg}] oauth-providers
                 :when (and (not (str/blank? client-id))
                            (not (str/blank? client-secret)))]
             [k cfg])))


(defmethod ig/init-key :accounts/routes-install
  [_ {:keys [storage mailer app-origin oauth-providers telegram ctx]}]
  (let [oauth (enabled-oauth oauth-providers)
        tg (when-not (str/blank? (:bot-token telegram)) telegram)]
    (log/info "Accounts: installing /auth/* routes"
              {:oauth (keys oauth) :telegram (some? tg)
               :graph-pages (some? ctx)
               :app-origin (if (str/blank? (str app-origin)) :from-host app-origin)})
    (rc/install-router! :accounts
                        (routes/make-router {:storage storage :mailer mailer
                                             :app-origin app-origin
                                             :oauth-providers oauth :telegram tg
                                             :page-renderer (make-page-renderer ctx)
                                             :email-renderer (make-email-renderer ctx)}))
    :installed))


(defmethod ig/halt-key! :accounts/routes-install
  [_ _]
  (rc/remove-router! :accounts))
