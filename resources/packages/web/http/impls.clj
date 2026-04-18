(ns graphden.packages.web.http.impls
  "Implementations for web/http base functions.

   Only thin wrappers around http-kit. Request adaptation, header merging,
   and auth are composed from fn-defs in app/server/fns.edn."
  (:require
    [org.httpkit.server :as http-kit]))


(defn http-server
  [{:keys [handler port]}]
  (http-kit/run-server handler {:port port}))


(defn http-stop
  [{:keys [server]}]
  (when server (server) nil))


(def impls
  {:http-server http-server
   :http-stop http-stop})
