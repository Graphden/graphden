(ns graphden.input.source-certification
  "Certify supported legacy scope rewrites in the order their proofs require."
  (:require
    [graphden.input.source :as source]
    [graphden.input.source-origins :as origins]
    [graphden.input.source-snapshot :as snapshot]))


(defn- argument-field
  [{[keyspace field & nested] :source-path}]
  (when (and (= :args keyspace) (simple-keyword? field) (empty? nested))
    field))


(defn- candidate
  [{id :identity blockers :blockers}]
  (when (and (uuid? id) (seq blockers))
    (cond
      (and (every? argument-field blockers)
           (every? #(= :ref-scope-required (:code %)) blockers)) [id nil]
      (and (every? argument-field blockers)
           (every? #(= :field-projection-required (:code %)) blockers))
      [id (mapv argument-field blockers)]
      :else
      (let [paths (into #{} (comp (keep origins/inline-binding-path)
                                  (filter #(< 2 (count %)))
                                  (map #(subvec % 0 2))) blockers)]
        (when (every? (fn [{:keys [code source-path] :as blocker}]
                        (or (origins/inline-binding-path blocker)
                            (case code
                              :inherited-projection-required (= [:args] source-path)
                              :list-builder-required (contains? paths source-path)
                              (:ref-scope-required :field-projection-required) (argument-field blocker)
                              false)))
                      blockers)
          [id :joint])))))


(defn- attempt
  [package roots id field]
  (try
    {:package (cond
                (= :joint field) (snapshot/with-certified-owner package id roots)
                field (snapshot/with-migrated-forwarding package id field roots)
                :else (snapshot/with-migrated-bindings package [id] roots))}
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


(defn- ready-candidates
  [package candidates owner]
  (into #{owner}
        (keep (fn [[id entry]]
                (when (and (contains? candidates id)
                           (= :authored-source-equivalent (:verification entry)))
                  id)))
        (:closure-baselines package)))


(defn- certify
  "From the ordinary source plan, verify supported scoped binding rewrites
   using trusted native signatures. An owner transaction may also certify
   dependencies; wake all their waiters and never re-prove a completed owner.
   Cycles and unproved owners stay blocked. :blocked contains only sanitized
   diagnostics, not source payloads."
  [package entries roots]
  (let [by-id (into (sorted-map) (keep candidate) entries)]
    (loop [package package
           pending (into clojure.lang.PersistentQueue/EMPTY (keys by-id))
           waiting {}
           certified #{}
           blocked {}]
      (if-let [id (peek pending)]
        (if (contains? certified id)
          (recur package (pop pending) waiting certified blocked)
          (let [{next-package :package diagnostic :blocked dependency :dependency}
                (attempt package roots id (get by-id id))]
            (if next-package
              (let [ready (sort (remove certified (ready-candidates next-package by-id id)))]
                (recur next-package (into (pop pending) (mapcat waiting ready)) (apply dissoc waiting ready)
                       (into certified ready) (apply dissoc blocked ready)))
              (recur package (pop pending)
                     (if (and (contains? by-id dependency) (not (contains? certified dependency)))
                       (update waiting dependency (fnil conj []) id)
                       waiting)
                     certified (assoc blocked id diagnostic)))))
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
  [prepared originals {:keys [definitions baselines legacy-graph]} roots]
  (let [visible (into originals definitions)
        prepared (shared-forwarding prepared baselines)
        package (snapshot/package-index prepared visible)
        package (-> package
                    (assoc :closure-baselines baselines)
                    (cond-> legacy-graph (assoc :legacy-graph legacy-graph))
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
