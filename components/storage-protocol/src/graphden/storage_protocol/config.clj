(ns graphden.storage-protocol.config
  "Declarative configuration validation using Malli.

   Provides reusable schemas and validation functions for storage backend
   configurations. Uses Malli for:
   - Declarative schema definitions
   - Human-readable error messages
   - Schema composition and reuse"
  (:require
    [clojure.string :as str]
    [malli.core :as m]
    [malli.error :as me]))


;; === Common schemas ===

(def non-blank-string
  "Non-empty string that is not just whitespace."
  [:and :string [:fn {:error/message "must not be blank"}
                 (fn [s] (and (string? s) (seq (str/trim s))))]])


(def positive-int
  "Positive integer (> 0)."
  [:and :int [:> 0]])


(def non-negative-int
  "Non-negative integer (>= 0)."
  [:and :int [:>= 0]])


;; === PostgreSQL configuration schema ===

(def postgres-pool-config
  "Schema for PostgreSQL connection pool configuration."
  [:map
   {:closed true}
   [:jdbc-url [:and
               :string
               [:fn {:error/message "must start with 'jdbc:postgresql://'"}
                #(str/starts-with? % "jdbc:postgresql://")]]]
   [:username non-blank-string]
   [:password non-blank-string]
   [:pool-size {:optional true
                :default 10}
    [:and positive-int [:< 101]]]
   [:min-idle {:optional true
               :default 2}
    positive-int]
   [:connection-timeout {:optional true
                         :default 30000}
    positive-int]
   [:idle-timeout {:optional true
                   :default 600000}
    non-negative-int]
   [:max-lifetime {:optional true
                   :default 1800000}
    positive-int]
   [:leak-detection-threshold {:optional true
                               :default 60000}
    non-negative-int]])


;; === Datomic configuration schemas ===

(def datomic-local-config
  "Schema for datomic-local configuration."
  [:map
   {:closed true}
   [:server-type [:= :datomic-local]]
   [:system :string]
   [:storage-dir :string]
   [:db-name :string]])


(def datomic-peer-server-config
  "Schema for datomic peer-server configuration."
  [:map
   {:closed true}
   [:server-type [:= :peer-server]]
   [:endpoint :string]
   [:access-key :string]
   [:secret :string]
   [:db-name :string]])


(def datomic-ion-config
  "Schema for datomic ion configuration."
  [:map
   [:server-type [:= :ion]]
   [:region :string]
   [:system :string]
   [:db-name :string]])


(def datomic-cloud-config
  "Schema for datomic cloud configuration."
  [:map
   [:server-type [:= :cloud]]
   [:region :string]
   [:system :string]
   [:db-name :string]])


(def datomic-config
  "Schema for any Datomic configuration (union of all types)."
  [:or
   datomic-local-config
   datomic-peer-server-config
   datomic-ion-config
   datomic-cloud-config])


;; === Validation functions ===

(defn validate-config!
  "Validates configuration against a Malli schema.
   Throws ex-info with :type :config-error/invalid-config on failure.

   Arguments:
   - config: The configuration map to validate
   - schema: Malli schema to validate against
   - config-name: Human-readable name for error messages (e.g., \"PostgreSQL pool\")"
  [config schema config-name]
  (when-not (m/validate schema config)
    (let [explanation (m/explain schema config)
          errors (me/humanize explanation)]
      (throw (ex-info (str "Invalid " config-name " configuration: " (pr-str errors))
                      {:type :config-error/invalid-config
                       :config-name config-name
                       :errors errors
                       :config config})))))


(defn validate-postgres-config!
  "Validates PostgreSQL pool configuration.
   Throws ex-info on invalid configuration."
  [config]
  (validate-config! config postgres-pool-config "PostgreSQL pool")
  ;; Additional cross-field validations
  (let [{:keys [min-idle pool-size idle-timeout max-lifetime]
         :or {pool-size 10 min-idle 2 idle-timeout 600000 max-lifetime 1800000}} config]
    (when (> min-idle pool-size)
      (throw (ex-info "min-idle cannot exceed pool-size"
                      {:type :config-error/invalid-pool-config
                       :min-idle min-idle
                       :pool-size pool-size})))
    (when (and (pos? idle-timeout) (>= idle-timeout max-lifetime))
      (throw (ex-info "idle-timeout must be less than max-lifetime"
                      {:type :config-error/invalid-pool-config
                       :idle-timeout idle-timeout
                       :max-lifetime max-lifetime})))))


(defn validate-datomic-config!
  "Validates Datomic client configuration.
   Throws ex-info on invalid configuration."
  [config]
  (validate-config! config datomic-config "Datomic"))


(defn apply-defaults
  "Applies default values to a configuration map based on schema.
   Returns config with defaults filled in for missing optional fields."
  [config schema]
  (let [schema-form (m/form schema)]
    (if (and (vector? schema-form) (= :map (first schema-form)))
      (reduce
        (fn [cfg field-def]
          (if (and (vector? field-def) (>= (count field-def) 2))
            (let [field-name (first field-def)
                  field-props (when (map? (second field-def)) (second field-def))
                  default-val (:default field-props)]
              (if (and default-val (not (contains? cfg field-name)))
                (assoc cfg field-name default-val)
                cfg))
            cfg))
        config
        (rest schema-form))
      config)))
