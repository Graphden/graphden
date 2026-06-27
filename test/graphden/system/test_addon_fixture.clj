(ns graphden.system.test-addon-fixture
  "Stand-in for a tenancy ADDON's Clojure side — proves the addon-manifest
   mechanism (config.clj) can require an addon namespace + wire a new
   Integrant key. A real addon (deps.edn dep) ships exactly this shape: an
   `ig/init-key` for its own component, loaded via the fragment's
   `:graphden/require`."
  (:require
    [integrant.core :as ig]))


;; Marker — resolvable only if `:graphden/require` actually loaded this ns.
(def loaded? true)


(defmethod ig/init-key :auth/test-provider [_ {:keys [label]}]
  {:provider :test :label label})
