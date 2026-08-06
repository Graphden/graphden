(ns graphden.tenancy.context
  "Current-organization binding for tenant scoping
   (docs/TENANCY_SEAM.md § Context).

   This is the core-side SEAM the tenancy addon's `OrgScopedStorage` reads
   to scope every row. It mirrors the `*allowed-effects*` pattern exactly:
   a thread-local the request path binds from the authenticated principal,
   consumed lower down — here, by the storage decorator instead of the
   effect gate.

   The core default is the shared `\"public\"` org. A single-tenant
   deployment (no addon wired, no `:org` in the principal) therefore
   behaves exactly as before: every row is public, every read sees public.
   Only when the addon's provider resolves a real `:org` AND its storage
   decorator is wired does scoping take effect — making tenancy opt-in by
   construction (ADR §3.0)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]))


(def public-org
  "The shared, always-present organization. Core writes/reads here; it is
   the default tenant so single-tenant mode needs no org concept at all."
  "public")


(def ^:dynamic *current-org*
  "Org id in scope for the current execution. The addon's request path
   rebinds this from the authenticated principal (via `with-org`); the
   addon's `OrgScopedStorage` reads it through `current-org`. Unbound =
   the shared public org."
  public-org)


(defn current-org
  "The org id in scope right now (`public-org` when unbound)."
  []
  *current-org*)


(defn platform-tier?
  "Is `org` the shared platform tier (the `public` org, or an unbound/nil
   org that normalises to it)? Today this ALSO means \"trusted / operator\":
   every privileged short-circuit in the tenancy layer keys on it. The two
   meanings — the read-only shared package tier, and platform-admin
   authority — are unified here so the whole tree tests the tier through ONE
   predicate rather than three ad-hoc encodings (`(= org public-org)`, a
   literal `\"public\"`, and `\"\"` at the RLS/SQL layer). When operator power
   moves to a capability grant (Track A2), the authority meaning peels off
   this predicate while the tier meaning stays."
  [org]
  (or (nil? org) (= org public-org)))


(defn current-platform-tier?
  "`platform-tier?` of the org in scope right now."
  []
  (platform-tier? (current-org)))


;; Platform-admin predicate SEAM. Whether the current principal holds the
;; platform-admin capability is a POLICY question (it reads the principal's
;; grants), so the check lives in the tenancy addon. Core keeps only this
;; installable hook + the predicate that consults it, so an addon-less /
;; single-tenant instance has no operator escalation. The tenancy addon calls
;; `install-platform-admin-fn!` at wire time with its zero-arg
;; `grant/current-platform-admin?`.
(defonce ^:private platform-admin-fn (atom (constantly false)))


(defn install-platform-admin-fn!
  "Install the addon's zero-arg platform-admin predicate. `nil` restores the
   no-op default (no platform-admin)."
  [f]
  (reset! platform-admin-fn (or f (constantly false))))


(defn current-platform-admin?
  "True when the current principal holds the platform-admin capability — via the
   installed seam. False with no tenancy addon."
  []
  (boolean (@platform-admin-fn)))


(def ^:dynamic *current-principal*
  "The authenticated principal (`AuthProvider` result) for the current
   request, bound by the addon's request-scope. Read by per-namespace grant
   enforcement at the storage layer, which needs the `:user`. nil = no
   authenticated principal."
  nil)


(defn org-from-principal
  "The org id carried by an auth principal — the addon's provider sets
   `:org` (see `auth/AuthProvider`). Falls back to the shared public org
   when absent (single-token mode never sets `:org`)."
  [principal]
  (or (:org principal) public-org))


(defmacro with-org
  "Run `body` with `*current-org*` bound to `org` (nil → public-org)."
  [org & body]
  `(binding [*current-org* (or ~org ~public-org)]
     (let [res# (do ~@body)] res#)))


;; =============================================================================
;; Execution mode — hosted vs bring-your-own executor
;; =============================================================================

(def byo-execution-mode
  "`:org.execution-mode` value marking an org whose graph runs on the
   customer's OWN executor. The platform stores the graph but hosted pods
   refuse to run it. Anything else (incl. nil) is hosted."
  "byo")


(def ^:private byo-cache
  ;; {org → {:byo? bool :at millis}}. A short-TTL memo so the per-request byo
  ;; check on hosted pods isn't a `:org` read every time. `:execution-mode`
  ;; only changes at provisioning, so a few seconds of staleness is fine — a
  ;; freshly-flipped byo org is refused within the TTL.
  (atom {}))


;; Parallel-test isolation: bound per test-thread to a fresh atom (see the
;; kaocha parallel plugin's `isolation-vars`) so one test caching an org as
;; byo can't leak that verdict into a sibling test that shares the org name.
;; nil in production → the process-global `byo-cache`.
(def ^:dynamic *byo-cache-override* nil)


(defn- byo-cache-atom
  []
  (or *byo-cache-override* byo-cache))


(def ^:private byo-cache-ttl-ms 5000)


(defn byo-org?
  "True when `org`'s `:execution-mode` is byo — its graph runs on the
   customer's own executor, so a hosted pod must refuse to run it.

   MUST be called with `storage` readable and `*current-org*` NOT yet bound to
   `org` (the request-scope reads `:org` in the public context before binding
   the tenant org — `:org` is tenant-forbidden, so an org-scoped read would be
   hidden). The public org is never byo. Fails hosted (returns false) on a
   read error, so a DB blip doesn't 421 every tenant."
  [storage org]
  (if (or (nil? org) (= public-org org))
    false
    (let [cache (byo-cache-atom)
          now (System/currentTimeMillis)
          cached (get @cache org)]
      (if (and cached (< (- now (:at cached)) byo-cache-ttl-ms))
        (:byo? cached)
        (let [byo? (try
                     (= byo-execution-mode
                        (some-> (first (sp/query-entities storage :org {:name org}))
                                :execution-mode))
                     (catch Exception e
                       ;; Fail hosted for THIS request (a DB blip must
                       ;; not 421 every tenant) but do NOT cache the
                       ;; error-derived answer — caching pinned the
                       ;; org into cloud mode for the whole TTL on a
                       ;; transient failure, silently mis-routing a
                       ;; BYO org's execution. `::read-failed` skips
                       ;; the cache write; the next request re-reads.
                       (log/warn e "byo-mode read failed — treating as hosted for this request only"
                                 {:org org})
                       ::read-failed))]
          (if (= ::read-failed byo?)
            false
            (do (swap! cache assoc org {:byo? byo? :at now})
                byo?)))))))


(defn invalidate-byo-cache!
  "Drop the byo-mode memo (all orgs, or one). Call after writing an org's
   `:execution-mode` so the flip takes effect before the TTL elapses."
  ([] (reset! (byo-cache-atom) {}))
  ([org] (swap! (byo-cache-atom) dissoc org)))
