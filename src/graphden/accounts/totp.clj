(ns graphden.accounts.totp
  "TOTP (RFC 6238, HMAC-SHA1, 30s step, 6 digits) — compatible with Google
   Authenticator / Authy / 1Password. Pure functions: generate a base32 secret,
   compute the code at a time, verify a submitted code with ±1 step of clock
   skew (constant-time compare), and build the `otpauth://` URI a QR encodes.

   The secret lives on `:account.totp-secret`; enrollment/confirm/login flow is
   in `accounts.core` + `accounts.routes`."
  (:require
    [clojure.string :as str]
    [graphden.accounts.crypto :as crypto])
  (:import
    (javax.crypto
      Mac)
    (javax.crypto.spec
      SecretKeySpec)))


(def ^:private ^:const step-secs 30)
(def ^:private ^:const digits 6)
(def ^:private b32-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")


(defn- bit-at
  "The bit at MSB-first position `pos` in the byte array, or 0 past the end."
  [^bytes bs total-bits pos]
  (if (< pos total-bits)
    (bit-and (bit-shift-right (aget bs (quot pos 8)) (- 7 (mod pos 8))) 1)
    0))


(defn base32-encode
  "RFC 4648 base32 (no padding), upper-case. Each output char is the 5-bit group
   starting at bit `j*5`, MSB-first, zero-padded past the end."
  [^bytes bs]
  (let [total-bits (* (alength bs) 8)
        nchars (quot (+ total-bits 4) 5)]
    (apply str
           (for [j (range nchars)]
             (let [start (* j 5)
                   v (reduce (fn [acc k]
                               (bit-or (bit-shift-left acc 1)
                                       (bit-at bs total-bits (+ start k))))
                             0 (range 5))]
               (.charAt b32-alphabet v))))))


(defn base32-decode
  ^bytes [^String s]
  (let [s (str/replace (str/upper-case s) #"[^A-Z2-7]" "")
        out (java.io.ByteArrayOutputStream.)]
    (loop [buf 0 bits 0 i 0]
      (if (< i (count s))
        (let [idx (.indexOf b32-alphabet (int (.charAt s i)))
              buf (bit-or (bit-shift-left buf 5) idx)
              bits (+ bits 5)]
          (if (>= bits 8)
            (do (.write out (bit-and (bit-shift-right buf (- bits 8)) 0xff))
                (recur buf (- bits 8) (inc i)))
            (recur buf bits (inc i))))
        (.toByteArray out)))))


(defn generate-secret
  "A fresh base32 TOTP secret (20 random bytes = 160 bits, per RFC 4226)."
  []
  (base32-encode (crypto/random-bytes 20)))


(defn- counter-bytes
  ^bytes [^long counter]
  (let [b (byte-array 8)]
    (loop [i 7 c counter]
      (when (>= i 0)
        (aset-byte b i (unchecked-byte (bit-and c 0xff)))
        (recur (dec i) (unsigned-bit-shift-right c 8))))
    b))


(defn- hotp
  [^bytes secret ^long counter]
  (let [mac (Mac/getInstance "HmacSHA1")
        _ (Mac/.init mac (SecretKeySpec. secret "HmacSHA1"))
        h (Mac/.doFinal mac (counter-bytes counter))
        offset (bit-and (aget h 19) 0xf)
        bin (bit-or (bit-shift-left (bit-and (aget h offset) 0x7f) 24)
                    (bit-shift-left (bit-and (aget h (+ offset 1)) 0xff) 16)
                    (bit-shift-left (bit-and (aget h (+ offset 2)) 0xff) 8)
                    (bit-and (aget h (+ offset 3)) 0xff))]
    (mod bin (long (Math/pow 10 digits)))))


(defn code-at
  "The 6-digit TOTP code for `secret-base32` at `time-secs` (unix seconds)."
  [secret-base32 time-secs]
  (format "%06d" (hotp (base32-decode secret-base32) (quot (long time-secs) step-secs))))


(defn valid?
  "True iff `code` matches the secret within ±1 step of `time-secs` (clock skew
   tolerance). Constant-time compare."
  [secret-base32 code time-secs]
  (boolean
    (when (and secret-base32 (not (str/blank? code)))
      (some (fn [delta]
              (crypto/constant-time-equal? (str code)
                                           (code-at secret-base32 (+ (long time-secs) (* delta step-secs)))))
            [-1 0 1]))))


(defn otpauth-uri
  "The `otpauth://totp/...` URI a QR encodes for `label` (usually the email)."
  [issuer label secret-base32]
  (str "otpauth://totp/" issuer ":" label
       "?secret=" secret-base32
       "&issuer=" issuer
       "&algorithm=SHA1&digits=" digits "&period=" step-secs))
