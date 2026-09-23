(ns graphden.system.branch-router.request
  "What the branch router reads off a Ring request, with no router state:
   the branch ref (`X-Graphden-Branch` header, else `?branch=`), whether
   the request is a browser page load, the URL with a stale `?branch=`
   stripped, and the registry-independent `/livez` liveness answer.
   Pure functions of the request — `graphden.system.branch-router/dispatch`
   is the only caller that decides anything with them."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as crud-request]))


(def header-name
  "Lowercased Ring-style header key the dispatcher reads."
  "x-graphden-branch")


(def query-param
  "URL query-string key the dispatcher reads when no header is set."
  "branch")


(defn- parse-branch-from-query
  "The `branch` value of `query-string`, URL-decoded. Decoding fails SOFT
   (`crud-request/safe-url-decode` — the raw text) because this runs on every
   request, authenticated or not: a malformed escape (`?%zz=1`) used to
   throw out of `dispatch` as a 500. A malformed KEY therefore never
   matches `branch`; a malformed VALUE is taken literally, so it reaches
   resolution as the ref the client sent and answers the unknown-branch
   400 — rather than silently serving (and writing to) the default branch."
  [query-string]
  (when (and query-string (not (str/blank? query-string)))
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= query-param (crud-request/safe-url-decode k))
                (some-> v crud-request/safe-url-decode))))
          (str/split query-string #"&"))))


(defn extract-branch-ref
  "Returns the branch ref the request asks for, or nil for default.
   Header wins over query param — explicit programmatic API beats
   shareable-URL convenience. Empty / blank values count as nil."
  [request]
  (let [hdr (get-in request [:headers header-name])
        qs (parse-branch-from-query (:query-string request))
        chosen (or (some-> hdr (str/trim) (#(when-not (str/blank? %) %)))
                   (some-> qs (str/trim) (#(when-not (str/blank? %) %))))]
    chosen))


(defn document-navigation?
  "Is this a browser NAVIGATION (a page load), rather than an API/XHR
   call? A GET whose `Accept` asks for HTML — Fetch/XHR from the editor
   ask for JSON or `*/*`, and htmx sends `HX-Request`. Used to answer a
   stale `?branch=` with a redirect instead of a 400 the browser would
   render as a dead page."
  [request]
  (let [headers (:headers request)
        accept (or (get headers "accept") "")]
    (and (= :get (:request-method request))
         (not (get headers "hx-request"))
         (str/includes? accept "text/html"))))


(defn uri-without-branch
  "The same URL with `branch` stripped from the query string — where a
   navigation naming a dead branch gets sent."
  [request]
  (let [qs (:query-string request)
        kept (when qs
               (->> (str/split qs #"&")
                    (remove #(or (str/blank? %)
                                 (str/starts-with? % "branch=")
                                 (= % "branch")))
                    (str/join "&")))]
    (if (str/blank? kept)
      (:uri request)
      (str (:uri request) "?" kept))))


;; Static liveness path — the ONE endpoint that must answer WITHOUT the
;; compiled registry. Every other route (including `/health`) is an
;; `app.routes` graph fn reached through the branch router's
;; `ring-callable-for-ctx` → `cr/registry`, so while a pod runs a full recompile (seconds — ~5 s today, 49.8 s in 2026-07 — holding
;; the ctx invalidation lock) they all block. A k8s livenessProbe / Docker
;; HEALTHCHECK pointed at such a path would kill a busy-but-alive pod, discard
;; its in-flight compile, and force a cold boot (~115 s) — a slower outage than
;; the rebuild it interrupted. `/livez` proves only "this process's HTTP worker
;; can answer" (liveness); readiness — can it actually serve? — stays `/health`
;; (registry-warm). `branch-router/dispatch` matches it before any
;; registry-touching seam, so it is immune to the rebuild. Path-only (any method); probes GET it.
(def liveness-path "/livez")


(def liveness-response
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"status\":\"alive\"}"})
