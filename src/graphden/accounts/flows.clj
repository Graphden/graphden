(ns graphden.accounts.flows
  "Thin flows that compose the storage layer (`accounts.core`) with the mailer
   (`accounts.email`). Kept separate so `core` stays storage-only and `email`
   stays transport-only."
  (:require
    [graphden.accounts.core :as core]
    [graphden.accounts.email :as email]))


(defn request-verification!
  "Mint a verification token for `addr` on `account-id`, build the message, and
   send it via `mailer`. Returns the mailer's `{:ok? …}` result. `app-base-url`
   is the public origin the link points at (e.g. https://app.graphden.dev)."
  [storage mailer app-base-url account-id addr]
  (let [token (core/mint-verification! storage account-id addr)
        body (email/verification-email-body app-base-url token)]
    (email/send-mail! mailer (assoc body :to addr))))


(defn request-password-reset!
  "Mint a reset token for `addr` (when a password identity exists) and email
   the link. Returns `{:ok? …}` from the mailer, or `{:ok? true :no-op true}`
   when no such identity — indistinguishable to the HTTP caller (no account
   enumeration)."
  [storage mailer app-base-url addr]
  (if-let [{:keys [token]} (core/mint-password-reset! storage addr)]
    (email/send-mail! mailer (assoc (email/reset-email-body app-base-url token) :to addr))
    {:ok? true :no-op true}))
