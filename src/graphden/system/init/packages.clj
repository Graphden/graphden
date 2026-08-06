(ns graphden.system.init.packages
  "Integrant init-keys for package loading + graph bootstrap: load the
   package definitions (`:app/packages`), register base-fn impls +
   type-aliases + base-fn rows (`:exec/base-fns`), and sync composed
   fn-defs + run the type-check sweep (`:exec/fn-entities`).

   These are thin wiring shells over `graphden.packages.sync` (which owns
   the actual sync logic)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.packages.loader :as pkg]
    [graphden.packages.manifest :as manifest]
    [graphden.packages.sync :as sync]
    [integrant.core :as ig]))


(defmethod ig/init-key :app/packages [_ {:keys [package-names extra-package-names]}]
  ;; `:extra-package-names` is the addon fns-channel seam (docs/TENANCY_SEAM.md
  ;; § Packages channel): the tenancy addon appends its own fns-package(s) — e.g.
  ;; the org-admin UI — via the manifest WITHOUT restating the core list,
  ;; so they load only when the addon is active.
  (let [names (vec (concat package-names extra-package-names
                           (manifest/package-names (manifest/read-manifest))))]
    (log/info "Loading packages:" names)
    (let [packages (pkg/load-packages names)]
      (log/info "Packages loaded:" (count (:packages packages)) "packages,"
                (count (:base-fn-defs packages)) "base-fns,"
                (count (:fn-defs packages)) "fn-defs")
      packages)))


(defmethod ig/init-key :exec/base-fns
  [_ {:keys [storage packages extra-base-fns]}]
  (log/info "Registering base functions...")
  (let [result (sync/register-base-fns-from-packages! storage packages extra-base-fns)
        base-fn-defs (:base-fn-defs packages)]
    (log/info "Base functions registered:"
              (count base-fn-defs)
              (when (seq extra-base-fns)
                (str "(+ " (count extra-base-fns) " extras)")))
    (assoc result :status :registered)))


;; No halt needed - registry is global state


;; =============================================================================
;; Fn Entities
;; =============================================================================

(defmethod ig/init-key :exec/fn-entities
  [_ {:keys [storage packages base-fns skip-allowlist-gate?]}]
  (log/info "Creating fn entities...")
  (let [fns (sync/sync-fn-entities-from-packages!
              storage packages base-fns
              {:skip-allowlist-gate? skip-allowlist-gate?})]
    (log/info "Fn entities created:" (count fns))
    fns))
