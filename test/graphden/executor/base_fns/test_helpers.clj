(ns graphden.executor.base-fns.test-helpers
  "Shared test helpers for base-functions tests."
  (:require
    [graphden.executor.base-fns.core :as core]
    [graphden.executor.base-fns.interface :as bf]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]))


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
  (registry/register-base-fns! (bf/get-all-defs)))


(defn register-arithmetic!
  []
  (registry/register-base-fns! core/arithmetic-defs))


(defn register-comparison!
  []
  (registry/register-base-fns! core/comparison-defs))


(defn register-logic!
  []
  (registry/register-base-fns! core/logic-defs))


(defn register-conditionals!
  []
  (registry/register-base-fns! core/conditional-defs))


(defn register-strings!
  []
  (registry/register-base-fns! core/string-defs))


(defn register-collections!
  []
  (registry/register-base-fns! core/collection-defs))


(defn register-hof!
  []
  (registry/register-base-fns! core/hof-defs))


(defn sync-storage!
  [storage]
  (registry/sync-defs-to-storage! storage (bf/get-all-defs)))
