(ns graphden.crud.structured-comparison
  "Concrete HTTP ingress for the pinned, bare arithmetic comparison."
  (:require
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.input.ir :as ir]
    [graphden.packages.arithmetic-input :as arithmetic]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]))


(def ^:private comparison-id (ids/fn-id "core.arithmetic" :neq))
(def ^:private declared-input {:values [:list :any]})


(defn direct-input?
  [ctx frozen root trusted analysis]
  (let [registry (meta (:base-fns ctx))
        registration (when (= comparison-id root) (arithmetic/evidence (::arithmetic/scalar-comparison registry)))
        impl (get-in registration [:in-place-definition :impl])
        row (get-in frozen [:fns root])
        input (:input-type-fn-id row)
        scope (get-in analysis [:scopes []])]
    (and (= root (get-in registration [:parsed :fn-id])) (some? impl) (identical? impl (get-in trusted [root :impl]))
         (uuid? input) (= input (get (::owned/primitive-input-types registry) root))
         (empty? (:parent-ids row)) (empty? (:bindings frozen))
         (empty? (:dependencies analysis)) (empty? (:closures analysis))
         (= #{[]} (set (keys (:scopes analysis))))
         (= declared-input (get-in scope [:configured-contract :type])
            (get-in analysis [:public-input :root :type]))
         (= :bool (get-in scope [:signature :return])))))


(defn- json-scalar?
  [x]
  (or (nil? x) (boolean? x) (string? x)
      (and (int? x) (<= -9007199254740991 x 9007199254740991))
      (and (float? x) (Double/isFinite (double x)))))


(defn check-args!
  [args]
  (let [xs (:values args)]
    (when-not (and (= #{:values} (set (keys args))) (= clojure.lang.PersistentVector (class xs)) (empty? (meta xs)) (seq xs)
                   (< (count xs) persist/max-args-bytes) (every? json-scalar? xs)
                   (persist/json-bytes-within? args persist/max-args-bytes))
      (ir/reject! :invalid-comparison-values {}))))
