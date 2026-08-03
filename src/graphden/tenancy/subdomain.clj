(ns graphden.tenancy.subdomain
  "Subdomain → org resolution (PLATFORM_PLAN §3.2). Resolves the org named by
   the request's `Host` — `acme.graphden.app` → org `acme`. Pure + an
   injectable `OrgResolver` (mirrors `auth.provider`); the identity default
   (label IS the org-id) is swappable for vanity aliases or a storage-backed
   table without touching the request path.

   SECURITY: this only NAMES the org the Host points at. The request-scope
   treats it as a GUARD, never an authority — the authenticated token (single-
   membership) is the org authority, and a subdomain that names a DIFFERENT
   org than the principal's is denied (cross-org). The subdomain can never
   WIDEN access, so a spoofed `Host` can't read another org's data.

   Inert in single-tenant: no resolver wired → `org-from-request` returns nil."
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


(defn extract-app-host
  "Parse a TWO-level app host `<label>.<org>.<base-domain>` into
   `{:label <label> :org <org>}`, or nil (Track C — an org's named apps).

   `shop.acme.graphden.app` → `{:label \"shop\" :org \"acme\"}`. The apex, a
   single-level `<org>.<base>` (that's the org's editor, not an app), and 3+
   level hosts all return nil. Strips a `:port` and lower-cases."
  [host base-domain]
  (when (and (string? host) (seq host) (string? base-domain) (seq base-domain))
    (let [h (-> host (str/split #":") first str/lower-case)
          suffix (str "." (str/lower-case base-domain))]
      (when (and (str/ends-with? h suffix)
                 (not= h (str/lower-case base-domain)))
        (let [sub (subs h 0 (- (count h) (count suffix)))
              parts (str/split sub #"\.")]
          (when (= 2 (count parts))
            (let [[label org] parts]
              (when (and (seq label) (seq org))
                {:label label :org org}))))))))


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
  "An `OrgResolver` backed by a literal `{subdomain org}` map. Only needed
   when a subdomain DIFFERS from its org-id (vanity aliases); for the plain
   `<org>.<base-domain>` case use `identity-org-resolver` — no map needed."
  [subdomain->org]
  (->StaticOrgResolver subdomain->org))


(defrecord IdentityOrgResolver
  []

  OrgResolver

  (org-for-subdomain [_ subdomain] subdomain))


(defn identity-org-resolver
  "The default `OrgResolver`: the subdomain label IS the org-id
   (`acme.<base-domain>` → org `acme`). No lookup table — orgs are string
   slugs, and an unknown slug just yields an empty tenant view (OrgScoped
   returns only public), so there's nothing to validate. Reach for
   `static-org-resolver` only for vanity aliases, and a hostname→org table
   only for custom domains (§3.2 R10)."
  []
  (->IdentityOrgResolver))


(def default-reserved-labels
  "Subdomain labels the PLATFORM keeps for itself — never resolved to a tenant
   org, so `app.<base-domain>` serves the editor/API (falls through like the
   apex) instead of routing to a tenant who registered org \"app\". The same
   set gates self-serve org creation (`users/signup!`), so the labels can't be
   squatted either. Operators can widen/replace the set via the
   `:tenancy/org-resolver` config's `:reserved`."
  #{"app" "www" "api" "admin" "mail" "smtp" "imap" "static" "assets" "cdn"
    "docs" "status" "editor" "demo" "vault" "metrics" "grafana"})


(defrecord ReservedAwareResolver
  [reserved inner]

  OrgResolver

  (org-for-subdomain
    [_ subdomain]
    (when-not (contains? reserved subdomain)
      (org-for-subdomain inner subdomain))))


(defn wrap-reserved
  "Wrap an `OrgResolver` so `reserved` labels resolve to nil (→ the request
   falls through to the platform editor/API, exactly like the apex)."
  [resolver reserved]
  (->ReservedAwareResolver (set reserved) resolver))


(defn reserved-org-name?
  "True when `name` is a platform-reserved subdomain label — self-serve org
   creation must refuse it (a tenant org by this name would either be
   unreachable or shadow a platform host)."
  ([name] (reserved-org-name? name default-reserved-labels))
  ([name reserved] (contains? (set reserved) (some-> name str/lower-case))))


(defn org-from-request
  "Resolve the org named by the request's `Host` subdomain, or nil. Nil when
   no resolver is wired, no `base-domain`, the host has no subdomain, or the
   subdomain isn't mapped — every case falls back to the principal's org."
  [resolver request base-domain]
  (when (and resolver base-domain)
    (when-let [sub (extract-subdomain (get-in request [:headers "host"]) base-domain)]
      (org-for-subdomain resolver sub))))


(defn app-from-request
  "Resolve a TWO-level app host `<label>.<org>.<base>` to `{:label <label>
   :org <org>}` (Track C), or nil. The middle label is resolved through the
   same `OrgResolver` as `org-from-request` — so a reserved / unmapped org
   position (`shop.app.<base>`) yields nil (→ not an app; falls through)."
  [resolver request base-domain]
  (when (and resolver base-domain)
    (when-let [{:keys [label org]} (extract-app-host (get-in request [:headers "host"]) base-domain)]
      (when-let [resolved (org-for-subdomain resolver org)]
        {:label label :org resolved}))))
