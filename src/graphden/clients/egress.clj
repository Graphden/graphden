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

   NOTE (before the network tier goes GA): `check-target!` validates the
   resolved IPs but the base-fn still dials by HOSTNAME, so a name that
   re-resolves to a different IP between the check and the dial is a narrow
   residual rebind window. Pinning the dial to the validated IP (custom
   resolver / connect-by-IP + Host header) is the hardening follow-up."
  (:require
    [clojure.string :as str])
  (:import
    (java.net
      Inet4Address
      Inet6Address
      InetAddress
      URI
      UnknownHostException)))


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
