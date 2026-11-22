(ns graphden.input.source-certification
  "Certify supported legacy scope rewrites in the order their proofs require."
  (:require
    [graphden.input.source :as source]
    [graphden.input.source-snapshot :as snapshot]))


(defn- argument-field
  [{[keyspace field & nested] :source-path}]
  (when (and (= :args keyspace) (simple-keyword? field) (empty? nested))
    field))


(defn- candidate
  [{id :identity blockers :blockers}]
  (when (and (uuid? id) (seq blockers) (every? argument-field blockers))
    (condp every? blockers
      #(= :ref-scope-required (:code %)) [id nil]
      #(= :field-projection-required (:code %)) [id (mapv argument-field blockers)]
      nil)))


(defn- attempt
  [package roots id field]
  (try
    {:package (if field
                (snapshot/with-migrated-forwarding package id field roots)
                (snapshot/with-migrated-bindings package [id] roots))}
    (catch clojure.lang.ExceptionInfo failure
      (let [{:keys [type code fn-id] :as data} (ex-data failure)]
        (when-not (or (#{"input" "types"} (some-> type namespace))
                      (= :compile/ambiguous-lambda-params type))
          (throw failure))
        {:blocked (cond-> (assoc (select-keys data [:fn-id :blockers])
                                 :code (or code type))
                    (keyword? (:reason data)) (assoc :reason (:reason data)))
         :dependency (when (and (= :unverified-source-declaration code) (not= fn-id id))
                       fn-id)}))))


(defn- certify
  "From the ordinary source plan, verify supported scoped binding rewrites
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


(defn- shared-forwarding
  [prepared baselines]
  (reduce (fn [context entry]
            (if (some :source-names (vals (get-in entry [:v2 :scoped-inputs])))
              (source/with-forwarded-parent context entry)
              context))
          prepared
          (vals baselines)))


(defn verify-plan
  "Verify local scope rewrites using source proofs from the declaring packages.
   An imported declaration keeps its original name resolution and verified
   rows even when the importing package uses a different reference spelling."
  [prepared originals {:keys [definitions baselines]} roots]
  (let [visible (into originals definitions)
        prepared (shared-forwarding prepared baselines)
        package (snapshot/package-index prepared visible)
        package (-> package
                    (assoc :closure-baselines baselines)
                    (update :prepared assoc ::source/originals-by-id (:originals package)))
        initial-context (:prepared package)
        preliminary (source/plan-module originals initial-context)
        {:keys [package certified blocked]} (certify package (:definitions preliminary) roots)
        context (:prepared package)
        plan (if (= initial-context context)
               preliminary
               (source/plan-module originals context))
        entries (mapv #(get (:closure-baselines package) (:identity %) %) (:definitions plan))
        verified (source/verify-module (assoc plan :definitions entries) context)
        local-proofs (into {} (comp (filter #(= :authored-source-equivalent (:verification %)))
                                    (map (juxt :identity identity)))
                           (:definitions verified))]
    {:context context :plan verified :certified certified :blocked blocked :package package
     :source-proof {:definitions visible :baselines (merge baselines local-proofs)}}))
