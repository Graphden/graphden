(ns graphden.system.config
  "Configuration loading with Aero reader tags."
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [integrant.core :as ig]))


;; Register Integrant reader tags for Aero
(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))


(defmethod aero/reader 'ig/refset
  [_ _ value]
  (ig/refset value))


;; Register #var reader tag - resolves to the var's value
(defmethod aero/reader 'var
  [_ _ value]
  (let [ns-sym (symbol (namespace value))
        var-sym (symbol (name value))]
    (require ns-sym)
    (deref (ns-resolve ns-sym var-sym))))


(defn read-config
  "Reads system configuration for the given profile.
   Profile can be :dev, :test, or :prod.
   Returns an Integrant configuration map."
  [profile]
  (let [filename (str "system-" (name profile) ".edn")
        resource (io/resource filename)]
    (when-not resource
      (throw (ex-info (str "Config file not found: " filename)
                      {:profile profile
                       :filename filename})))
    (aero/read-config resource {:profile profile})))
