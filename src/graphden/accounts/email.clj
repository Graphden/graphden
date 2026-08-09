(ns graphden.accounts.email
  "Transactional email for the accounts module — a small `Mailer` protocol with
   three impls, so email is pluggable and OPT-IN:

   - `ResendMailer` — posts to the Resend HTTP API (prod). Uses http-kit (the
     platform outbound path) to a fixed trusted host, so no SSRF egress guard is
     needed.
   - `LogMailer` — the default when no `RESEND_API_KEY` is set. Logs the whole
     message (including the verification link) at INFO, so a self-hosted
     instance WITHOUT an email provider can still complete verification by
     reading the link out of the log. Email never silently half-works.
   - `CapturingMailer` — collects messages into an atom, for tests.

   A message is `{:to :subject :html? :text?}`."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [org.httpkit.client :as http]))


(def default-from
  "Sender identity. graphden.dev is the Resend-verified domain; sending from it
   does not touch the apex MX that forwards INCOMING mail."
  "Graphden <noreply@graphden.dev>")


(defprotocol Mailer

  (send-mail!
    [this msg]
    "Send `{:to :subject :html? :text?}`. Returns `{:ok? bool …}`; never throws
     (a transport failure is reported as `:ok? false`)."))


(defn- resend-send!
  [api-key from {:keys [to subject html text]}]
  (try
    (let [resp @(http/post "https://api.resend.com/emails"
                           {:headers {"Authorization" (str "Bearer " api-key)
                                      "Content-Type" "application/json"}
                            :timeout 10000
                            :body (json/generate-string
                                    (cond-> {:from from :to [to] :subject subject}
                                      html (assoc :html html)
                                      text (assoc :text text)))})
          status (:status resp)]
      (if (and status (< status 300))
        {:ok? true :id (some-> (:body resp) (json/parse-string true) :id)}
        (do (log/warn "Resend send failed" {:status status :error (:error resp)})
            {:ok? false :status status :error (or (some-> (:error resp) str) (:body resp))})))
    (catch Exception e
      (log/warn e "Resend send threw")
      {:ok? false :error (Throwable/.getMessage e)})))


(defrecord ResendMailer
  [api-key from]

  Mailer

  (send-mail! [_ msg] (resend-send! api-key from msg)))


(defrecord LogMailer
  []

  Mailer

  (send-mail!
    [_ {:keys [to subject text html]}]
    (log/info (str "[accounts] email NOT sent (no RESEND_API_KEY) — logging it so "
                   "verification still works.\n  to: " to "\n  subject: " subject
                   "\n  body:\n" (or text html)))
    {:ok? true :logged? true}))


(defrecord CapturingMailer
  [sink]

  Mailer

  (send-mail! [_ msg] (swap! sink conj msg) {:ok? true :captured? true}))


(defn verification-email-body
  "Subject + html + text for an email-verification message. The link is
   `<app-base-url>/auth/verify?token=<token>` — the token is base64url (no
   padding), already URL-safe, so it needs no escaping."
  [app-base-url token]
  (let [link (str app-base-url "/auth/verify?token=" token)]
    {:subject "Verify your Graphden email"
     :text (str "Confirm your email for Graphden by opening this link:\n\n"
                link
                "\n\nThe link expires in 24 hours. If you didn't create a Graphden account, ignore this email.")
     :html (str "<p>Confirm your email for Graphden:</p>"
                "<p><a href=\"" link "\">Verify my email</a></p>"
                "<p>Or paste this link into your browser:<br>" link "</p>"
                "<p>The link expires in 24 hours. If you didn't create a Graphden account, ignore this email.</p>")}))


(defn make-mailer
  "Pick a `Mailer` from config: a non-blank `api-key` → `ResendMailer`, else the
   `LogMailer` fallback (self-hosted without email still works)."
  [{:keys [api-key from]}]
  (if (str/blank? api-key)
    (do (log/info "Accounts mailer: LogMailer (no RESEND_API_KEY) — verification links go to the log")
        (->LogMailer))
    (do (log/info "Accounts mailer: ResendMailer")
        (->ResendMailer api-key (if (str/blank? from) default-from from)))))
