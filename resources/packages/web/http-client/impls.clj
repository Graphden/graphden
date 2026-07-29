(ns graphden.packages.web.http-client.impls
  "Outgoing HTTP client base function — http-kit client wrapper."
  (:require
    [graphden.clients.egress :as egress]
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
  [url headers timeout-ms]
  ;; Egress guards (tasks #5 / #5b): for a RESTRICTED (tenant / cloud) execution
  ;; only. `*allowed-effects*` is nil for the unrestricted platform ctx, so the
  ;; platform's own outbound to internal services is never gated; a tenant that
  ;; reached here already holds :network (a paid tier — the effect gate blocks
  ;; free tenants earlier). check-target! = SSRF (reject internal / rebinding
  ;; targets before dialing); check-egress-rate! = the per-org outbound rate cap.
  (let [restricted? (some? cr/*allowed-effects*)]
    (when restricted?
      (egress/check-target! url)
      (egress/check-egress-rate!))
    ;; `:as :stream` (not `:text`) so a restricted response is read through the
    ;; per-org byte-cap — an oversize body is rejected mid-stream, never fully
    ;; buffered. The platform ctx slurps the stream in full (uncapped).
    (let [resp @(http/get url {:headers (or headers {})
                               :timeout (or timeout-ms 10000)
                               :as :stream})
          resp-status (:status resp)
          resp-headers (:headers resp)
          resp-body (:body resp)
          resp-error (:error resp)]
      (when resp-error
        (when (instance? java.io.InputStream resp-body)
          (java.io.InputStream/.close ^java.io.InputStream resp-body))
        (throw (ex-info (str "HTTP GET " url " failed: " (Throwable/.getMessage resp-error))
                        {:type :http-client/request-failed
                         :url url
                         :cause (Throwable/.getMessage resp-error)})))
      {:status (or resp-status 0)
       :headers (or (stringify-header-keys resp-headers) {})
       :body (cond
               (not (instance? java.io.InputStream resp-body)) (or resp-body "")
               restricted? (egress/read-capped-string! resp-body) ; tenant → byte-cap
               :else (slurp resp-body))})))


(defbase http-get
  [url headers timeout-ms]
  (cr/record-effect! :network)
  ;; Destructured names DO NOT shadow defbase arg-syms (the AST
  ;; walker rewrites bare arg-syms anywhere they appear, including
  ;; inside `{:keys [...]}`). So we rename the response's `:headers`
  ;; key locally to `resp-headers` to avoid colliding with the
  ;; `headers` arg-sym. Same precaution for any future status/body
  ;; arg names that might be added.
  (do-http-get* url (stringify-header-keys headers) timeout-ms))


(defbase http-get-with-authorization
  "Generalised auth-aware HTTP GET — `:auth-value` is the FULL
   `Authorization` header value (e.g. `\"Bearer xxx\"`, `\"Token xxx\"`,
   `\"Basic <b64>\"`) and gets injected directly into the request
   headers. The slot is `[:secret :text]`, so a secret-typed value
   accumulated via graph-level `:str` propagation (e.g. from
   `:vault-get`) is accepted structurally — it never crosses a
   generic `[:map :text :text]` slot. The slot-typing IS the
   secret-flow invariant that keeps decomposition safe.

   A scheme-specific variant (e.g. `:http-get-with-bearer`,
   `:http-get-with-token`, `:http-get-with-basic`) would be a thin
   graph fn-def that prepends the scheme keyword to a secret value
   via `:str` (which propagates `[:secret :text]`) and binds the
   resulting full auth-value here — none ship yet."
  [url auth-value extra-headers timeout-ms]
  (cr/record-effect! :network)
  (let [extra (or (stringify-header-keys extra-headers) {})
        headers (assoc extra "Authorization" auth-value)]
    (do-http-get* url headers timeout-ms)))


(def impls
  {:http-get http-get
   :http-get-with-authorization http-get-with-authorization})
