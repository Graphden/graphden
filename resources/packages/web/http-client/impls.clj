(ns graphden.packages.web.http-client.impls
  "Outgoing HTTP client base function — http-kit client wrapper."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.client :as http]))


(defn- stringify-header-keys
  "http-kit returns response headers as keyword-keyed string→string.
   The fn-graph speaks `[:map :text :text]`, so we name the keys to
   strings at the boundary — same one-shot conversion the server side
   does in the opposite direction.

   Also used on the REQUEST side: a request-headers map coming from a
   JSON body (via /api/execute) is keywordized by cheshire, so
   `{:Authorization \"…\"}` would crash http-kit's writer
   (`Keyword cannot be cast to String`). One symmetric stringify call
   fixes both directions."
  [hs]
  (when (map? hs)
    (persistent!
      (reduce-kv
        (fn [acc k v]
          (assoc! acc (if (keyword? k) (name k) (str k)) (str v)))
        (transient {})
        hs))))


(defn- do-http-get*
  "Shared get-impl shape — `headers` is the FINAL header map (already
   merged + stringified). Returns the record-shape response."
  [url headers]
  (let [resp @(http/get url {:headers (or headers {})
                             :timeout 10000
                             :as :text})
        resp-status (:status resp)
        resp-headers (:headers resp)
        resp-body (:body resp)
        resp-error (:error resp)]
    (when resp-error
      (throw (ex-info (str "HTTP GET " url " failed: " (Throwable/.getMessage resp-error))
                      {:type :http-client/request-failed
                       :url url
                       :cause (Throwable/.getMessage resp-error)})))
    {:status (or resp-status 0)
     :headers (or (stringify-header-keys resp-headers) {})
     :body (or resp-body "")}))


(defbase http-get
  [url headers]
  (cr/record-effect! :network)
  ;; Destructured names DO NOT shadow defbase arg-syms (the AST
  ;; walker rewrites bare arg-syms anywhere they appear, including
  ;; inside `{:keys [...]}`). So we rename the response's `:headers`
  ;; key locally to `resp-headers` to avoid colliding with the
  ;; `headers` arg-sym. Same precaution for any future status/body
  ;; arg names that might be added.
  (do-http-get* url (stringify-header-keys headers)))


(defbase http-get-with-authorization
  "Generalised auth-aware HTTP GET — `:auth-value` is the FULL
   `Authorization` header value (e.g. `\"Bearer xxx\"`, `\"Token xxx\"`,
   `\"Basic <b64>\"`) and gets injected directly into the request
   headers. The slot is `[:secret :text]`, so a secret-typed value
   accumulated via graph-level `:str` propagation (e.g. from
   `:vault-get`) is accepted structurally — it never crosses a
   generic `[:map :text :text]` slot. The slot-typing IS the
   secret-flow invariant that keeps decomposition safe.

   `:http-get-with-bearer` and any other scheme-specific variant
   (`:http-get-with-token`, `:http-get-with-basic`, …) are thin
   graph fn-defs that prepend the scheme keyword to a secret value
   via `:str` (which propagates `[:secret :text]`) and bind the
   resulting full auth-value here."
  [url auth-value extra-headers]
  (cr/record-effect! :network)
  (let [extra (or (stringify-header-keys extra-headers) {})
        headers (assoc extra "Authorization" auth-value)]
    (do-http-get* url headers)))


(def impls
  {:http-get http-get
   :http-get-with-authorization http-get-with-authorization})
