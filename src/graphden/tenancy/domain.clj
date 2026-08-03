(ns graphden.tenancy.domain
  "Custom domains (PLATFORM_PLAN §3.2, R10). Two pieces:

   1. REQUEST-TIME — `org-for-host` resolves a full hostname (`app.acme.com`)
      to an org via a verified `{hostname org}` map. The request-scope treats
      the result exactly like a subdomain: a GUARD, never an authority (the
      token's org wins; a host naming a different org → cross-org 403). So an
      unverified / spoofed Host can never widen access.

   2. PROVISIONING — `verify-domain-ownership` checks a DNS TXT record proves
      the org controls the domain before its `{hostname org}` row is trusted.
      A DNS lookup is a NETWORK effect, which tenant graphs are forbidden
      (`cloud-forbidden-effects`), so verification is a PRIVILEGED PLATFORM
      operation — a plain Clojure fn here, never a tenant-composable graph fn.
      This is the R10 subtlety: \"through the graph, but not by the user.\""
  (:require
    [clojure.string :as str]
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.util
      Hashtable)
    (javax.naming.directory
      Attribute
      Attributes
      InitialDirContext)))


;; --- request-time: hostname → org (a verified custom-domain map) ----------

(defprotocol HostResolver

  (org-for-host [this host])

  (target-for-host
    [this host]
    "The app target for a verified custom `host`: `{:org <org> :label <app>}`
     (`:label` only when the domain row pins a named app — Track C), or nil."))


(defrecord StaticHostResolver
  [host->org]

  HostResolver

  (org-for-host
    [_ host]
    (when host
      (get host->org (-> host (str/split #":") first str/lower-case))))


  (target-for-host
    [_ host]
    ;; A static `{hostname org}` map carries no per-host app-label — a config
    ;; custom domain always serves the org's default app.
    (when host
      (when-let [org (get host->org (-> host (str/split #":") first str/lower-case))]
        {:org org}))))


(defn static-host-resolver
  "A `HostResolver` over a literal `{hostname org}` map of VERIFIED custom
   domains. Strips a `:port` and lower-cases before lookup."
  [host->org]
  (->StaticHostResolver host->org))


(defn- normalize-host
  [host]
  (-> host (str/split #":") first str/lower-case))


(defrecord StorageHostResolver
  [storage]

  HostResolver

  (org-for-host
    [_ host]
    (when host
      (let [row (first (sp/query-entities storage :domain {:hostname (normalize-host host)}))]
        ;; ONLY verified rows resolve — an unverified host falls through to the
        ;; subdomain / token, so a half-provisioned domain never routes.
        (when (:verified? row)
          (:org row)))))


  (target-for-host
    [_ host]
    (when host
      (let [row (first (sp/query-entities storage :domain {:hostname (normalize-host host)}))]
        (when (:verified? row)
          ;; Track C: a row's `:app-label` pins the host at that named app;
          ;; absent → the org's default handler (the label-less target).
          (cond-> {:org (:org row)}
            (:app-label row) (assoc :label (:app-label row))))))))


(defn storage-host-resolver
  "A `HostResolver` over the `:domain` entity (§3.4 #2) — `hostname → org` from
   storage, so custom domains are provisionable without a redeploy. `storage`
   should be the BASE storage (app-routing reads in the platform context).
   Resolves only VERIFIED rows."
  [storage]
  (->StorageHostResolver storage))


(defn org-from-request
  "The org bound to the request's full `Host` (a verified custom domain), or
   nil. Nil → the request-scope falls through to the subdomain / token."
  [resolver request]
  (when resolver
    (org-for-host resolver (get-in request [:headers "host"]))))


(defn target-from-request
  "The app target for the request's `Host` via a verified custom domain
   (Track C): `{:org <org> :label <app>}` — `:label` only when the domain row
   pins a named app; otherwise `{:org <org>}` (the org's default). nil when no
   verified domain matches → the request falls through to the subdomain/token."
  [resolver request]
  (when resolver
    (target-for-host resolver (get-in request [:headers "host"]))))


;; --- provisioning: DNS TXT ownership verification (privileged) -------------

(def verification-key
  "TXT prefix the org publishes to prove control: `graphden-verify=<token>`."
  "graphden-verify=")


(defn dns-txt-records
  "Every TXT string for `hostname` via JNDI DNS (strips surrounding quotes /
   splits space-separated chunks). A real network call — platform-only. Best
   effort: returns [] on any lookup failure."
  [hostname]
  (try
    (let [env (doto (Hashtable.)
                (Hashtable/.put "java.naming.factory.initial" "com.sun.jndi.dns.DnsContextFactory"))
          ctx (InitialDirContext. env)
          attrs (InitialDirContext/.getAttributes ctx ^String hostname (into-array String ["TXT"]))
          txt (some-> attrs (Attributes/.get "TXT"))]
      (if txt
        (->> (enumeration-seq (Attribute/.getAll txt))
             (map #(str/replace (str %) #"\"" ""))
             (vec))
        []))
    (catch Exception _ [])))


(defn verify-domain-ownership
  "True iff `hostname` publishes a TXT record `graphden-verify=<token>`.
   `lookup-txt` (default `dns-txt-records`) is injectable so the decision is
   unit-testable without real DNS. Run ONLY platform-side — it makes a network
   call tenant graphs can't."
  ([hostname token] (verify-domain-ownership hostname token dns-txt-records))
  ([hostname token lookup-txt]
   (boolean
     (when (and (seq hostname) (seq token))
       (let [expected (str verification-key token)]
         (some #(= % expected) (lookup-txt hostname)))))))
