(ns graphden.packages.tenancy-admin.grants.impls
  "Impls for the org-admin grants panel base-fns (PLATFORM_PLAN §6).
   Thin storage shims over the tenancy addon's `:grant` entity — they
   read/write `(:storage ctx)`, the org-scoped storage, so the panel
   only ever sees the current org's grants. They throw when the addon
   isn't active (no `:grant` table), but this package only loads WITH
   the addon, so callers' `:try` guard is belt-and-suspenders."
  (:require
    [clojure.string :as str]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.grant :as grant]))


(defbase list-grants
  []
  (cr/record-effect! :db)
  (sp/query-entities (:storage ctx) :grant {}))


(defbase create-grant
  [subject capability namespace]
  (cr/record-effect! :db)
  ;; Reject an unknown capability at write. It's a fixed closed vocabulary
  ;; (`grant/capabilities`); an out-of-set value (a typo like "notacap")
  ;; round-trips through storage but never matches `cap-implies?`, so it
  ;; would authorize nothing while raising no error — a silently-dead
  ;; grant. Fail loud instead. (The admin form's <select> already prevents
  ;; this; this guards direct API callers.)
  (when-not (contains? grant/capabilities (keyword capability))
    (throw (ex-info (str "Unknown capability " (pr-str capability)
                         " (valid: " (str/join ", " (sort (map name grant/capabilities))) ")")
                    {:type :grant/invalid-capability :capability capability})))
  ;; Resolve the authored username to the user's STABLE id at the boundary and
  ;; store BOTH: `:subject-id` is what enforcement matches on (survives a
  ;; username edit / delete-recreate); `:subject` (username) is kept for
  ;; display + the personal-namespace path. nil id (no such user yet) → a dead
  ;; grant, same as a typo'd capability but non-throwing.
  (let [storage (:storage ctx)
        user-id (some-> (first (sp/query-entities storage :user {:username subject})) :id str)]
    (sp/create-entity storage :grant
                      {:subject subject
                       :subject-id user-id
                       :capability capability
                       :namespace namespace})))


(def impls
  {:list-grants list-grants
   :create-grant create-grant})
