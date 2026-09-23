(ns graphden.web.client-ip-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web.client-ip :as web-ip]))


(defn- req
  [xff]
  (cond-> {:remote-addr "10.0.0.9" :headers {}}
    xff (assoc-in [:headers "x-forwarded-for"] xff)))


(deftest client-ip-trusts-only-the-proxies-we-run
  (testing "0 trusted proxies: the header is attacker-controlled and ignored"
    (is (= "10.0.0.9" (web-ip/client-ip (req "6.6.6.6, 1.2.3.4") 0))))
  (testing "1 trusted proxy: the hop it appended — never the client-supplied leftmost"
    (is (= "1.2.3.4" (web-ip/client-ip (req "6.6.6.6, 1.2.3.4") 1)))
    (is (= "1.2.3.4" (web-ip/client-ip (req " 1.2.3.4 ") 1))))
  (testing "fewer hops than trusted proxies → the socket address"
    (is (= "10.0.0.9" (web-ip/client-ip (req "1.2.3.4") 2))))
  (testing "no address at all → a shared bucket, not a crash"
    (is (= "unknown" (web-ip/client-ip {:headers {}} 0)))))
