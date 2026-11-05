(ns graphden.test-infra.type-contract
  "Dependency-complete, source-only slices for package type contract tests."
  (:require
    [graphden.executor.composition.deps :as deps]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]))


(defn prepared-closure
  "Prepare the loaded corpus, then retain roots selected by root? and every
   dependency. Use the compiler's identity-based dependency extraction so bare
   cross-module refs, namespace-local collisions and anonymous bindings resolve
   exactly as in package sync. Only the resulting closure is type-checked."
  [loaded root?]
  (let [root-ids (into #{} (comp (filter root?)
                                 (map #(ids/fn-id (:namespace %) (:name %))))
                       (:fn-defs loaded))
        extra-defs (into {} (map (fn [[n fd]] [n (assoc fd :name n)]))
                         (:base-fn-defs loaded))
        {:keys [fn-defs name->id]}
        (records-parse/prepare-module (:fn-defs loaded)
                                      (sync/compute-all-fn-name-ids loaded)
                                      extra-defs)
        ;; Reuse the production extractor instead of a second reference parser.
        ;; Conservatively include identity-only refs too, matching these tests'
        ;; existing topological-sort contract.
        dependencies (#'deps/build-dependency-graph fn-defs (constantly false) name->id)
        reachable (loop [pending (vec root-ids) seen #{}]
                    (if-let [id (peek pending)]
                      (if (contains? seen id)
                        (recur (pop pending) seen)
                        (recur (into (pop pending) (get dependencies id)) (conj seen id)))
                      seen))
        selected (filterv #(contains? reachable (ids/fn-id (:namespace %) (:name %))) fn-defs)]
    (when (empty? root-ids)
      (throw (ex-info "Type contract selected no roots" {})))
    {:fn-defs (mapv check/checker-view
                    (deps/topological-sort selected (constantly false) name->id))
     :name->id name->id
     :root-ids root-ids}))
