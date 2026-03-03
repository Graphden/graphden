(ns graphden.library.base-fns.core.test-helpers
  "Shared test helpers for base-functions tests."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.library.base-fns.core :as bf]))


;; === Helper Functions ===

(defn literal-delay
  "Creates a delay wrapping a literal value."
  [value]
  (delay value))


(defn call-base-fn
  "Calls a base function with literal values wrapped in delays."
  [fn-name args]
  (let [delays (into {} (map (fn [[k v]] [k (delay v)]) args))]
    ((exec/get-base-fn fn-name) delays nil)))


;; === Registration Helpers ===
;; These wrap fn-registry to register base function definitions

(defn register-all!
  []
  (registry/register-base-fns! bf/all-defs))


(defn register-arithmetic!
  []
  (registry/register-base-fns! bf/arithmetic-defs))


(defn register-comparison!
  []
  (registry/register-base-fns! bf/comparison-defs))


(defn register-logic!
  []
  (registry/register-base-fns! bf/logic-defs))


(defn register-conditionals!
  []
  (registry/register-base-fns! bf/conditional-defs))


(defn register-strings!
  []
  (registry/register-base-fns! bf/string-defs))


(defn register-collections!
  []
  (registry/register-base-fns! bf/collection-defs))


(defn register-hof!
  []
  (registry/register-base-fns! bf/hof-defs))


(defn sync-storage!
  [storage]
  (registry/sync-defs-to-storage! storage bf/all-defs))
