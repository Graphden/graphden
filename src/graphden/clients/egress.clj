(ns graphden.clients.egress
  "SSRF egress guard (task #5). A tenant graph's outbound `:network` call (the
   `web/http-client` base-fns, external `web/sql`) must not be able to reach
   the platform's INTERNAL surface — loopback, the RFC1918 / CGNAT private
   ranges, IPv6 ULA, link-local (which includes the cloud-metadata endpoint
   169.254.169.254 that hands out IAM credentials), any-local or multicast.

   `internal-address?` is the pure classifier (the security heart, tested in
   isolation over the full range matrix). `resolve-public-ips` resolves a host
   and FAILS CLOSED — an unresolvable host, or one whose resolution includes
   ANY non-public address, is rejected — which also blocks the DNS-rebind
   trick of pointing a public name at an internal IP. `check-target!` is the
   gate the base-fns call before dialing; it throws `:egress/blocked`.

   `validating-dns` closes the DNS-rebind TOCTOU: it wires `resolve-public-ips`
   into OkHttp's resolver hook, so a RESTRICTED tenant call resolves-and-
   validates AT CONNECT TIME and OkHttp dials exactly those validated
   addresses — there is no second, unvalidated resolution between check and
   dial. TLS (SNI / Host / cert verification) stays on the hostname. The
   base-fns build their client with it (see `web/http-client`)."
  (:require
    [clojure.string :as str])
  (:import
    (java.net
      Inet4Address
      Inet6Address
      InetAddress
      URI
      UnknownHostException)
    (okhttp3
      Dns)))


(defn- cgnat?
  "IPv4 100.64.0.0/10 (RFC 6598 carrier-grade NAT) — not flagged by
   `InetAddress.isSiteLocalAddress`, but shared/private space all the same."
  [^Inet4Address addr]
  (let [b (InetAddress/.getAddress addr)
        o0 (bit-and (aget b 0) 0xff)
        o1 (bit-and (aget b 1) 0xff)]
    (and (= o0 100) (<= 64 o1 127))))


(defn- ipv6-ula?
  "IPv6 fc00::/7 (RFC 4193 unique-local) — `isSiteLocalAddress` only covers the
   deprecated fec0::/10, so ULA needs an explicit high-bits check."
  [^Inet6Address addr]
  (= 0xfc (bit-and (aget (InetAddress/.getAddress addr) 0) 0xfe)))


(defn internal-address?
  "True when `addr` is NOT a routable PUBLIC address and so must never be
   reachable from a tenant's outbound request: loopback, link-local (incl.
   169.254.169.254 cloud-metadata), site-local (RFC1918), any-local,
   multicast, IPv4 CGNAT (100.64/10), or IPv6 ULA (fc00::/7)."
  [^InetAddress addr]
  (boolean
    (or (InetAddress/.isLoopbackAddress addr)
        (InetAddress/.isLinkLocalAddress addr)
        (InetAddress/.isSiteLocalAddress addr)
        (InetAddress/.isAnyLocalAddress addr)
        (InetAddress/.isMulticastAddress addr)
        (and (instance? Inet4Address addr) (cgnat? addr))
        (and (instance? Inet6Address addr) (ipv6-ula? addr)))))


(defn resolve-public-ips
  "Resolve `host` to its addresses, FAILING CLOSED: throws `:egress/blocked`
   when the host can't be resolved OR any resolved address is
   `internal-address?` (so a public name pointed at an internal IP — DNS
   rebinding — is rejected). Returns the resolved addresses on success."
  [host]
  (let [addrs (try
                (seq (InetAddress/getAllByName host))
                (catch UnknownHostException _
                  (throw (ex-info (str "egress blocked: cannot resolve host " host)
                                  {:type :egress/blocked :host host :reason :unresolvable}))))]
    (when-let [bad (first (filter internal-address? addrs))]
      (throw (ex-info (str "egress blocked: " host " resolves to a non-public address "
                           (InetAddress/.getHostAddress bad))
                      {:type :egress/blocked :host host :reason :internal-target
                       :address (InetAddress/.getHostAddress bad)})))
    addrs))


(defn check-target!
  "Gate an outbound `url` before dialing: parse its host and run
   `resolve-public-ips`. Throws `:egress/blocked` for a non-public / rebinding
   / unparseable target; returns nil when the target is safe."
  [url]
  (let [host (try (URI/.getHost (URI. (str url)))
                  (catch Exception _ nil))]
    (when (str/blank? host)
      (throw (ex-info (str "egress blocked: no host in url " url)
                      {:type :egress/blocked :url url :reason :no-host})))
    (resolve-public-ips host)
    nil))


;; --- SQL egress (external tenant database) -----------------------------------
;;
;; `web/sql`'s `sql-exec` / `sql-query` dial a caller-supplied JDBC url, so the
;; SAME SSRF surface as HTTP applies: a tenant must not point a datasource at the
;; platform's internal network (its own Postgres, cloud metadata, an internal
;; service on 5432). `check-sql-target!` is the JDBC counterpart of
;; `check-target!` — it validates the url's host before a connection is opened.
;; Only the RESTRICTED (tenant) path calls it; a single-tenant / platform ctx
;; dials unguarded (the sql impls gate on `*allowed-effects*`).

(defonce ^{:doc "Installed by the addon: `(fn [host] → bool)` — true ⇒ `host` is
                 the PLATFORM's own database, which a tenant datasource must never
                 target (cross-tenant read). Internal platform DBs are already
                 covered by `resolve-public-ips` (they resolve to a private
                 address); this seam additionally blocks a platform DB reachable
                 on a PUBLIC endpoint (e.g. a managed instance). nil ⇒ no
                 platform-DB restriction (single-tenant)."}
  platform-db-host?
  (atom nil))


(defn jdbc-host
  "Host component of a JDBC url. A `jdbc:` url is opaque to `URI` (the `jdbc:`
   scheme makes the rest scheme-specific), so strip the `jdbc:` prefix first,
   then parse `postgresql://host:5432/db` as a URI. nil when no host is present.
   Public so the addon can derive the platform-DB host for `platform-db-host?`."
  [url]
  (let [s (str url)
        stripped (if (str/starts-with? s "jdbc:") (subs s 5) s)]
    (try (URI/.getHost (URI. stripped)) (catch Exception _ nil))))


(def ^{:private true
       :doc "JDBC connect params a tenant url must NOT carry. These load an
             arbitrary class or plug a custom transport into the driver, which
             would subvert the SSRF guard entirely (a `socketFactory` /
             `sslfactory` / `sslhostnameverifier` / `authenticationPluginClassName`
             can dial wherever it likes and ignore our resolved-address check),
             or turn on multi-host failover that reaches a second, unchecked host
             (`loadBalanceHosts`). Compared case-insensitively. Denylist, not
             allowlist, so ordinary connect options (`ssl`, `sslmode`,
             `connectTimeout`, `ApplicationName`, …) keep working."}
  unsafe-jdbc-params
  #{"socketfactory" "socketfactoryarg"
    "sslfactory" "sslfactoryarg"
    "sslpasswordcallback" "sslhostnameverifier"
    "authenticationpluginclassname"
    "loadbalancehosts"})


(defn- jdbc-param-names
  "Lower-cased set of connect-param KEYS in a JDBC `url`. Driver-agnostic: split
   on `? ; &` (Postgres/MySQL use `?…&…`, some drivers use `;…`), keep only
   `k=v` tokens, take the key. The leading `scheme://host/db` token has no `=`
   and is skipped."
  [url]
  (->> (str/split (str url) #"[?;&]")
       (keep (fn [tok]
               (when (str/includes? tok "=")
                 (-> tok (str/split #"=" 2) first str/trim str/lower-case))))
       set))


(defn reject-unsafe-jdbc-params!
  "Throw `:egress/blocked :reason :unsafe-jdbc-param` if a user-supplied JDBC
   `url` sets any `unsafe-jdbc-params` connect option (a class-loading /
   custom-transport gadget that would bypass the SSRF guard). Returns nil when
   the url carries none. Called by `check-sql-target!`; also usable standalone
   at any other JDBC-url intake seam (e.g. BYO executor config)."
  [url]
  (when-let [bad (first (filter unsafe-jdbc-params (jdbc-param-names url)))]
    (throw (ex-info (str "egress blocked: jdbc url sets a disallowed connect param `" bad "`")
                    {:type :egress/blocked :url url :reason :unsafe-jdbc-param
                     :param bad})))
  nil)


(defn check-sql-target!
  "Gate an outbound JDBC `url` before connecting (RESTRICTED tenant path): parse
   its host, reject the platform's own DB (installed `platform-db-host?` —
   cross-tenant), reject a class-loading / custom-transport connect param
   (`reject-unsafe-jdbc-params!` — E2), then reject a non-public / rebinding
   target (`resolve-public-ips`, which also covers an internal platform DB).
   Throws `:egress/blocked`; returns nil when the target is safe.

   SECURITY NOTE (E1 — residual JDBC SSRF TOCTOU): this resolves+validates the
   host, but next.jdbc/pgjdbc opens the socket with its OWN, SECOND DNS
   resolution — unlike the HTTP path, which pins OkHttp to our validated
   addresses via `validating-dns`. So a name that resolves public HERE but
   internal a few ms later (DNS rebinding) could still land on an internal IP
   at connect time. This gap is ACCEPTED, not closed, because pgjdbc exposes no
   per-connection validating resolver — its only address-pinning hook is
   `socketFactory`, which is itself the E2 gadget we deny above (so we cannot
   safely pin without re-opening a worse hole). Mitigations that DO hold at
   connect time: the deny-list re-checks the FIRST resolution (private ranges +
   platform-DB) and rejects fast; the rebind window is sub-second; and even a
   won race only reaches a host reachable from the executor, never arbitrary
   in-process capability. If pgjdbc later ships a resolver/validating-socket
   hook, wire it here the way `validating-dns` pins OkHttp and delete this note."
  [url]
  (let [host (jdbc-host url)]
    (when (str/blank? host)
      (throw (ex-info (str "egress blocked: no host in jdbc url " url)
                      {:type :egress/blocked :url url :reason :no-host})))
    (when-let [pred @platform-db-host?]
      (when (pred host)
        (throw (ex-info "egress blocked: the platform database is not a valid tenant target"
                        {:type :egress/blocked :host host :reason :platform-db}))))
    (reject-unsafe-jdbc-params! url)
    (resolve-public-ips host)
    nil))


(def validating-dns
  "An OkHttp `Dns` that resolves a hostname to its PUBLIC addresses only, via
   `resolve-public-ips` — the SAME validation `check-target!` runs, but applied
   AT CONNECT TIME. OkHttp dials exactly the addresses this returns while
   keeping the hostname for SNI / Host / certificate verification, so there is
   no second, unvalidated resolution: the DNS-rebind TOCTOU is closed. Throws
   `:egress/blocked` (surfaced by OkHttp as the call failure) when the host
   resolves to any internal address or can't be resolved. Stateless — one
   shared instance. Build the RESTRICTED tenant HTTP client with this."
  (reify Dns
    (lookup
      [_ hostname]
      ;; PersistentVector implements java.util.List<InetAddress>, which is the
      ;; Dns.lookup contract.
      (vec (resolve-public-ips hostname)))))


;; --- Per-org egress rate-limit + response byte-cap (task #5b) -----------------
;;
;; `check-target!` (above) is SSRF — it says WHERE a tenant may dial. These two
;; seams bound HOW MUCH: how often, and how big a response. They are installed by
;; the tenancy addon (closed over the plan/config); nil = unlimited (the pure
;; SSRF classifier stays usable single-tenant). Layering stays clean — the org
;; keying lives in the installed closure, so this clients-layer ns never depends
;; on tenancy. Both only fire for a RESTRICTED (tenant/cloud) execution — the
;; http-client hook guards on `*allowed-effects*`.

(defonce ^{:doc "Installed by the addon: `(fn [] → bool)` — false ⇒ the current
                 tenant is over its per-org egress rate. nil ⇒ unlimited."}
  egress-rate-limiter
  (atom nil))


(defonce ^{:doc "Installed by the addon: max bytes a tenant egress RESPONSE body
                 may carry (a long), enforced by a bounded streaming read in the
                 http-client. nil ⇒ uncapped."}
  max-response-bytes
  (atom nil))


(defn check-egress-rate!
  "Throw `:egress/rate-limited` when the installed per-org limiter denies this
   tenant's outbound call. No-op when no limiter is installed (single-tenant /
   unrestricted). Call BEFORE dialing, alongside `check-target!`."
  []
  (when-let [limiter @egress-rate-limiter]
    (when-not (limiter)
      (throw (ex-info "egress blocked: per-org outbound rate limit exceeded"
                      {:type :egress/rate-limited :reason :rate-exceeded})))))


(defn read-capped-string!
  "Read `input-stream` to a UTF-8 string, but no more than `@max-response-bytes`.
   Throws `:egress/response-too-large` the moment the body would exceed the cap —
   a STREAMING bound, so an oversize response is never fully buffered. When no cap
   is installed, reads the whole stream. Always closes the stream."
  [^java.io.InputStream input-stream]
  (let [cap @max-response-bytes]
    (with-open [in input-stream
                out (java.io.ByteArrayOutputStream.)]
      (let [buf (byte-array 8192)]
        (loop [total 0]
          (let [n (java.io.InputStream/.read in buf)]
            (if (neg? n)
              (String. (java.io.ByteArrayOutputStream/.toByteArray out) "UTF-8")
              (let [total' (+ total n)]
                (when (and cap (> total' cap))
                  (throw (ex-info (str "egress blocked: response body exceeds the "
                                       cap "-byte cap")
                                  {:type :egress/response-too-large
                                   :reason :body-too-large :cap cap})))
                (java.io.ByteArrayOutputStream/.write out buf 0 n)
                (recur total')))))))))
