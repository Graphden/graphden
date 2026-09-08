(ns graphden.packages.app.marketplace.impls
  "Impls for the marketplace's own base-fns (docs/MARKETPLACE.md) — the
   identity + roster reads, the two pure semver helpers, the origin card
   dial a mirror snapshots, and moderation (the flag, the operator's queue
   and decision). Every listing / card / partial / envelope around them is
   graph composition in the `marketplace*` modules. The remote dial shares
   the registry impls' bearer + egress conventions
   (`graphden.packages.registry-shared`)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.clients.egress :as egress]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.loaded :as loaded]
    [graphden.packages.registry-shared :as shared]
    [graphden.packages.semver :as semver]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [org.httpkit.client :as http-client]))


(defbase current-user-id
  "The current request's user id as text — the accounts principal's
   `:user-id`, or `anonymous` on a deployment without per-user identity
   (`tenancy.context/current-user-id`, the seam)."
  []
  (tc/current-user-id))


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


(defbase semver-latest
  "The highest version string in `versions` by parsed `[major minor patch]`
   — nil for an empty list. Pure; the marketplace card's \"latest\" pick."
  [versions]
  (->> versions
       (map str)
       (remove str/blank?)
       (sort-by semver/parse-version)
       last))


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
   view would hide another org's pending row. Raises `:package-moderated`
   through the notification seam (`tc/notify!`) with the updated row —
   the tenancy addon mails the publishing org's owner. Returns the
   updated row, or nil when no such (name, version)."
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
      (let [updated (sp/update-entity base :package-version (:id row)
                                      {:status status
                                       :moderation-note (when (= status "rejected") (some-> note str str/trim not-empty))
                                       :moderated-at (java.time.Instant/now)})]
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


(defbase remote-package-card
  "The REMOTE registry's marketplace card for `pkg-name` (`GET
   <source>/api/marketplace?q=<name>&kind=any`, exact-name match) — the
   social signals a mirror snapshots as its `:origin` (docs/MARKETPLACE.md
   § 7). nil when the remote has no marketplace (an older graphden), is
   unreachable, or lists no such package — a mirror without signals is
   still a mirror. Egress-guarded in restricted executions like the other
   remote dials."
  [source pkg-name]
  (cr/record-effect! :network)
  (cr/record-effect! :env)
  (let [base (str/replace (str source) #"/+$" "")
        url (str base "/api/marketplace?kind=any&q="
                 (java.net.URLEncoder/encode (str pkg-name) "UTF-8"))
        _ (when (some? cr/*allowed-effects*) (egress/check-target! url))
        resp @(http-client/get url {:headers (shared/remote-auth-headers) :as :text :timeout 60000})]
    (when (and (nil? (:error resp)) (= 200 (:status resp)))
      (let [cards (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
        (when (sequential? cards)
          (some-> (first (filter #(= (str pkg-name) (str (:name %))) cards))
                  (select-keys [:rating :installs :version-count :latest :published-at])
                  (assoc :url base :as-of (str (java.time.Instant/now)))))))))


(def impls
  {:current-user-id current-user-id
   :current-user-label current-user-label
   :loaded-packages loaded-packages
   ;; taint-propagate: answers one of the caller's own version strings
   :semver-latest {:impl semver-latest :taint-propagate? true}
   :semver-rank semver-rank
   :remote-package-card remote-package-card
   :moderation-on? moderation-on?
   ;; taint-propagate: the updated row carries the operator's note
   :moderate-package-version! {:impl moderate-package-version! :taint-propagate? true}
   :moderation-queue moderation-queue})
