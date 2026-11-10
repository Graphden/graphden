(ns graphden.packages.records.ids-test
  "Anonymous fn identity is per ORG on a tenant path and unchanged on the
   platform tier — the ids package sync has always produced."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records.ids :as ids]
    [graphden.tenancy.context :as tc]
    [graphden.util.hex :as hex]))


(deftest hex-byte-encoding-is-runtime-independent
  (is (= "" (hex/encode (byte-array 0))))
  (is (= (apply str (map #(format "%02x" %) (range 256)))
         (hex/encode (byte-array (map unchecked-byte (range 256)))))))


(deftest anonymous-fn-id-is-per-org
  (let [h (ids/shape-hash {:a :int})
        platform (ids/anonymous-fn-id h)]
    (testing "the platform tier (unbound, or the public org) keeps the shape-only id"
      (is (= platform (tc/with-org tc/public-org (ids/anonymous-fn-id h))))
      (is (= platform (ids/anonymous-fn-id h)) "deterministic"))
    (testing "a tenant's id differs from the platform's and from another tenant's"
      (let [acme (tc/with-org "acme" (ids/anonymous-fn-id h))
            beta (tc/with-org "beta" (ids/anonymous-fn-id h))]
        (is (not= platform acme))
        (is (not= acme beta))
        (is (= acme (tc/with-org "acme" (ids/anonymous-fn-id h))) "stable within the org")))))


(deftest digest-hex-retains-lowercase-utf8-digests-test
  ;; Fixed vectors protect stored identities and provenance across formatter changes.
  (doseq [[algorithm input expected]
          [["SHA-1" "" "da39a3ee5e6b4b0d3255bfef95601890afd80709"]
           ["SHA-1" "abc" "a9993e364706816aba3e25717850c26c9cd0d89d"]
           ["SHA-1" "Привет 🌍" "7e704a14dd0767635e3aa97e1aaa1b8e19425452"]
           ["SHA-256" "" "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]
           ["SHA-256" "abc" "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"]
           ["SHA-256" "Привет 🌍" "d415d2646823ba3dd5ca460a26bd0e0cc066770bbdff46c7517bf70336b01fde"]
           ["SHA-512" "" "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"]
           ["SHA-512" "abc" "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"]
           ["SHA-512" "Привет 🌍" "68f6ddbffd47d58d4b239e5ae584282ba23d210c9cb58adabd8868d19cc53e64a360f791256b4097ecabba6c88116750252dfb662a866bac6eb13768d73b5a9d"]]]
    (is (= expected (ids/digest-hex algorithm input)))))
