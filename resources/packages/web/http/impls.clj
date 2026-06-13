(ns graphden.packages.web.http.impls
  "Implementations for web/http base functions.

   Thin wrappers around http-kit. Request adaptation, header merging,
   and auth are composed from fn-defs elsewhere."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.server :as http-kit]))


(defn- stringify-response-headers
  "http-kit's `HeaderMap.camelCase` does `(String) key` on every entry
   of the response `:headers` map — keyword keys throw
   `ClassCastException` mid-write. Stringify at this adapter so the
   fn-graph can keep keyword-keyed records end-to-end (storage round-
   trip keywordises map keys; record-types in graphden are keyword-
   keyed). One conversion point, instead of every response-building
   fn-def having to wedge a `:stringify-map-keys` step into the graph."
  [resp]
  (if (map? (:headers resp))
    (update resp :headers
            (fn [hs]
              (persistent!
                (reduce-kv
                  (fn [acc k v]
                    (assoc! acc (if (keyword? k) (name k) (str k)) v))
                  (transient {})
                  hs))))
    resp))


(defn- realize-body
  "Eagerly slurp the request `:body` InputStream into a UTF-8 String
   so downstream graph fn-defs that read the body more than once (the
   per-call `*call-cache*` keys by the caller's full env — and a
   parse-stage referenced from both a `:cond` validation chain AND a
   sibling `:apply` ref can land in two cache slots) don't hit an
   exhausted stream on the second consumer.

   Without this, anything that read the body more than once produced
   a silently-wrong second result: nil keys, empty vectors, and
   downstream apply stages writing rows with `:name nil` and zero
   slots. See C19 regression hunt 2026-06-03.

   `crud.request/read-json-body` already accepts strings, maps, and
   InputStreams — once we hand it a String the rest of the chain
   works without changes."
  [req]
  (let [b (:body req)]
    (if (instance? java.io.InputStream b)
      (assoc req :body (slurp (java.io.InputStreamReader. b "UTF-8")))
      req)))


(defbase http-server
  [handler port]
  (cr/record-effect! :network)
  ;; :process — http-kit spawns a listener thread that lives past
  ;; this call. The returned stopper kills it; service registry's
  ;; validate-create requires :process for service-eligibility.
  (cr/record-effect! :process)
  (http-kit/run-server
    (fn [req] (stringify-response-headers (handler (realize-body req))))
    {:port port}))


(defbase http-stop
  [server]
  (cr/record-effect! :network)
  (when server (server) nil))


(def impls
  {:http-server http-server
   :http-stop http-stop})
