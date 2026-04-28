(ns graphden.packages.web.http.impls
  "Implementations for web/http base functions.

   Thin wrappers around http-kit. Request adaptation, header merging,
   and auth are composed from fn-defs elsewhere."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.server :as http-kit]))


(defbase http-server
  [handler port]
  (http-kit/run-server handler {:port port}))


(defbase http-stop
  [server]
  (when server (server) nil))


(def impls
  {:http-server http-server
   :http-stop http-stop})
