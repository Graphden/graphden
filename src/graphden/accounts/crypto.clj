(ns graphden.accounts.crypto
  "Small crypto primitives shared across the accounts module: SHA-256, HMAC-
   SHA256, a CSPRNG token, and a constant-time compare. One home so hashing is
   defined once (DRY) and every call site agrees byte-for-byte."
  (:require
    [clojure.string :as str])
  (:import
    (java.security
      MessageDigest
      SecureRandom)
    (java.util
      Base64
      Base64$Encoder)
    (javax.crypto
      Mac)
    (javax.crypto.spec
      SecretKeySpec)))


(defn sha256-bytes
  ^bytes [^String s]
  (MessageDigest/.digest (MessageDigest/getInstance "SHA-256")
                         (String/.getBytes s "UTF-8")))


(defn- hex
  [^bytes bs]
  (str/join (map #(format "%02x" (bit-and % 0xff)) bs)))


(defn sha256-hex
  [^String s]
  (hex (sha256-bytes s)))


(defn hmac-sha256-hex
  "Lower-case hex HMAC-SHA256 of `message` under `key-bytes`."
  [^bytes key-bytes ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (Mac/.init mac (SecretKeySpec. key-bytes "HmacSHA256"))
    (hex (Mac/.doFinal mac (String/.getBytes message "UTF-8")))))


(defn random-bytes
  ^bytes [n]
  (let [b (byte-array n)]
    (SecureRandom/.nextBytes (SecureRandom.) b)
    b))


(defn random-token
  "A high-entropy URL-safe token (32 random bytes, base64url, no padding)."
  []
  (Base64$Encoder/.encodeToString (Base64$Encoder/.withoutPadding (Base64/getUrlEncoder))
                                  (random-bytes 32)))


(defn constant-time-equal?
  "Constant-time compare of two strings (fixed-shape byte compare). Guards HMAC
   verification against timing side-channels."
  [^String a ^String b]
  (boolean
    (when (and a b)
      (MessageDigest/isEqual (String/.getBytes a "UTF-8") (String/.getBytes b "UTF-8")))))


(defn per-key-fixed-window-limiter
  "A per-key fixed-window rate limiter whose cap is supplied PER CALL:
   `(fn [key max-attempts] → allowed?)`, so keys carrying different limits
   can share one window state. In-memory (per process); denied attempts
   don't grow the window, and fully-idle keys are swept at most once per
   window so the map stays bounded by ACTIVE keys. Atomic test-and-record —
   a separate read-then-swap would let two concurrent attempts both pass
   under the cap."
  [window-ms]
  (let [state (atom {})
        last-prune (atom 0)]
    (fn [key max-attempts]
      (let [now (System/currentTimeMillis)
            cutoff (- now window-ms)
            prune (fn [ts] (filterv #(> % cutoff) ts))]
        (when (> now (+ @last-prune window-ms))
          (reset! last-prune now)
          (swap! state (fn [m]
                         (persistent!
                           (reduce-kv (fn [acc k ts]
                                        (let [r (prune ts)]
                                          (if (seq r) (assoc! acc k r) acc)))
                                      (transient {}) m)))))
        (let [[old new] (swap-vals! state
                                    (fn [m]
                                      (let [recent (prune (get m key []))]
                                        (assoc m key (if (< (count recent) max-attempts)
                                                       (conj recent now)
                                                       recent)))))]
          (> (count (get new key)) (count (prune (get old key)))))))))


(defn fixed-window-limiter
  "The fixed-cap case of `per-key-fixed-window-limiter`: `(fn [key] →
   allowed?)`, at most `max-attempts` per `window-ms` for every key."
  [max-attempts window-ms]
  (let [limiter (per-key-fixed-window-limiter window-ms)]
    (fn [key] (limiter key max-attempts))))
