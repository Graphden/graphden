(ns graphden.accounts.crypto-test
  "The accounts module's crypto primitives, and — the reason this namespace
   exists — its rate limiters.

   `fixed-window-limiter` is the only thing standing between a password
   list and `/auth/login`: `accounts.routes` caps login at 10/min, signup
   at 20/min, forgot-password at 5/min and TOTP entry at 10/min from ONE
   limiter each. Until this file, none of that had a test — a limiter that
   silently let everything through would have shipped green.

   Windows here are milliseconds, not minutes, so the expiry cases cost
   sleeps measured in tenths of a second. The digests are pinned to
   published vectors (FIPS 180-4 for SHA-256, RFC 4231 for HMAC) rather
   than to whatever the implementation currently emits — a self-consistent
   test would survive swapping the algorithm out from under it, which is
   exactly the change worth catching."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.accounts.crypto :as crypto])
  (:import
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))


;; =============================================================================
;; Digests — pinned to published test vectors
;; =============================================================================

(deftest sha256-hex-matches-the-published-vectors-test
  (testing "FIPS 180-4 examples"
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (crypto/sha256-hex "")))
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (crypto/sha256-hex "abc"))))
  (testing "UTF-8, not the platform default charset"
    ;; The digest of a non-ASCII string is the one thing a default-charset
    ;; bug changes without changing anything else, so a container with a
    ;; different LANG would hash differently from the machine that wrote
    ;; the row.
    (is (= "e34f6dec12c4f4599eba078f31ae8139420d21b1bd2d7ced7d22b09c2074fb48"
           (crypto/sha256-hex "тест"))
        "recompute with: printf 'тест' | sha256sum"))
  (testing "sha256-bytes is the same digest, undecoded"
    (is (= 32 (count (crypto/sha256-bytes "abc"))))))


(deftest hmac-sha256-hex-matches-rfc-4231-test
  (testing "RFC 4231 test case 1"
    (is (= "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
           (crypto/hmac-sha256-hex (byte-array (repeat 20 (byte 0x0b))) "Hi There"))))
  (testing "RFC 4231 test case 2"
    (is (= "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
           (crypto/hmac-sha256-hex (String/.getBytes "Jefe" "UTF-8")
                                   "what do ya want for nothing?"))))
  (testing "the key is part of the output"
    (is (not= (crypto/hmac-sha256-hex (String/.getBytes "k1" "UTF-8") "msg")
              (crypto/hmac-sha256-hex (String/.getBytes "k2" "UTF-8") "msg")))))


;; =============================================================================
;; Tokens and comparison
;; =============================================================================

(deftest random-token-is-url-safe-and-unguessable-test
  (let [tokens (repeatedly 200 crypto/random-token)]
    (testing "base64url alphabet, no padding — safe in a path or a query"
      (doseq [t (take 5 tokens)]
        (is (re-matches #"[A-Za-z0-9_-]+" t) (str "not url-safe: " t))))
    (testing "32 random bytes → 43 unpadded base64 chars"
      (is (= #{43} (set (map count tokens)))))
    (testing "no repeats — a counter or a seeded PRNG would collide here"
      (is (= 200 (count (set tokens)))))))


(deftest constant-time-equal?-is-nil-safe-and-correct-test
  (is (true? (crypto/constant-time-equal? "abc" "abc")))
  (is (false? (crypto/constant-time-equal? "abc" "abd")))
  (testing "length differences are not equality"
    (is (false? (crypto/constant-time-equal? "abc" "abcd")))
    (is (false? (crypto/constant-time-equal? "" "a"))))
  (testing "nil never compares equal — a missing HMAC must not authenticate"
    (is (false? (crypto/constant-time-equal? nil nil)))
    (is (false? (crypto/constant-time-equal? "abc" nil)))
    (is (false? (crypto/constant-time-equal? nil "abc"))))
  (testing "non-ASCII compares by UTF-8 bytes"
    (is (true? (crypto/constant-time-equal? "тест" "тест")))
    (is (false? (crypto/constant-time-equal? "тест" "тесТ")))))


;; =============================================================================
;; fixed-window-limiter — the login/signup/forgot/TOTP guard
;; =============================================================================

(deftest fixed-window-limiter-enforces-the-cap-test
  (let [allow? (crypto/fixed-window-limiter 3 60000)]
    (is (= [true true true false false]
           (mapv (fn [_] (allow? "1.2.3.4")) (range 5)))
        "3 per window means the 4th attempt is refused")))


(deftest fixed-window-limiter-keys-are-independent-test
  (let [allow? (crypto/fixed-window-limiter 2 60000)]
    (is (true? (allow? "attacker")))
    (is (true? (allow? "attacker")))
    (is (false? (allow? "attacker")) "attacker is out of budget")
    (testing "a different key still has its full budget"
      (is (true? (allow? "innocent")))
      (is (true? (allow? "innocent")))
      (is (false? (allow? "innocent"))))))


(deftest fixed-window-limiter-window-expires-test
  (let [window 150
        allow? (crypto/fixed-window-limiter 2 window)]
    (is (true? (allow? "k")))
    (is (true? (allow? "k")))
    (is (false? (allow? "k")))
    (Thread/sleep (long (* 2 window)))
    (is (true? (allow? "k")) "the window rolled — the budget is back")))


(deftest fixed-window-limiter-refusals-do-not-extend-the-window-test
  ;; The docstring's claim, and the one that decides whether a locked-out
  ;; user can ever get back in: a denied attempt must not be recorded, or a
  ;; client that keeps retrying holds its own window open forever.
  (let [window 200
        allow? (crypto/fixed-window-limiter 1 window)]
    (is (true? (allow? "k")))
    (dotimes [_ 20] (is (false? (allow? "k"))))
    (Thread/sleep (long (* 1.5 window)))
    (is (true? (allow? "k"))
        "20 refused attempts must not have pushed the window forward")))


(deftest fixed-window-limiter-is-atomic-under-concurrency-test
  ;; A read-then-swap limiter lets two threads both observe "under the cap"
  ;; and both pass. With N threads released at once against a cap of 5,
  ;; exactly 5 must win — anything more is the race the swap-vals! is there
  ;; to prevent, and it is the difference between a 10/min login cap and no
  ;; cap at all against a parallel attacker.
  (let [threads 32
        cap 5
        allow? (crypto/fixed-window-limiter cap 60000)
        start (CountDownLatch. 1)
        done (CountDownLatch. threads)
        wins (atom 0)]
    (dotimes [_ threads]
      (.start (Thread. ^Runnable
               (fn []
                 (CountDownLatch/.await start)
                 (when (allow? "shared-key") (swap! wins inc))
                 (CountDownLatch/.countDown done)))))
    (CountDownLatch/.countDown start)
    (is (true? (CountDownLatch/.await done 30 TimeUnit/SECONDS))
        "all racing threads finished")
    (is (= cap @wins)
        (str "exactly " cap " attempts may pass, got " @wins))))


;; =============================================================================
;; per-key-fixed-window-limiter — the shared-state, per-call-cap variant
;; =============================================================================

(deftest per-key-limiter-applies-the-cap-given-at-the-call-test
  ;; One window state, different budgets per key — how routes.clj could give
  ;; forgot-password a tighter cap than login without a second limiter.
  (let [allow? (crypto/per-key-fixed-window-limiter 60000)]
    (is (= [true false] (mapv (fn [_] (allow? "tight" 1)) (range 2))))
    (is (= [true true true false] (mapv (fn [_] (allow? "loose" 3)) (range 4))))
    (testing "the cap is read per call, not remembered from the first one"
      (is (false? (allow? "loose" 3)) "still exhausted at 3")
      (is (true? (allow? "loose" 4)) "raising the cap frees one more slot"))))


(deftest per-key-limiter-prunes-idle-keys-test
  ;; Bounded by ACTIVE keys, not by every key ever seen — otherwise a
  ;; spray of unique client IPs is an unbounded memory leak in a public
  ;; endpoint. Observed through behaviour: after the window, an old key
  ;; behaves exactly like one never seen.
  (let [window 120
        allow? (crypto/per-key-fixed-window-limiter window)]
    (doseq [i (range 50)] (is (true? (allow? (str "ip-" i) 1))))
    (Thread/sleep (long (* 2 window)))
    (is (true? (allow? "ip-0" 1)) "an expired key starts fresh")
    (is (true? (allow? "brand-new" 1)))))


(deftest limiter-keys-are-compared-by-value-test
  ;; Keys arrive as strings built from request data; two equal strings must
  ;; share a bucket even when they are different objects.
  (let [allow? (crypto/fixed-window-limiter 1 60000)]
    (is (true? (allow? (str/join ["1.2." "3.4"]))))
    (is (false? (allow? "1.2.3.4")) "same key by value, same bucket")))
