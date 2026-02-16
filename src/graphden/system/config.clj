(ns graphden.system.config
  "Configuration loading with Aero reader tags."
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [integrant.core :as ig]))


(defn read-config
  "Reads system configuration for the given profile.
   Profile can be :dev, :test, or :prod.
   Returns a prepared Integrant configuration map."
  [profile]
  (let [filename (str "system-" (name profile) ".edn")
        resource (io/resource filename)]
    (when-not resource
      (throw (ex-info (str "Config file not found: " filename)
                      {:profile profile
                       :filename filename})))
    (-> resource
        (aero/read-config {:profile profile})
        (ig/prep))))
