(ns graphden.packages.app.marketplace.impls
  "Impls for the marketplace's own base-fns (docs/MARKETPLACE.md) — the
   identity + roster reads, the pure semver helper, and moderation (the
   flag, the operator's queue and decision). Every listing / card /
   partial / envelope around them is graph composition in the
   `marketplace*` modules — the origin card a mirror snapshots
   (`:remote-package-card`) included: a fn-def over the registry's shared
   remote dial."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.loaded :as loaded]
    [graphden.packages.registry-shared :as shared]
    [graphden.packages.semver :as semver]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(defbase current-user-label
  "A public-safe label for the current user (display name / email local
   part / org / `anonymous`) — what a review is signed with."
  []
  (tc/current-user-label))


(defbase loaded-packages
  "The executor's loaded-package roster (`graphden.packages.loaded`): one
   row per package `:app/packages` loaded at boot — name, version,
   description, modules, dependencies, base-fn count, `:kind`
   (`impl+fns` / `fns-only`) and `:origin` (`bundled` / `manifest`).
   Boot-constant — no effect."
  []
  (loaded/read-roster))


(defbase semver-rank
  "A version string as one sortable number — `major·10⁶ + minor·10³ +
   patch` (each component capped at 999) — the pure `:sort-by` key that
   orders a version list; nil / unparsable → 0."
  [version]
  (let [[ma mi pa] (or (semver/parse-version version) [0 0 0])
        cap #(min 999 (long (or % 0)))]
    (+ (* 1000000 (cap ma)) (* 1000 (cap mi)) (cap pa))))


(defbase moderation-on?
  "Whether this deployment moderates PUBLIC listings (`GRAPHDEN_MARKETPLACE_MODERATION`)."
  []
  (shared/moderation-enabled?))


(defbase moderate-package-version!
  "The operator's decision on a pending public listing — `decision` is
   `approve` or `reject` (with an optional `note` the publisher sees).
   Gated on the platform-admin right at the deepest effectful core, so no
   route bypasses it; a tenant / org-admin cannot list itself. Reads the
   row through the BASE storage (`vs/unwrap`) — the operator's org-scoped
   view would hide another org's pending row. The WRITE runs in the row's
   own org scope (`tc/with-org`): row level security lets an org update
   only its own rows, and the operator's decision is an action ON that
   org's row — the platform-admin check above is the authorization, the
   scope is how the decided write reaches the table (found by the addon's
   `marketplace_moderation_test`: without it the update touched no row
   and the decision was silently lost). Raises `:package-moderated`
   through the notification seam (`tc/notify!`) with the updated row —
   the tenancy addon mails the publisher. Returns the updated row, or nil
   when no such (name, version); throws `:moderation/not-applied` when
   the row was found but the write did not land."
  [pkg-name pkg-version decision note]
  (when-not (tc/current-platform-admin?)
    (throw (ex-info "Moderating a listing requires the platform-admin right."
                    {:type :authz/forbidden :capability :platform-admin})))
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [base (shared/platform-base (request/require-storage ctx))
        row (first (sp/query-entities base :package-version {:name pkg-name :version pkg-version}))
        status (case (str decision) "approve" "approved" "reject" "rejected" nil)]
    (when (and row status)
      (let [updated (tc/with-org (:org-id row)
                                 (sp/update-entity base :package-version (:id row)
                                                   {:status status
                                                    :moderation-note (when (= status "rejected") (some-> note str str/trim not-empty))
                                                    :moderated-at (java.time.Instant/now)}))]
        (when-not (= status (:status updated))
          (throw (ex-info "The moderation decision did not reach the row."
                          {:type :moderation/not-applied :name pkg-name :version pkg-version :org (:org-id row)})))
        (tc/notify! :package-moderated updated)
        updated))))


(defbase moderation-queue
  "Every listing awaiting a decision, across orgs — the operator's queue.
   Platform-admin only (the read itself goes through the BASE storage; an
   org-scoped read would show only the operator's own org). Newest first."
  []
  (when-not (tc/current-platform-admin?)
    (throw (ex-info "The moderation queue requires the platform-admin right."
                    {:type :authz/forbidden :capability :platform-admin})))
  (cr/record-effect! :db)
  (let [base (shared/platform-base (request/require-storage ctx))]
    (->> (sp/query-entities base :package-version {:status "pending"})
         (sort-by :published-at)
         reverse
         (mapv #(-> %
                    (select-keys [:id :name :version :kind :description :category :tags :org-id :published-at :status])
                    (update :id str)
                    (update :published-at str))))))


(def impls
  {:current-user-label current-user-label
   :loaded-packages loaded-packages
   ;; taint-propagate: answers one of the caller's own version strings
   :semver-rank semver-rank
   :moderation-on? moderation-on?
   ;; taint-propagate: the updated row carries the operator's note
   :moderate-package-version! {:impl moderate-package-version! :taint-propagate? true}
   :moderation-queue moderation-queue})
