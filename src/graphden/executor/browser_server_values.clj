(ns graphden.executor.browser-server-values
  "Closed data contracts at the explicit browser/server boundary. Record
   projection removes undeclared fields before any wire encoding."
  (:require
    [graphden.executor.browser-plan :as wire]
    [graphden.types.core :as types]
    [graphden.types.validator :as validator]))


(defn- reject!
  []
  (throw (ex-info "Server call value is unavailable"
                  {:type :browser-plan/unsupported :reason :server-value})))


(defn- safe-type!
  [t]
  ;; Broad carriers and markers cannot prove what data crosses this boundary.
  (when (types/type-any? #(or (types/marker-type? %)
                              (types/fn-type? %)
                              (contains? #{:any :jsonb :map :fn :fn-ref :input-stream} %)) t)
    (reject!))
  t)


(declare project*)


(defn- project-union
  [t value]
  (or (some (fn [member]
              (try
                ((validator/build member) value)
                {:value (project* member value)}
                (catch Exception _ nil)))
            (types/union-members t))
      (reject!)))


(defn- project*
  [t value]
  (cond
    (types/record-type? t)
    (into {} (map (fn [[k child]] [k (project* child (get value k))])) t)
    (types/list-type? t) (mapv #(project* (types/list-elem t) %) value)
    (types/map-type? t)
    (into {} (map (fn [[k v]]
                    [(project* (types/map-key t) k)
                     (project* (types/map-val t) v)])) value)
    (types/refine-type? t) (project* (types/refine-base t) value)
    (types/union-type? t) (:value (project-union t value))
    :else value))


(defn contract
  "Freeze a finite, marker-free canonical type. No registry interpretation is
   needed later when validating the invocation or projecting its result."
  [t]
  (safe-type! (validator/checked-type t)))


(defn project
  "Validate and project bounded wire-safe data. Extra record fields are
   removed recursively, including records nested in lists or union branches."
  [t value]
  ;; Bound lazy data before generic validation can walk it without a limit.
  (let [bounded (wire/decode-value (wire/encode-result value))]
    ((validator/build t) bounded)
    (project* t bounded)))


(defn input
  "Ingress accepts exactly the declared value; extra fields cannot become
   hidden captures or executable reference envelopes."
  [t encoded]
  (let [value (wire/decode-value encoded)
        projected (project t value)]
    (when-not (= value projected) (reject!))
    projected))
