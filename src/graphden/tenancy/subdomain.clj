(ns graphden.tenancy.subdomain
  "Subdomain → org resolution (PLATFORM_PLAN §3.2). The tenancy addon can
   resolve the org from the request's `Host` header — `acme.graphden.app`
   → org `acme` — so a tenant reaches their workspace by URL, not just by
   token. Pure + an injectable `OrgResolver` (mirrors `auth.provider`), so
   the static-map default is swappable for a storage-backed `subdomain → org`
   table without touching the request path.

   Inert in single-tenant: no resolver wired → `org-from-request` returns nil
   and the request-scope falls back to the auth principal's org."
  (:require
    [clojure.string :as str]))


(defn extract-subdomain
  "The single-level subdomain of `host` under `base-domain`, or nil.

   `acme.graphden.app` / `graphden.app` → `\"acme\"`. Strips a `:port`,
   lower-cases, and rejects the apex (`graphden.app`), a bare host, and
   multi-level subdomains (`a.b.graphden.app`) — those don't name one org."
  [host base-domain]
  (when (and (string? host) (seq host) (string? base-domain) (seq base-domain))
    (let [h (-> host (str/split #":") first str/lower-case)
          suffix (str "." (str/lower-case base-domain))]
      (when (and (str/ends-with? h suffix)
                 (not= h (str/lower-case base-domain)))
        (let [sub (subs h 0 (- (count h) (count suffix)))]
          (when (and (seq sub) (not (str/includes? sub ".")))
            sub))))))


(defprotocol OrgResolver
  "Resolve a subdomain label to an org id (or nil when unmapped)."

  (org-for-subdomain [this subdomain]))


(defrecord StaticOrgResolver
  [subdomain->org]

  OrgResolver

  (org-for-subdomain
    [_ subdomain]
    (get subdomain->org subdomain)))


(defn static-org-resolver
  "An `OrgResolver` backed by a literal `{subdomain org}` map — the simplest
   wiring; swap for a storage-backed resolver in production."
  [subdomain->org]
  (->StaticOrgResolver subdomain->org))


(defn org-from-request
  "Resolve the org named by the request's `Host` subdomain, or nil. Nil when
   no resolver is wired, no `base-domain`, the host has no subdomain, or the
   subdomain isn't mapped — every case falls back to the principal's org."
  [resolver request base-domain]
  (when (and resolver base-domain)
    (when-let [sub (extract-subdomain (get-in request [:headers "host"]) base-domain)]
      (org-for-subdomain resolver sub))))
