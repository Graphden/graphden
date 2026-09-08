(ns graphden.packages.registry-shared
  "The three conventions the registry package's impls MODULES share
   (`registry/registry/impls.clj` and `registry/marketplace/impls.clj`).
   Module impls are evaluated standalone by the package loader — one
   `impls.clj` cannot `require` another — so what both need lives here:
   the remote registry's bearer, the moderation deploy flag and the
   platform-wide storage beneath the org-scoped decorator."
  (:require
    [clojure.string :as str]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.versioning.storage.core :as vs]))


(defn remote-auth-headers
  "Headers for a dial to the configured remote registry — the
   `GRAPHDEN_REGISTRY_TOKEN` bearer when one is set, else none."
  []
  (let [token (System/getenv "GRAPHDEN_REGISTRY_TOKEN")]
    (cond-> {} (seq (str token)) (assoc "Authorization" (str "Bearer " token)))))


(defn moderation-enabled?
  "Does this deployment moderate public listings? The public deploy
   setting `GRAPHDEN_MARKETPLACE_MODERATION` (`:marketplace-moderation`),
   truthy when \"1\" / \"true\" / \"yes\"."
  []
  (contains? #{"1" "true" "yes"} (some-> (deploy-config/read-setting :marketplace-moderation)
                                         str str/trim str/lower-case)))


(defn platform-base
  "The storage BENEATH the org-scoped decorator (`vs/unwrap` lands on the
   decorator; its `:base` is the backend). Cross-org reads — the
   operator's moderation queue, the registry-wide name check — go
   through it; the org-scoped view would show only the caller's own org.
   Row level security still applies at the pool (public rows are
   readable by every org — a pending listing is public by intent)."
  [storage]
  (let [s (vs/unwrap storage)]
    (or (:base s) s)))
