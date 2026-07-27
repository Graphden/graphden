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
      InetAddress)))


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
