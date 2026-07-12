(ns graphden.fleet.router
  "Internal forward-hop (docs/FLEET_RFC.md §6.1, T2.6): when a request lands on
   an executor that does NOT hold the target cell, forward it to the executor
   that does — per the `:placement` map — instead of answering `421`. So `421`
   becomes a rare backstop (stale map / no placement), not the mechanism.

   Addressing (chosen: option 1): an `executor-id` IS a DNS name — a k8s
   StatefulSet / headless-service pod name — so the holder's URL is simply
   `http://<executor-id>:<port>`. No executor→URL registry (that's the option-2
   extension for BYO / non-k8s, deliberately deferred)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.fleet.placement :as placement]
    [org.httpkit.client :as http]))


(defn- forward-url
  [executor-id port uri query-string]
  (str "http://" executor-id ":" port uri
       (when (seq query-string) (str "?" query-string))))


(defn forward-request
  "HTTP-forward `request` to the executor named `executor-id` (a DNS name) on
   `port`, and return its Ring response. A transport failure is a `502` — the
   holder is unreachable, which is the caller's problem to surface, not a
   silent drop. Strips hop-by-hop headers so the downstream response frames
   cleanly."
  [executor-id port request]
  (let [{:keys [request-method uri query-string headers body]} request
        resp @(http/request {:method (or request-method :get)
                             :url (forward-url executor-id port uri query-string)
                             ;; Drop Host (rewritten by the target) + framing
                             ;; headers httpkit sets itself.
                             :headers (dissoc headers "host" "content-length" "connection")
                             :body body
                             :timeout 30000
                             :as :text})]
    (if (:error resp)
      (do (log/warn (:error resp) "fleet forward-hop failed"
                    {:executor-id executor-id :uri uri})
          {:status 502 :headers {"Content-Type" "text/plain"}
           :body "Cell holder unreachable"})
      ;; httpkit's client returns header keys as keywords; a Ring response needs
      ;; string keys, so normalise (`name` is a no-op on strings, safe either way).
      {:status (:status resp)
       :headers (into {} (map (fn [[k v]] [(name k) v])) (:headers resp))
       :body (:body resp)})))


(defn forward-or-nil
  "If `(org, entry-fn-id)` is placed on a DIFFERENT executor than `self-id`,
   forward `request` there and return the response. Returns nil when there is
   no placement, or the cell is placed HERE (`self-id`) — the caller then
   serves it (if held) or `421`s (the backstop). `self-id` nil (unset fleet
   identity) ⇒ never forward."
  [storage self-id port org entry-fn-id request]
  (when self-id
    (when-let [holder (placement/executor-for storage org entry-fn-id)]
      (when (not= holder self-id)
        (forward-request holder port request)))))
