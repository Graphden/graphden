(ns graphden.system.config
  "Configuration loading with Aero reader tags + the addon-manifest merge
   (docs/TENANCY_SEAM.md § Addon config manifest).

   The core system config lives in `system-<profile>.edn`. An optional
   ADDON (a deps.edn dependency — the impl-channel) splices itself in by
   shipping an Aero EDN config fragment on the classpath and naming it in
   `GRAPHDEN_ADDON_CONFIGS`. Each fragment is deep-merged over the core
   config, so an addon can:
     - REDEFINE an indirection key the core exposes (e.g. `:auth/provider`,
       which the auth seam already routes through), and
     - ADD its own Integrant keys (e.g. `:org/scoped-storage`).
   No core-config edit is needed — the manifest is data.

   A fragment's `:graphden/require` vector lists namespaces to load before
   merge, so the addon's `ig/init-key` defmethods are registered before
   `ig/init` runs. The key is stripped from the merged Integrant config.

   With no addons configured the merge is a no-op — core runs single-tenant."
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [clojure.string :as str]
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


(defn- deep-merge
  "Recursively merge two Integrant configs. Maps merge key-by-key (so an
   addon can override just `:exec/context`'s `:auth-provider` without
   restating the rest); any non-map value (the addon's) replaces."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))


(defn addon-config-resources
  "Classpath resource names of addon config fragments to splice in, from
   `GRAPHDEN_ADDON_CONFIGS` (comma-separated). Empty when unset — core
   runs as-is (single-tenant). This is the §3.0 addon-manifest."
  []
  (->> (str/split (or (System/getenv "GRAPHDEN_ADDON_CONFIGS") "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))


(defn- merge-addon
  "Read one addon config fragment, run its `:graphden/require` directive
   (so the addon's init-key defmethods load), and deep-merge the rest
   over `cfg`."
  [cfg profile resource-name]
  (let [resource (io/resource resource-name)]
    (when-not resource
      (throw (ex-info (str "Addon config not found on classpath: " resource-name)
                      {:resource resource-name :profile profile})))
    (let [fragment (aero/read-config resource {:profile profile})]
      (doseq [ns-sym (:graphden/require fragment)]
        (require (symbol ns-sym)))
      (deep-merge cfg (dissoc fragment :graphden/require)))))


(defn read-config
  "Reads system configuration for the given profile (`:dev` / `:test` /
   `:prod`), then deep-merges any addon config fragments (default: from
   `GRAPHDEN_ADDON_CONFIGS`; pass `addon-resources` explicitly in tests).
   Returns an Integrant configuration map."
  ([profile]
   (read-config profile (addon-config-resources)))
  ([profile addon-resources]
   (let [filename (str "system-" (name profile) ".edn")
         resource (io/resource filename)]
     (when-not resource
       (throw (ex-info (str "Config file not found: " filename)
                       {:profile profile
                        :filename filename})))
     (reduce (fn [cfg res] (merge-addon cfg profile res))
             (aero/read-config resource {:profile profile})
             addon-resources))))
