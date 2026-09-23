(ns graphden.web.client-ip
  "The client IP the per-IP rate limiters key on — shared by the accounts
   `/auth/*` limiters and the tenancy addon's (demo start, org auth routes),
   which used to carry a copy that had to be kept in step by hand."
  (:require
    [clojure.string :as str]))


(def trusted-proxy-count
  "How many trusted reverse proxies sit in front of the app, from
   `GRAPHDEN_TRUSTED_PROXIES` (default 0).

   `X-Forwarded-For` is `client, proxyA, proxyB` where each proxy
   APPENDS the peer it saw, so the N rightmost hops are added by the N
   proxies we control and everything to their left is client-supplied.
   With N trusted proxies the real client is the entry N positions from
   the end. **N=0 means the app is directly reachable: the entire
   header is attacker-controlled** and must be ignored — otherwise a
   rotating `X-Forwarded-For:` sails past every per-IP limiter. Cloud
   (behind Caddy) sets `GRAPHDEN_TRUSTED_PROXIES=1`."
  (or (some-> (System/getenv "GRAPHDEN_TRUSTED_PROXIES") parse-long) 0))


(defn client-ip
  "Best-effort client IP for rate-limiting. Trusts exactly `n-trusted`
   (default `trusted-proxy-count`) rightmost `X-Forwarded-For` hops; with 0
   trusted proxies the header is ignored entirely and the socket
   `:remote-addr` is used, so a forged header can't defeat the limiter on a
   directly-reachable deploy. Keying on the LEFTMOST, client-supplied hop
   would give every rotated header a fresh bucket."
  ([request] (client-ip request trusted-proxy-count))
  ([request n-trusted]
   (or (when (pos? n-trusted)
         (let [hops (some->> (get-in request [:headers "x-forwarded-for"])
                             (#(str/split % #","))
                             (mapv str/trim)
                             (filterv not-empty))]
           (when (>= (count hops) n-trusted)
             (nth hops (- (count hops) n-trusted)))))
       (:remote-addr request)
       "unknown")))
