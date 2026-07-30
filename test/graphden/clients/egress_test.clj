(ns graphden.clients.egress-test
  "Security-critical (task #5): the SSRF classifier must flag every internal /
   private range and pass genuine public addresses — a miss is a hole that
   lets a tenant reach the platform's internal network or cloud metadata. All
   cases use IP LITERALS via `InetAddress/getByName`, which parses without any
   DNS lookup, so the suite touches no network."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.clients.egress :as egress])
  (:import
    (java.net
      InetAddress)
    (okhttp3
      Dns)))


(defn- addr
  ^InetAddress [s]
  (InetAddress/getByName s))


(deftest internal-address?-flags-every-private-range
  (testing "loopback, any-local, multicast"
    (is (egress/internal-address? (addr "127.0.0.1")))
    (is (egress/internal-address? (addr "127.10.20.30")))
    (is (egress/internal-address? (addr "::1")))
    (is (egress/internal-address? (addr "0.0.0.0")))
    (is (egress/internal-address? (addr "224.0.0.1"))))
  (testing "link-local — INCLUDING the 169.254.169.254 cloud-metadata endpoint"
    (is (egress/internal-address? (addr "169.254.169.254")))
    (is (egress/internal-address? (addr "169.254.0.1")))
    (is (egress/internal-address? (addr "fe80::1"))))
  (testing "RFC1918 site-local (all three blocks)"
    (is (egress/internal-address? (addr "10.0.0.1")))
    (is (egress/internal-address? (addr "172.16.0.1")))
    (is (egress/internal-address? (addr "172.31.255.255")))
    (is (egress/internal-address? (addr "192.168.1.1"))))
  (testing "IPv4 CGNAT 100.64.0.0/10 (RFC 6598)"
    (is (egress/internal-address? (addr "100.64.0.1")))
    (is (egress/internal-address? (addr "100.127.255.255"))))
  (testing "IPv6 ULA fc00::/7 (RFC 4193)"
    (is (egress/internal-address? (addr "fc00::1")))
    (is (egress/internal-address? (addr "fd12:3456:789a::1")))))


(deftest internal-address?-passes-genuine-public-addresses
  (testing "well-known public IPs are NOT internal"
    (is (not (egress/internal-address? (addr "8.8.8.8"))))
    (is (not (egress/internal-address? (addr "1.1.1.1"))))
    (is (not (egress/internal-address? (addr "2001:4860:4860::8888")))))
  (testing "just-outside-the-range boundaries stay public (no over-blocking)"
    (is (not (egress/internal-address? (addr "172.15.255.255"))) "just below 172.16/12")
    (is (not (egress/internal-address? (addr "172.32.0.1"))) "just above 172.16/12")
    (is (not (egress/internal-address? (addr "100.63.255.255"))) "just below CGNAT 100.64/10")
    (is (not (egress/internal-address? (addr "100.128.0.1"))) "just above CGNAT 100.64/10")
    (is (not (egress/internal-address? (addr "11.0.0.1"))) "just above 10/8")))


(deftest check-target!-gates-outbound-urls
  (testing "a url resolving to an internal / private IP is blocked"
    (doseq [u ["http://127.0.0.1/x" "http://10.0.0.1/" "http://169.254.169.254/latest/meta-data/"
               "https://192.168.1.1:8080/" "http://[::1]/"]]
      (is (= :egress/blocked
             (try (egress/check-target! u) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          (str u " must be blocked"))))
  (testing "a public-IP url passes (no DNS — literal)"
    (is (nil? (egress/check-target! "http://8.8.8.8/path")))
    (is (nil? (egress/check-target! "https://1.1.1.1/"))))
  (testing "a url with no host is blocked"
    (is (= :egress/blocked
           (try (egress/check-target! "not-a-url") nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))


(deftest resolve-then-reject-wiring-blocks-a-hostname-that-lands-internal
  ;; The other cases feed IP LITERALS, which `getByName` parses without a lookup
  ;; — so they exercise `internal-address?` but NOT the "resolve the host, then
  ;; reject if any resolved address is internal" wiring that IS the DNS-rebind
  ;; guard. `localhost` is a real HOSTNAME that resolves (locally, no network) to
  ;; a loopback address, so it drives exactly that path: the rebind trick of
  ;; pointing a name at an internal IP must be caught.
  (testing "check-target! blocks a hostname resolving to an internal IP"
    (let [ed (try (egress/check-target! "http://localhost:8080/steal")
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :egress/blocked (:type ed)))
      (is (= :internal-target (:reason ed))
          "blocked because the resolved address is internal, not because the URL is malformed")))
  (testing "resolve-public-ips itself fails closed on a host that lands internal"
    (is (= :egress/blocked
           (try (egress/resolve-public-ips "localhost") nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))


(deftest jdbc-host-extracts-the-host-from-a-jdbc-url
  (testing "a jdbc:postgresql url (opaque to bare URI) parses to its host"
    (is (= "db.example.com" (egress/jdbc-host "jdbc:postgresql://db.example.com:5432/mydb")))
    (is (= "8.8.8.8" (egress/jdbc-host "jdbc:postgresql://8.8.8.8:5432/db")))
    (is (= "my-host" (egress/jdbc-host "jdbc:mysql://my-host/db"))))
  (testing "no host / unparseable → nil"
    (is (nil? (egress/jdbc-host "jdbc:h2:mem:test")))
    (is (nil? (egress/jdbc-host "not-a-url")))
    (is (nil? (egress/jdbc-host nil)))))


(deftest check-sql-target!-gates-jdbc-urls
  ;; The JDBC counterpart of check-target! — same SSRF surface (a tenant
  ;; datasource must not reach the platform's internal network), applied before a
  ;; connection is opened. IP literals parse without a lookup (no network).
  (testing "a jdbc url whose host is an internal / private IP is blocked"
    (doseq [u ["jdbc:postgresql://127.0.0.1:5432/x" "jdbc:postgresql://10.0.0.1/x"
               "jdbc:postgresql://169.254.169.254/x" "jdbc:mysql://192.168.1.1:3306/x"]]
      (is (= :egress/blocked
             (try (egress/check-sql-target! u) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          (str u " must be blocked"))))
  (testing "a hostname resolving to an internal IP is blocked (rebind path)"
    (let [ed (try (egress/check-sql-target! "jdbc:postgresql://localhost:5432/x") nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :egress/blocked (:type ed)))
      (is (= :internal-target (:reason ed)))))
  (testing "a jdbc url with no host is blocked"
    (is (= :egress/blocked
           (try (egress/check-sql-target! "jdbc:h2:mem:test") nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
  (testing "a public-IP jdbc host passes (no DNS — literal)"
    (is (nil? (egress/check-sql-target! "jdbc:postgresql://8.8.8.8:5432/db")))))


(deftest check-sql-target!-blocks-the-platform-db-host
  ;; Defense-in-depth for a platform DB on a PUBLIC endpoint (an internal one is
  ;; already blocked by the SSRF resolver): the installed seam rejects a tenant
  ;; datasource pointed at the platform's own DB host, before resolution.
  (let [saved @egress/platform-db-host?]
    (try
      (reset! egress/platform-db-host? #(= % "8.8.8.8"))
      (testing "a jdbc url targeting the platform DB host → :platform-db block"
        (let [ed (try (egress/check-sql-target! "jdbc:postgresql://8.8.8.8:5432/graphden") nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= :egress/blocked (:type ed)))
          (is (= :platform-db (:reason ed)))))
      (testing "a different public host still passes the platform-db check"
        (is (nil? (egress/check-sql-target! "jdbc:postgresql://1.1.1.1:5432/tenant"))))
      (finally (reset! egress/platform-db-host? saved)))))


(deftest check-egress-rate!-honours-the-installed-limiter
  ;; task #5b: the per-org outbound rate cap. The seam holds an org-agnostic
  ;; `(fn [] → bool)`; the addon closes org-keying over it.
  (let [saved @egress/egress-rate-limiter]
    (try
      (testing "no limiter installed → no-op (single-tenant / unrestricted)"
        (reset! egress/egress-rate-limiter nil)
        (is (nil? (egress/check-egress-rate!))))
      (testing "limiter allows → no throw"
        (reset! egress/egress-rate-limiter (constantly true))
        (is (nil? (egress/check-egress-rate!))))
      (testing "limiter denies → throws :egress/rate-limited"
        (reset! egress/egress-rate-limiter (constantly false))
        (is (= :egress/rate-limited
               (try (egress/check-egress-rate!) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (finally (reset! egress/egress-rate-limiter saved)))))


(defn- stream-of
  ^java.io.InputStream [^String s]
  (java.io.ByteArrayInputStream. (String/.getBytes s "UTF-8")))


(deftest read-capped-string!-bounds-the-response-body
  ;; task #5b: a STREAMING byte-cap — an oversize body is rejected mid-read,
  ;; never fully buffered.
  (let [saved @egress/max-response-bytes]
    (try
      (testing "no cap → reads the whole stream"
        (reset! egress/max-response-bytes nil)
        (is (= "hello world" (egress/read-capped-string! (stream-of "hello world")))))
      (testing "under the cap → reads in full"
        (reset! egress/max-response-bytes 100)
        (is (= "small" (egress/read-capped-string! (stream-of "small")))))
      (testing "over the cap → throws :egress/response-too-large"
        (reset! egress/max-response-bytes 4)
        (is (= :egress/response-too-large
               (try (egress/read-capped-string! (stream-of "way too long"))
                    nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (testing "exactly at the cap → allowed (boundary)"
        (reset! egress/max-response-bytes 5)
        (is (= "12345" (egress/read-capped-string! (stream-of "12345")))))
      (finally (reset! egress/max-response-bytes saved)))))


(deftest validating-dns-pins-to-validated-public-addresses
  ;; The OkHttp `Dns` hook is what closes the rebind TOCTOU: it resolves+
  ;; validates AT CONNECT TIME, so OkHttp can only ever dial a validated-public
  ;; address. IP literals parse without any DNS lookup — no network here.
  (testing "an internal target throws :egress/blocked (no internal connection dialed)"
    (is (= :egress/blocked
           (try (Dns/.lookup egress/validating-dns "127.0.0.1") nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
  (testing "a public target resolves through to its address, unchanged"
    (is (= ["8.8.8.8"]
           (mapv #(InetAddress/.getHostAddress ^InetAddress %)
                 (Dns/.lookup egress/validating-dns "8.8.8.8"))))))
