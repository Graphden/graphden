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
