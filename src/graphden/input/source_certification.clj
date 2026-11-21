(ns graphden.input.source-certification
  "Certify supported legacy scope rewrites in the order their proofs require."
  (:require
    [graphden.input.source :as source]
    [graphden.input.source-snapshot :as snapshot]))


(defn- candidate
  [{id :identity [blocker & others] :blockers}]
  (when (and (uuid? id) blocker (empty? others))
    (let [[keyspace field & nested] (:source-path blocker)]
      (when (and (= :args keyspace) (simple-keyword? field) (empty? nested))
        (case (:code blocker)
          :ref-scope-required [id nil]
          :field-projection-required [id field]
          nil)))))


(defn- attempt
  [package roots id field]
  (try
    {:package (if field
                (snapshot/with-migrated-forwarding package id field roots)
                (snapshot/with-migrated-bindings package [id] roots))}
    (catch clojure.lang.ExceptionInfo failure
      (let [{:keys [type code fn-id] :as data} (ex-data failure)]
        (when-not (#{"input" "types"} (some-> type namespace))
          (throw failure))
        {:blocked (assoc (select-keys data [:fn-id :blockers])
                         :code (or code type))
         :dependency (when (and (= :unverified-source-declaration code) (not= fn-id id))
                       fn-id)}))))


(defn- certify
  "From the ordinary source plan, verify supported single-argument rewrites
   using trusted native signatures. A source owner is retried only when the
   exact dependency blocking its closure has since been certified. Cycles
   and unproved owners stay blocked. The returned package contains private
   replay evidence. :blocked holds sanitized diagnostics for attempted owners."
  [package entries roots]
  (let [by-id (into (sorted-map) (keep candidate) entries)]
    (loop [package package
           pending (into clojure.lang.PersistentQueue/EMPTY (keys by-id))
           waiting {}
           certified #{}
           blocked {}]
      (if-let [id (peek pending)]
        (let [{next-package :package diagnostic :blocked dependency :dependency}
              (attempt package roots id (get by-id id))]
          (if next-package
            (recur next-package (into (pop pending) (get waiting id)) (dissoc waiting id)
                   (conj certified id) (dissoc blocked id))
            (recur package (pop pending)
                   (if (and (contains? by-id dependency) (not (contains? certified dependency)))
                     (update waiting dependency (fnil conj []) id)
                     waiting)
                   certified (assoc blocked id diagnostic))))
        {:package package :certified certified :blocked blocked}))))


(defn verify-plan
  "Certify visible scope rewrites, then verify the original package plan
   in the updated context. Return only the requested package's definitions."
  [prepared originals dependencies roots]
  (let [visible (into originals dependencies)
        package (snapshot/package-index prepared visible)
        ;; The package preparation lists local definitions in :fn-defs.
        ;; Imported declarations must also be eligible as proof dependencies.
        visible-context (assoc (:prepared package) :fn-defs (vals (:declarations package)))
        preliminary (source/plan-module visible visible-context)
        {:keys [package certified blocked]} (certify package (:definitions preliminary) roots)
        context (:prepared package)
        plan (source/plan-module originals context)
        entries (mapv #(get (:closure-baselines package) (:identity %) %) (:definitions plan))]
    {:context context
     :plan (source/verify-module (assoc plan :definitions entries) context)
     :certified certified :blocked blocked
     :package package}))
