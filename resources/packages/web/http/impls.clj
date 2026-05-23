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


(defbase http-server
  [handler port]
  (cr/record-effect! :network)
  ;; :process — http-kit spawns a listener thread that lives past
  ;; this call. The returned stopper kills it; service registry's
  ;; validate-create requires :process for service-eligibility.
  (cr/record-effect! :process)
  (http-kit/run-server
    (fn [req] (stringify-response-headers (handler req)))
    {:port port}))


(defbase http-stop
  [server]
  (cr/record-effect! :network)
  (when server (server) nil))


(def impls
  {:http-server http-server
   :http-stop http-stop})
