(ns graphden.tenancy.operator-bootstrap
  "Boot-time operator bootstrap (Track A2c). When
   `GRAPHDEN_OPERATOR_PASSWORD` is set, seed — CREATE-IF-ABSENT — the
   operator org, its first user, and a `:platform-admin` grant, so the
   platform operator logs in through the NORMAL /login form instead of
   pasting a bearer into DevTools.

   The operator is `just tenant #1`: the org (default `graphden`) is a
   normal sandboxed tenant (default plan `network` — integrations reach
   external systems, files/env never), and the operator's cross-cutting
   authority is the `:platform-admin` capability grant (A2a/A2b), NOT a
   magic org. `:org` / `:user` / `:grant` are GLOBAL platform entities, so
   the seed writes them on the base storage directly — no org context, no
   `:org-id` stamping.

   Idempotent: reruns never clobber a password changed in-app (the user is
   created only when absent) and never duplicate the org / grant."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.users :as users]))


(defn bootstrap!
  "Seed the operator org + user + platform-admin grant on `storage` (the
   base platform storage). No-op unless `:password` is non-blank. Returns a
   summary map — `{:skipped :no-password}` when inactive, else
   `{:org :user :user-created? :grant-created?}`."
  [storage {:keys [user org plan password]}]
  (if (str/blank? password)
    {:skipped :no-password}
    (let [org-name (or (not-empty org) "graphden")
          user-name (or (not-empty user) "operator")
          plan-slug (or (not-empty plan) "network")
          org-created?
          (when-not (first (sp/query-entities storage :org {:name org-name}))
            (sp/create-entity storage :org {:name org-name :plan plan-slug})
            true)
          existing-user (first (sp/query-entities storage :user {:username user-name}))
          user-row (or existing-user
                       (sp/create-entity storage :user
                                         {:username user-name
                                          :password-hash (users/hash-password password)
                                          :org org-name}))
          grant-created?
          (when-not (some #(= "platform-admin" (:capability %))
                          (sp/query-entities storage :grant {:subject-id (str (:id user-row))}))
            (sp/create-entity storage :grant
                              {:subject-id (str (:id user-row))
                               :subject-kind "user"
                               :capability "platform-admin"
                               :namespace nil})
            true)]
      (log/info "operator-bootstrap:" user-name "@" org-name
                (str "(org-created " (boolean org-created?)
                     ", user-created " (nil? existing-user)
                     ", grant-created " (boolean grant-created?) ")"))
      {:org org-name
       :user user-name
       :user-created? (nil? existing-user)
       :grant-created? (boolean grant-created?)})))
