(ns graphden.accounts.telegram
  "Telegram Login Widget verification. The widget hands the browser a signed
   payload (id, first_name, username?, photo_url?, auth_date, hash); we verify
   the HMAC-SHA256 with the bot token server-side, per Telegram's spec:

     secret_key    = SHA256(bot_token)
     check_string  = each `key=value` (except hash), sorted by key, '\\n'-joined
     valid         = HMAC_SHA256(check_string, secret_key) == hash

   Telegram provides NO email, so a Telegram identity is never auto-linked by
   email — it either matches an existing telegram identity or is attached
   explicitly by a signed-in user (Phase 3)."
  (:require
    [clojure.string :as str]
    [graphden.accounts.crypto :as crypto]))


(def ^:const max-auth-age-secs
  "Reject a login payload older than this (replay window). 24h."
  (* 24 60 60))


(defn- data-check-string
  [data]
  (->> (dissoc data "hash")
       (sort-by key)
       (map (fn [[k v]] (str k "=" v)))
       (str/join "\n")))


(defn verify-login
  "Verify a Telegram Login Widget `data` map (string keys) against `bot-token`
   at `now-secs`. Returns the normalized identity map, or nil on a bad hash or a
   stale `auth_date`."
  [bot-token data now-secs]
  (let [their-hash (get data "hash")
        computed (crypto/hmac-sha256-hex (crypto/sha256-bytes bot-token)
                                         (data-check-string data))
        auth-date (some-> (get data "auth_date") str parse-long)]
    (when (and their-hash
               (crypto/constant-time-equal? computed their-hash)
               auth-date
               (<= (- now-secs auth-date) max-auth-age-secs))
      {:provider "telegram"
       :subject (str (get data "id"))
       :email nil
       :email-verified? false
       :display-name (or (not-empty (str (get data "username")))
                         (get data "first_name"))})))
