(ns graphden.accounts.flows
  "Thin flows that compose the storage layer (`accounts.core`) with the mailer
   (`accounts.email`). Kept separate so `core` stays storage-only and `email`
   stays transport-only.

   The message BODY comes from the optional `render-email` seam —
   `(fn [kind base-url token] → {:subject :text :html} | nil)`, wired by init
   to the `app.auth-pages` graph fn-defs — so a deployment composes its own
   copy/branding in the graph. nil / a throw falls back to the built-in
   templates in `accounts.email` (verification must survive a graph outage)."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.accounts.core :as core]
    [graphden.accounts.email :as email]))


(defn- rendered-or
  [render-email kind app-base-url token fallback-thunk]
  (or (when render-email
        (try (render-email kind app-base-url token)
             (catch Exception e
               (log/warn e "accounts: graph email render failed — using the built-in template"
                         {:kind kind})
               nil)))
      (fallback-thunk)))


(defn request-verification!
  "Mint a verification token for `addr` on `account-id`, build the message, and
   send it via `mailer`. Returns the mailer's `{:ok? …}` result. `app-base-url`
   is the public origin the link points at (e.g. https://app.graphden.dev)."
  ([storage mailer app-base-url account-id addr]
   (request-verification! storage mailer app-base-url account-id addr nil))
  ([storage mailer app-base-url account-id addr render-email]
   (if (str/blank? (str app-base-url))
     ;; No TRUSTED origin (GRAPHDEN_APP_ORIGIN unset AND the request
     ;; Host is not a dev-local host — see routes/link-origin). Sending
     ;; a link derived from an attacker-controllable Host would let a
     ;; forged Host: header point the emailed link at the attacker, so
     ;; we refuse — no token minted, no mail. Loud so ops sets the env.
     (do (log/warn "accounts: skipping verification email — no trusted app origin"
                   {:hint "set GRAPHDEN_APP_ORIGIN"})
         {:ok? true :no-op true})
     (let [token (core/mint-verification! storage account-id addr)
           body (rendered-or render-email :verify app-base-url token
                             #(email/verification-email-body app-base-url token))]
       (email/send-mail! mailer (assoc body :to addr))))))


(defn request-password-reset!
  "Mint a reset token for `addr` (when a password identity exists) and email
   the link. Returns `{:ok? …}` from the mailer, or `{:ok? true :no-op true}`
   when no such identity — indistinguishable to the HTTP caller (no account
   enumeration)."
  ([storage mailer app-base-url addr]
   (request-password-reset! storage mailer app-base-url addr nil))
  ([storage mailer app-base-url addr render-email]
   (cond
     ;; No trusted origin → refuse (host-header poisoning guard, same
     ;; as request-verification!). Mint nothing.
     (str/blank? (str app-base-url))
     (do (log/warn "accounts: skipping password-reset email — no trusted app origin"
                   {:hint "set GRAPHDEN_APP_ORIGIN"})
         {:ok? true :no-op true})

     :else
     (if-let [{:keys [token]} (core/mint-password-reset! storage addr)]
       (email/send-mail! mailer
                         (assoc (rendered-or render-email :reset app-base-url token
                                             #(email/reset-email-body app-base-url token))
                                :to addr))
       {:ok? true :no-op true}))))
